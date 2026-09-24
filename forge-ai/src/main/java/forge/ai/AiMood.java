package forge.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.eventbus.Subscribe;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;

/**
 * An AI seat's temper: who it holds a grudge against, how angry it is and how
 * its morale is holding up. EconomyDraft addition (2026-09-10) for the Commander
 * pod; stock Forge has no memory of who hurt it and picks its combat target
 * from board state alone.
 *
 * <p>Three numbers, all 0..100:
 * <ul>
 *   <li><b>grudge</b>, per opponent -- rises when that player damages this seat,
 *       kills its permanents (its commander most of all) or aims a spell at it;</li>
 *   <li><b>anger</b> -- the same events, without caring who did them;</li>
 *   <li><b>morale</b> -- drops when this seat is losing (lowest life, board wiped),
 *       recovers when it is ahead or when it lands a hit on its grudge target.</li>
 * </ul>
 * All three decay at the start of each of the seat's own turns.
 *
 * <p>It changes play in three places:
 * <ol>
 *   <li>{@link AiAttackController#choosePreferredDefenderPlayer} adds
 *       {@link #grudgeBonus} to an opponent's threat score, so a grudge target is
 *       attacked over a bigger board;</li>
 *   <li>the attack aggression ladder is shifted by {@link #adjustAggression}:
 *       an angry seat swings into trades it would normally decline, a
 *       demoralised one keeps blockers home;</li>
 *   <li>the seat's AI profile is swapped between Default, Reckless and Cautious
 *       (Forge's own tuned personalities) as the mood moves, which changes
 *       blocking, counterspell and removal choices everywhere else.</li>
 * </ol>
 *
 * <p>Off ({@link #OFF}) costs nothing: the instance never subscribes to the
 * game's events and every hook returns its input unchanged. The seat's
 * temperament is set once by ForgeServer from <code>_ai_mood.txt</code>.
 *
 * <p>Everything here runs on the game thread from the event bus, and only
 * mutates state in response to events -- never during an AI evaluation -- so a
 * decision window sees one consistent mood throughout (the attack controller's
 * per-window caches rely on that).
 */
public class AiMood {
    public static final int OFF = 0;
    public static final int MILD = 1;
    public static final int VOLATILE = 2;

    private static final int BASE_MORALE = 60;
    private static final String PROFILE_DEFAULT = "Default";
    private static final String PROFILE_ANGRY = "Reckless";
    private static final String PROFILE_LOW = "Cautious";

    /** Every enabled mood, by the player's view id, for the state exporter. */
    private static final Map<Integer, AiMood> BY_PLAYER_ID = new ConcurrentHashMap<>();

    private final AiController brain;
    private int temperament = OFF;
    private boolean subscribed = false;

    private final Map<Player, Integer> grudge = new HashMap<>();
    private int anger = 0;
    private int morale = BASE_MORALE;

    private String profile = "";
    /**
     * The profile the seat was created with (ForgeServer's _ai_profile.txt
     * override, or Forge's default). A mood swap to Reckless / Cautious
     * returns HERE when it passes, not to the literal "Default" -- otherwise
     * a Cautious seat that got angry once came back as Default for good.
     */
    private String baseProfile = "";
    private String lastDescription = "";

    public AiMood(AiController brain) {
        this.brain = brain;
    }

    // ------------------------------------------------------------ lifecycle

    /** Forget every seat's mood. ForgeServer calls this before each game. */
    public static void resetAll() {
        BY_PLAYER_ID.clear();
    }

    /**
     * Give the AI seat driving {@code p} a temper. A no-op for a seat that has
     * no AiController (a pure human controller) or for level {@link #OFF}.
     */
    public static void attach(Player p, int level) {
        AiMood m = of(p);
        if (m == null) {
            return;
        }
        m.enable(level);
    }

    public static AiMood of(Player p) {
        if (p == null || !(p.getController() instanceof PlayerControllerAi)) {
            return null;
        }
        AiController ai = ((PlayerControllerAi) p.getController()).getAi();
        return ai == null ? null : ai.getMood();
    }

    private void enable(int level) {
        temperament = Math.max(OFF, Math.min(VOLATILE, level));
        if (temperament == OFF || subscribed) {
            return;
        }
        Game g = brain.getGame();
        if (g == null) {
            return;
        }
        g.subscribeToEvents(this);
        subscribed = true;
        BY_PLAYER_ID.put(brain.getPlayer().getView().getId(), this);
        LobbyPlayer lp = brain.getPlayer().getLobbyPlayer();
        if (lp instanceof LobbyPlayerAi) {
            String current = ((LobbyPlayerAi) lp).getAiProfile();
            baseProfile = current == null ? "" : current;
        }
    }

