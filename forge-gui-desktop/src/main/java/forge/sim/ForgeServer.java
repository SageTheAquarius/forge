package forge.sim;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent, warm Forge match server.
 *
 * Loads the 33k-card DB ONCE (~40s), then listens on 127.0.0.1:bridge.port
 * (default 8781). Each accepted connection is one match: the human seat is the
 * PlayerControllerBridge (driven by the connected Python bridge over Channel),
 * the opponent is Forge AI. Matches start instantly since the DB is already hot.
 *
 * Run headless via:
 *   java -cp "target/test-classes;target/classes;<deps>" forge.sim.ForgeServer
 * (from forge-gui-desktop, so res/ resolves).
 */
public final class ForgeServer {
    private ForgeServer() {}

    public static void main(String[] args) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        int port = Integer.getInteger("bridge.port", 8781);
        ServerSocket ss = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        System.out.println("FORGE_SERVER_READY port=" + port);
        System.out.flush();

        while (true) {
            Socket sock = ss.accept();
            try {
                Channel.bind(sock);
                runOneGame();
            } catch (Throwable t) {   // never let one match kill the server
                t.printStackTrace();
            } finally {
                Channel.close();
                try { sock.close(); } catch (Exception ignore) { }
                System.out.println("MATCH_DONE");
                System.out.flush();
            }
        }
    }

    private static void runOneGame() {
        // Deck dir is env-overridable so it matches the Python side cross-platform.
        String deckDir = System.getenv("BRIDGE_DECK_DIR");
        if (deckDir == null || deckDir.isEmpty()) {
            deckDir = ForgeConstants.DECK_CONSTRUCTED_DIR;
        } else if (!deckDir.endsWith("/") && !deckDir.endsWith("\\")) {
            deckDir = deckDir + "/";
        }
        File bridgeDeck = new File(deckDir + "_bridge.dck");
        File oppDeck = new File(deckDir + "_bridge_opp.dck");
        Deck d1 = DeckSerializer.fromFile(bridgeDeck.exists() ? bridgeDeck
                : new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "sliver_shandalar.dck"));
        Deck d2 = DeckSerializer.fromFile(oppDeck.exists() ? oppDeck
                : new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "frogboss.dck"));

        LobbyPlayer lp1 = GamePlayerUtil.createAiPlayer("you", 0, "");
        LobbyPlayer lp2 = GamePlayerUtil.createAiPlayer("Computer", 1, "");

        // Scenario mode: a board is supplied via _scenario.txt, so skip drawing
        // opening hands / mulligan (as puzzle mode does) — otherwise the drawn
        // hands leave the tracker in a state that collides with the board apply.
        boolean scenario = new File(deckDir + "_scenario.txt").exists();

        List<RegisteredPlayer> pp = new ArrayList<>();
        RegisteredPlayer r1 = new RegisteredPlayer(d1); r1.setPlayer(lp1); pp.add(r1);
        RegisteredPlayer r2 = new RegisteredPlayer(d2); r2.setPlayer(lp2); pp.add(r2);
        if (scenario) {
            r1.setStartingHand(0);
            r2.setStartingHand(0);
        }

        Match mc = new Match(new GameRules(GameType.Constructed), pp, "Forge");
        Game g = mc.createGame();
        Player p0 = g.getPlayers().get(0);
        p0.dangerouslySetController(new PlayerControllerBridge(g, p0, lp1));

        // Test scaffold: if <deckDir>/_scenario.txt exists, apply it as an exact
        // board state at the start of the first turn (puzzle-style game state).
        // Lets end-to-end tests engineer combat/replacement/trigger situations
        // deterministically while the AI still makes its own block decisions.
        // Production never writes this file, so live games are unaffected.
        Runnable hook = buildScenarioHook(g, deckDir);
        mc.startGame(g, hook);
    }

    /** Build a startGameHook that applies deckDir/_scenario.txt, or null if absent. */
    private static Runnable buildScenarioHook(Game g, String deckDir) {
        try {
            File sf = new File(deckDir + "_scenario.txt");
            if (!sf.exists()) {
                return null;
            }
            List<String> lines = java.nio.file.Files.readAllLines(sf.toPath());
            ScenarioState gs = new ScenarioState();
            gs.parse(lines);
            return () -> {
                try {
                    // Apply INLINE on the loop thread. GameState.applyToGame() posts
                    // via game.getAction().invoke() to Forge's registered game thread;
                    // in this headless server the loop runs on a different thread, so
                    // that would run the board apply concurrently with the loop's
                    // checkStateEffects and race (ConcurrentModificationException).
                    gs.applyInline(g);
                    System.out.println("SCENARIO_APPLIED lines=" + lines.size());
                    System.out.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            };
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** GameState that applies on the current thread (no cross-thread invoke). */
    private static final class ScenarioState extends GameState {
        void applyInline(Game game) {
            applyGameOnThread(game);
        }
    }
}
