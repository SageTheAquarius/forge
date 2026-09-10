package forge.game.card;

import forge.game.Game;
import forge.game.replacement.ReplacementEffect;
import forge.game.staticability.StaticAbility;
import forge.util.collect.FCollectionView;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-thread memo of the trait lists a card derives on every call.
 *
 * CardState.getStaticAbilities() and getReplacementEffects() rebuild a fresh
 * collection each time: the state's own list, plus every changed-card-traits
 * layer, plus every keyword's granted abilities. They are cheap once and
 * ruinous in a loop, and the AI runs them in loops: "is this combat damage
 * prevented?" walks EVERY card in the game (libraries included) rebuilding
 * replacement effects, once per attacker/blocker pair, per candidate spell,
 * per decision; every targeting check rebuilds statics for hexproof/shroud.
 * On a four-seat late-game board that is how a 2-second AI budget expires
 * dozens of times a turn.
 *
 * While the AI evaluates, the game is frozen: the game thread is blocked on
 * the eval future and nothing mutates a card. So on that thread, and only
 * there, the lists can be remembered for the length of the evaluation. The
 * memo is thread-local and off by default, so the game thread, triggers,
 * and every other caller keep the exact old behaviour; the AI switches it on
 * at the start of an evaluation and off in a finally.
 *
 * (EconomyDraft bridge patch. Mirrored in forge_bridge/forge_java_src.)
 */
public final class CardTraitMemo {

    private static final class Memo {
        final Map<CardState, FCollectionView<StaticAbility>> statics = new IdentityHashMap<>();
        final Map<CardState, FCollectionView<ReplacementEffect>> replacements = new IdentityHashMap<>();
        final Map<CardState, FCollectionView<ReplacementEffect>> replacementsNoRules = new IdentityHashMap<>();
        /** Game -> its STATIC_ABILITIES_SOURCE_ZONES scan; see staticSourceCards. */
        final Map<Game, CardCollectionView> zoneScans = new IdentityHashMap<>();
        long hits, misses, zoneHits, zoneMisses;
    }

    private static final ThreadLocal<Memo> ACTIVE = new ThreadLocal<>();

    /** Kill-switch: -Dbridge.traitmemo=false turns the memo (and the library
     *  skip in ReplacementHandler) off, so one build can run both arms of a
     *  measurement, and production can back out without a rebuild. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("bridge.traitmemo"));

    /** Kill-switch for the zone-scan memo alone: -Dbridge.zonememo=false. */
    private static final boolean ZONE_MEMO = !"false".equals(System.getProperty("bridge.zonememo"));

    private CardTraitMemo() { }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Start remembering on this thread. Pair with {@link #end()} in a finally. */
    public static void begin() {
        if (ENABLED) {
            ACTIVE.set(new Memo());
        }
    }

    /** Stop remembering on this thread and forget everything. Returns "hits/misses zones=hits/misses". */
    public static String end() {
        Memo m = ACTIVE.get();
        ACTIVE.remove();
        return m == null ? "" : m.hits + "/" + m.misses + " zones=" + m.zoneHits + "/" + m.zoneMisses;
    }

    /**
     * The "every card that can carry a static ability" scan --
     * Game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES) -- remembered
     * for one evaluation.
     *
     * Eighty-odd static-ability checks open with that scan, and each call
     * copies every battlefield, graveyard, exile, command zone and stack at
     * the table into a fresh hash set. Combat prediction asks "can this
     * block?", "is this damage prevented?", "does toughness assign damage?"
     * per attacker x blocker, per candidate spell, so on a four-seat
     * late-game board that is 300+ cards hashed thousands of times per
     * decision. All eight timeout samples from the 2026-09-10 pod (turns
     * 48-49: 122s and 110s of engine time, the 2s budget blown five and three
     * times) sat in FCollection.add under one of those helpers.
     *
     * Keyed by Game so a simulated copy never sees the real game's list. Any
     * zone mutation on this thread forgets the memo (Zone.add / remove /
     * setCards / removeAllCards call {@link #zonesChanged()}), so an AI path
     * that moves cards in a copied game stays correct. The game thread is
     * blocked while the eval runs, so its mutations cannot race the memo. The
     * remembered collection is a snapshot handed to every caller; the 80+
     * callers of that constant all read it (loops, addAll, copies) -- none
     * mutates it.
     */
    public static CardCollectionView staticSourceCards(Game game,
            java.util.function.Supplier<CardCollectionView> build) {
        Memo m = ACTIVE.get();
        if (m == null || !ZONE_MEMO) {
            return build.get();
        }
        CardCollectionView v = m.zoneScans.get(game);
        if (v == null) {
            m.zoneMisses++;
            v = build.get();
            m.zoneScans.put(game, v);
        } else {
            m.zoneHits++;
        }
        return v;
    }

    /** A zone changed on this thread: forget every remembered zone scan. */
    public static void zonesChanged() {
        Memo m = ACTIVE.get();
        if (m != null && !m.zoneScans.isEmpty()) {
            m.zoneScans.clear();
        }
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    static FCollectionView<StaticAbility> statics(CardState s, java.util.function.Supplier<FCollectionView<StaticAbility>> build) {
        Memo m = ACTIVE.get();
        if (m == null) {
            return build.get();
        }
        FCollectionView<StaticAbility> v = m.statics.get(s);
        if (v == null) {
            m.misses++;
            v = build.get();
            m.statics.put(s, v);
        } else {
            m.hits++;
        }
        return v;
    }

    static FCollectionView<ReplacementEffect> replacements(CardState s, boolean rulesHost,
            java.util.function.Supplier<FCollectionView<ReplacementEffect>> build) {
        Memo m = ACTIVE.get();
        if (m == null) {
            return build.get();
        }
        Map<CardState, FCollectionView<ReplacementEffect>> map = rulesHost ? m.replacements : m.replacementsNoRules;
        FCollectionView<ReplacementEffect> v = map.get(s);
        if (v == null) {
            m.misses++;
            v = build.get();
            map.put(s, v);
        } else {
            m.hits++;
        }
        return v;
    }
}