    /** The profile a calm seat plays: its override if it has one, else Forge's Default. */
    private String baseProfile() {
        return baseProfile.isEmpty() ? PROFILE_DEFAULT : baseProfile;
    }

    public boolean isEnabled() {
        return temperament != OFF;
    }

    public int getTemperament() {
        return temperament;
    }

    // ------------------------------------------------------------ hooks

    /**
     * Extra threat score for {@code opp} when {@code ai} picks who to attack.
     * Zero unless the seat has a temper and a grudge. Scaled so a full grudge
     * outweighs a healthy board (board scores run a few hundred to a thousand).
     */
    public static int grudgeBonus(Player ai, Player opp) {
        AiMood m = of(ai);
        if (m == null || m.temperament == OFF) {
            return 0;
        }
        int g = m.grudgeOf(opp);
        if (g <= 0) {
            return 0;
        }
        return g * (m.temperament == VOLATILE ? 6 : 4);
    }

    /**
     * Shift the attack aggression ladder (0 = stay home .. 5 = all out) by the
     * seat's mood. A furious seat attacks into trades it would decline; a seat
     * with a grudge against the chosen defender leans in; a demoralised seat
     * keeps its creatures home unless it is too angry to care.
     */
    public static int adjustAggression(Player ai, int aggression, Player defender) {
        AiMood m = of(ai);
        if (m == null || m.temperament == OFF || aggression >= 6) {
            return aggression;   // 6 is the Exalted special case; leave it alone
        }
        int d = 0;
        if (m.anger >= 70) {
            d += 2;
        } else if (m.anger >= 40) {
            d += 1;
        }
        if (defender != null && m.grudgeOf(defender) >= 40 && d < 2) {
            d += 1;
        }
        if (m.morale <= 25 && m.anger < 40) {
            d -= 1;
        }
        return Math.max(0, Math.min(5, aggression + d));
    }

    /** Short state text for the wire, e.g. "furious at Sage"; "" when calm or off. */
    public static String describeFor(int playerViewId) {
        AiMood m = BY_PLAYER_ID.get(playerViewId);
        return m == null ? "" : m.describe();
    }

    // ------------------------------------------------------------ events

    @Subscribe
    public void onPlayerDamaged(GameEventPlayerDamaged ev) {
        if (temperament == OFF) {
            return;
        }
        try {
            Player me = brain.getPlayer();
            Player target = player(ev.target());
            Player from = ev.source() == null ? null : player(ev.source().getController());
            int amount = ev.amount();
            if (amount <= 0) {
                return;
            }
            if (target == me) {
                if (!isFoe(from)) {
                    return;
                }
                // Burn and drain feel more personal than a creature attack.
                int g = amount * (ev.combat() ? 2 : 3);
                addGrudge(from, g);
                anger = clamp(anger + scale(amount * 2));
                morale = clamp(morale - scale(amount / 2));
                if (amount >= 10) {
                    announceIfChanged();
                }
            } else if (from == me && target != null && isFoe(target)) {
                // Landing a hit is satisfying, doubly so on the grudge target.
                morale = clamp(morale + Math.min(5, amount / 2));
                if (grudgeOf(target) > 0 && target == topGrudge()) {
                    grudge.put(target, Math.max(0, grudgeOf(target) - amount));
                }
            }
        } catch (Exception ignore) {
            // A mood bug must never take the game thread down.
        }
    }

    @Subscribe
    public void onCardChangeZone(GameEventCardChangeZone ev) {
        if (temperament == OFF) {
            return;
        }
        try {
            if (ev.from() == null || ev.from().zoneType() != ZoneType.Battlefield) {
                return;
            }
            if (ev.to() == null || ev.to().zoneType() == ZoneType.Battlefield) {
                return;
            }
            CardView c = ev.card();
            Player me = brain.getPlayer();
            if (c == null || player(c.getOwner()) != me) {
                return;
            }
            Player culprit = blameForRemoval(c);
            if (!isFoe(culprit)) {
                return;
            }
            boolean creature = c.getCurrentState() != null && c.getCurrentState().isCreature();
            boolean land = c.getCurrentState() != null && c.getCurrentState().isLand();
            int g;
            int a;
            int m;
            if (c.isCommander()) {
                g = 30; a = 25; m = 12;
            } else if (creature) {
                int power = c.getCurrentState() == null ? 0 : Math.max(0, c.getCurrentState().getPower());
                g = 5 + Math.min(10, power); a = 4 + Math.min(6, power / 2); m = 3;
            } else if (land) {
                g = 6; a = 5; m = 2;     // land destruction is the classic feud starter
            } else {
                g = 4; a = 3; m = 1;
            }
            if (c.isToken()) {
                g /= 2; a /= 2; m /= 2;
            }
            addGrudge(culprit, g);
            anger = clamp(anger + scale(a));
            morale = clamp(morale - scale(m));
            if (c.isCommander()) {
                log(brain.getPlayer().getName() + " will remember that, " + culprit.getName() + ".");
                announceIfChanged();
            }
        } catch (Exception ignore) {
        }
    }

