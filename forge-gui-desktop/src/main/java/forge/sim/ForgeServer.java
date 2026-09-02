package forge.sim;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
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

        List<RegisteredPlayer> pp = new ArrayList<>();
        RegisteredPlayer r1 = new RegisteredPlayer(d1); r1.setPlayer(lp1); pp.add(r1);
        RegisteredPlayer r2 = new RegisteredPlayer(d2); r2.setPlayer(lp2); pp.add(r2);

        Match mc = new Match(new GameRules(GameType.Constructed), pp, "Forge");
        Game g = mc.createGame();
        Player p0 = g.getPlayers().get(0);
        p0.dangerouslySetController(new PlayerControllerBridge(g, p0, lp1));
        mc.startGame(g);
    }
}
