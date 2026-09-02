package forge.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.AiCostDecision;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.Cost;
import forge.game.cost.CostBehold;
import forge.game.cost.CostBeholdExile;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartWithList;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.cost.PaymentDecision;
import forge.game.keyword.Keyword;
import forge.game.replacement.ReplacementEffect;
import forge.game.trigger.WrappedAbility;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import forge.util.ITriggerEvent;

/**
 * Phase-B keystone: a Forge PlayerController for the HUMAN seat that is driven by
 * the EconomyDraft client instead of a local GUI.
 *
 * It extends {@link PlayerControllerAi} on purpose: PlayerController has ~60
 * abstract decision methods, and the AI already implements every one of them.
 * By extending the AI we (a) compile with zero stubs and (b) get a safe,
 * fully-playing fallback for every decision we haven't wired to the client yet.
 *
 * The plan: override the player-facing decision points one at a time
 * (chooseSpellAbilityToPlay / getAbilityToPlay for priority, declareAttackers,
 * declareBlockers, mulliganKeepHand, chooseTargets, mana payment, ...). Each
 * override serializes the decision to the client (via a BridgeChannel, TODO) and
 * blocks for the client's match_action reply; if the client says "auto/pass" it
 * defers to super (the AI), so partial wiring still yields a complete game.
 *
 * Injected with: player.setFirstController(new PlayerControllerBridge(game, player, lobby))
 * before Match.startGame().
 */
public class PlayerControllerBridge extends PlayerControllerAi {

    // "Pass Phase" / "Pass Turn": auto-pass my priority windows until the phase
    // (skipPhase) or my turn (skipTurn) ends, instead of stopping each window.
    private int skipTurn = -1;
    private String skipPhase = null;

    public PlayerControllerBridge(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
    }

    /**
     * Priority control point (PhaseHandler:1056). Push the live board to the
     * client and block for its action. Returning null = pass priority.
     *
     * B1: the driver auto-passes, so any reply -> pass. B2 will map a
     * "play_card"/"activate" reply to the matching SpellAbility here.
     */
    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        Player me = getPlayer();
        // The client dropped mid-match. Every further Channel.request() answers
        // "" -- i.e. pass / decline -- so the game would keep grinding with a
        // seat nobody is driving, and ForgeServer cannot accept the NEXT match
        // until this one ends. Concede at the first priority window instead:
        // the engine is free again within a turn, not whenever the AI happens
        // to finish the abandoned player off.
        if (Channel.isDead() && !me.conceded()) {
            me.concede();
            me.getGame().getAction().checkGameOverCondition();
            return null;
        }
        PhaseHandler ph = me.getGame().getPhaseHandler();
        String state = StateExporter.toJson(me.getGame().getView(), me);

        // Pass Turn: keep auto-passing until my turn ends (attacker declaration
        // still prompts via its own callback, so combat isn't skipped).
        if (skipTurn >= 0) {
            if (ph.getPlayerTurn() == me && ph.getTurn() == skipTurn) {
                Channel.request("{\"kind\":\"state\",\"state\":" + state + "}");
                return null;
            }
            skipTurn = -1;
        }
        // Pass Phase: keep auto-passing until this phase ends.
        if (skipPhase != null) {
            if (skipPhase.equals(String.valueOf(ph.getPhase()))) {
                Channel.request("{\"kind\":\"state\",\"state\":" + state + "}");
                return null;
            }
            skipPhase = null;
        }

        // Always push the current board so the client stays live (you see the
        // opponent develop, attack, etc. as it happens). Only STOP and wait for
        // input when it's actually your decision (see shouldPromptAtPriority);
        // otherwise push-and-continue so the game auto-advances.
        if (!shouldPromptAtPriority(me)) {
            Channel.request("{\"kind\":\"state\",\"state\":" + state + "}"); // fire-and-forget
            return null; // auto-pass
        }
        String reply = Channel.request("{\"kind\":\"priority\",\"state\":" + state + "}");

