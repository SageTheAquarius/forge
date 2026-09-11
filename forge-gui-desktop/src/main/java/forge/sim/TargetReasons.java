package forge.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantTarget;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * Why a thing cannot be targeted, in the player's words.
 *
 * Forge answers every targeting question with a boolean: canTarget,
 * canTargetSpellAbility, getNumCandidates. The bridge used those booleans to
 * build prompts that listed only the legal choices, which is correct and
 * unreadable -- the creature you wanted to hit is simply absent, the mode you
 * wanted to pick is simply not there, and nothing on screen says whether the
 * engine forgot it or refused it. Live report (2026-09-11, Lightning Round):
 * "doesn't Untimely Malfunction let me direct Phyrexian Obliterator's ability
 * back at its owner? why can't I do that?" The trigger has no target, so the
 * redirect mode was dropped from the picker (CR 603.3c) and the player was
 * left to guess.
 *
 * Everything here re-asks the questions Forge asked, in the order Forge asks
 * them, and reports the first that fails. The answers are captions, never
 * decisions: a wrong reason is a cosmetic bug, a thrown exception would be a
 * dead prompt, so every entry point swallows and returns something generic.
 *
 * Used by PlayerControllerBridge (target prompts, the mode picker, the
 * cast-refused feed lines, the fizzle watcher) and StateExporter (pre-click
 * greying of hand cards whose only targets live on the stack).
 */
final class TargetReasons {
    private TargetReasons() {}

    // ------------------------------------------------------------ wording ---

    /**
     * The "creature an opponent controls" of a prompt, from the script's
     * TgtPrompt with the "Select target" boilerplate removed; "" if unknown.
     */
    static String wants(SpellAbility sa) {
        try {
            TargetRestrictions tr = sa.getTargetRestrictions();
            if (tr == null || tr.getVTSelection() == null) return "";
            String w = tr.getVTSelection().trim();
            if (w.regionMatches(true, 0, "Select ", 0, 7)) w = w.substring(7).trim();
            if (w.regionMatches(true, 0, "target ", 0, 7)) w = w.substring(7).trim();
            if (w.regionMatches(true, 0, "a target ", 0, 9)) w = w.substring(9).trim();
            return w;
        } catch (Exception e) {
            return "";
        }
    }

    private static String doesNotMatch(SpellAbility sa) {
        String w = wants(sa);
        return w.isEmpty() ? "not the kind of thing this targets" : "doesn't match \"" + w + "\"";
    }

    /** "hexproof" / "protection from red" / "Silent Gravestone: cards in graveyards can't be ...". */
    private static String staticReason(StaticAbility st) {
        try {
            KeywordInterface kw = st.getKeyword();
            if (st.isKeyword(Keyword.HEXPROOF) || st.isKeyword(Keyword.SHROUD)
                    || st.isKeyword(Keyword.PROTECTION)) {
                String title = kw != null ? kw.getTitle() : null;
                if (title == null || title.isEmpty()) {
                    title = st.isKeyword(Keyword.HEXPROOF) ? "Hexproof"
                          : st.isKeyword(Keyword.SHROUD) ? "Shroud" : "Protection";
                }
                return title.substring(0, 1).toLowerCase() + title.substring(1);
            }
            Card host = st.getHostCard();
            String who = host != null ? host.getName() : "an effect";
            String desc = st.toString();
            if (desc == null || desc.trim().isEmpty()) {
                return who + " says it can't be targeted";
            }
            return who + ": " + desc.trim();
        } catch (Exception e) {
            return "a \"can't be the target\" effect";
        }
    }

    // ---------------------------------------------------- board targets ---

