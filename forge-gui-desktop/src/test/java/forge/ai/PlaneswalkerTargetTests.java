package forge.ai;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * EconomyDraft (2026-10-01): when the AI attacks a planeswalker instead of the
 * player. Stock Forge sent everything at any walker on the table unless it had
 * lethal on the player; now a walker has to be finishable this combat (with
 * the player not already in a race) or about to ultimate.
 *
 * Also the Lightning Round mulligan, which kept every hand because the decks
 * have no lands.
 */
public class PlaneswalkerTargetTests extends AITest {

    private Game game;
    private Player ai;
    private Player opp;

    private void setUp(int oppLife, int bears) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        opp.setLife(oppLife, null);
        for (Card c : addCards("Grizzly Bears", bears, ai)) {
            c.setSickness(false);
        }
    }

    private Card liliana(int loyalty) {
        Card pw = addCard("Liliana of the Veil", opp);
        pw.setCounters(CounterEnumType.LOYALTY, loyalty);
        return pw;
    }

    /** Who the first Grizzly Bears attacks, or null if it stays home. */
    private GameEntity attackTarget() {
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        game.getAction().checkStateEffects(true);
        AiCache.clear();
        Combat combat = new Combat(ai);
        new AiAttackController(ai).declareAttackers(combat);
        Card bear = findCardWithName(game, "Grizzly Bears");
        return combat.getDefenderByAttacker(bear);
    }

    @Test
    public void walkerTheAttackCannotFinishIsIgnored() {
        setUp(20, 1);
        liliana(3);
        AssertJUnit.assertEquals("2 power cannot kill a 3-loyalty walker: hit the player", opp, attackTarget());
    }

    @Test
    public void walkerTheAttackCanFinishIsKilled() {
        setUp(20, 2);
        Card pw = liliana(3);
        AssertJUnit.assertEquals("4 power finishes a 3-loyalty walker with the player at 20", pw, attackTarget());
    }

    @Test
    public void playerInRaceRangeIsHitInstead() {
        setUp(7, 2);
        liliana(3);
        AssertJUnit.assertEquals("7 life is two swings of 4: race the player", opp, attackTarget());
    }

    @Test
    public void walkerAboutToUltimateIsAlwaysAttacked() {
        setUp(20, 1);
        Card pw = liliana(5);
        AssertJUnit.assertTrue(ComputerUtilCard.isNearUltimate(pw));
        AssertJUnit.assertEquals("Liliana at 5 ultimates next turn: attack her even without the kill", pw, attackTarget());
    }

    @Test
    public void freshWalkerIsNotNearUltimate() {
        setUp(20, 0);
        AssertJUnit.assertFalse(ComputerUtilCard.isNearUltimate(liliana(3)));
    }

    // ---- Lightning Round mulligan ----

    private int lightningHandScore(int cardsToReturn, String... hand) {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        // a Vanguard card: the variant DB, as Player.initVariantsZones reads it
        ai.getZone(ZoneType.Command).add(Card.fromPaperCard(
                forge.StaticData.instance().getVariantCards().getCard("Lightning Round Rules"), ai));
        for (int i = 0; i < 10; i++) {
            addCardToZone("Hill Giant", ai, ZoneType.Library);
        }
        for (String name : hand) {
            addCardToZone(name, ai, ZoneType.Hand);
        }
        return ComputerUtil.scoreHand(ai.getCardsIn(ZoneType.Hand), ai, cardsToReturn);
    }

    @Test
    public void lightningHandWithNoEarlyPlaysIsMulliganed() {
        AssertJUnit.assertEquals(0, lightningHandScore(0,
                "Hill Giant", "Hill Giant", "Air Elemental", "Air Elemental", "Hill Giant"));
    }

    @Test
    public void lightningHandWithACurveIsKept() {
        AssertJUnit.assertTrue(lightningHandScore(0,
                "Grizzly Bears", "Centaur Courser", "Hill Giant", "Air Elemental", "Air Elemental") > 0);
    }

    @Test
    public void lightningPaidMulliganKeepsAnyThreeDrop() {
        AssertJUnit.assertTrue(lightningHandScore(1,
                "Centaur Courser", "Hill Giant", "Air Elemental", "Air Elemental", "Hill Giant") > 0);
    }

    @Test
    public void landlessDeckOutsideLightningStillKeeps() {
        game = initAndCreateGame();
        ai = game.getPlayers().get(1);
        for (int i = 0; i < 10; i++) {
            addCardToZone("Hill Giant", ai, ZoneType.Library);
        }
        for (int i = 0; i < 5; i++) {
            addCardToZone("Air Elemental", ai, ZoneType.Hand);
        }
        AssertJUnit.assertTrue(ComputerUtil.scoreHand(ai.getCardsIn(ZoneType.Hand), ai, 0) > 0);
    }
}
