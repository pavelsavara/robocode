/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import net.sf.robocode.dejavu.model.EnergyBreakdown;
import net.sf.robocode.dejavu.model.DriftReason;
import robocode.BulletHitEvent;
import robocode.Event;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick energy decomposition solver (design &sect;6). The hero's energy
 * change on a turn is the sum of a small number of independently observable
 * components:
 * <ul>
 *   <li><b>fire cost</b> {@code -firePower} &mdash; a new hero bullet appeared;</li>
 *   <li><b>bullet hit bonus</b> {@code +3*power} &mdash; per hero bullet striking
 *       the opponent this turn ({@link BulletHitEvent});</li>
 *   <li><b>bullet damage taken</b> {@code -getBulletDamage(power)} &mdash; per
 *       opponent bullet striking the hero this turn ({@link HitByBulletEvent});</li>
 *   <li><b>wall damage</b> {@code -max(|v|/2-1, 0)} on a {@code HIT_WALL} tick,
 *       using the reconstructed pre-collision velocity ({@link HitWallEvent});</li>
 *   <li><b>ram damage</b> {@code -0.6} per robot-robot contact this turn
 *       ({@link HitRobotEvent}).</li>
 * </ul>
 *
 * <p>Because every component is individually derivable from the bullet
 * snapshots and the hero state, {@link #account} is primarily a
 * <em>consistency check</em>: it decomposes the reconstructed event set, sums
 * the components, and compares the total against the observed
 * {@code cur.energy - prev.energy}. When the event set is complete the residual
 * is zero. When it is not (a dropped or mis-attributed event, or an ambiguous
 * pre-collision velocity), it runs a bounded search over the snapshot-derivable
 * degrees of freedom to repair the decomposition. A faithful, non-death living
 * tick is always reconcilable &mdash; with unique bullet ids every energy source
 * except the back-solved wall velocity is exactly snapshot-derivable, and the
 * engine's disabled-energy clamp is mirrored by {@link #residual} &mdash; so if
 * no exact combination is found the recording is not a faithful capture of a
 * real battle and {@link #account} throws {@link IllegalStateException}.
 *
 * <p>One instance per hero, constructed with the hero's robot index.
 */
public final class EnergyLedger {

    /** Tolerance for treating an energy residual as zero. */
    public static final double EPSILON = 1e-6;
    /** The engine clamps any post-update energy below this to exactly zero. */
    private static final double DISABLED_THRESHOLD = 0.01;
    /** Largest wall-hit damage possible, used to bound the wall re-solve. */
    private static final double MAX_WALL_DAMAGE = Rules.getWallHitDamage(Rules.MAX_VELOCITY);
    /** The per-turn inactivity energy zap once a robot has been idle too long. */
    private static final double INACTIVITY_ZAP = 0.1;
    /**
     * Idle turns the battle tolerates before draining energy. The engine default
     * (Battle rules / BattleSpecification) is 450; the dejavu capture battle uses
     * it unchanged. Mirrored here as a constant alongside {@link #INACTIVITY_ZAP}
     * (the engine's zap quantum) so the battle-global idle counter can be
     * reconstructed without threading the rule through every constructor.
     */
    private static final int INACTIVITY_TIME = 450;

    private final int heroIndex;

    // Battle-global inactivity state (design: solve the zap from data on BOTH
    // sides rather than guess it from the hero alone). The engine drains a fixed
    // quantum from every living robot once inactiveTurnCount passes
    // INACTIVITY_TIME; that counter is reset, in 10-energy chunks, by every
    // robot's qualifying energy losses. Both fields persist across the whole
    // battle; only the turn count resets each round (mirroring
    // Battle.initializeRound, which zeroes inactiveTurnCount but carries the
    // accumulated inactivityEnergy remainder).
    private int inactiveTurnCount;
    private double inactivityEnergy;

    public EnergyLedger(int heroIndex) {
        this.heroIndex = heroIndex;
    }

    /**
     * Reset the per-round inactivity turn counter at the start of each round,
     * mirroring {@code Battle.initializeRound} (which zeroes the count but leaves
     * the accumulated {@code inactivityEnergy} remainder to carry over).
     */
    public void resetRoundInactivity() {
        inactiveTurnCount = 0;
    }

    /**
     * Decompose the hero's energy change for the turn captured by {@code cur},
     * validating and (where possible) repairing the reconstructed event set so
     * the component sum equals the observed change.
     *
     * @param prev   snapshot of turn T-1 (energy baseline)
     * @param cur    snapshot of turn T (post-physics)
     * @param events the reconstructed events for turn T (bullet + collision
     *               events must already be present)
     * @param flags  provenance flags for the tick (currently unused by the
     *               decomposition, which throws rather than flags on failure)
     * @return the energy breakdown, with {@link EnergyBreakdown#getResidual()}
     *         being zero
     * @throws IllegalStateException if the energy change cannot be reconciled,
     *         which for a faithful living tick cannot happen
     */
    public EnergyBreakdown account(ITurnSnapshot prev, ITurnSnapshot cur,
            List<Event> events, EnumSet<DriftReason> flags) {
        EnergyBreakdown breakdown = new EnergyBreakdown();

        // Advance the battle-global idle counter exactly once per turn, before
        // the dead-hero short-circuit, so the counter keeps mirroring the engine
        // even on turns this hero is no longer accounted. Its decision drives the
        // deterministic zap prediction below.
        boolean zapThisTurn = advanceInactivity(prev, cur);

        IRobotSnapshot me = cur.getRobots()[heroIndex];
        IRobotSnapshot prevMe = prev.getRobots()[heroIndex];

        // A dead hero is not accounted: the engine stops delivering events to it
        // and its energy is frozen at zero, so there is nothing to reconcile.
        if (me.getState().isDead()) {
            return breakdown;
        }

        double prevEnergy = prevMe.getEnergy();
        double observedEnergy = me.getEnergy();
        double preColVel = Physics.preCollisionVelocity(prevMe.getVelocity());

        // Certain components, decomposed from the reconstructed event set (plus
        // the snapshot-derived fire cost, which carries no event of its own).
        breakdown.setFireCost(-fireCost(prev, cur));
        breakdown.setBulletHitBonus(eventBulletHitBonus(events));
        breakdown.setBulletDamage(-eventBulletDamage(events));
        breakdown.setWallDamage(hasHitWall(events) ? -Rules.getWallHitDamage(preColVel) : 0);
        breakdown.setRamDamage(-Rules.ROBOT_HIT_DAMAGE * countHitRobot(events));

        double residual = residual(prevEnergy, breakdown.sum(), observedEnergy);
        if (Math.abs(residual) <= EPSILON) {
            breakdown.setResidual(0);
            return breakdown;
        }

        // Inactivity zap (Proposal C): once the battle-global idle counter passes
        // the threshold the engine drains a fixed quantum from every living
        // robot. We reconstruct that counter deterministically from the energy
        // losses of *every* robot (advanceInactivity, above) and treat its
        // verdict as authoritative — the both-sides "solve once" path.
        if (zapThisTurn) {
            // The counter says the engine drained a quantum this turn. Commit it,
            // then let the normal reconcile/repair flow resolve any remaining
            // component with the drain already in place.
            breakdown.setInactivityDrain(inactivityDrain(prevEnergy));
            if (Math.abs(residual(prevEnergy, breakdown.sum(), observedEnergy)) <= EPSILON) {
                breakdown.setResidual(0);
                return breakdown;
            }
        } else {
            // The counter predicted no zap. Safety net (mirroring the kept
            // snapshot re-scan repair): recover a zap the counter might have
            // missed from its unmistakable one-quantum signature, committing it
            // only when it actually reconciles the observed energy.
            double drain = inactivityDrain(prevEnergy);
            if (drain != 0
                    && Math.abs(residual(prevEnergy, breakdown.sum() + drain, observedEnergy)) <= EPSILON) {
                breakdown.setInactivityDrain(drain);
                breakdown.setResidual(0);
                return breakdown;
            }
        }

        // The decomposition does not yet explain the observed change: run a
        // bounded search over the snapshot-derivable ambiguities to repair it.
        if (repair(prev, cur, events, breakdown, prevEnergy, observedEnergy, preColVel)) {
            breakdown.setResidual(0);
            return breakdown;
        }

        // Every faithful, non-death living tick is fully reconcilable: with
        // unique bullet ids every energy source except the back-solved wall
        // velocity is exactly snapshot-derivable, and the engine's disabled-energy
        // clamp (< 0.01 -> 0) is already mirrored by residual(). A surviving
        // residual therefore means the recording is not a faithful capture of a
        // real battle, which we reject rather than silently trust.
        double finalResidual = residual(prevEnergy, breakdown.sum(), observedEnergy);
        throw new IllegalStateException(
                "Unexplained energy residual on turn " + cur.getTurn() + ": " + finalResidual
                + " (prev=" + prevEnergy + " observed=" + observedEnergy
                + " components=" + breakdown.sum() + ")");
    }

    /**
     * Observed-minus-predicted energy, honouring the engine's clamp of any
     * post-update energy below {@link #DISABLED_THRESHOLD} to exactly zero.
     */
    private static double residual(double prevEnergy, double componentSum, double observedEnergy) {
        double predicted = prevEnergy + componentSum;
        if (predicted < DISABLED_THRESHOLD) {
            predicted = 0;
        }
        return observedEnergy - predicted;
    }

    /**
     * Bounded repair search. The reconstructed event set may be missing one or
     * more energy-bearing events that the snapshot still witnesses (a bullet
     * frozen in its terminal state, an unrecorded ram, a wall hit), or the wall
     * damage may have been computed from an over-estimated pre-collision
     * velocity. We enumerate the snapshot-derivable candidates that are not yet
     * accounted for and search for the combination that drives the residual to
     * zero, applying it to {@code breakdown} on success.
     */
    private boolean repair(ITurnSnapshot prev, ITurnSnapshot cur, List<Event> events,
            EnergyBreakdown breakdown, double prevEnergy, double observedEnergy, double preColVel) {
        IRobotSnapshot me = cur.getRobots()[heroIndex];

        List<Candidate> candidates = new ArrayList<Candidate>();

        double missingDamage = snapshotBulletDamageTaken(prev, cur) - eventBulletDamage(events);
        if (missingDamage > EPSILON) {
            candidates.add(new Candidate(Component.BULLET_DAMAGE, -missingDamage));
        }

        double missingBonus = snapshotBulletHitBonus(prev, cur) - eventBulletHitBonus(events);
        if (missingBonus > EPSILON) {
            candidates.add(new Candidate(Component.BULLET_HIT_BONUS, missingBonus));
        }

        double missingRam = Rules.ROBOT_HIT_DAMAGE * (snapshotHitRobotCount(cur) - countHitRobot(events));
        if (missingRam > EPSILON) {
            candidates.add(new Candidate(Component.RAM_DAMAGE, -missingRam));
        }

        if (me.getState() == RobotState.HIT_WALL && !hasHitWall(events)) {
            candidates.add(new Candidate(Component.WALL_DAMAGE, -Rules.getWallHitDamage(preColVel)));
        }

        // Search every subset of the missing candidates for one that reconciles
        // the energy change exactly.
        int n = candidates.size();
        for (int mask = 0; mask < (1 << n); mask++) {
            double add = 0;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    add += candidates.get(i).delta;
                }
            }
            if (Math.abs(residual(prevEnergy, breakdown.sum() + add, observedEnergy)) <= EPSILON) {
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) != 0) {
                        candidates.get(i).applyTo(breakdown);
                    }
                }
                return true;
            }
        }

        // Wall pre-collision velocity is the one quantity the snapshot cannot
        // retain (the post-collision velocity is zeroed). Every other component
        // is exactly witnessed by the snapshot &mdash; bullet ids are unique
        // within a round, so each hit, bonus and ram is unambiguously
        // attributable &mdash; so when a wall hit is present we rebuild the
        // non-wall components from the snapshot-exact values and solve the wall
        // damage directly from the residual, accepting it only as a physically
        // plausible wall-hit quantum.
        if (hasHitWall(events) || me.getState() == RobotState.HIT_WALL) {
            breakdown.setFireCost(-fireCost(prev, cur));
            breakdown.setBulletHitBonus(snapshotBulletHitBonus(prev, cur));
            breakdown.setBulletDamage(-snapshotBulletDamageTaken(prev, cur));
            breakdown.setRamDamage(-Rules.ROBOT_HIT_DAMAGE * snapshotHitRobotCount(cur));
            double nonWallSum = breakdown.sum() - breakdown.getWallDamage();
            double solvedWall = observedEnergy - prevEnergy - nonWallSum;
            if (solvedWall <= EPSILON && -solvedWall <= MAX_WALL_DAMAGE + EPSILON) {
                breakdown.setWallDamage(solvedWall);
                if (Math.abs(residual(prevEnergy, breakdown.sum(), observedEnergy)) <= EPSILON) {
                    return true;
                }
            }
        }

        return false;
    }

    // ---- Event-derived components ---------------------------------------

    private static double eventBulletHitBonus(List<Event> events) {
        double bonus = 0;
        for (Event event : events) {
            if (event instanceof BulletHitEvent) {
                bonus += Rules.getBulletHitBonus(((BulletHitEvent) event).getBullet().getPower());
            }
        }
        return bonus;
    }

    private static double eventBulletDamage(List<Event> events) {
        double damage = 0;
        for (Event event : events) {
            if (event instanceof HitByBulletEvent) {
                damage += Rules.getBulletDamage(((HitByBulletEvent) event).getPower());
            }
        }
        return damage;
    }

    private static int countHitRobot(List<Event> events) {
        int count = 0;
        for (Event event : events) {
            if (event instanceof HitRobotEvent) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasHitWall(List<Event> events) {
        for (Event event : events) {
            if (event instanceof HitWallEvent) {
                return true;
            }
        }
        return false;
    }

    // ---- Snapshot-derived components (repair search) --------------------

    /**
     * Total bullet damage the hero took this turn, read straight from the
     * snapshots: opponent bullets that crossed into {@code HIT_VICTIM} with the
     * hero as victim on the rising edge (the engine keeps a bullet in its
     * terminal state through the explosion, so only the first turn counts).
     */
    private double snapshotBulletDamageTaken(ITurnSnapshot prev, ITurnSnapshot cur) {
        IRobotSnapshot me = cur.getRobots()[heroIndex];
        Map<Long, BulletState> prevStates = bulletStates(prev);
        double damage = 0;
        for (IBulletSnapshot bullet : cur.getBullets()) {
            if (bullet.getState() == BulletState.HIT_VICTIM
                    && bullet.getOwnerIndex() != me.getRobotIndex()
                    && bullet.getVictimIndex() == me.getRobotIndex()
                    && isRisingEdge(prevStates, bullet)) {
                damage += Rules.getBulletDamage(bullet.getPower());
            }
        }
        return damage;
    }

    /**
     * Total bullet-hit bonus the hero earned this turn: hero bullets that
     * crossed into {@code HIT_VICTIM} on the rising edge.
     */
    private double snapshotBulletHitBonus(ITurnSnapshot prev, ITurnSnapshot cur) {
        IRobotSnapshot me = cur.getRobots()[heroIndex];
        Map<Long, BulletState> prevStates = bulletStates(prev);
        double bonus = 0;
        for (IBulletSnapshot bullet : cur.getBullets()) {
            if (bullet.getState() == BulletState.HIT_VICTIM
                    && bullet.getOwnerIndex() == me.getRobotIndex()
                    && isRisingEdge(prevStates, bullet)) {
                bonus += Rules.getBulletHitBonus(bullet.getPower());
            }
        }
        return bonus;
    }

    /** Number of robot-robot contacts the hero was party to this turn. */
    private int snapshotHitRobotCount(ITurnSnapshot cur) {
        int count = 0;
        for (IRobotSnapshot robot : cur.getRobots()) {
            if (robot.getState() == RobotState.HIT_ROBOT) {
                count++;
            }
        }
        return count;
    }

    /**
     * Hero fire cost this turn: the power of the hero-owned bullet born this turn
     * (its id absent from {@code prev}), or zero when the hero did not fire. The
     * engine deducts the fire power in {@code loadCommands()} as the bullet is
     * created and fires at most one bullet per turn. Detection is shared with the
     * event and command reconstructors via {@link FireDetector}.
     */
    private double fireCost(ITurnSnapshot prev, ITurnSnapshot cur) {
        IRobotSnapshot me = cur.getRobots()[heroIndex];
        IBulletSnapshot born = FireDetector.bornHeroBullet(prev, cur, me.getRobotIndex());
        return born == null ? 0 : born.getPower();
    }

    // ---- Inactivity zap ------------------------------------------------

    /**
     * The hero's energy loss from a single inactivity zap, mirroring the
     * engine's {@code zap}: subtract {@link #INACTIVITY_ZAP}, but clamp the
     * result below that quantum to zero.
     *
     * <p>The engine drains every living robot this fixed quantum each turn once
     * the battle-global idle counter passes its threshold. {@link #account}
     * predicts that zap deterministically via {@link #advanceInactivity}, which
     * reconstructs the counter from the energy losses of every robot in the
     * battle; this method supplies the magnitude of the resulting hero drain.
     */
    private static double inactivityDrain(double energyAtZap) {
        if (energyAtZap <= 0) {
            return 0;
        }
        double after = energyAtZap - INACTIVITY_ZAP;
        if (after < INACTIVITY_ZAP) {
            after = 0;
        }
        return after - energyAtZap;
    }

    /**
     * Advance the battle-global idle counter across one turn and report whether
     * the engine drained an inactivity zap on it. The counter is reconstructed
     * exactly from the snapshots of <em>both</em> robots, mirroring the order in
     * {@code Battle.runTurn}/{@code Battle.updateRobots}:
     *
     * <ol>
     *   <li>pre-decision resets &mdash; fire costs ({@code loadCommands}) and
     *       bullet damage taken ({@code updateBullets}) reset the counter in
     *       10-energy chunks;</li>
     *   <li>the zap decision is taken: {@code zap = count > INACTIVITY_TIME};</li>
     *   <li>post-decision resets &mdash; ram damage (applied during the move)
     *       and full-energy kills reset the counter;</li>
     *   <li>the counter is incremented once.</li>
     * </ol>
     *
     * Energy <em>gains</em> (e.g. bullet-hit bonuses) and wall damage do not
     * reset the counter, matching the engine's {@code setEnergy(.., false)} and
     * {@code resetInactiveTurnCount}'s positive-loss guard, so they are excluded.
     */
    private boolean advanceInactivity(ITurnSnapshot prev, ITurnSnapshot cur) {
        addInactivityEnergy(preDecisionLoss(prev, cur));
        boolean zap = inactiveTurnCount > INACTIVITY_TIME;
        addInactivityEnergy(postDecisionLoss(prev, cur));
        inactiveTurnCount++;
        return zap;
    }

    /**
     * Accumulate a robot's energy loss into the idle counter, resetting it (the
     * engine zeroes {@code inactiveTurnCount}) for every whole 10 energy lost.
     * Mirrors {@code Battle.resetInactiveTurnCount}, whose positive-loss guard
     * means only losses count.
     */
    private void addInactivityEnergy(double loss) {
        if (loss <= 0) {
            return;
        }
        inactivityEnergy += loss;
        while (inactivityEnergy >= 10) {
            inactivityEnergy -= 10;
            inactiveTurnCount = 0;
        }
    }

    /**
     * Counter-resetting energy losses applied before the engine's zap decision,
     * summed over the whole battle: each robot's fire cost (a bullet present in
     * {@code cur} but not {@code prev} cost its owner that power) and each
     * bullet's damage to its victim (a bullet reaching {@code HIT_VICTIM} on a
     * rising edge).
     */
    private static double preDecisionLoss(ITurnSnapshot prev, ITurnSnapshot cur) {
        Map<Long, BulletState> prevStates = bulletStates(prev);
        double loss = 0;
        for (IBulletSnapshot bullet : cur.getBullets()) {
            if (prevStates.get(bulletKey(bullet)) == null) {
                loss += bullet.getPower(); // fire cost to the bullet's owner
            }
            if (bullet.getState() == BulletState.HIT_VICTIM && isRisingEdge(prevStates, bullet)) {
                loss += Rules.getBulletDamage(bullet.getPower()); // damage to the victim
            }
        }
        return loss;
    }

    /**
     * Counter-resetting energy losses applied after the engine's zap decision,
     * summed over the whole battle: each robot's ram damage (state
     * {@code HIT_ROBOT}) and the full 10-energy reset for each robot that died
     * this turn (alive in {@code prev}, dead in {@code cur}).
     */
    private static double postDecisionLoss(ITurnSnapshot prev, ITurnSnapshot cur) {
        IRobotSnapshot[] prevRobots = prev.getRobots();
        IRobotSnapshot[] curRobots = cur.getRobots();
        double loss = 0;
        for (int i = 0; i < curRobots.length; i++) {
            if (curRobots[i].getState() == RobotState.HIT_ROBOT) {
                loss += Rules.ROBOT_HIT_DAMAGE;
            }
            boolean wasAlive = i < prevRobots.length && !prevRobots[i].getState().isDead();
            if (wasAlive && curRobots[i].getState().isDead()) {
                loss += 10.0; // kill() -> resetInactiveTurnCount(10.0)
            }
        }
        return loss;
    }

    private static boolean isRisingEdge(Map<Long, BulletState> prevStates, IBulletSnapshot bullet) {
        BulletState prevState = prevStates.get(bulletKey(bullet));
        return prevState == null || !isTerminal(prevState);
    }

    private static Map<Long, BulletState> bulletStates(ITurnSnapshot snapshot) {
        Map<Long, BulletState> states = new HashMap<Long, BulletState>();
        for (IBulletSnapshot bullet : snapshot.getBullets()) {
            states.put(bulletKey(bullet), bullet.getState());
        }
        return states;
    }

    private static long bulletKey(IBulletSnapshot bullet) {
        // Pack owner in the high word and the bullet id in the low word, so the key
        // stays collision-free for any int id.
        return (((long) bullet.getOwnerIndex()) << 32) | (bullet.getBulletId() & 0xFFFFFFFFL);
    }

    private static boolean isTerminal(BulletState state) {
        return state == BulletState.HIT_VICTIM
                || state == BulletState.HIT_WALL
                || state == BulletState.HIT_BULLET;
    }

    /** The breakdown field a repair candidate contributes to. */
    private enum Component {
        FIRE_COST, BULLET_HIT_BONUS, BULLET_DAMAGE, WALL_DAMAGE, RAM_DAMAGE
    }

    /** A snapshot-derived energy quantum the event set may have dropped. */
    private static final class Candidate {
        private final Component component;
        private final double delta;

        Candidate(Component component, double delta) {
            this.component = component;
            this.delta = delta;
        }

        void applyTo(EnergyBreakdown breakdown) {
            switch (component) {
            case FIRE_COST:
                breakdown.setFireCost(breakdown.getFireCost() + delta);
                break;
            case BULLET_HIT_BONUS:
                breakdown.setBulletHitBonus(breakdown.getBulletHitBonus() + delta);
                break;
            case BULLET_DAMAGE:
                breakdown.setBulletDamage(breakdown.getBulletDamage() + delta);
                break;
            case WALL_DAMAGE:
                breakdown.setWallDamage(breakdown.getWallDamage() + delta);
                break;
            case RAM_DAMAGE:
                breakdown.setRamDamage(breakdown.getRamDamage() + delta);
                break;
            default:
                break;
            }
        }
    }
}
