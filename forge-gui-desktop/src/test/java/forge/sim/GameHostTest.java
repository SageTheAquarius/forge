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
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase B1: run a REAL Forge game where the human seat (player 0) is driven by
 * the external bridge (PlayerControllerBridge -> Channel -> Python driver), and
 * the opponent is Forge AI. Proves the bidirectional interactive loop: Forge
 * streams state to the driver at every human priority and blocks for its action.
 *
 * Start the Python driver first (it listens on 127.0.0.1:8781), which launches
 * this test; the game's Channel connects back.
 */
public class GameHostTest {

    @Test(timeOut = 300000)
    public void hostGame() throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);

        // Human seat: use the bridge-supplied deck (_bridge.dck, written by the
        // Python side from the player's drafted/built deck) if present; else a
        // default creature deck.
        File bridgeDeck = new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "_bridge.dck");
        Deck d1 = DeckSerializer.fromFile(bridgeDeck.exists() ? bridgeDeck
                : new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "sliver_shandalar.dck"));
        Deck d2 = DeckSerializer.fromFile(new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "frogboss.dck"));

        LobbyPlayer lp1 = GamePlayerUtil.createAiPlayer("you", 0, "");
        LobbyPlayer lp2 = GamePlayerUtil.createAiPlayer("frogboss", 1, "");

        List<RegisteredPlayer> pp = new ArrayList<>();
        RegisteredPlayer r1 = new RegisteredPlayer(d1); r1.setPlayer(lp1); pp.add(r1);
        RegisteredPlayer r2 = new RegisteredPlayer(d2); r2.setPlayer(lp2); pp.add(r2);

        Match mc = new Match(new GameRules(GameType.Constructed), pp, "Host");
        Game g = mc.createGame();

        // Swap the human seat's controller from AI to the bridge BEFORE the game
        // starts. setFirstController throws if a controller is already assigned
        // (game setup assigned the AI one), so use dangerouslySetController, the
        // same unconditional swap Forge uses for gain-control effects.
        Player p0 = g.getPlayers().get(0);
        p0.dangerouslySetController(new PlayerControllerBridge(g, p0, lp1));

        mc.startGame(g);
        System.out.println("HOST_GAME_DONE outcome=" + g.getOutcome());
        System.out.println("===== GAME LOG =====");
        java.util.List<forge.game.GameLogEntry> log = g.getGameLog().getLogEntries(null);
        java.util.Collections.reverse(log);
        for (forge.game.GameLogEntry e : log) System.out.println(e);
    }
}
