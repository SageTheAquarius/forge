package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Late-game pace instrumentation and the four cheap levers that sit on top of
 * it. (EconomyDraft bridge patch. Mirrored in forge_bridge/forge_java_src.)
 *
 * The 2026-09-10 audit of eleven Commander pods put the remaining late-game
 * cost in two places that no memo reaches: the NUMBER of full block
 * simulations the AI runs per decision, and three AI seats re-evaluating at
 * every one of the human's priority windows when nothing has changed. Each
 * lever below has its own kill switch so one build can run both arms of a
 * measurement, and production can back any of them out without a rebuild:
 *
 *   -Dbridge.idlepass=false      an AI that already passed an identical window
 *                                evaluates again instead of passing at once
 *   -Dbridge.predictcache=false  combat predictions are recomputed within a
 *                                decision instead of remembered
 *   -Dbridge.predictbound=false  every prediction runs the block simulation,
 *                                even when no block could change the verdict
 *   -Dbridge.cheappump=false     pump / counter AI solves a whole combat per
 *                                candidate target instead of reusing answers
 *   -Dbridge.turnbudget=<ms>     per-seat thinking budget per turn; over it the
 *                                seat's predictions stop simulating blocks
 *                                (0 disables; default 12000)
 *
 * The counters are cumulative for the JVM's current game and are exported in
 * every state push ("perf"), so the relay can print the per-turn deltas next
 * to its pace line and a slow turn names its own cause.
 */
public final class AiPerf {

    public static final boolean IDLE_PASS = !"false".equals(System.getProperty("bridge.idlepass"));
    public static final boolean PREDICT_CACHE = !"false".equals(System.getProperty("bridge.predictcache"));
    public static final boolean PREDICT_BOUND = !"false".equals(System.getProperty("bridge.predictbound"));
    public static final boolean CHEAP_PUMP = !"false".equals(System.getProperty("bridge.cheappump"));
    /** Predictions use the quick block assignment (no reinforcement, no second pass): -Dbridge.predictquick=false. */
    public static final boolean PREDICT_QUICK = !"false".equals(System.getProperty("bridge.predictquick"));
    public static final long TURN_BUDGET_MS = Long.getLong("bridge.turnbudget", 12000L);
    /**
     * The combat predictors (ComputerUtilCombat.predict*BonusOf*, canDestroy*)
     * gather the board's combat triggers and "attacking"/"blocking" statics
     * once per decision instead of once per attacker x blocker question, and
     * remember each creature's activated pump bonus (a mana-payment
     * simulation per ability): -Dbridge.combatmemo=false. On the 21:52 pod of
     * 2026-09-10 one attack declaration ran 220s with every 2s sample inside
     * those scans.
     */
    public static final boolean COMBAT_MEMO = !"false".equals(System.getProperty("bridge.combatmemo"));

    /** Priority evaluations (chooseSpellAbilityToPlayFromList futures). */
    public static final LongAdder evals = new LongAdder();
    /** Evaluations that took 1s or more. */
    public static final LongAdder evalsSlow = new LongAdder();
    /** Wall milliseconds spent in evaluations. */
    public static final LongAdder evalMs = new LongAdder();
    /** Evaluations cut by the AI_TIMEOUT budget. */
    public static final LongAdder timeouts = new LongAdder();
    /** Attack declarations on the game thread, and their milliseconds. */
    public static final LongAdder declAttackN = new LongAdder();
    public static final LongAdder declAttackMs = new LongAdder();
    /** Block declarations on the game thread, and their milliseconds. */
    public static final LongAdder declBlockN = new LongAdder();
    public static final LongAdder declBlockMs = new LongAdder();
    /** Full block assignments (AiBlockController.assignBlockers). */
    public static final LongAdder blockSims = new LongAdder();
    /** Combat predictions asked, answered from the decision cache, and answered by the bound. */
    public static final LongAdder predicts = new LongAdder();
    public static final LongAdder predictHits = new LongAdder();
    public static final LongAdder predictBounded = new LongAdder();
    /** "Would this (pumped) creature attack?" questions, and cache hits. */
    public static final LongAdder pumpChecks = new LongAdder();
    public static final LongAdder pumpHits = new LongAdder();
    /** Windows an AI seat passed without evaluating because nothing had changed. */
    public static final LongAdder idlePasses = new LongAdder();
    /** Decisions degraded by the turn budget. */
    public static final LongAdder governed = new LongAdder();
    /** Whole priority decisions (chooseSpellAbilityToPlay) on the game thread, and their milliseconds:
     *  the candidate build, the land logic and the eval future together. */
    public static final LongAdder chooseN = new LongAdder();
    public static final LongAdder chooseMs = new LongAdder();
    /** Candidate-ability builds (ComputerUtilAbility.buildSpellAbilities) and their milliseconds. */
    public static final LongAdder buildN = new LongAdder();
    public static final LongAdder buildMs = new LongAdder();
    /** State exports for the bridge and their milliseconds. */
    public static final LongAdder exportN = new LongAdder();
    public static final LongAdder exportMs = new LongAdder();
    /** The human seat's per-window "should I stop?" scan (PlayerControllerBridge.shouldPromptAtPriority). */
    public static final LongAdder humanN = new LongAdder();
    public static final LongAdder humanMs = new LongAdder();
    /** Combat-predictor board scans answered from the decision scope (COMBAT_MEMO). */
    public static final LongAdder combatHits = new LongAdder();
    /** Trigger / ordering / prevention decisions on the game thread (AiController.withDecision), and their milliseconds. */
    public static final LongAdder decideN = new LongAdder();
    public static final LongAdder decideMs = new LongAdder();

