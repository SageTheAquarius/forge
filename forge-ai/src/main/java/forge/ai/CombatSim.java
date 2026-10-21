package forge.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import forge.ai.simulation.GameCopier;
import forge.ai.simulation.GameSimulator;
import forge.ai.simulation.GameStateEvaluator;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * EconomyDraft 2026-10-19: combat by simulation.
 *
 * The rule-based attack and block logic (AiAttackController, AiBlockController)
 * decides from magic numbers -- an aggression level from life ratios, "safe
 * block" tests -- and the recorded games show that is where the AI loses the
 * most: on the replayed prod positions its regret per decision was 0.112 for
 * blocks and 0.053 for attacks against 0.021 for plays. This class keeps the
 * rule-based declaration as one candidate, adds a few alternatives (nothing,
 * everything, one creature more, one fewer), plays each through the whole
 * combat on a game copy (the cheap copier, ~3 ms) and declares the one whose
 * board scores best afterwards (GameStateEvaluator, the same judge the hybrid
 * simulation uses for spells).
 *
 * An attack is judged against two defenders and the worse result counts: the
 * defender blocking as the AI would, and the defender not blocking at all. A
 * block needs no opponent model (the attackers are declared). Tricks are not
 * modelled here; the trick model in AiAttackController still shapes the
 * rule-based candidate.
 *
 * Only a hybrid-simulation seat does this, never inside a simulation copy
 * (Game.SIMULATION_COPY) and never once the seat is over its thinking budget
 * (AiPerf.fast). Knobs: AiProps SIM_ATTACK_CANDIDATES, SIM_BLOCK_CANDIDATES
 * (0 = off) and SIM_COMBAT_BUDGET_MS. -Dbridge.combatsim.log=true prints every
 * candidate's score.
 */
public final class CombatSim {
    private static final boolean LOG = Boolean.getBoolean("bridge.combatsim.log");
    /** Kill switch: -Dbridge.combatsim=off leaves every declaration rule-based. */
    private static final boolean OFF = "off".equals(System.getProperty("bridge.combatsim"));
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    public static final LongAdder attackDecisions = new LongAdder();
    public static final LongAdder attackChanged = new LongAdder();
    public static final LongAdder blockDecisions = new LongAdder();
    public static final LongAdder blockChanged = new LongAdder();
    public static final LongAdder sims = new LongAdder();
    public static final LongAdder simMs = new LongAdder();

    private CombatSim() { }

    private static AiController aic(final Player p) {
        if (OFF || p == null || p.getGame() == null || p.getGame().SIMULATION_COPY || DEPTH.get() > 0) {
            return null;
        }
        if (!(p.getController() instanceof PlayerControllerAi)) {
            return null;
        }
        AiController aic = ((PlayerControllerAi) p.getController()).getAi();
        if (!aic.usesHybridSimulation() || AiPerf.fast(p)) {
            return null;
        }
        return aic;
    }

    // ── attacks ─────────────────────────────────────────────────────────────