    /** Why {@code sa} cannot target {@code ge} right now; null if it can. */
    static String reject(SpellAbility sa, GameEntity ge) {
        try {
            if (ge == null) return "gone";
            if (sa.canTarget(ge)) return null;
            SpellAbility root = sa.getRootAbility() != null ? sa.getRootAbility() : sa;
            if (root.isSpell() && root.getHostCard() == ge) {
                return "a spell can't target itself";
            }
            TargetRestrictions tr = sa.getTargetRestrictions();
            Player activator = sa.getActivatingPlayer();
            Card host = sa.getHostCard();
            if (ge instanceof Card) {
                Card c = (Card) ge;
                if (c.isPhasedOut()) return "phased out";
                if (c.getOwner() != null && !c.getOwner().isInGame()) return "its owner has left the game";
                if (tr != null) {
                    Zone z = c.getZone();
                    if (z != null && tr.getZone() != null && !tr.getZone().contains(z.getZoneType())) {
                        return "not in " + zoneName(z.getZoneType());
                    }
                    if (!c.isValid(tr.getValidTgts(), activator, host, sa)) {
                        return doesNotMatch(sa);
                    }
                }
                StaticAbility st = StaticAbilityCantTarget.cantTarget(c, sa);
                if (st != null) return staticReason(st);
                return "not a legal target for this ability";
            }
            if (ge instanceof Player) {
                Player p = (Player) ge;
                if (p.hasLost()) return "has lost the game";
                if (tr != null) {
                    if (!tr.canTgtPlayer()) return "this doesn't target players";
                    if (!p.isValid(tr.getValidTgts(), activator, host, sa)) {
                        return doesNotMatch(sa);
                    }
                }
                StaticAbility st = StaticAbilityCantTarget.cantTarget(p, sa);
                if (st != null) return staticReason(st);
                return "not a legal target for this ability";
            }
            return "not a legal target for this ability";
        } catch (Exception e) {
            return "not a legal target right now";
        }
    }

    /**
     * The entities in {@code sa}'s target zones that are NOT candidates and
     * are worth a greyed row: first everything refused by a hexproof / shroud
     * / protection / "can't be the target" effect, then -- up to {@code cap}
     * rows in all -- the ones that are the right kind of card but fail the
     * rest of the restriction (your own creature when it wants an opponent's).
     * A Bolt does not list every land as "not a creature".
     */
    static void boardNearMisses(SpellAbility sa, List<GameEntity> candidates,
                                List<GameEntity> misses, List<String> why, int cap) {
        try {
            TargetRestrictions tr = sa.getTargetRestrictions();
            Player activator = sa.getActivatingPlayer();
            if (tr == null || activator == null || activator.getGame() == null) return;
            Game game = activator.getGame();
            Set<GameEntity> have = new HashSet<>(candidates);
            List<GameEntity> closeKind = new ArrayList<>();
            List<String> closeWhy = new ArrayList<>();
            if (tr.canTgtPlayer()) {
                for (Player p : game.getPlayers()) {
                    if (have.contains(p)) continue;
                    String w = reject(sa, p);
                    if (w == null) continue;
                    if (w.startsWith("doesn't match") || w.startsWith("not the kind")) {
                        closeKind.add(p); closeWhy.add(w);
                    } else {
                        misses.add(p); why.add(w);
                    }
                }
            }
            List<ZoneType> zones = tr.getZone();
            if (zones != null && !zones.isEmpty()) {
                for (Card c : game.getCardsIn(zones)) {
                    if (have.contains(c)) continue;
                    if (misses.size() >= cap) break;
                    String w = reject(sa, c);
                    if (w == null) continue;
                    if (w.startsWith("doesn't match") || w.startsWith("not the kind")) {
                        if (baseTypeMatches(sa, tr, c)) {
                            closeKind.add(c); closeWhy.add(w);
                        }
                    } else {
                        misses.add(c); why.add(w);
                    }
                }
            }
            for (int i = 0; i < closeKind.size() && misses.size() < cap; i++) {
                misses.add(closeKind.get(i));
                why.add(closeWhy.get(i));
            }
        } catch (Exception e) {
            // Near misses are decoration on the prompt; the prompt still opens.
        }
    }

