package forge.sim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.common.eventbus.Subscribe;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Offline correctness evaluation of one decision snapshot.
 *
 * Driven by {@code <deckDir>/_eval.txt} (key=value lines) next to a
 * {@code _scenario.txt} that holds a DecisionSnapshots state. The scenario hook
 * in ForgeServer applies the board, then hands the game here. Every seat is
 * played by the Forge AI (no bridge controller is attached in eval mode), so
 * the client on the socket only ever sees game_over.
 *
 *   seat=<index>        the seat whose decision is being judged
 *   mode=list           write the legal candidates for that seat and stop
 *   mode=force          play candidate <candidate> for that seat, then let the
 *                       AI drive every seat for <horizon> turns
 *   candidate=<index>   0 is always "pass"; the rest as listed by mode=list
 *   horizon=<turns>     stop after this many turns and score the board
 *   seed=<n>            hidden information is redrawn before playing: every
 *                       other seat's hand goes back into its library, the
 *                       libraries are shuffled, the hands redrawn. The seed
 *                       makes one arm repeatable; several seeds average out
 *                       what the player could not have known.
 *
 * Output {@code <deckDir>/_eval_out.json}: candidates (mode=list), or the
 * outcome of the forced line: won / lost / a static board score for the seat
 * at the horizon (life, creatures, permanents, cards in hand, against the
 * average opponent). The Python driver (forge_bridge/quality/evaluate.py)
 * turns those into values and regret.
 *
 * Why fresh games rather than Forge's GameCopier: the copier cannot map the
 * Commander Effect, so it throws on every Commander and Lightning table, which
 * are the games this is for. Rebuilding the game from the snapshot goes
 * through the same ScenarioState path the practice sandbox already trusts.
 */
public final class Evaluate {
    public final int seat;
    public final String mode;
    public final int candidate;
    public final int horizon;
    public final long seed;
    private final File out;
    private Game game;
    private Player me;
    private int turn0 = 0;
    private boolean done = false;
    private List<String> labels = new ArrayList<>();
    private String chosen = "";

    private Evaluate(Map<String, String> kv, String deckDir) {
        this.seat = Integer.parseInt(kv.getOrDefault("seat", "0").trim());
        this.mode = kv.getOrDefault("mode", "list").trim();
        this.candidate = Integer.parseInt(kv.getOrDefault("candidate", "0").trim());
        this.horizon = Integer.parseInt(kv.getOrDefault("horizon", "4").trim());
        this.seed = Long.parseLong(kv.getOrDefault("seed", "1").trim());
        this.out = new File(deckDir + "_eval_out.json");
    }

    /** True when this match is an evaluation run (the flag file is present). */
    public static boolean present(String deckDir) {
        return new File(deckDir + "_eval.txt").exists();
    }