    @Subscribe
    public void onSpellCast(GameEventSpellAbilityCast ev) {
        if (temperament == OFF || ev.si() == null) {
            return;
        }
        try {
            Player me = brain.getPlayer();
            Player caster = player(ev.si().getActivatingPlayer());
            if (!isFoe(caster)) {
                return;
            }
            boolean aimedAtMe = false;
            if (ev.si().getTargetPlayers() != null) {
                for (PlayerView pv : ev.si().getTargetPlayers()) {
                    if (player(pv) == me) {
                        aimedAtMe = true;
                        break;
                    }
                }
            }
            if (!aimedAtMe && ev.si().getTargetCards() != null) {
                for (CardView cv : ev.si().getTargetCards()) {
                    if (player(cv.getController()) == me) {
                        aimedAtMe = true;
                        break;
                    }
                }
            }
            if (!aimedAtMe) {
                return;
            }
            // Being singled out is irritating even before the spell resolves.
            addGrudge(caster, 3);
            anger = clamp(anger + scale(2));
        } catch (Exception ignore) {
        }
    }

    @Subscribe
    public void onTurnBegan(GameEventTurnBegan ev) {
        if (temperament == OFF) {
            return;
        }
        try {
            if (player(ev.turnOwner()) != brain.getPlayer()) {
                return;
            }
            tick();
        } catch (Exception ignore) {
        }
    }

    // ------------------------------------------------------------ internals

    /** Start of this seat's own turn: decay, take stock of the table, re-profile. */
    private void tick() {
        Player me = brain.getPlayer();
        double keep = temperament == VOLATILE ? 0.9 : 0.8;
        List<Player> gone = new ArrayList<>();
        for (Map.Entry<Player, Integer> e : grudge.entrySet()) {
            if (e.getKey().hasLost()) {
                gone.add(e.getKey());
            } else {
                e.setValue((int) Math.floor(e.getValue() * keep));
            }
        }
        for (Player p : gone) {
            if (grudge.get(p) >= 25) {
                log(me.getName() + " is satisfied to see " + p.getName() + " gone.");
            }
            grudge.remove(p);
        }
        grudge.values().removeIf(v -> v <= 0);
        anger = (int) Math.floor(anger * (temperament == VOLATILE ? 0.8 : 0.7));

        // Morale follows the standings.
        int myLife = me.getLife();
        int best = Integer.MIN_VALUE;
        int worst = Integer.MAX_VALUE;
        int total = 0;
        int n = 0;
        for (Player p : me.getOpponents()) {
            if (p.hasLost()) {
                continue;
            }
            best = Math.max(best, p.getLife());
            worst = Math.min(worst, p.getLife());
            total += p.getLife();
            n++;
        }
        if (n > 0) {
            double avg = total / (double) n;
            if (myLife < worst) {
                morale = clamp(morale - scale(6));
            }
            if (myLife < avg * 0.6) {
                morale = clamp(morale - scale(6));
            } else if (myLife >= best) {
                morale = clamp(morale + 4);
            }
            if (me.getCreaturesInPlay().isEmpty() && myLife < avg) {
                morale = clamp(morale - scale(4));
            }
        }
        // ...and drifts back toward neutral.
        if (morale < BASE_MORALE) {
            morale = Math.min(BASE_MORALE, morale + 3);
        } else if (morale > BASE_MORALE) {
            morale = Math.max(BASE_MORALE, morale - 3);
        }

        applyProfile();
        announceIfChanged();
    }

