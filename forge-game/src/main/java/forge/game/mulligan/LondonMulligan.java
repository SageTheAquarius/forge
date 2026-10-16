package forge.game.mulligan;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class LondonMulligan extends AbstractMulligan {
    public LondonMulligan(Player p, boolean firstMullFree) {
        super(p, firstMullFree);
    }

    @Override
    public boolean canMulligan() {
        return !kept && tuckCardsDuringMulligan() <= player.getStartingHandSize();
    }

    @Override
    public int handSizeAfterNextMulligan() {
        // CR 103.4: a mulligan redraws the STARTING hand size. Forge used the
        // maximum hand size, which is the same number in every stock format
        // and so never showed - until Lightning Round (5-card opener, maximum
        // hand 6, first mulligan free): every first mulligan drew a free
        // 6-card hand. See forge_bridge/test_lightning_round.py.
        return player.getStartingHandSize();
    }

    @Override
    public void mulliganDraw() {
        player.drawCards(handSizeAfterNextMulligan());
        int tuckingCards = tuckCardsDuringMulligan();
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));

        for (final Card c : player.getController().tuckCardsViaMulligan(hand, tuckingCards)) {
            player.getGame().getAction().moveToLibrary(c, -1, null);
        }
    }

    /**
     * -Dbridge.aifreemulligan=on: the AI's first mulligan is free even when
     * the game's is not (an opponent-cheat on the Arena practice box, where
     * the human plays Arena's rules and the engine is given an edge to make
     * up for how it plays). Off by default: nothing changes without it.
     */
    private static final boolean AI_FREE_MULLIGAN =
            "on".equals(System.getProperty("bridge.aifreemulligan"));

    @Override
    public int tuckCardsDuringMulligan() {
        if (timesMulliganed == 0) {
            return 0;
        }

        boolean free = firstMulliganFree
                || (AI_FREE_MULLIGAN && player.getController() != null && player.getController().isAI());
        int extraCard = free ? 1 : 0;
        return timesMulliganed - extraCard;
    }
}