    public static Evaluate read(String deckDir) {
        File f = new File(deckDir + "_eval.txt");
        if (!f.exists()) return null;
        try {
            Map<String, String> kv = new LinkedHashMap<>();
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1));
            }
            return new Evaluate(kv, deckDir);
        } catch (Exception e) {
            System.out.println("[eval] bad _eval.txt: " + e);
            return null;
        }
    }

    // ── entry, from the scenario hook after the board is applied ─────────────

    public void afterApply(Game g) {
        this.game = g;
        try {
            if (seat < 0 || seat >= g.getPlayers().size()) {
                fail("no seat " + seat);
                return;
            }
            me = g.getPlayers().get(seat);
            turn0 = g.getPhaseHandler().getTurn();
            g.subscribeToEvents(this);
            determinize(new Random(seed));
            List<SpellAbility> cands = candidates();
            labels = new ArrayList<>();
            labels.add("pass");
            for (SpellAbility sa : cands) labels.add(label(sa));
            if ("list".equals(mode)) {
                finish("list");
                g.setGameOver(GameEndReason.Draw);
                return;
            }
            if (candidate > 0) {
                if (candidate - 1 >= cands.size()) {
                    fail("no candidate " + candidate + " (have " + cands.size() + ")");
                    g.setGameOver(GameEndReason.Draw);
                    return;
                }
                SpellAbility sa = cands.get(candidate - 1);
                chosen = labels.get(candidate);
                boolean ok;
                if (sa.isLandAbility()) {
                    ok = me.playLand(sa.getHostCard(), sa) != null;
                } else {
                    sa.setActivatingPlayer(me);
                    ok = ComputerUtil.handlePlayingSpellAbility(me, sa, null);
                }
                if (!ok) {
                    fail("could not play " + chosen);
                    g.setGameOver(GameEndReason.Draw);
                    return;
                }
            } else {
                chosen = "pass";
            }
            System.out.println("[eval] seat " + me.getName() + " turn " + turn0 + " forced: " + chosen
                    + " (horizon " + horizon + ", seed " + seed + ")");
            System.out.flush();
        } catch (Exception e) {
            fail("afterApply: " + e);
            try { g.setGameOver(GameEndReason.Draw); } catch (Exception ignore) { }
        }
    }

    // ── the horizon ──────────────────────────────────────────────────────────

    @Subscribe
    @SuppressWarnings("unused")
    public void onTurnBegan(GameEventTurnBegan ev) {
        if (done || game == null) return;
        try {
            if (game.getPhaseHandler().getTurn() >= turn0 + horizon) {
                finish("horizon");
                game.setGameOver(GameEndReason.Draw);
            }
        } catch (Exception e) {
            fail("horizon: " + e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onOutcome(GameEventGameOutcome ev) {
        if (!done) finish("outcome");
    }

    // ── hidden information ───────────────────────────────────────────────────

    private void determinize(Random rnd) {
        for (Player p : game.getPlayers()) {
            if (p == me) {
                shuffleLibrary(p, rnd);
                continue;
            }
            CardCollection hand = new CardCollection(p.getCardsIn(ZoneType.Hand));
            int n = hand.size();
            for (Card c : hand) {
                game.getAction().moveToLibrary(c, null);
            }
            shuffleLibrary(p, rnd);
            CardCollection lib = new CardCollection(p.getCardsIn(ZoneType.Library));
            for (int i = 0; i < n && i < lib.size(); i++) {
                // moveTo, not drawCards: a redraw is bookkeeping, not a draw,
                // and must not fire "whenever you draw" triggers.
                game.getAction().moveTo(p.getZone(ZoneType.Hand), lib.get(i), null, null);
            }
        }
    }

    private static void shuffleLibrary(Player p, Random rnd) {
        // Shuffle by re-ordering the zone's own list: Player.shuffle() would fire
        // shuffle triggers and use the game's RNG, which the seed does not reach.
        CardCollection lib = new CardCollection(p.getCardsIn(ZoneType.Library));
        List<Card> cards = new ArrayList<>(lib);
        java.util.Collections.shuffle(cards, rnd);
        p.getZone(ZoneType.Library).setCards(cards);
    }

    // ── candidates ───────────────────────────────────────────────────────────

    private List<SpellAbility> candidates() {
        List<SpellAbility> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, me);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(cards, me)) {
            try {
                if (sa == null || sa.isManaAbility()) continue;
                sa.setActivatingPlayer(me);
                if (!sa.canPlay()) continue;
                if (!sa.isLandAbility() && !ComputerUtilMana.canPayManaCost(sa, me, 0, false)) continue;
                String l = label(sa);
                if (seen.contains(l)) continue;      // two copies of one card in hand
                seen.add(l);
                out.add(sa);
            } catch (Exception ignore) {
                // a candidate that cannot even be examined is not a candidate
            }
        }
        return out;
    }

    static String label(SpellAbility sa) {
        Card h = sa.getHostCard();
        String name = h != null ? h.getName() : "?";
        if (sa.isLandAbility()) return name + " : land";
        if (sa.isSpell()) return name + " : cast " + PlayEvents.costKind(sa);
        String d = sa.getDescription();
        d = d == null ? "" : d.replace('\n', ' ').trim();
        if (d.length() > 60) d = d.substring(0, 60);
        return name + " : " + d;
    }

    // ── scoring ──────────────────────────────────────────────────────────────

    /** A plain board score for `p` against the average opponent still in the
     *  game: 10 per life, creatures by Forge's own creature evaluator (about
     *  100-400 each, scaled down to life terms), other permanents by mana value,
     *  4 per card in hand. Terminal states dominate everything. */
    static int score(Game g, Player p) {
        if (p.hasWon()) return 100000;
        if (!p.isInGame()) return -100000;
        double mine = side(p);
        double opp = 0;
        int n = 0;
        for (Player q : g.getPlayers()) {
            if (q == p || !q.isInGame()) continue;
            opp += side(q);
            n++;
        }
        if (n == 0) return 100000;
        return (int) Math.round(mine - opp / n);
    }

    private static double side(Player p) {
        CardCollection creatures = new CardCollection(), others = new CardCollection();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) creatures.add(c); else if (!c.isLand()) others.add(c);
        }
        return p.getLife() * 10.0
                + ComputerUtilCard.evaluateCreatureList(creatures) / 10.0
                + ComputerUtilCard.evaluatePermanentList(others) * 3.0
                + p.getCardsIn(ZoneType.Hand).size() * 4.0
                + p.getCardsIn(ZoneType.Battlefield).size();   // lands and mana sources count a little
    }

    // ── output ───────────────────────────────────────────────────────────────

    private void fail(String why) {
        System.out.println("[eval] FAIL " + why);
        System.out.flush();
        write(new PlayEvents.Json().s("mode", mode).n("seat", seat).s("error", why));
    }

    private void finish(String end) {
        if (done) return;
        done = true;
        try {
            PlayEvents.Json j = new PlayEvents.Json().s("mode", mode).n("seat", seat)
                    .s("seat_name", me != null ? me.getName() : "").n("turn0", turn0)
                    .s("end", end).n("turn", game.getPhaseHandler().getTurn());
            List<String> ls = new ArrayList<>();
            for (String l : labels) ls.add(PlayEvents.Json.q(l));
            j.raw("candidates", "[" + String.join(",", ls) + "]");
            if (!"list".equals(mode)) {
                j.s("chosen", chosen).n("candidate", candidate).n("seed", seed);
                j.b("won", me.hasWon()).b("lost", !me.isInGame() && !me.hasWon());
                j.n("score", score(game, me));
                List<String> life = new ArrayList<>();
                for (Player p : game.getPlayers()) {
                    life.add(PlayEvents.Json.q(p.getName()) + ":" + p.getLife());
                }
                j.raw("life", "{" + String.join(",", life) + "}");
            }
            write(j);
            System.out.println("[eval] " + end + " -> " + out);
            System.out.flush();
        } catch (Exception e) {
            write(new PlayEvents.Json().s("mode", mode).n("seat", seat).s("error", "finish: " + e));
        }
    }

    private void write(PlayEvents.Json j) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out, false), StandardCharsets.UTF_8)) {
            w.write(j.toString());
            w.write('\n');
        } catch (IOException e) {
            System.out.println("[eval] cannot write " + out + ": " + e);
        }
    }
}
