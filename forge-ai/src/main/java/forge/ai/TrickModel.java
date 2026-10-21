package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventCombatEnded;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * EconomyDraft 2026-10-17: what each player has shown about combat tricks this
 * game, so the AI can judge an attack into open mana by the odds of a trick
 * instead of always (or never) assuming one.
 *
 * <p>For every player, two counts: <b>chances</b> -- combats in which they
 * defended with two or more mana available and a card in hand -- and
 * <b>tricks</b> -- instant-speed spells they cast during a combat on someone
 * else's turn. The estimate is a Beta posterior,
 * <pre>  P(trick) = (A + tricks) / (A + B + chances)</pre>
 * with a prior mean of A / (A + B) = 0.3. A player who casts tricks drives it
 * up within a combat or two; one who keeps passing with mana up drives it down.
 * It is 0 when the defender has no card in hand or no mana to cast with.
 *
 * <p>One model per engine, attached by ForgeServer to the REAL game when it
 * starts (each engine JVM plays one game at a time). Counts are keyed by
 * player name, so the model holds no game objects: the AI's simulated game
 * copies (same names, new objects) read the real game's counts, and nothing
 * they do is counted. An earlier version kept a model per Game in a weak map
 * whose values held Players -- every simulated copy stayed alive and the
 * engine ran out of memory within a few games.
 */
public final class TrickModel {
    private static final double A = 1.2;
    private static final double B = 2.8;
    private static volatile TrickModel current;

    private final Map<String, int[]> counts = new HashMap<>();   // {chances, tricks}
    private final Set<String> pending = new HashSet<>();
    private final java.lang.ref.WeakReference<Game> gameRef;

    private TrickModel(Game game) {
        this.gameRef = new java.lang.ref.WeakReference<>(game);
    }

    /** Start counting for a new real game (ForgeServer, at game start). */
    public static void attach(Game game) {
        TrickModel m = new TrickModel(game);
        game.subscribeToEvents(m);
        current = m;
    }

    /** The current game's model, or null before any game was attached. */
    public static TrickModel current() {
        return current;
    }

    private synchronized int[] c(String name) {
        return counts.computeIfAbsent(name, k -> new int[2]);
    }

    /** The odds `defender` holds and uses a trick in this combat; works for a
     *  player of a simulated copy too (looked up by name). */
    public static double probability(Player defender) {
        if (defender == null || defender.getCardsIn(ZoneType.Hand).isEmpty()) {
            return 0;
        }
        int mana = ComputerUtilMana.getAvailableManaEstimate(defender);
        if (mana <= 0) {
            return 0;
        }
        TrickModel m = current;
        int[] k = m == null ? new int[2] : m.c(defender.getName());
        double p = Math.min(0.95, (A + k[1]) / (A + B + k[0]));
        // One open mana casts fewer tricks than two.
        return mana == 1 ? p * 0.5 : p;
    }

    public int tricksSeen(String name) {
        return c(name)[1];
    }

    public int chancesSeen(String name) {
        return c(name)[0];
    }

    @Subscribe
    public void onAttackers(GameEventAttackersDeclared ev) {
        try {
            Game game = gameRef.get();
            Combat combat = game == null ? null : game.getCombat();
            if (combat == null) {
                return;
            }
            synchronized (this) {
                pending.clear();
                for (Player d : combat.getDefendingPlayers()) {
                    if (!combat.getAttackersOf(d).isEmpty()
                            && !d.getCardsIn(ZoneType.Hand).isEmpty()
                            && ComputerUtilMana.getAvailableManaEstimate(d) >= 2) {
                        pending.add(d.getName());
                    }
                }
            }
        } catch (Exception ignore) {
            // a model must never cost a game
        }
    }

    @Subscribe
    public void onCast(GameEventSpellAbilityCast ev) {
        try {
            Game game = gameRef.get();
            if (game == null) {
                return;
            }
            PhaseHandler ph = game.getPhaseHandler();
            if (!ph.inCombat() || game.getStack().isEmpty()) {
                return;
            }
            SpellAbility sa = game.getStack().peekAbility();
            if (sa == null || !sa.isSpell()) {
                return;
            }
            Player who = sa.getActivatingPlayer();
            Card host = sa.getHostCard();
            if (who == null || host == null || who.equals(ph.getPlayerTurn())) {
                return;
            }
            if (host.isInstant() || host.hasKeyword(Keyword.FLASH)) {
                c(who.getName())[1]++;
            }
        } catch (Exception ignore) {
        }
    }

    @Subscribe
    public synchronized void onCombatEnded(GameEventCombatEnded ev) {
        try {
            for (String d : pending) {
                c(d)[0]++;
            }
            pending.clear();
        } catch (Exception ignore) {
        }
    }
}
