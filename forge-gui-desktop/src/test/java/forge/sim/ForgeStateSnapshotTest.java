package forge.sim;

import com.google.common.eventbus.Subscribe;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays a REAL Forge game and writes the busiest mid-game board as neutral JSON
 * (via StateExporter) to forge_bridge/forge_state.json. The Python translator
 * then maps it to EconomyDraft's wire schema for the Godot MatchView to render.
 */
public class ForgeStateSnapshotTest {

    private static final String OUT =
        "C:\\Users\\sagep\\OneDrive\\MTG Project\\forge_bridge\\forge_state.json";

    static class Snap {
        final Game game;
        String best = "";
        Snap(Game g) { this.game = g; }

        @Subscribe
        public void on(GameEvent ev) {
            if (ev instanceof GameEventTurnBegan) {
                String j = StateExporter.toJson(game.getView());
                // Keep the biggest snapshot = the busiest board = best render test.
                if (j.length() > best.length()) best = j;
            }
        }
    }

    @Test(timeOut = 300000)
    public void snapshotRealGame() throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);

        Deck d1 = DeckSerializer.fromFile(new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "bluedragon.dck"));
        Deck d2 = DeckSerializer.fromFile(new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "frogboss.dck"));

        List<RegisteredPlayer> pp = new ArrayList<>();
        RegisteredPlayer r1 = new RegisteredPlayer(d1);
        r1.setPlayer(GamePlayerUtil.createAiPlayer("bluedragon", 0, ""));
        pp.add(r1);
        RegisteredPlayer r2 = new RegisteredPlayer(d2);
        r2.setPlayer(GamePlayerUtil.createAiPlayer("frogboss", 1, ""));
        pp.add(r2);

        Match mc = new Match(new GameRules(GameType.Constructed), pp, "Snapshot");
        Game g = mc.createGame();
        Snap snap = new Snap(g);
        g.subscribeToEvents(snap);
        try {
            mc.startGame(g);
        } catch (Exception | StackOverflowError e) {
            // Even if the game aborts, keep whatever board we captured.
            System.out.println("game ended early: " + e);
        }

        if (snap.best.isEmpty()) {
            snap.best = StateExporter.toJson(g.getView());
        }
        Files.write(Paths.get(OUT), snap.best.getBytes(StandardCharsets.UTF_8));
        System.out.println("WROTE_SNAPSHOT bytes=" + snap.best.length() + " -> " + OUT);
    }
}
