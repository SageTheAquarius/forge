package forge.sim;

import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Forge's own AI as a SECOND OPINION for a bridged seat (the Claude seat,
 * mtg_sim/claude_seat.py; PLAN section 4.1).
 *
 * The seat asks for it explicitly ({@code {"action":"hint"}} at a priority
 * window, {@code {"hint":true}} at an attack or block window) and only when it
 * is about to spend an API call, so a window the relay or the seat passes on
 * its own costs nothing here. The bridge then re-asks the same window with
 * {@code "hint": {...}} attached:
 * <pre>
 *   {"kind":"priority","text":"cast Grizzly Bears","card":12,"ability":0,"why":"..."}
 *   {"kind":"attack","text":"attack with Hill Giant, Thopter","attackers":[7,9]}
 *   {"kind":"block","text":"Wall blocks Hill Giant","blocks":[{"blocker":5,"attacker":7}]}
 *   {}   when nothing could be computed in time
 * </pre>
 *
 * How it is computed: a {@link GameCopier} copy of the game (every seat in a
 * copy is an AI seat), the OTHER players' hands redrawn at random from their
 * hand plus library and every library shuffled -- the copier keeps hidden
 * information (PRUNE_HIDDEN_INFO is off), and a hint that peeked at the
 * opponent's hand would hand the seat what it is promised never to see --
 * then the copied seat's own {@link AiController} is asked what it would do,
 * with the "why" trace on, and the answer is mapped back to the live cards
 * by id and to the exported ability index by label. Runs on its own thread
 * under {@link #BUDGET_MS}; past that the window goes on without an opinion.
 * Never inside a simulation copy, never when a previous hint is still running.
 */
public final class AiHint {

    /** Wall-clock budget per hint. -Dbridge.hint.ms overrides. */
    public static final long BUDGET_MS = Long.getLong("bridge.hint.ms", 2500L);
    private static final boolean LOG = Boolean.getBoolean("bridge.hint.log");

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-hint");
        t.setDaemon(true);
        return t;
    });
    private static volatile Future<String> running;

    private AiHint() { }

    public static String priority(final Player me) {
        return run(() -> priorityImpl(me));
    }

    public static String attack(final Player me, final Combat combat) {
        return run(() -> attackImpl(me, combat));
    }

    public static String blocks(final Player me, final Combat combat) {
        return run(() -> blocksImpl(me, combat));
    }

    private static String run(Callable<String> job) {
        Future<String> prev = running;
        if (prev != null && !prev.isDone()) {
            if (LOG) System.out.println("[hint] previous hint still running; skipped");
            return null;
        }
        final long t0 = System.currentTimeMillis();
        Future<String> f;
        try {
            f = POOL.submit(job);
        } catch (RuntimeException e) {
            return null;
        }
        running = f;
        try {
            String out = f.get(BUDGET_MS, TimeUnit.MILLISECONDS);
            if (LOG) System.out.println("[hint] " + (System.currentTimeMillis() - t0) + " ms: " + out);
            return out;
        } catch (TimeoutException e) {
            if (LOG) System.out.println("[hint] over budget (" + BUDGET_MS + " ms), no opinion");
            f.cancel(true);
            return null;
        } catch (Exception e) {
            if (LOG) System.out.println("[hint] failed: " + e);
            return null;
        }
    }

    // ── the copy ────────────────────────────────────────────────────────────

    private static final class Copy {
        final GameCopier copier;
        final Game game;
        final Player me;
        final AiController ai;

        Copy(GameCopier copier, Game game, Player me, AiController ai) {
            this.copier = copier; this.game = game; this.me = me; this.ai = ai;
        }
    }

    private static Copy copyFor(Player me) {
        Game game = me.getGame();
        if (game.SIMULATION_COPY) {
            return null;
        }
        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy(null, me);
        Player meCopy = copier.find(me);
        if (meCopy == null || !(meCopy.getController() instanceof PlayerControllerAi)) {
            return null;
        }
        redrawHidden(copy, meCopy, new Random(game.getTimestamp() ^ me.getId()));
        AiController ai = ((PlayerControllerAi) meCopy.getController()).getAi();
        // The copier gives a non-AI lobby player the full-simulation picker;
        // the opinion wanted is the rule-based one, which is what the seats
        // play in a real game, and it answers inside the budget.
        ai.setUseSimulation(null);
        return new Copy(copier, copy, meCopy, ai);
    }

    /** Evaluate.determinize, on the copy: the other players' hands are drawn
     *  again from hand + library, every library is shuffled. */
    private static void redrawHidden(Game g, Player me, Random rnd) {
        for (Player p : g.getPlayers()) {
            if (p == me) {
                shuffleLibrary(p, rnd);
                continue;
            }
            CardCollection hand = new CardCollection(p.getCardsIn(ZoneType.Hand));
            int n = hand.size();
            for (Card c : hand) {
                g.getAction().moveToLibrary(c, null);
            }
            shuffleLibrary(p, rnd);
            CardCollection lib = new CardCollection(p.getCardsIn(ZoneType.Library));
            for (int i = 0; i < n && i < lib.size(); i++) {
                g.getAction().moveTo(p.getZone(ZoneType.Hand), lib.get(i), null, null);
            }
        }
    }

    private static void shuffleLibrary(Player p, Random rnd) {
        CardCollection lib = new CardCollection(p.getCardsIn(ZoneType.Library));
        List<Card> cards = new ArrayList<>(lib);
        java.util.Collections.shuffle(cards, rnd);
        p.getZone(ZoneType.Library).setCards(cards);
    }

    private static Card original(Copy c, Card copied) {
        GameObject o = c.copier.reverseFind(copied);
        return o instanceof Card ? (Card) o : null;
    }

    // ── priority ────────────────────────────────────────────────────────────

    private static String priorityImpl(Player me) {
        Copy c = copyFor(me);
        if (c == null) {
            return null;
        }
        List<SpellAbility> pick;
        Map<SpellAbility, String> trace;
        AiController.traceOn(c.me);
        try {
            pick = c.ai.chooseSpellAbilityToPlay();
        } finally {
            trace = AiController.traceOff(c.me);
        }
        SpellAbility sa = pick == null || pick.isEmpty() ? null : pick.get(0);
        StringBuilder sb = new StringBuilder("{\"kind\":\"priority\"");
        String why = reasons(trace, sa);
        if (sa == null) {
            sb.append(",\"text\":\"pass (nothing it would play now)\",\"card\":-1,\"ability\":-1");
        } else {
            Card host = sa.getHostCard();
            Card orig = host != null ? original(c, host) : null;
            String label = StateExporter.abilityLabel(sa, host);
            String verb = sa.isLandAbility() ? "play" : sa.isSpell() ? "cast" : "activate";
            String text = verb + " " + (host != null ? host.getName() : "?")
                    + (label.isEmpty() || label.equalsIgnoreCase(verb + " " + (host != null ? host.getName() : ""))
                       ? "" : " (" + label + ")");
            int idx = -1;
            if (orig != null) {
                List<SpellAbility> all = StateExporter.possibleAbilities(orig, me);
                if (all != null) {
                    for (int i = 0; i < all.size(); i++) {
                        if (StateExporter.abilityLabel(all.get(i), orig).equals(label)) {
                            idx = i;
                            break;
                        }
                    }
                }
            }
            sb.append(",\"text\":").append(PlayEvents.Json.q(text))
              .append(",\"card\":").append(orig != null ? orig.getId() : -1)
              .append(",\"ability\":").append(idx);
        }
        if (!why.isEmpty()) {
            sb.append(",\"why\":").append(PlayEvents.Json.q(why));
        }
        return sb.append('}').toString();
    }

    /** The AI's verdict on its pick and the first few plays it declined. */
    private static String reasons(Map<SpellAbility, String> trace, SpellAbility chosen) {
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (chosen != null && trace.get(chosen) != null) {
            sb.append("chosen: ").append(trace.get(chosen));
        }
        int n = 0;
        for (Map.Entry<SpellAbility, String> e : trace.entrySet()) {
            if (e.getKey() == chosen || e.getValue() == null) continue;
            if (n++ >= 3) break;
            Card h = e.getKey().getHostCard();
            if (sb.length() > 0) sb.append("; ");
            sb.append("declined ").append(h != null ? h.getName() : "?").append(": ").append(e.getValue());
        }
        String s = sb.toString();
        return s.length() > 350 ? s.substring(0, 347) + "..." : s;
    }

    // ── combat ──────────────────────────────────────────────────────────────

    private static String attackImpl(Player me, Combat live) {
        Copy c = copyFor(me);
        if (c == null) {
            return null;
        }
        Combat combat = new Combat(c.me);
        c.ai.declareAttackers(c.me, combat);
        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Card a : combat.getAttackers()) {
            Card orig = original(c, a);
            if (orig != null) {
                ids.add(orig.getId());
                names.add(orig.getName());
            }
        }
        java.util.Collections.sort(names);
        StringBuilder sb = new StringBuilder("{\"kind\":\"attack\",\"text\":");
        sb.append(PlayEvents.Json.q(ids.isEmpty() ? "no attack" : "attack with " + String.join(", ", names)));
        sb.append(",\"attackers\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.append("]}").toString();
    }

    private static String blocksImpl(Player me, Combat live) {
        Copy c = copyFor(me);
        if (c == null || live == null) {
            return null;
        }
        // Rebuild the declared attack on the copy: the copier does not carry
        // the combat, and the AI's block logic reads it off the Combat object.
        Player attackerCopy = c.copier.find(live.getAttackingPlayer());
        if (attackerCopy == null) {
            return null;
        }
        Combat combat = new Combat(attackerCopy);
        for (Card a : live.getAttackers()) {
            Card ac = c.copier.find(a);
            GameEntity dc = c.copier.find(live.getDefenderByAttacker(a));
            if (ac != null && dc != null) {
                combat.addAttacker(ac, dc);
            }
        }
        if (combat.getAttackers().isEmpty()) {
            return null;
        }
        c.ai.declareBlockersFor(c.me, combat);
        List<String> pairsJson = new ArrayList<>();
        List<String> pairsText = new ArrayList<>();
        for (Card b : combat.getAllBlockers()) {
            if (!b.getController().equals(c.me)) continue;
            Card ob = original(c, b);
            for (Card a : combat.getAttackersBlockedBy(b)) {
                Card oa = original(c, a);
                if (ob == null || oa == null) continue;
                pairsJson.add("{\"blocker\":" + ob.getId() + ",\"attacker\":" + oa.getId() + "}");
                pairsText.add(ob.getName() + " blocks " + oa.getName());
            }
        }
        java.util.Collections.sort(pairsText);
        return "{\"kind\":\"block\",\"text\":"
                + PlayEvents.Json.q(pairsText.isEmpty() ? "no blocks" : String.join("; ", pairsText))
                + ",\"blocks\":[" + String.join(",", pairsJson) + "]}";
    }
}