    /**
     * True if {@code c} is the right KIND of card for the restriction and only
     * fails a qualifier: "Creature.OppCtrl" -> is it a Creature at all.
     */
    private static boolean baseTypeMatches(SpellAbility sa, TargetRestrictions tr, Card c) {
        try {
            String[] valid = tr.getValidTgts();
            if (valid == null) return false;
            for (String v : valid) {
                int dot = v.indexOf('.');
                String base = dot < 0 ? v : v.substring(0, dot);
                if (base.isEmpty()) continue;
                if (c.isValid(base, sa.getActivatingPlayer(), sa.getHostCard(), sa)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return false;
    }

    private static String zoneName(ZoneType zt) {
        if (zt == null) return "play";
        switch (zt) {
            case Battlefield: return "play";
            case Graveyard:   return "a graveyard";
            case Hand:        return "a hand";
            case Library:     return "a library";
            case Exile:       return "exile";
            case Stack:       return "the stack";
            case Command:     return "the command zone";
            default:          return zt.name().toLowerCase();
        }
    }

    private static String nowIn(ZoneType zt) {
        if (zt == null) return "left play";
        switch (zt) {
            case Battlefield: return "is back on the battlefield as a new object";
            case Graveyard:   return "is now in the graveyard";
            case Hand:        return "is now in its owner's hand";
            case Library:     return "is now in its owner's library";
            case Exile:       return "is now in exile";
            case Stack:       return "is now on the stack";
            case Command:     return "is now in the command zone";
            default:          return "is now in " + zt.name().toLowerCase();
        }
    }

    // ---------------------------------------------------- stack targets ---

    private static SpellAbility unwrap(SpellAbility sa) {
        return sa != null && sa.isWrapper() ? ((WrappedAbility) sa).getWrappedAbility() : sa;
    }

    /** "a spell" / "a triggered ability" / "an activated ability". */
    private static String kindOf(SpellAbility onStack) {
        SpellAbility top = unwrap(onStack);
        if (top == null) return "an ability";
        if (top.isSpell()) return "a spell";
        if (onStack.isTrigger() || top.isTrigger()) return "a triggered ability";
        if (top.isActivatedAbility()) return "an activated ability";
        return "an ability";
    }

    /** "" for a spell, "'s trigger" / "'s ability" for the rest, for stack labels. */
    static String stackKindSuffix(SpellAbility onStack) {
        try {
            SpellAbility top = unwrap(onStack);
            if (top == null || top.isSpell()) return "";
            if (onStack.isTrigger() || top.isTrigger()) return "'s trigger";
            if (top.isActivatedAbility()) return "'s ability";
            return "'s effect";
        } catch (Exception e) {
            return "";
        }
    }

    private static int countTargets(SpellAbility top) {
        int n = 0;
        for (TargetChoices tc : top.getAllTargetChoices()) {
            n += tc.size();
        }
        return n;
    }

    private static boolean usesTargetingAnywhere(SpellAbility top) {
        for (SpellAbility s = top; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) return true;
        }
        return false;
    }

    /** " → Grizzly Bears, AI 1" / " (no target)" -- where a stack item is aimed. */
    static String aimedAt(SpellAbility onStack) {
        try {
            SpellAbility top = unwrap(onStack);
            if (top == null) return "";
            List<String> parts = new ArrayList<>();
            for (TargetChoices tc : top.getAllTargetChoices()) {
                for (GameObject o : tc) {
                    parts.add(describe(o));
                }
            }
            if (parts.isEmpty()) {
                return usesTargetingAnywhere(top) ? " (no target chosen)" : " (no target)";
            }
            return " → " + String.join(", ", parts);
        } catch (Exception e) {
            return "";
        }
    }

    /** A target, wherever it lives, by name. */
    static String describe(GameObject o) {
        if (o instanceof Card) return ((Card) o).getName();
        if (o instanceof Player) return ((Player) o).getName();
        if (o instanceof SpellAbility) {
            SpellAbility s = (SpellAbility) o;
            Card h = s.getHostCard();
            return (h != null ? h.getName() : String.valueOf(s)) + stackKindSuffix(s);
        }
        return String.valueOf(o);
    }

    /** "Spell.YouDontCtrl" -> "a spell you don't control", for the reasons below. */
    private static String readableTargetType(String tt) {
        List<String> kinds = new ArrayList<>();
        String qual = "";
        for (String part : tt.split(",")) {
            String p = part.trim();
            int dot = p.indexOf('.');
            String base = dot < 0 ? p : p.substring(0, dot);
            String rest = dot < 0 ? "" : p.substring(dot + 1);
            switch (base) {
                case "Spell":        kinds.add("a spell"); break;
                case "Activated":    kinds.add("an activated ability"); break;
                case "Triggered":    kinds.add("a triggered ability"); break;
                case "SpellAbility": kinds.add("a spell or ability"); break;
                default:             kinds.add(base.toLowerCase()); break;
            }
            if (rest.contains("YouDontCtrl")) qual = " you don't control";
            else if (rest.contains("OppCtrl")) qual = " an opponent controls";
            else if (rest.contains("YouCtrl")) qual = " you control";
            if (rest.contains("singleTarget")) qual += " with a single target";
        }
        return String.join(" or ", kinds) + qual;
    }

    /** Why {@code sa} cannot target the stack item {@code onStack}; never null. */
    static String stackReject(SpellAbility sa, SpellAbility onStack) {
        try {
            SpellAbility top = unwrap(onStack);
            if (top == null) return "not a legal target";
            if (sa.equals(top)) return "a spell can't target itself";
            TargetRestrictions tr = sa.getTargetRestrictions();
            Player activator = sa.getActivatingPlayer();
            Card host = sa.getHostCard();
            String tt = sa.getParam("TargetType");
            if (tt != null && !top.isValid(tt.split(","), activator, host, sa)) {
                if (tt.contains("singleTarget")) {
                    int n = countTargets(top);
                    if (n == 0) return "has no target";
                    if (n > 1) return "has " + n + " targets (needs exactly one)";
                }
                return "is " + kindOf(onStack) + " (needs " + readableTargetType(tt) + ")";
            }
            if (tr != null && tr.getSAValidTargeting() != null) {
                return "isn't targeting the right kind of thing (needs one aimed at "
                        + tr.getSAValidTargeting().replace(",", " or ").toLowerCase() + ")";
            }
            Card th = top.getHostCard();
            if (th != null && th.isImmutable() && !th.isEmblem() && th.getEffectSource() == null) {
                return "is an effect, not a spell or ability";
            }
            if (tr != null) {
                Card src = th != null && th.isImmutable() && !th.isEmblem() && th.getEffectSource() != null
                        ? th.getEffectSource() : th;
                if (src != null && !src.isValid(tr.getValidTgts(), activator, host, sa)) {
                    return doesNotMatch(sa);
                }
            }
            return "not a legal target for this ability";
        } catch (Exception e) {
            return "not a legal target right now";
        }
    }

    // ------------------------------------------------------------- modes ---

    /** The Choices of a modal spell that Forge did NOT offer this time. */
    static List<AbilitySub> missingModes(SpellAbility charm, List<AbilitySub> offered) {
        List<AbilitySub> out = new ArrayList<>();
        try {
            List<AbilitySub> all = charm.getAdditionalAbilityList("Choices");
            if (all == null) return out;
            for (AbilitySub ch : all) {
                boolean present = false;
                String d = ch.getDescription();
                for (AbilitySub o : offered) {
                    if (o == ch || (d != null && d.equals(o.getDescription()))) {
                        present = true;
                        break;
                    }
                }
                if (!present) out.add(ch);
            }
        } catch (Exception e) {
            // An odd script shows what Forge offered and nothing more.
        }
        return out;
    }

    /**
     * Why CharmEffect.makePossibleOptions dropped this mode: chosen before
     * under a ChoiceRestriction, or a mandatory target with no candidate --
     * and for a stack target, WHICH stack items were refused and why, because
     * that is the whole question ("the trigger is right there, why not").
     */
    static String missingModeReason(SpellAbility charm, AbilitySub mode, Player me) {
        try {
            if (charm.hasParam("ChoiceRestriction")) {
                List<String> chosen = charm.getHostCard().getChosenModes(charm, charm.getParam("ChoiceRestriction"));
                if (chosen != null && chosen.contains(mode.getDescription())) {
                    return "already chosen";
                }
            }
            String why = StateExporter.noTargetReason(mode);
            if (why == null) return "not available right now";
            String detail = stackDetail(mode, me);
            return detail.isEmpty() ? why : why + ": " + detail;
        } catch (Exception e) {
            return "not available right now";
        }
    }

    /** "Phyrexian Obliterator's trigger [AI 1] - has no target; ..." for a stack-targeting part. */
    private static String stackDetail(SpellAbility root, Player me) {
        try {
            Game game = me != null ? me.getGame() : null;
            if (game == null) return "";
            for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
                if (!s.usesTargeting()) continue;
                TargetRestrictions tr = s.getTargetRestrictions();
                List<ZoneType> zones = tr.getZone();
                if (zones == null || zones.size() != 1 || zones.get(0) != ZoneType.Stack) continue;
                List<String> parts = new ArrayList<>();
                for (SpellAbilityStackInstance si : game.getStack()) {
                    SpellAbility on = si.getSpellAbility();
                    if (s.canTargetSpellAbility(on)) continue;
                    parts.add(PlayerControllerBridge.describeStackTarget(on) + " - " + stackReject(s, on));
                }
                return parts.isEmpty() ? "the stack is empty" : String.join("; ", parts);
            }
        } catch (Exception e) {
            // no detail
        }
        return "";
    }

    /**
     * For a modal spell that cannot be cast because fewer modes than it needs
     * have a legal target (CharmEffect.makeChoices returns false, silently):
     * "no mode can be chosen: 'Destroy target artifact' - no legal target ...".
     * Null for anything else, including a castable charm.
     */
    static String noModeReason(SpellAbility sa, Player me) {
        try {
            if (sa == null || sa.getApi() != ApiType.Charm) return null;
            List<AbilitySub> possible = CharmEffect.makePossibleOptions(sa);
            Card source = sa.getHostCard();
            int num = AbilityUtils.calculateAmount(source, sa.getParamOrDefault("CharmNum", "1"), sa);
            int min = sa.hasParam("MinCharmNum")
                    ? AbilityUtils.calculateAmount(source, sa.getParam("MinCharmNum"), sa) : num;
            if (sa.hasParam("CanRepeatModes") || possible.size() >= min) return null;
            List<String> parts = new ArrayList<>();
            for (AbilitySub ch : missingModes(sa, possible)) {
                parts.add("\"" + ch.getDescription() + "\" - " + missingModeReason(sa, ch, me));
            }
            String head = possible.isEmpty() ? "no mode can be chosen"
                    : "needs " + min + " modes and only " + possible.size() + " can be chosen";
            return parts.isEmpty() ? head : head + ": " + String.join("; ", parts);
        } catch (Exception e) {
            return null;
        }
    }

    // --------------------------------------------------- refused casts ---

    /**
     * The first reason one of a card's abilities is not playable, asking the
     * questions in the order Forge's playability filter asks them: timing and
     * cast restrictions (canPlay), a mandatory target with nothing legal, then
     * mana. Mana abilities are skipped -- nobody clicks a land to be told its
     * mana ability can't be paid for. Null when every question passes, which
     * means Forge refused for a reason this cannot see.
     */
    static String unplayableReason(List<SpellAbility> any, Player me) {
        try {
            for (SpellAbility sa : any) {
                if (sa == null || sa.isManaAbility()) continue;
                if (sa.getActivatingPlayer() == null) sa.setActivatingPlayer(me);
                if (!sa.canPlay()) {
                    if (!me.canCastSorcery()) {
                        Game g = me.getGame();
                        boolean myTurn = g != null && g.getPhaseHandler() != null
                                && g.getPhaseHandler().isPlayerTurn(me);
                        return myTurn ? "sorcery speed - needs your main phase with an empty stack"
                                      : "sorcery speed - only on your own turn, in a main phase";
                    }
                    return "a restriction on the card isn't met right now (wrong zone, "
                            + "already used this turn, or a condition in its text)";
                }
                String modes = noModeReason(sa, me);
                if (modes != null) return modes;
                String tgt = StateExporter.noTargetReason(sa);
                if (tgt != null) {
                    String detail = stackDetail(sa, me);
                    return detail.isEmpty() ? tgt : tgt + ": " + detail;
                }
                String mana = StateExporter.unpayableReason(sa, me, null);
                if (mana != null) return mana;
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    // ---------------------------------------------------------- fizzles ---

    private static final Set<Game> WATCHED = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Explain fizzles for this game, once. Forge's own log line is "X ability
     * fizzles." with no reason; this adds "X fizzled: Grizzly Bears is now in
     * the graveyard" from a snapshot of every stack item's targets, refreshed
     * whenever the stack changes (a redirect that resolves refreshes too).
     */
    static void watchFizzles(Game g) {
        if (g == null) return;
        synchronized (WATCHED) {
            if (!WATCHED.add(g)) return;
        }
        try {
            g.subscribeToEvents(new FizzleWatch(g));
        } catch (Exception e) {
            System.out.println("[forge-bridge] fizzle watch not installed: " + e);
        }
    }

    /**
     * Subscribed on the game's event bus (synchronous, on the game thread --
     * ForgeServer.HumanDeathWatch relies on the same). GameEventSpellResolved
     * fires after MagicStack.hasFizzled has already stripped the illegal
     * targets off the ability, so the targets are remembered from the last
     * time the stack changed and the reason is worked out per remembered
     * target against the current board.
     */
    static final class FizzleWatch {
        private final Game game;
        private Map<Integer, List<GameObject>> aimed = new HashMap<>();

        FizzleWatch(Game game) {
            this.game = game;
        }

        @Subscribe
        @SuppressWarnings("unused")
        public void onCast(GameEventSpellAbilityCast ev) {
            refresh();
        }

        @Subscribe
        @SuppressWarnings("unused")
        public void onResolved(GameEventSpellResolved ev) {
            try {
                if (ev.hasFizzled()) explain(ev);
            } catch (Exception e) {
                // a caption, never worth a stack trace in the game thread
            }
            refresh();
        }

        private void refresh() {
            try {
                Map<Integer, List<GameObject>> next = new HashMap<>();
                for (SpellAbilityStackInstance si : game.getStack()) {
                    SpellAbility sa = si.getSpellAbility();
                    if (sa == null) continue;
                    SpellAbility top = unwrap(sa);
                    List<GameObject> targets = new ArrayList<>();
                    for (TargetChoices tc : top.getAllTargetChoices()) {
                        targets.addAll(tc);
                    }
                    next.put(sa.getId(), targets);
                }
                aimed = next;
            } catch (Exception e) {
                // keep the previous snapshot
            }
        }

        private void explain(GameEventSpellResolved ev) {
            int id = ev.spell() != null ? ev.spell().getId() : -1;
            List<GameObject> was = aimed.get(id);
            if (was == null || was.isEmpty()) return;
            SpellAbility sa = null;
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (si.getSpellAbility() != null && si.getSpellAbility().getId() == id) {
                    sa = unwrap(si.getSpellAbility());
                    break;
                }
            }
            String name = sa != null && sa.getHostCard() != null ? sa.getHostCard().getName()
                    : ev.spell() != null && ev.spell().getHostCard() != null
                        ? ev.spell().getHostCard().getName() : "The spell";
            List<String> parts = new ArrayList<>();
            for (GameObject o : was) {
                parts.add(describe(o) + " " + goneReason(sa, o));
            }
            game.fireEvent(new GameEventAddLog(GameLogEntryType.INFORMATION,
                    name + " fizzled - " + String.join("; ", parts) + "."));
        }

        /** Why a remembered target is illegal now, as a predicate on its name. */
        private String goneReason(SpellAbility sa, GameObject o) {
            try {
                if (o instanceof Card) {
                    Card card = (Card) o;
                    Card current = game.getCardState(card, null);
                    if (current == null) return "is gone";
                    if (!current.equalsWithGameTimestamp(card)) {
                        Zone z = current.getZone();
                        return nowIn(z != null ? z.getZoneType() : null);
                    }
                    if (sa != null) {
                        String w = reject(sa, current);
                        if (w != null) return "- " + w;
                    }
                    return "is no longer a legal target";
                }
                if (o instanceof Player) {
                    Player p = (Player) o;
                    if (p.hasLost()) return "has lost the game";
                    if (sa != null) {
                        String w = reject(sa, p);
                        if (w != null) return "- " + w;
                    }
                    return "is no longer a legal target";
                }
                if (o instanceof SpellAbility) {
                    SpellAbility t = (SpellAbility) o;
                    for (SpellAbilityStackInstance si : game.getStack()) {
                        if (si.getSpellAbility() == t || si.getSpellAbility().getId() == t.getId()) {
                            return sa != null ? "- " + stackReject(sa, si.getSpellAbility())
                                              : "is no longer a legal target";
                        }
                    }
                    return "already resolved or was countered";
                }
            } catch (Exception e) {
                // fall through
            }
            return "is no longer a legal target";
        }
    }
}