    private void applyProfile() {
        String want;
        if (anger >= 40) {
            want = PROFILE_ANGRY;
        } else if (morale <= 30) {
            want = PROFILE_LOW;
        } else {
            want = baseProfile();
        }
        if (want.equals(profile)) {
            return;
        }
        LobbyPlayer lp = brain.getPlayer().getLobbyPlayer();
        if (!(lp instanceof LobbyPlayerAi)) {
            return;
        }
        if (!AiProfileUtil.getAvailableProfiles().contains(want)) {
            return;   // profile file not shipped; keep whatever is loaded
        }
        ((LobbyPlayerAi) lp).setAiProfile(want);
        profile = want;
    }

    private String describe() {
        if (temperament == OFF) {
            return "";
        }
        Player t = topGrudge();
        int g = t == null ? 0 : grudgeOf(t);
        String at = (t != null && g >= 20) ? " at " + t.getName() : "";
        if (anger >= 70) {
            return "furious" + at;
        }
        if (anger >= 40) {
            return "angry" + at;
        }
        if (g >= 25) {
            return "holds a grudge against " + t.getName();
        }
        if (morale <= 25) {
            return "demoralised";
        }
        if (morale <= 40) {
            return "rattled";
        }
        if (anger >= 15 || g >= 10) {
            return "annoyed" + ((t != null && g >= 10) ? " at " + t.getName() : "");
        }
        if (morale >= 80) {
            return "confident";
        }
        return "";
    }

    private void announceIfChanged() {
        String now = describe();
        if (now.equals(lastDescription)) {
            return;
        }
        String name = brain.getPlayer().getName();
        if (now.isEmpty()) {
            log(name + " has calmed down.");
        } else if (now.startsWith("holds")) {
            log(name + " " + now + ".");
        } else {
            log(name + " is " + now + ".");
        }
        lastDescription = now;
    }

    private void log(String line) {
        Game g = brain.getGame();
        if (g != null) {
            g.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION, line));
        }
    }

    /**
     * Who to blame for one of this seat's permanents leaving the battlefield:
     * the controller of the spell or ability resolving right now, else the
     * other side of the combat it died in. Null when nobody obvious (state
     * based actions, its own sacrifice, a wrath it cast itself).
     */
    private Player blameForRemoval(CardView c) {
        Game g = brain.getGame();
        MagicStack stack = g.getStack();
        if (stack != null && stack.isResolving()) {
            // The resolving SpellAbility stays on top of the stack until it
            // finishes resolving, so peek is the one being resolved.
            SpellAbility sa = stack.peekAbility();
            if (sa != null && sa.getActivatingPlayer() != null) {
                return sa.getActivatingPlayer();
            }
        }
        Combat combat = g.getCombat();
        if (combat != null) {
            // Match by id: the view has no back-reference to its Card, and the
            // combat's lists are short enough to scan.
            for (Card a : combat.getAttackers()) {
                if (a.getId() != c.getId()) {
                    continue;
                }
                for (Card b : combat.getBlockers(a)) {
                    return b.getController();
                }
                GameEntity def = combat.getDefenderByAttacker(a);
                if (def instanceof Player) {
                    return (Player) def;
                }
                return null;
            }
            for (Card b : combat.getAllBlockers()) {
                if (b.getId() == c.getId()) {
                    return combat.getAttackingPlayer();
                }
            }
        }
        return null;
    }

    private Player topGrudge() {
        Player best = null;
        int bestVal = 0;
        for (Map.Entry<Player, Integer> e : grudge.entrySet()) {
            if (e.getValue() > bestVal && !e.getKey().hasLost()) {
                best = e.getKey();
                bestVal = e.getValue();
            }
        }
        return best;
    }

    private int grudgeOf(Player p) {
        Integer v = grudge.get(p);
        return v == null ? 0 : v;
    }

    private void addGrudge(Player p, int amount) {
        grudge.put(p, clamp(grudgeOf(p) + scale(amount)));
    }

    private boolean isFoe(Player p) {
        Player me = brain.getPlayer();
        return p != null && p != me && !p.hasLost() && me.isOpponentOf(p);
    }

    private Player player(PlayerView pv) {
        if (pv == null) {
            return null;
        }
        Game g = brain.getGame();
        return g == null ? null : g.getPlayer(pv);
    }

    private int scale(int v) {
        return temperament == VOLATILE ? (v * 8 + 4) / 5 : v;   // x1.6, rounded
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    @Override
    public String toString() {
        return "AiMood[" + brain.getPlayer().getName() + " t=" + temperament
                + " anger=" + anger + " morale=" + morale + " grudge=" + grudge + "]";
    }
}
