package forge.ai;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;

import com.google.common.collect.Multimap;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayEnergy;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityRestriction;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import java.util.Map;
import java.util.WeakHashMap;

public class ComputerUtilAbility {
    public static CardCollection getAvailableLandsToPlay(final Game game, final Player player) {
        if (!game.getStack().isEmpty() || !game.getPhaseHandler().getPhase().isMain()) {
            return null;
        }

        //filter out cards that can't be played
        CardCollection landList = CardLists.filter(player.getCardsIn(ZoneType.Hand), c -> {
            if (!c.hasPlayableLandFace()) {
                return false;
            }
            return player.canPlayLand(c, false, c.getFirstSpellAbility());
        });

        final CardCollection landsNotInHand = new CardCollection(player.getCardsIn(ZoneType.Graveyard));
        landsNotInHand.addAll(game.getCardsIn(ZoneType.Exile));
        if (!player.getCardsIn(ZoneType.Library).isEmpty()) {
            landsNotInHand.add(player.getCardsIn(ZoneType.Library).get(0));
        }
        for (final Card crd : landsNotInHand) {
            if (!(crd.hasPlayableLandFace() || (crd.isFaceDown() && crd.getState(CardStateName.Original).getType().isLand()))) {
                continue;
            }
            if (!crd.mayPlay(player).isEmpty()) {
                landList.add(crd);
            }
        }
        return landList;
    }

    private static final boolean TRIM_ZONES = System.getProperty("ai.trimzones") != null;

