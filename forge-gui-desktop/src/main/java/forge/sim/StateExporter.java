package forge.sim;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.Multiset;

import forge.card.MagicColor;
import forge.game.GameView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
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

    public static String toJson(GameView g) {
        return toJson(g, null);
    }

    public static String toJson(GameView g, Player human) {
        Set<Integer> mana = new HashSet<>();
        Set<Integer> activated = new HashSet<>();
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
        }
        manaCards = mana;
        activatedCards = activated;

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        kv(sb, "turn", g.getTurn()); sb.append(',');
        kvs(sb, "phase", String.valueOf(g.getPhase())); sb.append(',');
        kvs(sb, "turn_player", g.getPlayerTurn() != null ? g.getPlayerTurn().getName() : ""); sb.append(',');

        sb.append("\"players\":[");
        boolean firstP = true;
        for (PlayerView p : g.getPlayers()) {
            if (!firstP) sb.append(','); firstP = false;
            player(sb, p);
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
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    private static void player(StringBuilder sb, PlayerView p) {
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
        zone(sb, "exile", p.getCards(ZoneType.Exile));
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
        kv(sb, "damage", c.getDamage()); sb.append(',');
        kvCounters(sb, "counters", c);
        sb.append('}');
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

    private static String esc(String s) {
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