    /** Refine the attack declaration in {@code combat} (the rule-based one) in place. */
    public static void refineAttack(final Player ai, final Combat combat) {
        final AiController aic = aic(ai);
        if (aic == null) {
            return;
        }
        final int k = aic.getIntProperty(AiProps.SIM_ATTACK_CANDIDATES);
        if (k <= 0) {
            return;
        }
        final Game game = ai.getGame();
        final Map<Card, GameEntity> base = new LinkedHashMap<>();
        for (Card a : combat.getAttackers()) {
            base.put(a, combat.getDefenderByAttacker(a));
        }
        GameEntity defender = null;
        for (GameEntity d : base.values()) {
            if (d != null) {
                defender = d;
                break;
            }
        }
        if (defender == null) {
            defender = AiAttackController.choosePreferredDefenderPlayer(ai);
        }
        if (defender == null) {
            return;
        }
        final List<Card> legal = new ArrayList<>();
        for (Card c : ai.getCreaturesInPlay()) {
            if (base.containsKey(c) || CombatUtil.canAttack(c, defender)) {
                legal.add(c);
            }
        }
        if (legal.isEmpty()) {
            return;
        }
        CardLists.sortByPowerDesc(legal);

        final List<Map<Card, GameEntity>> adds = new ArrayList<>();
        final List<Map<Card, GameEntity>> removes = new ArrayList<>();
        for (Card c : legal) {
            if (!base.containsKey(c)) {
                Map<Card, GameEntity> m = new LinkedHashMap<>(base);
                m.put(c, defender);
                adds.add(m);
            }
        }
        for (Card c : base.keySet()) {
            Map<Card, GameEntity> m = new LinkedHashMap<>(base);
            m.remove(c);
            removes.add(m);
        }
        final Map<Card, GameEntity> all = new LinkedHashMap<>(base);
        for (Card c : legal) {
            all.putIfAbsent(c, defender);
        }
        final List<Map<Card, GameEntity>> cands = new ArrayList<>();
        cands.add(base);
        cands.add(new LinkedHashMap<>());
        cands.add(all);
        for (int i = 0; i < Math.max(adds.size(), removes.size()); i++) {
            if (i < adds.size()) cands.add(adds.get(i));
            if (i < removes.size()) cands.add(removes.get(i));
        }
        final List<Map<Card, GameEntity>> uniq = new ArrayList<>();
        final Set<Set<Card>> seen = new HashSet<>();
        for (Map<Card, GameEntity> m : cands) {
            if (seen.add(new HashSet<>(m.keySet()))) {
                uniq.add(m);
            }
            if (uniq.size() > k) {
                break;
            }
        }
        if (uniq.size() < 2) {
            return;
        }

        attackDecisions.increment();
        final long deadline = System.currentTimeMillis() + aic.getIntProperty(AiProps.SIM_COMBAT_BUDGET_MS);
        Map<Card, GameEntity> bestSet = base;
        Integer bestScore = null;
        DEPTH.set(1);
        try {
            for (Map<Card, GameEntity> cand : uniq) {
                if (cand != base && (bestScore == null || System.currentTimeMillis() > deadline)) {
                    break;   // the rule-based line could not be scored, or out of time
                }
                final Integer s = attackScore(game, ai, cand);
                if (LOG) {
                    System.out.println("[COMBATSIM] attack " + names(cand.keySet()) + " -> " + s
                            + (cand == base ? " (rule-based)" : ""));
                }
                if (s == null) {
                    continue;
                }
                if (bestScore == null || s > bestScore) {
                    bestScore = s;
                    bestSet = cand;
                }
            }
        } finally {
            DEPTH.set(0);
        }
        if (bestSet == base) {
            return;
        }
        attackChanged.increment();
        combat.clearAttackers();
        for (Map.Entry<Card, GameEntity> e : bestSet.entrySet()) {
            combat.addAttacker(e.getKey(), e.getValue());
        }
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            for (Map.Entry<Card, GameEntity> e : base.entrySet()) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
            attackChanged.decrement();
        } else if (LOG) {
            System.out.println("[COMBATSIM] attack: " + names(base.keySet()) + " -> " + names(bestSet.keySet()));
        }
    }

    /** The worse of: the defender blocks as the AI would / does not block. */
    private static Integer attackScore(final Game game, final Player ai, final Map<Card, GameEntity> set) {
        final Integer withBlocks = simulateAttack(game, ai, set, true);
        if (withBlocks == null) {
            return null;
        }
        final Integer noBlocks = simulateAttack(game, ai, set, false);
        return noBlocks == null ? withBlocks : Math.min(withBlocks, noBlocks);
    }

    private static Integer simulateAttack(final Game game, final Player ai, final Map<Card, GameEntity> set,
            final boolean oppBlocks) {
        final long t0 = System.nanoTime();
        sims.increment();
        try {
            final GameCopier copier = new GameCopier(game);
            final Game copy = copier.makeCopy(null, ai);
            final Player aiCopy = copier.find(ai);
            if (aiCopy == null) {
                return null;
            }
            final PhaseHandler ph = copy.getPhaseHandler();
            final Map<Card, GameEntity> forced = new LinkedHashMap<>();
            for (Map.Entry<Card, GameEntity> e : set.entrySet()) {
                final Card c = copier.find(e.getKey());
                final GameEntity d = copier.find(e.getValue());
                if (c == null || d == null) {
                    return null;
                }
                forced.put(c, d);
            }
            // back to the first main phase (the copied combat is dropped) and
            // through combat: the forced declaration, the defender's blocks
            // (the AI's, or none), damage, end of combat.
            ph.devModeSet(PhaseType.MAIN1, aiCopy, true, ph.getTurn());
            ph.devSetForcedAttacks(forced);
            if (!oppBlocks) {
                ph.devSetForcedBlocks(new HashMap<>());
            }
            final Player opp = aiCopy.getWeakestOpponent();
            ph.devAdvanceToPhase(PhaseType.COMBAT_END, () -> GameSimulator.resolveStack(copy, opp));
            if (ph.devForcedAttacksRejected()) {
                return null;
            }
            return score(copy, aiCopy);
        } catch (RuntimeException e) {
            if (LOG) {
                System.out.println("[COMBATSIM] attack simulation failed: " + e);
            }
            return null;
        } finally {
            simMs.add((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    // ── blocks ──────────────────────────────────────────────────────────────

    /** Refine the block assignment in {@code combat} (the rule-based one) in place. */
    public static void refineBlocks(final Player ai, final Combat combat) {
        final AiController aic = aic(ai);
        if (aic == null) {
            return;
        }
        final int k = aic.getIntProperty(AiProps.SIM_BLOCK_CANDIDATES);
        if (k <= 0) {
            return;
        }
        final Game game = ai.getGame();
        final List<Card> attackers = new ArrayList<>();
        for (Card a : combat.getAttackers()) {
            if (combat.getDefenderPlayerByAttacker(a) == ai) {
                attackers.add(a);
            }
        }
        if (attackers.isEmpty()) {
            return;
        }
        CardLists.sortByPowerDesc(attackers);
        final Map<Card, Card> base = new LinkedHashMap<>();   // blocker -> attacker
        for (Card b : combat.getAllBlockers()) {
            if (b.getController() != ai) {
                continue;
            }
            for (Card a : combat.getAttackersBlockedBy(b)) {
                base.put(b, a);
                break;   // one attacker per blocker in the candidate space
            }
        }
        final List<Card> blockers = new ArrayList<>();
        for (Card b : ai.getCreaturesInPlay()) {
            if (base.containsKey(b)) {
                blockers.add(b);
                continue;
            }
            for (Card a : attackers) {
                if (CombatUtil.canBlock(a, b, combat)) {
                    blockers.add(b);
                    break;
                }
            }
        }
        if (blockers.isEmpty()) {
            return;
        }

        final List<Map<Card, Card>> adds = new ArrayList<>();
        final List<Map<Card, Card>> removes = new ArrayList<>();
        for (Card a : attackers) {
            for (Card b : blockers) {
                if (base.get(b) == a || !CombatUtil.canBlock(a, b, combat)) {
                    continue;
                }
                Map<Card, Card> m = new LinkedHashMap<>(base);
                m.put(b, a);
                adds.add(m);
            }
        }
        for (Card b : base.keySet()) {
            Map<Card, Card> m = new LinkedHashMap<>(base);
            m.remove(b);
            removes.add(m);
        }
        final List<Map<Card, Card>> cands = new ArrayList<>();
        cands.add(base);
        cands.add(new LinkedHashMap<>());
        for (int i = 0; i < Math.max(adds.size(), removes.size()); i++) {
            if (i < adds.size()) cands.add(adds.get(i));
            if (i < removes.size()) cands.add(removes.get(i));
        }
        final List<Map<Card, Card>> uniq = new ArrayList<>();
        final Set<Map<Card, Card>> seen = new HashSet<>();
        for (Map<Card, Card> m : cands) {
            if (seen.add(m)) {
                uniq.add(m);
            }
            if (uniq.size() > k) {
                break;
            }
        }
        if (uniq.size() < 2) {
            return;
        }

        blockDecisions.increment();
        final long deadline = System.currentTimeMillis() + aic.getIntProperty(AiProps.SIM_COMBAT_BUDGET_MS);
        Map<Card, Card> bestSet = base;
        Integer bestScore = null;
        DEPTH.set(1);
        try {
            for (Map<Card, Card> cand : uniq) {
                if (cand != base && (bestScore == null || System.currentTimeMillis() > deadline)) {
                    break;
                }
                final Integer s = simulateBlocks(game, ai, cand);
                if (LOG) {
                    System.out.println("[COMBATSIM] block " + pairs(cand) + " -> " + s
                            + (cand == base ? " (rule-based)" : ""));
                }
                if (s == null) {
                    continue;
                }
                if (bestScore == null || s > bestScore) {
                    bestScore = s;
                    bestSet = cand;
                }
            }
        } finally {
            DEPTH.set(0);
        }
        if (bestSet == base) {
            return;
        }
        blockChanged.increment();
        applyBlocks(ai, combat, bestSet);
        if (CombatUtil.validateBlocks(combat, ai) != null) {
            applyBlocks(ai, combat, base);
            blockChanged.decrement();
        } else if (LOG) {
            System.out.println("[COMBATSIM] block: " + pairs(base) + " -> " + pairs(bestSet));
        }
    }

    private static void applyBlocks(final Player ai, final Combat combat, final Map<Card, Card> set) {
        for (Card b : new ArrayList<>(combat.getAllBlockers())) {
            if (b.getController() == ai) {
                combat.undoBlockingAssignment(b);
            }
        }
        for (Map.Entry<Card, Card> e : set.entrySet()) {
            combat.addBlocker(e.getValue(), e.getKey());
        }
    }

    private static Integer simulateBlocks(final Game game, final Player ai, final Map<Card, Card> set) {
        final long t0 = System.nanoTime();
        sims.increment();
        try {
            final GameCopier copier = new GameCopier(game);
            final Game copy = copier.makeCopy(null, ai);
            final Player aiCopy = copier.find(ai);
            final Combat copyCombat = copy.getCombat();
            if (aiCopy == null || copyCombat == null) {
                return null;
            }
            final Map<Card, Card> forced = new HashMap<>();
            for (Map.Entry<Card, Card> e : set.entrySet()) {
                final Card b = copier.find(e.getKey());
                final Card a = copier.find(e.getValue());
                if (a == null || b == null) {
                    return null;
                }
                forced.put(b, a);
            }
            // the copy carries whatever the rule-based pass had assigned
            for (Card b : new ArrayList<>(copyCombat.getAllBlockers())) {
                if (b.getController() == aiCopy) {
                    copyCombat.undoBlockingAssignment(b);
                }
            }
            final PhaseHandler ph = copy.getPhaseHandler();
            final Player turnCopy = copier.find(game.getPhaseHandler().getPlayerTurn());
            ph.devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, turnCopy, false, ph.getTurn());
            ph.devSetForcedBlocks(forced);
            final Player opp = aiCopy.getWeakestOpponent();
            ph.devAdvanceToPhase(PhaseType.COMBAT_END, () -> GameSimulator.resolveStack(copy, opp));
            return score(copy, aiCopy);
        } catch (RuntimeException e) {
            if (LOG) {
                System.out.println("[COMBATSIM] block simulation failed: " + e);
            }
            return null;
        } finally {
            simMs.add((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    // ── shared ──────────────────────────────────────────────────────────────

    /** -Dbridge.combatsim.lifeweight=N overrides the profile's SIM_COMBAT_LIFE_WEIGHT (calibration runs). */
    private static final Integer LIFE_WEIGHT_OVERRIDE = Integer.getInteger("bridge.combatsim.lifeweight");

    private static int score(final Game copy, final Player aiCopy) {
        int v = new GameStateEvaluator().getScoreForGameState(copy, aiCopy).value;
        if (GameStateEvaluator.isWinning(v) || !aiCopy.isInGame()) {
            return v;
        }
        int w = LIFE_WEIGHT_OVERRIDE != null ? LIFE_WEIGHT_OVERRIDE
                : ((PlayerControllerAi) aiCopy.getController()).getAi().getIntProperty(AiProps.SIM_COMBAT_LIFE_WEIGHT);
        if (w > 0) {
            final Player opp = aiCopy.getWeakestOpponent();
            final double mine = Math.log(Math.max(1, aiCopy.getLife()));
            final double theirs = opp == null ? 0 : Math.log(Math.max(1, opp.getLife()));
            v += (int) Math.round(w * (mine - theirs));
        }
        return v;
    }

    private static String names(final Set<Card> cards) {
        List<String> ns = new ArrayList<>();
        for (Card c : cards) {
            ns.add(c.getName());
        }
        return ns.isEmpty() ? "none" : String.join("; ", ns);
    }

    private static String pairs(final Map<Card, Card> set) {
        List<String> ns = new ArrayList<>();
        for (Map.Entry<Card, Card> e : set.entrySet()) {
            ns.add(e.getKey().getName() + ">" + e.getValue().getName());
        }
        return ns.isEmpty() ? "none" : String.join("; ", ns);
    }
}