    /** Could this player conceivably do anything with a card they do not control? */
    private static boolean couldMatter(final Card c, final Player player) {
        // Cheap test first. mayPlay() walks the static abilities that grant play
        // permission and is dear enough that asking it per card cost more than
        // the whole filter saved -- measured at 58% SLOWER with the order
        // reversed. The stored ability list is just a field read.
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && activatableByAnyone(sa)) {
                return true;                   // e.g. "any player may activate"
            }
        }
        return !c.mayPlay(player).isEmpty();    // explicitly granted permission
    }

    /**
     * Could a player who does NOT control this card activate this ability?
     *
     * This is what made the filter above worth having. It used to return true
     * for any activated ability at all, and the comment claimed that meant
     * "any player may activate" -- but an ordinary tap-for-mana on an opponent's
     * Forest is an activated ability too, and every land has one. So the filter
     * kept essentially the whole battlefield, dropped almost nothing, and
     * measured as pure noise against the no-filter arm.
     *
     * SpellAbilityVariables.activator defaults to "You", and
     * checkActivatorRestrictions resolves that against the card's CONTROLLER:
     *
     *     activator.isValid(getActivator(), c.getController(), c, sa)
     *
     * so a default ability on a card you do not control is never activatable by
     * you, and building its full ability list -- alternative-cost expansion and
     * all -- can only produce candidates canPlay() rejects later anyway. Cards
     * that really are open to the table say so explicitly (Activator$ Player),
     * and those are kept.
     *
     * Costs one string comparison per ability, against a stored field.
     */
    private static boolean activatableByAnyone(final SpellAbility sa) {
        SpellAbilityRestriction r = sa.getRestrictions();
        if (r == null) {
            return true;        // no restrictions to read: keep it, be safe
        }
        String who = r.getActivator();
        return who != null && !"You".equals(who);
    }

    public static CardCollection getAvailableCards(final Game game, final Player player) {
        CardCollection all = new CardCollection(player.getCardsIn(ZoneType.Hand));

        all.addAll(player.getCardsIn(ZoneType.Graveyard));
        for (Player p : game.getPlayers()) {
            if (!p.getCardsIn(ZoneType.Library).isEmpty()) {
                all.add(p.getCardsIn(ZoneType.Library).get(0));
            }
        }
        all.addAll(game.getCardsIn(ZoneType.Command));
        all.addAll(game.getCardsIn(ZoneType.Exile));
        // ---- lever 2: skip opponents' permanents that offer this player nothing ----
        //
        // Off unless -Dai.trimzones is set. The battlefield is the bulk of this
        // list -- 48 to 58 of 63 to 72 cards in the pod that was measured -- and
        // most of it belongs to opponents. getSpellAbilities then runs the full
        // getAllPossibleAbilities, including alternative-cost expansion, over
        // every one of them.
        //
        // An opponent's card is kept if it has an activated ability THIS player
        // could actually activate (see activatableByAnyone), or if they have
        // been granted permission to play it. Everything else can only yield
        // candidates canPlay() rejects later. Cheap to decide:
        // getSpellAbilities() is the stored list, with none of the
        // alternative-cost work that makes the full build expensive.
        //
        // The "could actually activate" part is the whole lever. Keeping every
        // card with any activated ability kept every LAND -- they all tap for
        // mana -- so the filter dropped almost nothing and measured as noise
        // against the unfiltered arm.
        if (TRIM_ZONES) {
            for (final Card c : game.getCardsIn(ZoneType.Battlefield)) {
                if (c.getController() == player || couldMatter(c, player)) {
                    all.add(c);
                }
            }
        } else {
            all.addAll(game.getCardsIn(ZoneType.Battlefield));
        }
        return all;
    }

    /**
     * Log a slow candidate-ability build, broken down by the zone the cards came
     * from. Off unless it actually is slow, so it costs a nanoTime in the normal
     * case.
     *
     * Why it exists: a live four-player pod went 1.0s to 226.8s of engine time
     * per turn over five turns, and eleven thread dumps taken while it was slow
     * had NO "Game AI Eval" thread at all -- the time was on the main thread,
     * inside this method, under Card.getAllPossibleAbilities and
     * GameActionUtil.getAlternativeCosts. That is BEFORE the AI_TIMEOUT-guarded
     * section, which is why capping that budget would not have touched it.
     *
     * getAvailableCards feeds this hand + graveyard + every library's top card +
     * every command zone + EVERY player's exile + every battlefield, so the two
     * zones that only ever grow are in it, and a board wipe moves a whole
     * battlefield into one of them. Which zone actually dominates could not be
     * reproduced in 45 turns of seeded local pods, so this reports it from the
     * real game rather than being guessed at.
     */
    /**
     * Report threshold, in ms. 150 in production so the log stays quiet.
     *
     * Configurable because the default hides exactly the measurement a fix
     * needs. A 47-permanent board -- the width of the live game that was slow
     * -- built in under 150ms on a dev box and logged NOTHING, so there was no
     * way to tell whether a lever helped, or even what the build cost was.
     * Matches forge.slowstatic.ms, which exists for the same reason.
     */
    private static final long SLOW_BUILD_MS = Long.getLong("forge.sabuild.ms", 150L);

    private static void reportSlowBuild(CardCollectionView all, Player activator, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1000000L;
        if (ms <= SLOW_BUILD_MS) {
            return;
        }
        int hand = 0, graveyard = 0, exile = 0, battlefield = 0, command = 0, other = 0;
        for (final Card c : all) {
            Zone z = c.getGame() == null ? null : c.getGame().getZoneOf(c);
            ZoneType zt = z == null ? null : z.getZoneType();
            if (zt == ZoneType.Hand) hand++;
            else if (zt == ZoneType.Graveyard) graveyard++;
            else if (zt == ZoneType.Exile) exile++;
            else if (zt == ZoneType.Battlefield) battlefield++;
            else if (zt == ZoneType.Command) command++;
            else other++;
        }
        // Seat and phase, because the cost is bimodal: in a 36-turn pod, 225
        // builds sat at ~300ms while 66 ran 1-5s and accounted for 73% of all
        // build time. Without knowing WHICH seat and WHICH phase the slow ones
        // land in, there is no way to tell a broad scaling problem from one
        // seat's board or one phase's re-entry, and those want different fixes.
        String seat = activator == null ? "?" : activator.getName();
        String phase = "?";
        try {
            if (activator != null && activator.getGame() != null) {
                phase = String.valueOf(activator.getGame().getPhaseHandler().getPhase());
            }
        } catch (Exception ignored) {
            // a diagnostic must never be the thing that breaks a game
        }
        System.out.println("[SA-BUILD] " + ms + "ms cards=" + all.size()
                + " seat=" + seat + " phase=" + phase
                + " hand=" + hand + " graveyard=" + graveyard + " exile=" + exile
                + " battlefield=" + battlefield + " command=" + command
                + " other=" + other);
        System.out.flush();
    }

    // ---- lever 1: reuse the candidate list while the board has not moved ----
    //
    // Off unless -Dai.sacache is set. In a 36-turn four-player pod this method
    // was 51% of all engine time (271.8s of 533.8s), rebuilt from scratch at
    // every priority window for every AI seat -- and between consecutive windows
    // in a phase (a chain of triggers resolving, everyone passing) the input is
    // usually identical.
    //
    // The correctness risk, stated plainly: the returned SpellAbility objects
    // are live. The card's own SAs are the same instances every call already, but
    // the alternative-cost SAs are freshly allocated per call, and downstream
    // code sets activating player, last-state and targets on them. Reusing them
    // across windows could therefore carry state forward. The list itself is
    // copied out because callers removeIf/removeAll on it.
    //
    // This is why it is flag-gated rather than simply switched on: the seeded
    // replay (-Dbridge.seed with -Dbridge.notimeout) plays a deterministic game,
    // so a safe cache must reproduce it EXACTLY -- same window count per turn,
    // same decisions. Any divergence means the AI saw a stale list and the cache
    // must be rejected, not tuned.
    private static final boolean SA_CACHE_ON = System.getProperty("ai.sacache") != null;
    private static final Map<Player, Object[]> SA_CACHE = new WeakHashMap<>();
    /** Last observed build cost per player, in ms. Drives the gate below. */
    private static final Map<Player, Long> SA_LAST_MS = new WeakHashMap<>();
    /**
     * Only cache for a player whose builds are actually expensive.
     *
     * Measured: on a small board the cache made a seeded 22-turn replay 15%
     * SLOWER (15.8s -> 18.2s), because cacheKey walks every card to build a
     * string and a ~20ms build cannot repay that. On a 30-turn replay with a
     * developed board the same cache was 36% faster. So the cache is not
     * universally good -- it is good exactly where the builds are slow, which
     * is the 66 builds >=1s that made up 73% of build time in the real game.
     *
     * Below the threshold this method costs one map lookup and nothing else.
     */
    private static final long SA_CACHE_MIN_MS = 100L;

    /** Cheap key: what the list depends on, without computing any of it. */
    private static String cacheKey(final CardCollectionView all, final Player activator) {
        Game game = activator.getGame();
        StringBuilder b = new StringBuilder(256);
        b.append(game.getTimestamp()).append('|')
         .append(game.getPhaseHandler().getTurn()).append('|')
         .append(game.getPhaseHandler().getPhase()).append('|')
         .append(game.getStack().size()).append('|');
        for (final Card c : all) {
            Zone z = game.getZoneOf(c);
            b.append(c.getId()).append(':')
             .append(z == null ? "?" : z.getZoneType()).append(':')
             .append(c.getLayerTimestamp()).append(',');
        }
        return b.toString();
    }

    public static List<SpellAbility> getSpellAbilities(final CardCollectionView all, final Player activator) {
        if (SA_CACHE_ON && activator != null && wasExpensive(activator)) {
            String key = cacheKey(all, activator);
            Object[] hit = SA_CACHE.get(activator);
            if (hit != null && key.equals(hit[0])) {
                @SuppressWarnings("unchecked")
                List<SpellAbility> cached = (List<SpellAbility>) hit[1];
                // Copy: AiController does removeIf/removeAll on what it gets back.
                return Lists.newArrayList(cached);
            }
            List<SpellAbility> built = buildSpellAbilities(all, activator);
            SA_CACHE.put(activator, new Object[] {key, built});
            return Lists.newArrayList(built);
        }
        return buildSpellAbilities(all, activator);
    }

    /** True once this player has had at least one build worth caching. */
    private static boolean wasExpensive(final Player activator) {
        Long last = SA_LAST_MS.get(activator);
        return last != null && last >= SA_CACHE_MIN_MS;
    }

    private static List<SpellAbility> buildSpellAbilities(final CardCollectionView all, final Player activator) {
        final long startNanos = System.nanoTime();
        try {
        final List<SpellAbility> spellAbilities = Lists.newArrayList();
        for (final Card c : all) {
            Multimap<SpellAbility, SpellAbility> unhiddenAltCost = ArrayListMultimap.create();
            List<SpellAbility> possible = c.getAllPossibleAbilities(activator, false, unhiddenAltCost);
            for (SpellAbility sa : unhiddenAltCost.keySet()) {
                if (possible.contains(sa)) {
                    // when SA can also be played as basic exclude its AltCost to prevent redundant check later
                    possible.removeAll(unhiddenAltCost.get(sa));
                }
            }
            spellAbilities.addAll(possible);
        }
        return spellAbilities;
        } finally {
            long ms = (System.nanoTime() - startNanos) / 1000000L;
            AiPerf.buildN.increment();
            AiPerf.buildMs.add(ms);
            if (activator != null) {
                SA_LAST_MS.put(activator, ms);
            }
            reportSlowBuild(all, activator, startNanos);
        }
    }

    public static List<SpellAbility> getOriginalAndAltCostAbilities(final List<SpellAbility> originList, final Player activator) {
        List<SpellAbility> originListWithAddCosts = Lists.newArrayList();
        for (SpellAbility sa : originList) {
            // If this spell has alternative additional costs, add them instead of the unmodified SA itself
            sa.setActivatingPlayer(activator);
            originListWithAddCosts.addAll(GameActionUtil.getAdditionalCostSpell(sa));
        }

        final List<SpellAbility> newAbilities = Lists.newArrayList();
        for (SpellAbility sa : originListWithAddCosts) {
            // determine which alternative costs are cheaper than the original and prioritize them
            List<SpellAbility> saAltCosts = GameActionUtil.getAlternativeCosts(sa, activator, false);
            List<SpellAbility> priorityAltSa = Lists.newArrayList();
            List<SpellAbility> otherAltSa = Lists.newArrayList();
            for (SpellAbility altSa : saAltCosts) {
                if (sa.getPayCosts().isOnlyManaCost()
                        && altSa.getPayCosts().isOnlyManaCost() && sa.getPayCosts().getTotalMana().compareTo(altSa.getPayCosts().getTotalMana()) == 1) {
                    // the alternative cost is strictly cheaper, so why not? (e.g. Omniscience etc.)
                    priorityAltSa.add(altSa);
                } else {
                    otherAltSa.add(altSa);
                }
            }

            // add alternative costs as additional spell abilities
            newAbilities.addAll(priorityAltSa);
            newAbilities.add(sa);
            newAbilities.addAll(otherAltSa);
        }

        final List<SpellAbility> result = Lists.newArrayList();
        for (SpellAbility sa : newAbilities) {
            sa.setActivatingPlayer(activator);

            // Optional cost selection through the AI controller
            boolean choseOptCost = false;
            List<OptionalCostValue> list = GameActionUtil.getOptionalCostValues(sa);
            if (!list.isEmpty()) {
                list = activator.getController().chooseOptionalCosts(sa, list);
                if (!list.isEmpty()) {
                    // still check base spell first in case of Promise Gift
                    if (list.stream().anyMatch(ocv -> ocv.getType().equals(OptionalCost.PromiseGift))) {
                        result.add(sa);
                    }
                    result.add(GameActionUtil.addOptionalCosts(sa, list));
                    choseOptCost = true;
                }
            }

            // Add only one ability: either the one with preferred optional costs, or the original one if there are none
            if (!choseOptCost) {
                result.add(sa);
            }
        }

        return result;
    }

    public static SpellAbility getTopSpellAbilityOnStack(Game game, SpellAbility sa) {
        Iterator<SpellAbilityStackInstance> it = game.getStack().iterator();

        if (!it.hasNext()) {
            return null;
        }

        SpellAbility tgtSA = it.next().getSpellAbility();
        // Grab the topmost spellability that isn't this SA and use that for comparisons
        if (sa.equals(tgtSA) && game.getStack().size() > 1) {
            if (!it.hasNext()) {
                return null;
            }
            tgtSA = it.next().getSpellAbility();
        }
        return tgtSA;
    }

    public static SpellAbility getFirstCopySASpell(List<SpellAbility> spells) {
        SpellAbility sa = null;
        for (SpellAbility spell : spells) {
            if (spell.getApi() == ApiType.CopySpellAbility) {
                sa = spell;
                break;
            }
        }
        return sa;
    }

    public static Card getAbilitySource(SpellAbility sa) {
        return sa.getOriginalHost() != null ? sa.getOriginalHost() : sa.getHostCard();
    }

    public static String getAbilitySourceName(SpellAbility sa) {
        final Card c = getAbilitySource(sa);
        return c != null ? c.getName() : "";
    }

    public static CardCollection getCardsTargetedWithApi(Player ai, CardCollection cardList, SpellAbility sa, ApiType api) {
        // Returns a collection of cards which have already been targeted with the given API either in the parent ability,
        // in the sub ability, or by something on stack. If "sa" is specified, the parent and sub abilities of this SA will
        // be checked for targets. If "sa" is null, only the stack instances will be checked.
        CardCollection targeted = new CardCollection();
        if (sa != null) {
            SpellAbility saSub = sa.getRootAbility();
            while (saSub != null) {
                if (saSub.getApi() == api && saSub.getTargets() != null) {
                    for (Card c : cardList) {
                        if (saSub.getTargets().getTargetCards().contains(c)) {
                            // Was already targeted with this API in a parent or sub SA
                            targeted.add(c);
                        }
                    }
                }
                saSub = saSub.getSubAbility();
            }
        }
        for (SpellAbilityStackInstance si : ai.getGame().getStack()) {
            SpellAbility ab = si.getSpellAbility();
            if (ab != null && ab.getApi() == api && si.getTargetChoices() != null) {
                for (Card c : cardList) {
                    // TODO: somehow ensure that the detected SA won't be countered
                    if (si.getTargetChoices().getTargetCards().contains(c)) {
                        // Was already targeted by a spell ability instance on stack
                        targeted.add(c);
                    }
                }
            }
        }

        return targeted;
    }

    public static boolean isFullyTargetable(SpellAbility sa) {
        SpellAbility sub = sa;
        while (sub != null) {
            if (sub.usesTargeting() && sub.getTargetRestrictions().getNumCandidates(sub) < sub.getMinTargets()) {
                return false;
            }
            sub = sub.getSubAbility();
        }
        return true;
    }

    public final static saComparator saEvaluator = new saComparator();

    public final static class saComparator implements Comparator<SpellAbility> {
        @Override
        public int compare(final SpellAbility a, final SpellAbility b) {
            return compareEvaluator(a, b, false);
        }
        public int compareEvaluator(final SpellAbility a, final SpellAbility b, boolean safeToEvaluateCreatures) {
            // we want the highest costs first
            // TODO support alternative strategies like going wide with attackers
            int a1 = a.getPayCosts().getTotalMana().getCMC();
            int b1 = b.getPayCosts().getTotalMana().getCMC();

            // deprioritize SAs explicitly marked as preferred to be activated last compared to all other SAs
            if (a.hasParam("AIActivateLast") && !b.hasParam("AIActivateLast")) {
                return 1;
            }
            if (b.hasParam("AIActivateLast") && !a.hasParam("AIActivateLast")) {
                return -1;
            }

            // deprioritize planar die roll marked with AIRollPlanarDieParams:LowPriority$ True
            if (ApiType.RollPlanarDice == a.getApi() || ApiType.RollPlanarDice == b.getApi()) {
                Card hostCardForGame = a.getHostCard();
                if (hostCardForGame == null) {
                    if (b.getHostCard() != null) {
                        hostCardForGame = b.getHostCard();
                    } else {
                        return 0; // fallback if neither SA have a host card somehow
                    }
                }
                Game game = hostCardForGame.getGame();
                if (game.getActivePlanes() != null) {
                    for (Card c : game.getActivePlanes()) {
                        if (c.hasSVar("AIRollPlanarDieParams") && c.getSVar("AIRollPlanarDieParams").toLowerCase().matches(".*lowpriority\\$\\s*true.*")) {
                            if (ApiType.RollPlanarDice == a.getApi()) {
                                return 1;
                            }
                            return -1;
                        }
                    }
                }
            }

            // deprioritize pump spells with pure energy cost (can be activated last,
            // since energy is generally scarce, plus can benefit e.g. Electrostatic Pummeler)
            int a2 = 0, b2 = 0;
            if (a.getApi() == ApiType.Pump && a.getPayCosts().getCostEnergy() != null) {
                if (a.getPayCosts().hasOnlySpecificCostType(CostPayEnergy.class)) {
                    a2 = a.getPayCosts().getCostEnergy().convertAmount();
                }
            }
            if (b.getApi() == ApiType.Pump && b.getPayCosts().getCostEnergy() != null) {
                if (b.getPayCosts().hasOnlySpecificCostType(CostPayEnergy.class)) {
                    b2 = b.getPayCosts().getCostEnergy().convertAmount();
                }
            }
            if (a2 == 0 && b2 > 0) {
                return -1;
            }
            if (b2 == 0 && a2 > 0) {
                return 1;
            }

            // use 0 cmc abilities first (might be a Mox)
            if (a1 == 0 && b1 > 0 && ApiType.Mana != a.getApi()) {
                return -1;
            }
            if (a1 > 0 && b1 == 0 && ApiType.Mana != b.getApi()) {
                return 1;
            }

            if (a.getHostCard() != null && a.getHostCard().hasSVar("FreeSpellAI")) {
                return -1;
            }
            if (b.getHostCard() != null && b.getHostCard().hasSVar("FreeSpellAI")) {
                return 1;
            }

            if (a.getHostCard().equals(b.getHostCard()) && a.getApi() == b.getApi()) {
                // Cheaper Spectacle costs should be preferred
                // FIXME: Any better way to identify that these are the same ability, one with Spectacle and one not?
                // (looks like it's not a full-fledged alternative cost as such, and is not processed with other alt costs)
                if (a.isSpectacle() && !b.isSpectacle() && a1 < b1) {
                    return 1;
                }
                if (b.isSpectacle() && !a.isSpectacle() && b1 < a1) {
                    return 1;
                }
            }

            a1 += getSpellAbilityPriority(a);
            b1 += getSpellAbilityPriority(b);

            // if both are creature spells sort them after
            if (safeToEvaluateCreatures) {
                // try to align the scales: if priority swings in either direction extra evaluation matters less
                a1 += Math.round(ComputerUtilCard.evaluateCreature(a) / (10.5f + Math.abs(a1)));
                b1 += Math.round(ComputerUtilCard.evaluateCreature(b) / (10.5f + Math.abs(b1)));
            }

            return b1 - a1;
        }

        private static int getSpellAbilityPriority(SpellAbility sa) {
            int p = 0;
            Card source = sa.getHostCard();
            final Player ai = source == null ? sa.getActivatingPlayer() : source.getController();
            if (ai == null) {
                System.err.println("Error: couldn't figure out the activating player and host card for SA: " + sa);
                return 0;
            }
            final boolean noCreatures = ai.getCreaturesInPlay().isEmpty();

            if (source != null) {
                // puts creatures in front of spells
                if (source.isCreature()) {
                    p += 1;
                }
                if (ComputerUtilCard.isCardRemAIDeck(sa.getOriginalHost() != null ? sa.getOriginalHost() : source)) {
                    p -= 10;
                }
                if (source.hasSVar("AIPriorityModifier")) {
                    p += Integer.parseInt(source.getSVar("AIPriorityModifier"));
                }
                // try to use it before it's gone
                if (source.isInPlay() && source.hasSVar("EndOfTurnLeavePlay")) {
                    p += 1;
                }
                // prefer spells from hand when it can lower risk of discarding
                if (source.isInZone(ZoneType.Hand) && !ai.isUnlimitedHandSize()) {
                    p += Math.max(0, CardLists.count(ai.getCardsIn(ZoneType.Hand), c -> !c.hasSVar("DiscardMe")) - ai.getMaxHandSize());
                }
                // don't play equipment before having any creatures
                if (source.isEquipment() && noCreatures) {
                    p -= 9;
                }
                // don't equip stuff in main 2 if there's more stuff to cast at the moment
                if (sa.getApi() == ApiType.Attach && !sa.isCurse() && source.getGame().getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
                    p -= 1;
                }
                // 1. increase chance of using Surge effects
                // 2. non-surged versions are usually inefficient
                if (source.hasKeyword(Keyword.SURGE) && !sa.isSurged()) {
                    p -= 9;
                }
                // move snap-casted spells to front
                if (source.isInZone(ZoneType.Graveyard) && source.mayPlay(sa.getMayPlay()) != null) {
                    p += 50;
                }
                // if the profile specifies it, deprioritize Storm spells in an attempt to build up storm count
                if (source.hasKeyword(Keyword.STORM) && ai.getController() instanceof PlayerControllerAi) {
                    p -= (((PlayerControllerAi) ai.getController()).getAi().getIntProperty(AiProps.PRIORITY_REDUCTION_FOR_STORM_SPELLS));
                }

                for (Trigger trig : source.getTriggers()) {
                    if (!"Battlefield".equals(trig.getParam("TriggerZones"))) {
                        continue;
                    }
                    final TriggerType mode = trig.getMode();
                    // benefit from Magecraft abilities
                    if ((mode == TriggerType.SpellCast || mode == TriggerType.SpellCastOrCopy) && "You".equals(sa.getParam("ValidActivatingPlayer"))) {
                        p += 1;
                    }
                }

                for (StaticAbility sta : source.getStaticAbilities()) {
                    final Set<StaticAbilityMode> mode = sta.getMode();
                    // reduce cost to enable more plays
                    if (mode.contains(StaticAbilityMode.ReduceCost) && "You".equals(sta.getParam("Activator"))) {
                        p += 1;
                    }
                }
            }

            // use Surge and Prowl costs when able to
            if (sa.isSurged() || sa.isProwl()) {
                p += 9;
            }
            // sort planeswalker abilities with most costly first
            if (sa.isPwAbility()) {
                final CostPart cost = sa.getPayCosts().getCostParts().get(0);
                if (cost instanceof CostRemoveCounter) {
                    p += cost.convertAmount() == null ? 1 : cost.convertAmount();
                } else if (cost instanceof CostPutCounter) {
                    p -= cost.convertAmount();
                }
                if (sa.hasParam("Ultimate")) {
                    p += 9;
                }
            }

            if (ApiType.DestroyAll == sa.getApi()) {
                // check boardwipe earlier
                p += 4;
            } else if (sa.isManaAbility()) {
                // keep mana abilities for paying
                p -= 9;
            }

            // try to use mana ritual before casting spells to maximize potential mana
            if ("ManaRitual".equals(sa.getParam("AILogic"))) {
                p += 9;
            }

            if ((sa.isPlotting() || sa.isForetelling() || sa.isKeyword(Keyword.SUSPEND)) && ai.getTurn() > 10) {
                // less time in late game, prefer something that affects board right away
                p -= 1;
            }

            return p;
        }
    }

    public static List<SpellAbility> sortCreatureSpells(final List<SpellAbility> all) {
        // try to smoothen power creep by making CMC less of a factor
        final List<SpellAbility> creatures = AiController.filterListByApi(Lists.newArrayList(all), ApiType.PermanentCreature);
        if (creatures.size() <= 1) {
            return all;
        }
        // TODO this doesn't account for nearly identical creatures where one is a newer but more cost efficient variant
        creatures.sort(ComputerUtilCard.EvaluateCreatureSpellComparator);
        int idx = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getApi() == ApiType.PermanentCreature) {
                all.set(i, creatures.get(idx));
                idx++;
            }
        }
        return all;
    }
}
