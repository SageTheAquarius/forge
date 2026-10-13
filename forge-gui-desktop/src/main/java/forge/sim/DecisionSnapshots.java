package forge.sim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventPlayerPriority;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * Board snapshots at decision points, for the offline correctness evaluator.
 *
 * At every main-phase priority window of the active player (human or AI)
 * with an empty stack, the whole game is written in Forge's own puzzle-state
 * format (forge.game.GameState: one pN block per seat, every zone, counters,
 * attachments, mana pool, turn and phase) -- the format the practice sandbox
 * already reloads through ScenarioState, so a snapshot can be put back on a
 * fresh engine and played forward from exactly here. The evaluator does that
 * for each candidate action and scores the difference; nothing of the sort
 * runs on the live box.
 *
 * Output: {@code <deckDir>/_decisions.jsonl}, truncated at game start, one
 * JSON line per snapshot: {"n":seq,"turn":t,"phase":"MAIN1","seat":name,
 * "seat_i":i,"seats":n,"state":"<puzzle text>"}. The relay side copies it
 * next to the game record when the match ends. A matching
 * {@code evt {"e":"window","n":seq,...}} line goes into the game log so the
 * event stream says which window each cast belongs to.
 *
 * Cost: one GameState.initFromGame + toString per qualifying window,
 * deduplicated by content hash per (turn, phase, seat) so a window re-offered
 * after nothing changed writes nothing. Priority windows off the main phases
 * and windows with a stack are skipped (v1: the evaluator scores sorcery-speed
 * choices). Off with {@code -Dbridge.snapshots=off}.
 */
public final class DecisionSnapshots {
    private final Game game;
    private final File file;
    private Writer out;
    private int seq = 0;
    private final Map<String, Integer> lastHash = new HashMap<>();
    private long msSpent = 0;

    public static boolean enabled() {
        return !"off".equals(System.getProperty("bridge.snapshots"));
    }

    public DecisionSnapshots(Game game, String deckDir) {
        this.game = game;
        this.file = new File(deckDir + "_decisions.jsonl");
        try {
            this.out = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[snapshots] cannot open " + file + ": " + e);
            this.out = null;
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onPriority(GameEventPlayerPriority ev) {
        if (out == null || game.isGameOver()) return;
        try {
            PhaseType phase = ev.phase();
            // The attack decision: the active player's beginning-of-combat
            // window in a duel. The board here is the board the attack is
            // declared from; the evaluator re-applies it at
            // COMBAT_DECLARE_ATTACKERS with |Attacking on a candidate set
            // (GameState.handleCombat, which is 1v1 only, hence duels).
            boolean duel = game.getPlayers().size() == 2;
            boolean attackWindow = phase == PhaseType.COMBAT_BEGIN && duel;
            if (ev.priority() == null || ev.turn() == null) return;
            // The block decision: the defender's priority in the declare-attackers
            // step, attackers on the table and no blocks yet. The evaluator
            // re-applies the board at COMBAT_DECLARE_BLOCKERS with |Blocking on a
            // candidate assignment (GameState.handleCombat + the forced-block hook).
            boolean blockWindow = phase == PhaseType.COMBAT_DECLARE_ATTACKERS && duel
                    && !ev.priority().equals(ev.turn())
                    && game.getCombat() != null && !game.getCombat().getAttackers().isEmpty();
            if (phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2 && !attackWindow && !blockWindow) return;
            if (!blockWindow && !ev.priority().equals(ev.turn())) return;   // the active player's own choice
            if (!game.getStack().isEmpty()) return;
            Player p = game.getPlayer(ev.priority());
            if (p == null) return;
            // No creature that could attack / block: no decision to score.
            List<Card> attackers = attackWindow ? legalAttackers(p)
                    : blockWindow ? new ArrayList<>(game.getCombat().getAttackers()) : null;
            List<Card> blockers = blockWindow ? legalBlockers(p) : null;
            if (attackWindow && attackers.isEmpty()) return;
            if (blockWindow && blockers.isEmpty()) return;
            String kind = attackWindow ? "attack" : blockWindow ? "block" : "main";
            long t0 = System.currentTimeMillis();
            GameState gs = new GameState();
            gs.initFromGame(game);
            String state = gs.toString();
            int turn = game.getPhaseHandler().getTurn();
            String key = turn + "/" + phase.name() + "/" + kind + "/" + p.getName();
            Integer prev = lastHash.get(key);
            int h = state.hashCode();
            if (prev != null && prev == h) return;
            lastHash.put(key, h);
            seq++;
            int idx = game.getPlayers().indexOf(p);
            PlayEvents.Json j = new PlayEvents.Json().n("n", seq).n("turn", turn)
                    .s("phase", phase.name()).s("kind", kind).s("seat", p.getName()).n("seat_i", idx)
                    .n("seats", game.getPlayers().size()).s("state", state);
            if (attackers != null) {
                j.raw("attackers", cardRows(attackers));
            }
            if (blockers != null) {
                j.raw("blockers", cardRows(blockers));
            }
            out.write(j.toString());
            out.write('\n');
            out.flush();
            msSpent += System.currentTimeMillis() - t0;
            game.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION,
                    "evt " + new PlayEvents.Json().s("e", "window").n("n", seq).n("turn", turn)
                            .s("phase", phase.name()).s("kind", kind).s("seat", p.getName())));
        } catch (Exception ignore) {
            // a snapshot must never cost a game
        }
    }

    private static String cardRows(List<Card> cards) {
        List<String> rows = new ArrayList<>();
        for (Card c : cards) {
            rows.add(new PlayEvents.Json().s("c", c.getName()).n("id", c.getId()).toString());
        }
        return "[" + String.join(",", rows) + "]";
    }

    /** The player's creatures that could block in the current combat. */
    private List<Card> legalBlockers(Player p) {
        List<Card> out = new ArrayList<>();
        try {
            for (Card c : p.getCreaturesInPlay()) {
                if (CombatUtil.canBlock(c, game.getCombat())) out.add(c);
            }
        } catch (Exception ignore) {
            // a creature the check cannot examine is not a candidate
        }
        return out;
    }

    /** The player's creatures that could be declared as attackers right now. */
    private static List<Card> legalAttackers(Player p) {
        List<Card> out = new ArrayList<>();
        try {
            for (Card c : p.getCreaturesInPlay()) {
                if (CombatUtil.canAttack(c)) out.add(c);
            }
        } catch (Exception ignore) {
            // a creature the check cannot examine is not a candidate
        }
        return out;
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onOutcome(GameEventGameOutcome ev) {
        close();
    }

    public void close() {
        if (out == null) return;
        try {
            out.flush();
            out.close();
        } catch (IOException ignore) {
        }
        out = null;
        System.out.println("[snapshots] " + seq + " decision snapshots in " + msSpent + " ms -> " + file);
        System.out.flush();
    }
}
