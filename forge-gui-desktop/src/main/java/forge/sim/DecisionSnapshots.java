package forge.sim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameState;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventPlayerPriority;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

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
    private final Set<String> respondSeen = new HashSet<>();
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
            // The response decision: the defender's first priority with the
            // attackers declared, whether or not it has a blocker. Not a
            // snapshot (the evaluator has no forced-cast hook yet): one evt
            // line with the instants the defender could cast now and which
            // attackers each could target, so the play review can tell
            // "held the removal and took the hit" from "had no answer".
            if (blockWindow && game.getStack().isEmpty()) {
                respond(game.getPlayer(ev.priority()));
            }
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
            PlayEvents.Json w = new PlayEvents.Json().s("e", "window").n("n", seq).n("turn", turn)
                    .s("phase", phase.name()).s("kind", kind).s("seat", p.getName());
            if (kind.equals("main")) {
                // What the seat could cast from here, by the engine's own
                // questions (timing, restrictions, a mandatory target, mana);
                // the review's "ended the turn with X castable" reads this
                // instead of comparing mana values with a mana estimate.
                w.n("mana", openMana(p)).raw("ok", okRows(p, null));
            }
            msSpent += System.currentTimeMillis() - t0;
            game.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION, "evt " + w));
        } catch (Exception ignore) {
            // a snapshot must never cost a game
        }
    }

    /** The response window, once per combat and defender. */
    private void respond(Player p) {
        try {
            if (p == null || game.getCombat() == null) return;
            int turn = game.getPhaseHandler().getTurn();
            if (!respondSeen.add(turn + "/" + p.getName())) return;
            long t0 = System.currentTimeMillis();
            List<Card> atk = new ArrayList<>(game.getCombat().getAttackers());
            List<String> rows = new ArrayList<>();
            for (Card a : atk) {
                rows.add(new PlayEvents.Json().s("c", a.getName()).n("id", a.getId())
                        .n("p", a.getNetPower()).n("t", a.getNetToughness()).toString());
            }
            PlayEvents.Json j = new PlayEvents.Json().s("e", "respond").n("turn", turn)
                    .s("seat", p.getName()).n("mana", openMana(p))
                    .raw("ok", okRows(p, atk)).raw("atk", "[" + String.join(",", rows) + "]");
            msSpent += System.currentTimeMillis() - t0;
            game.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION, "evt " + j));
        } catch (Exception ignore) {
            // never cost a game
        }
    }

    private static int openMana(Player p) {
        try {
            return Math.max(0, ComputerUtilMana.getAvailableManaEstimate(p, true));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The hand cards the player could play right now: one row per card with
     * a playable non-mana ability (the bridge's own questions, the ones the
     * client's greying and the refused-click explanation ask). With
     * {@code attackers} null (a main window) a card whose own enters trigger
     * needs a target and has none is left out: the spell is legal, the card
     * would do nothing. With attackers (a response window) each row carries
     * the attacker ids the card's modes could target.
     */
    private static String okRows(Player p, List<Card> attackers) {
        List<String> rows = new ArrayList<>();
        try {
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                if (c.isLand()) continue;
                List<SpellAbility> sas = playable(c, p);
                if (sas.isEmpty()) continue;
                if (attackers == null) {
                    if (etbHasNoTarget(c, p)) continue;
                    rows.add(PlayEvents.Json.q(c.getName()));
                    continue;
                }
                Set<Integer> ids = new LinkedHashSet<>();
                for (SpellAbility sa : sas) {
                    for (SpellAbility m : modes(sa)) {
                        if (!m.usesTargeting()) continue;
                        if (m.getActivatingPlayer() == null) m.setActivatingPlayer(p);
                        for (Card a : attackers) {
                            if (m.canTarget(a)) ids.add(a.getId());
                        }
                    }
                }
                List<String> idRows = new ArrayList<>();
                for (Integer id : ids) idRows.add(String.valueOf(id));
                rows.add(new PlayEvents.Json().s("c", c.getName())
                        .raw("tgts", "[" + String.join(",", idRows) + "]").toString());
            }
        } catch (Exception ignore) {
            // a card the checks cannot examine is not listed
        }
        return "[" + String.join(",", rows) + "]";
    }

    /** The card's abilities that pass every playability question right now. */
    private static List<SpellAbility> playable(Card c, Player p) {
        List<SpellAbility> out = new ArrayList<>();
        try {
            for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                if (sa == null || sa.isManaAbility() || sa.isLandAbility()) continue;
                List<SpellAbility> one = new ArrayList<>();
                one.add(sa);
                if (TargetReasons.unplayableReason(one, p) == null) out.add(sa);
            }
        } catch (Exception ignore) {
            // not playable as far as this can tell
        }
        return out;
    }

    /** A charm's modes, or the ability itself. */
    private static List<SpellAbility> modes(SpellAbility sa) {
        List<SpellAbility> out = new ArrayList<>();
        try {
            if (sa.getApi() == ApiType.Charm) out.addAll(CharmEffect.makePossibleOptions(sa));
        } catch (Exception ignore) {
            // fall back to the ability itself
        }
        if (out.isEmpty()) out.add(sa);
        return out;
    }

    /** True when the card's own enters trigger needs a target and nothing is one. */
    private static boolean etbHasNoTarget(Card c, Player p) {
        try {
            for (Trigger t : c.getTriggers()) {
                if (t.getMode() != TriggerType.ChangesZone) continue;
                if (!"Battlefield".equals(t.getParam("Destination"))) continue;
                if (!t.getParamOrDefault("ValidCard", "").contains("Card.Self")) continue;
                SpellAbility ta = t.ensureAbility();
                if (ta == null || !ta.usesTargeting()) continue;
                if (ta.getActivatingPlayer() == null) ta.setActivatingPlayer(p);
                if (ta.getMinTargets() > 0 && ta.getTargetRestrictions().getNumCandidates(ta) == 0) {
                    return true;
                }
            }
        } catch (Exception ignore) {
            // cannot tell: treat the card as castable
        }
        return false;
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
