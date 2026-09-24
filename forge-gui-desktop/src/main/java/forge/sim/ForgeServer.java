package forge.sim;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.Spell;
import forge.util.MyRandom;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import com.google.common.eventbus.Subscribe;

import java.io.File;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.card.GamePieceType;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;

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
        // The match runs on this thread, so it must BE the game thread as far
        // as the engine is concerned: GameAction.invoke() runs its Runnable
        // inline when Thread.getName() starts with "Game" (ThreadUtil.isGameThread)
        // and otherwise posts it to a worker pool. Under the old name ("main")
        // every such call ran concurrently with the loop -- GameState's
        // scenario mana seeding (produceMana via invoke) raced the first state
        // export and lost whenever start-of-game timing shifted (the [JVM-CPU]
        // baseline's JMX init did it, 2026-09-16: test_scenario_colorless_mana_pool
        // read an empty pool under the 3-engine suite, never alone).
        Thread.currentThread().setName("Game loop (bridge)");
        installStampedStdout();
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
                // 0 is allowed: a Practice game is two bridged seats (the
                // player and the Dummy) with no AI at all. The "never a
                // one-player game" guard below still forces one AI seat when
                // there is only one human.
                aiSeats = Math.max(0, Math.min(3, Integer.parseInt(raw)));
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

        // AI temper, from the Commander editor's Temperament picker: 0 (or the
        // file absent) = stock Forge, 1 = mild, 2 = volatile. See forge.ai.AiMood.
        int moodLevel = 0;
        File moodFlag = new File(deckDir + "_ai_mood.txt");
        if (moodFlag.exists()) {
            try {
                String raw = new String(java.nio.file.Files.readAllBytes(
                        moodFlag.toPath()), "UTF-8").trim();
                moodLevel = Math.max(0, Math.min(2, Integer.parseInt(raw)));
            } catch (Exception e) {
                moodLevel = 0;   // unreadable: stock behaviour, never a surprise
            }
        }

        // AI profile per AI seat (forge-gui/res/ai/<name>.ai: Default,
        // Reckless, Cautious, Experimental), one line per seat in seat order,
        // from _ai_profile.txt. The relay writes it from the draft's difficulty
        // or deals a shuffled rotation to a pod; absent, or a line that names
        // no shipped profile, means "" -- Forge's own default, as before.
        List<String> seatProfiles = new ArrayList<>();
        File profileFlag = new File(deckDir + "_ai_profile.txt");
        if (profileFlag.exists()) {
            try {
                List<String> known = forge.ai.AiProfileUtil.getAvailableProfiles();
                for (String raw : new String(java.nio.file.Files.readAllBytes(
                        profileFlag.toPath()), "UTF-8").split("\n")) {
                    String p = raw.trim();
                    if (!p.isEmpty() && !known.contains(p)) {
                        System.out.println("[AI-PROFILE] unknown profile " + p + " (have " + known + "); using default");
                        p = "";
                    }
                    seatProfiles.add(p);
                }
            } catch (Exception e) {
                seatProfiles.clear();   // unreadable: stock behaviour
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
        // Practice game: _sandbox.txt (written by the relay for that mode and
        // removed after) lets PlayerControllerBridge honour `sandbox` cheat
        // replies. Read per game, so a flag cannot linger into the next one.
        PlayerControllerBridge.setSandbox(new File(deckDir + "_sandbox.txt").exists());

        // Lightning Round (and any future house format): _format.txt next to the
        // decks carries `key=value` lines. `life` and `hand` override what the
        // variant would give every seat; the format's own rules live in a
        // Vanguard-type card listed in each .dck's [Avatar] section, which
        // Player.initVariantsZones puts into the command zone like any avatar.
        // Absent, every seat plays exactly as before.
        LightningFormat format = LightningFormat.read(new File(deckDir + "_format.txt"));

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
            format.apply(rHuman, dHuman);
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
            String seatName = (aiSeats == 1 && humanSeats == 1) ? "Computer" : "AI " + (i + 1);
            String profile = i < seatProfiles.size() ? seatProfiles.get(i) : "";
            // Forge's simulation-based spell picker (SpellAbilityPicker /
            // GameSimulator), off in every game until 2026-09-23. It is a
            // per-decision cost with no timeout of its own, so it is opt-in
            // per table shape: -Dbridge.duelsim=full|hybrid|off for the one
            // AI of a duel, -Dbridge.podsim=hybrid|off for a pod's seats.
            // The measured defaults are in the constants below.
            // Constructed tables only: GameSimulator's GameCopier throws
            // "Couldn't map Commander Effect" from copyCommandersToSnapshot
            // on any Commander game (a Lightning duel included), which ended
            // test_lightning_no_deckout on turn 2 with the AI seat gone.
            java.util.Set<forge.ai.AIOption> simOptions = commander ? null : simOptionsFor(
                    aiSeats == 1 && humanSeats == 1 ? DUEL_SIM
                            : "full".equals(POD_SIM) ? "hybrid" : POD_SIM);
            if (commander && i == 0 && (simOptionsFor(DUEL_SIM) != null || simOptionsFor(POD_SIM) != null)) {
                System.out.println("[AI-PROFILE] simulation AI skipped: Commander table (GameCopier cannot map the Commander Effect)");
            }
            LobbyPlayer lpOpp = simOptions == null
                    ? GamePlayerUtil.createAiPlayer(seatName, humanSeats + i, profile)
                    : GamePlayerUtil.createAiPlayer(seatName, humanSeats + i, 0, simOptions, profile);
            if (!profile.isEmpty() || simOptions != null) {
                System.out.println("[AI-PROFILE] " + seatName + ": " + (profile.isEmpty() ? "default" : profile)
                        + (simOptions == null ? "" : " sim=" + simOptions.iterator().next()));
            }
            rOpp.setPlayer(lpOpp);
            pp.add(rOpp);
            format.apply(rOpp, dOpp);
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
        forge.ai.AiPerf.reset();
        forge.ai.AiMood.resetAll();

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
        // ...but not from turn 1. The division was measured on turn 25 of a
        // live pod; on turn 5 the same window cost 0.2s, and a 1-2s budget
        // there only makes the AI misplay its opening (a timed-out evaluation
        // plays nothing) for no pace gain. So a pod keeps the duel's 5s while
        // the game is young -- before turn EARLY_TIMEOUT_TURN or while fewer
        // than EARLY_TIMEOUT_PERMANENTS are on the battlefield -- and the
        // per-seat share only applies once the board is big enough to need it.
        // Re-evaluated at every turn start (GameEventTurnBegan, as AiMood does).
        if (aiSeats > 1 && EARLY_TIMEOUT_TURN > 0) {
            EarlyTimeout early = new EarlyTimeout(g, g.AI_TIMEOUT);
            early.apply(1);
            g.subscribeToEvents(early);
        }
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
        // Give every AI-driven seat its temper. Human seats were just handed a
        // bridge controller, so attach() finds no AiController there and does
        // nothing; with moodLevel 0 it is a no-op everywhere.
        if (moodLevel > 0) {
            for (int i = humanSeats; i < g.getPlayers().size(); i++) {
                forge.ai.AiMood.attach(g.getPlayers().get(i), moodLevel);
            }
            System.out.println("[MOOD] level " + moodLevel + " on "
                    + Math.max(0, g.getPlayers().size() - humanSeats) + " AI seat(s)");
        }
        Player p0 = g.getPlayers().get(0);

        // A human seat that has lost is never asked for priority again, so the
        // bridge has no control point left to push from -- but in a POD the
        // surviving AI seats keep playing each other out for many more turns.
        // Measured in prod 2026-09-08: the player died to combat damage on
        // turn 49, the three AI seats then played through turn 59, and for 59
        // seconds the client received nothing at all. It sat frozen on the
        // last combat board, then took the entire backlog in one flush when
        // the pod finally resolved. The engine was not stuck -- 69.2s engine
        // vs 0.1s bridge on that turn -- it was simply finishing a game the
        // player was no longer in.
        //
        // GameEndReason.AllHumansLost is Forge's own answer to this, the same
        // reason PlayerControllerBridge.endIfAbandoned() uses when the socket
        // dies: "used to end multiplayer games where all humans have lost or
        // conceded while AIs cannot end match by themselves".
        if (!"off".equals(System.getProperty("bridge.deathwatch"))) {
            g.subscribeToEvents(new HumanDeathWatch(g, humanSeats));
        }
        // What each stack item became (resolved / fizzled / countered), for
        // the client's effects layer -- see StateExporter.StackOutcomes.
        g.subscribeToEvents(StateExporter.newStackOutcomes());
        // Structured play events ("evt {...}" game-log lines) for the post-game
        // quality report -- which cost a spell was cast for, fizzles, targets,
        // what was left in hand with mana up. See PlayEvents; -Dbridge.playevents=off.
        if (PlayEvents.enabled()) {
            g.subscribeToEvents(new PlayEvents(g));
        }
        // Board snapshots at main-phase decision points, for the offline
        // evaluator (<deckDir>/_decisions.jsonl). See DecisionSnapshots.
        if (DecisionSnapshots.enabled()) {
            g.subscribeToEvents(new DecisionSnapshots(g, deckDir));
        }

        // Test scaffold: if <deckDir>/_scenario.txt exists, apply it as an exact
        // board state at the start of the first turn (puzzle-style game state).
        // Lets end-to-end tests engineer combat/replacement/trigger situations
        // deterministically while the AI still makes its own block decisions.
        // Production never writes this file, so live games are unaffected.
        Runnable scenarioHook = buildScenarioHook(g, deckDir);
        // Lightning Round's per-seat upkeep-lands card goes into the command
        // zone at the same moment a scenario board would: turn 1's untap step,
        // on the game thread, before the first upkeep can fire it.
        final Runnable hook;
        if (format.hasManaCards()) {
            hook = () -> {
                if (scenarioHook != null) scenarioHook.run();
                format.addManaCards(g);
            };
        } else {
            hook = scenarioHook;
        }
        // Where the game thread actually is, sampled every 250ms and printed
        // per turn as [GAME-THREAD] - the pace line's counters only cover the
        // code that was instrumented, and v130's turn 49 had 78s of 140s that
        // none of them named. -Dbridge.samplems=0 turns it off.
        GameThreadSampler sampler = new GameThreadSampler(g, Thread.currentThread());
        sampler.start();
        try {
            mc.startGame(g, hook);
        } finally {
            sampler.shutdown();
        }

        // The outcome lines ("<player> has lost the game", the match summary) are
        // logged AFTER the last decision point, so no state export has carried
        // them yet. One final push so the player sees how the game ended.
        Channel.request("{\"kind\":\"game_over\",\"state\":"
                + StateExporter.toJson(g.getView(), p0) + "}");
    }

    /**
     * Samples the game thread's stack and prints, per turn, a histogram keyed
     * by "<what the phase loop called> > <outermost forge.ai frame> @ <innermost
     * forge frame>". Samples sitting in the bridge socket (waiting on a human)
     * are counted separately, not as engine time.
     */
    static final class GameThreadSampler extends Thread {
        private final Game game;
        private final Thread target;
        private volatile boolean on = true;
        private int turn = -1;
        private final java.util.Map<String, Integer> hist = new java.util.HashMap<>();
        // Coarser buckets ("<entry> > <subsystem>") - the fine keys spread a
        // slow turn over dozens of leaves (v132, turn 55: the top 8 summed to
        // 55% and named no caller) - plus the first full stack seen per bucket,
        // printed for the top bucket of a slow turn so the log names the
        // chain, not just the leaf.
        private final java.util.Map<String, Integer> coarse = new java.util.HashMap<>();
        private final java.util.Map<String, String> stacks = new java.util.HashMap<>();
        private int samples;
        private int waiting;
        // [JVM-CPU]: where the turn's wall time went, per turn -- the game
        // thread's CPU, the whole process's CPU, and the host's CPU modes from
        // /proc/stat. The 2026-09-15 prod Lightning cliff (turns 23-24 at 31 s
        // and 58 s) read as an engine cost in the stacks above and was the
        // host stealing two thirds of the CPU; this line says so directly:
        // wall >> process CPU with a high steal share is the host, process
        // CPU >> game-thread CPU is JIT / GC / eval threads.
        private long cpuWall = System.nanoTime();
        private long cpuProc = processCpu();
        private long cpuGame = -1;   // set once target is known, below
        private long[] cpuHost = hostCpu();

        GameThreadSampler(Game g, Thread t) {
            super("Game Thread Sampler");
            game = g;
            target = t;
            cpuGame = threadCpu();
            setDaemon(true);
        }

        void shutdown() {
            on = false;
            interrupt();
            flush();
        }

        @Override
        public void run() {
            long every = Long.getLong("bridge.samplems", 250L);
            if (every <= 0) {
                return;
            }
            while (on) {
                try {
                    Thread.sleep(every);
                } catch (InterruptedException e) {
                    return;
                }
                int t;
                try {
                    t = game.getPhaseHandler().getTurn();
                } catch (Exception e) {
                    continue;
                }
                if (t != turn) {
                    flush();
                    turn = t;
                }
                StackTraceElement[] st = target.getStackTrace();
                String key = classify(st);
                synchronized (this) {
                    if (key == null) {
                        waiting++;
                    } else {
                        samples++;
                        hist.merge(key, 1, Integer::sum);
                        String ck = classifyCoarse(st);
                        coarse.merge(ck, 1, Integer::sum);
                        if (!stacks.containsKey(ck)) {
                            stacks.put(ck, forgeFrames(st));
                        }
                    }
                }
            }
        }

        /** Packages whose OUTERMOST frame names the subsystem a busy game thread is in. */
        private static final String[] SUBSYSTEMS = {
            "forge.ai.", "forge.game.staticability.", "forge.game.trigger.", "forge.game.replacement.",
            "forge.game.ability.effects.", "forge.game.ability.AbilityUtils", "forge.game.GameAction",
            "forge.game.card.CardLists", "forge.game.card.CardCopyService", "forge.game.cost.",
            "forge.game.combat.", "forge.sim.StateExporter",
        };

        /** "<entry> > <outermost subsystem frame>", or "<entry> > <innermost forge class>" when no subsystem is on the stack. */
        static String classifyCoarse(StackTraceElement[] st) {
            int loop = -1;
            for (int i = 0; i < st.length; i++) {
                String m = st[i].getMethodName();
                if ("mainLoopStep".equals(m) || "mainGameLoop".equals(m)) {
                    loop = i;
                    break;
                }
            }
            int end = loop < 0 ? st.length : loop;
            String entry = loop > 0 ? shortName(st[loop - 1].getClassName()) + "." + st[loop - 1].getMethodName()
                                    : "outside-loop";
            String sub = null;
            for (int i = end - 1; i >= 0 && sub == null; i--) {
                String c = st[i].getClassName();
                for (String p : SUBSYSTEMS) {
                    if (c.startsWith(p)) {
                        sub = shortName(c) + "." + st[i].getMethodName();
                        break;
                    }
                }
            }
            if (sub == null) {
                for (int i = 0; i < end; i++) {
                    if (st[i].getClassName().startsWith("forge.")) {
                        sub = shortName(st[i].getClassName());
                        break;
                    }
                }
            }
            return sub == null ? entry : entry + " > " + sub;
        }

        /** The forge.* frames of a sample, innermost first, as one line. */
        private static String forgeFrames(StackTraceElement[] st) {
            StringBuilder b = new StringBuilder(512);
            int n = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (!c.startsWith("forge.")) {
                    continue;
                }
                if (n++ > 0) {
                    b.append(" < ");
                }
                b.append(shortName(c)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                if ("mainLoopStep".equals(e.getMethodName()) || n >= 28) {
                    break;
                }
            }
            return b.toString();
        }

        static String classify(StackTraceElement[] st) {
            if (st.length == 0) {
                return null;
            }
            int loop = -1;
            for (int i = 0; i < st.length; i++) {
                String m = st[i].getMethodName();
                if ("mainLoopStep".equals(m) || "mainGameLoop".equals(m)) {
                    loop = i;
                    break;
                }
            }
            for (int i = 0; i < (loop < 0 ? st.length : loop); i++) {
                String c = st[i].getClassName();
                if (c.startsWith("forge.sim.Channel")) {
                    return null;      // waiting on the bridge / the human
                }
            }
            String top = null;
            String ai = null;
            int end = loop < 0 ? st.length : loop;
            for (int i = 0; i < end; i++) {
                String c = st[i].getClassName();
                if (top == null && c.startsWith("forge.")) {
                    top = shortName(c) + "." + st[i].getMethodName();
                }
                if (c.startsWith("forge.ai.")) {
                    ai = shortName(c) + "." + st[i].getMethodName();   // last one wins = outermost
                }
            }
            String entry = loop > 0 ? shortName(st[loop - 1].getClassName()) + "." + st[loop - 1].getMethodName()
                                    : "outside-loop";
            StringBuilder b = new StringBuilder(entry);
            if (ai != null) {
                b.append(" > ").append(ai);
            }
            if (top != null) {
                b.append(" @ ").append(top);
            }
            return b.toString();
        }

        private static String shortName(String cls) {
            int i = cls.lastIndexOf('.');
            String s = i < 0 ? cls : cls.substring(i + 1);
            int d = s.indexOf('$');
            return d < 0 ? s : s.substring(0, d);
        }

        private long processCpu() {
            try {
                java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (os instanceof com.sun.management.OperatingSystemMXBean) {
                    return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
                }
            } catch (Throwable ignored) { }
            return -1;
        }

        private long threadCpu() {
            try {
                java.lang.management.ThreadMXBean tm = java.lang.management.ManagementFactory.getThreadMXBean();
                return tm.isThreadCpuTimeSupported() ? tm.getThreadCpuTime(target.getId()) : -1;
            } catch (Throwable ignored) {
                return -1;
            }
        }

        /** user, nice, system, idle, iowait, irq, softirq, steal jiffies from /proc/stat; null off Linux. */
        private static long[] hostCpu() {
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/stat"));
                for (String l : lines) {
                    if (l.startsWith("cpu ")) {
                        String[] f = l.trim().split("\\s+");
                        long[] v = new long[8];
                        for (int i = 0; i < 8 && i + 1 < f.length; i++) {
                            v[i] = Long.parseLong(f[i + 1]);
                        }
                        return v;
                    }
                }
            } catch (Throwable ignored) { }
            return null;
        }

        private void cpuLine() {
            long wall = System.nanoTime();
            long proc = processCpu();
            long game = threadCpu();
            long[] host = hostCpu();
            StringBuilder sb = new StringBuilder("[JVM-CPU] turn " + turn + ": wall " + fmt(wall - cpuWall));
            if (proc >= 0 && cpuProc >= 0) {
                sb.append("; process cpu ").append(fmt(proc - cpuProc));
            }
            if (game >= 0 && cpuGame >= 0) {
                sb.append(" (game thread ").append(fmt(game - cpuGame)).append(')');
            }
            if (host != null && cpuHost != null) {
                long total = 0;
                for (int i = 0; i < 8; i++) {
                    total += host[i] - cpuHost[i];
                }
                if (total > 0) {
                    sb.append("; host user ").append(100 * (host[0] - cpuHost[0]) / total)
                      .append("% sys ").append(100 * (host[2] - cpuHost[2]) / total)
                      .append("% steal ").append(100 * (host[7] - cpuHost[7]) / total)
                      .append("% idle ").append(100 * (host[3] - cpuHost[3]) / total)
                      .append("% of ").append(Runtime.getRuntime().availableProcessors()).append(" cpus");
                }
            }
            System.out.println(sb);
            cpuWall = wall;
            cpuProc = proc;
            cpuGame = game;
            cpuHost = host;
        }

        private static String fmt(long nanos) {
            return String.format("%.1fs", nanos / 1e9);
        }

        private synchronized void flush() {
            if (turn < 0) {
                return;
            }
            cpuLine();
            if (samples + waiting == 0) {
                return;
            }
            java.util.List<java.util.Map.Entry<String, Integer>> es = new java.util.ArrayList<>(hist.entrySet());
            es.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder("[GAME-THREAD] turn " + turn + ": " + samples
                    + " busy samples, " + waiting + " waiting on the bridge:");
            int shown = 0;
            for (java.util.Map.Entry<String, Integer> en : es) {
                if (shown++ >= 8) {
                    break;
                }
                sb.append(' ').append(100 * en.getValue() / Math.max(1, samples)).append("% ")
                  .append(en.getKey()).append(';');
            }
            System.out.println(sb);
            java.util.List<java.util.Map.Entry<String, Integer>> cs = new java.util.ArrayList<>(coarse.entrySet());
            cs.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder cb = new StringBuilder("[GAME-THREAD] turn " + turn + " coarse:");
            shown = 0;
            for (java.util.Map.Entry<String, Integer> en : cs) {
                if (shown++ >= 8) {
                    break;
                }
                cb.append(' ').append(100 * en.getValue() / Math.max(1, samples)).append("% ")
                  .append(en.getKey()).append(';');
            }
            System.out.println(cb);
            // A slow turn (10s+ busy at the default 250ms) also prints the first
            // stack seen in its top bucket, so the caller chain is in the log.
            if (samples >= 40 && !cs.isEmpty()) {
                String top = cs.get(0).getKey();
                System.out.println("[GAME-THREAD] turn " + turn + " stack (" + top + "): " + stacks.get(top));
            }
            hist.clear();
            coarse.clear();
            stacks.clear();
            samples = 0;
            waiting = 0;
        }
    }

    /**
     * Ends the match the moment every human seat has lost, so the client is
     * told it lost instead of waiting out an AI-only pod. See the subscribe
     * call in startMatch for the prod trace that motivated this.
     *
     * Subscribes on the GameEvent base class: Guava's EventBus dispatches to
     * handlers of every supertype of the posted event, so this sees all of
     * them and reacts at whichever one fires first after the lethal
     * state-based action -- no guessing which event a given loss produces.
     */
    private static final class HumanDeathWatch {
        private final Game game;
        private final int humanSeats;
        private boolean fired;

        HumanDeathWatch(Game game, int humanSeats) {
            this.game = game;
            this.humanSeats = humanSeats;
        }

        @Subscribe
        @SuppressWarnings("unused")
        public void onGameEvent(GameEvent ev) {
            // humanSeats == 0 is an AI-only sim (the benchmark harness). The
            // loop below would vacuously "find every human lost" and kill the
            // game on its first event, so bail before it runs.
            if (fired || humanSeats <= 0 || game.isGameOver()) {
                return;
            }
            List<Player> players = game.getPlayers();
            for (int i = 0; i < humanSeats && i < players.size(); i++) {
                if (!players.get(i).hasLost()) {
                    return;
                }
            }
            // Latch before setGameOver: that fires GameEventGameFinished,
            // which re-enters this handler on the same thread.
            fired = true;
            game.setGameOver(GameEndReason.AllHumansLost);
        }
    }

    /** The scenario this game started from, kept for the Practice game's Restart. */
    private static ScenarioState lastScenario;

    /**
     * Re-apply this game's starting scenario (the Practice game's "Restart").
     * Runs on the calling thread, which is the game loop's when it comes from
     * a priority window -- the same reason the start hook applies inline.
     */
    static boolean reapplyScenario(Game g) {
        ScenarioState gs = lastScenario;
        if (gs == null) {
            return false;
        }
        gs.applyInline(g);
        return true;
    }

    /**
     * -Dbridge.earlytimeout=<turn>: a pod keeps the duel's 5s AI_TIMEOUT
     * before this turn (and while the board is small), default 15. 0 turns
     * the lever off: the per-seat share applies from turn 1 as it did before.
     */
    static final int EARLY_TIMEOUT_TURN = Integer.getInteger("bridge.earlytimeout", 15);
    /** ...or while fewer permanents than this are on the battlefield, whatever the turn. */
    static final int EARLY_TIMEOUT_PERMANENTS = 25;

    /**
     * -Dbridge.duelsim=full|hybrid|off: the simulation picker for the single
     * AI of a duel. -Dbridge.podsim=hybrid|off: the same for a pod's seats
     * (full is refused there: three seats simulating is the late-game lag
     * this whole file exists to avoid). Defaults are what the 2026-09-23
     * measurement supported (seeded 40-card duel, fresh JVM per arm, two
     * seeds, -Dbridge.decisionlog=1): off avg 6-8ms/decision; hybrid avg
     * 20-62ms, p95 135-245ms, max 0.8s, engine time 1.7-3.5x; full ran
     * 6-8.6s single decisions and threw OutOfMemoryError at -Xmx1500m. So
     * the duel defaults to hybrid, full is opt-in only, and pods stay off.
     */
    static final String DUEL_SIM = System.getProperty("bridge.duelsim", "hybrid").trim().toLowerCase();
    static final String POD_SIM = System.getProperty("bridge.podsim", "off").trim().toLowerCase();

    static java.util.Set<forge.ai.AIOption> simOptionsFor(String mode) {
        if ("full".equals(mode)) {
            return java.util.EnumSet.of(forge.ai.AIOption.USE_FULL_SIMULATION);
        }
        if ("hybrid".equals(mode)) {
            return java.util.EnumSet.of(forge.ai.AIOption.USE_HYBRID_SIMULATION);
        }
        return null;
    }

    /**
     * Sets g.AI_TIMEOUT at every turn start: the duel's 5s while the game is
     * young, the shared per-seat budget once it is not. Subscribed to the
     * game's event bus, so it runs on the game thread between decisions.
     */
    static final class EarlyTimeout {
        private final Game g;
        private final int shared;
        private int current = -1;

        EarlyTimeout(Game g, int shared) {
            this.g = g;
            this.shared = shared;
        }

        @Subscribe
        public void onTurnBegan(GameEventTurnBegan ev) {
            try {
                apply(ev.turnNumber());
            } catch (Exception ignore) {
                // never let a pacing knob take the game thread down
            }
        }

        void apply(int turn) {
            int permanents = g.getCardsIn(ZoneType.Battlefield).size();
            boolean early = turn < EARLY_TIMEOUT_TURN || permanents < EARLY_TIMEOUT_PERMANENTS;
            int want = early ? 5 : shared;
            if (want == current) {
                return;
            }
            current = want;
            g.AI_TIMEOUT = want;
            System.out.println("[AI-TIMEOUT] turn " + turn + ", " + permanents
                    + " permanents: " + want + "s per decision" + (early ? " (early game)" : " (shared)"));
        }
    }

    /**
     * A house format's per-seat overrides, read from deckDir/_format.txt.
     *
     * The relay writes the file for a Lightning Round game (20-card Commander:
     * 12 life, a 5-card hand, no lands, mana from Command Crystals) and removes
     * it for every other game, so an absent or empty file is the ordinary
     * variant. Only the two numbers Forge keeps on RegisteredPlayer live here;
     * the format's triggered rules (the upkeep crystal, wounds becoming
     * crystals) are the Vanguard-type card "Lightning Round Rules" that the
     * .dck lists under [Avatar]. RegisteredPlayer.assignVanguardAvatar reads
     * that section and Player.initVariantsZones puts the card in the command
     * zone, so nothing here has to know what the rules are - only that the
     * seat has them.
     *
     * apply() runs AFTER forCommander() on purpose: forCommander sets 40 life
     * and the avatar's own HandLifeModifier is +0/+0, so the explicit numbers
     * are the last word.
     */
    static final class LightningFormat {
        final int life;      // <= 0 means "leave the variant's value alone"
        final int hand;      // <= 0 likewise
        final boolean avatar;
        // seat index (ForgeServer's pp order: humans first, then AI) -> the
        // colours that seat's upkeep lands may be, as WUBRG letters ("WR"), or
        // "C" for a colourless identity. Absent seats get no mana card at all.
        final Map<Integer, String> seatColors;
        // seat index -> the colour the seat asked to draw FIRST (firstN= in
        // _format.txt), or absent for a random start. Only the first bag
        // cycle is pinned; the bag is still every colour once per cycle.
        final Map<Integer, Character> seatFirst;

        // Upkeep lands are drawn from a shuffled bag of the seat's colours,
        // refilled when empty, so every colour arrives once per cycle and a
        // run of three Mountains with a hand of white cards cannot happen.
        // The whole sequence is drawn up front (with MyRandom, so a seeded
        // game replays) and written into the card as one conditional chain
        // keyed on an upkeep counter; MANA_TURNS is how deep that chain goes.
        // Past it the seat falls back to an any-colour Command Crystal, which
        // at 30+ of its own turns is a game this format has never seen.
        static final int MANA_TURNS = 30;

        private LightningFormat(int life, int hand, boolean avatar,
                                Map<Integer, String> seatColors,
                                Map<Integer, Character> seatFirst) {
            this.life = life;
            this.hand = hand;
            this.avatar = avatar;
            this.seatColors = seatColors;
            this.seatFirst = seatFirst;
        }

        static LightningFormat read(File f) {
            if (!f.exists()) {
                return new LightningFormat(0, 0, false, Collections.emptyMap(),
                                           Collections.emptyMap());
            }
            int life = 0, hand = 0;
            boolean avatar = false;
            Map<Integer, String> colors = new HashMap<>();
            Map<Integer, Character> first = new HashMap<>();
            try {
                for (String raw : new String(java.nio.file.Files.readAllBytes(
                        f.toPath()), "UTF-8").split("\n")) {
                    String line = raw.trim();
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    if ("life".equals(key)) life = Integer.parseInt(val);
                    else if ("hand".equals(key)) hand = Integer.parseInt(val);
                    else if ("avatar".equals(key)) avatar = "1".equals(val) || "true".equalsIgnoreCase(val);
                    else if (key.startsWith("colors")) {
                        int seat = Integer.parseInt(key.substring("colors".length()));
                        String letters = val.toUpperCase(java.util.Locale.ROOT).replaceAll("[^WUBRGC]", "");
                        if (!letters.isEmpty()) colors.put(seat, letters);
                    }
                    else if (key.startsWith("first")) {
                        int seat = Integer.parseInt(key.substring("first".length()));
                        String letters = val.toUpperCase(java.util.Locale.ROOT).replaceAll("[^WUBRGC]", "");
                        if (!letters.isEmpty()) first.put(seat, letters.charAt(0));
                    }
                }
            } catch (Exception e) {
                // A malformed file must not turn a game into something nobody
                // asked for: ignore it wholesale rather than half-apply it.
                System.out.println("[FORMAT] unreadable " + f + ": " + e);
                return new LightningFormat(0, 0, false, Collections.emptyMap(),
                                           Collections.emptyMap());
            }
            System.out.println("[FORMAT] life=" + life + " hand=" + hand + " avatar=" + avatar
                    + " colors=" + colors + " first=" + first);
            return new LightningFormat(life, hand, avatar, colors, first);
        }

        void apply(RegisteredPlayer seat, Deck deck) {
            if (avatar && deck != null && deck.has(forge.deck.DeckSection.Avatar)) {
                // Also applies the avatar's HandLifeModifier, which is why the
                // explicit numbers below come after it.
                seat.assignVanguardAvatar();
            }
            if (life > 0) seat.setStartingLife(life);
            if (hand > 0) seat.setStartingHand(hand);
        }

        boolean hasManaCards() {
            return !seatColors.isEmpty();
        }

        /**
         * Put each seat's upkeep-lands card into its command zone.
         *
         * Same construction Puzzle.addGoalEnforcement uses for its goal card: a
         * Card built in memory with a parsed trigger, never a script file, so
         * it can differ per seat and per game. Vanguard-typed so StateExporter
         * keeps it out of the command zone the client draws.
         *
         * Runs from the startGameHook, on the game thread, during turn 1's
         * untap step -- before the first upkeep, which is the trigger it has to
         * be in place for.
         */
        void addManaCards(Game g) {
            List<Player> players = g.getPlayers();
            for (Map.Entry<Integer, String> e : seatColors.entrySet()) {
                int i = e.getKey();
                if (i < 0 || i >= players.size()) continue;
                try {
                    Player p = players.get(i);
                    Character first = seatFirst.get(i);
                    Card c = buildManaCard(g, p, e.getValue(), first);
                    p.getZone(ZoneType.Command).add(c);
                    System.out.println("[FORMAT] seat " + i + " upkeep lands from " + e.getValue()
                            + (first != null ? " starting with " + first : ""));
                } catch (Exception ex) {
                    // Leave the seat with no upkeep mana rather than no game.
                    System.out.println("[FORMAT] seat " + i + " mana card failed: " + ex);
                }
            }
        }

        private static String tokenScriptFor(char color) {
            switch (color) {
                case 'W': return "w_plains_lightning";
                case 'U': return "u_island_lightning";
                case 'B': return "b_swamp_lightning";
                case 'R': return "r_mountain_lightning";
                case 'G': return "g_forest_lightning";
                default:  return "c_wastes_lightning";
            }
        }

        private static Card buildManaCard(Game g, Player owner, String colors, Character first) {
            // The bag, drawn MANA_TURNS deep: shuffle the colours, deal them
            // out, shuffle again. A seat with one colour gets a plain sequence.
            List<Character> bag = new ArrayList<>();
            for (char ch : colors.toCharArray()) bag.add(ch);
            List<Character> sequence = new ArrayList<>(MANA_TURNS);
            while (sequence.size() < MANA_TURNS) {
                Collections.shuffle(bag, MyRandom.getRandom());
                for (char ch : bag) {
                    if (sequence.size() < MANA_TURNS) sequence.add(ch);
                }
            }
            // A chosen first land (2026-09-23): swap it to the front of the
            // FIRST cycle only. The cycle still holds every colour once, so
            // the bag's guarantee is untouched; the player just knows what
            // turn 1 makes. A colour outside the bag is ignored, not added.
            if (first != null && bag.contains(first) && !sequence.isEmpty()
                    && sequence.get(0) != first) {
                int at = sequence.indexOf(first);
                if (at > 0 && at < bag.size()) {
                    sequence.set(at, sequence.get(0));
                    sequence.set(0, first);
                }
            }

            Card c = new Card(g.nextCardId(), g);
            c.setOwner(owner);
            c.setName("Lightning Round Mana");
            c.setGamePieceType(GamePieceType.EFFECT);
            c.addType("Vanguard");
            c.setImageKey("t:lightning_mana");
            c.setOracleText("At the beginning of your upkeep, create a basic land token. "
                    + "Its colour is drawn from a shuffled bag of " + colors
                    + ", refilled when empty.");

            // One trigger, one Execute: bump the upkeep counter, then walk a
            // chain of token effects each gated on the counter equalling its
            // turn. A condition only skips its own ability and the chain runs
            // on, so exactly one token is made per upkeep. Chaining rather
            // than one trigger per turn keeps it to a single trigger, so there
            // is never a "which triggers first?" question for the player.
            c.setSVar("Turns", "Count$CardCounters.TURN");
            for (int t = 1; t <= MANA_TURNS; t++) {
                String next = t < MANA_TURNS ? "L" + (t + 1) : "LTail";
                c.setSVar("L" + t, "DB$ Token | TokenScript$ " + tokenScriptFor(sequence.get(t - 1))
                        + " | TokenOwner$ You | ConditionCheckSVar$ Turns | ConditionSVarCompare$ EQ" + t
                        + " | SubAbility$ " + next);
            }
            c.setSVar("LTail", "DB$ Token | TokenScript$ command_crystal | TokenOwner$ You"
                    + " | ConditionCheckSVar$ Turns | ConditionSVarCompare$ GE" + (MANA_TURNS + 1));
            String eff = "DB$ PutCounter | Defined$ Self | CounterType$ TURN | CounterNum$ 1 | SubAbility$ L1";
            // Static$ True (2026-09-23): the land is made inside the upkeep
            // event, never on the stack. Nobody gets a priority window for it
            // (three AI upkeeps a round were each a stop under the default
            // policy) and nothing can counter it. Same for the wound trigger
            // on lightning_round_rules.txt.
            String trig = "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Command"
                    + " | Static$ True"
                    + " | TriggerDescription$ At the beginning of your upkeep, create a basic land token"
                    + " drawn from your colour bag (" + colors
                    + (first != null ? ", " + first + " first" : "") + ").";
            Trigger trigger = TriggerHandler.parseTrigger(trig, c, true);
            trigger.setOverridingAbility(AbilityFactory.getAbility(eff, c));
            c.addTrigger(trigger);
            return c;
        }
    }

    /** Build a startGameHook that applies deckDir/_scenario.txt, or null if absent. */
    private static Runnable buildScenarioHook(Game g, String deckDir) {
        lastScenario = null;
        try {
            File sf = new File(deckDir + "_scenario.txt");
            if (!sf.exists()) {
                return null;
            }
            List<String> lines = java.nio.file.Files.readAllLines(sf.toPath());
            ScenarioState gs = new ScenarioState();
            gs.parse(lines);
            lastScenario = gs;
            return () -> {
                try {
                    // A scenario seat registers with a starting hand of 0 (the
                    // board supplies the hand), and Game.java copies that into
                    // the MAXIMUM hand size too -- so every cleanup step asked
                    // the player to discard down to nothing. Forge's puzzle mode
                    // resets both to seven (Puzzle.setupMaxPlayerHandSize); so
                    // do we, before the board goes on.
                    for (Player p : g.getPlayers()) {
                        p.setStartingHandSize(7);
                        p.setMaxHandSize(7);
                    }
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
            restoreAvatars(game);
            // GameState clears zones with removeAllCards(true): the real list
            // is emptied, the PlayerView is not. A zone the scenario then left
            // empty (no humanlibrary= line) kept its 19-card view, and the
            // exporter reads views -- so the client, and any test reading
            // library_count, saw a library that was not there.
            for (Player p : game.getPlayers()) {
                p.updateAllZonesForView();
            }
        }

        /**
         * GameState.applyToGame clears every zone, the command zone included,
         * and a scenario cannot name a Vanguard card to put it back (they live
         * in the variant card DB, which GameState never consults). So a
         * Lightning Round scenario lost its rules card -- no wounds, no
         * deck-out rule -- and nothing said so. Put each seat's avatars back
         * the way Player.initVariantsZones placed them at game start.
         */
        private static void restoreAvatars(Game game) {
            for (Player p : game.getPlayers()) {
                RegisteredPlayer rp = p.getRegisteredPlayer();
                if (rp == null || rp.getVanguardAvatars() == null) {
                    continue;
                }
                for (forge.item.PaperCard avatar : rp.getVanguardAvatars()) {
                    boolean present = false;
                    for (Card c : p.getZone(ZoneType.Command).getCards()) {
                        if (c.getName().equals(avatar.getName())) {
                            present = true;
                            break;
                        }
                    }
                    if (present) {
                        continue;
                    }
                    Card c = Card.fromPaperCard(avatar, p);
                    c.setCollectible(true);
                    p.getZone(ZoneType.Command).add(c);
                    System.out.println("[SCENARIO] restored avatar " + avatar.getName()
                            + " for seat " + game.getPlayers().indexOf(p));
                }
            }
        }
    }

    // ---- timestamped stdout ------------------------------------------------
    //
    // Everything this JVM prints -- Forge's own logging, the [SA-BUILD] samples,
    // the AI timeout stacks -- lands in /data/logs/forge_<boot>.log with no time
    // on it, while the Python session log stamps every line. So "which card
    // made the AI slow" could not be answered by joining the two files; the
    // Forge side only ever said "somewhere around then". The prefix here is the
    // session log's format ([HH:MM:SS.mmm], UTC) so the two read side by side.
    // stderr shares the stream so a stack trace cannot interleave with a line.

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static void installStampedStdout() {
        OutputStream raw = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 13);
        PrintStream ps = new PrintStream(new StampedStream(raw), true, StandardCharsets.UTF_8);
        System.setOut(ps);
        System.setErr(ps);
    }

    /** Prefixes each line with the wall clock. Line-buffered on newline only. */
    private static final class StampedStream extends OutputStream {
        private final OutputStream out;
        private boolean lineStart = true;

        StampedStream(OutputStream out) {
            this.out = out;
        }

        private void stamp() throws IOException {
            String t = "[" + ZonedDateTime.now(ZoneOffset.UTC).format(STAMP) + "] ";
            out.write(t.getBytes(StandardCharsets.UTF_8));
            lineStart = false;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (lineStart) {
                stamp();
            }
            out.write(b);
            if (b == '\n') {
                lineStart = true;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            int start = off;
            final int end = off + len;
            for (int i = off; i < end; i++) {
                if (lineStart) {
                    stamp();
                }
                if (b[i] == '\n') {
                    out.write(b, start, i - start + 1);
                    start = i + 1;
                    lineStart = true;
                }
            }
            if (start < end) {
                out.write(b, start, end - start);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            out.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            out.close();
        }
    }
}