        String compact = reply.replaceAll("\\s", "");
        if (compact.contains("\"action\":\"pass_turn\"")) {
            skipTurn = ph.getTurn();     // skip the rest of my turn
            return null;
        }
        if (compact.contains("\"action\":\"pass_phase\"")) {
            skipPhase = String.valueOf(ph.getPhase());  // skip the rest of this phase
            return null;
        }
        if (compact.contains("\"action\":\"activate\"")) {
            Card chosen = findCard(me, parseInt(reply, "\"card\":"));
            boolean wantMana = compact.contains("\"mana\":true");
            // The client's "Cycle" item on a hand card. Cycling and every
            // typecycling variant (Basic landcycling, Mountaincycling, ...) are
            // activated abilities with ActivationZone$ Hand, so they'd otherwise
            // be indistinguishable from any other activated ability on the card
            // — ask for the cycling one by name rather than taking the first.
            boolean wantCycle = compact.contains("\"cycle\":true");
            if (chosen != null) {
                for (SpellAbility sa : chosen.getAllPossibleAbilities(me, true)) {
                    boolean isMana = sa.isManaAbility();
                    if (wantCycle ? sa.isCycling()
                            : wantMana ? isMana : (!isMana && sa.isActivatedAbility())) {
                        // Return it to the engine; our playChosenSpellAbility routes
                        // it through the human play path (taps a land for mana and
                        // floats it, or prompts for targets), like the real human.
                        return Lists.newArrayList(sa);
                    }
                }
            }
            return null;
        }
        if (compact.contains("\"action\":\"play\"")) {
            int cardId = parseInt(reply, "\"card\":");
            Card chosen = findCard(me, cardId);
            if (chosen != null) {
                // Currently-playable abilities for this card (land play, cast, ...).
                List<SpellAbility> abs = chosen.getAllPossibleAbilities(me, true);
                if (!abs.isEmpty()) {
                    // Optional ability index; default 0 (e.g. the land-play / main cast).
                    int idx = parseInt(reply, "\"ability\":");
                    if (idx < 0 || idx >= abs.size()) idx = 0;
                    return Lists.newArrayList(abs.get(idx));
                }
            }
        }
        return null; // pass priority
    }

    /**
     * WHICH version of a spell to cast, when the card offers more than one.
     * PlaySpellAbility.chooseOptionalAdditionalCosts builds one SpellAbility per
     * "AlternateAdditionalCost" branch and asks the controller to pick; the
     * engine also routes cast-from-elsewhere effects (PlayEffect, DiscoverEffect,
     * ChangeZoneEffect) through here.
     *
     * PlayerControllerAi just returns abilities.get(0), so the player was never
     * asked. Kinsbaile Aspirant ("behold a Kithkin or pay {2}") always silently
     * took branch 0 — the behold — and so appeared to cast for a bare {W} with
     * no additional cost at all.
     *
     * One option means no decision, so this only prompts when there is a real
     * choice; an empty reply keeps the old behaviour rather than fizzling the cast.
     */
    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities,
                                         ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        List<String> names = new ArrayList<>();
        for (SpellAbility sa : abilities) {
            names.add(abilityLabel(sa));
        }
        String host = hostCard != null ? hostCard.getName() : "Spell";
        List<Integer> sel = promptIndices(host + ": which cost do you want to pay?",
                names, 1, 1, false, "choose");
        if (sel.isEmpty()) {
            return abilities.get(0);
        }
        return abilities.get(sel.get(0));
    }

    /** Label for one branch of {@link #getAbilityToPlay}: its cost, then its text. */
    private static String abilityLabel(SpellAbility sa) {
        String desc = sa.toUnsuppressedString();
        desc = desc == null ? "" : desc.trim();
        // getAdditionalCostSpell appends "(Additional cost: X)" to tell the
        // branches apart; the cost string below already says it, so drop it.
        int extra = desc.lastIndexOf("(Additional cost:");
        if (extra >= 0) {
            desc = desc.substring(0, extra).trim();
        }
        Cost cost = sa.getPayCosts();
        String costStr = cost == null ? "" : cost.toSimpleString();
        if (costStr.isEmpty()) {
            return desc.isEmpty() ? String.valueOf(sa) : desc;
        }
        return desc.isEmpty() ? costStr : costStr + " - " + desc;
    }

    /**
     * Play the chosen spell/ability through the engine's REAL human path
     * (exactly as {@code PlayerControllerHuman.playChosenSpellAbility} does)
     * instead of the AI's ComputerUtil path. This makes everything behave like a
     * human: tapping a land for mana actually taps it and floats mana; a mana
     * ability resolves immediately; a targeted spell prompts YOU for its targets
     * ({@link #chooseTargetsFor}); X / modes hit our chooseNumber / chooseMode
     * overrides. Lands resolve directly inside playSpellAbility.
     */
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa == null) return false;
        try {
            return PlaySpellAbility.playSpellAbility(this, getPlayer(), sa);
        } catch (Exception e) {
            System.err.println("[bridge] play failed for "
                + (sa.getHostCard() != null ? sa.getHostCard().getName() : "?") + ": " + e);
            return false;
        }
    }

    /**
     * Put YOUR triggered abilities on the stack through the engine's real human
     * path, for the same reason {@link #playChosenSpellAbility} does it for cast
     * spells. This is the hook every non-static trigger of yours goes through
     * (MagicStack.chooseOrderOfSimultaneousStackEntry).
     *
     * PlayerControllerAi picks a trigger's targets with AI heuristics and pays
     * its costs without asking, so a trigger meant to ask YOU something never
     * did. Two live examples: Rooftop Percher ("exile up to two target cards
     * from graveyards") is TargetMin 0, so the AI declined to target and the
     * trigger exiled nothing; Dream Seizer ("you may blight 1") had its optional
     * cost auto-paid on a creature of the AI's choosing, so {@link
     * #confirmPayment} never fired.
     *
     * PlaySpellAbility.playSpellAbility routes both back through our own
     * {@link #chooseTargetsFor} / {@link #confirmPayment} overrides, and only
     * prompts where the ability actually offers a decision — triggers with
     * nothing to choose still go on the stack silently. Copied abilities keep
     * the AI path: they are not the player's decision to make here.
     */
    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        List<SpellAbility> ordered = orderSimultaneousSa(activePlayerSAs);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            SpellAbility next = ordered.get(i);
            if (next.isTrigger() && !next.isCopied()) {
                try {
                    PlaySpellAbility.playSpellAbility(this, getPlayer(), next);
                    continue;
                } catch (Exception e) {
                    System.err.println("[bridge] trigger failed for "
                        + (next.getHostCard() != null ? next.getHostCard().getName() : "?")
                        + ": " + e);
                }
            }
            super.orderAndPlaySimultaneousSa(Lists.newArrayList(next));
        }
    }

    /**
     * The RESOLUTION half of the same story. The hook above runs when a trigger
     * goes on the stack, which is where its targets are chosen; this one runs
     * when it resolves, which is where its costs are paid — WrappedAbility.resolve
     * calls it. Both are needed: Dream Seizer's "you may blight 1" is an optional
     * cost, so without this the AI pays it silently on a creature of its choosing
     * and {@link #confirmPayment} is never reached.
     */
    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        try {
            PlaySpellAbility.playSpellAbilityNoStack(this, getPlayer(), effectSA, !canSetupTargets);
        } catch (Exception e) {
            System.err.println("[bridge] resolve failed for "
                + (effectSA != null && effectSA.getHostCard() != null
                   ? effectSA.getHostCard().getName() : "?") + ": " + e);
            super.playSpellAbilityNoStack(effectSA, canSetupTargets);
        }
    }

    /**
     * Cost payment decisions. The bridge inherits {@link AiCostDecision} for all
     * ~30 cost types; this subclass takes over only the ones where the player is
     * meant to choose, and leaves the rest on the AI's (sane) defaults.
     */
    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player p, SpellAbility ability,
                                                      boolean effect, String prompt) {
        return new BridgeCostDecision(p, ability, effect);
    }

    private final class BridgeCostDecision extends AiCostDecision {
        BridgeCostDecision(Player p, SpellAbility sa, boolean effect) {
            super(p, sa, effect);
        }

        /**
         * "Put a counter on a permanent" as a COST — which is how Forge models
         * blight ("To blight 1, put a -1/-1 counter on a creature you control";
         * CostBlight delegates here). The AI picked its worst creature and paid
         * without asking, so Dream Seizer's "you may blight 1" both chose for
         * you and gave you no way to decline.
         *
         * The prompt is optional: choosing nothing returns null, which Forge
         * reads as "cost not paid" — the correct way to decline a trigger whose
         * cost is its own "may". payCostFromSource has no choice in it, so that
         * stays on the AI path.
         */
        @Override
        public PaymentDecision visit(CostPutCounter cost) {
            if (cost.payCostFromSource()) {
                return super.visit(cost);
            }
            CardCollection options = CardLists.getValidCards(
                    getPlayer().getGame().getCardsIn(ZoneType.Battlefield),
                    cost.getType().split(";"), getPlayer(), ability.getHostCard(), ability);
            options = CardLists.filter(options, CardPredicates.canReceiveCounters(cost.getCounter()));
            if (options.isEmpty()) {
                return super.visit(cost);
            }
            String host = ability.getHostCard() != null ? ability.getHostCard().getName() : "this";
            List<String> names = new ArrayList<>();
            for (Card c : options) {
                names.add(c.getName());
            }
            // getName() is the printed form ("-1/-1"); toString() would show
            // Forge's internal enum name ("M1M1").
            String counter = cost.getCounter() != null ? cost.getCounter().getName() : "a counter";
            List<Integer> sel = promptIndices(
                    host + ": put " + counter + " on which permanent? (none = decline)",
                    names, 0, 1, true, "choose");
            if (sel.isEmpty()) {
                return null;   // declined -> the cost goes unpaid
            }
            int idx = sel.get(0);
            if (idx < 0 || idx >= options.size()) {
                return super.visit(cost);
            }
            return PaymentDecision.card(options.get(idx));
        }

        /**
         * "Behold a Kithkin" as a COST — choose a Kithkin you control or reveal
         * one from your hand (CostBehold reveals from Hand,Battlefield;
         * CostBeholdExile is the same choice followed by an exile).
         *
         * AiCostDecision picked getBestCreatureAI() without asking, which is how
         * Kinsbaile Aspirant paid its additional cost invisibly. It also returns
         * a null card when the only valid choices are noncreature, so prompting
         * over the whole valid list fixes that too.
         */
        @Override
        public PaymentDecision visit(CostBehold cost) {
            return beholdDecision(cost);
        }

        @Override
        public PaymentDecision visit(CostBeholdExile cost) {
            return beholdDecision(cost);
        }

        private PaymentDecision beholdDecision(CostBehold cost) {
            int num = cost.getAbilityAmount(ability);
            CardCollection options = CardLists.getValidCards(
                    getPlayer().getCardsIn(cost.getRevealFrom()),
                    cost.getType().split(";"), getPlayer(), ability.getHostCard(), ability);
            if (options.size() < num) {
                return null;   // can't pay
            }
            if (options.size() == num) {
                return PaymentDecision.card(options);   // no decision to make
            }
            List<String> names = new ArrayList<>();
            for (Card c : options) {
                names.add(c.getName());
            }
            String host = ability.getHostCard() != null ? ability.getHostCard().getName() : "this";
            List<Integer> sel = promptIndices(
                    host + ": " + cost.toString() + " - choose which",
                    names, num, num, false, "choose");
            CardCollection picked = new CardCollection();
            for (int idx : sel) {
                picked.add(options.get(idx));
            }
            for (int k = 0; k < options.size() && picked.size() < num; k++) {
                if (!picked.contains(options.get(k))) {
                    picked.add(options.get(k));
                }
            }
            return PaymentDecision.card(picked);
        }

        /**
         * Shared shape for every "which of my cards pays this?" cost: gather the
         * legal payers, let the client pick exactly *count*, and treat anything
         * short of that as declining. Returning null leaves the cost unpaid,
         * which aborts the activation — the same thing cancelling the payment
         * dialog does in Forge's own client.
         */
        private PaymentDecision pickPayers(String verb, CardCollectionView options, int count) {
            if (options == null || count <= 0 || options.size() < count) {
                return null;
            }
            List<Card> pool = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Card c : options) {
                pool.add(c);
                names.add(c.getName());
            }
            if (pool.size() == count) {
                return PaymentDecision.card(new CardCollection(pool));   // no decision to make
            }
            String host = ability != null && ability.getHostCard() != null
                    ? ability.getHostCard().getName() : "this";
            List<Integer> sel = promptIndices(
                    host + ": choose " + count + " to " + verb + " (none = decline)",
                    names, 0, count, true, "choose");
            if (sel.size() != count) {
                return null;
            }
            CardCollection chosen = new CardCollection();
            for (int idx : sel) {
                chosen.add(pool.get(idx));
            }
            return PaymentDecision.card(chosen);
        }

        /**
         * "Sacrifice a creature:" — by a wide margin the most common card-choosing
         * activation cost in the pool (87 occurrences across the six sets), and the
         * AI was picking its worst permanent every time without asking.
         *
         * The exotic forms stay on the AI path deliberately: paying from the source
         * or the original host has no choice in it, "All" has no choice either, and
         * +WithDifferentNames needs an iterated narrowing prompt that is not worth
         * reproducing until a card in the pool actually uses it.
         */
        @Override
        public PaymentDecision visit(CostSacrifice cost) {
            String type = cost.getType();
            if (cost.payCostFromSource() || "OriginalHost".equals(type)
                    || "All".equals(cost.getAmount()) || type.contains("+WithDifferentNames")) {
                return super.visit(cost);
            }
            CardCollectionView list = CardLists.filter(
                    getPlayer().getCardsIn(ZoneType.Battlefield),
                    CardPredicates.canBeSacrificedBy(ability, isEffect()));
            list = CardLists.getValidCards(list, type.split(";"), getPlayer(),
                    ability.getHostCard(), ability);
            PaymentDecision pd = pickPayers("sacrifice", list, cost.getAbilityAmount(ability));
            return pd != null ? pd : super.visit(cost);
        }

        /** "Discard a card:" as an activation cost — which card is the player's. */
        @Override
        public PaymentDecision visit(CostDiscard cost) {
            String type = cost.getType();
            if (cost.payCostFromSource() || "Hand".equals(type) || "LastDrawn".equals(type)
                    || "Random".equals(type) || type.contains("WithDifferentNames")
                    || type.contains("WithSameName")) {
                return super.visit(cost);
            }
            CardCollectionView hand = CardLists.getValidCards(
                    getPlayer().getCardsIn(ZoneType.Hand), type.split(";"), getPlayer(),
                    ability.getHostCard(), ability);
            PaymentDecision pd = pickPayers("discard", hand, cost.getAbilityAmount(ability));
            return pd != null ? pd : super.visit(cost);
        }

        /** "Tap N untapped creatures you control:" — crew included. */
        @Override
        public PaymentDecision visit(CostTapType cost) {
            String type = cost.getType();
            if ("OriginalHost".equals(type) || "Any".equals(cost.getAmount())
                    || type.contains(".sharesCreatureTypeWith")
                    || type.contains("+withTotalPowerGE")) {
                return super.visit(cost);
            }
            CardCollection list = CardLists.getValidCards(
                    getPlayer().getCardsIn(ZoneType.Battlefield), type.split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            list = CardLists.filter(list, ability.isCrew()
                    ? CardPredicates.CAN_CREW : CardPredicates.CAN_TAP);
            PaymentDecision pd = pickPayers(ability.isCrew() ? "crew with" : "tap",
                    list, cost.getAbilityAmount(ability));
            return pd != null ? pd : super.visit(cost);
        }

        /** "Exile a card from your graveyard:" and friends. */
        @Override
        public PaymentDecision visit(CostExile cost) {
            String type = cost.getType();
            if (cost.payCostFromSource() || "OriginalHost".equals(type) || "All".equals(type)
                    || type.contains("+with") || type.contains("FromTopGrave")) {
                return super.visit(cost);
            }
            CardCollection list = cost.zoneRestriction != 1
                    ? new CardCollection(getPlayer().getGame().getCardsIn(cost.from))
                    : new CardCollection(getPlayer().getCardsIn(cost.from));
            list = CardLists.getValidCards(list, type.split(";"), getPlayer(),
                    ability.getHostCard(), ability);
            list = CardLists.filter(list, CardPredicates.canExiledBy(ability, isEffect()));
            PaymentDecision pd = pickPayers("exile", list, cost.getAbilityAmount(ability));
            return pd != null ? pd : super.visit(cost);
        }
    }

    /**
     * Static ("doesn't use the stack") triggers, the other half of the split
     * above — TriggerHandler calls this one directly. Same reasoning.
     */
    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        try {
            return PlaySpellAbility.playSpellAbilityNoStack(this, getPlayer(), wrapperAbility, false);
        } catch (Exception e) {
            System.err.println("[bridge] static trigger failed for "
                + (host != null ? host.getName() : "?") + ": " + e);
            return super.playTrigger(host, wrapperAbility, isMandatory);
        }
    }

    /**
     * Cast-time targeting. PlaySpellAbility.setupTargets() calls this while a
     * spell/ability is on its way to the stack. Walk the ability chain and, for
     * each targeted part, gather legal candidates and let the client pick
     * (min..max). Returning false cancels the cast.
     */
    @Override
    public boolean chooseTargetsFor(SpellAbility ability) {
        for (SpellAbility sa = ability; sa != null; sa = sa.getSubAbility()) {
            if (sa.usesTargeting() && !pickTargetsForSA(sa)) {
                return false;
            }
        }
        return true;
    }

    /** Prompt the client for one targeted ability's targets; assign the picks. */
    private boolean pickTargetsForSA(SpellAbility sa) {
        sa.resetTargets();
        List<GameEntity> candidates = sa.getTargetRestrictions().getAllCandidates(sa);
        int min = sa.getMinTargets();
        int max = sa.getMaxTargets();
        if (max <= 0) max = candidates.size();
        if (candidates.isEmpty()) {
            return min == 0; // no legal targets: only OK if targeting is optional
        }
        List<String> names = new ArrayList<>();
        for (GameEntity ge : candidates) {
            names.add(describeTarget(ge));
        }
        int hi = Math.min(max, candidates.size());
        String host = sa.getHostCard() != null ? sa.getHostCard().getName() : "ability";
        List<Integer> sel = promptIndices(
            "Choose target" + (hi > 1 ? "s" : "") + " for " + host,
            names, min, hi, min == 0, "target_select");
        for (int idx : sel) {
            if (idx >= 0 && idx < candidates.size()) {
                sa.getTargets().add(candidates.get(idx));
            }
        }
        return sa.isTargetNumberValid();
    }

    /** Human-readable label for a target candidate (creature/permanent or player). */
    private static String describeTarget(GameEntity ge) {
        if (ge instanceof Card) {
            Card c = (Card) ge;
            Player ctrl = c.getController();
            return c.getName() + (ctrl != null ? " [" + ctrl.getName() + "]" : "");
        }
        if (ge instanceof Player) {
            return "Player: " + ((Player) ge).getName();
        }
        return String.valueOf(ge);
    }

    /**
     * Mana / effect color choice. Forge calls this when a source can produce more
     * than one color (e.g. a land that taps for any color) or an effect asks the
     * player to name a color. The AI auto-picks; we prompt the client's color
     * picker instead and return the chosen color's byte mask.
     */
    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        return promptColor(message, colors, false);
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return promptColor(message, colors, true);
    }

    /** Ask the client to pick one color from the allowed set; returns its mask. */
    private byte promptColor(String message, ColorSet colors, boolean allowColorless) {
        List<String> syms = new ArrayList<>();
        List<Byte> vals = new ArrayList<>();
        boolean any = (colors == null);
        if (any || colors.hasWhite()) { syms.add("W"); vals.add(MagicColor.WHITE); }
        if (any || colors.hasBlue())  { syms.add("U"); vals.add(MagicColor.BLUE); }
        if (any || colors.hasBlack()) { syms.add("B"); vals.add(MagicColor.BLACK); }
        if (any || colors.hasRed())   { syms.add("R"); vals.add(MagicColor.RED); }
        if (any || colors.hasGreen()) { syms.add("G"); vals.add(MagicColor.GREEN); }
        if (allowColorless)           { syms.add("C"); vals.add(MagicColor.COLORLESS); }
        if (vals.isEmpty()) { return MagicColor.WHITE; }
        if (vals.size() == 1) { return vals.get(0); }   // no real choice; skip the prompt

        StringBuilder opts = new StringBuilder("[");
        for (int i = 0; i < syms.size(); i++) {
            if (i > 0) opts.append(',');
            opts.append("{\"id\":\"").append(syms.get(i)).append("\",\"name\":\"")
                .append(syms.get(i)).append("\"}");
        }
        opts.append(']');
        String req = "{\"kind\":\"prompt\",\"prompt\":{"
            + "\"message\":\"" + escName(message == null ? "Choose a color" : message) + "\","
            + "\"options\":" + opts + ",\"multi\":false,\"min\":1,\"max\":1,"
            + "\"optional\":false,\"prompt_type\":\"color_select\"},"
            + "\"state\":" + stateJson() + "}";
        String reply = Channel.request(req);

        String compact = reply.replaceAll("\\s", "").toUpperCase();
        int i = compact.indexOf("\"SELECTION\":[");
        if (i >= 0) {
            int end = compact.indexOf(']', i);
            String inner = compact.substring(i + 13, end < 0 ? compact.length() : end);
            for (int k = 0; k < syms.size(); k++) {
                if (inner.contains("\"" + syms.get(k) + "\"")) {
                    return vals.get(k);
                }
            }
        }
        return vals.get(0); // default to first allowed color on empty/invalid reply
    }

    /**
     * When to stop for the human, matching the original EconomyDraft pacing:
     *   1. Your own main phase (empty stack) -> develop.
     *   2. Anything on the stack -> stop if you could respond. This is checked
     *      FIRST, so the opponent casting a spell always gives you a window,
     *      whatever phase it happens in (it used to lose to the combat branch
     *      below: a trick cast in beginning-of-combat, before attackers exist,
     *      was auto-passed and resolved without asking you).
     *   3. The opponent's turn with an EMPTY stack -> stop only at the two
     *      windows a human actually holds mana for: beginning of combat (your
     *      last chance to act before they attack) and their end step. Every
     *      other empty window on their turn (untap/upkeep/draw/main/cleanup)
     *      auto-passes so the game still advances on its own.
     *   4. During combat -> only if you actually have an instant-speed play
     *      (a combat trick), so we don't pause for a pointless pass.
     * All the "stop" cases on the opponent's turn are gated on hasLegalPlay, so
     * a hand with nothing castable never slows the game down.
     */
    private static boolean shouldPromptAtPriority(Player me) {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        PhaseType phase = ph.getPhase();
        boolean stackEmpty = game.getStack().isEmpty();
        boolean myTurn = ph.getPlayerTurn() == me;
        if (myTurn && phase != null && phase.isMain() && stackEmpty) {
            return true; // your main phase
        }
        // Something on the stack you could respond to (theirs or your own).
        if (!stackEmpty) {
            return hasLegalPlay(me);
        }
        if (phase == null) {
            return false;
        }
        // Combat: only stop if combat is actually happening (attackers declared)
        // AND you have an instant-speed play — otherwise there's nothing to do,
        // so don't pause (this was stopping every combat step with no creatures).
        // Exception: their beginning of combat, where nothing is declared yet but
        // it is the last window to kill a creature before it attacks you.
        if (phase.name().startsWith("COMBAT")) {
            if (!myTurn && phase == PhaseType.COMBAT_BEGIN) {
                return hasLegalPlay(me);
            }
            Combat combat = game.getCombat();
            boolean combatOn = combat != null && !combat.getAttackers().isEmpty();
            return combatOn && hasLegalPlay(me);
        }
        // Their end step — the classic "hold up an instant" window.
        if (!myTurn && phase == PhaseType.END_OF_TURN) {
            return hasLegalPlay(me);
        }
        return false;
    }

    /** True if the player has any non-mana ability playable right now. */
    private static boolean hasLegalPlay(Player me) {
        for (ZoneType zt : PRIORITY_ZONES) {
            for (Card c : me.getCardsIn(zt)) {
                if (canPlaySomething(me, c)) {
                    return true;
                }
            }
        }
        // Only the TOP library card can be playable in practice ("you may cast
        // artifact spells from the top of your library"), and checking one card
        // is cheap where walking the whole library every priority would not be.
        for (Card c : me.getCardsIn(ZoneType.Library, 1)) {
            if (canPlaySomething(me, c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canPlaySomething(Player me, Card c) {
        for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
            if (!sa.isManaAbility()) {
                return true;
            }
        }
        return false;
    }

    private static final ZoneType[] PRIORITY_ZONES = {
        ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command,
    };

    // Where a clicked card can be looked up. Library is here but NOT in
    // PRIORITY_ZONES: finding a card by id is a cheap scan, deciding whether
    // each one is playable is not.
    private static final ZoneType[] FIND_ZONES = {
        ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
        ZoneType.Command, ZoneType.Library,
    };

    /** Ask the client to Keep or Mulligan. Reply {"keep":true/false}. */
    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        String state = StateExporter.toJson(getPlayer().getGame().getView(), getPlayer());
        String reply = Channel.request("{\"kind\":\"mulligan\",\"state\":" + state + "}");
        // Default to keep on anything unexpected.
        return !reply.replaceAll("\\s", "").contains("\"keep\":false");
    }

    /** London mulligan: ask the client which N cards to put on the bottom. */
    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (cardsToReturn <= 0) {
            return new CardCollection();
        }
        StringBuilder cards = new StringBuilder("[");
        boolean first = true;
        for (Card c : hand) {
            if (!first) cards.append(',');
            first = false;
            cards.append("{\"id\":").append(c.getId()).append(",\"name\":\"")
                 .append(escName(c.getName())).append("\"}");
        }
        cards.append(']');
        String reply = Channel.request("{\"kind\":\"mulligan_bottom\",\"count\":" + cardsToReturn
                + ",\"cards\":" + cards + ",\"state\":" + stateJson() + "}");
        String compact = reply.replaceAll("\\s", "");
        CardCollection chosen = new CardCollection();
        for (Card c : hand) {
            if (inArray(compact, "bottom", c.getId())) {
                chosen.add(c);
            }
        }
        // Must return exactly cardsToReturn: pad from the rest of the hand if the
        // player under-selected, trim if they over-selected (mirrors the original).
        for (Card c : hand) {
            if (chosen.size() >= cardsToReturn) break;
            if (!chosen.contains(c)) chosen.add(c);
        }
        while (chosen.size() > cardsToReturn) {
            chosen.remove(chosen.size() - 1);
        }
        return chosen;
    }

    private static String escName(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ─── Generic client choices ──────────────────────────────────────────────
    // Route Forge decision points to the client's prompt UI. Options are sent by
    // INDEX (so any option type works); the client returns the chosen indices.

    /**
     * The board, for a request that would otherwise carry none.
     *
     * The bridge's Feed is driven entirely by the game-log tail inside a
     * request's "state" (forge_match_sync.flush_log), so a request without one
     * forwards nothing. Prompts used to be exactly that: the client got a modal
     * while every line explaining WHY sat unflushed until the answer came back.
     * A board wipe therefore read as "order these two triggers" with a still
     * intact battlefield, then six creatures vanishing at once afterwards.
     */
    private String stateJson() {
        Player me = getPlayer();
        return StateExporter.toJson(me.getGame().getView(), me);
    }

    private List<Integer> promptIndices(String title, List<String> names, int min, int max,
                                        boolean optional, String promptType) {
        StringBuilder opts = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) opts.append(',');
            opts.append("{\"id\":\"").append(i).append("\",\"name\":\"").append(escName(names.get(i))).append("\"}");
        }
        opts.append(']');
        String req = "{\"kind\":\"prompt\",\"prompt\":{"
                + "\"message\":\"" + escName(title == null ? "Choose" : title) + "\","
                + "\"options\":" + opts + ","
                + "\"multi\":" + (max > 1) + ",\"min\":" + min + ",\"max\":" + max + ","
                + "\"optional\":" + optional + ",\"prompt_type\":\"" + promptType + "\"},"
                + "\"state\":" + stateJson() + "}";
        String reply = Channel.request(req);
        List<Integer> out = new ArrayList<>();
        String compact = reply.replaceAll("\\s", "");
        int i = compact.indexOf("\"selection\":[");
        if (i >= 0) {
            int end = compact.indexOf(']', i);
            if (end > i) {
                for (String tok : compact.substring(i + 13, end).split(",")) {
                    String d = tok.replaceAll("[^0-9]", "");
                    if (!d.isEmpty()) {
                        int v = Integer.parseInt(d);
                        if (v >= 0 && v < names.size() && !out.contains(v)) out.add(v);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList,
            DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional,
            Player relatedPlayer, Map<String, Object> params) {
        List<T> opts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (T t : optionList) { opts.add(t); names.add(String.valueOf(t)); }
        if (opts.isEmpty()) return null;
        if (opts.size() == 1 && !isOptional) return opts.get(0);
        List<Integer> sel = promptIndices(title, names, isOptional ? 0 : 1, 1, isOptional, "choose");
        if (sel.isEmpty()) return isOptional ? null : opts.get(0);
        return opts.get(sel.get(0));
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList,
            int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title,
            Player relatedPlayer, Map<String, Object> params) {
        List<T> opts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (T t : optionList) { opts.add(t); names.add(String.valueOf(t)); }
        List<T> result = new ArrayList<>();
        if (opts.isEmpty()) return result;
        List<Integer> sel = promptIndices(title, names, min, Math.min(max, opts.size()), min == 0, "choose");
        for (int idx : sel) result.add(opts.get(idx));
        for (int k = 0; k < opts.size() && result.size() < min; k++) {
            if (!result.contains(opts.get(k))) result.add(opts.get(k));
        }
        return result;
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal,
            String selectPrompt, boolean isOptional, Player decider) {
        if (fetchList == null || fetchList.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (Card c : fetchList) names.add(c.getName());
        List<Integer> sel = promptIndices(selectPrompt, names, isOptional ? 0 : 1, 1, isOptional, "choose");
        if (sel.isEmpty()) return isOptional ? null : fetchList.get(0);
        return fetchList.get(sel.get(0));
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal,
            String selectPrompt, Player decider) {
        List<Card> result = new ArrayList<>();
        if (fetchList == null || fetchList.isEmpty()) return result;
        List<String> names = new ArrayList<>();
        for (Card c : fetchList) names.add(c.getName());
        List<Integer> sel = promptIndices(selectPrompt, names, min, Math.min(max, fetchList.size()), min == 0, "choose");
        for (int idx : sel) result.add(fetchList.get(idx));
        return result;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        List<String> names = new ArrayList<>();
        if (options != null && !options.isEmpty()) names.addAll(options);
        else { names.add("Yes"); names.add("No"); }
        List<Integer> sel = promptIndices(message, names, 1, 1, false, "confirm");
        return !sel.isEmpty() && sel.get(0) == 0;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (min >= max || max - min > 50) return super.chooseNumber(sa, title, min, max);
        List<String> names = new ArrayList<>();
        for (int n = min; n <= max; n++) names.add(String.valueOf(n));
        List<Integer> sel = promptIndices(title, names, 1, 1, false, "number");
        return sel.isEmpty() ? min : min + sel.get(0);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (values == null || values.isEmpty()) return super.chooseNumber(sa, title, values, relatedPlayer);
        List<String> names = new ArrayList<>();
        for (int v : values) names.add(String.valueOf(v));
        List<Integer> sel = promptIndices(title, names, 1, 1, false, "number");
        return sel.isEmpty() ? values.get(0) : values.get(sel.get(0));
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
            int min, int num, boolean allowRepeat) {
        if (possible == null || possible.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (AbilitySub s : possible) {
            String d = s.getDescription();
            names.add(d == null || d.isEmpty() ? String.valueOf(s) : d);
        }
        List<Integer> sel = promptIndices("Choose mode", names, min, Math.min(num, possible.size()), false, "choose");
        List<AbilitySub> result = new ArrayList<>();
        for (int idx : sel) result.add(possible.get(idx));
        for (int k = 0; k < possible.size() && result.size() < min; k++) {
            if (!result.contains(possible.get(k))) result.add(possible.get(k));
        }
        return result;
    }

    // ---- Additional human decision points (were silently defaulting to the AI) ----

    /** Optional ("may") triggered ability: ask the human whether to use it. */
    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        SpellAbility sa = wrapper == null ? null : wrapper.getWrappedAbility();
        // Triggers with a payable cost are declined by simply not paying — let the
        // engine handle those so we don't double-prompt.
        if (sa != null && sa.hasParam("Cost") && !"0".equals(sa.getParam("Cost"))) {
            return true;
        }
        String host = (sa != null && sa.getHostCard() != null) ? sa.getHostCard().getName() : "ability";
        return yesNo("Use " + host + "'s triggered ability?");
    }

    /** Optional cost during resolution ("Do you want to pay ...?"). */
    @Override
    public boolean confirmPayment(CostPart costPart, String question, SpellAbility sa) {
        String host = (sa != null && sa.getHostCard() != null) ? sa.getHostCard().getName() : "this";
        String cost = costPart != null ? costPart.toString() : "";
        return yesNo("Pay " + (cost.isEmpty() ? "the cost" : cost) + " for " + host + "?");
    }

    /**
     * "Unless" costs paid while an ability resolves. This is the shape behind
     * every rummage/loot card: Tweeze's "You may discard a card. If you do,
     * draw a card." is scripted as
     * {@code DB$ Draw | UnlessCost$ Discard<1/Card> | UnlessPayer$ You | UnlessSwitched$ True},
     * and AbilityUtils.handleUnlessCost routes the whole decision through here.
     *
     * PlayerControllerAi's version asks {@code willPayUnlessCost} and then pays
     * with an AiCostDecision, so the human was never offered the "may" and never
     * picked which card to pitch — the AI decided both, silently, and the draw
     * just happened. Use the same resolve-time payment path PlayerControllerHuman
     * uses; it calls back into our confirmPayment / chooseCardsForCost overrides.
     */
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid,
            FCollectionView<Player> allPayers) {
        String prompt = null;
        if (sa != null && sa.isKeyword(Keyword.ECHO)) {
            prompt = "Pay echo";
        } else if (sa != null && sa.isKeyword(Keyword.CUMULATIVE_UPKEEP)) {
            prompt = "Cumulative upkeep for " + sa.getHostCard();
        }
        try {
            return PlaySpellAbility.payCostDuringAbilityResolve(this, getPlayer(), cost, sa, prompt);
        } catch (Exception e) {
            System.err.println("[bridge] unless-cost payment failed for "
                + (sa != null && sa.getHostCard() != null ? sa.getHostCard().getName() : "?")
                + ": " + e);
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }
    }

    /**
     * Which cards pay a card-based cost (discard / exile / return / reveal / tap)
     * when it is paid during resolution. PlayerControllerAi's version is an
     * {@code assert(false)} placeholder — the AI never pays this way — so this
     * override is mandatory for anything reaching
     * {@link #payCostToPreventEffect} above.
     *
     * Returning null means "not paid", which is how an optional cost is declined:
     * for Tweeze, cancelling the discard prompt is what makes you skip the draw.
     */
    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa,
            CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        if (optionList == null || optionList.size() < amount) {
            return null;
        }
        List<Card> pool = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Card c : optionList) {
            pool.add(c);
            names.add(c.getName());
        }
        String host = (sa != null && sa.getHostCard() != null) ? sa.getHostCard().getName() : "this";
        // Forge's own lblSelectNSpecifyTypeCardsToAction is "Select %d {0} card(s)
        // to {1}" — the %d is never substituted by its MessageFormat localizer, so
        // fill it in here rather than showing the player a raw format specifier.
        String action = (prompt == null || prompt.isEmpty())
                ? "choose " + amount + " card(s)"
                : prompt.replace("%d", String.valueOf(amount)).replaceAll("\s{2,}", " ").trim();
        String message = host + ": " + action + (isOptional ? " (none = decline)" : "");
        List<Integer> sel = promptIndices(message, names, isOptional ? 0 : amount, amount,
                isOptional, "choose");
        if (sel.size() != amount) {
            return null;   // cancelled / short pick -> cost goes unpaid
        }
        CardCollection chosen = new CardCollection();
        for (int idx : sel) {
            chosen.add(pool.get(idx));
        }
        return chosen;
    }

    /** Announce X (and similar numeric announcements) at cast time. */
    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        try {
            Cost cost = ability.getPayCosts();
            if ("X".equals(announce) && cost != null) {
                Integer costX = cost.getMaxForNonManaX(ability, getPlayer(), false);
                if (costX != null) max = Math.min(max, costX);
            }
            if (min > max) return null;
            if (min == max) return min;
            String host = ability.getHostCard() != null ? ability.getHostCard().getName() : "";
            return chooseNumber(ability, "Choose " + announce + " for " + host, min, max);
        } catch (Exception e) {
            return super.announceRequirements(ability, min, max, announce);
        }
    }

    /**
     * Optional ADDITIONAL costs, chosen as the spell goes on the stack: kicker,
     * and ECL's blight riders — Requiting Hex is "As an additional cost to cast
     * this spell, you may blight 1 ... If this spell's additional cost was paid,
     * you gain 2 life."
     *
     * PlayerControllerAi answers this with SpellAbilityAi.chooseOptionalCosts,
     * which pays whatever its own heuristics like, so the human was never asked.
     * Requiting Hex then resolved with the cost unpaid and no life gained, which
     * is the correct RULE for an unpaid cost but was never the player's decision.
     * Declining is an empty list, and paying routes the cost itself through
     * BridgeCostDecision (blight is a CostPutCounter), so the player also picks
     * which creature takes the -1/-1.
     */
    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
            List<OptionalCostValue> optionalCostValues) {
        if (optionalCostValues == null || optionalCostValues.isEmpty()) {
            return Lists.newArrayList();
        }
        List<String> names = new ArrayList<>();
        for (OptionalCostValue v : optionalCostValues) {
            names.add(v.toString());
        }
        String host = chosen != null && chosen.getHostCard() != null
                ? chosen.getHostCard().getName() : "this spell";
        List<Integer> sel = promptIndices(
                host + ": pay optional additional cost? (none = decline)",
                names, 0, names.size(), true, "choose");
        List<OptionalCostValue> chosenCosts = new ArrayList<>();
        for (int idx : sel) {
            if (idx >= 0 && idx < optionalCostValues.size()) {
                chosenCosts.add(optionalCostValues.get(idx));
            }
        }
        return chosenCosts;
    }

    /** Cleanup step: choose which cards to discard down to max hand size. */
    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int nDiscard) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        return chooseCardsFrom("Discard down to max hand size — choose " + nDiscard + " to discard",
            hand, nDiscard, nDiscard);
    }

    /** Effect-driven discard ("discard N cards"). */
    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
            CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        if (playerDiscard != getPlayer()) {
            return super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        }
        String host = (sa != null && sa.getHostCard() != null) ? sa.getHostCard().getName() : "effect";
        return chooseCardsFrom("Discard " + rangeText(min, max) + " card(s) for " + host, validCards, min, max);
    }

    /** "Sacrifice a permanent" — let the human pick which. */
    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
            CardCollectionView valid, String message) {
        return chooseCardsFrom("Sacrifice " + rangeText(min, max) + " permanent(s)", valid, min, max);
    }

    /** "Destroy a permanent" (chooser's choice) — let the human pick which. */
    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
            CardCollectionView valid, String message) {
        return chooseCardsFrom("Destroy " + rangeText(min, max) + " permanent(s)", valid, min, max);
    }

    /** Choose a type (creature type, land type, ...) e.g. Cavern of Souls. */
    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes,
            boolean isOptional) {
        if (validTypes == null || validTypes.isEmpty()) {
            return super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
        }
        List<String> names = new ArrayList<>(validTypes);
        List<Integer> sel = promptIndices("Choose a " + kindOfType + " type", names,
            isOptional ? 0 : 1, 1, isOptional, "choose");
        return sel.isEmpty() ? names.get(0) : names.get(sel.get(0));
    }

    /** Win the die roll: choose to play or draw. */
    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        boolean playFirst = yesNo("You won the roll — play first? (No = draw first)");
        if (playFirst) return getPlayer();
        for (Player p : getPlayer().getGame().getPlayers()) {
            if (p != getPlayer()) return p;
        }
        return getPlayer();
    }

    /** Scry: choose which of the top N cards go to the bottom (rest stay on top). */
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return scrySplit(topN, "Scry", "bottom of your library");
    }

    /** Surveil: choose which of the top N cards go to the graveyard (rest stay on top). */
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return scrySplit(topN, "Surveil", "graveyard");
    }

    private ImmutablePair<CardCollection, CardCollection> scrySplit(CardCollection topN,
            String verb, String dest) {
        CardCollection top = new CardCollection();
        CardCollection bottom = new CardCollection();
        if (topN == null || topN.isEmpty()) {
            return ImmutablePair.of(top, bottom);
        }
        List<Card> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Card c : topN) { list.add(c); names.add(c.getName()); }
        List<Integer> sel = promptIndices(
            verb + ": choose card(s) to put on the " + dest + " (unpicked stay on top)",
            names, 0, list.size(), true, "choose");
        Set<Integer> toBottom = new HashSet<>(sel);
        for (int i = 0; i < list.size(); i++) {
            if (toBottom.contains(i)) bottom.add(list.get(i));
            else top.add(list.get(i));
        }
        return ImmutablePair.of(top, bottom);
    }

    /** Prompt a yes/no confirmation. */
    private boolean yesNo(String message) {
        List<String> names = new ArrayList<>();
        names.add("Yes");
        names.add("No");
        List<Integer> sel = promptIndices(message, names, 1, 1, false, "confirm");
        return !sel.isEmpty() && sel.get(0) == 0;
    }

    /** Prompt a multi-select over a card pool; returns the chosen cards (padded to min). */
    private CardCollection chooseCardsFrom(String message, CardCollectionView pool, int min, int max) {
        CardCollection out = new CardCollection();
        if (pool == null || pool.isEmpty()) return out;
        List<Card> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Card c : pool) { list.add(c); names.add(c.getName()); }
        int hi = Math.min(max, list.size());
        int lo = Math.max(0, Math.min(min, hi));
        List<Integer> sel = promptIndices(message, names, lo, hi, lo == 0, "choose");
        for (int idx : sel) {
            if (idx >= 0 && idx < list.size()) out.add(list.get(idx));
        }
        for (int k = 0; k < list.size() && out.size() < lo; k++) {
            if (!out.contains(list.get(k))) out.add(list.get(k));
        }
        return out;
    }

    private static String rangeText(int min, int max) {
        return min == max ? String.valueOf(min) : (min + "-" + max);
    }

    // ---- Combat damage order + assignment ----

    /** Damage-assignment order among multiple blockers (first = damaged first). */
    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        String host = attacker != null ? attacker.getName() : "attacker";
        return orderCards("Damage order for " + host, blockers);
    }

    /** Insert a newly-declared blocker into the damage-assignment order. */
    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        CardCollection all = new CardCollection(oldBlockers);
        if (blocker != null && !all.contains(blocker)) all.add(blocker);
        String host = attacker != null ? attacker.getName() : "attacker";
        return orderCards("Damage order for " + host, all);
    }

    /** Order the attackers a single blocker is blocking (which it damages first). */
    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        String host = blocker != null ? blocker.getName() : "blocker";
        return orderCards("Damage order for " + host, attackers);
    }

    /**
     * Combat damage assignment. For a single blocked creature WITH trample we let
     * the human choose how much to assign to the blocker (the rest tramples
     * through). Everything else is assigned legally by the engine, which already
     * respects the damage-assignment order the human chose via orderBlockers.
     */
    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
            CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        boolean trample = attacker != null && attacker.hasKeyword(Keyword.TRAMPLE) && defender != null;
        if (blockers != null && blockers.size() == 1 && trample && damageDealt > 0) {
            try {
                Card b = blockers.get(0);
                int lethal = Math.max(0, b.getLethalDamage());
                int min = Math.min(lethal, damageDealt);
                Map<Card, Integer> map = new HashMap<>();
                if (min >= damageDealt) {          // no excess to trample
                    map.put(b, damageDealt);
                    return map;
                }
                int toBlocker = chooseNumber(null,
                    "Assign damage to " + b.getName() + " (rest tramples to " + entityName(defender) + ")",
                    min, damageDealt);
                map.put(b, toBlocker);
                if (damageDealt - toBlocker > 0) map.put(null, damageDealt - toBlocker);
                return map;
            } catch (Exception e) {
                // fall through to engine default
            }
        }
        return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
    }

    // ---- Replacement effects + simultaneous triggers ----

    /** When several replacement effects could apply, choose which to apply first. */
    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.isEmpty()) return null;
        if (possibleReplacers.size() == 1) return possibleReplacers.get(0);
        List<String> names = new ArrayList<>();
        for (ReplacementEffect re : possibleReplacers) names.add(describeRE(re));
        List<Integer> sel = promptIndices("Choose which replacement effect to apply first",
            names, 1, 1, false, "choose");
        return possibleReplacers.get(sel.isEmpty() ? 0 : sel.get(0));
    }

    /** Optional ("may") replacement effect: ask whether to apply it. */
    @Override
    public boolean confirmReplacementEffect(ReplacementEffect re, SpellAbility effectSA,
            GameEntity affected, String question) {
        String host = (re != null && re.getHostCard() != null) ? re.getHostCard().getName() : "effect";
        return yesNo("Apply " + host + "'s replacement effect?");
    }

    /** Order your own triggers that go on the stack simultaneously (APNAP). */
    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs == null || activePlayerSAs.size() <= 1) return activePlayerSAs;
        List<SpellAbility> remainingSa = new ArrayList<>(activePlayerSAs);
        List<SpellAbility> ordered = new ArrayList<>();
        while (remainingSa.size() > 1) {
            List<String> names = new ArrayList<>();
            for (SpellAbility s : remainingSa) names.add(describeSa(s));
            List<Integer> sel = promptIndices(
                "Order simultaneous triggers — pick #" + (ordered.size() + 1)
                    + " to place on the stack (later picks resolve first)",
                names, 1, 1, false, "choose");
            int idx = sel.isEmpty() ? 0 : sel.get(0);
            ordered.add(remainingSa.remove(idx));
        }
        ordered.add(remainingSa.get(0));
        return ordered;
    }

    // ---- shared ordering / describe helpers ----

    /** Repeatedly ask the client to pick the next card, building a full order. */
    private CardCollection orderCards(String label, CardCollectionView cards) {
        CardCollection ordered = new CardCollection();
        if (cards == null || cards.isEmpty()) return ordered;
        CardCollection remainingCards = new CardCollection(cards);
        while (remainingCards.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Card c : remainingCards) names.add(c.getName());
            List<Integer> sel = promptIndices(label + " — pick #" + (ordered.size() + 1) + " (damaged first)",
                names, 1, 1, false, "choose");
            int idx = sel.isEmpty() ? 0 : sel.get(0);
            Card pick = remainingCards.get(idx);
            ordered.add(pick);
            remainingCards.remove(pick);
        }
        ordered.add(remainingCards.get(0));
        return ordered;
    }

    private static String describeRE(ReplacementEffect re) {
        String host = (re != null && re.getHostCard() != null) ? re.getHostCard().getName() : "";
        String d = re != null ? re.getDescription() : "";
        return host + (d != null && !d.isEmpty() ? ": " + d : "");
    }

    private static String describeSa(SpellAbility s) {
        String host = (s != null && s.getHostCard() != null) ? s.getHostCard().getName() : "";
        String d = s != null ? s.getDescription() : "";
        return host + (d != null && !d.isEmpty() ? ": " + d : "");
    }

    private static String entityName(GameEntity ge) {
        if (ge instanceof Card) return ((Card) ge).getName();
        if (ge instanceof Player) return ((Player) ge).getName();
        return ge != null ? String.valueOf(ge) : "defender";
    }

    /**
     * The card the client just clicked, wherever it lives.
     *
     * Exile, Command and Library are in the list because a card can be castable
     * from any of them: every "exile the top card of your library, you may play
     * it" effect leaves the card in Exile with a MayPlay grant, and Mm'menon
     * casts artifacts off the top of the library. Without those zones the
     * lookup returned null and the play action was dropped in silence, which
     * reads as "the game won't let me cast it".
     */
    private static Card findCard(Player me, int id) {
        if (id < 0) return null;
        for (ZoneType zt : FIND_ZONES) {
            for (Card c : me.getCardsIn(zt)) {
                if (c.getId() == id) return c;
            }
        }
        return null;
    }

    /** Crude JSON int extractor for a "key": value pair (no JSON lib on the test classpath). */
    private static int parseInt(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return -1;
        int j = i + key.length();
        while (j < s.length() && s.charAt(j) == ' ') j++;
        int k = j;
        while (k < s.length() && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '-')) k++;
        try { return Integer.parseInt(s.substring(j, k)); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Combat: attackers. Reply {"attackers":"all"} attacks with everything
     * eligible; {"attackers":[ids]} attacks with the chosen creatures.
     *
     * By default every attacker is sent at the defending player. To attack a
     * planeswalker instead, the reply also carries
     * {"targets":[{"attacker":id,"defender":pwId}, ...]} -- the same shape the
     * blocker reply uses, so the flat parseInt() scan finds it.
     */
    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (combat.getDefendingPlayers().isEmpty()) { super.declareAttackers(attacker, combat); return; }
        GameEntity defender = combat.getDefendingPlayers().get(0);
        List<Card> eligible = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            // Eligible against ANY defender, not just the player: a creature can
            // legally be able to attack only a planeswalker (or only the player),
            // and this list gates who may be added below.
            for (GameEntity d : combat.getDefenders()) {
                if (CombatUtil.canAttack(c, d)) { eligible.add(c); break; }
            }
        }
        String state = StateExporter.toJson(attacker.getGame().getView(), attacker);
        String reply = Channel.request("{\"kind\":\"declare_attackers\",\"eligible\":" + idList(eligible)
                + ",\"defenders\":" + defenderList(combat) + ",\"state\":" + state + "}");
        String compact = reply.replaceAll("\\s", "");
        boolean all = compact.contains("\"attackers\":\"all\"");
        for (Card c : eligible) {
            if (!all && !inArray(compact, "attackers", c.getId())) continue;
            GameEntity target = defender;
            int pw = parseInt(compact, "\"attacker\":" + c.getId() + ",\"defender\":");
            if (pw > 0) {
                GameEntity chosen = findDefender(combat, pw);
                // Fall back to the player rather than dropping the attack if the
                // planeswalker has since left or the attack would be illegal.
                if (chosen != null && CombatUtil.canAttack(c, chosen)) target = chosen;
            }
            if (CombatUtil.canAttack(c, target)) combat.addAttacker(c, target);
        }
    }

    /** Non-player defenders (planeswalkers, battles) as [{"id":n,"name":"..."}]. */
    private static String defenderList(Combat combat) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (GameEntity d : combat.getDefenders()) {
            if (!(d instanceof Card)) continue;
            Card card = (Card) d;
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(card.getId())
             .append(",\"name\":\"").append(StateExporter.esc(card.getName())).append("\"}");
        }
        return b.append(']').toString();
    }

    /** The defender with this card id (a planeswalker / battle), or null. */
    private static GameEntity findDefender(Combat combat, int id) {
        for (GameEntity d : combat.getDefenders()) {
            if (d instanceof Card && ((Card) d).getId() == id) return d;
        }
        return null;
    }

    /**
     * Combat: blockers. Reply {"blocks":"none"} = no blocks;
     * {"blocks":[{"blocker":id,"attacker":id}, ...]} assigns blockers.
     */
    @Override
    public void declareBlockers(Player defender, Combat combat) {
        String state = StateExporter.toJson(defender.getGame().getView(), defender);
        String reply = Channel.request("{\"kind\":\"declare_blockers\",\"state\":" + state + "}");
        String compact = reply.replaceAll("\\s", "");
        if (compact.contains("\"blocks\":\"none\"") || !compact.contains("blocker")) {
            return; // no blocks
        }
        for (Card blk : defender.getCreaturesInPlay()) {
            int atkId = parseInt(compact, "\"blocker\":" + blk.getId() + ",\"attacker\":");
            if (atkId < 0) continue;
            Card atk = findAttacker(combat, atkId);
            if (atk != null && CombatUtil.canBlock(blk, combat)) {
                combat.addBlocker(atk, blk);
            }
        }
    }

    private static Card findAttacker(Combat combat, int id) {
        for (Card c : combat.getAttackers()) if (c.getId() == id) return c;
        return null;
    }

    private static String idList(List<Card> cs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < cs.size(); i++) { if (i > 0) b.append(','); b.append(cs.get(i).getId()); }
        return b.append("]").toString();
    }

    /** True if `id` appears in the compact JSON array under "key":[...]. */
    private static boolean inArray(String compact, String key, int id) {
        int i = compact.indexOf("\"" + key + "\":[");
        if (i < 0) return false;
        int end = compact.indexOf(']', i);
        if (end < 0) return false;
        String arr = "," + compact.substring(i + key.length() + 4, end) + ",";
        return arr.contains("," + id + ",");
    }
}
