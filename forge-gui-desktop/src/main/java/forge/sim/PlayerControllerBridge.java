package forge.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

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
            if (chosen != null) {
                for (SpellAbility sa : chosen.getAllPossibleAbilities(me, true)) {
                    boolean isMana = sa.isManaAbility();
                    if (wantMana ? isMana : (!isMana && sa.isActivatedAbility())) {
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
     * When to stop for the human, matching the original EconomyDraft pacing:
     *   1. Your own main phase (empty stack) -> develop.
     *   2. During combat -> only if you actually have an instant-speed play
     *      (a combat trick), so we don't pause for a pointless pass.
     *   3. Any time the stack is non-empty -> only if you could respond.
     * Everything else (untap/upkeep/draw/end, the opponent's non-combat windows)
     * auto-passes so the game advances on its own.
     */
    private static boolean shouldPromptAtPriority(Player me) {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        PhaseType phase = ph.getPhase();
        boolean stackEmpty = game.getStack().isEmpty();
        if (ph.getPlayerTurn() == me && phase != null && phase.isMain() && stackEmpty) {
            return true; // your main phase
        }
        // Combat: only stop if combat is actually happening (attackers declared)
        // AND you have an instant-speed play — otherwise there's nothing to do,
        // so don't pause (this was stopping every combat step with no creatures).
        boolean inCombat = phase != null && phase.name().startsWith("COMBAT");
        if (inCombat) {
            Combat combat = game.getCombat();
            boolean combatOn = combat != null && !combat.getAttackers().isEmpty();
            return combatOn && hasLegalPlay(me);
        }
        // Something on the stack you could respond to.
        if (!stackEmpty) {
            return hasLegalPlay(me);
        }
        return false;
    }

    /** True if the player has any non-mana ability playable right now. */
    private static boolean hasLegalPlay(Player me) {
        for (ZoneType zt : PRIORITY_ZONES) {
            for (Card c : me.getCardsIn(zt)) {
                for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                    if (!sa.isManaAbility()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final ZoneType[] PRIORITY_ZONES = {
        ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command,
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
                + ",\"cards\":" + cards + "}");
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
                + "\"optional\":" + optional + ",\"prompt_type\":\"" + promptType + "\"}}";
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

    private static Card findCard(Player me, int id) {
        if (id < 0) return null;
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.getId() == id) return c;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getId() == id) return c;
        for (Card c : me.getCardsIn(ZoneType.Graveyard)) if (c.getId() == id) return c;
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
     */
    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (combat.getDefendingPlayers().isEmpty()) { super.declareAttackers(attacker, combat); return; }
        GameEntity defender = combat.getDefendingPlayers().get(0);
        List<Card> eligible = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, defender)) eligible.add(c);
        }
        String state = StateExporter.toJson(attacker.getGame().getView(), attacker);
        String reply = Channel.request("{\"kind\":\"declare_attackers\",\"eligible\":" + idList(eligible)
                + ",\"state\":" + state + "}");
        String compact = reply.replaceAll("\\s", "");
        boolean all = compact.contains("\"attackers\":\"all\"");
        for (Card c : eligible) {
            if (all || inArray(compact, "attackers", c.getId())) {
                combat.addAttacker(c, defender);
            }
        }
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
