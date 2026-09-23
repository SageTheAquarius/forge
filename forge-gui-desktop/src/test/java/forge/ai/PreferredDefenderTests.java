package forge.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * EconomyDraft (2026-09-23): who a multiplayer AI seat attacks. The stock
 * quadratic low-life term made every AI in a 12-life Lightning pod dogpile the
 * lowest seat; now that only happens when the seat is in lethal range.
 */
public class PreferredDefenderTests extends AITest {

    /** A four-seat free-for-all at {@code startingLife}; seat 0 is the AI under test. */
    private Game fourSeatGame(int startingLife) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        for (String name : new String[] {"ai", "opp1", "opp2", "opp3"}) {
            RegisteredPlayer rp = new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi(name, null));
            rp.setStartingLife(startingLife);
            players.add(rp);
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        Player ai = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    private Set<Player> draws(Player ai, int n) {
        Set<Player> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            AiCache.clear();
            seen.add(AiAttackController.choosePreferredDefenderPlayer(ai));
        }
        return seen;
    }

    @Test
    public void lowestSeatOutOfLethalRangeIsNotTheAutomaticTarget() {
        Game game = fourSeatGame(12);
        Player ai = game.getPlayers().get(0);
        Player low = game.getPlayers().get(1);
        game.getPlayers().get(1).setLife(6, null);
        game.getPlayers().get(2).setLife(12, null);
        game.getPlayers().get(3).setLife(12, null);
        Card bear = addCard("Grizzly Bears", ai);
        bear.setSickness(false);
        game.getAction().checkStateEffects(true);

        Map<Player, Integer> scores = AiAttackController.threatScores(ai, ai.getOpponents(), false);
        int lowScore = scores.get(low);
        for (Player opp : ai.getOpponents()) {
            if (opp != low) {
                AssertJUnit.assertTrue("a healthy seat (" + scores.get(opp) + ") must outscore the 6-life seat ("
                        + lowScore + ") when 2 power cannot reach it", scores.get(opp) > lowScore);
            }
        }
        Set<Player> seen = draws(ai, 30);
        AssertJUnit.assertFalse("the 6-life seat was still attacked at least once in 30 draws", seen.contains(low));
    }

    @Test
    public void seatInLethalRangeIsStillTheTarget() {
        Game game = fourSeatGame(12);
        Player ai = game.getPlayers().get(0);
        Player low = game.getPlayers().get(1);
        game.getPlayers().get(1).setLife(2, null);
        game.getPlayers().get(2).setLife(12, null);
        game.getPlayers().get(3).setLife(12, null);
        Card bear = addCard("Grizzly Bears", ai);
        bear.setSickness(false);
        game.getAction().checkStateEffects(true);

        Set<Player> seen = draws(ai, 30);
        AssertJUnit.assertEquals("a 2-life seat facing 2 power is the only sensible target", 1, seen.size());
        AssertJUnit.assertTrue(seen.contains(low));
    }

    @Test
    public void reachableSeatByAvailablePowerIsLethal() {
        Game game = fourSeatGame(12);
        Player ai = game.getPlayers().get(0);
        Player low = game.getPlayers().get(1);
        game.getPlayers().get(1).setLife(6, null);
        game.getPlayers().get(2).setLife(12, null);
        game.getPlayers().get(3).setLife(12, null);
        for (Card c : addCards("Grizzly Bears", 3, ai)) {
            c.setSickness(false);
        }
        game.getAction().checkStateEffects(true);

        AssertJUnit.assertEquals(6, AiAttackController.cheapAvailablePower(ai));
        Set<Player> seen = draws(ai, 30);
        AssertJUnit.assertEquals("6 life against 6 power is lethal range", 1, seen.size());
        AssertJUnit.assertTrue(seen.contains(low));
    }

    @Test
    public void spreadBarelyRegistersAtFortyLife() {
        Game game = fourSeatGame(40);
        Player ai = game.getPlayers().get(0);
        game.getPlayers().get(1).setLife(30, null);
        game.getPlayers().get(2).setLife(40, null);
        game.getPlayers().get(3).setLife(40, null);
        game.getAction().checkStateEffects(true);

        Map<Player, Integer> scores = AiAttackController.threatScores(ai, ai.getOpponents(), false);
        int hi = scores.get(game.getPlayers().get(2));
        int lo = scores.get(game.getPlayers().get(1));
        // 30 vs a 36.7 mean: -15 spread; the 40s get +7 each. Nothing near a board's worth.
        AssertJUnit.assertTrue("spread at 40 life should be a few points, was " + (hi - lo), hi - lo < 40);
    }
}
