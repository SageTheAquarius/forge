package forge.sim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameState;
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
            if (phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2) return;
            if (ev.priority() == null || ev.turn() == null) return;
            if (!ev.priority().equals(ev.turn())) return;      // the active player's own choice
            if (!game.getStack().isEmpty()) return;
            Player p = game.getPlayer(ev.priority());
            if (p == null) return;
            long t0 = System.currentTimeMillis();
            GameState gs = new GameState();
            gs.initFromGame(game);
            String state = gs.toString();
            int turn = game.getPhaseHandler().getTurn();
            String key = turn + "/" + phase.name() + "/" + p.getName();
            Integer prev = lastHash.get(key);
            int h = state.hashCode();
            if (prev != null && prev == h) return;
            lastHash.put(key, h);
            seq++;
            int idx = game.getPlayers().indexOf(p);
            PlayEvents.Json j = new PlayEvents.Json().n("n", seq).n("turn", turn)
                    .s("phase", phase.name()).s("seat", p.getName()).n("seat_i", idx)
                    .n("seats", game.getPlayers().size()).s("state", state);
            out.write(j.toString());
            out.write('\n');
            out.flush();
            msSpent += System.currentTimeMillis() - t0;
            game.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION,
                    "evt " + new PlayEvents.Json().s("e", "window").n("n", seq).n("turn", turn)
                            .s("phase", phase.name()).s("seat", p.getName())));
        } catch (Exception ignore) {
            // a snapshot must never cost a game
        }
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
