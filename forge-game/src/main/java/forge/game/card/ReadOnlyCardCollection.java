package forge.game.card;

import java.util.Collection;
import java.util.Comparator;

/**
 * A {@link CardCollection} that refuses every mutation once built.
 *
 * {@link CardTraitMemo} hands one remembered zone list to every caller of
 * Game.getCardsIn(zone) for the length of an AI evaluation. Those callers
 * used to get a fresh copy each, and a handful of them mutate what they get
 * (an effect that removes cards from a sideboard list, say). On the AI eval
 * thread a silent mutation of the shared list would corrupt every later
 * answer in that evaluation, so the shared list throws instead: the eval
 * fails loudly, the AI passes that window, and the stack trace names the
 * caller to fix. The game thread never sees one of these.
 *
 * (EconomyDraft bridge patch. Mirrored in forge_bridge/forge_java_src.)
 */
public final class ReadOnlyCardCollection extends CardCollection {
    private static final long serialVersionUID = 1L;

    private final boolean frozen;

    public ReadOnlyCardCollection(final Iterable<Card> cards) {
        super(cards);          // fills through addAll while frozen is still false
        frozen = true;
    }

    private void check() {
        if (frozen) {
            throw new UnsupportedOperationException(
                    "read-only zone list remembered for this AI evaluation (CardTraitMemo)");
        }
    }

    @Override public boolean add(final Card e) { check(); return super.add(e); }
    @Override public void add(final int index, final Card element) { check(); super.add(index, element); }
    @Override public boolean addAll(final Collection<? extends Card> c) { check(); return super.addAll(c); }
    @Override public boolean addAll(final Iterable<? extends Card> i) { check(); return super.addAll(i); }
    @Override public boolean addAll(final Card[] c) { check(); return super.addAll(c); }
    @Override public boolean addAll(final int index, final Collection<? extends Card> c) { check(); return super.addAll(index, c); }
    @Override public boolean remove(final Object o) { check(); return super.remove(o); }
    @Override public Card remove(final int index) { check(); return super.remove(index); }
    @Override public boolean removeAll(final Collection<?> c) { check(); return super.removeAll(c); }
    @Override public boolean removeAll(final Iterable<?> c) { check(); return super.removeAll(c); }
    @Override public boolean retainAll(final Collection<?> c) { check(); return super.retainAll(c); }
    @Override public void clear() { check(); super.clear(); }
    @Override public Card set(final int index, final Card element) { check(); return super.set(index, element); }
    @Override public void sort() { check(); super.sort(); }
    @Override public void sort(final Comparator<? super Card> comparator) { check(); super.sort(comparator); }
}
