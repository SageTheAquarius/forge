package forge.ai.simulation;


import forge.ai.AIOption;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.util.collect.FCollectionView;

import java.util.*;

public class GameSimulator {
    public static boolean COPY_STACK = false;
    final private SimulationController controller;
    private GameCopier copier;
    private Game simGame;
    private Player aiPlayer;
    private GameStateEvaluator eval;
    private List<String> origLines;
    private Score origScore;
    private SpellAbilityChoicesIterator interceptor;

    // Verifying that the copied game scores identically to the original costs a second full
    // evaluation (with debug string building enabled) for every simulator that gets constructed.
    // That's worth paying for in development and in the test suite, where a game copy bug should
    // fail loudly, but not in a shipped game where it only slows the AI down. Assertions are
    // enabled by Maven Surefire, so the check still runs for every test.
    private static final boolean CHECK_GAME_COPY_SCORE = areAssertionsEnabled();

    @SuppressWarnings("AssertWithSideEffects")
    private static boolean areAssertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    public GameSimulator(SimulationController controller, Game origGame, Player origAiPlayer, PhaseType advanceToPhase) {
        this.controller = controller;
        copier = new GameCopier(origGame);
        simGame = copier.makeCopy(advanceToPhase, origAiPlayer);

        aiPlayer = copier.find(origAiPlayer);
        eval = new GameStateEvaluator();

        origLines = new ArrayList<>();
        debugLines = origLines;

        debugPrint = SIM_DEBUG;
        origScore = cachedOrigScore(origGame, origAiPlayer);

        if (advanceToPhase == null && CHECK_GAME_COPY_SCORE) {
            ensureGameCopyScoreMatches(origGame, origAiPlayer);
        }

        // If the stack on the original game is not empty, resolve it
        // first and get the updated eval score, since this is what we'll
        // want to compare to the eval score after simulating.
        if (COPY_STACK && !origGame.getStackZone().isEmpty()) {
            origLines = new ArrayList<>();
            debugLines = origLines;
            Game copyOrigGame = copier.makeCopy();
            Player copyOrigAiPlayer = copyOrigGame.getPlayers().get(1);
            resolveStack(copyOrigGame, copyOrigGame.getPlayers().get(0));
            origScore = eval.getScoreForGameState(copyOrigGame, copyOrigAiPlayer);
        }

        debugPrint = SIM_DEBUG;
        debugLines = null;
    }

    /** -Dbridge.simdebug=true: print how every simulated state is scored (card by card). */
    private static final boolean SIM_DEBUG = Boolean.getBoolean("bridge.simdebug");

    // EconomyDraft 2026-10-17: the score of the ORIGINAL position is the same
    // for every candidate play the AI tries in one decision, but each
    // GameSimulator recomputed it -- including a whole game copy for the
    // upcoming-combat lookahead -- so a third of all copies were repeats.
    // Cached per game object and full board fingerprint (weak keys: a
    // simulated copy's entry goes with it). -Dbridge.scorecache=false: off.
    private static final boolean SCORE_CACHE = !"false".equals(System.getProperty("bridge.scorecache"));
    private static final java.util.Map<Game, Object[]> ORIG_SCORES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final java.util.concurrent.atomic.AtomicLong CACHE_HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CACHE_MISSES = new java.util.concurrent.atomic.AtomicLong();

    private static String diffAt(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(Math.max(0, i - 10), Math.min(a.length(), i + 30));
    }

    private Score cachedOrigScore(Game origGame, Player origAiPlayer) {
        if (!SCORE_CACHE) {
            return eval.getScoreForGameState(origGame, origAiPlayer);
        }
        String fp = boardFingerprint(origGame, origAiPlayer);
        Object[] hit = ORIG_SCORES.get(origGame);
        if (hit != null && fp.equals(hit[0])) {
            CACHE_HITS.incrementAndGet();
            return (Score) hit[1];
        }
        long miss = CACHE_MISSES.incrementAndGet();
        if (miss % 1000 == 0) {
            System.out.println("[SCORECACHE] hits " + CACHE_HITS.get() + " misses " + miss
                    + (hit == null ? " (new game)" : " (changed: " + diffAt(fp, (String) hit[0]) + ")"));
        }
        Score sc = eval.getScoreForGameState(origGame, origAiPlayer);
        ORIG_SCORES.put(origGame, new Object[] {fp, sc});
        return sc;
    }

