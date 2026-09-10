package forge.game.staticability;

import forge.game.card.Card;

public class StaticAbilityCantCrew {

    public static boolean cantCrew(final Card card) {
        for (final Card ca : card.getGame().getStaticSourcesAnd(card)) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.CantCrew)) {
                    continue;
                }
                if (applyCantCrew(stAb, card)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean applyCantCrew(final StaticAbility stAb, final Card card) {
        if (!stAb.matchesValidParam("ValidCard", card)) {
            return false;
        }
        return true;
    }

}
