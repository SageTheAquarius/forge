package forge.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.eventbus.Subscribe;

import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardModeChosen;
import forge.game.event.GameEventCardPlotted;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnEnded;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Structured play events for the post-game quality report.
 *
 * The Feed tells a person what happened ("you cast Aloe Alchemist"); it does
 * not say WHICH cost was paid, whether a trigger fizzled, what was targeted,
 * or what was left in hand with mana up at end of turn. Those are the facts
 * the utilization / missed-opportunity scoring (mtg_sim/game_stats.py) needs,
 * and Forge knows every one of them at the moment it happens.
 *
 * Each event is one game-log INFORMATION line of the form {@code evt {...}}:
 * one compact JSON object, no newlines, no " (123)" card-id suffixes. Riding
 * the game log means it reaches Python through the SAME path as every Feed
 * line (StateExporter.gameLog -> forge_state_to_wire.feed_lines -> push_feed),
 * the session log records it verbatim ("feed: evt {...}"), and the backfill
 * tool replays it for free. ws_server drops "evt " lines before they reach
 * the client, so the player never sees them.
 *
 * Cost on the game thread: a few string builds per stack item and one small
 * scan of the active player's board at end of turn. No simulation, nothing
 * that scales with the size of the game. Switch off with
 * {@code -Dbridge.playevents=off}.
 *
 * Events (field "e"):
 *   cast      spell put on the stack: seat, card, id, mv, cost (normal |
 *             kicked | plotted | flashback | ... | free), from (zone), spare
 *             (mana still available AFTER paying), tgts, tp
 *   activate  activated ability: seat, card, id, desc, spare, tgts, tp
 *   trigger   triggered ability on the stack: seat, card, id, mode, desc
 *   resolve   stack item resolved: kind (cast|activate|trigger), card, id,
 *             mode, fizzled
 *   plot      card plotted: seat, card, id
 *   mode      modal choice: seat, card, mode
 *   attack    attackers declared: seat, atk[{c,id,to}]
 *   block     blockers declared: seat, blk[{c,id,of}]
 *   turn_end  turn, seat, spare, pool, hand[{c,mv,t}] of the ACTIVE player
 *   game_end  turn, seats[{seat, hand[names], plotted[names], lib}]
 */
public final class PlayEvents {
    private final Game game;
    /** SA id -> the summary of its cast/activate/trigger event, for "resolve". */
    private final Map<Integer, String[]> pending = new HashMap<>();

    public PlayEvents(Game game) {
        this.game = game;
    }

    public static boolean enabled() {
        return !"off".equals(System.getProperty("bridge.playevents"));
    }

    // ── stack ────────────────────────────────────────────────────────────────