    /** Everything the evaluator and its combat lookahead read, as one string. */
    static String boardFingerprint(Game g, Player ai) {
        StringBuilder b = new StringBuilder(512);
        // (not the game's timestamp: scoring itself advances it on the real
        // game, so every lookup missed)
        b.append(ai.getId()).append('|')
         .append(g.getPhaseHandler().getTurn()).append(g.getPhaseHandler().getPhase())
         .append(g.getPhaseHandler().getPlayerTurn() == null ? -1 : g.getPhaseHandler().getPlayerTurn().getId())
         .append('|').append(g.getStack().size()).append('|');
        for (Player p : g.getPlayers()) {
            b.append(p.getId()).append(':').append(p.getLife()).append(',').append(p.getPoisonCounters())
             .append(',').append(p.getCardsIn(forge.game.zone.ZoneType.Hand).size())
             .append(',').append(p.getCardsIn(forge.game.zone.ZoneType.Library).size())
             .append(',').append(p.getManaPool().totalMana()).append(';');
        }
        for (Card c : g.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
            b.append(c.getId()).append(c.isTapped() ? 't' : 'u').append(c.getDamage())
             .append('/').append(c.getNetPower()).append('/').append(c.getNetToughness())
             .append(c.getCounters().hashCode()).append(c.getController().getId()).append(',');
        }
        return b.toString();
    }

    private void ensureGameCopyScoreMatches(Game origGame, Player origAiPlayer) {
        eval.setDebugging(true);
        List<String> simLines = new ArrayList<>();
        debugLines = simLines;
        Score simScore = eval.getScoreForGameState(simGame, aiPlayer);
        if (!simScore.equals(origScore)) {
            // Re-eval orig with debug printing.
            origLines = new ArrayList<>();
            debugLines = origLines;
            eval.getScoreForGameState(origGame, origAiPlayer);
            // Print debug info.
            printDiff(origLines, simLines);
            // make sure it gets printed
            System.out.flush();
            throw new RuntimeException("Game copy error. See diff output above for details.");
        }
        eval.setDebugging(false);
    }

    public void setInterceptor(SpellAbilityChoicesIterator interceptor) {
        this.interceptor = interceptor;
        ((PlayerControllerAi) aiPlayer.getController()).getAi().getSimulationPicker().setInterceptor(interceptor);
    }

    private void printDiff(List<String> lines1, List<String> lines2) {
        int i = 0;
        int j = 0;
        Collections.sort(lines1);
        Collections.sort(lines2);
        while (i < lines1.size() && j < lines2.size()) {
            String left = lines1.get(i);
            String right = lines2.get(j);
            int cmp = left.compareTo(right);
            if (cmp == 0) {
                i++; j++;
            } else if (cmp < 0) {
                System.out.println("-" + left);
                i++;
            } else { 
                System.out.println("+"  + right);
                j++;
            }
        }
        while (i < lines1.size()) {
            System.out.println("-" + lines1.get(i++));
        }
        while (j < lines2.size()) {
            System.out.println("+" + lines2.get(j++));
        }
    }

    public static boolean debugPrint;
    public static List<String> debugLines;
    public static void debugPrint(String str) {
        if (debugPrint) {
            System.out.println(str);
        }
        if (debugLines != null) {
            debugLines.add(str);
        }
    }

    private SpellAbility findSaInSimGame(final SpellAbility sa) {
        // is already an ability from sim game
        if (sa.getHostCard().getGame().equals(this.simGame)) {
            return sa;
        }
        Card origHostCard = sa.getHostCard();
        Card hostCard = copier.find(origHostCard);
        String desc = sa.getDescription();
        // TODO tests fail if this isn't checked first
        FCollectionView<SpellAbility> candidates = hostCard.getSpellAbilities();

        SpellAbility result = saMatcher(candidates, desc);
        if (result == null) {
            // could try and reimplement this so a quick match doesn't require building the rest first
            result = saMatcher(hostCard.getAllPossibleAbilities(aiPlayer, true), desc);
        }

        if (result != null) {
            result = SpellAbilityChoiceCopier.copyCastChoices(sa, result, aiPlayer);
        }

        return result;
    }

    private SpellAbility saMatcher(Iterable<SpellAbility> candidates, String desc) {
        // first pass for accuracy (spells with alternative costs)
        for (SpellAbility cSa : candidates) {
            if (desc.equals(cSa.getDescription())) {
                return cSa;
            }
        }
        // fall back for safety
        for (SpellAbility cSa : candidates) {
            if (desc.startsWith(cSa.getDescription())) {
                return cSa;
            }
        }
        return null;
    }

