package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * EconomyDraft 2026-10-21: redraw the hidden information in a copied game so
 * a lookahead never peeks. The seat's own library is shuffled (it does not
 * know its draws); every other seat's hand goes back into its library, the
 * library is shuffled and the same number of cards is dealt back. Ported
 * from the offline evaluator (forge.sim.Evaluate.determinize), which does
 * the same thing with a seed per arm.
 */
public final class Determinize {
    private Determinize() { }

    public static void apply(final Game game, final Player me, final Random rnd) {
        for (final Player p : game.getPlayers()) {
            if (p == me) {
                shuffleLibrary(p, rnd);
                continue;
            }
            final CardCollection hand = new CardCollection(p.getCardsIn(ZoneType.Hand));
            final int n = hand.size();
            for (final Card c : hand) {
                game.getAction().moveToLibrary(c, null);
            }
            shuffleLibrary(p, rnd);
            final CardCollection lib = new CardCollection(p.getCardsIn(ZoneType.Library));
            for (int i = 0; i < n && i < lib.size(); i++) {
                // moveTo, not drawCards: a redraw is bookkeeping, not a draw,
                // and must not fire "whenever you draw" triggers.
                game.getAction().moveTo(p.getZone(ZoneType.Hand), lib.get(i), null, null);
            }
        }
    }

    private static void shuffleLibrary(final Player p, final Random rnd) {
        // Re-order the zone's own list: Player.shuffle() would fire shuffle
        // triggers and use the game's RNG.
        final CardCollection lib = new CardCollection(p.getCardsIn(ZoneType.Library));
        final List<Card> cards = new ArrayList<>(lib);
        java.util.Collections.shuffle(cards, rnd);
        p.getZone(ZoneType.Library).setCards(cards);
    }
}
