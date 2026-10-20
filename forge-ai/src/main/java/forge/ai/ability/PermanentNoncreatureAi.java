package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** 
 * AbilityFactory for Creature Spells.
 *
 */
public class PermanentNoncreatureAi extends PermanentAi {

    /**
     * The rest of the logic not covered by the canPlayAI template is defined
     * here
     */
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        AiAbilityDecision decision = super.checkApiLogic(ai, sa);
        if (!decision.willingToPlay()) {
            return decision;
        }

        final Card host = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        final Game game = ai.getGame();

        // Check for valid targets before casting
        if (host.hasSVar("OblivionRing")) {
            // TODO: only the "may" case wouldn't fail checkETBEffects - replace with NeedsToPlay SVar?
            SpellAbility effectExile = AbilityFactory.getAbility(host.getSVar("TrigExile"), host);
            final ZoneType origin = ZoneType.listValueOf(effectExile.getParamOrDefault("Origin", "Battlefield")).get(0);
            effectExile.setActivatingPlayer(ai);
            CardCollection targets = CardLists.getTargetableCards(game.getCardsIn(origin), effectExile);
            if (sourceName.equals("Suspension Field")
                    || sourceName.equals("Detention Sphere")) {
                // existing "exile until leaves" enchantments only target opponent's permanents
                targets = CardLists.filterControlledBy(targets, ai.getOpponents());
            }
            if (targets.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            // EconomyDraft 2026-10-20: the exile trigger targets after the
            // permanent is paid for, so a Ward on the only targets has to come
            // out of what is left. Memory Trap with four lands open took a
            // Wrecking Gecko (Ward {2}) in prod and exiled nothing. Hold the
            // permanent until a target the AI can afford is there.
            final int left = ComputerUtil.manaLeftAfter(ai, sa);
            final CardCollection affordable = CardLists.filter(targets, c -> ComputerUtil.wardPayable(ai, c, left));
            if (affordable.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
            targets = affordable;
            // EconomyDraft 2026-10-17: an "exile until this leaves" permanent
            // is one-shot removal; do not spend it on a 2-drop on turn 3
            // (Memory Trap on Chandra's Emberling in prod logs). Wait for
            // something worth it, less picky as the game goes on.
            CardCollection oppTargets = CardLists.filterControlledBy(targets, ai.getOpponents());
            final Card best = ComputerUtilCard.getBestRemovalTargetAI(ai, oppTargets.isEmpty() ? targets : oppTargets);
            final int minCmc = game.getPhaseHandler().getTurn() <= 8 ? 3 : 2;
            if (best != null && !best.isPlaneswalker() && best.getCMC() < minCmc
                    && !(best.isCreature() && ComputerUtilCard.evaluateCreature(best) >= 180)) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            }
        }
        return decision;
    }
}