    public Score simulateSpellAbility(SpellAbility origSa) {
        return simulateSpellAbility(origSa, this.eval, true);
    }
    public Score simulateSpellAbility(SpellAbility origSa, boolean resolve) {
        return simulateSpellAbility(origSa, this.eval, resolve);
    }
    public Score simulateSpellAbility(SpellAbility origSa, GameStateEvaluator eval, boolean resolve) {
        SpellAbility sa;
        if (origSa.isLandAbility()) {
            Card hostCard = (Card) copier.find(origSa.getHostCard());
            if (origSa.canPlay()) {
                aiPlayer.playLand(hostCard, origSa);
            } else {
                System.err.println("Simulation: Couldn't play land! " + origSa);
            }
            sa = origSa;
        } else {
            // TODO: optimize: prune identical SA (e.g. two of the same card in hand)
            sa = findSaInSimGame(origSa);
            if (sa == null) {
                System.err.println("Simulation: SA not found! " + origSa + " / " + origSa.getClass());
                return new Score(Integer.MIN_VALUE);
            }

            debugPrint("Found SA " + sa + " on host card " + sa.getHostCard() + " with owner:"+ sa.getHostCard().getOwner());
            sa.setActivatingPlayer(aiPlayer);

            simGame.copyLastState();
            boolean success = ComputerUtil.handlePlayingSpellAbility(aiPlayer, sa, playingSa -> {
                if (interceptor != null) {
                    interceptor.announceX(playingSa);
                    interceptor.chooseTargets(playingSa, GameSimulator.this);
                } else {
                    SpellAbilityChoiceCopier.copyTargets(origSa, playingSa, copier::find);
                }
                if (debugPrint && !playingSa.getAllTargetChoices().isEmpty()) {
                    debugPrint("Targets: ");
                    for (TargetChoices target : playingSa.getAllTargetChoices()) {
                        System.out.print(target);
                    }
                    System.out.println();
                }
            });
            if (!success) {
                return new Score(Integer.MIN_VALUE);
            }
        }

        if (resolve) {
            // TODO: Support multiple opponents.
            Player opponent = aiPlayer.getWeakestOpponent();
            resolveStack(simGame, opponent);
        }

        // TODO: If this is during combat, before blockers are declared,
        // we should simulate how combat will resolve and evaluate that
        // state instead!
        List<String> simLines = null;
        if (debugPrint) {
            debugPrint("SimGame:");
            simLines = new ArrayList<>();
            debugLines = simLines;
            debugPrint = false;
        }
        Score score = eval.getScoreForGameState(simGame, aiPlayer);
        if (simLines != null) {
            debugLines = null;
            debugPrint = true;
            printDiff(origLines, simLines);
        }
        controller.possiblyCacheResult(score, origSa);
        if (controller.shouldRecurse() && !simGame.isGameOver()) {
            controller.push(sa, score, this);
            SpellAbilityPicker sim = new SpellAbilityPicker(aiPlayer);
            SpellAbility nextSa = sim.chooseSpellAbilityToPlay(controller);
            if (nextSa != null) {
                score = sim.getScoreForChosenAbility();
            }
            controller.pop(score, nextSa);
        }

        return score;
    }

    public static void resolveStack(final Game game, final Player opponent) {
        // TODO: This needs to set an AI controller for all opponents, in case of multiplayer.
        PlayerControllerAi sim = new PlayerControllerAi(game, opponent, opponent.getLobbyPlayer());
        sim.getAi().setUseSimulation(AIOption.USE_FULL_SIMULATION);
        opponent.runWithController(() -> {
            final Set<Card> allAffectedCards = new HashSet<>();
            game.getAction().checkStateEffects(false, allAffectedCards);
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.getStack().isEmpty() && !game.isGameOver()) {
                debugPrint("Resolving:" + game.getStack().peekAbility());

                // Resolve the top effect on the stack.
                game.getStack().resolveStack();

                // Evaluate state based effects as a result of resolving stack.
                // Note: Needs to happen after resolve stack rather than at the
                // top of the loop to ensure state effects are evaluated after the
                // last resolved effect
                game.getAction().checkStateEffects(false, allAffectedCards);

                // Add any triggers additional triggers as a result of the above.
                // Must be below state effects, since legendary rule is evaluated
                // as part of state effects and trigger come afterward. (e.g. to
                // correctly handle two Dark Depths - one having no counters).
                game.getStack().addAllTriggeredAbilitiesToStack();

                // Continue until stack is empty.
            }
        }, sim);
    }

    public Game getSimulatedGameState() {
        return simGame;
    }

    public Score getScoreForOrigGame() {
        return origScore;
    }

    public GameCopier getGameCopier() {
        return copier;
    }
}
