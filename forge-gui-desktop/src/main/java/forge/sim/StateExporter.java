package forge.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import com.google.common.collect.Multiset;

import forge.ImageKeys;
import forge.StaticData;
import forge.ai.ComputerUtilMana;
import forge.card.CardEdition;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.keyword.KeywordView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostRemoveCounter;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;

/**
 * Serializes a Forge GameView into a compact, NEUTRAL JSON game state.
 *
 * This is the keystone of the "your Godot UI on top of Forge" bridge: Forge owns
 * the rules; this turns its live game state into JSON that the Python translator
 * maps onto EconomyDraft's exact `_serialize_match_state` wire schema. Kept
 * deliberately lean — schema fidelity (the ~40 per-card fields, phase-name
 * mapping, is_you) lives in Python where the reference serializer lives.
 *
 * Hand-rolled JSON (no dependency assumptions on the test classpath).
 */
public final class StateExporter {
    private StateExporter() {}

    // Ability flags for the human's battlefield cards (id -> has mana / activated
    // ability), so the client can offer "Tap for Mana" / "Activate Ability"
    // correctly. Single game at a time, so static is fine.
    private static Set<Integer> manaCards = Collections.emptySet();
    private static Set<Integer> activatedCards = Collections.emptySet();

    // Cycling / typecycling cost per hand card id. Cycling is an activated
    // ability whose ActivationZone is Hand, so the battlefield scan above never
    // sees it and the client had no way to offer it. The client renders one
    // generic "Cycle (<cost>)" item from this field, exactly as it does for the
    // native engine (which also folds landcycling into `cycling_cost`).
    private static Map<Integer, String> cyclingCosts = Collections.emptyMap();

    // Cards OUTSIDE hand and battlefield that the human can play right now.
    // The impulse-draw shape ("exile the top card of your library; until the end
    // of your next turn you may play that card" -- Kulrath Zealot, Sizzling
    // Changeling and ~30 others in the pool) puts a castable card in Exile, and
    // flashback/disturb put one in the graveyard. Forge already knows: the card
    // has a playable SpellAbility while the MayPlay effect lasts. Without this
    // flag the client had nothing to hang a menu item on, so those cards sat in
    // the exile viewer with no way to cast them and the effect read as broken.
    private static Set<Integer> playableElsewhere = Collections.emptySet();

    // Zones scanned for the flag above. Hand and battlefield already have their
    // own paths (every hand card is offered; battlefield uses the ability flags).
    // Library is handled separately -- see libraryTop: only its TOP card can be
    // playable in practice, and scanning a 40-card library every push would cost
    // far more than it is worth.
    private static final ZoneType[] PLAY_FROM_ZONES = {
        ZoneType.Exile, ZoneType.Graveyard, ZoneType.Command,
    };

    // The top card of the human's library, exported ONLY while something lets
    // them look at it ("You may look at the top card of your library any time"
    // -- Mm'menon, the Right Hand, which then also lets you cast artifacts from
    // there). The library is hidden information and the client renders whatever
    // it is handed, so this stays null unless Forge says the look is legal.
    private static CardView libraryTop = null;
    // Whose export this is. A face-down card's real name is shown only to
    // the seats Forge says may see it (its controller, or everyone once a
    // look-at effect has revealed it); every other seat gets the blank
    // 2/2 face Forge already exports.
    private static PlayerView viewer = null;
    private static int libraryTopOwner = -1;

    /** PlayerView id -> that player's Start Your Engines speed (0 = not started).
     *
     * PlayerView does not carry speed, so it is read off the real Player objects
     * once per export and looked up while serialising each seat. Without it the
     * mechanic is invisible: Forge tracks the 1-4 ratchet and fires an event for
     * a SOUND, but nothing reaches the game log, so a player had no way to see
     * their own speed or know they had hit max. */
    private static Map<Integer, Integer> speedById = Collections.emptyMap();

    /**
     * Per-card list of the ways this player could use that card right now,
     * pre-rendered as a JSON array and keyed by card id.
     *
     * Forge is the only thing that knows how many ways a card can be played -- a
     * planeswalker's three loyalty abilities, an Adventure creature's two
     * halves, a prepared spell, a creature that can also be cycled. The client
     * used to get two booleans (has_mana_ability / has_activated_abilities), so
     * it offered one generic "Activate Ability" and the bridge took the FIRST
     * match: only loyalty ability #1 of any walker was ever reachable, and an
     * Adventure always cast its creature half.
     *
     * The list index is the client's handle. It comes back as {"ability":N} and
     * PlayerControllerBridge re-derives the same getAllPossibleAbilities() list
     * to resolve it -- safe because the game is parked waiting for this reply,
     * so nothing between the push and the answer can reorder it. Ids are NOT
     * usable here: getAllPossibleAbilities copies SpellAbilities for alternative
     * and additional costs, so a fresh id is minted on every call.
     */
    private static Map<Integer, String> abilityLists = Collections.emptyMap();

    /**
     * Memo for getAllPossibleAbilities, invalidated by the game's own timestamp.
     *
     * Profiled (JFR, 4685 samples on the real turn-40 board): Card.isValid is
     * 57% of all gameplay CPU, and 80% of that sits under
     * GameActionUtil.getAlternativeCosts -> GameAction.checkStaticAbilities --
     * a full static-abilities pass run PER CARD to work out that card's
     * characteristics in its alternate state (CR 601.3e). Two callers in this
     * bridge each walk the same five zones and pay it independently:
     *
     *     StateExporter.putAbilities          33.5% of isValid samples
     *     PlayerControllerBridge.canPlaySomething   8.6%
     *
     * hasLegalPlay only wants a boolean and the exporter wants the list, but
     * both ask the identical question about the identical cards, back to back,
     * while the game is parked waiting on this player. So the second walk is
     * pure duplicate work.
     *
     * Keyed on the game timestamp AND the timing state the playability filter
     * reads -- see the comment on the stamp below, and note that the first
     * version of this keyed on the timestamp alone and broke equip. No
     * call-site plumbing and nothing to remember to invalidate. The cached
     * lists are handed to readers only; the reply path still re-derives the
     * list from Forge when it resolves an ability index, so the index contract
     * in abilityLists is untouched.
     */
    private static long abilityMemoStamp = Long.MIN_VALUE;
    private static final Map<Long, List<SpellAbility>> abilityMemo = new HashMap<>();

    /** -Dbridge.nomemo=1 bypasses the memo, for A/B measurement only. */
    private static final boolean MEMO_OFF = System.getProperty("bridge.nomemo") != null;

