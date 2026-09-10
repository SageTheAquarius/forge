package forge.game.card;

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
        long hits, misses;
    }

    private static final ThreadLocal<Memo> ACTIVE = new ThreadLocal<>();

    /** Kill-switch: -Dbridge.traitmemo=false turns the memo (and the library
     *  skip in ReplacementHandler) off, so one build can run both arms of a
     *  measurement, and production can back out without a rebuild. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("bridge.traitmemo"));

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

    /** Stop remembering on this thread and forget everything. Returns "hits/misses". */
    public static String end() {
        Memo m = ACTIVE.get();
        ACTIVE.remove();
        return m == null ? "" : m.hits + "/" + m.misses;
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