    @Subscribe
    @SuppressWarnings("unused")
    public void onCast(GameEventSpellAbilityCast ev) {
        try {
            if (ev.si() == null) return;
            SpellAbility sa = findStackAbility(ev.si().getId());
            if (sa == null) return;
            Card host = sa.getHostCard();
            Player who = sa.getActivatingPlayer();
            if (host == null || who == null) return;
            String kind = sa.isSpell() ? "cast" : sa.isTrigger() ? "trigger" : "activate";
            Json j = new Json().s("e", kind).s("seat", who.getName())
                    .s("card", host.getName()).n("id", host.getId());
            if (host.isToken()) j.b("tok", true);
            String mode = "";
            if (sa.isSpell()) {
                j.n("mv", host.getCMC());
                j.s("cost", costKind(sa));
                if (host.getCastFrom() != null && host.getCastFrom().getZoneType() != null) {
                    j.s("from", host.getCastFrom().getZoneType().name().toLowerCase());
                }
                j.n("spare", spareMana(who));
            } else if (sa.isTrigger()) {
                Trigger t = sa.getTrigger();
                mode = t != null && t.getMode() != null ? t.getMode().name() : "";
                j.s("mode", mode);
                j.s("desc", clip(sa.getDescription(), 100));
            } else {
                j.s("desc", clip(sa.getDescription(), 100));
                j.n("spare", spareMana(who));
            }
            targets(j, sa);
            emit(j);
            pending.put(sa.getId(), new String[] {kind, host.getName(),
                    String.valueOf(host.getId()), mode});
        } catch (Exception ignore) {
            // stats must never cost a game
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onResolved(GameEventSpellResolved ev) {
        try {
            if (ev.spell() == null) return;
            String[] p = pending.remove(ev.spell().getId());
            Json j = new Json().s("e", "resolve");
            if (p != null) {
                j.s("kind", p[0]).s("card", p[1]).n("id", Integer.parseInt(p[2]));
                if (!p[3].isEmpty()) j.s("mode", p[3]);
            } else {
                CardView cv = ev.spell().getHostCard();
                j.s("kind", "").s("card", cv != null ? cv.getName() : "")
                        .n("id", cv != null ? cv.getId() : 0);
            }
            j.b("fizzled", ev.hasFizzled());
            emit(j);
        } catch (Exception ignore) {
        }
    }

    // ── choices ──────────────────────────────────────────────────────────────

    @Subscribe
    @SuppressWarnings("unused")
    public void onPlotted(GameEventCardPlotted ev) {
        try {
            if (ev.card() == null) return;
            emit(new Json().s("e", "plot").s("seat", name(ev.activatingPlayer()))
                    .s("card", ev.card().getName()).n("id", ev.card().getId()));
        } catch (Exception ignore) {
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onMode(GameEventCardModeChosen ev) {
        try {
            emit(new Json().s("e", "mode").s("seat", name(ev.player()))
                    .s("card", ev.cardName()).s("mode", clip(ev.mode(), 100))
                    .b("random", ev.random()));
        } catch (Exception ignore) {
        }
    }

    // ── combat ───────────────────────────────────────────────────────────────

    @Subscribe
    @SuppressWarnings("unused")
    public void onAttackers(GameEventAttackersDeclared ev) {
        try {
            Json j = new Json().s("e", "attack").s("seat", name(ev.player()));
            List<String> rows = new ArrayList<>();
            if (ev.attackersMap() != null) {
                for (Map.Entry<GameEntityView, CardView> en : ev.attackersMap().entries()) {
                    CardView c = en.getValue();
                    if (c == null) continue;
                    rows.add(new Json().s("c", c.getName()).n("id", c.getId())
                            .s("to", en.getKey() != null ? en.getKey().getName() : "").toString());
                }
            }
            j.raw("atk", "[" + String.join(",", rows) + "]");
            emit(j);
        } catch (Exception ignore) {
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onBlockers(GameEventBlockersDeclared ev) {
        try {
            Json j = new Json().s("e", "block").s("seat", name(ev.defendingPlayer()));
            List<String> rows = new ArrayList<>();
            if (ev.blockers() != null) {
                for (Map.Entry<GameEntityView, com.google.common.collect.Multimap<CardView, CardView>> d
                        : ev.blockers().entrySet()) {
                    if (d.getValue() == null) continue;
                    for (Map.Entry<CardView, CardView> en : d.getValue().entries()) {
                        CardView attacker = en.getKey(), blocker = en.getValue();
                        if (blocker == null) continue;
                        rows.add(new Json().s("c", blocker.getName()).n("id", blocker.getId())
                                .s("of", attacker != null ? attacker.getName() : "")
                                .n("of_id", attacker != null ? attacker.getId() : 0).toString());
                    }
                }
            }
            j.raw("blk", "[" + String.join(",", rows) + "]");
            emit(j);
        } catch (Exception ignore) {
        }
    }

    // ── turn / game boundaries ───────────────────────────────────────────────

    @Subscribe
    @SuppressWarnings("unused")
    public void onTurnEnded(GameEventTurnEnded ev) {
        try {
            Player p = game.getPhaseHandler().getPlayerTurn();
            if (p == null) return;
            Json j = new Json().s("e", "turn_end").n("turn", game.getPhaseHandler().getTurn())
                    .s("seat", p.getName()).n("spare", spareMana(p))
                    .n("pool", p.getManaPool().totalMana());
            List<String> hand = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                hand.add(new Json().s("c", c.getName()).n("mv", c.getCMC())
                        .s("t", typeLetter(c)).toString());
            }
            j.raw("hand", "[" + String.join(",", hand) + "]");
            emit(j);
        } catch (Exception ignore) {
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onOutcome(GameEventGameOutcome ev) {
        try {
            Json j = new Json().s("e", "game_end").n("turn", game.getPhaseHandler().getTurn());
            List<String> seats = new ArrayList<>();
            for (Player p : game.getPlayers()) {
                List<String> hand = new ArrayList<>(), plotted = new ArrayList<>();
                for (Card c : p.getCardsIn(ZoneType.Hand)) hand.add(Json.q(c.getName()));
                for (Card c : p.getCardsIn(ZoneType.Exile)) {
                    if (c.isPlotted()) plotted.add(Json.q(c.getName()));
                }
                seats.add(new Json().s("seat", p.getName())
                        .raw("hand", "[" + String.join(",", hand) + "]")
                        .raw("plotted", "[" + String.join(",", plotted) + "]")
                        .n("lib", p.getCardsIn(ZoneType.Library).size()).toString());
            }
            j.raw("seats", "[" + String.join(",", seats) + "]");
            emit(j);
        } catch (Exception ignore) {
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SpellAbility findStackAbility(int stackItemId) {
        for (SpellAbilityStackInstance si : game.getStack()) {
            if (si.getId() == stackItemId) return si.getSpellAbility();
        }
        // The item may already be gone (split second, a copy resolved at once):
        // the newest instance is the one the event was about.
        SpellAbilityStackInstance top = game.getStack().peek();
        return top != null ? top.getSpellAbility() : null;
    }

    /** Which cost the spell was cast for. One word the Python side keys on. */
    static String costKind(SpellAbility sa) {
        SpellAbility root = sa.getRootAbility();
        AlternativeCost alt = root.getAlternativeCost();
        if (alt != null) return alt.name().toLowerCase();
        if (root.isKicked()) return "kicked";
        for (OptionalCost oc : root.getOptionalCosts()) {
            if (oc != null) return oc.name().toLowerCase();
        }
        if (root.isCastFromPlayEffect()) return "free";
        return "normal";
    }

    private static int spareMana(Player p) {
        try {
            // checkPlayable=true: a tapped land's mana ability cannot be played,
            // so this is what is STILL available -- after the cast just paid.
            return Math.max(0, ComputerUtilMana.getAvailableManaEstimate(p, true));
        } catch (Exception e) {
            return -1;
        }
    }

    private static void targets(Json j, SpellAbility sa) {
        List<String> cards = new ArrayList<>(), players = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            TargetChoices tc = s.getTargets();
            if (tc == null) continue;
            for (Card c : tc.getTargetCards()) {
                Json t = new Json().s("c", c.getName()).n("id", c.getId());
                if (c.isToken()) t.b("tok", true);
                if (c.getController() != null) t.s("who", c.getController().getName());
                cards.add(t.toString());
            }
            for (Player p : tc.getTargetPlayers()) players.add(Json.q(p.getName()));
        }
        if (!cards.isEmpty()) j.raw("tgts", "[" + String.join(",", cards) + "]");
        if (!players.isEmpty()) j.raw("tp", "[" + String.join(",", players) + "]");
    }

    private static String typeLetter(Card c) {
        if (c.isLand()) return "L";
        if (c.isCreature()) return "C";
        if (c.isInstant()) return "I";
        if (c.isSorcery()) return "S";
        if (c.isPlaneswalker()) return "P";
        if (c.isEnchantment()) return "E";
        if (c.isArtifact()) return "A";
        return "O";
    }

    private String name(PlayerView pv) {
        if (pv == null) return "";
        Player p = game.getPlayer(pv);
        return p != null ? p.getName() : pv.getName();
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    private void emit(Json j) {
        // Posted from inside another event's dispatch: Guava's EventBus queues
        // it and delivers after the current event, so the evt line always
        // lands just after the Feed line it annotates.
        game.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION, "evt " + j));
    }

    /** The smallest JSON builder that will do: flat objects, no nesting logic. */
    static final class Json {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private Json key(String k) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(k).append("\":");
            return this;
        }
        Json s(String k, String v) { key(k); sb.append(q(v)); return this; }
        Json n(String k, long v) { key(k); sb.append(v); return this; }
        Json b(String k, boolean v) { key(k); sb.append(v); return this; }
        Json raw(String k, String json) { key(k); sb.append(json); return this; }

        static String q(String v) {
            if (v == null) return "\"\"";
            StringBuilder o = new StringBuilder(v.length() + 2).append('"');
            for (int i = 0; i < v.length(); i++) {
                char ch = v.charAt(i);
                switch (ch) {
                    case '"': o.append("\\\""); break;
                    case '\\': o.append("\\\\"); break;
                    case '\n': case '\r': case '\t': o.append(' '); break;
                    default:
                        if (ch < 0x20) o.append(' '); else o.append(ch);
                }
            }
            return o.append('"').toString();
        }

        @Override
        public String toString() { return sb.toString() + "}"; }
    }
}