    private AiPerf() { }

    // ---- per-decision cache scope ---------------------------------------

    private static final class Scope {
        int depth;
        final Map<String, Object> cache = new HashMap<>();
        /** The seat deciding, and when its outermost decision began (in-flight budget). */
        Player player;
        long t0;
    }

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    /**
     * Open a decision scope on this thread; answers remembered inside it are
     * forgotten by the outermost {@link #scopeEnd()}. Re-entrant. Valid for
     * the same reason CardTraitMemo is: the game is frozen while the AI
     * evaluates on its eval thread, and while it declares attackers or
     * blockers on the game thread.
     */
    public static void scopeBegin() {
        scopeBegin(null);
    }

    /**
     * As {@link #scopeBegin()}, naming the seat that is deciding so the turn
     * budget can see the decision WHILE it runs. {@link #spent} only lands
     * when a decision ends, so a single runaway declaration never tripped the
     * budget: on 2026-09-10 (v132) one attack declaration ran 220s on a 12s
     * budget, "has used its thinking time" arriving after it had finished.
     */
    public static void scopeBegin(Player ai) {
        Scope s = SCOPE.get();
        if (s == null) {
            s = new Scope();
            s.player = ai;
            s.t0 = System.currentTimeMillis();
            SCOPE.set(s);
        }
        s.depth++;
    }

    public static void scopeEnd() {
        Scope s = SCOPE.get();
        if (s == null) {
            return;
        }
        if (--s.depth <= 0) {
            SCOPE.remove();
        }
    }

    /** The active decision's cache, or null outside any decision. */
    public static Map<String, Object> scope() {
        Scope s = SCOPE.get();
        return s == null ? null : s.cache;
    }

    // ---- per-turn thinking budget -----------------------------------------

    /** Player -> {turn, ms spent deciding in that turn}. */
    private static final Map<Player, long[]> SPENT = Collections.synchronizedMap(new WeakHashMap<>());

    /** Record wall time one AI seat spent on a decision. */
    public static void spent(Player ai, long ms) {
        if (ai == null || TURN_BUDGET_MS <= 0) {
            return;
        }
        int turn = currentTurn(ai);
        synchronized (SPENT) {
            long[] e = SPENT.get(ai);
            if (e == null || e[0] != turn) {
                e = new long[] {turn, 0L};
                SPENT.put(ai, e);
            }
            e[1] += ms;
        }
    }

    /** True once this seat has spent more than its budget deciding this turn. */
    public static boolean fast(Player ai) {
        if (ai == null || TURN_BUDGET_MS <= 0) {
            return false;
        }
        long[] e;
        synchronized (SPENT) {
            e = SPENT.get(ai);
        }
        long ms = e != null && e[0] == currentTurn(ai) ? e[1] : 0L;
        // Plus the decision this thread is in the middle of, if it is this seat's.
        Scope s = SCOPE.get();
        if (s != null && s.player == ai) {
            ms += System.currentTimeMillis() - s.t0;
        }
        return ms > TURN_BUDGET_MS;
    }

    private static int currentTurn(Player ai) {
        Game g = ai.getGame();
        return g == null || g.getPhaseHandler() == null ? -1 : g.getPhaseHandler().getTurn();
    }

