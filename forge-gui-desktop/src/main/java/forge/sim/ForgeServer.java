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
import forge.util.MyRandom;
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
        // -Dbridge.perfmode=false turns it back off, for A/B measurement only.
        Spell.setPerformanceMode(!"false".equals(System.getProperty("bridge.perfmode")));
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

        // How many seats are driven by a human client. Absent means one, which
        // is every duel and every solo pod played until now - so the file being
        // missing has to keep behaving exactly as before.
        int humanSeats = 1;
        File humansFlag = new File(deckDir + "_humans.txt");
        if (humansFlag.exists()) {
            try {
                String raw = new String(java.nio.file.Files.readAllBytes(
                        humansFlag.toPath()), "UTF-8").trim();
                humanSeats = Math.max(1, Math.min(4, Integer.parseInt(raw)));
            } catch (Exception e) {
                humanSeats = 1;   // a malformed flag means the old single-human game
            }
        }
        // One display name per human seat, in seat order. Absent for a solo
        // game, in which case the seat keeps its old "you" label.
        List<String> seatNames = new ArrayList<>();
        File namesFlag = new File(deckDir + "_seat_names.txt");
        if (namesFlag.exists()) {
            try {
                for (String raw : new String(java.nio.file.Files.readAllBytes(
                        namesFlag.toPath()), "UTF-8").split("\n")) {
                    seatNames.add(raw.trim());
                }
            } catch (Exception e) {
                seatNames.clear();   // unreadable: fall back to the old labels
            }
        }

        // _commander.txt is the AI seat count and _humans.txt the human one, so
        // the table is simply the sum - no arithmetic between them. A 4-seat pod
        // with two humans is _humans.txt=2 alongside _commander.txt=2.
        // (Going past 3 humans would need that flag's clamp to allow zero AI;
        // nothing asks for it yet.)
        if (humanSeats + aiSeats < 2) {
            aiSeats = 1;   // never build a one-player game
        }

        File bridgeDeck = new File(deckDir + "_bridge.dck");
        Deck d1 = DeckSerializer.fromFile(bridgeDeck.exists() ? bridgeDeck
                : new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "sliver_shandalar.dck"));

        // Scenario mode: a board is supplied via _scenario.txt, so skip drawing
        // opening hands / mulligan (as puzzle mode does) — otherwise the drawn
        // hands leave the tracker in a state that collides with the board apply.
        boolean scenario = new File(deckDir + "_scenario.txt").exists();

        List<RegisteredPlayer> pp = new ArrayList<>();
        // Human seats come first, so seat index == index in pp. Seat 0 keeps
        // _bridge.dck and the name "you"; further humans read
        // _bridge_human2.dck onward, mirroring the _bridge_opp<N>.dck naming.
        // Kept: PlayerControllerBridge needs each human seat's LobbyPlayer below.
        List<LobbyPlayer> humanLobby = new ArrayList<>();
        for (int i = 0; i < humanSeats; i++) {
            Deck dHuman = d1;
            if (i > 0) {
                File humanFile = new File(deckDir + "_bridge_human" + (i + 1) + ".dck");
                dHuman = DeckSerializer.fromFile(humanFile.exists() ? humanFile
                        : new File(ForgeConstants.DECK_CONSTRUCTED_DIR + "sliver_shandalar.dck"));
            }
            RegisteredPlayer rHuman = commander
                    ? RegisteredPlayer.forCommander(dHuman)
                    : new RegisteredPlayer(dHuman);
            // Seat 0 is "you" in a solo game, which reads naturally when there
            // is nobody else. In a pod that label is only unambiguous from seat
            // 0's own chair, so _seat_names.txt carries the real usernames and
            // everyone is named. Absent, the solo behaviour is unchanged.
            String humanName = i == 0 ? "you" : "Player " + (i + 1);
            if (i < seatNames.size() && !seatNames.get(i).isEmpty()) {
                humanName = seatNames.get(i);
            }
            LobbyPlayer lpHuman = GamePlayerUtil.createAiPlayer(humanName, i, "");
            rHuman.setPlayer(lpHuman);
            humanLobby.add(lpHuman);
            pp.add(rHuman);
            if (scenario) {
                rHuman.setStartingHand(0);
            }
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
            // "Computer" is the duel's name for the sole opponent; once there
            // is more than one human at the table it is always "AI N", so the
            // seat a player is looking at reads unambiguously.
            rOpp.setPlayer(GamePlayerUtil.createAiPlayer(
                    (aiSeats == 1 && humanSeats == 1) ? "Computer" : "AI " + (i + 1),
                    humanSeats + i, ""));
            pp.add(rOpp);
            if (scenario) {
                rOpp.setStartingHand(0);
            }
        }

        // ---- measurement switches; all no-ops unless the property is set ----
        // Two different pods are not a benchmark: board complexity varies more
        // between games than any change we make to the engine, which is how an
        // earlier "is it faster?" reading came out backwards. Seeding makes the
        // SAME game replay, so wall clock finally means something.
        String seed = System.getProperty("bridge.seed");
        if (seed != null && !seed.isEmpty()) {
            MyRandom.setRandom(new java.util.Random(Long.parseLong(seed)));
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
        //
        // The divisor is the AI seat count, not the opponent count: seats driven
        // by a human do no thinking, so sharing the budget with them would starve
        // the seats that actually need it. With no AI at all there is nothing to
        // budget and the guard keeps this off a divide by zero.
        g.AI_TIMEOUT = aiSeats > 0 ? Math.max(2, 5 / aiSeats) : g.AI_TIMEOUT;
        // -Dbridge.notimeout=1: let every AI evaluation run to completion. A
        // timeout cuts the eval thread at a wall-clock moment, so it consumes a
        // different amount of RNG each run and a seeded game still diverges.
        // Without it the replay is deterministic AND the elapsed time is a pure
        // measure of how much work the AI actually does - which is the thing
        // being compared. Never set in production.
        if (System.getProperty("bridge.notimeout") != null) {
            g.AI_CAN_USE_TIMEOUT = false;
        }
        // Every human seat gets its own bridge controller, tagged with its seat
        // index so the client on the other end of the shared Channel knows which
        // player it is being asked about. Seats beyond humanSeats keep the AI
        // controller they were registered with.
        for (int i = 0; i < humanSeats && i < g.getPlayers().size(); i++) {
            Player ph = g.getPlayers().get(i);
            ph.dangerouslySetController(
                    new PlayerControllerBridge(g, ph, humanLobby.get(i), i));
        }
        Player p0 = g.getPlayers().get(0);

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