    /** getAllPossibleAbilities(player, true), memoised for this game state. */
    static List<SpellAbility> possibleAbilities(Card c, Player p) {
        if (MEMO_OFF) {
            return c == null ? Collections.emptyList()
                             : c.getAllPossibleAbilities(p, true);
        }
        if (c == null || p == null || c.getGame() == null) {
            return c == null ? Collections.emptyList()
                             : c.getAllPossibleAbilities(p, true);
        }
        // The stamp is NOT just the game timestamp. possibleAbilities passes
        // removeUnplayable=true, so the list is filtered by what is legal RIGHT
        // NOW -- and that is timing-dependent, not only state-dependent.
        //
        // test_scenario_equip_timing caught this: equip is sorcery-speed, so
        // Forge offers "Equip {1}" in a main phase and nothing in combat. With
        // only the game timestamp in the key, the combat-phase push was served
        // the main-phase list and the Equipment looked activatable when it was
        // not. Exactly the bug this bridge had just finished fixing from the
        // other direction.
        //
        // So fold in everything the playability filter reads: the phase, the
        // stack (an empty stack is part of sorcery speed), and canCastSorcery
        // itself, which also covers whose turn it is.
        // Null-safe throughout: this runs during the MULLIGAN too, where there
        // is no phase yet. An unguarded getPhase().ordinal() took out
        // test_abandon_releases_engine, test_abandon_pod_releases_engine and
        // test_mulligan_free together -- all three sit at that moment.
        Game game = c.getGame();
        long stamp;
        try {
            stamp = game.getTimestamp() * 1000003L;
            if (game.getPhaseHandler() != null
                    && game.getPhaseHandler().getPhase() != null) {
                stamp += game.getPhaseHandler().getPhase().ordinal() * 37L;
            }
            if (game.getStack() != null) {
                stamp += game.getStack().size() * 7L;
            }
            stamp += p.canCastSorcery() ? 1L : 0L;
        } catch (Exception e) {
            return c.getAllPossibleAbilities(p, true);   // never cache blind
        }
        if (stamp != abilityMemoStamp) {
            abilityMemo.clear();
            abilityMemoStamp = stamp;
        }
        // Layer timestamp too: a card whose characteristics were re-applied
        // within one game timestamp must not be served from before that.
        long key = (((long) c.getId()) << 40)
                 ^ (((long) p.getId()) << 32)
                 ^ c.getLayerTimestamp();
        List<SpellAbility> hit = abilityMemo.get(key);
        if (hit != null) {
            return hit;
        }
        List<SpellAbility> built = c.getAllPossibleAbilities(p, true);
        abilityMemo.put(key, built);
        return built;
    }

    /** Zones whose cards get an ability list. Library is handled with libraryTop. */
    private static final ZoneType[] ABILITY_ZONES = {
        ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard,
        ZoneType.Exile, ZoneType.Command,
    };

    /**
     * Blocker card id -> the name(s) of what it blocks, from the live combat.
     *
     * The translator used to hardcode is_blocking/blocking, and the relay could
     * only echo the human's own *pending* picks while the declare-blockers
     * window was open. So the AI's blocks never rendered at all, and the
     * player's own vanished from the board the moment the window closed --
     * combat damage resolved against a board that showed no blocks.
     */
    private static Map<Integer, String> blockingOf = Collections.emptyMap();

    /**
     * Attacker card id -> a JSON object naming what it attacks:
     * {"target", "kind" (player|planeswalker|battle), "defender", "target_id"}.
     *
     * `attacking` alone is a bool, and in a pod that is not enough: the client
     * could not tell an attack on YOU from an attack on the seat next to you,
     * so the combat lane (attackers-at-you with live damage math) had nothing
     * to filter on. `defender` is the player who blocks for the target -- the
     * planeswalker's controller, or the battle's protector -- which is the
     * seat that needs to see the chip.
     */
    private static Map<Integer, String> defenderOf = Collections.emptyMap();

    /**
     * Blocker card id -> JSON array of the attacker ids it blocks. `blocking`
     * carries NAMES, and five Goblin tokens share one; ids are what the lane
     * needs to hang a blocker under the right chip.
     */
    private static Map<Integer, String> blockingIdsOf = Collections.emptyMap();

    /**
     * Game-log entry types worth showing in the client's Feed: Forge's MEDIUM
     * verbosity (turns, lands, spells, combat, damage, life, mulligans, deaths)
     * minus PHASE/MANA, which would flood the panel with lines the board already
     * shows. GAME_OUTCOME / MATCH_RESULTS are kept so the player sees who won.
     *
     * INFORMATION carries the results of choices that happen off-board and
     * leave no trace on it: coin flips, dice rolls, clash, votes, and the
     * colour/number/type a card told someone to pick. Forge does not log those
     * itself -- it hands them to PlayerController.notifyOfValue, which the
     * desktop client shows in a modal -- so PlayerControllerBridge turns them
     * into INFORMATION entries instead. Leaving the type out of this set made
     * that whole path dead: a card that said "flip a coin" resolved its win or
     * lose branch with nothing in the Feed to say which one happened.
     *
     * PLAYER_CONTROL is a Mindslaver-shaped effect taking over a seat. Rare,
     * but if somebody else is making your plays the Feed had better say so.
     *
     * Still excluded, deliberately: PHASE and MANA (one line per step and per
     * mana ability -- the board already shows both), and ANTE, which cannot
     * happen in the formats this serves.
     */
    private static final Set<GameLogEntryType> FEED_TYPES = EnumSet.of(
            GameLogEntryType.TURN, GameLogEntryType.MULLIGAN, GameLogEntryType.LAND,
            GameLogEntryType.STACK_ADD, GameLogEntryType.STACK_RESOLVE,
            GameLogEntryType.COMBAT, GameLogEntryType.DAMAGE, GameLogEntryType.LIFE,
            GameLogEntryType.DISCARD, GameLogEntryType.ZONE_CHANGE,
            GameLogEntryType.EFFECT_REPLACED, GameLogEntryType.INFORMATION,
            GameLogEntryType.PLAYER_CONTROL,
            GameLogEntryType.GAME_OUTCOME, GameLogEntryType.MATCH_RESULTS);

    /**
     * How many trailing log entries each export carries. Entries are sent with
     * their ABSOLUTE index in Forge's log, so the bridge simply drops anything it
     * has already forwarded — no state kept here, and a re-sent tail is harmless.
     */
    private static final int LOG_TAIL = 400;

    public static String toJson(GameView g) {
        return toJson(g, null);
    }

    public static String toJson(GameView g, Player human) {
        // Timed into AiPerf so the relay's pace line can show what every push
        // costs on the game thread (the human's ability export is the same
        // getAllPossibleAbilities walk the AI's candidate build pays for).
        final long t0 = System.currentTimeMillis();
        forge.ai.AiPerf.exportN.increment();
        try {
            return toJsonNow(g, human);
        } finally {
            forge.ai.AiPerf.exportMs.add(System.currentTimeMillis() - t0);
        }
    }

