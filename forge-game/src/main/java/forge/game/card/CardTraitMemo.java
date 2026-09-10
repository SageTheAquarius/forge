package forge.game.card;

import forge.game.Game;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.util.collect.FCollectionView;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
 * The same argument covers the AI's attack and block declarations, which run
 * on the GAME thread with no timeout: nothing mutates the board while the AI
 * decides what attacks, it only fills a Combat object. AiController wraps
 * those two decisions in begin()/end() as well, so a decision nested inside
 * an evaluation (the eval thread predicts combats too) must not reset the
 * outer memo: begin/end are a depth counter, and only the outermost end()
 * forgets.
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
        /** Game -> zone -> every player's cards there; see cardsIn. */
        final Map<Game, Map<forge.game.zone.ZoneType, CardCollectionView>> zoneLists = new IdentityHashMap<>();
        /** CardState -> its derived trigger list; see triggers. */
        final Map<CardState, FCollectionView<Trigger>> triggers = new IdentityHashMap<>();
        /** CardState -> its derived mana / non-mana ability lists; see spellAbilities. */
        final Map<CardState, FCollectionView<SpellAbility>> manaAbilities = new IdentityHashMap<>();
        final Map<CardState, FCollectionView<SpellAbility>> nonManaAbilities = new IdentityHashMap<>();
        /** Trigger -> requirementsCheck(game) for its own game; see requirements. */
        final Map<Trigger, Boolean> requirements = new IdentityHashMap<>();
        int depth;
        long hits, misses, zoneHits, zoneMisses, trigHits, trigMisses;
    }

    private static final ThreadLocal<Memo> ACTIVE = new ThreadLocal<>();

    /** Kill-switch: -Dbridge.traitmemo=false turns the memo (and the library
     *  skip in ReplacementHandler) off, so one build can run both arms of a
     *  measurement, and production can back out without a rebuild. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("bridge.traitmemo"));

    /** Kill-switch for the zone-scan memo alone: -Dbridge.zonememo=false. */
    private static final boolean ZONE_MEMO = !"false".equals(System.getProperty("bridge.zonememo"));

    /** Kill-switch for the trigger-list, ability-list and requirementsCheck
     *  memos alone: -Dbridge.triggermemo=false. */
    private static final boolean TRIGGER_MEMO = !"false".equals(System.getProperty("bridge.triggermemo"));

    private CardTraitMemo() { }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Start remembering on this thread. Pair with {@link #end()} in a finally.
     *  Re-entrant: a nested begin joins the memo already active on the thread. */
    public static void begin() {
        if (!ENABLED) {
            return;
        }
        Memo m = ACTIVE.get();
        if (m == null) {
            m = new Memo();
            ACTIVE.set(m);
        }
        m.depth++;
    }

    /** Stop remembering on this thread. The outermost end() forgets everything
     *  and returns "hits/misses zones=hits/misses triggers=hits/misses"; a
     *  nested end() returns "". */
    public static String end() {
        Memo m = ACTIVE.get();
        if (m == null) {
            return "";
        }
        if (--m.depth > 0) {
            return "";
        }
        ACTIVE.remove();
        return m.hits + "/" + m.misses + " zones=" + m.zoneHits + "/" + m.zoneMisses
                + " triggers=" + m.trigHits + "/" + m.trigMisses;
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
            Supplier<CardCollectionView> build) {
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

    /**
     * Game.getCardsIn(zone) -- every player's cards in one zone -- remembered
     * for one evaluation, as a list that refuses mutation.
     *
     * The second cliff on v124 (Sage's 08:00 pod, turns 34-35 at 119s and
     * 113s engine, 20 timeouts): per candidate spell the AI runs a full
     * block/attack simulation, and every combat trigger's requirement check
     * inside it calls game.getCardsIn(Battlefield), which copies all four
     * battlefields into a fresh set. Same fix as the static-source scan, one
     * level down; see {@link ReadOnlyCardCollection} for why the shared list
     * throws on mutation instead of trusting several hundred callers.
     */
    public static CardCollectionView cardsIn(Game game, forge.game.zone.ZoneType zone,
            Supplier<CardCollectionView> build) {
        Memo m = ACTIVE.get();
        if (m == null || !ZONE_MEMO) {
            return build.get();
        }
        Map<forge.game.zone.ZoneType, CardCollectionView> byZone = m.zoneLists.get(game);
        if (byZone == null) {
            byZone = new java.util.EnumMap<>(forge.game.zone.ZoneType.class);
            m.zoneLists.put(game, byZone);
        }
        CardCollectionView v = byZone.get(zone);
        if (v == null) {
            m.zoneMisses++;
            v = new ReadOnlyCardCollection(build.get());
            byZone.put(zone, v);
        } else {
            m.zoneHits++;
        }
        return v;
    }

    /** A zone changed on this thread: forget every remembered list that can
     *  depend on where a card is (zone lists, ability lists -- an Adventure is
     *  castable only off the battlefield -- and trigger requirements, whose
     *  IsPresent clauses count cards in zones). */
    public static void zonesChanged() {
        Memo m = ACTIVE.get();
        if (m == null) {
            return;
        }
        if (!m.zoneScans.isEmpty()) {
            m.zoneScans.clear();
        }
        if (!m.zoneLists.isEmpty()) {
            m.zoneLists.clear();
        }
        if (!m.requirements.isEmpty()) {
            m.requirements.clear();
        }
        if (!m.manaAbilities.isEmpty()) {
            m.manaAbilities.clear();
        }
        if (!m.nonManaAbilities.isEmpty()) {
            m.nonManaAbilities.clear();
        }
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    static FCollectionView<StaticAbility> statics(CardState s, Supplier<FCollectionView<StaticAbility>> build) {
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
            Supplier<FCollectionView<ReplacementEffect>> build) {
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

    /**
     * CardState.getTriggers() -- the state's own triggers plus every
     * changed-trait layer's and every keyword's -- remembered per decision.
     *
     * Combat prediction (predictPowerBonusOfAttacker and its three siblings)
     * opens by collecting EVERY battlefield and command-zone card's triggers,
     * once per attacker x blocker pair it weighs; on Sage's 08:35 pod (v125,
     * turn 37) three of eight timeout samples were inside that rebuild.
     */
    static FCollectionView<Trigger> triggers(CardState s, Supplier<FCollectionView<Trigger>> build) {
        Memo m = ACTIVE.get();
        if (m == null || !TRIGGER_MEMO) {
            return build.get();
        }
        FCollectionView<Trigger> v = m.triggers.get(s);
        if (v == null) {
            m.trigMisses++;
            v = build.get();
            m.triggers.put(s, v);
        } else {
            m.trigHits++;
        }
        return v;
    }

    /** CardState.getManaAbilities() / getNonManaAbilities(), remembered per
     *  decision for the same reason as {@link #triggers}: canGainKeyword and
     *  predictPowerBonusOfAttacker walk getAllSpellAbilities per pair. */
    static FCollectionView<SpellAbility> spellAbilities(CardState s, boolean mana,
            Supplier<FCollectionView<SpellAbility>> build) {
        Memo m = ACTIVE.get();
        if (m == null || !TRIGGER_MEMO) {
            return build.get();
        }
        Map<CardState, FCollectionView<SpellAbility>> map = mana ? m.manaAbilities : m.nonManaAbilities;
        FCollectionView<SpellAbility> v = map.get(s);
        if (v == null) {
            m.trigMisses++;
            v = build.get();
            map.put(s, v);
        } else {
            m.trigHits++;
        }
        return v;
    }

    /**
     * Trigger.requirementsCheck(game) for the trigger's own game, remembered
     * per decision.
     *
     * combatTriggerWillTrigger asks it for every trigger at the table, per
     * attacker x blocker pair, and the answer walks the players' zones
     * (IsPresent copies whole battlefields through getValidCards). Everything
     * it reads -- life, hand sizes, phase, zones, resolved counts -- is frozen
     * while the AI decides; a zone change on this thread forgets the answers
     * ({@link #zonesChanged()}).
     */
    public static boolean requirements(Trigger t, BooleanSupplier compute) {
        Memo m = ACTIVE.get();
        if (m == null || !TRIGGER_MEMO) {
            return compute.getAsBoolean();
        }
        Boolean v = m.requirements.get(t);
        if (v == null) {
            m.trigMisses++;
            v = compute.getAsBoolean();
            m.requirements.put(t, v);
        } else {
            m.trigHits++;
        }
        return v;
    }
}