    /** Names of the seats currently over budget, for the state export. */
    public static List<String> fastSeats() {
        List<String> out = new ArrayList<>();
        synchronized (SPENT) {
            for (Map.Entry<Player, long[]> en : SPENT.entrySet()) {
                Player p = en.getKey();
                if (p != null && fast(p)) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }

    // ---- idle fingerprint -------------------------------------------------

    /**
     * Everything an AI seat's "should I play something?" answer can depend
     * on, as one string, without computing any of it. Two consecutive
     * windows with the same fingerprint would get the same decision, so a
     * "nothing" answer can be repeated without the evaluation. Opponents'
     * tapped state is deliberately left out: Forge's AI does not condition
     * its plays on it, and every land the human taps would otherwise be a
     * miss.
     */
    public static String fingerprint(Game game, Player ai) {
        PhaseHandler ph = game.getPhaseHandler();
        StringBuilder b = new StringBuilder(768);
        b.append(game.getTimestamp()).append('|').append(ph.getTurn()).append('|')
         .append(ph.getPhase()).append('|')
         .append(ph.getPlayerTurn() == null ? -1 : ph.getPlayerTurn().getId()).append('|');
        b.append(game.getStack().size()).append(':');
        for (SpellAbilityStackInstance si : game.getStack()) {
            Card src = si.getSourceCard();
            b.append(src == null ? -1 : src.getId()).append('.');
            if (si.getSpellAbility() != null) {
                b.append(si.getSpellAbility().getApi());
            }
            b.append(',');
        }
        for (Player p : game.getPlayers()) {
            b.append('|').append(p.getId()).append('=').append(p.getLife()).append(',')
             .append(p.getPoisonCounters()).append(',')
             .append(p.getCardsIn(ZoneType.Hand).size()).append(',')
             .append(p.getCardsIn(ZoneType.Graveyard).size()).append(',')
             .append(p.getCardsIn(ZoneType.Exile).size()).append(',')
             .append(p.getManaPool().totalMana()).append(';');
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                b.append(c.getId());
                if (p == ai && c.isTapped()) {
                    b.append('t');
                }
                if (c.isCreature()) {
                    b.append(c.getNetPower()).append('/').append(c.getNetToughness());
                    if (c.isSick()) {
                        b.append('s');
                    }
                }
                int counters = c.getCounters().size();
                if (counters != 0) {
                    b.append('c').append(counters);
                }
                if (c.getDamage() != 0) {
                    b.append('d').append(c.getDamage());
                }
                b.append(',');
            }
            if (p == ai) {
                b.append('h');
                for (Card c : p.getCardsIn(ZoneType.Hand)) {
                    b.append(c.getId()).append(',');
                }
            }
        }
        return b.toString();
    }

    // ---- export -----------------------------------------------------------

    public static void reset() {
        for (LongAdder a : new LongAdder[] {evals, evalsSlow, evalMs, timeouts, declAttackN, declAttackMs,
                declBlockN, declBlockMs, blockSims, predicts, predictHits, predictBounded, pumpChecks,
                pumpHits, idlePasses, governed, chooseN, chooseMs, buildN, buildMs, exportN, exportMs,
                humanN, humanMs, combatHits, decideN, decideMs}) {
            a.reset();
        }
        synchronized (SPENT) {
            SPENT.clear();
        }
    }

    /** The counters as one JSON object. */
    public static String json() {
        StringBuilder b = new StringBuilder(320).append('{');
        num(b, "evals", evals); num(b, "evals_slow", evalsSlow); num(b, "eval_ms", evalMs);
        num(b, "timeouts", timeouts);
        num(b, "attack_n", declAttackN); num(b, "attack_ms", declAttackMs);
        num(b, "block_n", declBlockN); num(b, "block_ms", declBlockMs);
        num(b, "sims", blockSims);
        num(b, "predicts", predicts); num(b, "predict_hits", predictHits); num(b, "bounded", predictBounded);
        num(b, "pump_checks", pumpChecks); num(b, "pump_hits", pumpHits);
        num(b, "idle", idlePasses); num(b, "governed", governed);
        num(b, "choose_n", chooseN); num(b, "choose_ms", chooseMs);
        num(b, "build_n", buildN); num(b, "build_ms", buildMs);
        num(b, "export_n", exportN); num(b, "export_ms", exportMs);
        num(b, "human_n", humanN); num(b, "human_ms", humanMs);
        num(b, "combat_hits", combatHits);
        num(b, "decide_n", decideN); num(b, "decide_ms", decideMs);
        b.append("\"fast\":[");
        boolean first = true;
        for (String name : fastSeats()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append('"').append(name.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append("]}").toString();
    }

    private static void num(StringBuilder b, String key, LongAdder v) {
        b.append('"').append(key).append("\":").append(v.sum()).append(',');
    }
}
