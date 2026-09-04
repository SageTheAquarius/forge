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
import forge.game.spellability.Spell;
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

        // Forge's own performance switch, off by default and read by FModel from
        // a desktop preference we never set. Turn it on AFTER initialize(), which
        // is what sets it from that preference.
        //
        // Profiling the AI worker on turn 36+ of a four-player pod (117 stacks):
        // 98% of its time is under predictNextCombatsRemainingLife, and inside
        // that 85% is canGainKeyword -> canPayCost -> canPayManaCost ->
        // groupSourcesByManaColor, which ends in Card.canTap -> cantHappenCheck
        // -> ReplacementHandler.getReplacementList -> forEachCardInGame at 70%.
        // That last call walks EVERY card in every zone of all four players --
        // libraries included, so ~300 of the ~400 cards can never matter -- once
        // per mana source per cost check.
        //
        // ReplacementHandler's fast path skips the zones that cannot hold a live
        // Tap/Untap/ProduceMana replacement, and its comment describes exactly
        // the profile above as "a major hot path". It is gated on this flag
        // purely so custom cards can put one somewhere unusual; no real card
        // does. Setting it also skips an LKI controller-swap copy in
        // Spell.canPlay when the activator is not the controller.
        Spell.setPerformanceMode(true);
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
        // Commander mode is signalled by _commander.txt, written by the Python
        // bridge next to the decks. Its contents are the extra AI seat count
        // (1-3); the file's absence means the ordinary 2-player Constructed game.
        File commanderFlag = new File(deckDir + "_commander.txt");
        boolean commander = commanderFlag.exists();
        int aiSeats = 1;
        if (commander) {
            try {
                String raw = new String(java.nio.file.Files.readAllBytes(
                        commanderFlag.toPath()), "UTF-8").trim();
                aiSeats = Math.max(1, Math.min(3, Integer.parseInt(raw)));
            } catch (Exception e) {
                aiSeats = 3;   // a malformed flag still means "a pod", not a duel
            }
        }

        File bridgeDeck = new File(deckDir + "_bridge.dck");
        Deck d1 = DeckSerializer.fromFile(bridgeDeck.exists() ? bridgeDeck
                : new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "sliver_shandalar.dck"));

        // Scenario mode: a board is supplied via _scenario.txt, so skip drawing
        // opening hands / mulligan (as puzzle mode does) — otherwise the drawn
        // hands leave the tracker in a state that collides with the board apply.
        boolean scenario = new File(deckDir + "_scenario.txt").exists();

        List<RegisteredPlayer> pp = new ArrayList<>();
        RegisteredPlayer r1 = commander
                ? RegisteredPlayer.forCommander(d1)
                : new RegisteredPlayer(d1);
        // Kept: PlayerControllerBridge needs the human seat's LobbyPlayer below.
        LobbyPlayer lp1 = GamePlayerUtil.createAiPlayer("you", 0, "");
        r1.setPlayer(lp1);
        pp.add(r1);
        if (scenario) {
            r1.setStartingHand(0);
        }

        // Opponent seats. The duel reads _bridge_opp.dck as it always has; a pod
        // reads _bridge_opp.dck, _bridge_opp2.dck, _bridge_opp3.dck in turn.
        for (int i = 0; i < aiSeats; i++) {
            File oppFile = new File(deckDir + (i == 0
                    ? "_bridge_opp.dck" : "_bridge_opp" + (i + 1) + ".dck"));
            Deck dOpp = DeckSerializer.fromFile(oppFile.exists() ? oppFile
                    : new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "frogboss.dck"));
            RegisteredPlayer rOpp = commander
                    ? RegisteredPlayer.forCommander(dOpp)
                    : new RegisteredPlayer(dOpp);
            rOpp.setPlayer(GamePlayerUtil.createAiPlayer(
                    aiSeats == 1 ? "Computer" : "AI " + (i + 1), i + 1, ""));
            pp.add(rOpp);
            if (scenario) {
                rOpp.setStartingHand(0);
            }
        }

        GameRules rules = new GameRules(commander
                ? GameType.Commander : GameType.Constructed);
        if (commander) {
            // new GameRules(GameType.Commander) sets only `gameType`; the set
            // behind hasAppliedVariant() stays empty. Several Commander rules
            // are gated on the VARIANT, not the game type - most visibly
            // GameAction.stateBasedAction_Commander (CR 903.9a), which is what
            // asks "put your commander into the command zone instead?". Without
            // this the commander went to the graveyard and the player was never
            // offered the choice at all.
            rules.addAppliedVariant(GameType.Commander);
        }
        // EconomyDraft house rule: the first mulligan is free (London mulligan
        // with no card put on the bottom). Applies to both seats, so the AI gets
        // it too. Second and later mulligans tuck as normal (1, 2, 3, ...).
        rules.setFirstMulliganFree(true);

        Match mc = new Match(rules, pp, "Forge");
        Game g = mc.createGame();

        // Forge's 5s AI_TIMEOUT is a budget PER AI DECISION, and it was chosen
        // for a duel, where one AI seat decides between the human's windows. A
        // pod has three, so a single game step can cost 3 x 5s before the human
        // is asked anything -- measured at 13.5s per window on turn 25 of a live
        // four-player game, against 0.2s on turn 5. The cost is Forge's own
        // multiplayer attack logic (AiAttackController.choosePreferredDefenderPlayer
        // runs a full block-assignment prediction per opponent, and only ever
        // does so when there IS more than one opponent), and it grows with the
        // board, so late Commander turns are exactly where it bites.
        //
        // Share the duel's budget across the seats instead of handing it to each
        // of them, which keeps a game STEP at roughly the pace Forge intended.
        // Timing out is not a failure mode: AiController returns null and that
        // seat simply plays nothing that window, which is already what happened
        // repeatedly in the log this came from.
        g.AI_TIMEOUT = Math.max(2, 5 / aiSeats);
        Player p0 = g.getPlayers().get(0);
        p0.dangerouslySetController(new PlayerControllerBridge(g, p0, lp1));

        // Test scaffold: if <deckDir>/_scenario.txt exists, apply it as an exact
        // board state at the start of the first turn (puzzle-style game state).
        // Lets end-to-end tests engineer combat/replacement/trigger situations
        // deterministically while the AI still makes its own block decisions.
        // Production never writes this file, so live games are unaffected.
        Runnable hook = buildScenarioHook(g, deckDir);
        mc.startGame(g, hook);

        // The outcome lines ("<player> has lost the game", the match summary) are
        // logged AFTER the last decision point, so no state export has carried
        // them yet. One final push so the player sees how the game ended.
        Channel.request("{\"kind\":\"game_over\",\"state\":"
                + StateExporter.toJson(g.getView(), p0) + "}");
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
