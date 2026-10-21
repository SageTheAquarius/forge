package forge.ai;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * EconomyDraft 2026-10-20, prod game (session_4_sagetheaquarius, turn 15-16):
 * the AI chump-blocked a Wrecking Gecko with Samut, Tyrant of Naktamun instead
 * of a Mindseeker Oculus wearing Medic's Kitesail, then cast Perfected Theory
 * ("base 1/1 until end of turn") on the Gecko at its own beginning of combat.
 */
public class ChumpAndTempCurseTest extends AITest {

    private Card equippedOculus(Player ai) {
        Card oculus = addCard("Mindseeker Oculus", ai);
        Card kitesail = addCard("Medic's Kitesail", ai);
        kitesail.attachToEntity(oculus, null);
        return oculus;
    }

    @Test
    public void testWorstCreatureIgnoresEquipmentThatStays() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card samut = addCard("Samut, Tyrant of Naktamun", ai);
        Card oculus = equippedOculus(ai);
        game.getAction().checkStateEffects(true);

        AssertJUnit.assertEquals("Kitesail should be applied", 3, oculus.getNetPower());
        AssertJUnit.assertTrue("Samut's static should be worth more than a spent ETB",
                ComputerUtilCard.evaluateCreatureAsLoss(samut) > ComputerUtilCard.evaluateCreatureAsLoss(oculus));
        AssertJUnit.assertSame(oculus, ComputerUtilCard.getWorstCreatureAI(List.of(samut, oculus)));
    }

    @Test
    public void testChumpBlocksWithTheEquippedVanillaCreature() {
        Game game = initAndCreateGame();
        Player human = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        // 10 - 7 leaves 3: in danger. (At 5 or less the AI stays "in danger"
        // after one chump and throws in every blocker.) A 7/7, not the game's
        // 5/5 Gecko: 3 + 2 power kills a 5/5, and that gang block is a
        // different (legitimate) decision from which creature to chump with.
        ai.setLife(10, null);

        Card gecko = addCard("Enormous Baloth", human);
        gecko.setSickness(false);
        Card samut = addCard("Samut, Tyrant of Naktamun", ai);
        Card oculus = equippedOculus(ai);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, human);
        game.getAction().checkStateEffects(true);

        Combat combat = new Combat(human);
        combat.addAttacker(gecko, ai);
        new AiBlockController(ai, false).assignBlockersForCombat(combat);

        // isBlocked() is only set when the game finalizes the declaration
        AssertJUnit.assertFalse("At 10 life against a 7/7 the AI must block", combat.getBlockers(gecko).isEmpty());
        AssertJUnit.assertTrue("Oculus (spent ETB, its Kitesail stays) is the chump", combat.isBlocking(oculus));
        AssertJUnit.assertFalse("Samut keeps its split-second static", combat.isBlocking(samut));
    }

    private Game perfectedTheoryBoard() {
        Game game = initAndCreateGame();
        Player human = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        addCard("Wrecking Gecko", human).setSickness(false);
        equippedOculus(ai).setSickness(false);
        for (int i = 0; i < 4; i++) {
            addCard("Island", ai);
        }
        addCardToZone("Perfected Theory", ai, ZoneType.Hand);
        return game;
    }

    /** The Perfected Theory mode + target the AI casts now, or null. */
    private String perfectedTheoryCast(Player ai) {
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        List<SpellAbility> sas = aic.chooseSpellAbilityToPlay();
        if (sas == null) {
            return null;
        }
        for (SpellAbility sa : sas) {
            if ("Perfected Theory".equals(sa.getHostCard().getName())) {
                StringBuilder sb = new StringBuilder();
                for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                    sb.append(s.getDescription()).append(" -> ").append(s.getTargets()).append("; ");
                }
                return sb.toString();
            }
        }
        return null;
    }

    @Test
    public void testNoShrinkOnACreatureThatCannotBlockAnything() {
        Game game = perfectedTheoryBoard();
        Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, ai);
        game.getAction().checkStateEffects(true);

        // The Gecko cannot block the AI's only attacker (a flier) and is a
        // 5/5 again on its controller's turn: the shrink buys nothing.
        String cast = perfectedTheoryCast(ai);
        AssertJUnit.assertTrue("No 1/1 on the Gecko: " + cast,
                cast == null || !cast.contains("Wrecking Gecko"));
    }

    @Test
    public void testNoShrinkWithNothingToAttackWith() {
        Game game = initAndCreateGame();
        Player human = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        addCard("Wrecking Gecko", human).setSickness(false);
        for (int i = 0; i < 4; i++) {
            addCard("Island", ai);
        }
        addCardToZone("Perfected Theory", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, ai);
        game.getAction().checkStateEffects(true);

        // Either mode on the Gecko (1/1, or 4/5 as a "shrink") wears off
        // before it can matter; without the fix the AI cast one every time.
        String cast = perfectedTheoryCast(ai);
        AssertJUnit.assertNull("Wasted Perfected Theory: " + cast, cast);
    }

    @Test
    public void testShrinkAnAttackerThatABlockerThenKills() {
        Game game = perfectedTheoryBoard();
        Player human = game.getPlayers().get(0);
        Player ai = game.getPlayers().get(1);
        Card gecko = findCardWithName(game, "Wrecking Gecko");
        addCard("Samut, Tyrant of Naktamun", ai);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, human);
        Combat combat = new Combat(human);
        combat.addAttacker(gecko, ai);
        game.getPhaseHandler().setCombat(combat);
        game.getAction().checkStateEffects(true);

        // The Gecko is attacking now: a 1/1 dies to a 2/1 blocker this turn.
        String cast = perfectedTheoryCast(ai);
        AssertJUnit.assertNotNull("Shrinking an attacking Gecko is the card's job", cast);
    }
}
