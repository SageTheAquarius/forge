package forge.ai;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

import forge.ai.simulation.Determinize;
import forge.ai.simulation.GameSimulator;
import forge.ai.simulation.GameStateEvaluator;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.ai.simulation.SimulationController;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * EconomyDraft 2026-10-21: a few lines, a few turns ahead.
 *
 * The one-play simulation (OnePlaySafetyChecker) plays each candidate once,
 * lets the stack resolve and scores the board as it stands. That catches a
 * trick that gets a creature killed, and nothing whose value is conditional
 * on a LATER turn: Lich's Relic ({B} equipment, "when this enters you may pay
 * {2}: destroy a creature") scored +75 on turn 2 with one land and no target,
 * because an artifact on the table is 80 points and a card in hand is 5, and
 * a trigger that did nothing cost nothing. "Hold it" was never an option the
 * scorer saw: the comparison ranks plays against each other, not against
 * passing.
 *
 * This plays "pass" and the best few candidates each straight down on a copy
 * of the game -- no branching: the copy is played forward by the plain
 * rule-based AI on both seats (Game.ROLLOUT) through LOOKAHEAD_TURNS turns,
 * with the opponent's hand redrawn from their unseen cards (Determinize) --
 * and scores the board at the end of the AI's next turn. The line with the
 * best end score wins when it beats the one-play answer's line by
 * LOOKAHEAD_MARGIN. One copy lives at a time, so the heap holds one extra
 * game, not a tree; cost grows with the depth, not the breadth.
 *
 * It runs only when the one-play scorer is likely to be wrong (see
 * {@link #gate}), on the seat's own main phase with an empty stack, never in
 * a copy, never over the turn budget, and stops at LOOKAHEAD_BUDGET_MS with
 * the one-play answer kept. The playout policy matters: a card held in the
 * "pass" line is cast later by the same rule-based AI, so the generic hold
 * rules it carries (ComputerUtil.etbUnusable: an enters effect it cannot pay
 * for or aim yet) are what make the held line real.
 *
 * Knobs: AiProps LOOKAHEAD_LINES (0 = off), LOOKAHEAD_TURNS,
 * LOOKAHEAD_BUDGET_MS, LOOKAHEAD_MARGIN. -Dbridge.lookahead=off kills it;
 * -Dbridge.lookahead.log=true prints every run as [LOOKAHEAD].
 */
public final class Lookahead {
    private static final boolean OFF = "off".equals(System.getProperty("bridge.lookahead"));
    private static final boolean LOG = Boolean.getBoolean("bridge.lookahead.log");
    /** Priority windows a rollout may step through before it is called off. */
    private static final int MAX_STEPS = 600;

    public static final LongAdder lines = new LongAdder();
    public static final LongAdder lineMs = new LongAdder();
    public static final LongAdder aborted = new LongAdder();

    private Lookahead() { }

    /** A candidate the one-play comparison accepted, with its one-play score. */
    public static final class Cand {
        public final SpellAbility sa;
        public final Integer x;
        public final int score;

        public Cand(final SpellAbility sa, final Integer x, final int score) {
            this.sa = sa;
            this.x = x;
            this.score = score;
        }

        String label() {
            final String d = sa.getDescription() == null ? "" : sa.getDescription().replace('\n', ' ');
            return sa.getHostCard().getName() + (x != null ? " X=" + x : "")
                    + (d.isEmpty() ? "" : " [" + d.substring(0, Math.min(30, d.length())) + "]");
        }
    }

    /** The lookahead's answer: {@code cand == null} means pass. */
    public static final class Result {
        public final Cand cand;
        public final String why;

        Result(final Cand cand, final String why) {
            this.cand = cand;
            this.why = why;
        }
    }

    /**
     * Refine the one-play comparison's answer. {@code cands} are the accepted
     * candidates with their scores, {@code cands.get(0)} the one the
     * comparison chose. Null keeps that answer.
     */
    public static Result refine(final AiController aic, final Player ai, final List<Cand> cands) {
        if (OFF || cands == null || cands.isEmpty()) {
            return null;
        }
        final Game game = ai.getGame();
        if (game == null || game.SIMULATION_COPY || game.ROLLOUT || game.getPlayers().size() != 2) {
            return null;
        }
        if (!aic.usesHybridSimulation() || AiPerf.fast(ai)) {
            return null;
        }
        final int k = aic.getIntProperty(AiProps.LOOKAHEAD_LINES);
        if (k <= 0) {
            return null;
        }
        final PhaseHandler ph = game.getPhaseHandler();
        if (!ph.isPlayerTurn(ai) || ph.getPhase() == null || !ph.getPhase().isMain() || !game.getStack().isEmpty()) {
            return null;
        }
        final int margin = aic.getIntProperty(AiProps.LOOKAHEAD_MARGIN);
        final String why = gate(ai, cands, margin);
        if (why == null) {
            return null;
        }

        final long t0 = System.nanoTime();
        final long deadline = t0 + aic.getIntProperty(AiProps.LOOKAHEAD_BUDGET_MS) * 1_000_000L;
        final int turns = Math.max(1, aic.getIntProperty(AiProps.LOOKAHEAD_TURNS));
        final int targetTurn = ph.getTurn() + turns;
        // one redraw shared by every line, so the guess cancels out in the comparison
        final long seed = GameSimulator.class.hashCode() ^ (ph.getTurn() * 31L + game.getTimestamp());
        AiPerf.lookN.increment();

        final List<Cand> top = cands.subList(0, Math.min(k, cands.size()));
        final StringBuilder log = LOG ? new StringBuilder("[LOOKAHEAD] ").append(ai.getName())
                .append(" turn ").append(ph.getTurn()).append(' ').append(ph.getPhase())
                .append(" (").append(why).append(")") : null;
        try {
            final Integer passScore = rollout(game, ai, null, targetTurn, deadline, seed);
            if (log != null) {
                log.append("\n    pass -> ").append(passScore);
            }
            if (passScore == null) {
                aborted.increment();
                return null;
            }
            Integer first = null;
            Cand best = null;
            int bestScore = Integer.MIN_VALUE;
            for (final Cand c : top) {
                final Integer s = rollout(game, ai, c, targetTurn, deadline, seed);
                if (log != null) {
                    log.append("\n    ").append(c.label()).append(" (one-play ").append(c.score).append(") -> ").append(s);
                }
                if (s == null) {
                    aborted.increment();
                    return null;
                }
                if (first == null) {
                    first = s;
                }
                if (best == null || s > bestScore) {
                    best = c;
                    bestScore = s;
                }
            }
            Result r = null;
            if (passScore > bestScore && passScore - first > margin) {
                r = new Result(null, "pass " + passScore + " beats " + top.get(0).label() + " " + first);
            } else if (best != null && best != top.get(0) && bestScore - first > margin) {
                r = new Result(best, best.label() + " " + bestScore + " beats " + top.get(0).label() + " " + first);
            }
            if (r != null) {
                AiPerf.lookChanged.increment();
            }
            if (log != null) {
                log.append("\n    => ").append(r == null ? "keep " + top.get(0).label() : r.why);
            }
            return r;
        } finally {
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            AiPerf.lookMs.add(ms);
            if (log != null) {
                System.out.println(log.append(" [").append(ms).append(" ms]"));
            }
        }
    }

    /**
     * Why the one-play scorer may be wrong here, or null when it is trusted.
     * Cheap checks only; the lookahead itself is the expensive part.
     */
    static String gate(final Player ai, final List<Cand> cands, final int margin) {
        final Cand top = cands.get(0);
        for (final Cand c : cands) {
            final String more = ComputerUtil.doesMoreLater(ai, c.sa);
            if (more != null) {
                return more;
            }
        }
        final Card host = top.sa.getHostCard();
        if (top.sa.isSpell() && host.isPermanent() && !host.isCreature() && !host.isPlaneswalker()) {
            return "noncreature permanent";
        }
        if (cands.size() > 1 && top.score - cands.get(1).score <= margin) {
            return "close: " + top.score + " vs " + cands.get(1).score;
        }
        if (ComputerUtil.manaLeftAfter(ai, top.sa) <= 0) {
            final int others = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS).size()
                    - (host.isInZone(ZoneType.Hand) ? 1 : 0);
            if (others > 0) {
                return "taps out with " + others + " in hand";
            }
        }
        return null;
    }

    /**
     * Play one line on a copy: the candidate (or nothing), then the game
     * forward to the end of turn {@code targetTurn}. The board's score there,
     * or null when the line could not be played out inside the budget.
     */
    private static Integer rollout(final Game game, final Player ai, final Cand cand, final int targetTurn,
            final long deadline, final long seed) {
        final long t0 = System.nanoTime();
        lines.increment();
        final Object scope = AiPerf.detachScope();
        try {
            final GameSimulator sim = new GameSimulator(new SimulationController(new Score(0), 0), game, ai, null);
            final Game copy = sim.getSimulatedGameState();
            final Player aiCopy = sim.getGameCopier().find(ai);
            if (aiCopy == null) {
                return null;
            }
            copy.ROLLOUT = true;
            Determinize.apply(copy, aiCopy, new Random(seed));
            final PhaseHandler cph = copy.getPhaseHandler();
            if (cand != null) {
                if (cand.x != null) {
                    cand.sa.getRootAbility().setXManaCostPaid(cand.x);
                }
                final Score s = sim.simulateSpellAbility(cand.sa);
                if (s.value == Integer.MIN_VALUE) {
                    return null;
                }
                // 117.3c: the player who cast keeps priority
                cph.devGivePriority(aiCopy);
            } else {
                // everyone passes: the phase ends, the AI is asked again in the next one
                final PhaseType next = PhaseType.getNext(cph.getPhase(), cph.getPlayerTurn().isPhasesReversed());
                final Player opp = aiCopy.getWeakestOpponent();
                cph.devAdvanceToPhase(next, () -> GameSimulator.resolveStack(copy, opp));
                cph.devGivePriority(cph.getPlayerTurn());
            }
            int steps = 0;
            while (!copy.isGameOver() && !reached(cph, targetTurn)) {
                if (steps++ >= MAX_STEPS || System.nanoTime() > deadline) {
                    return null;
                }
                cph.mainLoopStep();
            }
            return score(copy, aiCopy);
        } catch (final RuntimeException e) {
            if (LOG) {
                System.out.println("[LOOKAHEAD] line failed: " + e);
            }
            return null;
        } finally {
            AiPerf.reattachScope(scope);
            lineMs.add((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /** Past the second main phase of the target turn. */
    private static boolean reached(final PhaseHandler ph, final int targetTurn) {
        if (ph.getTurn() > targetTurn) {
            return true;
        }
        return ph.getTurn() == targetTurn && ph.getPhase() != null && ph.getPhase().isAfter(PhaseType.MAIN2);
    }

    /** The board as the evaluator sees it, plus CombatSim's life term. */
    private static int score(final Game copy, final Player aiCopy) {
        final int v = new GameStateEvaluator().getScoreForGameState(copy, aiCopy).value;
        if (GameStateEvaluator.isWinning(v) || !aiCopy.isInGame()) {
            return v;
        }
        final int w = ((PlayerControllerAi) aiCopy.getController()).getAi().getIntProperty(AiProps.SIM_COMBAT_LIFE_WEIGHT);
        if (w <= 0) {
            return v;
        }
        final Player opp = aiCopy.getWeakestOpponent();
        final double mine = Math.log(Math.max(1, aiCopy.getLife()));
        final double theirs = opp == null ? 0 : Math.log(Math.max(1, opp.getLife()));
        return v + (int) Math.round(w * (mine - theirs));
    }
}
