package forge.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.function.Predicate;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.AiCostDecision;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.card.ICardFace;
import forge.game.card.CardState;
import forge.game.card.CounterType;
import forge.game.ability.ApiType;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.GameObject;
import forge.game.GameLogEntryType;
import forge.game.event.GameEventAddLog;
import forge.util.MessageUtil;
import forge.util.TextUtil;
import forge.game.spellability.TargetChoices;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.keyword.KeywordInterface;
import forge.game.player.PlayerController.BinaryChoiceType;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import org.apache.commons.lang3.tuple.Pair;
import forge.StaticData;
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
import forge.card.CardType;
import forge.game.cost.CostChooseColor;
import forge.game.cost.CostChooseCreatureType;
import forge.game.cost.CostCollectEvidence;
import forge.game.cost.CostExileFromStack;
import forge.game.cost.CostGainLife;
import forge.game.cost.CostDamage;
import forge.game.cost.CostDraw;
import forge.game.cost.CostExert;
import forge.game.cost.CostExiledMoveToGrave;
import forge.game.cost.CostForage;
import forge.game.cost.CostGainControl;
import forge.game.cost.CostMill;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostRemoveAnyCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostReturn;
import forge.game.cost.CostReveal;
import forge.game.cost.CostTapType;
import forge.game.cost.CostUnattach;
import forge.game.cost.CostUntapType;
import forge.game.GameEntityCounterTable;
import forge.game.card.CounterEnumType;
import forge.game.cost.PaymentDecision;
import forge.game.keyword.Keyword;
import forge.game.replacement.ReplacementEffect;
import forge.game.trigger.WrappedAbility;
import forge.game.combat.AttackConstraints;
import forge.game.combat.AttackRequirement;
import forge.game.combat.AttackRestriction;
import forge.game.combat.AttackRestrictionType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.combat.GlobalAttackRestrictions;
import forge.game.staticability.StaticAbilityMustAttack;
import com.google.common.collect.Multimap;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.card.CardView;
import forge.util.Localizer;
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

    /**
     * Which seat this controller speaks for, tagged onto every request.
     *
     * A pod can now have more than one human, and they share the single bound
     * Channel: Channel.request is synchronized and Forge's game loop is
     * single-threaded, so exactly one decision is ever outstanding and one
     * socket is still sufficient. What the bridge on the other end cannot do is
     * guess WHICH player it is being asked about, hence this.
     */
    private final int seat;

    public PlayerControllerBridge(Game game, Player p, LobbyPlayer lp) {
        this(game, p, lp, 0);
    }

    public PlayerControllerBridge(Game game, Player p, LobbyPlayer lp, int seat) {
        super(game, p, lp);
        this.seat = seat;
        // "X ability fizzles." with the reason: see TargetReasons.watchFizzles.
        // Once per game however many human seats there are.
        TargetReasons.watchFizzles(game);
    }

    /**
     * Send one request tagged with this seat, and return the client's reply.
     *
     * Every request in this class is built as {"kind":... , so splicing the
     * seat in after the opening brace leaves each call site exactly as it was
     * and keeps the wire format a single flat object.
     */
    // Practice game: cheats are honoured only while this is set. ForgeServer
    // reads _sandbox.txt once per game and sets it; static because the flag is
    // per GAME and this server runs one game at a time.
    private static volatile boolean sandbox;

    static void setSandbox(boolean on) {
        sandbox = on;
    }

    private String ask(String json) {
        if (json == null || json.isEmpty() || json.charAt(0) != '{') {
            return Channel.request(json);   // not an object; nothing to tag
        }
        return Channel.request("{\"seat\":" + seat + "," + json.substring(1));
    }

    /**
     * The client is gone: concede this seat and end the match. True if it did.
     *
     * Every further Channel.request() answers "" -- pass / decline -- so the
     * game would grind on with a seat nobody is driving, and ForgeServer serves
     * ONE match per accepted connection, so the next player's game sits behind
     * it and comes back "still finishing a previous match".
     *
     * Conceding alone is not enough. It ends a DUEL, because one seat left
     * means one winner, but a POD still has three AI seats that will happily
     * play each other out for minutes. Forge has a reason for exactly this
     * case, documented as "used to end multiplayer games where all humans have
     * lost or conceded while AIs cannot end match by themselves".
     *
     * Called from every point where the engine blocks on this player, not just
     * priority: the mulligan prompt is the FIRST thing a game shows and so the
     * most likely place for someone to close the tab.
     */
    private boolean endIfAbandoned() {
        if (!Channel.isDead()) {
            return false;
        }
        Player me = getPlayer();
        Game g = me.getGame();
        if (!me.conceded()) {
            me.concede();
            g.getAction().checkGameOverCondition();
        }
        if (!g.isGameOver()) {
            g.setGameOver(GameEndReason.AllHumansLost);
        }
        return true;
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
        if (endIfAbandoned()) {
            return null;
        }
        PhaseHandler ph = me.getGame().getPhaseHandler();
        // Board fingerprint, not the full JSON. The state used to be
        // serialised and pushed on EVERY priority window - including the
        // auto-passing ones - and each push also costs a socket round trip
        // and a full client re-render. That is fine on turn 2 and brutal on
        // turn 20 of a four-player pod: measured 102ms average early in a
        // game against 864ms late, on the same hardware. Most of those
        // windows show a board identical to the one already on screen, so
        // skip them.
        String fingerprint = boardFingerprint(me, ph);
        boolean unchanged = fingerprint.equals(lastPushedFingerprint);

        // Pass Turn: keep auto-passing until my turn ends (attacker declaration
        // still prompts via its own callback, so combat isn't skipped).
        if (skipTurn >= 0) {
            if (ph.getPlayerTurn() == me && ph.getTurn() == skipTurn) {
                pushBoard(me, fingerprint, unchanged);
                return null;
            }
            skipTurn = -1;
        }
        // Pass Phase: keep auto-passing until this phase ends.
        if (skipPhase != null) {
            if (skipPhase.equals(String.valueOf(ph.getPhase()))) {
                pushBoard(me, fingerprint, unchanged);
                return null;
            }
            skipPhase = null;
        }

        // Always push the current board so the client stays live (you see the
        // opponent develop, attack, etc. as it happens). Only STOP and wait for
        // input when it's actually your decision (see shouldPromptAtPriority);
        // otherwise push-and-continue so the game auto-advances.
        // Timed into AiPerf ("human n/s" on the pace line): this scan walks every
        // card the human could act with, at every window, on the game thread.
        final long humanT0 = System.currentTimeMillis();
        final boolean prompt = shouldPromptAtPriority(me);
        forge.ai.AiPerf.humanN.increment();
        forge.ai.AiPerf.humanMs.add(System.currentTimeMillis() - humanT0);
        if (!prompt) {
            pushBoard(me, fingerprint, unchanged);
            return null; // auto-pass
        }
        lastPushedFingerprint = fingerprint;
        String reply = ask("{\"kind\":\"priority\",\"state\":"
                + StateExporter.toJson(me.getGame().getView(), me) + "}");

        String compact = reply.replaceAll("\\s", "");
        if (compact.contains("\"action\":\"sandbox\"")) {
            // A Practice-game cheat: apply it, then ask this same window again
            // (as a rejected click does) so the player sees the result and
            // keeps priority rather than passing it.
            sandboxCheat(me, reply);
            return chooseSpellAbilityToPlay();
        }
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
                List<SpellAbility> all = chosen.getAllPossibleAbilities(me, true);
                // The client picked one item out of the card's exported ability
                // list (StateExporter.putAbilities), so it means THAT ability,
                // not "the first one of this kind". Both sides build the list
                // with the same call and the game is parked waiting for this
                // reply, so the index still points where the player pointed.
                // Without it the scan below took the first non-mana activated
                // ability, which is why only loyalty ability #1 of a
                // planeswalker was ever reachable.
                int idx = parseInt(reply, "\"ability\":");
                if (idx >= 0 && idx < all.size()) {
                    return Lists.newArrayList(all.get(idx));
                }
                for (SpellAbility sa : all) {
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
                // Forge says this card has nothing playable right now, and the
                // old code fell straight through to "pass priority" - so the
                // click vanished with no message and the player could not tell
                // "illegal" from "the button is broken". Say why, and DO NOT
                // pass: passing priority on a rejected click hands the window
                // away and can let a spell you meant to counter resolve.
                explainUnplayable(me, chosen);
                return chooseSpellAbilityToPlay();
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
        boolean allExtraCosts = true;
        for (SpellAbility sa : abilities) {
            names.add(abilityLabel(sa));
            String desc = sa.toUnsuppressedString();
            allExtraCosts &= additionalCostOf(desc == null ? "" : desc) != null;
        }
        String host = hostCard != null ? hostCard.getName() : "Spell";
        String title = allExtraCosts
                ? host + ": choose an additional cost"
                : host + ": which do you want to cast?";
        List<Integer> sel;
        promptSource = hostCard;
        try {
            sel = promptIndices(title, names, 1, 1, false, "choose");
        } finally {
            promptSource = null;
        }
        if (sel.isEmpty()) {
            return abilities.get(0);
        }
        return abilities.get(sel.get(0));
    }

    /**
     * Tell the player why a card they clicked could not be played.
     *
     * Forge's getAllPossibleAbilities(me, true) filters out anything unplayable
     * right now - unaffordable, wrong timing, or no legal target - but it does
     * not say which. Reconstructing the exact reason means re-running each
     * ability's canPlay/cost check, so this reports the distinguishable cases
     * and otherwise says plainly that Forge rejected it, which is still far
     * better than the click vanishing.
     */
    private void explainUnplayable(Player me, Card c) {
        String name = c.getName();
        String why;
        List<SpellAbility> any = c.getAllPossibleAbilities(me, false);
        if (any.isEmpty()) {
            why = name + " has nothing you can play from here.";
        } else {
            // Ask the questions the filter asked, one ability at a time, and
            // report the first that fails: timing (canPlay), then a mandatory
            // target with no candidate, then mana. The pieces already existed
            // for the pre-click greying (StateExporter) -- this composes them
            // instead of guessing "mana or target" and leaving the player to
            // work out which.
            String found = TargetReasons.unplayableReason(any, me);
            if (found != null) {
                why = "Cannot play " + name + " right now: " + found;
            } else if (!me.canCastSorcery()) {
                why = "Cannot play " + name + " right now. If it is sorcery-speed, it "
                        + "needs your own main phase with an empty stack.";
            } else {
                why = "Cannot play " + name + " right now - Forge rejected it and did "
                        + "not say why (usually a cast restriction on the card itself).";
            }
        }
        ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(why) + "\"]}");
    }

    /** Fingerprint of the last board actually sent, so identical pushes are skipped. */
    private String lastPushedFingerprint = "";

    /**
     * A cheap summary of everything the client renders.
     *
     * Deliberately not the exported JSON: the whole point is to decide whether
     * serialising that JSON is worth doing at all. Covers turn, phase, stack
     * depth, and per player life, zone sizes, and the tapped / damage / counter
     * totals on their battlefield - which is what visibly changes between two
     * priority windows. Anything it misses costs one stale frame that the next
     * real change corrects, never a wrong game action.
     */
    private static String boardFingerprint(Player me, PhaseHandler ph) {
        StringBuilder b = new StringBuilder(128);
        b.append(ph.getTurn()).append('|').append(ph.getPhase()).append('|')
         .append(me.getGame().getStack().size()).append('|');
        for (Player p : me.getGame().getPlayers()) {
            b.append(p.getLife()).append(',')
             .append(p.getCardsIn(ZoneType.Hand).size()).append(',')
             .append(p.getCardsIn(ZoneType.Graveyard).size()).append(',')
             .append(p.getCardsIn(ZoneType.Exile).size()).append(',')
             .append(p.getCardsIn(ZoneType.Command).size()).append(',');
            int tapped = 0, damage = 0, counters = 0, n = 0;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                n++;
                if (c.isTapped()) tapped++;
                damage += c.getDamage();
                counters += c.getNumAllCounters();
            }
            b.append(n).append(',').append(tapped).append(',')
             .append(damage).append(',').append(counters).append(';');
        }
        return b.toString();
    }

    /**
     * Send the board to the client, unless it is byte-for-byte the board the
     * client already has. Skipping saves the JSON export, the socket round trip
     * and a full client re-render - the three costs that made late turns crawl.
     */
    private void pushBoard(Player me, String fingerprint, boolean unchanged) {
        if (unchanged) {
            return;
        }
        lastPushedFingerprint = fingerprint;
        // A push-and-continue board is one the player cannot act on, so the
        // affordability captions (an auto-tapper simulation per ability) are
        // skipped; the next interactive ask carries them. On the 21:52 pod of
        // 2026-09-10 exports cost 0.5-0.8s each, 90 a turn, on these pushes.
        ask("{\"kind\":\"state\",\"state\":"
                + StateExporter.toJson(me.getGame().getView(), me, false) + "}");
    }

    /**
     * Label for one branch of {@link #getAbilityToPlay}.
     *
     * GameActionUtil.getAdditionalCostSpell tags each branch's description with
     * "(Additional cost: X)", where X is that branch's OWN extra cost - the one
     * thing that differs between the options. The label used to show the full
     * merged cost and the whole spell text on every row instead, so Betrayer's
     * Bargain offered "{1}{R}, Sacrifice a creature or enchantment - Betrayer's
     * Bargain deals 5 damage to target creature..." against "{3}{R} - Betrayer's
     * Bargain deals 5 damage to target creature...", and the list row clipped
     * off exactly the part that told them apart. The card is on screen already;
     * the row just says the cost: "Sacrifice a creature or enchantment" /
     * "Pay {2} more".
     */
    static String abilityLabel(SpellAbility sa) {
        String desc = sa.toUnsuppressedString();
        desc = desc == null ? "" : desc.trim();
        String extra = additionalCostOf(desc);
        if (extra != null) {
            return describeExtraCost(extra);
        }
        // Not an additional-cost branch (a cast-from-elsewhere choice, say):
        // cost, then text, kept to a row's width like the priority menu does.
        Cost cost = sa.getPayCosts();
        String costStr = cost == null ? "" : cost.toSimpleString();
        desc = desc.replaceAll("\\s+", " ");
        String label = costStr.isEmpty()
                ? (desc.isEmpty() ? String.valueOf(sa) : desc)
                : (desc.isEmpty() ? costStr : costStr + " - " + desc);
        return label.length() > 90 ? label.substring(0, 87) + "..." : label;
    }

    /** The X of an "(Additional cost: X)" tag in a description, or null. */
    static String additionalCostOf(String desc) {
        final String tag = "(Additional cost:";
        int at = desc.lastIndexOf(tag);
        if (at < 0) {
            return null;
        }
        int close = desc.indexOf(')', at + tag.length());
        String extra = close < 0 ? desc.substring(at + tag.length())
                                 : desc.substring(at + tag.length(), close);
        return extra.trim();
    }

    /** "{2}" reads as "Pay {2} more"; "Sacrifice a creature" is already prose. */
    static String describeExtraCost(String extra) {
        if (extra.isEmpty()) {
            return "No additional cost";
        }
        if (extra.matches("(\\{[^}]*\\}\\s*)+")) {
            return "Pay " + extra.trim() + " more";
        }
        return extra;
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
        boolean played;
        try {
            played = PlaySpellAbility.playSpellAbility(this, getPlayer(), sa);
        } catch (Exception e) {
            System.err.println("[bridge] play failed for "
                + (sa.getHostCard() != null ? sa.getHostCard().getName() : "?") + ": " + e);
            played = false;
        }
        if (!played) {
            explainIfUnpaid(sa);
        }
        return played;
    }

    /**
     * The play unwound. If the mana was the reason, say so.
     *
     * The mana cost is paid last, by the AI auto-tapper, and when it finds
     * nothing to tap the engine cancels the whole play with a line on stdout
     * and nothing for the player: the click had already walked through target
     * selection, so it read as "chose my target, then nothing happened". Live
     * report: Goblin Engineer, seven silent attempts, each after that turn's
     * mana had gone into creatures.
     *
     * {@link StateExporter#unpayableReason} now greys the ability before the
     * click; this is the backstop for a click that raced the state push, for
     * a spell dragged from hand (the drag path never reads the greyed flag),
     * and for X costs, which the pre-check leaves alone. Nothing changed on the
     * board (a failed payment refunds its mana), so asking the same question
     * again in test mode gives the answer the real payment just got. A cancel
     * at the target prompt with plenty of mana stays silent, as before.
     *
     * The auto-tapper also flags an ability it could not pay for as "skip".
     * Only the AI's own recursion guard reads that flag, but it is sticky on
     * the card's ability object, so it is cleared here rather than left to
     * surprise someone later.
     */
    private void explainIfUnpaid(SpellAbility sa) {
        try {
            sa.setSkip(false);
            Card host = sa.getHostCard();
            String name = host != null ? host.getName() : "That";
            // A modal spell with no mode left to choose. CharmEffect.makeChoices
            // returns false before any prompt opens (CR 603.3c: every mode's
            // target clause has nothing legal), and PlaySpellAbility unwinds
            // with nothing said. Name the modes and what each one needed.
            String modes = TargetReasons.noModeReason(sa, getPlayer());
            if (modes != null) {
                ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(name + ": " + modes) + "\"]}");
                return;
            }
            String why = StateExporter.unpayableReason(sa, getPlayer(), null);
            if (why == null) {
                return;
            }
            ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(name + ": " + why) + "\"]}");
        } catch (Exception e) {
            // An explanation is never worth breaking the play path over.
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
     * "I was asked, and I said no."
     *
     * {@link BridgeCostDecision#pickPayers} has two different reasons to hand
     * back nothing, and they are NOT interchangeable: there was no legal way to
     * pay (fall through to the AI, which will also fail to pay), versus the
     * player was shown the "(none = decline)" prompt and declined. Returning
     * null for both made every decline fall through to AiCostDecision, which
     * then paid the cost with a card of its own choosing — the player's "no"
     * silently became "the AI picks". Flaring Cinder ("you may discard a card.
     * If you do, draw a card.") is the report: closing the discard prompt
     * discarded an Island anyway and drew.
     *
     * A distinct sentinel keeps the two apart; visit() maps it to null, which
     * is how CostPayment is told the cost went unpaid.
     */
    private static final PaymentDecision DECLINED = PaymentDecision.number(0);

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
                // Fabricate. The card names itself, so there is no WHICH left to
                // ask -- but "put N +1/+1 counters on it" is the UnlessCost of
                // "create N Servo tokens", and declining is how you take the
                // Servos. Without this the AI paid the counters every time and
                // the Servo half of the mechanic was unreachable; the modal
                // never even opened. HumanCostDecision guards this exact branch
                // the same way, with a comment naming the mechanic.
                if (isEffect() && ability != null && ability.hasParam("UnlessCost")) {
                    int n = cost.getAbilityAmount(ability);
                    String cn = cost.getCounter() != null ? cost.getCounter().getName() : "counter";
                    String self = ability.getHostCard() != null ? ability.getHostCard().getName() : "it";
                    if (!yesNo("Put " + n + " " + cn + " counter" + (n == 1 ? "" : "s")
                            + " on " + self + "?")) {
                        return null;
                    }
                }
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
         * short of that as declining.
         *
         * Three outcomes, and the caller has to tell them apart:
         *   null      — nothing legal to pay with; the AI path is no worse.
         *   DECLINED  — the player was asked and said no. The cost goes unpaid.
         *   otherwise — what the player picked.
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
                return DECLINED;
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
            if (cost.payCostFromSource()) {
                if (!confirmSourceCost("sacrifice " + sourceName())) {
                    return null;
                }
                return super.visit(cost);
            }
            if ("OriginalHost".equals(type)
                    || "All".equals(cost.getAmount()) || type.contains("+WithDifferentNames")) {
                return super.visit(cost);
            }
            CardCollectionView list = CardLists.filter(
                    getPlayer().getCardsIn(ZoneType.Battlefield),
                    CardPredicates.canBeSacrificedBy(ability, isEffect()));
            list = CardLists.getValidCards(list, type.split(";"), getPlayer(),
                    ability.getHostCard(), ability);
            PaymentDecision pd = pickPayers("sacrifice", list, cost.getAbilityAmount(ability));
            if (pd == DECLINED) {
                return null;   // the player said no; leave the cost unpaid
            }
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
            if (pd == DECLINED) {
                return null;   // the player said no; leave the cost unpaid
            }
            return pd != null ? pd : super.visit(cost);
        }

        /**
         * Crew, and anything else shaped "tap any number of creatures with
         * total power N or more".
         *
         * Reported from a live Commander game: "crewing vehicles seems to crew
         * random creatures i own, not let me choose". It did. This method used
         * to send +withTotalPowerGE straight to super -- AiCostDecision --
         * which picks the creatures itself and taps them without asking, so
         * every Vehicle crewed with whatever the AI heuristic liked. The
         * comment above it even said "crew included", which it was not.
         *
         * Crew does not fit pickPayers, which is why it was excluded: that
         * helper picks EXACTLY `count` cards, and crew has no count. The cost
         * is a total-power threshold over any number of creatures, so the
         * prompt has to be 0..all with the threshold checked afterwards.
         *
         * Mirrors HumanCostDecision.visit(CostTapType)'s totalPower branch
         * (line ~1437): offer everything legal, and treat both an empty pick
         * and a pick that falls short as declining, leaving the cost unpaid.
         * Deliberately NOT auto-paying a short selection with extra creatures
         * -- picking which creatures tap is the whole decision, and a Vehicle
         * that quietly taps a blocker you were saving is the bug being fixed.
         */
        private PaymentDecision totalPowerDecision(CostTapType cost, String type) {
            int need;
            try {
                need = Integer.parseInt(type.split("withTotalPowerGE")[1]);
            } catch (Exception e) {
                return super.visit(cost);   // unparseable: the AI path is no worse
            }
            String bare = TextUtil.fastReplace(
                    type, TextUtil.concatNoSpace("+withTotalPowerGE",
                            String.valueOf(need)), "");
            CardCollection list = CardLists.getValidCards(
                    getPlayer().getCardsIn(ZoneType.Battlefield), bare.split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            // canPay() does this, so the offered list must too, or we would
            // show the Vehicle itself as something to crew with.
            if (!cost.canTapSource && ability.getHostCard() != null) {
                list.remove(ability.getHostCard());
            }
            list = CardLists.filter(list, ability.isCrew()
                    ? CardPredicates.CAN_CREW : CardPredicates.CAN_TAP);
            if (list.isEmpty() || CardLists.getTotalPower(list, ability) < need) {
                return null;   // cannot be paid at all
            }
            List<Card> pool = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Card c : list) {
                pool.add(c);
                // Power is shown because it IS the cost. Without it the player
                // is adding up numbers that are not on screen.
                names.add(c.getName() + " (power " + c.getNetPower() + ")");
            }
            String host = ability != null && ability.getHostCard() != null
                    ? ability.getHostCard().getName() : "this";
            String verb = ability.isCrew() ? "crew" : "tap for";
            List<Integer> sel = promptIndices(
                    host + ": " + verb + " — tap creatures with total power "
                            + need + " or more (none = decline)",
                    names, 0, pool.size(), true, "choose");
            CardCollection chosen = new CardCollection();
            for (int idx : sel) {
                if (idx >= 0 && idx < pool.size()) chosen.add(pool.get(idx));
            }
            if (chosen.isEmpty() || CardLists.getTotalPower(chosen, ability) < need) {
                return null;   // declined, or not enough power: leave it unpaid
            }
            return PaymentDecision.card(chosen);
        }

        /** "Tap N untapped creatures you control:" — crew handled above. */
        @Override
        public PaymentDecision visit(CostTapType cost) {
            String type = cost.getType();
            // Checked FIRST: crew also matches the "Any" amount guard below,
            // which is how it ended up on the AI path in the first place.
            if (type.contains("+withTotalPowerGE")) {
                return totalPowerDecision(cost, type);
            }
            if ("OriginalHost".equals(type) || "Any".equals(cost.getAmount())
                    || type.contains(".sharesCreatureTypeWith")) {
                return super.visit(cost);
            }
            CardCollection list = CardLists.getValidCards(
                    getPlayer().getCardsIn(ZoneType.Battlefield), type.split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            list = CardLists.filter(list, ability.isCrew()
                    ? CardPredicates.CAN_CREW : CardPredicates.CAN_TAP);
            PaymentDecision pd = pickPayers(ability.isCrew() ? "crew with" : "tap",
                    list, cost.getAbilityAmount(ability));
            if (pd == DECLINED) {
                return null;   // the player said no; leave the cost unpaid
            }
            return pd != null ? pd : super.visit(cost);
        }

        /** "Exile a card from your graveyard:" and friends. */
        @Override
        public PaymentDecision visit(CostExile cost) {
            String type = cost.getType();
            if (cost.payCostFromSource()) {
                // Same shape as fabricate: no WHICH, but during resolution this
                // is an "unless you exile it" and the answer is the player's.
                if (!confirmSourceCost("exile " + sourceName())) {
                    return null;
                }
                return super.visit(cost);
            }
            if ("OriginalHost".equals(type) || "All".equals(type)
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
            if (pd == DECLINED) {
                return null;   // the player said no; leave the cost unpaid
            }
            return pd != null ? pd : super.visit(cost);
        }

        /** The host card's name, for a prompt about a cost it pays itself. */
        private String sourceName() {
            return ability != null && ability.getHostCard() != null
                    ? ability.getHostCard().getName() : "it";
        }

        /**
         * The WHETHER question for a cost with no WHICH in it -- the card pays
         * with itself, or the cost is "pay 3 life" and there is nothing to pick.
         *
         * Only asked while the cost is being paid during RESOLUTION. For an
         * activated ability the player answered this question by activating it,
         * and asking again would put a confirm in front of every painland tap.
         * An unless cost during resolution is the opposite case: the whole
         * decision is whether to pay, and it was going to the AI.
         */
        private boolean confirmSourceCost(String what) {
            if (!isEffect()) {
                return true;
            }
            if (ability != null && ability.getPayCosts() != null
                    && ability.getPayCosts().isMandatory()) {
                return true;
            }
            String host = ability != null && ability.getHostCard() != null
                    ? ability.getHostCard().getName() : "this";
            return yesNo(host + ": " + what + "?");
        }

        /**
         * Which permanent loses counters. SubCounter is on 940 cards in the
         * Commander pool -- the biggest hole left in this class -- and
         * AiCostDecision picked the permanent, so "remove a counter from a
         * creature you control" quietly took one off whatever the AI valued
         * least.
         *
         * Only the "choose one of yours" branch is ours. Paying from the source
         * or the original host names the permanent already, and a null counter
         * type is a second question (which KIND of counter) that the AI declines
         * outright -- both stay where they were.
         */
        @Override
        public PaymentDecision visit(CostRemoveCounter cost) {
            if (cost.counter == null || cost.payCostFromSource()
                    || "OriginalHost".equals(cost.getType())) {
                return super.visit(cost);
            }
            boolean all = "All".equals(cost.getAmount());
            int need = all ? 1 : cost.getAbilityAmount(ability);
            if (need <= 0) {
                return super.visit(cost);
            }
            CardCollectionView valid = CardLists.getValidCards(
                    getPlayer().getCardsIn(cost.zone), cost.getType().split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            valid = CardLists.filter(valid, CardPredicates.hasCounter(cost.counter, need));
            if (valid.isEmpty()) {
                return super.visit(cost);
            }
            List<Card> pool = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Card c : valid) {
                pool.add(c);
                names.add(c.getName() + " (" + c.getCounters(cost.counter) + " "
                        + cost.counter.getName() + ")");
            }
            Card chosen;
            if (pool.size() == 1) {
                chosen = pool.get(0);          // no decision to make
            } else {
                List<Integer> sel = promptIndices(
                        sourceName() + ": remove " + (all ? "all" : String.valueOf(need)) + " "
                                + cost.counter.getName() + " from which permanent?",
                        names, 1, 1, false, "choose");
                if (sel.isEmpty()) {
                    return null;
                }
                chosen = pool.get(sel.get(0));
            }
            int take = all ? chosen.getCounters(cost.counter) : need;
            if (take <= 0) {
                return super.visit(cost);
            }
            GameEntityCounterTable table = new GameEntityCounterTable();
            table.put(null, chosen, cost.counter, take);
            return PaymentDecision.counters(table);
        }

        /**
         * "Remove N counters from among permanents you control": both WHICH
         * permanent and which KIND of counter, which is why Forge's own client
         * has a bespoke input for it. One permanent and one kind here -- every
         * card in the pool that uses this cost asks for a single counter, and
         * splitting a cost across permanents is a prompt nobody wants.
         */
        @Override
        public PaymentDecision visit(CostRemoveAnyCounter cost) {
            int need = cost.getAbilityAmount(ability);
            if (need <= 0) {
                return super.visit(cost);
            }
            CardCollectionView list = cost.payCostFromSource()
                    ? new CardCollection(ability.getHostCard())
                    : CardLists.getValidCards(getPlayer().getCardsIn(ZoneType.Battlefield),
                            cost.getType().split(";"), getPlayer(), ability.getHostCard(), ability);
            list = CardLists.filter(list, CardPredicates.hasCounters());
            List<Card> pool = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Card c : list) {
                if (c.getNumAllCounters() < need) {
                    continue;
                }
                pool.add(c);
                names.add(c.getName() + " (" + c.getNumAllCounters() + " counters)");
            }
            if (pool.isEmpty()) {
                return super.visit(cost);
            }
            Card chosen;
            if (pool.size() == 1) {
                chosen = pool.get(0);
            } else {
                List<Integer> sel = promptIndices(
                        sourceName() + ": remove " + need + " counter" + (need == 1 ? "" : "s")
                                + " from which permanent?", names, 1, 1, false, "choose");
                if (sel.isEmpty()) {
                    return null;
                }
                chosen = pool.get(sel.get(0));
            }
            List<CounterType> kinds = new ArrayList<>();
            List<String> kindNames = new ArrayList<>();
            for (CounterType ct : chosen.getCounters().elementSet()) {
                int have = chosen.getCounters(ct);
                if (have >= need && chosen.canRemoveCounters(ct)) {
                    kinds.add(ct);
                    kindNames.add(ct.getName() + " (" + have + ")");
                }
            }
            if (kinds.isEmpty()) {
                return super.visit(cost);
            }
            CounterType kind = kinds.get(0);
            if (kinds.size() > 1) {
                List<Integer> sel = promptIndices(chosen.getName() + ": remove which counters?",
                        kindNames, 1, 1, false, "choose");
                if (sel.isEmpty()) {
                    return null;
                }
                kind = kinds.get(sel.get(0));
            }
            GameEntityCounterTable table = new GameEntityCounterTable();
            table.put(null, chosen, kind, need);
            return PaymentDecision.counters(table);
        }

        /** Which permanent goes back to your hand. */
        @Override
        public PaymentDecision visit(CostReturn cost) {
            if (cost.payCostFromSource()) {
                Card self = ability.getHostCard();
                if (self == null || self.getController() != getPlayer() || !self.isInPlay()) {
                    return super.visit(cost);
                }
                return confirmSourceCost("return " + self.getName() + " to your hand")
                        ? PaymentDecision.card(self) : null;
            }
            CardCollectionView valid = CardLists.getValidCards(
                    getPlayer().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            PaymentDecision pd = pickPayers("return to hand", valid, cost.getAbilityAmount(ability));
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /**
         * Which creature exerts. Reached through payCombatCost now that it pays
         * the cost instead of only asking about it -- without this the exert
         * would go back to AiCostDecision the moment it was actually paid.
         */
        @Override
        public PaymentDecision visit(CostExert cost) {
            if (cost.payCostFromSource()) {
                Card self = ability.getHostCard();
                if (self == null || self.getController() != ability.getActivatingPlayer()
                        || !self.isInPlay()) {
                    return null;
                }
                return yesNo("Exert " + self.getName()
                        + "? (it won't untap during your next untap step)")
                        ? PaymentDecision.card(self) : null;
            }
            int c = cost.getAbilityAmount(ability);
            if (c == 0) {
                return PaymentDecision.number(0);
            }
            CardCollectionView valid = CardLists.getValidCards(
                    getPlayer().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            PaymentDecision pd = pickPayers("exert", valid, c);
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /** Which card you reveal to pay. */
        @Override
        public PaymentDecision visit(CostReveal cost) {
            String type = cost.getType();
            if (cost.payCostFromSource() || "Hand".equals(type) || "SameColor".equals(type)) {
                return super.visit(cost);
            }
            int c = cost.getAbilityAmount(ability);
            if (c <= 0) {
                return super.visit(cost);
            }
            CardCollectionView from = CardLists.getValidCards(
                    getPlayer().getCardsIn(cost.getRevealFrom()), type.split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            PaymentDecision pd = pickPayers("reveal", from, c);
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /**
         * Forage: sacrifice a Food OR exile three cards from your graveyard.
         * The AI took whichever branch its heuristics liked, which is a real
         * choice -- a Food is worth keeping, and so is a graveyard.
         */
        @Override
        public PaymentDecision visit(CostForage cost) {
            CardCollection food = CardLists.filter(getPlayer().getCardsIn(ZoneType.Battlefield),
                    CardPredicates.isType("Food"), CardPredicates.canBeSacrificedBy(ability, isEffect()));
            CardCollection grave = CardLists.filter(getPlayer().getCardsIn(ZoneType.Graveyard),
                    CardPredicates.canExiledBy(ability, isEffect()));
            boolean canFood = !food.isEmpty();
            boolean canExile = grave.size() >= 3;
            if (!canFood && !canExile) {
                return super.visit(cost);
            }
            boolean useFood = canFood;
            if (canFood && canExile) {
                List<String> how = new ArrayList<>();
                how.add("Sacrifice a Food");
                how.add("Exile three cards from your graveyard");
                List<Integer> sel = promptIndices(sourceName() + ": forage how?",
                        how, 1, 1, false, "confirm");
                if (sel.isEmpty()) {
                    return null;
                }
                useFood = sel.get(0) == 0;
            }
            PaymentDecision pd = useFood ? pickPayers("sacrifice", food, 1)
                                         : pickPayers("exile", grave, 3);
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /**
         * Collect evidence N: exile any number of cards from your graveyard with
         * total mana value N or more. Not a fixed count, so pickPayers cannot
         * express it -- the prompt is 0..all and the total is checked after.
         */
        @Override
        public PaymentDecision visit(CostCollectEvidence cost) {
            CardCollection list = CardLists.filter(getPlayer().getCardsIn(ZoneType.Graveyard),
                    CardPredicates.canExiledBy(ability, isEffect()));
            int total = cost.getAbilityAmount(ability);
            if (list.isEmpty() || total <= 0) {
                return super.visit(cost);
            }
            List<Card> pool = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Card c : list) {
                pool.add(c);
                names.add(c.getName() + " (mv " + c.getCMC() + ")");
            }
            List<Integer> sel = promptIndices(
                    sourceName() + ": exile cards totalling mana value " + total
                            + " or more (none = decline)",
                    names, 0, pool.size(), true, "choose");
            if (sel.isEmpty()) {
                return null;
            }
            CardCollection chosen = new CardCollection();
            int sum = 0;
            for (int idx : sel) {
                if (idx >= 0 && idx < pool.size()) {
                    chosen.add(pool.get(idx));
                    sum += pool.get(idx).getCMC();
                }
            }
            if (chosen.isEmpty() || sum < total) {
                return null;   // not enough evidence: the cost goes unpaid
            }
            return PaymentDecision.card(chosen);
        }

        /** Which exiled card is put into your graveyard. */
        @Override
        public PaymentDecision visit(CostExiledMoveToGrave cost) {
            int c = cost.getAbilityAmount(ability);
            Player activator = ability.getActivatingPlayer();
            if (activator == null || c <= 0) {
                return super.visit(cost);
            }
            CardCollectionView list = CardLists.getValidCards(
                    activator.getGame().getCardsIn(ZoneType.Exile), cost.getType().split(";"),
                    activator, ability.getHostCard(), ability);
            PaymentDecision pd = pickPayers("put into your graveyard", list, c);
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /** Which Aura or Equipment comes off. */
        @Override
        public PaymentDecision visit(CostUnattach cost) {
            CardCollection targets = cost.findCardToUnattach(ability.getHostCard(), getPlayer(), ability);
            if (targets == null || targets.isEmpty()) {
                return super.visit(cost);
            }
            if (targets.size() == 1) {
                return confirmSourceCost("unattach " + targets.getFirst().getName())
                        ? PaymentDecision.card(targets.getFirst()) : null;
            }
            PaymentDecision pd = pickPayers("unattach", targets, cost.getAbilityAmount(ability));
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /** Which of your permanents untap to pay. */
        @Override
        public PaymentDecision visit(CostUntapType cost) {
            int c = cost.getAbilityAmount(ability);
            if (c <= 0) {
                return super.visit(cost);
            }
            CardCollection valid = CardLists.getValidCards(
                    getPlayer().getGame().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            valid = CardLists.filter(valid, c2 -> c2.canUntap(null, false)
                    && (c2.getCounters(CounterEnumType.STUN) == 0
                        || c2.canRemoveCounters(CounterEnumType.STUN)));
            PaymentDecision pd = pickPayers("untap", valid, c);
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /** Which permanent you take control of to pay. */
        @Override
        public PaymentDecision visit(CostGainControl cost) {
            int c = cost.getAbilityAmount(ability);
            if (c <= 0) {
                return super.visit(cost);
            }
            CardCollectionView valid = CardLists.getValidCards(
                    getPlayer().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"),
                    getPlayer(), ability.getHostCard(), ability);
            valid = CardLists.filter(valid, crd -> crd.canBeControlledBy(getPlayer()));
            PaymentDecision pd = pickPayers("gain control of", valid, c);
            if (pd == DECLINED) {
                return null;
            }
            return pd != null ? pd : super.visit(cost);
        }

        /**
         * A colour named as a COST (Painter's Servant-shaped cards). chooseColors
         * is one of ours, so routing through it is all this needs -- but
         * AiCostDecision does not route, it picks.
         */
        @Override
        public PaymentDecision visit(CostChooseColor cost) {
            int c = cost.getAbilityAmount(ability);
            return PaymentDecision.colors(chooseColors(
                    sourceName() + ": choose a colour", ability, c, c, ColorSet.WUBRG));
        }

        /** A creature type named as a COST; chooseSomeType is likewise ours. */
        @Override
        public PaymentDecision visit(CostChooseCreatureType cost) {
            String choice = chooseSomeType("Creature", ability,
                    CardType.getAllCreatureTypes(), true);
            return choice == null ? null : PaymentDecision.type(choice);
        }

        /**
         * Which opponent gains the life. Free in a duel, a real decision in a
         * pod -- and the AI was making it.
         */
        @Override
        public PaymentDecision visit(CostGainLife cost) {
            List<Player> opts = new ArrayList<>();
            for (Player opp : cost.getPotentialTargets(getPlayer(), ability)) {
                if (opp.canGainLife()) {
                    opts.add(opp);
                }
            }
            if (opts.isEmpty()) {
                return super.visit(cost);
            }
            if (cost.getCntPlayers() == Integer.MAX_VALUE || opts.size() == 1) {
                return PaymentDecision.players(opts);   // everyone, or no choice
            }
            List<String> names = new ArrayList<>();
            for (Player p : opts) {
                names.add(p.getName());
            }
            List<Integer> sel = promptIndices(sourceName() + ": which player gains life?",
                    names, 1, 1, false, "choose");
            if (sel.isEmpty()) {
                return null;
            }
            List<Player> chosen = new ArrayList<>();
            chosen.add(opts.get(sel.get(0)));
            return PaymentDecision.players(chosen);
        }

        /** Which spell on the stack gets exiled to pay. */
        @Override
        public PaymentDecision visit(CostExileFromStack cost) {
            List<SpellAbility> sas = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (SpellAbilityStackInstance si : getPlayer().getGame().getStack()) {
                Card stC = si.getSourceCard();
                SpellAbility stSA = si.getSpellAbility().getRootAbility();
                if (stC != null && stSA != null && stSA.isSpell()
                        && stC.isValid(cost.getType().split(";"), ability.getActivatingPlayer(),
                                       ability.getHostCard(), ability)) {
                    sas.add(stSA);
                    names.add(stSA.getStackDescription());
                }
            }
            if ("All".equals(cost.getType())) {
                return PaymentDecision.spellabilities(sas);
            }
            int c = cost.getAbilityAmount(ability);
            if (c <= 0 || sas.size() < c) {
                return super.visit(cost);
            }
            List<Integer> sel = promptIndices(
                    sourceName() + ": exile which spell from the stack?", names, c, c, false, "choose");
            if (sel.size() != c) {
                return null;
            }
            List<SpellAbility> chosen = new ArrayList<>();
            for (int idx : sel) {
                if (idx >= 0 && idx < sas.size()) {
                    chosen.add(sas.get(idx));
                }
            }
            return PaymentDecision.spellabilities(chosen);
        }

        // --- costs with no WHICH, only a WHETHER ---------------------------
        // AiCostDecision pays these on its own read of the board. That is right
        // for an activation cost the player already opted into, and wrong for an
        // unless cost during resolution, where whether to pay is the decision.
        // confirmSourceCost draws that line.

        @Override
        public PaymentDecision visit(CostPayLife cost) {
            int c = cost.getAbilityAmount(ability);
            return confirmSourceCost("pay " + c + " life") ? super.visit(cost) : null;
        }

        @Override
        public PaymentDecision visit(CostMill cost) {
            int c = cost.getAbilityAmount(ability);
            return confirmSourceCost("mill " + c + " card" + (c == 1 ? "" : "s"))
                    ? super.visit(cost) : null;
        }

        @Override
        public PaymentDecision visit(CostDamage cost) {
            int c = cost.getAbilityAmount(ability);
            return confirmSourceCost("take " + c + " damage") ? super.visit(cost) : null;
        }

        @Override
        public PaymentDecision visit(CostDraw cost) {
            int c = cost.getAbilityAmount(ability);
            return confirmSourceCost("draw " + c + " card" + (c == 1 ? "" : "s"))
                    ? super.visit(cost) : null;
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

    /**
     * Say why an activation is about to unwind: a mandatory target has no
     * candidate, so PlaySpellAbility will cancel the whole thing.
     *
     * Reported from a live Commander pod: "it felt like my last planeswalker
     * wasn't able to activate its -2 ability". Daretti, Scrap Savant's -2
     * returns "target artifact card from your graveyard", and an opponent's
     * Silent Gravestone says cards in graveyards can't be targeted. Forge was
     * right to refuse (CR 602.2b: no legal target, no activation) -- but the
     * refusal was a bare {@code return false} here, so the click produced no
     * prompt, no feed line and no loyalty change, and a correct ruling read as
     * a broken button. Two clicks vanished before the player gave up and used
     * the +2. Same silence for any targeted ability: hexproof, shroud,
     * protection, or a "can't be the target" static all land here.
     *
     * StateExporter now greys a loyalty ability in this state before it is
     * clicked (see putAbilities); this is the backstop for everything else
     * and for a click that raced the state push.
     */
    private void explainNoTarget(SpellAbility sa, String what, List<String> nearMisses) {
        try {
            SpellAbility root = sa.getRootAbility() != null ? sa.getRootAbility() : sa;
            Card host = root.getHostCard();
            String name = host != null ? host.getName() : "That ability";
            String wants = TargetReasons.wants(sa);
            StringBuilder why = new StringBuilder(name).append(": cannot activate \"")
                    .append(StateExporter.abilityLabel(root, host)).append("\" - ").append(what)
                    .append(wants.isEmpty() ? "" : " (needs " + wants + ")").append('.');
            // The things it could NOT target and why, so "nothing legal" names
            // the hexproof creature / the targetless trigger instead of listing
            // every effect that might have caused it.
            if (nearMisses != null && !nearMisses.isEmpty()) {
                why.append(" Not targetable: ");
                for (int i = 0; i < nearMisses.size(); i++) {
                    if (i > 0) why.append("; ");
                    why.append(nearMisses.get(i));
                }
                why.append('.');
            } else {
                why.append(" Hexproof, shroud, protection or a \"can't be the target\" effect "
                        + "such as Silent Gravestone can cause this.");
            }
            ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(why.toString()) + "\"]}");
        } catch (Exception e) {
            // An explanation is never worth breaking the activation path over.
        }
    }

    /**
     * An optional target ("up to one target creature", "you may target ...")
     * with nothing legal to point at resolves with no target. Correct, but
     * silent: the player who clicked expecting a target prompt sees the
     * ability go on the stack and never learns it had a target clause. Say
     * so, once, at the moment it is decided.
     */
    private void explainOptionalTargetSkipped(SpellAbility sa, List<String> nearMisses) {
        try {
            Card host = sa.getHostCard();
            String name = host != null ? host.getName() : "That ability";
            String wants = TargetReasons.wants(sa);
            StringBuilder line = new StringBuilder(name)
                    .append(": no legal target for its optional target")
                    .append(wants.isEmpty() ? "" : " (" + wants + ")")
                    .append(", so it resolves without one.");
            if (nearMisses != null && !nearMisses.isEmpty()) {
                line.append(" Not targetable: ").append(String.join("; ", nearMisses)).append('.');
            }
            ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(line.toString()) + "\"]}");
        } catch (Exception e) {
            // never worth breaking the play over
        }
    }

    /**
     * Cap on the greyed "can't target" rows appended to a target prompt. A
     * four-player pod at turn 60 has far more permanents than anyone wants to
     * scroll past to find the one that is hexproof.
     */
    private static final int NEAR_MISS_CAP = 12;

    /** Prompt the client for one targeted ability's targets; assign the picks. */
    private boolean pickTargetsForSA(SpellAbility sa) {
        sa.resetTargets();
        // Stack targets are a separate world — see pickStackTargetsForSA.
        List<ZoneType> zones = sa.getTargetRestrictions().getZone();
        if (zones != null && zones.size() == 1 && zones.get(0) == ZoneType.Stack) {
            return pickStackTargetsForSA(sa);
        }
        List<GameEntity> candidates = sa.getTargetRestrictions().getAllCandidates(sa);
        int min = sa.getMinTargets();
        int max = sa.getMaxTargets();
        if (max <= 0) max = candidates.size();
        // The things that LOOK like targets but are not, each with its reason:
        // the hexproof creature, the opponent behind a Leyline, your own
        // creature when the spell wants an opponent's. Listed greyed under the
        // legal ones so the prompt answers "why isn't X in this list" itself.
        // (2026-09-11: "doesn't Untimely Malfunction let me redirect Phyrexian
        // Obliterator's trigger?" -- the trigger has no target, and nothing on
        // screen said so.)
        List<GameEntity> misses = new ArrayList<>();
        List<String> missWhy = new ArrayList<>();
        TargetReasons.boardNearMisses(sa, candidates, misses, missWhy, NEAR_MISS_CAP);
        List<String> missLines = new ArrayList<>();
        for (int i = 0; i < misses.size(); i++) {
            missLines.add(describeTarget(misses.get(i)) + " - " + missWhy.get(i));
        }
        if (candidates.isEmpty()) {
            if (min > 0) {
                explainNoTarget(sa, "nothing legal to target", missLines);
            } else {
                explainOptionalTargetSkipped(sa, missLines);
            }
            return min == 0; // no legal targets: only OK if targeting is optional
        }
        List<String> names = new ArrayList<>();
        List<String> cardNames = new ArrayList<>(cardNamesOf(candidates));
        List<String> disabled = new ArrayList<>();
        for (GameEntity ge : candidates) {
            names.add(describeTarget(ge));
            disabled.add(null);
        }
        for (int i = 0; i < misses.size(); i++) {
            GameEntity ge = misses.get(i);
            names.add(describeTarget(ge));
            cardNames.add(ge instanceof Card ? ((Card) ge).getName() : null);
            disabled.add(missWhy.get(i));
        }
        int hi = Math.min(max, candidates.size());
        String host = sa.getHostCard() != null ? sa.getHostCard().getName() : "ability";
        List<Integer> sel;
        promptSource = sa.getHostCard();
        try {
            sel = promptIndices(
                "Choose target" + (hi > 1 ? "s" : "") + " for " + host + targetingContext(sa),
                names, cardNames, disabled, min, hi, min == 0, "target_select", null);
        } finally {
            promptSource = null;
        }
        for (int idx : sel) {
            // Greyed rows sit past candidates.size() and are never assigned,
            // whatever the client sends back.
            if (idx >= 0 && idx < candidates.size()) {
                sa.getTargets().add(candidates.get(idx));
            }
        }
        return sa.isTargetNumberValid();
    }

    /**
     * Targets that live on the STACK: counterspells, Fork-style copy effects,
     * "target activated ability". Forge treats these as a wholly separate
     * targeting path and the generic one above cannot reach them.
     *
     * {@code TargetRestrictions.getAllCandidates} only ever yields Players and
     * Cards, and its sibling {@code hasCandidates} says so outright — "Stack
     * Zone targets are considered later". A spell on the stack is targeted as
     * the SpellAbility, not as its card: {@code CounterEffect} reads its victims
     * back through {@code TargetChoices.getTargetSpells()}, which filters for
     * SpellAbility instances. So the generic path returned an empty candidate
     * list, {@link #pickTargetsForSA} saw {@code min == 1} and returned false,
     * and {@code PlaySpellAbility} silently unwound the cast — no prompt, no
     * feed line, the card just stayed in hand. Every Counter-API card in the
     * pool was uncastable; Dispelling Exhale is the report.
     *
     * PlayerControllerHuman gets this for free by delegating to TargetSelection,
     * whose {@code chooseCardFromStack} walks {@code game.getStack()} and filters
     * on {@code canTargetSpellAbility}. This mirrors that, minus the interactive
     * "[FINISH TARGETING]" loop — promptIndices already collects a whole
     * multi-select answer in one round trip.
     */
    private boolean pickStackTargetsForSA(SpellAbility sa) {
        Game game = getPlayer().getGame();
        List<SpellAbility> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        // Everything else on the stack, with the reason it is not a candidate.
        // The stack is short, so every item is listed -- "counter whose spell"
        // and "redirect which ability" are decided by seeing the whole stack.
        List<SpellAbility> misses = new ArrayList<>();
        List<String> missWhy = new ArrayList<>();
        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility onStack = si.getSpellAbility();
            // canTargetSpellAbility unwraps WrappedAbility and enforces
            // TargetType$ / self-target rules (115.5). Add the raw ability
            // though, exactly as TargetSelection does.
            if (sa.canTargetSpellAbility(onStack)) {
                candidates.add(onStack);
                names.add(describeStackTarget(onStack));
            } else {
                misses.add(onStack);
                missWhy.add(TargetReasons.stackReject(sa, onStack));
            }
        }
        List<String> missLines = new ArrayList<>();
        for (int i = 0; i < misses.size(); i++) {
            missLines.add(describeStackTarget(misses.get(i)) + " - " + missWhy.get(i));
        }
        int min = sa.getMinTargets();
        int max = sa.getMaxTargets();
        if (max <= 0) max = candidates.size();
        if (candidates.isEmpty()) {
            if (min > 0) {
                explainNoTarget(sa, misses.isEmpty() ? "the stack is empty"
                                                     : "nothing on the stack it can target", missLines);
            } else {
                explainOptionalTargetSkipped(sa, missLines);
            }
            return min == 0; // nothing counterable: only OK if targeting is optional
        }
        int hi = Math.min(max, candidates.size());
        String host = sa.getHostCard() != null ? sa.getHostCard().getName() : "ability";
        // Counter-target list: these options are SpellAbilities on the stack,
        // so the card to show is the one that cast them.
        List<String> stackCardNames = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (SpellAbility onStack2 : candidates) {
            Card host2 = onStack2.getHostCard();
            stackCardNames.add(host2 != null ? host2.getName() : null);
            disabled.add(null);
        }
        for (int i = 0; i < misses.size(); i++) {
            Card host2 = misses.get(i).getHostCard();
            names.add(describeStackTarget(misses.get(i)));
            stackCardNames.add(host2 != null ? host2.getName() : null);
            disabled.add(missWhy.get(i));
        }
        List<Integer> sel = promptIndices(
            "Choose target" + (hi > 1 ? "s" : "") + " for " + host,
            names, stackCardNames, disabled, min, hi, min == 0, "target_select", null);
        for (int idx : sel) {
            if (idx >= 0 && idx < candidates.size()) {
                sa.getTargets().add(candidates.get(idx));
            }
        }
        return sa.isTargetNumberValid();
    }

    /**
     * Label for a spell/ability on the stack. The controller matters more here
     * than anywhere else — "counter whose spell?" is the whole decision — so it
     * comes from getActivatingPlayer rather than the host card's controller,
     * which can differ for a stolen or copied spell.
     *
     * Says what KIND of thing it is (a spell, a trigger, an activated ability)
     * and where it is currently aimed: for a redirect spell the whole decision
     * is "where is it pointed now", and for a counterspell "Bolt -> my Bears"
     * is the difference between countering it and letting it through.
     */
    static String describeStackTarget(SpellAbility onStack) {
        Card host = onStack.getHostCard();
        Player ctrl = onStack.getActivatingPlayer();
        String name = host != null ? host.getName() : String.valueOf(onStack);
        return name + TargetReasons.stackKindSuffix(onStack)
                + (ctrl != null ? " [" + ctrl.getName() + "]" : "")
                + TargetReasons.aimedAt(onStack);
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
        String reply = ask(req);

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
        for (SpellAbility sa : StateExporter.possibleAbilities(c, me)) {
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
        // Abandoning at the mulligan prompt is the COMMON case, not an edge
        // one: the game window opens, the Keep/Mulligan modal is the first
        // thing shown, and that is exactly where a player closes the tab or
        // refreshes. Checking only at the priority window let those pods run
        // on, which is what put the next player back in the deck editor with
        // "still finishing a previous match".
        if (endIfAbandoned()) {
            return true;            // keep; the game is over either way
        }
        String state = StateExporter.toJson(getPlayer().getGame().getView(), getPlayer());
        String reply = ask("{\"kind\":\"mulligan\",\"state\":" + state + "}");
        if (endIfAbandoned()) {
            return true;            // the client vanished while we were asking
        }
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
        String reply = ask("{\"kind\":\"mulligan_bottom\",\"count\":" + cardsToReturn
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

    /**
     * JSON string escaping for anything that reaches the client.
     *
     * This used to escape only backslash and quote, which is not enough: any
     * string carrying a raw control character produced INVALID JSON, the
     * Python relay's json.loads threw "Invalid control character at ...", and
     * the match died mid-window and was filed as an error. Seen in a Commander
     * match on 2026-09-07 and a Lightning Round game on 2026-09-11, both at
     * declare-attackers. Twelve call sites passed names and labels through it.
     *
     * StateExporter.esc already does the whole job -- quote, backslash, \n, \r,
     * \t and every other char below 0x20 -- and is what the rest of the wire
     * has always used, so delegate rather than keep a second, weaker copy.
     */
    private static String escName(String s) {
        return StateExporter.esc(s);
    }

    /** Same escaping; kept as a separate name for the rules-text call sites. */
    private static String escText(String s) {
        return StateExporter.esc(s);
    }

    /**
     * The card a prompt is ABOUT, for the modal's header. Set it around a
     * promptIndices call and clear it after; null means the prompt has no
     * single card (a mulligan, "choose a pile"). The native engine sends
     * source_card / source_card_details with every prompt and PromptModal
     * titles the window with it and draws the card; without it every Forge
     * prompt was titled "Choose" with nothing to look at.
     */
    private Card promptSource = null;

    private String sourceCardJson() {
        Card c = promptSource;
        if (c == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(",\"source_card\":\"").append(escName(c.getName())).append("\"");
        b.append(",\"source_card_details\":{\"name\":\"").append(escName(c.getName())).append("\"");
        try {
            if (c.getManaCost() != null && !c.getManaCost().isNoCost()) {
                b.append(",\"mana_cost\":\"").append(escName(c.getManaCost().getShortString())).append("\"");
            }
            b.append(",\"type_line\":\"").append(escName(String.valueOf(c.getType()))).append("\"");
            // Card scripts write line breaks as the two characters backslash-n
            // and Card.getOracleText hands them over untouched -- CardView is
            // where Forge turns them into real newlines. escText then escaped
            // the backslash, so the prompt header read "First strike\nWhen
            // Combustible Gearhulk enters" verbatim. Convert as CardView does.
            String oracle = c.getOracleText();
            if (oracle != null && !oracle.isEmpty()) {
                oracle = oracle.replace("\\r", "").replace("\\n", "\n");
                b.append(",\"oracle_text\":\"").append(escText(oracle)).append("\"");
            }
            if (c.isCreature()) {
                b.append(",\"power\":").append(c.getNetPower())
                 .append(",\"toughness\":").append(c.getNetToughness());
            }
        } catch (Exception e) {   // a header is never worth losing the prompt
            // fall through with whatever was built
        }
        b.append('}');
        return b.toString();
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
        return promptIndices(title, names, null, min, max, optional, promptType, null);
    }

    private List<Integer> promptIndices(String title, List<String> names, int min, int max,
                                        boolean optional, String promptType, Card cardToShow) {
        return promptIndices(title, names, null, min, max, optional, promptType, cardToShow);
    }

    /** Real card names for a list of entities; null entry for anything else. */
    private static List<String> cardNamesOf(List<? extends GameEntity> entities) {
        List<String> out = new ArrayList<>();
        for (GameEntity ge : entities) {
            out.add(ge instanceof Card ? ((Card) ge).getName() : null);
        }
        return out;
    }

    /**
     * As above, plus the ONE card this decision is about.
     *
     * Forge hands several confirm-style effects a {@code cardToShow} so the UI
     * can put the card in front of you while you answer. Cascade is the report:
     * PlayEffect asks "play this card without paying its mana cost?" with the
     * exiled card attached, and DiscoverEffect does the same with what it found.
     * The bridge dropped that argument, so the client received the card's name
     * buried in a message string and nothing structured to render — "i cant
     * tell much about the cards".
     *
     * The client already knows this shape: PromptModal calls
     * {@code _load_texture(name, art_slug)} for reveal prompts and option
     * payloads, so emitting the same pair alongside message/options is all it
     * needs. art_slug stays empty deliberately — it names one exact printing and
     * the translator only fills it for TOKENS (from Forge's set/collector pair).
     * A cascaded or discovered card is a real card, so the name lookup is the
     * correct path, and it resolves for double-faced names too now that
     * card_image_fetch aliases each face to the combined name.
     */
    /** True if this option label is a real card name, not "Yes" or "Pay {2}". */
    private static boolean isKnownCard(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        try {
            return StaticData.instance().getCommonCards().contains(name);
        } catch (Exception e) {   // never let a prompt die over a label lookup
            return false;
        }
    }

    /**
     * Option labels are often DECORATED -- describeTarget renders a creature as
     * "Grizzly Bears [AI 1]" so you can tell two copies apart, and the entity
     * choosers use String.valueOf(entity). Those labels are worth keeping, but
     * they defeat isKnownCard, so a target list showed no art and the client
     * treated it as a yes/no box: click commits, no card ever visible. That is
     * the "still can't see the cards before confirming" report.
     *
     * So carry the real name separately instead of trying to recover it from the
     * label. `cardNames` is parallel to `names`, null (or a null entry) where the
     * option is not a card -- a player, a mode, "Yes".
     */
    private List<Integer> promptIndices(String title, List<String> names, List<String> cardNames,
                                        int min, int max, boolean optional, String promptType,
                                        Card cardToShow) {
        return promptIndices(title, names, cardNames, null, min, max, optional, promptType, cardToShow);
    }

    /**
     * As above, plus a reason per option that CANNOT be chosen right now.
     *
     * {@code disabled} is parallel to {@code names}; a null entry (or a null
     * list) is an ordinary option. A non-null entry is shipped as
     * {@code "disabled":"why"} and PromptModal renders the row greyed, with the
     * reason, and unselectable. Two uses: a mode whose target clause has no
     * candidate (chooseModeForAbility), and the "near miss" rows a target
     * prompt appends under the legal ones (pickTargetsForSA) -- the hexproof
     * creature, the targetless trigger, the opponent behind a Leyline. Both
     * turn "why isn't X in this list" into a line of text next to X.
     *
     * Callers still ignore any returned index that points at a disabled row:
     * the client never sends one, but the wire is not trusted for legality.
     */
    private List<Integer> promptIndices(String title, List<String> names, List<String> cardNames,
                                        List<String> disabled,
                                        int min, int max, boolean optional, String promptType,
                                        Card cardToShow) {
        // Say which options are CARDS. Every prompt ships the same
        // {id,name} pair whether the name is "Grizzly Bears" or "Yes", so the
        // client had no way to tell a card list from a yes/no box and could
        // neither show the card nor decide whether picking one should commit
        // immediately. Asking Forge's own card DB is exact and costs a lookup:
        // "Pay {2}", "Choose a pile" and "Yes" do not resolve, real cards do.
        StringBuilder opts = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) opts.append(',');
            opts.append("{\"id\":\"").append(i).append("\",\"name\":\"")
                .append(escName(names.get(i))).append("\"");
            String real = cardNames != null && i < cardNames.size() ? cardNames.get(i) : null;
            if (real != null) {
                // Known to be a card by construction, and the label is decorated,
                // so hand over the name the art lookup needs.
                opts.append(",\"is_card\":true,\"card_name\":\"")
                    .append(escName(real)).append("\"");
            } else if (isKnownCard(names.get(i))) {
                opts.append(",\"is_card\":true");
            }
            String why = disabled != null && i < disabled.size() ? disabled.get(i) : null;
            if (why != null && !why.isEmpty()) {
                opts.append(",\"disabled\":\"").append(escName(why)).append("\"");
            }
            opts.append('}');
        }
        opts.append(']');
        String req = "{\"kind\":\"prompt\",\"prompt\":{"
                + "\"message\":\"" + escName(title == null ? "Choose" : title) + "\","
                + "\"options\":" + opts + ","
                + "\"multi\":" + (max > 1) + ",\"min\":" + min + ",\"max\":" + max + ","
                // PromptModal reads min_select / max_select, not min / max. Without
                // these it assumed min_select=1, max_select=9999 for every Forge
                // prompt: Confirm started disabled, a single-select prompt never
                // auto-confirmed on click, and an optional one offered "Cancel"
                // where it should have offered "Skip".
                + "\"min_select\":" + min + ",\"max_select\":" + max + ","
                + "\"optional\":" + optional + ",\"prompt_type\":\"" + promptType + "\""
                + (cardToShow == null ? ""
                   : ",\"card\":{\"name\":\"" + escName(cardToShow.getName())
                     + "\",\"art_slug\":\"\"}")
                + sourceCardJson()
                + "},"
                + "\"state\":" + stateJson() + "}";
        String reply = ask(req);
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
        if (sa != null && sa.getApi() == ApiType.CompanionChoose) {
            // Player.assignCompanion, at game start. Forge's title is a
            // localizer key; say what the choice is and that declining is fine
            // (Player.java is patched to accept the null).
            title = "Choose a companion (skip = play without one)";
        }
        List<Integer> sel = promptIndices(title, names, cardNamesOf(opts),
                isOptional ? 0 : 1, 1, isOptional, "choose", null);
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
        List<Integer> sel = promptIndices(title, names, cardNamesOf(opts), min,
                Math.min(max, opts.size()), min == 0, "choose", null);
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
        // cardToShow is the card the question is ABOUT (cascade's exiled card,
        // discover's find). Forwarding it is what lets the client render it.
        List<Integer> sel = promptIndices(message, names, 1, 1, false, "confirm", cardToShow);
        return !sel.isEmpty() && sel.get(0) == 0;
    }

    // The widest numeric range we render as a list of options. The bail-out
    // used to be 50, which handed X back to the AI on any spell cast with more
    // than 50 mana available -- reachable in a Commander pod, and silent when it
    // happens. The client renders a filter box past 60 options, so a long
    // numeric list is typed into rather than scrolled; 300 is past any real X
    // and still an answerable prompt.
    private static final int NUMBER_PROMPT_SPAN = 300;

    // Never delegates to super. AiController.chooseNumber defaults to AILogic
    // "Max" and ends in `return max`, so an unbounded range came back as
    // Integer.MAX_VALUE with the player never asked at all -- and an announced X
    // that large kills the JVM when it is paid (see announceCeiling). A range
    // too wide to render is clamped to a window and still asked.
    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (min >= max) return min;
        int top = max - min > NUMBER_PROMPT_SPAN ? min + NUMBER_PROMPT_SPAN : max;
        List<String> names = new ArrayList<>();
        for (int n = min; n <= top; n++) names.add(String.valueOf(n));
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
        List<String> disabled = new ArrayList<>();
        for (AbilitySub s : possible) {
            String d = s.getDescription();
            names.add(d == null || d.isEmpty() ? String.valueOf(s) : d);
            disabled.add(null);
        }
        // The modes Forge left OUT. CharmEffect.makePossibleOptions drops a
        // mode whose mandatory target has no candidate (CR 603.3c) and one
        // already chosen under a ChoiceRestriction, silently: the picker just
        // has fewer rows than the card has bullet points. "Doesn't Untimely
        // Malfunction let me redirect Phyrexian Obliterator's trigger?" was a
        // player staring at a two-mode picker for a three-mode card -- the
        // trigger has no target, so the redirect mode was never offered, and
        // nothing on screen said so. List them greyed with the reason, after
        // the live ones; their ids sit past possible.size() and are ignored
        // below whatever comes back.
        try {
            for (AbilitySub ch : TargetReasons.missingModes(sa, possible)) {
                String d = ch.getDescription();
                names.add(d == null || d.isEmpty() ? String.valueOf(ch) : d);
                disabled.add(TargetReasons.missingModeReason(sa, ch, getPlayer()));
            }
        } catch (Exception e) {
            // The picker must open whatever the explanation does.
        }
        List<Integer> sel = promptIndices("Choose mode", names, null, disabled,
                min, Math.min(num, possible.size()), false, "choose", null);
        List<AbilitySub> result = new ArrayList<>();
        for (int idx : sel) {
            if (idx >= 0 && idx < possible.size()) result.add(possible.get(idx));
        }
        for (int k = 0; k < possible.size() && result.size() < min; k++) {
            if (!result.contains(possible.get(k))) result.add(possible.get(k));
        }
        return result;
    }

    /**
     * "As this enters, choose X or Y" and every other GenericChoice effect.
     *
     * Monastery Siege is the clearest case: its script is
     * {@code DB$ GenericChoice | Choices$ Khans,Dragons | AILogic$ Khans}. Without
     * this override the call reached PlayerControllerAi, which obeys that
     * AILogic and always chose Khans - the player watched the game decide for
     * them. The choice is the whole card.
     *
     * Modal spells go through chooseModeForAbility; this is the sibling path for
     * choices presented as a list of sub-abilities rather than modes.
     */
    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells,
            SpellAbility sa, String title, int num, Map<String, Object> params) {
        if (spells == null || spells.isEmpty()) return Lists.newArrayList();
        List<String> names = new ArrayList<>();
        for (SpellAbility s : spells) {
            String d = s == null ? null : s.getDescription();
            names.add(d == null || d.isEmpty() ? String.valueOf(s) : d);
        }
        int want = Math.min(Math.max(num, 1), spells.size());
        // min == want: these choices are mandatory ("choose Khans or Dragons"
        // has no "choose neither"), so the prompt must not offer a cancel.
        List<Integer> sel = promptIndices(
                title == null || title.isEmpty() ? "Choose" : title,
                names, want, want, false, "choose");
        List<SpellAbility> result = Lists.newArrayList();
        for (int idx : sel) {
            if (idx >= 0 && idx < spells.size()) result.add(spells.get(idx));
        }
        // A dropped connection or a malformed reply must not stall the game:
        // fall back to the first option rather than returning nothing, which
        // would silently skip the effect entirely.
        for (int k = 0; k < spells.size() && result.size() < want; k++) {
            if (!result.contains(spells.get(k))) result.add(spells.get(k));
        }
        return result;
    }

    // ---- Batch 1 of the ai_decision_allowlist sweep ---------------------------
    // Prioritised by audit_decision_reach.py counts against the Commander pool.
    // Two of these are worse than "the AI decided": divideShield returns an empty
    // map for the AI (the effect simply does nothing) and the Predicate overload
    // of chooseSingleCardFace throws UnsupportedOperationException.

    /**
     * "Divide N shields / damage / counters among any number of targets."
     *
     * PlayerControllerAi returns an empty HashMap with the comment "AI currently
     * can't use this so this is not implemented" - so on the bridge these 122
     * pool cards divided nothing at all. This is a dead effect, not a bad choice.
     *
     * Allocation is one prompt per point: simpler than a spinner per target, and
     * it lets the player stack them unevenly, which is the whole reason the card
     * says "divided as you choose".
     */
    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource,
            Map<GameEntity, Integer> affected, int shieldAmount) {
        Map<GameEntity, Integer> result = new HashMap<>();
        if (affected == null || affected.isEmpty() || shieldAmount <= 0) return result;
        List<GameEntity> targets = new ArrayList<>(affected.keySet());
        if (targets.size() == 1) {
            result.put(targets.get(0), shieldAmount);
            return result;
        }
        String host = effectSource != null ? effectSource.getName() : "effect";
        for (int remaining = shieldAmount; remaining > 0; remaining--) {
            List<String> names = new ArrayList<>();
            for (GameEntity e : targets) names.add(String.valueOf(e));
            List<Integer> sel = promptIndices(
                    host + ": assign shield " + (shieldAmount - remaining + 1)
                            + " of " + shieldAmount, names, 1, 1, false, "choose");
            int idx = sel.isEmpty() ? 0 : sel.get(0);
            if (idx < 0 || idx >= targets.size()) idx = 0;
            result.merge(targets.get(idx), 1, Integer::sum);
        }
        return result;
    }

    /** Which face of a modal/split card to name (Rooms, Venture, card-face picks). */
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces == null || faces.isEmpty()) return null;
        if (faces.size() == 1) return faces.get(0);
        List<String> names = new ArrayList<>();
        for (ICardFace f : faces) names.add(f == null ? "?" : f.getName());
        List<Integer> sel = promptIndices(
                message == null || message.isEmpty() ? "Choose a card face" : message,
                names, 1, 1, false, "choose");
        return faces.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), faces.size() - 1));
    }

    /**
     * Which card state (Room half, unlocked door, ...) to choose.
     * 94 pool cards reach this; the AI answered every one of them.
     */
    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states,
            String message, Map<String, Object> params) {
        if (states == null || states.isEmpty()) return null;
        if (states.size() == 1) return states.get(0);
        List<String> names = new ArrayList<>();
        for (CardState s : states) names.add(s == null ? "?" : String.valueOf(s.getName()));
        List<Integer> sel = promptIndices(
                message == null || message.isEmpty() ? "Choose a card state" : message,
                names, 1, 1, false, "choose");
        return states.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), states.size() - 1));
    }

    /** Fact-or-Fiction style pile choice. The AI just took the bigger pile. */
    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1,
            CardCollectionView pile2, String faceUp) {
        List<String> names = new ArrayList<>();
        names.add("Pile 1: " + describePile(pile1, faceUp));
        names.add("Pile 2: " + describePile(pile2, faceUp));
        String host = (sa != null && sa.getHostCard() != null)
                ? sa.getHostCard().getName() : "effect";
        List<Integer> sel = promptIndices(host + ": choose a pile", names, 1, 1, false, "choose");
        return sel.isEmpty() || sel.get(0) == 0;      // true == pile 1
    }

    /** Face-down piles are listed by size only, or the choice would be no choice. */
    private static String describePile(CardCollectionView pile, String faceUp) {
        int n = pile == null ? 0 : pile.size();
        if (!"True".equals(faceUp)) return n + " card(s)";
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (Card c : pile) {
            if (shown++ > 0) b.append(", ");
            if (shown > 4) { b.append('…'); break; }
            b.append(c.getName());
        }
        return b.length() == 0 ? "(empty)" : b.toString();
    }

    /** "Put a counter of your choice" - which kind. */
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa,
            String prompt, Map<String, Object> params) {
        if (options == null || options.isEmpty()) return null;
        if (options.size() == 1) return options.get(0);
        List<String> names = new ArrayList<>();
        for (CounterType t : options) names.add(String.valueOf(t));
        List<Integer> sel = promptIndices(
                prompt == null || prompt.isEmpty() ? "Choose a counter type" : prompt,
                names, 1, 1, false, "choose");
        return options.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), options.size() - 1));
    }

    /** Council's-Judgment-style voting. The AI voted on the human's behalf. */
    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
            ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        if (options == null || options.isEmpty()) return null;
        if (options.size() == 1) return options.get(0);
        List<String> names = new ArrayList<>();
        for (Object o : options) names.add(String.valueOf(o));
        List<Integer> sel = promptIndices(
                prompt == null || prompt.isEmpty() ? "Vote" : prompt,
                names, 1, 1, optional, "choose");
        if (sel.isEmpty()) return optional ? null : options.get(0);
        return options.get(Math.min(sel.get(0), options.size() - 1));
    }

    // ---- Batch 2 of the ai_decision_allowlist sweep ---------------------------

    /** Clash: keep the revealed card on top, or put it on the bottom. */
    @Override
    public boolean willPutCardOnTop(Card c) {
        List<String> names = new ArrayList<>();
        names.add("Top of your library");
        names.add("Bottom of your library");
        String card = c != null ? c.getName() : "the revealed card";
        List<Integer> sel = promptIndices("Clash — put " + card + " where?",
                names, 1, 1, false, "choose");
        return sel.isEmpty() || sel.get(0) == 0;
    }

    /** Delve: which cards to exile from your graveyard to pay generic mana. */
    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        if (grave == null || grave.isEmpty() || genericAmount <= 0) {
            return CardCollection.EMPTY;
        }
        // Optional: Delve never forces you to exile anything, so min is 0.
        return chooseCardsFrom("Delve — exile up to " + genericAmount
                + " card(s) from your graveyard", grave, 0,
                Math.min(genericAmount, grave.size()));
    }

    /** Splice onto Arcane: which cards in hand to splice onto this spell. */
    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        List<Card> out = new ArrayList<>();
        if (cards == null || cards.isEmpty()) return out;
        List<String> names = new ArrayList<>();
        for (Card c : cards) names.add(c == null ? "?" : c.getName());
        // Splicing costs mana, so it must be declinable - hence optional/min 0.
        List<Integer> sel = promptIndices("Splice onto this spell? (none = no splice)",
                names, 0, names.size(), true, "choose");
        for (int idx : sel) {
            if (idx >= 0 && idx < cards.size()) out.add(cards.get(idx));
        }
        return out;
    }

    /** "Choose a colour" for protection (Iona, Glory, ...). */
    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        if (choices == null || choices.isEmpty()) return null;
        if (choices.size() == 1) return choices.get(0);
        String host = (sa != null && sa.getHostCard() != null)
                ? sa.getHostCard().getName() : "effect";
        List<Integer> sel = promptIndices(host + ": choose protection from",
                choices, 1, 1, false, "choose");
        return choices.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), choices.size() - 1));
    }

    /** Which spell on the stack to copy / target (CopySpellAbility effects). */
    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells,
            SpellAbility sa, String title, Map<String, Object> params) {
        if (spells == null || spells.isEmpty()) return null;
        if (spells.size() == 1) return spells.get(0);
        List<String> names = new ArrayList<>();
        for (SpellAbility s : spells) {
            String d = s == null ? null : s.getDescription();
            if (d == null || d.isEmpty()) {
                d = (s != null && s.getHostCard() != null)
                        ? s.getHostCard().getName() : String.valueOf(s);
            }
            names.add(d);
        }
        List<Integer> sel = promptIndices(
                title == null || title.isEmpty() ? "Choose a spell" : title,
                names, 1, 1, false, "choose");
        return spells.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), spells.size() - 1));
    }

    // ---- Batch 3: dice (92 pool cards) ----------------------------------------
    // Every one of these is a stub in PlayerControllerAi, not a considered AI
    // choice: four return Aggregates.random(), chooseDiceToReroll returns an
    // empty list (so you never reroll) and payCostDuringRoll returns false (so
    // you can never pay to reroll or modify). Dice cards were therefore either
    // random or inert on the bridge.

    private static List<String> intNames(List<Integer> rolls) {
        List<String> names = new ArrayList<>();
        for (Integer r : rolls) names.add(String.valueOf(r));
        return names;
    }

    /** Which of the rolled dice to reroll (none is a valid answer). */
    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        List<Integer> out = new ArrayList<>();
        if (rolls == null || rolls.isEmpty()) return out;
        List<Integer> sel = promptIndices("Choose dice to reroll (none = keep all)",
                intNames(rolls), 0, rolls.size(), true, "choose");
        for (int idx : sel) {
            if (idx >= 0 && idx < rolls.size()) out.add(rolls.get(idx));
        }
        return out;
    }

    /** Which roll to ignore. */
    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        if (rolls == null || rolls.isEmpty()) return null;
        if (rolls.size() == 1) return rolls.get(0);
        List<Integer> sel = promptIndices("Choose a roll to ignore",
                intNames(rolls), 1, 1, false, "choose");
        return rolls.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), rolls.size() - 1));
    }

    /** Which roll to modify. */
    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        if (rolls == null || rolls.isEmpty()) return null;
        if (rolls.size() == 1) return rolls.get(0);
        List<Integer> sel = promptIndices("Choose a roll to modify",
                intNames(rolls), 1, 1, false, "choose");
        return rolls.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), rolls.size() - 1));
    }

    /** Which roll result to swap. */
    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(
            List<RollDiceEffect.DieRollResult> rolls) {
        if (rolls == null || rolls.isEmpty()) return null;
        if (rolls.size() == 1) return rolls.get(0);
        List<String> names = new ArrayList<>();
        for (RollDiceEffect.DieRollResult r : rolls) names.add(String.valueOf(r));
        List<Integer> sel = promptIndices("Choose a roll to swap", names, 1, 1, false, "choose");
        return rolls.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), rolls.size() - 1));
    }

    /** What value to swap a roll to. */
    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult,
            int power, int toughness) {
        if (swapChoices == null || swapChoices.isEmpty()) return null;
        if (swapChoices.size() == 1) return swapChoices.get(0);
        List<Integer> sel = promptIndices(
                "Swap the roll (" + currentResult + ") to which value?",
                swapChoices, 1, 1, false, "choose");
        return swapChoices.get(sel.isEmpty() ? 0
                : Math.min(sel.get(0), swapChoices.size() - 1));
    }

    /**
     * Pay a cost to reroll / modify a die. The AI hardcoded false, so the option
     * was never even offered - "you may pay {1} to reroll" silently never paid.
     */
    // Asked the question and threw the answer away, exactly as payCombatCost
    // did: "you may pay {1} to reroll" charged nothing, so the reroll was free
    // and anything keyed on the cost actually being paid never happened.
    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        String host = (sa != null && sa.getHostCard() != null)
                ? sa.getHostCard().getName() : "effect";
        String what = cost == null ? "the cost" : cost.toSimpleString();
        List<String> names = new ArrayList<>();
        names.add("Pay " + what);
        names.add("Decline");
        List<Integer> sel = promptIndices(host + ": pay " + what + "?",
                names, 1, 1, false, "confirm");
        if (sel.isEmpty() || sel.get(0) != 0) {
            return false;
        }
        if (cost == null || sa == null) {
            return true;
        }
        try {
            return PlaySpellAbility.payCostDuringAbilityResolve(this, getPlayer(), cost, sa, null);
        } catch (Exception e) {
            System.err.println("[bridge] roll cost payment failed for " + host + ": " + e);
            return false;
        }
    }

    // ---- Batch 4: combat and cast-timing --------------------------------------

    /**
     * Which attackers to exert ("you may exert this as it attacks").
     *
     * The AI answered via AiAttackController with its own aggression score, so
     * the human's creatures were exerted on someone else's risk appetite.
     *
     * The returned list MUST be mutable and non-null: PhaseHandler reassigns it
     * and then calls addAll() on it with the enlist list, so an immutable empty
     * list throws UnsupportedOperationException and null throws NPE.
     */
    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        List<Card> out = new ArrayList<>();
        if (attackers == null || attackers.isEmpty()) return out;
        List<String> names = new ArrayList<>();
        for (Card c : attackers) names.add(c == null ? "?" : c.getName());
        List<Integer> sel = promptIndices("Exert which attackers? (none = exert nothing)",
                names, 0, names.size(), true, "choose");
        for (int idx : sel) {
            if (idx >= 0 && idx < attackers.size()) out.add(attackers.get(idx));
        }
        return out;
    }

    /**
     * Which attackers to enlist with. The AI enlisted the maximum every time;
     * enlisting taps one of your untapped creatures, so "always max" quietly
     * spends your blockers. Same mutability requirement as exertAttackers.
     */
    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        List<Card> out = new ArrayList<>();
        if (attackers == null || attackers.isEmpty()) return out;
        List<String> names = new ArrayList<>();
        for (Card c : attackers) names.add(c == null ? "?" : c.getName());
        List<Integer> sel = promptIndices("Enlist with which attackers? (none = no enlist)",
                names, 0, names.size(), true, "choose");
        for (int idx : sel) {
            if (idx >= 0 && idx < attackers.size()) out.add(attackers.get(idx));
        }
        return out;
    }

    /**
     * Pay an attack tax. Two different things wear this method: a
     * Propaganda-style tax, and the OPTIONAL attack costs -- exert and enlist --
     * which CombatUtil.checkPropagandaEffects routes here as well.
     *
     * Answering the question was never the whole job. This asked "Pay {2}?" and
     * returned the answer, and nothing ever paid the cost: the attack went in
     * for free past Ghostly Prison, and -- because a cost that is never paid is
     * a cost that never happened -- Exert<1/CARDNAME> left the creature
     * unexerted, so the Exerted trigger never fired. Glorybringer attacked,
     * dealt its combat damage, and never asked for the 4-damage target. That is
     * the report this fixes.
     *
     * PlayerControllerHuman hands the whole thing to
     * PlaySpellAbility.payCostDuringAbilityResolve, which walks the cost parts
     * through getCostDecisionMaker (our BridgeCostDecision, so each part is the
     * player's choice) and finishes any mana part on the auto-tapper. Do the
     * same, but keep the explicit Pay/Decline question in front of it: the mana
     * branch is answered by the AI's payManaCost, which never declines, and
     * declining a tax is a real decision -- it costs you the attack.
     */
    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        String what = cost == null ? "the cost" : cost.toSimpleString();
        String who = card != null ? card.getName() : "this creature";
        List<String> names = new ArrayList<>();
        names.add("Pay " + what);
        names.add("Decline (remove " + who + " from combat)");
        List<Integer> sel = promptIndices(
                (prompt == null || prompt.isEmpty() ? who + ": pay " + what + "?" : prompt),
                names, 1, 1, false, "confirm");
        if (sel.isEmpty() || sel.get(0) != 0) {
            return false;
        }
        if (cost == null || sa == null) {
            return true;
        }
        try {
            return PlaySpellAbility.payCostDuringAbilityResolve(this, getPlayer(), cost, sa, prompt);
        } catch (Exception e) {
            // Returning true is what the old code did by omission, and it is the
            // wrong side to fail on: it puts an unpaid attacker into combat.
            System.err.println("[bridge] combat cost payment failed for " + who + ": " + e);
            return false;
        }
    }

    /**
     * Opening-hand abilities (Leyline, Chancellor, Gemstone Caverns).
     * The returned ORDER is the resolution order (CR 103.5), which is why this
     * asks for an ordered multi-select rather than a set of checkboxes.
     */
    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(
            List<SpellAbility> usableFromOpeningHand) {
        List<SpellAbility> out = new ArrayList<>();
        if (usableFromOpeningHand == null || usableFromOpeningHand.isEmpty()) return out;
        List<String> names = new ArrayList<>();
        for (SpellAbility s : usableFromOpeningHand) {
            String d = s == null ? null : s.getDescription();
            if (d == null || d.isEmpty()) {
                d = (s != null && s.getHostCard() != null)
                        ? s.getHostCard().getName() : String.valueOf(s);
            }
            names.add(d);
        }
        List<Integer> sel = promptIndices(
                "Activate from your opening hand? (in order; none = skip)",
                names, 0, names.size(), true, "choose");
        for (int idx : sel) {
            if (idx >= 0 && idx < usableFromOpeningHand.size()) {
                out.add(usableFromOpeningHand.get(idx));
            }
        }
        return out;
    }

    /**
     * Cast a card an effect is letting you cast (cascade, Discover, PlayEffect).
     *
     * A MANDATORY one must not be declinable - DiscoverEffect marks its cost
     * mandatory, and silently not casting it would be a rules violation, not a
     * choice. Only genuinely optional ones get the prompt.
     */
    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        if (tgtSA == null) return false;
        boolean mandatory = tgtSA.getPayCosts() != null && tgtSA.getPayCosts().isMandatory();
        if (!mandatory) {
            String what = tgtSA.getHostCard() != null
                    ? tgtSA.getHostCard().getName() : String.valueOf(tgtSA);
            List<String> names = new ArrayList<>();
            names.add("Cast " + what);
            names.add("Decline");
            // Cascade asks TWICE — PlayEffect's "do you want to play it?" and
            // then this one — so both windows need the card, or the second is
            // the bare yes/no the first one stopped being.
            List<Integer> sel = promptIndices("Cast " + what + "?", names, 1, 1, false, "confirm",
                    tgtSA.getHostCard());
            if (sel.isEmpty() || sel.get(0) != 0) return false;
        }
        return PlaySpellAbility.playSpellAbility(this, getPlayer(), tgtSA);
    }

    /**
     * Choose new targets for an ability (Misdirection, Deflecting Swat, any
     * ChangeTargets effect).
     *
     * PlayerControllerAi returns null unconditionally - "AI currently can't do
     * this" - so every redirect spell was a silent no-op on the bridge. The real
     * work is a side effect: the targets on `ability` are mutated in place, and
     * the return value only signals success. PlayerControllerHuman uses
     * TargetSelection from forge-gui, which this module cannot see, so this
     * reuses the bridge's own target picker instead.
     */
    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability,
            Predicate<GameObject> filter, boolean optional) {
        if (ability == null) return null;
        TargetChoices previous = ability.getTargets();
        if (optional) {
            List<String> names = new ArrayList<>();
            names.add("Choose new targets");
            names.add("Keep current targets");
            List<Integer> sel = promptIndices("Change targets?", names, 1, 1, false, "confirm");
            if (sel.isEmpty() || sel.get(0) != 0) return null;
        }
        if (!pickTargetsForSA(ability)) {
            // Cancelled or no legal target: restore what was there rather than
            // leaving the ability with no targets at all.
            ability.setTargets(previous);
            return null;
        }
        return ability.getTargets();
    }

    // ---- Batch 5: the rest of the sweep ---------------------------------------

    /**
     * How many times to pay an optional keyword cost (Multikicker, Replicate,
     * Squad, Casualty, Offspring, Conspire, Harmonize).
     *
     * The AI paid the MAXIMUM it could afford, so every kicker spell was
     * silently kicked to the limit of the player's mana. Callers pass
     * max = Integer.MAX_VALUE, so the offered range needs its own cap - Forge's
     * own human client uses 9 for exactly this reason.
     */
    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost,
            KeywordInterface keyword, String prompt, int max) {
        if (max <= 0) return 0;
        String label = prompt;
        if (label == null || label.isEmpty()) {
            String kw = keyword == null ? "this cost" : String.valueOf(keyword.getKeyword());
            label = "Pay " + kw + (cost == null ? "" : " (" + cost.toSimpleString() + ")") + "?";
        }
        if (max == 1) {
            List<String> yn = new ArrayList<>();
            yn.add("Pay");
            yn.add("Decline");
            List<Integer> sel = promptIndices(label, yn, 1, 1, false, "confirm");
            return (!sel.isEmpty() && sel.get(0) == 0) ? 1 : 0;
        }
        int cap = Math.min(max, 9);          // max is often Integer.MAX_VALUE
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= cap; i++) names.add(String.valueOf(i));
        List<Integer> sel = promptIndices(label + " (how many times?)",
                names, 1, 1, false, "choose");
        return sel.isEmpty() ? 0 : Math.min(sel.get(0), cap);
    }

    /**
     * Apply an optional static/replacement effect? The AI hardcoded true, so
     * these were always accepted - including the four combat-damage-assignment
     * statics ("assign damage as though it weren't blocked", "divided as you
     * choose"), where declining is often the right play.
     */
    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode,
            String message, String logic) {
        String host = hostCard != null ? hostCard.getName() : "effect";
        List<String> yn = new ArrayList<>();
        yn.add("Yes");
        yn.add("No");
        List<Integer> sel = promptIndices(
                (message == null || message.isEmpty()) ? host + ": apply this effect?" : message,
                yn, 1, 1, false, "confirm");
        return !sel.isEmpty() && sel.get(0) == 0;
    }

    /** One of two named alternatives (tap/untap, play/draw, odds/evens, ...). */
    @Override
    public boolean chooseBinary(SpellAbility sa, String question,
            BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        String[] labels = binaryLabels(kindOfChoice);
        List<String> names = new ArrayList<>();
        names.add(labels[0]);
        names.add(labels[1]);
        List<Integer> sel = promptIndices(
                (question == null || question.isEmpty()) ? "Choose" : question,
                names, 1, 1, false, "confirm");
        if (sel.isEmpty()) return defaultChoice != null && defaultChoice;
        return sel.get(0) == 0;
    }

    /**
     * The params overload. PlayerControllerAi overrides this one SEPARATELY
     * (routing it to SpellApiToAi), so overriding only the Boolean version would
     * leave CountersPutOrRemove and TimeTravel still answered by the AI.
     */
    @Override
    public boolean chooseBinary(SpellAbility sa, String question,
            BinaryChoiceType kindOfChoice, Map<String, Object> params) {
        return chooseBinary(sa, question, kindOfChoice, (Boolean) null);
    }

    /** Human-readable sides for each BinaryChoiceType. */
    private static String[] binaryLabels(BinaryChoiceType kind) {
        if (kind == null) return new String[] {"Yes", "No"};
        switch (kind) {
            case HeadsOrTails:       return new String[] {"Heads", "Tails"};
            case TapOrUntap:         return new String[] {"Tap", "Untap"};
            case PlayOrDraw:         return new String[] {"Play", "Draw"};
            case OddsOrEvens:        return new String[] {"Odds", "Evens"};
            case UntapOrLeaveTapped: return new String[] {"Untap", "Leave tapped"};
            case LeftOrRight:        return new String[] {"Left", "Right"};
            case AddOrRemove:        return new String[] {"Add", "Remove"};
            case IncreaseOrDecrease: return new String[] {"Increase", "Decrease"};
            default:                 return new String[] {"Yes", "No"};
        }
    }

    /** Which flip result to keep (Krark's Thumb and friends). AI was random. */
    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        List<String> names = new ArrayList<>();
        names.add("Heads");
        names.add("Tails");
        List<Integer> sel = promptIndices(call ? "Call the flip" : "Keep which result?",
                names, 1, 1, false, "confirm");
        return sel.isEmpty() || sel.get(0) == 0;
    }

    /** Which keyword a pump effect grants. The AI picked at random. */
    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa,
            String prompt, Card tgtCard) {
        if (options == null || options.isEmpty()) return null;
        if (options.size() == 1) return options.get(0);
        String who = tgtCard != null ? tgtCard.getName() : "target";
        List<Integer> sel = promptIndices(
                (prompt == null || prompt.isEmpty()) ? "Grant which keyword to " + who + "?" : prompt,
                options, 1, 1, false, "choose");
        return options.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), options.size() - 1));
    }

    /** Spellskite-style redirection: which of the spell's targets to steal. */
    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa,
            List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        if (allTargets == null || allTargets.isEmpty()) return null;
        if (allTargets.size() < 2) return allTargets.get(0);
        List<String> names = new ArrayList<>();
        for (Pair<SpellAbilityStackInstance, GameObject> p : allTargets) {
            names.add(String.valueOf(p.getValue()));
        }
        List<Integer> sel = promptIndices("Redirect which target?", names, 1, 1, false, "choose");
        // Never null: ChangeTargetsEffect calls .getKey() on the result.
        return allTargets.get(sel.isEmpty() ? 0 : Math.min(sel.get(0), allTargets.size() - 1));
    }

    /** Choose colours. The AI's heuristic ignored min/max and under-filled. */
    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max,
            ColorSet options) {
        if (options == null) return ColorSet.fromMask(0);
        List<String> names = options.stream().map(MagicColor.Color::getName)
                .collect(Collectors.toList());
        if (names.isEmpty()) return ColorSet.fromMask(0);
        int hi = Math.min(Math.max(max, 1), names.size());
        int lo = Math.min(Math.max(min, 0), hi);
        List<Integer> sel = promptIndices(
                (message == null || message.isEmpty()) ? "Choose colour(s)" : message,
                names, lo, hi, lo == 0, "choose");
        List<String> picked = new ArrayList<>();
        for (int idx : sel) {
            if (idx >= 0 && idx < names.size()) picked.add(names.get(idx));
        }
        // ChooseColorEffect NPEs on null and reads isColorless() as "declined".
        while (picked.size() < lo && picked.size() < names.size()) {
            for (String n : names) {
                if (!picked.contains(n)) { picked.add(n); break; }
            }
        }
        echoOwnChoice(sa, String.join(", ", picked));
        return ColorSet.fromNames(picked);
    }

    /**
     * Ordering of competing cost-reduction statics. NOT prompted: Forge's own
     * human client auto-answers this too. It is overridden only because
     * returning null makes CostAdjustment loop forever - remove(null) never
     * shrinks the list, so the game thread hangs rather than crashing.
     */
    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.isEmpty()) return null;
        return possibleReplacers.get(0);
    }

    /**
     * Order in which cost parts are paid. NOT prompted - Forge's human client
     * returns the list unchanged unless a full-control flag is set, and a
     * returned list that DROPS an element silently skips paying that cost.
     */
    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    /** Which cards to reveal from hand. The AI took the first N positionally. */
    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max,
            CardCollectionView valid) {
        if (valid == null || valid.isEmpty()) return CardCollection.EMPTY;
        int hi = Math.min(max, valid.size());
        int lo = Math.min(min, hi);
        return chooseCardsFrom("Reveal " + rangeText(lo, hi) + " card(s) from your hand",
                valid, lo, hi);
    }

    /**
     * "Discard N cards unless you discard a <type>." Mandatory - the player only
     * chooses WHICH. Never return null: orderCardsByTheirOwners calls size().
     */
    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand,
            String[] unlessTypes, SpellAbility sa) {
        if (hand == null || hand.isEmpty()) return CardCollection.EMPTY;
        String types = unlessTypes == null ? "" : String.join(" / ", unlessTypes);
        int hi = Math.min(Math.max(min, 1), hand.size());
        return chooseCardsFrom("Discard " + hi + " card(s), or one "
                + (types.isEmpty() ? "of the named type" : types), hand, hi, hi);
    }

    /**
     * Generic "choose N cards for this effect".
     *
     * Combat.java, CamouflageEffect and FlipOntoBattlefieldEffect all call
     * .get(0) / .getFirst() on the result, so an empty return is a crash there -
     * hence the backfill when the choice is mandatory.
     */
    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList,
            SpellAbility sa, String title, int min, int max, boolean isOptional,
            Map<String, Object> params) {
        if (sourceList == null || sourceList.isEmpty()) return CardCollection.EMPTY;
        int hi = Math.min(max <= 0 ? sourceList.size() : max, sourceList.size());
        int lo = isOptional ? 0 : Math.min(Math.max(min, 0), hi);
        CardCollection out = chooseCardsFrom(
                (title == null || title.isEmpty()) ? "Choose card(s)" : title,
                sourceList, lo, hi);
        if (out.isEmpty() && !isOptional && min >= 1) {
            out.add(sourceList.getFirst());
        }
        return out;
    }

    /**
     * One card per named category. Returning empty when the choice is MANDATORY
     * makes DigMultipleEffect re-prompt forever, so the mandatory case is
     * backfilled rather than allowed through empty.
     */
    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
            SpellAbility sa, String title, boolean isOptional) {
        CardCollection chosen = new CardCollection();
        if (validMap == null || validMap.isEmpty()) return chosen;
        for (Map.Entry<String, CardCollection> e : validMap.entrySet()) {
            CardCollection bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) continue;
            CardCollection pick = chooseCardsFrom(
                    ((title == null || title.isEmpty()) ? "Choose" : title)
                            + " - " + e.getKey(), bucket, 0, 1);
            for (Card c : pick) {
                if (!chosen.contains(c)) chosen.add(c);
            }
        }
        if (chosen.isEmpty() && !isOptional) {
            for (CardCollection bucket : validMap.values()) {
                if (bucket != null && !bucket.isEmpty()) {
                    chosen.add(bucket.getFirst());
                    break;
                }
            }
        }
        return chosen;
    }

    /**
     * Convoke / Improvise: which of your permanents tap to help pay.
     *
     * Distinct from the mana auto-tapper (payManaCost), which is deliberately
     * left to Forge - this taps CREATURES, so an automatic choice spends your
     * blockers. An empty map is a legal "convoke nothing".
     */
    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa,
            ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts,
            boolean creatures, Integer maxReduction) {
        if (untappedCards == null || untappedCards.isEmpty()) {
            return new HashMap<>();
        }
        if (forge.game.cost.CostAdjustment.isTestPayment()) {
            // A castability probe, not a payment: the AI's mana checker runs
            // this path for "could this be paid?" too, and it probes once per
            // candidate and again after every answer. Reported 2026-09-10:
            // "Tap for Improvise?" opened before X was announced and reopened
            // on every Confirm, so nothing seemed selectable. Answer the probe
            // the way the AI would (tap whatever helps) and ask the player
            // only when the mana is really being paid.
            return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards,
                    artifacts, creatures, maxReduction);
        }
        int hi = untappedCards.size();
        if (maxReduction != null && maxReduction > 0) hi = Math.min(hi, maxReduction);
        CardCollection picked = chooseCardsFrom(
                "Tap for " + (artifacts && !creatures ? "Improvise" : "Convoke")
                        + "? (none = pay normally)", untappedCards, 0, hi);
        if (picked.isEmpty()) return new HashMap<>();
        // Let Forge work out which shard each chosen permanent pays; the player
        // decides WHICH cards are spent, not the shard bookkeeping.
        return ComputerUtilMana.getConvokeOrImproviseFromList(manaCost, picked,
                artifacts, creatures);
    }

    // ---- Batch 6: naming a card (Meddling Mage, Nevermore, Pithing Needle) ----

    /**
     * Name any card in Magic, filtered by `cpp`.
     *
     * PlayerControllerAi throws UnsupportedOperationException here, so a human
     * reaching this crashed the game outright rather than merely being decided
     * for. The candidate set is the whole card database (~33k faces), which no
     * plain option list can render - the client gets prompt_type "name_card"
     * and shows a type-to-filter box instead of a scrollable list.
     *
     * Mirrors PlayerControllerHuman, which routes both chooseCardName overloads
     * through chooseSingleCardFace.
     */
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message,
            Predicate<ICardFace> cpp, String name) {
        List<ICardFace> faces = new ArrayList<>();
        try {
            for (ICardFace f : StaticData.instance().getCommonCards().getAllFaces()) {
                if (f != null && (cpp == null || cpp.test(f))) faces.add(f);
            }
        } catch (Exception e) {
            // No card DB reachable: better to decline than to crash the game.
            return null;
        }
        if (faces.isEmpty()) return null;
        faces.sort(Comparator.comparing(ICardFace::getName));
        List<String> names = new ArrayList<>();
        for (ICardFace f : faces) names.add(f.getName());
        List<Integer> sel = promptIndices(
                (message == null || message.isEmpty()) ? "Name a card" : message,
                names, 1, 1, false, "name_card");
        if (sel.isEmpty()) return null;
        int idx = Math.min(Math.max(sel.get(0), 0), faces.size() - 1);
        return faces.get(idx);
    }

    /** Name a card, unrestricted (Meddling Mage, Nevermore, Pithing Needle). */
    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid,
            String message) {
        String host = (sa != null && sa.getHostCard() != null) ? sa.getHostCard().getName() : "";
        ICardFace face = chooseSingleCardFace(sa, message, cpp, host);
        return face == null ? "" : face.getName();
    }

    /** Name a card from a supplied shortlist - routes to the batch-1 override. */
    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        ICardFace face = chooseSingleCardFace(sa, faces, message);
        return face == null ? "" : face.getName();
    }

    // ---- Additional human decision points (were silently defaulting to the AI) ----

    /**
     * Optional ("may") triggered ability: ask the human whether to use it.
     *
     * This question ALWAYS arrives after the target prompt, and that is correct
     * even though it reads backwards. An optional trigger goes on the stack with
     * its targets already chosen (CR 603.3c); the "you may" is part of the
     * effect and is not decided until the ability resolves, which is what lets
     * you decline after seeing what everyone did in response. Forge implements
     * exactly that -- TriggerHandler.runSingleTrigger puts the wrapper on the
     * stack, and WrappedAbility.resolve() calls this method.
     *
     * What was wrong is that the question did not say so. "Use Hissing
     * Iguanar's triggered ability?" names no target, mentions no trigger, and
     * lands minutes of game-time after the thing that caused it -- so it reads
     * as a brand new offer to activate something. That is the report: "my
     * hissing iguanar triggers, i choose target for the damage, then i get
     * another prompt asking if i want to activate its ability."
     *
     * So say what is resolving and at whom, and put the card on screen with it.
     */
    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        SpellAbility sa = wrapper == null ? null : wrapper.getWrappedAbility();
        // Triggers with a payable cost are declined by simply not paying — let the
        // engine handle those so we don't double-prompt.
        if (sa != null && sa.hasParam("Cost") && !"0".equals(sa.getParam("Cost"))) {
            return true;
        }
        Card host = sa != null ? sa.getHostCard() : null;
        String name = host != null ? host.getName() : "ability";
        String what = triggerResolveText(wrapper, sa);
        List<String> names = new ArrayList<>();
        names.add("Yes, use it");
        names.add("No, decline");
        List<Integer> sel = promptIndices(
                name + "'s trigger is resolving" + what + " Use it?",
                names, 1, 1, false, "confirm", host);
        return !sel.isEmpty() && sel.get(0) == 0;
    }

    /**
     * ", targeting AI 2." for the confirm above -- the one fact the player needs
     * to connect this question to the target prompt they answered earlier.
     *
     * Deliberately NOT getStackDescription. That reads
     * "Whenever another creature dies, you may have Hissing Iguanar deal 1
     * damage to target player or planeswalker. (Targeting: [[Computer]])
     * [Zone Changer: Grizzly Bears (89)]" -- the card's whole rules text, which
     * the card image beside the prompt is already showing, wrapped around two
     * kinds of engine annotation. The targets alone say the new thing.
     */
    private static String triggerResolveText(WrappedAbility wrapper, SpellAbility sa) {
        try {
            SpellAbility targeted = sa;
            if (targeted == null || targeted.getTargets() == null
                    || !targeted.getTargets().getTargetEntities().iterator().hasNext()) {
                targeted = wrapper;
            }
            if (targeted == null || targeted.getTargets() == null) {
                return ".";
            }
            StringBuilder names = new StringBuilder();
            for (GameEntity ge : targeted.getTargets().getTargetEntities()) {
                if (ge == null) {
                    continue;
                }
                if (names.length() > 0) {
                    names.append(" and ");
                }
                names.append(entityName(ge));
            }
            return names.length() == 0 ? "." : ", targeting " + names + ".";
        } catch (Exception e) {   // a prompt must never be the thing that breaks
            return ".";
        }
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
        // A mana-only unless cost never reaches a cost visitor: it goes straight
        // to payManaCost, which is the AI's -- deliberately, so the auto-tapper
        // picks the lands. But WHICH lands and WHETHER to pay at all are
        // different questions, and only the first is meant to be automatic.
        // Without this, every "unless you pay {1}" -- Rhystic Study being the
        // famous one -- paid itself out of your mana with no prompt.
        if (cost != null && sa != null && cost.isOnlyManaCost()
                && !cost.getTotalMana().isZero() && !cost.isMandatory()) {
            String host = sa.getHostCard() != null ? sa.getHostCard().getName() : "this";
            if (!yesNo("Pay " + cost.toSimpleString() + " for " + host + "?")) {
                return false;
            }
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
            max = Math.min(max, announceCeiling(ability, min, max));
            if (min > max) return null;
            if (min == max) return min;
            String host = ability.getHostCard() != null ? ability.getHostCard().getName() : "";
            return chooseNumber(ability, "Choose " + announce + " for " + host, min, max);
        } catch (Exception e) {
            // super is PlayerControllerAi, which answers an unbounded
            // announcement with `max`. Clamp whatever comes back rather than
            // letting Integer.MAX_VALUE out of this method.
            Integer fallback = super.announceRequirements(ability, min, max, announce);
            if (fallback == null) return null;
            return Math.max(min, Math.min(fallback, min + NUMBER_PROMPT_SPAN));
        }
    }

    /**
     * A ceiling for an announcement whose declared maximum is unbounded.
     *
     * AbilityUtils.getAnnouncementBounds hands out Integer.MAX_VALUE for any X
     * the card does not cap itself (XMax / AnnounceMax) -- which is every plain
     * {X} mana cost. PlayerControllerHuman lives with that because its Swing
     * prompt takes a typed integer; ours renders a list of options, and the AI
     * underneath it answers `max`. Torch the Witness ({X}{R}) was announced as
     * X = 2147483647 and took the JVM down in ManaCostBeingPaid.getUnpaidShards,
     * which allocates one list entry per X: OutOfMemoryError, and the match
     * dropped with no game_over.
     *
     * For an X in the mana cost the honest ceiling is the mana actually on hand,
     * from the same helper the auto-tapper reasons with -- so we never offer an
     * X that the payment step then cannot cover. Anything else unbounded gets
     * PlayerControllerHuman's own default cutoff of min + 9.
     */
    private int announceCeiling(SpellAbility ability, int min, int max) {
        Cost cost = ability.getPayCosts();
        if (cost != null && cost.hasManaCost() && cost.getTotalMana().countX() > 0) {
            return Math.max(min, ComputerUtilMana.determineLeftoverMana(ability, getPlayer(), false));
        }
        return max - min > NUMBER_PROMPT_SPAN ? min + 9 : max;
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
        List<Integer> sel;
        promptSource = chosen != null ? chosen.getHostCard() : null;
        try {
            sel = promptIndices(
                    host + ": pay optional additional cost? (none = decline)",
                    names, 0, names.size(), true, "choose");
        } finally {
            promptSource = null;
        }
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
        String chosen = sel.isEmpty() ? names.get(0) : names.get(sel.get(0));
        echoOwnChoice(sa, chosen);
        return chosen;
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
     * Combat damage assignment. Only a lone blocker plus trample used to reach
     * the player; two blockers went to super, which is
     * ComputerUtilCombat.distributeAIDamage -- so the AI decided which of your
     * attacker's blockers died, and how much of a trampler got through, on its
     * own risk appetite.
     *
     * The list arrives in the damage-assignment order the player already chose
     * through orderBlockers, so walking it in order and asking for each is the
     * whole decision. CR 510.1c holds by construction: the minimum offered for a
     * blocker is lethal damage to it (or everything left, if that is less),
     * because assigning less than lethal is only legal when nothing is assigned
     * past it -- and then the remainder has nowhere else to go anyway.
     */
    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
            CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        boolean trample = attacker != null && attacker.hasKeyword(Keyword.TRAMPLE) && defender != null;
        if (blockers == null || blockers.isEmpty() || damageDealt <= 0) {
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        try {
            Map<Card, Integer> map = new HashMap<>();
            int left = damageDealt;
            int n = blockers.size();
            for (int i = 0; i < n && left > 0; i++) {
                Card b = blockers.get(i);
                boolean last = (i == n - 1);
                if (last && !trample) {          // nowhere else for it to go
                    map.put(b, left);
                    left = 0;
                    break;
                }
                int lethal = Math.max(0, b.getLethalDamage());
                int lo = Math.min(lethal, left);
                if (lo >= left) {                // no spare damage to place
                    map.put(b, left);
                    left = 0;
                    break;
                }
                String where = last
                        ? " (rest tramples to " + entityName(defender) + ")"
                        : " (rest goes to the blockers behind it)";
                int toBlocker = chooseNumber(null,
                        "Assign damage to " + b.getName() + where, lo, left);
                if (toBlocker > 0) {
                    map.put(b, toBlocker);
                }
                left -= toBlocker;
            }
            if (left > 0) {
                if (trample) {
                    map.put(null, left);         // null is the defending entity
                } else {
                    Card lastBlocker = blockers.get(n - 1);
                    map.put(lastBlocker, map.getOrDefault(lastBlocker, 0) + left);
                }
            }
            return map;
        } catch (Exception e) {
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
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

    // ---- Practice-game cheats ---------------------------------------------
    //
    // Forge's desktop GUI has these under Dev Mode (IDevModeCheats, implemented
    // in PlayerControllerHuman); this is the same handful driven by the relay's
    // `sandbox` reply instead of a menu, on the game thread. Gated on
    // _sandbox.txt (see setSandbox): a real game never has the flag, so a stray
    // reply is refused with a Feed line, never applied.

    private void sandboxFeed(String line) {
        ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(line) + "\"]}");
    }

    private void sandboxCheat(Player me, String reply) {
        if (!sandbox) {
            sandboxFeed("Sandbox actions are off for this game.");
            return;
        }
        Game g = me.getGame();
        String cmd = parseStr(reply, "\"cmd\":");
        if (cmd == null) {
            cmd = "";
        }
        // Which seat the cheat is for: the reply's `player` is Forge's own
        // player index (the relay's seat index), absent means the asked seat.
        Player p = me;
        int seat = parseInt(reply, "\"player\":");
        if (seat >= 0 && seat < g.getPlayers().size()) {
            p = g.getPlayers().get(seat);
        }
        try {
            switch (cmd) {
                case "add_card":
                    sandboxAddCard(g, p, parseStr(reply, "\"name\":"), parseStr(reply, "\"zone\":"));
                    break;
                case "mana":
                    sandboxMana(g, p);
                    break;
                case "life": {
                    int v = parseInt(reply, "\"value\":");
                    if (v < 0) {
                        sandboxFeed("Sandbox: life needs a number.");
                        break;
                    }
                    p.setLife(v, null);
                    sandboxFeed("Sandbox: " + p.getName() + " is at " + v + " life.");
                    break;
                }
                case "draw": {
                    int n = parseInt(reply, "\"n\":");
                    if (n < 1) {
                        n = 1;
                    }
                    p.drawCards(n);
                    sandboxFeed("Sandbox: " + p.getName() + " draws " + n + ".");
                    break;
                }
                case "untap": {
                    int n = 0;
                    for (Card c : new CardCollection(p.getCardsIn(ZoneType.Battlefield))) {
                        if (c.isTapped()) {
                            c.untap();
                            n++;
                        }
                    }
                    sandboxFeed("Sandbox: untapped " + n + " of " + p.getName() + "'s permanents.");
                    break;
                }
                case "restart":
                    if (ForgeServer.reapplyScenario(g)) {
                        sandboxFeed("Sandbox: board reset to the starting scenario.");
                    } else {
                        sandboxFeed("Sandbox: this game has no starting scenario to go back to.");
                    }
                    break;
                default:
                    sandboxFeed("Sandbox: unknown command '" + cmd + "'.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sandboxFeed("Sandbox: that failed (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").");
        }
    }

    /** Put a fresh copy of a named card into one of a player's zones. */
    private void sandboxAddCard(Game g, Player p, String name, String zone) {
        if (name == null || name.isEmpty()) {
            sandboxFeed("Sandbox: no card name given.");
            return;
        }
        forge.item.PaperCard pc = StaticData.instance().getCommonCards().getUniqueByName(name);
        if (pc == null) {
            sandboxFeed("Sandbox: Forge has no card named '" + name + "'.");
            return;
        }
        Card c = Card.fromPaperCard(pc, p);
        c.setGameTimestamp(g.getNextTimestamp());
        String z = zone == null ? "hand" : zone.toLowerCase();
        ZoneType zt;
        switch (z) {
            case "battlefield": zt = ZoneType.Battlefield; break;
            case "graveyard": zt = ZoneType.Graveyard; break;
            case "library": zt = ZoneType.Library; break;
            case "exile": zt = ZoneType.Exile; break;
            default: zt = ZoneType.Hand; z = "hand";
        }
        if (zt == ZoneType.Battlefield) {
            if (!c.isPermanent() || c.isAura()) {
                sandboxFeed("Sandbox: " + name + " cannot be put straight onto the "
                        + "battlefield; add it to a hand and cast it.");
                return;
            }
            // Straight into play, no ETB triggers (the dev-mode "add to
            // battlefield" path), and never summoning sick: this is a board
            // being set up, not a creature being cast.
            g.getAction().moveTo(ZoneType.Battlefield, c, null, forge.game.ability.AbilityKey.newMap());
            if (c.isCreature()) {
                c.setSickness(false);
            }
        } else if (zt == ZoneType.Library) {
            g.getAction().moveToLibrary(c, null);
        } else {
            g.getAction().moveTo(zt, c, null, forge.game.ability.AbilityKey.newMap());
        }
        sandboxFeed("Sandbox: " + name + " added to " + p.getName() + "'s " + z + ".");
    }

    /** Float three of each colour and three colourless (Dev Mode's "Add mana"). */
    private void sandboxMana(Game g, Player p) {
        final Card dummy = new Card(-777777, g);
        dummy.setOwner(p);
        final java.util.Map<String, String> produced = new java.util.HashMap<>();
        produced.put("Produced", "W W W U U U B B B R R R G G G 3");
        final forge.game.spellability.AbilityManaPart abMana =
                new forge.game.spellability.AbilityManaPart(dummy, produced);
        abMana.produceMana(null);
        sandboxFeed("Sandbox: " + p.getName() + " floats WWW UUU BBB RRR GGG and 3 colourless "
                + "(gone at the end of this step).");
    }

    /** Crude JSON string extractor for a "key": "value" pair; null if absent. */
    private static String parseStr(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return null;
        int j = i + key.length();
        while (j < s.length() && s.charAt(j) == ' ') j++;
        if (j >= s.length() || s.charAt(j) != '"') return null;
        StringBuilder sb = new StringBuilder();
        for (int k = j + 1; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '\\' && k + 1 < s.length()) {
                sb.append(s.charAt(++k));
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
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
        String reply = ask("{\"kind\":\"declare_attackers\",\"eligible\":" + idList(eligible)
                + ",\"defenders\":" + defenderList(combat) + ",\"state\":" + state + "}");
        if (endIfAbandoned()) {
            // PhaseHandler re-asks until the declaration validates, and with the
            // client gone every answer is "" = attack with nothing. When a
            // creature MUST attack that never validates, so the engine spun on
            // this prompt forever: the 2026-09-10 pod Sage left at declare-
            // attackers was still "finishing a previous match" seven minutes
            // later, and every new game was refused. Ending the game here is
            // what the do/while checks for at the top of each pass.
            return;
        }
        String compact = reply.replaceAll("\\s", "");
        boolean all = compact.contains("\"attackers\":\"all\"");
        List<String> notes = new ArrayList<>();
        for (Card c : eligible) {
            if (!all && !inArray(compact, "attackers", c.getId())) continue;
            GameEntity target = defender;
            int pw = parseInt(compact, "\"attacker\":" + c.getId() + ",\"defender\":");
            if (pw > 0) {
                GameEntity chosen = findDefender(combat, pw);
                // Fall back to the player rather than dropping the attack if the
                // planeswalker has since left or the attack would be illegal.
                if (chosen != null && CombatUtil.canAttack(c, chosen)) {
                    target = chosen;
                } else if (chosen != null) {
                    notes.add(c.getName() + " can't attack " + defenderName(chosen)
                            + ", so it attacks " + defenderName(defender) + " instead");
                }
            }
            if (!CombatUtil.canAttack(c, target)) {
                // Eligible means "can attack SOMEONE"; the seat it was aimed at
                // may be off limits (goad, "can't attack you", must-attack-X).
                // Keep the attack by redirecting it, and say so.
                GameEntity other = null;
                for (GameEntity d : combat.getDefenders()) {
                    if (d != target && CombatUtil.canAttack(c, d)) { other = d; break; }
                }
                if (other == null) {
                    notes.add(c.getName() + " can't attack " + defenderName(target) + " and was left out of the attack");
                    continue;
                }
                notes.add(c.getName() + " can't attack " + defenderName(target)
                        + ", so it attacks " + defenderName(other) + " instead");
                target = other;
            }
            combat.addAttacker(c, target);
        }
        if (!all) {
            // A pick the engine never offered: the client's board was stale.
            for (Card c : attacker.getCreaturesInPlay()) {
                if (!eligible.contains(c) && inArray(compact, "attackers", c.getId())) {
                    notes.add(c.getName() + " can't attack this combat and was left out");
                }
            }
        }
        for (String n : notes) {
            logToFeed("Attack: " + n + ".");
        }
        explainIfInvalid(attacker, combat);
    }

    /**
     * Say WHY an attack is about to be refused.
     *
     * PhaseHandler.declareAttackersTurnBasedAction loops on
     * CombatUtil.validateAttackers, and when it says no the player gets a bare
     * "Attack declaration invalid" and the same prompt again -- with no hint
     * whether it was a must-attack, a can't-attack-alone, a goad, or a
     * cap on attackers. Reported from a live pod on 2026-09-10 ("I NEED THE
     * LOG TO TELL ME WHY"). This reproduces the engine's own checks, one by
     * one, and writes each violated constraint to the game log, which reaches
     * the Feed and the session log with the re-ask's state.
     *
     * Never throws: an explanation must not be the thing that breaks combat.
     */
    private void explainIfInvalid(Player attacker, Combat combat) {
        try {
            if (CombatUtil.validateAttackers(combat)) {
                return;
            }
            AttackConstraints ac = combat.getAttackConstraints();
            Map<Card, GameEntity> att = combat.getAttackersAndDefenders();
            List<String> why = new ArrayList<>();

            // Restrictions: any one of these makes the attack illegal outright.
            GlobalAttackRestrictions g = ac.getGlobalRestrictions();
            if (g.getMax() != null && att.size() > g.getMax()) {
                why.add("at most " + g.getMax() + (g.getMax() == 1 ? " creature" : " creatures")
                        + " can attack this combat, " + att.size() + " were declared");
            }
            for (Map.Entry<GameEntity, Integer> e : g.getDefenderMax().entrySet()) {
                long n = att.values().stream().filter(d -> d == e.getKey()).count();
                if (n > e.getValue()) {
                    why.add(e.getValue() == 0
                            ? defenderName(e.getKey()) + " can't be attacked this combat"
                            : "at most " + e.getValue() + " can attack " + defenderName(e.getKey()) + ", " + n + " were declared");
                }
            }
            for (Map.Entry<Card, GameEntity> e : att.entrySet()) {
                AttackRestriction r = ac.getRestrictions().get(e.getKey());
                if (r == null) continue;
                if (!r.canAttack(e.getValue())) {
                    why.add(e.getKey().getName() + " can't attack " + defenderName(e.getValue()));
                }
                for (AttackRestrictionType t : r.getViolation(att)) {
                    why.add(e.getKey().getName() + " " + restrictionText(t, att.size()));
                }
            }

            // Requirements: legal only if no other attack satisfies more of them.
            int mine = ac.countViolations(att);
            Pair<Map<Card, GameEntity>, Integer> best = ac.getLegalAttackers();
            if (mine >= 0 && mine > best.getRight()) {
                for (Card c : attacker.getCreaturesInPlay()) {
                    AttackRequirement req = ac.getRequirements().get(c);
                    if (req == null || !req.hasRequirement()) continue;
                    if (req.countViolations(att.get(c), att) <= 0) continue;
                    why.add(requirementText(c, req, att));
                }
                // "You must attack X with at least one creature": private to
                // AttackConstraints, so ask the static layer again.
                Multimap<GameEntity, StaticAbility> mustAttack =
                        StaticAbilityMustAttack.mustAttackSpecific(attacker, combat.getDefenders());
                Map<StaticAbility, List<GameEntity>> bySource = new HashMap<>();
                for (Map.Entry<GameEntity, StaticAbility> e : mustAttack.entries()) {
                    bySource.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
                }
                for (Map.Entry<StaticAbility, List<GameEntity>> e : bySource.entrySet()) {
                    boolean any = false;
                    for (GameEntity ge : e.getValue()) {
                        if (att.containsValue(ge)) { any = true; break; }
                    }
                    if (!any) {
                        List<String> names = new ArrayList<>();
                        for (GameEntity ge : e.getValue()) names.add(defenderName(ge));
                        why.add("you must attack " + String.join(" or ", names)
                                + " with at least one creature if able (" + sourceText(e.getKey()) + ")");
                    }
                }
                why.add("a legal attack leaves " + best.getRight() + " requirement"
                        + (best.getRight() == 1 ? "" : "s") + " unmet, yours leaves " + mine
                        + "; for example: " + attackText(best.getLeft()));
            }
            if (why.isEmpty()) {
                why.add("the engine refused it without naming a restriction or requirement (violations="
                        + mine + ", best possible=" + best.getRight() + ")");
            }
            logToFeed("Attack declaration invalid, declare again: " + String.join("; ", why) + ".");
        } catch (Exception e) {
            System.out.println("[forge-bridge] explainIfInvalid failed: " + e);
        }
    }

    private static String restrictionText(AttackRestrictionType t, int nAttackers) {
        switch (t) {
            case ONLY_ALONE:          return "can only attack alone, but " + nAttackers + " creatures are attacking";
            case NOT_ALONE:           return "can't attack alone";
            case NEED_TWO_OTHERS:     return "can't attack unless at least two other creatures attack";
            case NEED_BLACK_OR_GREEN: return "can't attack unless a black or green creature also attacks";
            case NEED_GREATER_POWER:  return "can't attack unless a creature with greater power also attacks";
            case NEVER:               return "can't attack";
            default:                  return "breaks an attack restriction (" + t + ")";
        }
    }

    /** "Goblin must attack AI 2 if able (goaded by AI 2)" and the like. */
    private String requirementText(Card c, AttackRequirement req, Map<Card, GameEntity> att) {
        StringBuilder b = new StringBuilder(c.getName());
        GameEntity now = att.get(c);
        List<String> must = new ArrayList<>();
        int anyone = Integer.MAX_VALUE;
        for (Pair<GameEntity, Integer> p : req.getSortedRequirements()) {
            anyone = Math.min(anyone, p.getRight());
        }
        for (Pair<GameEntity, Integer> p : req.getSortedRequirements()) {
            if (p.getRight() > 0 && p.getRight() > anyone) {
                must.add(defenderName(p.getLeft()));
            }
        }
        if (!must.isEmpty()) {
            b.append(" must attack ").append(String.join(" or ", must)).append(" if able");
        } else if (anyone != Integer.MAX_VALUE && anyone > 0) {
            b.append(" must attack if able");
        }
        if (now == null) {
            b.append(" (it isn't attacking)");
        } else if (!must.isEmpty() && !must.contains(defenderName(now))) {
            b.append(" (it is attacking ").append(defenderName(now)).append(")");
        }
        if (c.isGoaded()) {
            List<String> by = new ArrayList<>();
            for (Player p : c.getGoaded()) by.add(p.getName());
            b.append(", goaded by ").append(String.join(" and ", by));
        }
        // "If CARDNAME attacks, X attacks too": the forced creature is the key.
        for (Map.Entry<Card, Collection<StaticAbility>> e : req.getCausesToAttack().asMap().entrySet()) {
            if (att.containsKey(e.getKey())) continue;
            List<String> src = new ArrayList<>();
            for (StaticAbility s : e.getValue()) src.add(sourceText(s));
            b.append("; ").append(e.getKey().getName()).append(" must also attack when ")
             .append(c.getName()).append(" does (").append(String.join(", ", src)).append(")");
        }
        return b.toString();
    }

    private static String sourceText(StaticAbility s) {
        try {
            String host = s.getHostCard() != null ? s.getHostCard().getName() : "?";
            String desc = String.valueOf(s).trim();
            return desc.isEmpty() || desc.equals(host) ? host : host + ": " + desc;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String attackText(Map<Card, GameEntity> attack) {
        if (attack == null || attack.isEmpty()) return "attack with nothing";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Card, GameEntity> e : attack.entrySet()) {
            parts.add(e.getKey().getName() + " at " + defenderName(e.getValue()));
        }
        return String.join(", ", parts);
    }

    /** A defender as the table knows it: a seat's name, or "Jace (AI 2's planeswalker)". */
    private static String defenderName(GameEntity ge) {
        if (ge == null) return "nobody";
        if (ge instanceof Card) {
            Card c = (Card) ge;
            Player guard = c.isBattle() ? c.getProtectingPlayer() : c.getController();
            String kind = c.isBattle() ? "battle" : (c.isPlaneswalker() ? "planeswalker" : "permanent");
            return c.getName() + (guard == null ? "" : " (" + guard.getName() + "'s " + kind + ")");
        }
        return String.valueOf(ge);
    }

    /** One line into the shared game log, which flush_log forwards to every seat's Feed. */
    private void logToFeed(String message) {
        try {
            Player me = getPlayer();
            if (me == null || me.getGame() == null || message == null || message.isEmpty()) return;
            me.getGame().fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION, message));
        } catch (Exception e) {
            System.out.println("[forge-bridge] logToFeed failed: " + e);
        }
    }

    /**
     * Player ids and card ids are separate spaces in Forge and both start small,
     * so a defending player cannot be sent using its own id without risking a
     * collision with a planeswalker's. Players are offset into a range no card
     * id reaches instead.
     */
    private static final int PLAYER_DEFENDER_BASE = 1000000;

    /**
     * Every legal defender: planeswalkers and battles as themselves, and the
     * defending PLAYERS offset by PLAYER_DEFENDER_BASE.
     *
     * Players used to be filtered out of this list entirely, and declareAttackers
     * aimed every attacker at getDefendingPlayers().get(0). In a duel that is
     * invisible - there is only one opponent. In a Commander pod it meant every
     * attack hit the same seat with no way to see or change it.
     *
     * Cards used to go out as a flat "kind":"card", which left the client to
     * work out what each one was by scanning the opponents' battlefields for it.
     * That can never find a battle: a Siege enters under the control of the
     * player who CAST it and sits on their own battlefield, protected by an
     * opponent. So "defender" here is the seat that will block for it -- the
     * protector for a battle, the controller for a planeswalker -- which is also
     * the only way the player can be told who is defending the thing they are
     * attacking.
     */
    private static String defenderList(Combat combat) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (GameEntity d : combat.getDefenders()) {
            if (!(d instanceof Card)) continue;
            Card card = (Card) d;
            if (!first) b.append(',');
            first = false;
            Player guard = card.isBattle() ? card.getProtectingPlayer() : card.getController();
            b.append("{\"id\":").append(card.getId())
             .append(",\"name\":\"").append(StateExporter.esc(card.getName()))
             .append("\",\"kind\":\"").append(card.isBattle() ? "battle" : "planeswalker")
             .append("\",\"defender\":\"").append(StateExporter.esc(guard == null ? "" : guard.getName()))
             .append("\"}");
        }
        FCollectionView<Player> players = combat.getDefendingPlayers();
        for (int i = 0; i < players.size(); i++) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(PLAYER_DEFENDER_BASE + i)
             .append(",\"name\":\"").append(StateExporter.esc(players.get(i).getName()))
             .append("\",\"kind\":\"player\"}");
        }
        return b.append(']').toString();
    }

    /** The defender with this id: a planeswalker/battle, or an offset player. */
    private static GameEntity findDefender(Combat combat, int id) {
        if (id >= PLAYER_DEFENDER_BASE) {
            FCollectionView<Player> players = combat.getDefendingPlayers();
            int idx = id - PLAYER_DEFENDER_BASE;
            return (idx >= 0 && idx < players.size()) ? players.get(idx) : null;
        }
        for (GameEntity d : combat.getDefenders()) {
            if (d instanceof Card && ((Card) d).getId() == id) return d;
        }
        return null;
    }

    /**
     * Combat: blockers. Reply {"blocks":"none"} = no blocks;
     * {"blocks":[{"blocker":id,"attacker":id}, ...]} assigns blockers.
     */
    /**
     * Put "AI 1 picked red" in the game log.
     *
     * PlayerControllerAi.notifyOfValue is an EMPTY method -- the AI is told
     * about every choice another player makes and does nothing with it -- and
     * this controller inherits from it, so every notified value was silently
     * dropped for our players. Not just chosen colours: chosen numbers, types,
     * players, named cards, clash results, the lot.
     *
     * Reported from a live pod: an opponent's Realm-Cloaked Giant was missing
     * from a red spell's target list. It was wearing Pentarch Ward, whose AI
     * logic picks the most prominent colour in YOUR deck -- red -- so the Giant
     * had protection from red and was correctly untargetable. The Feed said
     * "As Pentarch Ward enters, choose a color." and then nothing at all, so
     * there was no way to learn that from inside the game.
     *
     * PlayerControllerHuman formats the same message and shows it in a modal;
     * a modal per notification is wrong for us (they arrive constantly and
     * nobody wants to click through them), so this goes to the game log, which
     * flush_log already forwards to every seat's Feed.
     *
     * The dedupe guard: GameAction.notifyOfValue calls this once per player
     * except the chooser, so with two humans in a pod both bridge controllers
     * fire and the line would appear twice in a log that is shared by the whole
     * table. Only the first controller to see a given (timestamp, message) logs
     * it. The cost is that a genuinely repeated identical message within one
     * timestamp is shown once -- much the better failure of the two.
     */
    private static String lastNotified = null;

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject relatedTarget, String value) {
        super.notifyOfValue(saSource, relatedTarget, value);
        try {
            Player me = getPlayer();
            if (me == null || me.getGame() == null) {
                return;
            }
            // Perspective-free on purpose: pass null instead of `me`.
            // formatNotificationMessage renders the subject as "You" when it
            // matches the player it is formatting for, but there is ONE game
            // log shared by every seat in the pod, so "You win the flip." is
            // right for at most one reader and wrong for the rest. With null
            // the subject is always the player's name -- "Sage wins the flip."
            // -- which reads correctly from every seat, and Lang.joinVerb
            // conjugates third-person to match. It also makes the dedupe below
            // sound: every bridge controller now produces the identical string
            // for one event, so collapsing them keeps no arbitrary viewpoint.
            String message = MessageUtil.formatNotificationMessage(
                    saSource, null, relatedTarget, value);
            if (message == null || message.trim().isEmpty()) {
                return;
            }
            String key = me.getGame().getTimestamp() + "|" + message;
            synchronized (PlayerControllerBridge.class) {
                if (key.equals(lastNotified)) {
                    return;
                }
                lastNotified = key;
            }
            me.getGame().fireEvent(
                    new GameEventAddLog(GameLogEntryType.INFORMATION, message));
        } catch (Exception e) {
            // A log line must never be the thing that breaks a game.
            System.out.println("[forge-bridge] notifyOfValue failed: " + e);
        }
    }

    /**
     * Put "Revealed cards in AI 1's hand: Duress, Swamp" in THIS seat's Feed.
     *
     * Same bug shape as notifyOfValue above and much more common. Forge tells a
     * player what was revealed by calling PlayerController.reveal;
     * PlayerControllerHuman opens a modal listing the cards, and
     * PlayerControllerAi files them in AiCardMemory and shows nothing. This
     * controller inherited the AI's version, so ~36 call sites across the
     * engine revealed cards to our players and they saw nothing at all: the
     * hand a Duress made an opponent reveal, the cards Discover and Explore and
     * every Dig effect turn up, a drawn-and-revealed card, the face-down
     * creature a failed turn-up exposes, cards revealed to pay a cost.
     * Impulse-draw effects were the worst of it -- the card is exiled and
     * playable, but nothing said which card, so the effect read as a no-op.
     *
     * Seat-scoped on purpose -- this is the one difference from notifyOfValue,
     * and it is a correctness issue rather than a preference. A reveal is a
     * DISCLOSURE TO SPECIFIC PLAYERS, not a public event: GameAction.revealTo
     * shows cards to a named subset, and GameAction.reveal skips the owner when
     * dontRevealToOwner is set. Both arrive here through this identical
     * signature, so the controller cannot tell a public reveal from a private
     * one -- which means the game log is the wrong channel, because
     * flush_log broadcasts it to the whole table and a private reveal would
     * leak. Forge already solved the addressing for us: it calls this method
     * once per player who is entitled to see, so writing to the caller's own
     * Feed discloses exactly what Forge intended and nothing more.
     *
     * That also makes "you"/"your" correct here, where it was wrong for the
     * shared log: this line has exactly one reader.
     *
     * Do NOT turn this into a prompt. ai_decision_allowlist.txt carried `reveal`
     * for exactly that reason -- "NOT a decision: returns void. It is a display
     * call made mid-resolution; blocking on it would stall the game thread"
     * (audited 2026-09-03) -- and that reasoning is still right about prompting
     * and was the thing that kept the method un-overridden. What it missed is
     * that "do not ask the player anything" is not the same as "tell the player
     * nothing". This override asks nothing: it writes one Feed line through the
     * same fire-and-continue channel explainUnplayable uses, which the bridge
     * answers with an empty reply the moment it arrives.
     */
    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner,
                       String message, boolean addSuffix) {
        super.reveal(cards, zone, owner, message, addSuffix);
        List<String> names = new ArrayList<>();
        if (cards != null) {
            for (Card c : cards) {
                names.add(c == null ? null : c.getName());
            }
        }
        revealToFeed(names, zone, owner == null ? null : owner.getView(),
                     message, addSuffix);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner,
                       String message, boolean addSuffix) {
        super.reveal(cards, zone, owner, message, addSuffix);
        List<String> names = new ArrayList<>();
        if (cards != null) {
            for (CardView cv : cards) {
                names.add(cv == null ? null : cv.getName());
            }
        }
        revealToFeed(names, zone, owner, message, addSuffix);
    }

    /**
     * Record in THIS seat's Feed a choice the player just made themselves.
     *
     * Reported from a live Commander game: Secluded Courtyard asked for a
     * creature type and the log never said which one was picked.
     *
     * This is a different hole from the one notifyOfValue covers, and the
     * INFORMATION fix could not reach it. Four effects deliberately exclude the
     * chooser from their own notification -- ChooseTypeEffect:136 and
     * ChooseColorEffect:96 pass `noNotify = p`, DiscardEffect:170 and
     * RollDiceEffect:499 pass the player directly -- because Forge's desktop
     * client just showed that player a dialog and telling them again would be
     * noise. So notifyOfValue is never called on the chooser's controller at
     * all, and no filter change can make it appear.
     *
     * Worse in a solo pod: the other seats ARE told, but they are AI, and
     * PlayerControllerAi.notifyOfValue is empty. With one human at the table
     * nobody writes the line, so the choice vanishes completely.
     *
     * Our prompt is a modal that closes, so the bridge records what it asked
     * and what came back. Seat-scoped rather than the shared game log: the
     * other seats already get the engine's own line through notifyOfValue, and
     * a second copy from here would double it for them. That also makes "you"
     * correct, and keeps the wording independent of
     * MessageUtil.formatNotificationMessage, whose phrasing is keyed on
     * sa.getApi() -- fine for ChooseType, but chooseSomeType is also called by
     * ChangeText, CounterEffect, RepeatEach, Untap and PlaySpellAbility, where
     * that formatting reads oddly.
     */
    private void echoOwnChoice(SpellAbility sa, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        try {
            String host = null;
            if (sa != null && sa.getHostCard() != null) {
                host = sa.getHostCard().getName();
            }
            // Naming the card is the useful half -- "you chose Ally" on its own
            // is unreadable three triggers later.
            String line = (host == null || host.trim().isEmpty())
                    ? "You chose " + value.trim() + "."
                    : host.trim() + ": you chose " + value.trim() + ".";
            ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(line) + "\"]}");
        } catch (Exception e) {
            // A Feed line must never be the thing that breaks a game.
            System.out.println("[forge-bridge] echoOwnChoice failed: " + e);
        }
    }

    /** How many revealed names to print before summarising the rest. */
    private static final int REVEAL_NAME_CAP = 12;

    /**
     * The shared tail of both reveal overloads.
     *
     * The title is built exactly the way PlayerControllerHuman builds its modal
     * title -- same two localized strings, same addSuffix rule -- so the two
     * clients say the same thing about the same event. The card names are then
     * appended, because a Feed line is all we get: there is no modal to hold a
     * card list next to a title.
     */
    private void revealToFeed(List<String> names, ZoneType zone, PlayerView owner,
                              String message, boolean addSuffix) {
        try {
            Player me = getPlayer();
            if (me == null) {
                return;
            }
            String zoneName = zone == null ? "" : zone.getTranslatedName().toLowerCase();
            String title;
            if (message == null || message.trim().isEmpty()) {
                title = Localizer.getInstance().getMessage(
                        "lblLookCardInPlayerZone", "{player's}", zoneName);
            } else if (addSuffix) {
                title = message.trim() + " " + Localizer.getInstance().getMessage(
                        "lblPlayerZone", "{player's}", zoneName);
            } else {
                title = message.trim();
            }
            // formatMessage only substitutes {player}/{player's} when it is
            // handed a PlayerView; with a null owner the placeholders would
            // survive into the Feed as literal braces, so strip them instead.
            if (owner != null) {
                title = MessageUtil.formatMessage(title, me.getView(), owner);
            } else {
                title = title.replace("{player's}", "the").replace("{player}", "a player");
            }
            title = title.trim();

            // Face-down and tokens can name themselves as blank; a nameless
            // entry still has to be counted or "reveals 3 cards" becomes
            // "reveals" and the player cannot tell how many they are missing.
            List<String> shown = new ArrayList<>();
            for (String n : names) {
                shown.add(n == null || n.trim().isEmpty() ? "a face-down card" : n.trim());
            }

            String line;
            if (shown.isEmpty()) {
                line = title + " - nothing to reveal.";
            } else {
                // DigUntil and mill-style reveals can turn up a big chunk of a
                // library at once. Print a readable prefix rather than a wall.
                StringBuilder list = new StringBuilder();
                int n = Math.min(shown.size(), REVEAL_NAME_CAP);
                for (int i = 0; i < n; i++) {
                    if (i > 0) list.append(", ");
                    list.append(shown.get(i));
                }
                if (shown.size() > n) {
                    list.append(" and ").append(shown.size() - n).append(" more");
                }
                line = title + ": " + list + ".";
            }
            ask("{\"kind\":\"feed\",\"lines\":[\"" + StateExporter.esc(line) + "\"]}");
        } catch (Exception e) {
            // A Feed line must never be the thing that breaks a game.
            System.out.println("[forge-bridge] reveal failed: " + e);
        }
    }

    /**
     * Evasion was not enforced. This checked each block with
     * CombatUtil.canBlock(blocker, combat) -- the overload that takes no
     * attacker and only answers "can this creature block ANYTHING right now":
     * untapped, no "can't block", within its block-count limits. Every
     * attacker-relative restriction lives behind the three-argument
     * canBlock(attacker, blocker, combat), which ends at
     * StaticAbilityCantAttackBlock.cantBlockBy -- and flying is exactly that,
     * since Keyword.FLYING is defined as "can't be blocked except by creatures
     * with flying or reach". So a ground creature could block a Dragon and the
     * block stood. Protection, shadow, intimidate and landwalk were open the
     * same way.
     *
     * Menace is a property of the whole SET of blocks rather than of any one
     * pair, so it needs CombatUtil.validateBlocks on top -- the same call
     * Forge's own human client makes before it will let you leave the blocking
     * step. PhaseHandler does NOT do this for us: declareBlockersTurnBasedAction
     * only strips "can't block alone" cases and unpaid block costs.
     *
     * A rejected block is reported rather than silently dropped. A block that
     * just vanishes reads as a UI bug -- which is exactly how the native
     * engine's version of this ("X cannot block Y." to stdout) reads today.
     */
    @Override
    public void declareBlockers(Player defender, Combat combat) {
        String state = StateExporter.toJson(defender.getGame().getView(), defender);
        // Which attacker each of this player's creatures may legally block, so
        // the client can stop OFFERING a flier to a ground creature. Refusing
        // the block below is the rules fix; this is the half that keeps the
        // player from being told no in the first place.
        StringBuilder legal = new StringBuilder("[");
        for (Card blk : defender.getCreaturesInPlay()) {
            StringBuilder ids = new StringBuilder();
            for (Card atk : combat.getAttackers()) {
                if (CombatUtil.canBlock(atk, blk, combat)) {
                    if (ids.length() > 0) ids.append(',');
                    ids.append(atk.getId());
                }
            }
            if (legal.length() > 1) legal.append(',');
            legal.append("{\"blocker\":").append(blk.getId())
                 .append(",\"attackers\":[").append(ids).append("]}");
        }
        legal.append(']');
        String reply = ask("{\"kind\":\"declare_blockers\",\"legal\":" + legal
                + ",\"state\":" + state + "}");
        if (endIfAbandoned()) {
            return;   // client gone: end the game now rather than at the next priority
        }
        String compact = reply.replaceAll("\\s", "");
        if (compact.contains("\"blocks\":\"none\"") || !compact.contains("blocker")) {
            return; // no blocks
        }
        List<String> rejected = new ArrayList<>();
        List<Card> assigned = new ArrayList<>();
        for (Card blk : defender.getCreaturesInPlay()) {
            int atkId = parseInt(compact, "\"blocker\":" + blk.getId() + ",\"attacker\":");
            if (atkId < 0) continue;
            Card atk = findAttacker(combat, atkId);
            if (atk == null) continue;
            if (!CombatUtil.canBlock(atk, blk, combat)) {
                rejected.add(blk.getName() + " can't block " + atk.getName() + ".");
                continue;
            }
            combat.addBlocker(atk, blk);
            assigned.add(blk);
        }
        // Menace and friends: legal one pair at a time, illegal as a set. Drop
        // the most recent block until the set is legal rather than re-asking --
        // the player is mid-combat, and a prompt that repeats with no way to
        // satisfy it is worse than being told what was dropped.
        String why = safeValidateBlocks(combat, defender);
        while (why != null && !assigned.isEmpty()) {
            Card drop = assigned.remove(assigned.size() - 1);
            combat.undoBlockingAssignment(drop);
            rejected.add(drop.getName() + " could not block: " + why);
            why = safeValidateBlocks(combat, defender);
        }
        if (!rejected.isEmpty()) {
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < rejected.size(); i++) {
                if (i > 0) lines.append(',');
                lines.append('"').append(StateExporter.esc(rejected.get(i))).append('"');
            }
            ask("{\"kind\":\"feed\",\"lines\":[" + lines + "]}");
        }
    }

    /** The reason a set of blocks is illegal, or null when it is fine. */
    private static String safeValidateBlocks(Combat combat, Player defender) {
        try {
            String why = CombatUtil.validateBlocks(combat, defender);
            return (why == null || why.trim().isEmpty()) ? null : why.trim();
        } catch (Exception e) {   // never let combat die over a validation call
            System.out.println("[forge-bridge] validateBlocks failed: " + e);
            return null;
        }
    }

    /**
     * Says which kind of thing you are aiming, when it is not a spell you just
     * chose to cast.
     *
     * An optional trigger is targeted BEFORE you are asked whether to use it --
     * the rules order, see confirmTrigger -- so a bare "Choose target for
     * Hissing Iguanar" asks you to aim something you never agreed to do, and
     * the confirm that follows reads as an unrelated second prompt. Naming the
     * trigger and warning that the confirm is coming turns two mystery prompts
     * into one sequence.
     */
    private static String targetingContext(SpellAbility sa) {
        try {
            if (sa == null || !sa.isTrigger()) {
                return "";
            }
            return sa.isOptionalTrigger()
                    ? "'s trigger (you choose whether to use it when it resolves)"
                    : "'s trigger";
        } catch (Exception e) {
            return "";
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
