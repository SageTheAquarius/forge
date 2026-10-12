package forge.game.trigger;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.Localizer;

public class TriggerSacrificedOnce extends Trigger {

    public TriggerSacrificedOnce(Map<String, String> params, Card host, boolean intrinsic) {
        super(params, host, intrinsic);
    }

    @Override
    public boolean performTest(Map<AbilityKey, Object> runParams) {
        if (!matchesValidParam("ValidPlayer", runParams.get(AbilityKey.Player))) {
            return false;
        }
        if (!matchesValidParam("ValidCause", runParams.get(AbilityKey.Cause))) {
            return false;
        }
        if (!matchesValidParam("ValidCard", runParams.get(AbilityKey.Cards))) {
            return false;
        }
        if (hasParam("FirstTime")) {
            // "Whenever you sacrifice a permanent for the first time each
            // turn": this sacrifice is the turn's first matching one. The
            // player's sacrificed-this-turn list already holds this event's
            // cards (GameAction.sacrifice adds each before firing), so the
            // event is the first when nothing matching precedes it. Counted,
            // not removed by identity: a card sacrificed, returned and
            // sacrificed again this turn is two LKI copies with one id.
            final Player p = (Player) runParams.get(AbilityKey.Player);
            final CardCollection now = (CardCollection) runParams.get(AbilityKey.Cards);
            int turnTotal = p.getSacrificedThisTurn().size();
            int thisEvent = now.size();
            if (hasParam("ValidCard")) {
                turnTotal = CardLists.getValidCardsAsList(p.getSacrificedThisTurn(), getParam("ValidCard"), getHostCard().getController(), getHostCard(), this).size();
                thisEvent = CardLists.getValidCardsAsList(now, getParam("ValidCard"), getHostCard().getController(), getHostCard(), this).size();
            }
            if (turnTotal > thisEvent) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setTriggeringObjects(SpellAbility sa, Map<AbilityKey, Object> runParams) {
        CardCollection cards = (CardCollection) runParams.get(AbilityKey.Cards);

        if (hasParam("ValidCard")) {
            cards = CardLists.getValidCards(cards, getParam("ValidCard"), getHostCard().getController(), getHostCard(), this);
        }

        sa.setTriggeringObject(AbilityKey.Cards, cards);
        sa.setTriggeringObject(AbilityKey.Amount, cards.size());
        sa.setTriggeringObjectsFrom(runParams, AbilityKey.Player, AbilityKey.Cause);
    }

    @Override
    public String getImportantStackObjects(SpellAbility sa) {
        StringBuilder sb = new StringBuilder();
        sb.append(Localizer.getInstance().getMessage("lblPlayer")).append(": ").append(sa.getTriggeringObject(AbilityKey.Player)).append(", ");
        sb.append(Localizer.getInstance().getMessage("lblAmount")).append(": ").append(sa.getTriggeringObject(AbilityKey.Amount));
        return sb.toString();
    }

}