    private static String toJsonNow(GameView g, Player human) {
        Set<Integer> mana = new HashSet<>();
        Set<Integer> activated = new HashSet<>();
        Map<Integer, String> cycling = new HashMap<>();
        Set<Integer> elsewhere = new HashSet<>();
        Map<Integer, String> abilities = new HashMap<>();
        // One export, one affordability answer per (card name, cost). See unpayableReason.
        Map<String, String> payMemo = new HashMap<>();
        CardView top = null;
        if (human != null) {
            for (Card c : human.getCardsIn(ZoneType.Battlefield)) {
                if (!c.getManaAbilities().isEmpty()) {
                    mana.add(c.getId());
                }
                for (SpellAbility sa : c.getNonManaAbilities()) {
                    if (sa.isActivatedAbility()) {
                        activated.add(c.getId());
                        break;
                    }
                }
            }
            for (Card c : human.getCardsIn(ZoneType.Hand)) {
                String cost = cyclingCost(c);
                if (cost != null) {
                    cycling.put(c.getId(), cost);
                }
            }
            // getAllPossibleAbilities(..., true) drops anything that can't be
            // played from where the card currently is, so an exiled card only
            // shows up while its MayPlay grant is live and the timing is legal.
            for (ZoneType zt : PLAY_FROM_ZONES) {
                for (Card c : human.getCardsIn(zt)) {
                    for (SpellAbility sa : c.getAllPossibleAbilities(human, true)) {
                        if (!sa.isManaAbility()) {
                            elsewhere.add(c.getId());
                            break;
                        }
                    }
                }
            }
            for (Card c : human.getCardsIn(ZoneType.Library, 1)) {
                for (SpellAbility sa : c.getAllPossibleAbilities(human, true)) {
                    if (!sa.isManaAbility()) {
                        elsewhere.add(c.getId());
                        break;
                    }
                }
                if (c.mayPlayerLook(human)) {
                    top = c.getView();
                    putAbilities(abilities, c, human, payMemo);
                }
            }
            for (ZoneType zt : ABILITY_ZONES) {
                for (Card c : human.getCardsIn(zt)) {
                    putAbilities(abilities, c, human, payMemo);
                }
            }
        }
        manaCards = mana;
        activatedCards = activated;
        cyclingCosts = cycling;
        playableElsewhere = elsewhere;
        abilityLists = abilities;
        blockingOf = blockAssignments(g);
        defenderOf = attackTargets(g);
        blockingIdsOf = blockIds(g);
        libraryTop = top;
        viewer = human != null ? human.getView() : null;
        libraryTopOwner = human != null && human.getView() != null ? human.getView().getId() : -1;
        Map<Integer, Integer> speeds = new HashMap<>();
        if (human != null && human.getGame() != null) {
            for (Player pl : human.getGame().getPlayers()) {
                if (pl != null && pl.getView() != null) {
                    speeds.put(pl.getView().getId(), pl.getSpeed());
                }
            }
        }
        speedById = speeds;

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        kv(sb, "turn", g.getTurn()); sb.append(',');
        kvs(sb, "phase", String.valueOf(g.getPhase())); sb.append(',');
        kvs(sb, "turn_player", g.getPlayerTurn() != null ? g.getPlayerTurn().getName() : ""); sb.append(',');
        // Day/night. GameView does not carry it: Forge keeps `daytime` on Game as a
        // nullable tri-state (null = neither, false = day, true = night) and never
        // mirrors it into the view layer. Reading it off the human Player's Game keeps
        // us out of Forge's trackable sync -- adding a TrackableProperty would mean
        // editing two more engine files for one string.
        //
        // Empty means "neither day nor night", which is every game with no daybound
        // permanent and every game before the first one enters. The client draws
        // nothing at all in that case, so untouched games look exactly as they did.
        kvs(sb, "daytime", dayTime(human)); sb.append(',');
        // AI pace counters, cumulative for this game (forge.ai.AiPerf). The relay
        // prints the per-turn deltas next to its pace line.
        sb.append("\"perf\":").append(forge.ai.AiPerf.json()).append(',');

        sb.append("\"players\":[");
        boolean firstP = true;
        for (PlayerView p : g.getPlayers()) {
            if (!firstP) sb.append(','); firstP = false;
            player(sb, p, g);
        }
        sb.append("],");

        sb.append("\"stack\":[");
        if (g.getStack() != null) {
            boolean firstS = true;
            for (StackItemView si : g.getStack()) {
                if (!firstS) sb.append(','); firstS = false;
                sb.append('{');
                String nm = si.getSourceCard() != null ? si.getSourceCard().getName() : si.getKey();
                kvs(sb, "name", nm != null ? nm : ""); sb.append(',');
                kvs(sb, "controller", si.getActivatingPlayer() != null ? si.getActivatingPlayer().getName() : ""); sb.append(',');
                kvs(sb, "type", si.isAbility() ? "ability" : "spell");
                sb.append('}');
            }
        }
        sb.append("],");

        gameLog(sb, g);
        sb.append('}');
        return sb.toString();
    }

