package forge.sim;

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
import forge.card.CardEdition;
import forge.card.MagicColor;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.keyword.KeywordView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.cost.Cost;
import forge.game.spellability.SpellAbility;
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
    private static int libraryTopOwner = -1;

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
     * Game-log entry types worth showing in the client's Feed: Forge's MEDIUM
     * verbosity (turns, lands, spells, combat, damage, life, mulligans, deaths)
     * minus PHASE/MANA, which would flood the panel with lines the board already
     * shows. GAME_OUTCOME / MATCH_RESULTS are kept so the player sees who won.
     */
    private static final Set<GameLogEntryType> FEED_TYPES = EnumSet.of(
            GameLogEntryType.TURN, GameLogEntryType.MULLIGAN, GameLogEntryType.LAND,
            GameLogEntryType.STACK_ADD, GameLogEntryType.STACK_RESOLVE,
            GameLogEntryType.COMBAT, GameLogEntryType.DAMAGE, GameLogEntryType.LIFE,
            GameLogEntryType.DISCARD, GameLogEntryType.ZONE_CHANGE,
            GameLogEntryType.EFFECT_REPLACED,
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
        Set<Integer> mana = new HashSet<>();
        Set<Integer> activated = new HashSet<>();
        Map<Integer, String> cycling = new HashMap<>();
        Set<Integer> elsewhere = new HashSet<>();
        Map<Integer, String> abilities = new HashMap<>();
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
                    putAbilities(abilities, c, human);
                }
            }
            for (ZoneType zt : ABILITY_ZONES) {
                for (Card c : human.getCardsIn(zt)) {
                    putAbilities(abilities, c, human);
                }
            }
        }
        manaCards = mana;
        activatedCards = activated;
        cyclingCosts = cycling;
        playableElsewhere = elsewhere;
        abilityLists = abilities;
        blockingOf = blockAssignments(g);
        libraryTop = top;
        libraryTopOwner = human != null && human.getView() != null ? human.getView().getId() : -1;

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        kv(sb, "turn", g.getTurn()); sb.append(',');
        kvs(sb, "phase", String.valueOf(g.getPhase())); sb.append(',');
        kvs(sb, "turn_player", g.getPlayerTurn() != null ? g.getPlayerTurn().getName() : ""); sb.append(',');

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
        kv(sb, "library_count", count(p.getCards(ZoneType.Library))); sb.append(',');
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
    private static void putAbilities(Map<Integer, String> out, Card c, Player human) {
        List<SpellAbility> sas = c.getAllPossibleAbilities(human, true);
        if (sas == null || sas.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(64 * sas.size());
        sb.append('[');
        for (int i = 0; i < sas.size(); i++) {
            SpellAbility sa = sas.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            kv(sb, "i", i); sb.append(',');
            kvs(sb, "kind", abilityKind(sa)); sb.append(',');
            kvs(sb, "cost", abilityCost(sa, c)); sb.append(',');
            kvs(sb, "label", abilityLabel(sa, c));
            sb.append('}');
        }
        sb.append(']');
        out.put(c.getId(), sb.toString());
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
    private static String abilityLabel(SpellAbility sa, Card c) {
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
                if (name.isEmpty() || !seen.add(name)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc(name)).append('"');
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
        sb.append('"').append(k).append("\":{");
        Multiset<CounterType> counters = c.getCounters();
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