    /**
     * The tail of Forge's game log as {"i":<absolute index>,"type":..,"text":..}.
     * This is what feeds the client's Feed panel: Forge already writes a human
     * readable line for every turn, land, spell, attack, damage and death, so the
     * bridge just forwards them instead of re-deriving events from state diffs.
     */
    private static void gameLog(StringBuilder sb, GameView g) {
        sb.append("\"log\":[");
        GameLog log = g.getGameLog();
        List<GameLogEntry> all = log != null ? log.getAllEntries() : Collections.emptyList();
        boolean first = true;
        for (int i = Math.max(0, all.size() - LOG_TAIL); i < all.size(); i++) {
            GameLogEntry e = all.get(i);
            if (e == null || e.type() == null || !FEED_TYPES.contains(e.type())) {
                continue;
            }
            String msg = e.message();
            if (msg == null || msg.isEmpty()) {
                continue;
            }
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"i\":").append(i).append(',');
            kvs(sb, "type", e.type().name()); sb.append(',');
            kvs(sb, "text", msg);
            sb.append('}');
        }
        sb.append(']');
    }

    private static void player(StringBuilder sb, PlayerView p, GameView g) {
        sb.append('{');
        kvs(sb, "name", p.getName()); sb.append(',');
        kv(sb, "life", p.getLife()); sb.append(',');
        // Eliminated. The wire translator and the client have read `has_lost`
        // all along -- the pod bar greys the seat out, focus skips it, and the
        // attack chooser refuses to offer it as a target -- but nothing ever
        // emitted it, so it was permanently false. A pod that lost two seats
        // mid-game still showed four live opponents with their last life total,
        // and offered dead players to attack.
        sb.append("\"has_lost\":").append(p.getHasLost()); sb.append(',');
        // Start Your Engines: 0 = engine never started, 1-4 once it has, 4 = max
        // speed. Exported for every seat so the client can show it and the
        // bridge can announce a change.
        kv(sb, "speed", speedById.getOrDefault(p.getId(), 0)); sb.append(',');
        kv(sb, "library_count", count(p.getCards(ZoneType.Library))); sb.append(',');
        // Cards drawn all game. The stats recorder used to infer this from
        // hand-size increases, which cannot see a card drawn and spent inside the
        // same priority window; this is Forge's own count, taken at the one place
        // a draw happens. Excludes the opening hand.
        kv(sb, "cards_drawn", p.getNumDrawnThisGame()); sb.append(',');
        // Poison, energy, experience, rad -- see kvPlayerCounters. Poison is a
        // LOSS CONDITION and was invisible: nothing exported it and nothing
        // logged it, so ten counters could arrive without a number or a Feed
        // line anywhere along the way.
        kvPlayerCounters(sb, "counters", p); sb.append(',');
        // mana pool by color symbol
        sb.append("\"mana_pool\":{");
        sb.append("\"W\":").append(p.getMana(MagicColor.WHITE)).append(',');
        sb.append("\"U\":").append(p.getMana(MagicColor.BLUE)).append(',');
        sb.append("\"B\":").append(p.getMana(MagicColor.BLACK)).append(',');
        sb.append("\"R\":").append(p.getMana(MagicColor.RED)).append(',');
        sb.append("\"G\":").append(p.getMana(MagicColor.GREEN)).append(',');
        sb.append("\"C\":").append(p.getMana(MagicColor.COLORLESS));
        sb.append("},");
        zone(sb, "hand", p.getHand()); sb.append(',');
        zone(sb, "battlefield", p.getCards(ZoneType.Battlefield)); sb.append(',');
        zone(sb, "graveyard", p.getCards(ZoneType.Graveyard)); sb.append(',');
        zone(sb, "exile", p.getCards(ZoneType.Exile)); sb.append(',');
        // Commander. Empty/zero outside a Commander game, so the client can read
        // these unconditionally. The command zone is already in PLAY_FROM_ZONES
        // and ABILITY_ZONES, so a commander sitting there is already marked
        // playable and already carries its ability list - it was simply in no
        // exported zone for the client to draw.
        zone(sb, "command_zone", p.getCards(ZoneType.Command)); sb.append(',');
        commanderDamage(sb, p, g); sb.append(',');
        // {2} per previous cast of that commander (CR 903.8). Reported as the
        // tax itself, not the cast count, so the client can show it verbatim.
        int tax = 0;
        try {
            for (CardView c : p.getCommanders()) {
                tax = Math.max(tax, 2 * p.getCommanderCast(c));
            }
        } catch (Exception ignore) { }
        kv(sb, "commander_tax", tax); sb.append(',');
        sb.append("\"commander_ids\":[");
        try {
            boolean firstId = true;
            for (CardView c : p.getCommanders()) {
                if (!firstId) sb.append(',');
                firstId = false;
                sb.append(c.getId());
            }
        } catch (Exception ignore) { }
        sb.append("],");
        // null for everyone but the human, and for the human too unless an
        // effect currently lets them look at their own top card.
        sb.append("\"library_top\":");
        if (libraryTop != null && p.getId() == libraryTopOwner) {
            card(sb, libraryTop);
        } else {
            sb.append("null");
        }
        sb.append('}');
    }

    /**
     * Damage this player has taken from each commander at the table, keyed by
     * commander name (CR 903.10a - 21 from a single commander is lethal, and it
     * is tracked per commander, not summed).
     */
    private static void commanderDamage(StringBuilder sb, PlayerView p, GameView g) {
        sb.append("\"commander_damage\":{");
        boolean first = true;
        try {
            for (PlayerView other : g.getPlayers()) {
                for (CardView c : other.getCommanders()) {
                    int dmg = p.getCommanderDamage(c);
                    if (dmg <= 0) {
                        continue;
                    }
                    if (!first) sb.append(',');
                    first = false;
                    // The key is a commander name, so it needs escaping; the
                    // value is a count, so it must not be quoted.
                    sb.append('"').append(esc(c.getName())).append("\":").append(dmg);
                }
            }
        } catch (Exception ignore) { }
        sb.append('}');
    }

    private static void zone(StringBuilder sb, String key, Iterable<CardView> cards) {
        sb.append('"').append(key).append("\":[");
        if (cards != null) {
            boolean first = true;
            for (CardView c : cards) {
                if (!first) sb.append(','); first = false;
                card(sb, c);
            }
        }
        sb.append(']');
    }

    /**
     * The mana part of this card's cycling / typecycling cost, or null if it has
     * none. Forge builds both keywords as an activated ability (CardFactoryUtil:
     * "AB$ Draw|Discard<1/CARDNAME>" for Cycling, "AB$ ChangeZone" for
     * TypeCycling), so `isCycling()` covers plain Cycling, Basic landcycling and
     * every Xcycling variant with one check. The discard half of the cost is
     * implicit in the client's label, so only the mana is exported.
     */
    private static String cyclingCost(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isCycling()) {
                String mc = sa.getPayCosts() != null
                        ? String.valueOf(sa.getPayCosts().getTotalMana()) : "";
                return mc.isEmpty() ? "{0}" : mc;
            }
        }
        return null;
    }

    private static void card(StringBuilder sb, CardView c) {
        CardStateView s = c.getCurrentState();
        sb.append('{');
        kv(sb, "id", c.getId()); sb.append(',');
        kvs(sb, "name", s != null ? s.getName() : c.getName()); sb.append(',');
        kvs(sb, "type_line", s != null && s.getType() != null ? s.getType().toString() : ""); sb.append(',');
        kvs(sb, "mana_cost", s != null && s.getManaCost() != null ? s.getManaCost().toString() : ""); sb.append(',');
        kvs(sb, "oracle_text", s != null ? nz(s.getOracleText()) : ""); sb.append(',');
        kv(sb, "power", s != null ? s.getPower() : 0); sb.append(',');
        kv(sb, "toughness", s != null ? s.getToughness() : 0); sb.append(',');
        boolean creature = s != null && s.getType() != null && s.getType().isCreature();
        kvb(sb, "is_creature", creature); sb.append(',');
        // Battles, and who defends this one.
        //
        // A Siege enters under its CASTER's control and stays on their
        // battlefield, protected by an opponent -- correct rules, and correct
        // here, but it reads as a bug at the table: the player who has to
        // defend the thing sees it sitting across the board on someone else's
        // side. The translator uses these two fields to render a battle in the
        // PROTECTOR's row instead. Nothing about control changes; only where
        // the card is drawn.
        //
        // Both fall away by themselves when it is defeated: the battle is
        // exiled and cast transformed, and the transformed side is not a
        // battle, so it comes back under the caster with no protector and
        // renders on the caster's side again.
        boolean battle = s != null && s.getType() != null && s.getType().isBattle();
        kvb(sb, "is_battle", battle); sb.append(',');
        kvs(sb, "protected_by",
            battle && c.getProtectingPlayer() != null
                ? nz(c.getProtectingPlayer().getName()) : ""); sb.append(',');
        // Face-down (morph, disguise, manifest, cloak, foretold-in-exile...).
        // Forge's current state for one of these is the nameless 2/2 face,
        // so without this flag the client had nothing to draw and left a
        // grey frame. The translator turns it into card-back art. The
        // hidden face's name rides along only when this viewer may see it.
        boolean faceDown = c.isFaceDown();
        kvb(sb, "face_down", faceDown); sb.append(',');
        String hiddenName = "";
        if (faceDown && viewer != null && c.canFaceDownBeShownTo(viewer)
                && c.getAlternateState() != null) {
            hiddenName = nz(c.getAlternateState().getName());
        }
        kvs(sb, "face_down_name", hiddenName); sb.append(',');
        kvb(sb, "tapped", c.isTapped()); sb.append(',');
        kvb(sb, "sick", c.isSick()); sb.append(',');
        kvb(sb, "attacking", c.isAttacking()); sb.append(',');
        kvb(sb, "has_mana_ability", manaCards.contains(c.getId())); sb.append(',');
        kvb(sb, "has_activated_abilities", activatedCards.contains(c.getId())); sb.append(',');
        kvs(sb, "cycling_cost", nz(cyclingCosts.get(c.getId()))); sb.append(',');
        kvb(sb, "playable", playableElsewhere.contains(c.getId())); sb.append(',');
        kv(sb, "damage", c.getDamage()); sb.append(',');
        if (c.isToken()) {
            kvb(sb, "is_token", true); sb.append(',');
            kvs(sb, "token_art", tokenArt(s)); sb.append(',');
        }
        kvAttachments(sb, c); sb.append(',');
        kvKeywords(sb, s); sb.append(',');
        String blk = blockingOf.get(c.getId());
        kvb(sb, "is_blocking", blk != null); sb.append(',');
        kvs(sb, "blocking", nz(blk)); sb.append(',');
        sb.append("\"blocking_ids\":").append(nzList(blockingIdsOf.get(c.getId()))).append(',');
        String atkTarget = defenderOf.get(c.getId());
        sb.append("\"attack\":").append(atkTarget == null ? "null" : atkTarget).append(',');
        sb.append("\"abilities\":").append(nzList(abilityLists.get(c.getId()))).append(',');
        kvCounters(sb, "counters", c);
        sb.append('}');
    }

    /**
     * Every ability of {@code c} that {@code human} could use right now, as
     * [{"i":0,"kind":..,"cost":..,"label":..}, ...].
     *
     * getAllPossibleAbilities(player, true) is deliberately the same call the
     * bridge makes when the reply comes back -- the index is only meaningful
     * because both sides build the list the same way.
     */
    /**
     * Why the mana auto-tapper could not pay for {@code sa} right now, in the
     * player's words -- or null when it can (or when the question does not
     * apply: lands, mana abilities, free abilities, X costs).
     *
     * The bridge pays every mana cost with the AI's auto-tapper
     * (ComputerUtilMana.payManaCost), deliberately, so the player never has to
     * pick lands. The other half of that bargain is that Forge's playability
     * filter never checks affordability for a human: getAllPossibleAbilities
     * offers "{R}, {T}, Sacrifice an artifact: ..." on a tapped-out board, the
     * client shows it live, the click walks all the way through target
     * selection, and THEN the auto-tapper finds nothing to tap and unwinds the
     * whole thing with one line on the server's stdout. Reported from a live
     * Commander pod as "my Goblin Engineer should be able to be activated but
     * it won't let me" -- seven attempts over 40 minutes, every one of them
     * after the turn's mana had gone into creatures, every one of them silent.
     *
     * This asks the same auto-tapper the same question in test mode, so the
     * answer here IS the answer the click would get. It greys the ability
     * before the click (putAbilities) and captions the failure after one
     * (PlayerControllerBridge.playChosenSpellAbility). X costs are left alone:
     * the test path picks X by AI heuristics, and X=0 may well be payable.
     *
     * The memo keys on card name + cost + spell/ability, which is exactly the
     * granularity Forge's own cost adjustments work at (Training Grounds
     * touches creature abilities, Foundry Inspector artifact spells, ...), and
     * collapses the six Myr tokens on a go-wide board into one check.
     */
    static String unpayableReason(SpellAbility sa, Player p, Map<String, String> memo) {
        try {
            if (sa == null || p == null || sa.isLandAbility() || sa.isManaAbility()) {
                return null;
            }
            Cost cost = sa.getPayCosts();
            if (cost == null || !cost.hasManaCost()) {
                return null;
            }
            ManaCost mc = cost.getTotalMana();
            if (mc == null || mc.isZero() || mc.countX() > 0) {
                return null;
            }
            Card host = sa.getHostCard();
            String key = (host != null ? host.getName() : "?") + "|" + mc + "|"
                    + (sa.isSpell() ? "S" : "A");
            if (memo != null && memo.containsKey(key)) {
                return memo.get(key);
            }
            if (sa.getActivatingPlayer() == null) {
                sa.setActivatingPlayer(p);
            }
            String why = null;
            if (!ComputerUtilMana.canPayManaCost(sa, p, 0, false)) {
                why = "can't pay " + mc + " - no untapped mana source to auto-tap"
                        + " (creatures that came in this turn can't tap yet;"
                        + " float mana by hand first if you have another way to make it)";
            }
            if (memo != null) {
                memo.put(key, why);
            }
            return why;
        } catch (Exception e) {
            // Affordability is a caption, never a reason to drop the ability.
            return null;
        }
    }

    private static void putAbilities(Map<Integer, String> out, Card c, Player human) {
        putAbilities(out, c, human, null);
    }

    private static void putAbilities(Map<Integer, String> out, Card c, Player human,
            Map<String, String> payMemo) {
        List<SpellAbility> sas = possibleAbilities(c, human);
        if (sas == null) {
            sas = Collections.emptyList();
        }
        // A planeswalker you control shows ALL its loyalty abilities, always:
        // the ones Forge filtered out come after the playable ones, with i=-1
        // and the reason. See missingLoyaltyAbilities.
        boolean walker = c.isPlaneswalker() && c.isInPlay() && c.getController() == human;
        List<SpellAbility> missing = walker ? missingLoyaltyAbilities(c, sas)
                                            : Collections.<SpellAbility>emptyList();
        if (sas.isEmpty() && missing.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(64 * (sas.size() + missing.size()));
        sb.append('[');
        int n = 0;
        for (int i = 0; i < sas.size(); i++) {
            SpellAbility sa = sas.get(i);
            if (n++ > 0) sb.append(',');
            sb.append('{');
            kv(sb, "i", i); sb.append(',');
            kvs(sb, "kind", abilityKind(sa)); sb.append(',');
            kvs(sb, "cost", abilityCost(sa, c)); sb.append(',');
            kvs(sb, "label", abilityLabel(sa, c));
            // Playable by Forge's filter, yet doomed: a mandatory target with
            // no candidate. Loyalty abilities only -- see noTargetReason.
            String why = sa.isPwAbility() ? noTargetReason(sa) : null;
            // Playable by Forge's filter, yet unaffordable: the filter never
            // asks whether a human can pay, and the auto-tapper that pays for
            // them says no. See unpayableReason.
            if (why == null) {
                why = unpayableReason(sa, human, payMemo);
            }
            if (why != null) {
                sb.append(',');
                kvs(sb, "disabled", why);
            }
            sb.append('}');
        }
        for (SpellAbility sa : missing) {
            if (n++ > 0) sb.append(',');
            sb.append('{');
            kv(sb, "i", -1); sb.append(',');
            kvs(sb, "kind", abilityKind(sa)); sb.append(',');
            kvs(sb, "cost", abilityCost(sa, c)); sb.append(',');
            kvs(sb, "label", abilityLabel(sa, c)); sb.append(',');
            kvs(sb, "disabled", loyaltyUnavailableReason(sa, c, human));
            sb.append('}');
        }
        sb.append(']');
        out.put(c.getId(), sb.toString());
    }

    /**
     * The loyalty abilities of {@code c} that getAllPossibleAbilities(.., true)
     * left out -- already used one this turn, can't afford the -N, wrong
     * timing.
     *
     * Reported from a live Commander pod: "it felt like my last planeswalker
     * wasn't able to activate its -2 ability". The -2 in question was
     * legitimately unusable (Silent Gravestone across the table made its
     * graveyard target illegal), but the card offered no way to tell WHY, or
     * even that the engine had looked at it. Listing every loyalty ability,
     * with the unavailable ones greyed and captioned, turns "this button is
     * broken" into "needs 10 loyalty, has 3" / "already used a loyalty ability
     * this turn". Entries carry i=-1, which the client never sends back --
     * the index contract on the playable list is untouched.
     *
     * Identity first, description second: getAllPossibleAbilities adds the
     * very same SpellAbility objects it got from getSpellAbilities, then copies
     * for alternative costs; loyalty abilities have none, but the description
     * check keeps this honest if that ever changes.
     */
    private static List<SpellAbility> missingLoyaltyAbilities(Card c, List<SpellAbility> offered) {
        List<SpellAbility> out = new ArrayList<>();
        try {
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (!sa.isPwAbility()) continue;
                boolean present = false;
                String desc = sa.toUnsuppressedString();
                for (SpellAbility o : offered) {
                    if (o == sa || o.getRootAbility() == sa
                            || (desc != null && desc.equals(o.toUnsuppressedString()))) {
                        present = true;
                        break;
                    }
                }
                if (!present) out.add(sa);
            }
        } catch (Exception e) {
            // Best effort: a walker with an odd script just shows what Forge offered.
        }
        return out;
    }

    /** Why Forge filtered this loyalty ability out right now, in the player's words. */
    private static String loyaltyUnavailableReason(SpellAbility sa, Card c, Player human) {
        try {
            int need = loyaltyCost(sa);
            int have = c.getCurrentLoyalty();
            if (need > have) {
                return "needs " + need + " loyalty, has " + have;
            }
            if (c.getPlaneswalkerAbilityActivated() > 0) {
                return "already used a loyalty ability this turn";
            }
            if (!human.canCastSorcery()) {
                Game g = human.getGame();
                boolean myTurn = g != null && g.getPhaseHandler() != null
                        && g.getPhaseHandler().isPlayerTurn(human);
                return myTurn ? "sorcery speed: needs your main phase with an empty stack"
                              : "sorcery speed: only on your own turn";
            }
            String tgt = noTargetReason(sa);
            if (tgt != null) return tgt;
        } catch (Exception e) {
            // fall through
        }
        return "not available right now";
    }

    /** Loyalty this ability removes as its cost (0 for a +N or a 0). */
    private static int loyaltyCost(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null) return 0;
        int total = 0;
        for (CostPart part : cost.getCostParts()) {
            if (part instanceof CostRemoveCounter) {
                CostRemoveCounter rc = (CostRemoveCounter) part;
                if (CounterEnumType.LOYALTY.equals(rc.counter)) {
                    total += rc.getAbilityAmount(sa);
                }
            }
        }
        return total;
    }

    /**
     * "no legal target (...)" if a mandatory target anywhere in this ability's
     * chain has no candidate right now, else null.
     *
     * Forge's playability filter does not look at targets, so an ability can
     * be offered and then cancel the moment it is clicked -- see
     * PlayerControllerBridge.explainNoTarget for the report. The check walks
     * every card in the target zone through canTarget (isValid, hexproof,
     * CantTarget statics), which is the cost centre this exporter is profiled
     * against, so it is limited to loyalty abilities: one to three per
     * planeswalker, and planeswalkers are few. The cast-time feed line covers
     * everything else.
     */
    private static String noTargetReason(SpellAbility root) {
        try {
            for (SpellAbility sa = root; sa != null; sa = sa.getSubAbility()) {
                if (!sa.usesTargeting() || sa.getMinTargets() <= 0) continue;
                TargetRestrictions tr = sa.getTargetRestrictions();
                List<ZoneType> zones = tr.getZone();
                boolean any;
                if (zones != null && zones.size() == 1 && zones.get(0) == ZoneType.Stack) {
                    any = false;
                    Game g = root.getHostCard() != null ? root.getHostCard().getGame() : null;
                    if (g != null) {
                        for (SpellAbilityStackInstance si : g.getStack()) {
                            if (sa.canTargetSpellAbility(si.getSpellAbility())) {
                                any = true;
                                break;
                            }
                        }
                    }
                } else {
                    any = !tr.getAllCandidates(sa).isEmpty();
                }
                if (!any) {
                    String wants = tr.getVTSelection() == null ? "" : tr.getVTSelection().trim();
                    if (wants.regionMatches(true, 0, "Select ", 0, 7)) {
                        wants = wants.substring(7);
                    }
                    return "no legal target" + (wants.isEmpty() ? "" : " (needs " + wants + ")");
                }
            }
        } catch (Exception e) {
            // Unknown: do not grey an ability on a guess.
        }
        return null;
    }

    /**
     * How the client should send this ability back: "mana" and "cycle" keep the
     * dedicated verbs they already have (Tap for Mana taps the card visually,
     * Cycle is its own menu item), everything else is a play or an activation
     * carrying {"ability":i}.
     */
    private static String abilityKind(SpellAbility sa) {
        if (sa.isManaAbility()) return "mana";
        if (sa.isCycling()) return "cycle";
        if (sa.isLandAbility()) return "land";
        if (sa.isSpell()) return "spell";
        if (sa.isActivatedAbility()) return "activated";
        return "other";
    }

    /**
     * The cost as menu text. Two Forge-isms have to go first: toSimpleString()
     * leaves the literal "CARDNAME" in costs that spend the source (a
     * typecycling discard, say), and a land play costs "no cost", which reads
     * as a cost rather than as the absence of one.
     */
    private static String abilityCost(SpellAbility sa, Card c) {
        Cost cost = sa.getPayCosts();
        String s = cost == null ? "" : cost.toSimpleString();
        if (s == null || s.isEmpty() || "no cost".equalsIgnoreCase(s.trim())) {
            return "";
        }
        return subCardName(s.trim(), c);
    }

    /** Forge's own placeholder for "this card", as it appears in cost text. */
    private static String subCardName(String s, Card c) {
        if (c == null || s.indexOf("CARDNAME") < 0) {
            return s;
        }
        return s.replace("CARDNAME", c.getName());
    }

    /** One line of menu text: what the ability does, without its cost. */
    static String abilityLabel(SpellAbility sa, Card c) {
        String desc = sa.toUnsuppressedString();
        desc = desc == null ? "" : desc.trim();
        // getAdditionalCostSpell tags the branch with its cost; the cost is a
        // field of its own here, so drop the duplicate.
        int extra = desc.lastIndexOf("(Additional cost:");
        if (extra >= 0) {
            desc = desc.substring(0, extra).trim();
        }
        desc = desc.replaceAll("\\s+", " ");
        desc = subCardName(desc, c);
        if (desc.isEmpty()) {
            desc = c != null ? c.getName() : "Ability";
        }
        return desc.length() > 90 ? desc.substring(0, 87) + "..." : desc;
    }

    /**
     * The card's effective keywords, lowercased, e.g. ["flying","first strike"].
     *
     * The client draws its badge row (flying, deathtouch, first strike, menace,
     * ward, ...) straight off this list, and the translator used to hardcode it
     * empty -- so on Forge no creature ever showed a badge and blocks were
     * declared blind. Parameterised keywords arrive as "Ward:2" /
     * "Protection:Card.Blue:from blue"; the client matches the bare keyword, so
     * the tail is cut.
     *
     * The QUALIFIED form now goes out alongside the bare one, taken from
     * KeywordView.title(): "protection from red", "ward {2}".
     *
     * For protection the parameter is the whole point, and cutting it lost the
     * only part worth reading. Reported from a live pod: a Realm-Cloaked Giant
     * wearing Pentarch Ward was missing from a red spell's target list, and the
     * card showed nothing to explain why -- an untargetable creature looked
     * exactly like a targetable one, so correct rules read as an engine bug.
     * title() also resolves a CHOSEN colour, which the raw script form cannot:
     * Pentarch Ward's keyword is literally "Protection:Card.ChosenColor:
     * chosenColor", a placeholder rather than a word.
     *
     * Additive on purpose. The bare name is still emitted first, so every
     * existing bare-keyword matcher on the client keeps working untouched. It
     * also fixes a quiet loss: two different protections used to dedupe down to
     * a single "protection".
     */
    private static void kvKeywords(StringBuilder sb, CardStateView s) {
        sb.append("\"keywords\":[");
        if (s != null && s.getKeywords() != null) {
            Set<String> seen = new HashSet<>();
            boolean first = true;
            for (KeywordView k : s.getKeywords()) {
                if (k == null) continue;
                String raw = k.original();
                if (raw == null || raw.isEmpty()) continue;
                int colon = raw.indexOf(':');
                String name = (colon > 0 ? raw.substring(0, colon) : raw)
                        .trim().toLowerCase(Locale.ENGLISH);
                if (!name.isEmpty() && seen.add(name)) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('"').append(esc(name)).append('"');
                }
                // Only when the bare name was actually TRUNCATED, i.e. the raw
                // form had a colon. With no colon the raw string is already the
                // full human text and title() double-prefixes it: innate
                // protection is scripted "Protection from black", and
                // Protection.getTitle() returns "Protection from " +
                // getTypeDescription(), where the description is itself "from
                // black" -- so the wire carried "protection from from black".
                if (colon <= 0) {
                    continue;
                }
                String title;
                try {
                    title = k.title();
                } catch (Exception e) {
                    continue;   // a label must never break a state export
                }
                if (title == null) continue;
                title = title.trim().toLowerCase(Locale.ENGLISH);
                if (title.isEmpty() || !seen.add(title)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc(title)).append('"');
            }
        }
        sb.append(']');
    }

    /** Blocker id -> comma-joined names of the attackers it is blocking. */
    private static Map<Integer, String> blockAssignments(GameView g) {
        CombatView combat = g == null ? null : g.getCombat();
        if (combat == null || combat.getAttackers() == null) {
            return Collections.emptyMap();
        }
        Map<Integer, String> out = new HashMap<>();
        for (CardView atk : combat.getAttackers()) {
            if (atk == null) continue;
            Iterable<CardView> blockers = combat.getBlockers(atk);
            if (blockers == null) continue;
            for (CardView b : blockers) {
                if (b == null) continue;
                String prior = out.get(b.getId());
                out.put(b.getId(), prior == null ? atk.getName() : prior + ", " + atk.getName());
            }
        }
        return out;
    }

    /** Attacker id -> JSON naming its defender; see {@link #defenderOf}. */
    private static Map<Integer, String> attackTargets(GameView g) {
        CombatView combat = g == null ? null : g.getCombat();
        if (combat == null || combat.getAttackers() == null) {
            return Collections.emptyMap();
        }
        Map<Integer, String> out = new HashMap<>();
        for (CardView atk : combat.getAttackers()) {
            if (atk == null) continue;
            GameEntityView d;
            try {
                d = combat.getDefender(atk);
            } catch (Exception e) {
                d = null;
            }
            if (d == null) continue;
            String target, kind, defender, targetId;
            if (d instanceof CardView) {
                CardView dc = (CardView) d;
                CardStateView ds = dc.getCurrentState();
                boolean battle = ds != null && ds.getType() != null && ds.getType().isBattle();
                kind = battle ? "battle" : "planeswalker";
                PlayerView guard = battle ? dc.getProtectingPlayer() : dc.getController();
                target = ds != null ? ds.getName() : dc.getName();
                defender = guard != null ? guard.getName() : "";
                targetId = String.valueOf(dc.getId());
            } else if (d instanceof PlayerView) {
                target = ((PlayerView) d).getName();
                kind = "player";
                defender = target;
                targetId = "";
            } else {
                continue;
            }
            StringBuilder sb = new StringBuilder(96);
            sb.append('{');
            kvs(sb, "target", nz(target)); sb.append(',');
            kvs(sb, "kind", kind); sb.append(',');
            kvs(sb, "defender", nz(defender)); sb.append(',');
            kvs(sb, "target_id", targetId);
            sb.append('}');
            out.put(atk.getId(), sb.toString());
        }
        return out;
    }

    /** Blocker id -> JSON array of attacker ids it blocks; see {@link #blockingIdsOf}. */
    private static Map<Integer, String> blockIds(GameView g) {
        CombatView combat = g == null ? null : g.getCombat();
        if (combat == null || combat.getAttackers() == null) {
            return Collections.emptyMap();
        }
        Map<Integer, List<Integer>> ids = new HashMap<>();
        for (CardView atk : combat.getAttackers()) {
            if (atk == null) continue;
            Iterable<CardView> blockers = combat.getBlockers(atk);
            if (blockers == null) continue;
            for (CardView b : blockers) {
                if (b == null) continue;
                ids.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(atk.getId());
            }
        }
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : ids.entrySet()) {
            StringBuilder sb = new StringBuilder(16);
            sb.append('[');
            boolean first = true;
            for (Integer id : e.getValue()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(id);
            }
            sb.append(']');
            out.put(e.getKey(), sb.toString());
        }
        return out;
    }

    private static String nzList(String json) { return json == null ? "[]" : json; }

    /**
     * Who this card is attached to, and what is attached to it.
     *
     * The client renders Auras and Equipment fanned behind their host rather
     * than as loose cards in the battlefield row, and it decides that purely
     * from these fields (MatchView pre-scans both battlefields for
     * "attached_to" before rendering either). Without them an equipped
     * Stalactite Dagger sat in the row looking unattached even though the
     * creature had its +1/+1 — which reads as "equip did nothing".
     *
     * An Aura on a *player* (getEnchantedPlayer) has no card host, so it stays
     * unattached here and keeps rendering as its own permanent.
     */
    private static void kvAttachments(StringBuilder sb, CardView c) {
        CardView host = c.getAttachedTo();
        kvs(sb, "attached_to", host != null ? String.valueOf(host.getId()) : ""); sb.append(',');
        List<CardView> attached = c.getAttachedCards();
        // "attachments" carries names (what the card detail shows),
        // "attachment_ids" the ids — matching the native engine's serializer.
        sb.append("\"attachments\":[");
        boolean first = true;
        for (CardView a : attached == null ? Collections.<CardView>emptyList() : attached) {
            if (a == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(a.getName())).append('"');
        }
        sb.append("],\"attachment_ids\":[");
        first = true;
        for (CardView a : attached == null ? Collections.<CardView>emptyList() : attached) {
            if (a == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(a.getId()).append('"');
        }
        sb.append(']');
    }

    /**
     * Scryfall identity of a token's printed art, as "<tokenSet>/<collectorNumber>".
     *
     * Forge pins a token's printing in its image key —
     * "t:<script>|<SET>|<collectorNumber>|<artIndex>" (PaperToken.getImageKey) —
     * where SET is the card set whose printing made the token. Scryfall files
     * token art under that set's *token* set, normally "T" + the set code but
     * overridable per edition (TokensCode), so the edition is resolved here
     * where StaticData is on hand rather than guessed at in Python. Returns ""
     * when Forge knows no printing; the client then falls back to the name.
     */
    private static String tokenArt(CardStateView s) {
        if (s == null) return "";
        String key = s.getImageKey();
        if (key == null || !key.startsWith(ImageKeys.TOKEN_PREFIX)) return "";
        String[] parts = key.substring(ImageKeys.TOKEN_PREFIX.length()).split("\\|");
        if (parts.length < 3 || parts[1].isEmpty() || parts[2].isEmpty()) return "";
        CardEdition edition = StaticData.instance().getEditions().get(parts[1]);
        if (edition == null) return "";
        String tokenSet = edition.getTokensCode();
        if (tokenSet == null || tokenSet.isEmpty()) return "";
        return tokenSet.toLowerCase(Locale.ENGLISH) + "/" + parts[2];
    }

    /** Emit a card's counters as {"-1/-1":3, "+1/+1":1, ...} (name -> count). */
    private static void kvCounters(StringBuilder sb, String k, CardView c) {
        kvCounterMultiset(sb, k, c == null ? null : c.getCounters());
    }

    /**
     * Emit a PLAYER's counters, same shape as a card's.
     *
     * Poison, energy, experience and rad all live here, and none of them
     * reached the client before. Player.java writes exactly ONE log line in the
     * whole class (DISCARD), so none of them is announced either -- a player
     * could be one counter from losing to poison with nothing on screen and
     * nothing in the Feed ever having mentioned it.
     *
     * Generic rather than four named fields on purpose: it is the same Multiset
     * the card path already handles, so every counter type Forge has -- and any
     * it gains -- comes across without another edit here.
     *
     * The MAP's presence is what says this jar supports the field at all. An
     * older jar sends no "counters" key, which the translator reads as unknown
     * rather than as zero; a current jar with no poison sends the map without a
     * poison entry, which is a genuine zero. Those must stay distinguishable --
     * see _opt_counter in forge_state_to_wire.
     */
    private static void kvPlayerCounters(StringBuilder sb, String k, PlayerView p) {
        kvCounterMultiset(sb, k, p == null ? null : p.getCounters());
    }

    private static void kvCounterMultiset(StringBuilder sb, String k,
                                          Multiset<CounterType> counters) {
        sb.append('"').append(k).append("\":{");
        if (counters != null && !counters.isEmpty()) {
            boolean first = true;
            for (Multiset.Entry<CounterType> e : counters.entrySet()) {
                if (e.getCount() <= 0 || e.getElement() == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc(e.getElement().getName())).append("\":").append(e.getCount());
            }
        }
        sb.append('}');
    }

    private static int count(Iterable<CardView> it) {
        int n = 0; if (it != null) for (CardView ignored : it) n++; return n;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** "day", "night", or "" for neither -- see the daytime field in toJson. */
    private static String dayTime(Player human) {
        if (human == null) return "";
        Game game = human.getGame();
        if (game == null) return "";
        if (game.isDay()) return "day";
        if (game.isNight()) return "night";
        return "";
    }

    private static void kv(StringBuilder sb, String k, int v) { sb.append('"').append(k).append("\":").append(v); }
    private static void kvb(StringBuilder sb, String k, boolean v) { sb.append('"').append(k).append("\":").append(v); }
    private static void kvs(StringBuilder sb, String k, String v) { sb.append('"').append(k).append("\":\"").append(esc(v)).append('"'); }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.toString();
    }
}
