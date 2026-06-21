/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import net.sf.robocode.dejavu.model.EnergyBreakdown;
import net.sf.robocode.dejavu.model.FireDetection;
import net.sf.robocode.dejavu.model.TickEvents;
import net.sf.robocode.security.HiddenAccess;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.DeathEvent;
import robocode.Event;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.RobotStatus;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.RobotState;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.sf.robocode.dejavu.model.DriftReason;

/**
 * Reconstructs the {@link Event}s delivered to the hero on a given turn by
 * diffing consecutive snapshots. The snapshot for turn T captures post-physics
 * state of turn T, so events delivered on turn T are derived from
 * {@code prev = snapshot[T-1]} and {@code cur = snapshot[T]}.
 * <p>
 * One instance per round (per hero). Call {@link #seedRoundStart} once with the
 * spawn snapshot, then {@link #reconstruct} for each subsequent turn.
 */
public final class EventReconstructor {

    private final int heroIndex;
    private final double battlefieldWidth;
    private final double battlefieldHeight;
    private final int numRounds;

    // Inter-tick state (hero perspective). The hero kinematics at the end of the
    // previous turn are the engine's "last*" values at the start of this turn;
    // they drive both the radar sweep and the "did the robot move" scan gate.
    private double prevRadarHeading = Double.NaN;
    private double prevBodyHeading = Double.NaN;
    private double prevGunHeading = Double.NaN;
    private double prevX = Double.NaN;
    private double prevY = Double.NaN;
    private double prevHeroVelocity = Double.NaN;
    private RobotState prevState = RobotState.ACTIVE;
    private RobotState prevOpponentState = RobotState.ACTIVE;
    private double prevOpponentEnergy = Double.NaN;
    private double prevOpponentVelocity = Double.NaN;
    private double prevOpponentX = Double.NaN;
    private double prevOpponentY = Double.NaN;
    // Once the hero is killed the engine stops delivering events to it (a
    // non-running robot rejects events), so all hero-perspective events are
    // suppressed for the remainder of the round.
    private boolean heroDead = false;

    /** Numerical tolerance for the gun-heat / energy fire cross-checks. */
    private static final double EPSILON = 1e-6;

    // Stable bullet identity: (ownerIndex, bulletId) -> last seen state, so a
    // terminal-state event fires exactly once on the rising edge even though the
    // engine keeps the bullet in its terminal state for the explosion animation.
    private final Map<Long, BulletState> bulletLastState = new HashMap<Long, BulletState>();

    // (ownerIndex, bulletId) -> the bullet's position at the end of the previous
    // turn. A bullet that strikes a victim is snapped back to its pre-collision
    // position in the snapshot, but the event embeds the post-movement position
    // (one velocity step further along the heading). Replaying that step from the
    // previous-turn position recovers the event position exactly and is
    // independent of the engine's per-turn bullet shuffle order.
    private final Map<Long, double[]> bulletLastPos = new HashMap<Long, double[]>();

    // The hero fire detected on the most recent reconstructed turn, or null.
    private FireDetection lastFire;

    // The energy decomposition solver and the breakdown of the most recently
    // reconstructed turn.
    private final EnergyLedger energyLedger;
    private EnergyBreakdown lastEnergyBreakdown;

    public EventReconstructor(int heroIndex, double battlefieldWidth, double battlefieldHeight, int numRounds) {
        this.heroIndex = heroIndex;
        this.battlefieldWidth = battlefieldWidth;
        this.battlefieldHeight = battlefieldHeight;
        this.numRounds = numRounds;
        this.energyLedger = new EnergyLedger(heroIndex);
    }

    /** Reset all inter-tick state. Call at the beginning of each round. */
    public void resetRound() {
        prevRadarHeading = Double.NaN;
        prevBodyHeading = Double.NaN;
        prevGunHeading = Double.NaN;
        prevX = Double.NaN;
        prevY = Double.NaN;
        prevHeroVelocity = Double.NaN;
        prevState = RobotState.ACTIVE;
        prevOpponentState = RobotState.ACTIVE;
        prevOpponentEnergy = Double.NaN;
        prevOpponentVelocity = Double.NaN;
        prevOpponentX = Double.NaN;
        prevOpponentY = Double.NaN;
        heroDead = false;
        bulletLastState.clear();
        bulletLastPos.clear();
        lastFire = null;
        lastEnergyBreakdown = null;
        energyLedger.resetRoundInactivity();
    }

    /**
     * Seed inter-tick state from the round-start (spawn) snapshot so the turn-1
     * radar sweep is reconstructed exactly.
     */
    public void seedRoundStart(ITurnSnapshot start) {
        IRobotSnapshot[] robots = start.getRobots();
        IRobotSnapshot me = robots[heroIndex];
        IRobotSnapshot opponent = robots[1 - heroIndex];
        prevRadarHeading = me.getRadarHeading();
        prevBodyHeading = me.getBodyHeading();
        prevGunHeading = me.getGunHeading();
        prevX = me.getX();
        prevY = me.getY();
        prevHeroVelocity = me.getVelocity();
        prevState = me.getState();
        prevOpponentState = opponent.getState();
        prevOpponentEnergy = opponent.getEnergy();
        prevOpponentVelocity = opponent.getVelocity();
        prevOpponentX = opponent.getX();
        prevOpponentY = opponent.getY();
    }

    /**
     * Reconstruct the events delivered on the turn captured by {@code cur}.
     *
     * @param prev snapshot of turn T-1 (baseline)
     * @param cur  snapshot of turn T (post-physics)
     * @return events in engine dispatch order plus provenance flags
     */
    public TickEvents reconstruct(ITurnSnapshot prev, ITurnSnapshot cur) {
        List<Event> events = new ArrayList<Event>();
        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        // Per-event provenance: targeted flags attached to a single reconstructed
        // event (keyed by event identity), threaded to the builders that raise
        // them and handed to the TickEvents for the harness to consult per event.
        Map<Event, EnumSet<DriftReason>> eventFlags = new IdentityHashMap<Event, EnumSet<DriftReason>>();

        // Fire: a new hero-owned FIRED bullet born this turn. Not itself an Event,
        // but its power feeds the bullet-hit events and the energy ledger.
        lastFire = detectFire(prev, cur);

        // Bullet lifecycle: BulletHit / HitByBullet / BulletMissed / BulletHitBullet.
        buildBulletEvents(cur, events, eventFlags);

        // Collisions: HitWallEvent (hero rammed a wall) and HitRobotEvent (hero
        // and opponent boundingboxes touched, possibly with the hero at fault).
        buildWallEvent(prev, cur, events);
        buildRobotCollisionEvents(prev, cur, events, eventFlags);

        // Energy ledger: decompose the hero's energy change into its components
        // and validate/repair the event set, throwing if the decomposition
        // cannot be reconciled with the observed delta (a non-faithful capture).
        lastEnergyBreakdown = energyLedger.account(prev, cur, events, flags);

        // Death / win: RobotDeathEvent (opponent died), WinEvent (hero last
        // standing) and DeathEvent (hero died). Emitted before the scan/status
        // so the heroDead guard below still sees this as a living tick.
        buildDeathWinEvents(cur, events);

        // Scan: the radar sweep from the previous radar heading to the current one.
        ScannedRobotEvent scan = buildScanEvent(cur, eventFlags);
        if (scan != null) {
            events.add(scan);
        }

        // Status: one StatusEvent per living tick, carrying post-physics state.
        StatusEvent status = buildStatusEvent(cur);
        if (status != null) {
            events.add(status);
        }

        // Final per-tick ordering: replicate the engine dispatch order (time
        // ascending, then priority descending). Reconstructed events carry no
        // priority (the engine sets it when enqueuing, which we never do), so we
        // stamp each event with its class default priority before sorting; the
        // natural order then faithfully reproduces the engine ordering, including
        // the ScannedRobotEvent/HitRobotEvent compareTo tie-break overrides.
        // System (critical) events are left untouched: their getPriority()
        // override already returns their fixed value (e.g. WinEvent -> 100,
        // DeathEvent -> -1), so stamping them is both unnecessary and would make
        // the engine clamp/ignore the out-of-range value and warn on the console.
        for (Event event : events) {
            if (!HiddenAccess.isCriticalEvent(event)) {
                HiddenAccess.setEventPriority(event, defaultPriority(event));
            }
        }
        Collections.sort(events);

        advanceState(cur);
        return new TickEvents(cur.getTurn(), events, flags, eventFlags);
    }

    /**
     * Build the {@link StatusEvent} delivered to the hero on the turn captured
     * by {@code cur}, or {@code null} when the hero is dead (the engine does not
     * wake a dead robot, so no status is delivered).
     */
    private StatusEvent buildStatusEvent(ITurnSnapshot cur) {
        IRobotSnapshot[] robots = cur.getRobots();
        IRobotSnapshot me = robots[heroIndex];
        if (me.getState().isDead()) {
            return null;
        }
        IRobotSnapshot opponent = robots[1 - heroIndex];
        int others = opponent.getState().isDead() ? 0 : 1;

        RobotStatus robotStatus = HiddenAccess.createStatus(
                me.getEnergy(), me.getX(), me.getY(),
                me.getBodyHeading(), me.getGunHeading(), me.getRadarHeading(),
                me.getVelocity(),
                0, // bodyTurnRemaining
                0, // radarTurnRemaining
                0, // gunTurnRemaining
                0, // distanceRemaining
                me.getGunHeat(),
                others,
                0, // numSentries (out of scope for 1v1)
                cur.getRound(),
                numRounds,
                cur.getTurn());

        StatusEvent statusEvent = new StatusEvent(robotStatus);
        HiddenAccess.setEventTime(statusEvent, cur.getTurn());
        return statusEvent;
    }

    /**
     * Build the {@link ScannedRobotEvent} the hero received on the turn captured
     * by {@code cur}, or {@code null} when no scan is delivered. Mirrors the
     * engine's {@code RobotPeer.scan}: the PIE arc swept from the previous radar
     * heading to the current one is tested against the opponent's bounding box;
     * a dead hero or dead opponent yields no event.
     * <p>
     * The engine scans every turn, emitting only when the hero moved or called
     * {@code scan()} manually. A manual {@code scan()} leaves no trace in the
     * snapshots, so &mdash; staying snapshot-pure &mdash; we assume every robot
     * scans even with zero movement: a stationary tick collapses to a
     * zero-width sweep, i.e. the radial-line scan a manual {@code scan()} would
     * produce.
     */
    private ScannedRobotEvent buildScanEvent(ITurnSnapshot cur,
            Map<Event, EnumSet<DriftReason>> eventFlags) {
        IRobotSnapshot[] robots = cur.getRobots();
        IRobotSnapshot me = robots[heroIndex];
        IRobotSnapshot opponent = robots[1 - heroIndex];

        if (me.getState().isDead() || opponent.getState().isDead()) {
            return null;
        }
        // Without a seeded previous radar heading the turn-1 sweep is unknown.
        if (Double.isNaN(prevRadarHeading)) {
            return null;
        }

        // Sweep from the previous radar heading to the current one, normalized to
        // (-PI, PI] so a wrap across 0/2*PI is handled like the engine.
        double scanRadians = me.getRadarHeading() - prevRadarHeading;
        if (scanRadians < -Math.PI) {
            scanRadians = 2 * Math.PI + scanRadians;
        } else if (scanRadians > Math.PI) {
            scanRadians = scanRadians - 2 * Math.PI;
        }

        Arc2D arc = Geometry.scanArc(me.getX(), me.getY(), prevRadarHeading, scanRadians);
        Rectangle2D oppBox = Geometry.robotBox(opponent.getX(), opponent.getY());
        if (!Geometry.arcIntersects(arc, oppBox)) {
            return null;
        }

        double dx = opponent.getX() - me.getX();
        double dy = opponent.getY() - me.getY();
        double angle = Math.atan2(dx, dy);
        double dist = Math.hypot(dx, dy);
        double bearing = Geometry.normalRelativeAngle(angle - me.getBodyHeading());

        ScannedRobotEvent event = new ScannedRobotEvent(
                opponent.getName(), opponent.getEnergy(), bearing, dist,
                opponent.getBodyHeading(), opponent.getVelocity(), opponent.isSentryRobot());
        HiddenAccess.setEventTime(event, cur.getTurn());
        // On a degenerate (zero-width) sweep the radar did not move, so whether
        // the engine actually delivered a scan this turn is not snapshot-pure
        // (a manual scan() leaves no trace); flag the presence as uncertain.
        if (Math.abs(scanRadians) <= MOVE_EPSILON) {
            addEventFlag(eventFlags, event, DriftReason.SCAN_UNCERTAIN);
        }
        return event;
    }

    /**
     * The hero fire detected on the most recently reconstructed turn, or
     * {@code null} if the hero did not fire that turn.
     */
    public FireDetection getLastFireDetection() {
        return lastFire;
    }

    /**
     * The energy decomposition of the most recently reconstructed turn, or
     * {@code null} before the first {@link #reconstruct} call.
     */
    public EnergyBreakdown getLastEnergyBreakdown() {
        return lastEnergyBreakdown;
    }

    /**
     * Detect a hero fire on the turn captured by {@code cur}: a hero-owned
     * bullet whose id is absent from {@code prev} (born this turn). The engine
     * advances a freshly fired bullet from {@code FIRED} to {@code MOVING}
     * within its birth turn, so the born bullet is identified by its new id
     * rather than its state. The born bullet's power is the realized,
     * engine-clamped fire power.
     * <p>
     * Returns {@code null} when the hero did not fire. The gun-heat and energy
     * cross-checks (design §5) are real-engine invariants every genuine fire
     * satisfies; a correct reconstruction over a faithful recording can never
     * violate them, so a violation indicates corrupt or non-faithful input (or an
     * engine bug) and raises {@link IllegalStateException} rather than being
     * silently trusted.
     *
     * @param prev snapshot of turn T-1 (gun-heat / energy baseline before firing)
     * @param cur  snapshot of turn T (carries the born bullet)
     * @throws IllegalStateException if the detected fire is inconsistent with the
     *         hero's gun-heat / energy cross-checks
     */
    public FireDetection detectFire(ITurnSnapshot prev, ITurnSnapshot cur) {
        IRobotSnapshot me = cur.getRobots()[heroIndex];
        if (me.getState().isDead()) {
            return null;
        }

        IBulletSnapshot born = FireDetector.bornHeroBullet(prev, cur, me.getRobotIndex());
        if (born == null) {
            return null;
        }

        double power = born.getPower();

        // Gun heat must have risen to ~1+power/5 (then cooled at most one step);
        // and the fire must have been affordable from the pre-fire energy. These
        // are real-engine invariants, so a violation means the input is not a
        // faithful recording of a real battle (or the engine is buggy).
        double expectedHeat = Rules.getGunHeat(power);
        IRobotSnapshot pre = prev.getRobots()[heroIndex];
        if (me.getGunHeat() < expectedHeat - 1.0 - EPSILON
                || pre.getEnergy() < power - EPSILON) {
            throw new IllegalStateException(
                    "Inconsistent fire on turn " + cur.getTurn() + ": power=" + power
                    + " gunHeat=" + me.getGunHeat() + " (expected >= " + (expectedHeat - 1.0)
                    + ") preEnergy=" + pre.getEnergy());
        }

        Bullet bullet = new Bullet(
                born.getHeading(), born.getX(), born.getY(), power,
                me.getName(), null, true, born.getBulletId());
        return new FireDetection(cur.getTurn(), power, bullet, EnumSet.noneOf(DriftReason.class));
    }

    /**
     * Reconstruct the bullet lifecycle events delivered to the hero on the turn
     * captured by {@code cur} and append them to {@code events}.
     * <p>
     * A bullet entering a terminal state ({@code HIT_VICTIM}, {@code HIT_WALL},
     * {@code HIT_BULLET}) carries exactly one engine event on that turn, but the
     * engine keeps the bullet in that state through the explosion animation, so
     * the same terminal state recurs across the next few snapshots. We therefore
     * emit only on the rising edge — the first turn the bullet appears in the
     * terminal state — tracked per bullet identity in {@link #bulletLastState}.
     * <p>
     * From the hero's perspective: a hero-owned bullet reaching {@code HIT_VICTIM}
     * yields {@link BulletHitEvent}; an opponent bullet reaching {@code HIT_VICTIM}
     * with the hero as victim yields {@link HitByBulletEvent}; a hero-owned bullet
     * reaching {@code HIT_WALL} yields {@link BulletMissedEvent}; a hero-owned
     * bullet reaching {@code HIT_BULLET} yields {@link BulletHitBulletEvent}
     * paired with the nearest opposing {@code HIT_BULLET} bullet.
     */
    private void buildBulletEvents(ITurnSnapshot cur, List<Event> events,
            Map<Event, EnumSet<DriftReason>> eventFlags) {
        IRobotSnapshot[] robots = cur.getRobots();
        IRobotSnapshot me = robots[heroIndex];
        IBulletSnapshot[] bullets = cur.getBullets();

        List<Event> bulletEvents = new ArrayList<Event>();
        // Hero bullets that struck the opponent this turn, in build order. When
        // two or more land on the same turn the engine's processing order (and
        // thus the per-event victim energy) is RNG-dependent and not
        // snapshot-recoverable, so every such event is tagged DOUBLE_HIT below.
        List<Event> heroHitsOnOpponent = new ArrayList<Event>();
        // Running victim energy for the opponent, computed from its start-of-turn
        // energy minus the fire cost of any bullet it launches this turn and the
        // damage of each hero bullet that strikes it this turn. The engine
        // deducts fire cost in loadCommands() and bullet damage in updateBullets()
        // (both before the victim moves), then records the post-damage energy in
        // BulletHitEvent.
        double opponentEnergyRemaining = prevOpponentEnergy;
        if (!Double.isNaN(opponentEnergyRemaining)) {
            opponentEnergyRemaining -= opponentFireCost(bullets);
        }
        // Does an opponent bullet strike the hero on this same turn? If so the
        // opponent earns a shooter bonus (Rules.getBulletHitBonus) that is written
        // to its energy in the same RNG-shuffled updateBullets() pass that records
        // the victim energy on the hero's own hit, so that hit's per-event
        // getEnergy() may or may not include the bonus. Detected up-front so every
        // hero hit on the opponent this turn can be flagged below.
        boolean opponentShooterBonusThisTurn = false;
        for (IBulletSnapshot bullet : bullets) {
            BulletState state = bullet.getState();
            BulletState prevState = bulletLastState.get(bulletKey(bullet));
            boolean risingEdge = isTerminal(state) && (prevState == null || !isTerminal(prevState));
            if (risingEdge
                    && state == BulletState.HIT_VICTIM
                    && bullet.getOwnerIndex() == (1 - heroIndex)
                    && bullet.getVictimIndex() == me.getRobotIndex()) {
                opponentShooterBonusThisTurn = true;
                break;
            }
        }
        for (IBulletSnapshot bullet : bullets) {
            BulletState state = bullet.getState();
            BulletState prevState = bulletLastState.get(bulletKey(bullet));
            boolean risingEdge = isTerminal(state) && (prevState == null || !isTerminal(prevState));
            if (risingEdge && !heroDead) {
                double victimEnergy = Double.NaN;
                boolean heroHitOnOpponent = false;
                if (state == BulletState.HIT_VICTIM
                        && bullet.getOwnerIndex() == me.getRobotIndex()
                        && bullet.getVictimIndex() == (1 - heroIndex)
                        && !Double.isNaN(opponentEnergyRemaining)) {
                    opponentEnergyRemaining -= Rules.getBulletDamage(bullet.getPower());
                    if (opponentEnergyRemaining < .01) {
                        opponentEnergyRemaining = 0;
                    }
                    victimEnergy = opponentEnergyRemaining;
                    heroHitOnOpponent = true;
                }
                Event event = buildBulletEvent(bullet, cur, robots, me, victimEnergy);
                if (event != null) {
                    bulletEvents.add(event);
                    if (heroHitOnOpponent && event instanceof BulletHitEvent) {
                        heroHitsOnOpponent.add(event);
                    }
                }
            }
        }

        // Append in build order; the final per-tick sort in reconstruct() imposes
        // the engine dispatch ordering across all event types.
        events.addAll(bulletEvents);

        // Two or more hero bullets on the opponent this turn: the per-event victim
        // energy ordering is RNG-dependent, so flag each colliding BulletHitEvent.
        if (heroHitsOnOpponent.size() >= 2) {
            for (Event hit : heroHitsOnOpponent) {
                addEventFlag(eventFlags, hit, DriftReason.DOUBLE_HIT);
            }
        }

        // An opponent bullet also struck the hero this turn: the opponent's hit
        // bonus may or may not be folded into the victim energy recorded on our
        // hit, depending on the RNG-shuffled processing order, so flag each hero
        // hit on the opponent.
        if (opponentShooterBonusThisTurn) {
            for (Event hit : heroHitsOnOpponent) {
                addEventFlag(eventFlags, hit, DriftReason.SHOOTER_BONUS_UNRESOLVED);
            }
        }

        // After the hero dies its thread stops reading out events on subsequent
        // turns, so no later-turn events are delivered. The killing blow on the
        // death turn itself is still delivered, because the dying hero reads out
        // the turn's queue before processing its DeathEvent. Detecting death at
        // the end of the turn therefore suppresses only subsequent turns.
        if (me.getState().isDead()) {
            heroDead = true;
        }

        // Record this turn's states for the next turn's rising-edge test, and
        // each active bullet's position for the next turn's post-movement replay.
        for (IBulletSnapshot bullet : bullets) {
            long key = bulletKey(bullet);
            bulletLastState.put(key, bullet.getState());
            if (bullet.getState().isActive()) {
                bulletLastPos.put(key, new double[] {bullet.getX(), bullet.getY()});
            } else {
                bulletLastPos.remove(key);
            }
        }
    }

    /** Attach an event-level provenance flag to {@code event}. */
    private static void addEventFlag(Map<Event, EnumSet<DriftReason>> eventFlags,
            Event event, DriftReason flag) {
        EnumSet<DriftReason> set = eventFlags.get(event);
        if (set == null) {
            set = EnumSet.noneOf(DriftReason.class);
            eventFlags.put(event, set);
        }
        set.add(flag);
    }

    /**
     * Sum the fire power of every opponent-owned bullet that first appears this
     * turn. The engine deducts this energy from the opponent in loadCommands()
     * before bullet collisions are resolved, so it must be removed from the
     * opponent's start-of-turn energy when reconstructing BulletHitEvent.
     */
    private double opponentFireCost(IBulletSnapshot[] bullets) {
        double cost = 0;
        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getOwnerIndex() == (1 - heroIndex)
                    && !bulletLastState.containsKey(bulletKey(bullet))) {
                cost += bullet.getPower();
            }
        }
        return cost;
    }

    /**
     * Build the single hero-relevant event for a bullet that has just entered a
     * terminal state, or {@code null} when the transition concerns neither the
     * hero as shooter nor the hero as victim.
     */
    private Event buildBulletEvent(IBulletSnapshot bullet, ITurnSnapshot cur,
            IRobotSnapshot[] robots, IRobotSnapshot me, double victimEnergy) {
        int owner = bullet.getOwnerIndex();
        boolean heroOwned = owner == me.getRobotIndex();

        Event event = null;
        switch (bullet.getState()) {
        case HIT_VICTIM:
            if (heroOwned) {
                int victimIndex = bullet.getVictimIndex();
                IRobotSnapshot victim = robotByIndex(robots, victimIndex);
                if (victim == null) {
                    return null;
                }
                double energy = Double.isNaN(victimEnergy) ? victim.getEnergy() : victimEnergy;
                double[] pos = eventBulletPosition(bullet);
                Bullet b = new Bullet(bullet.getHeading(), pos[0], pos[1],
                        bullet.getPower(), me.getName(), victim.getName(), false, bullet.getBulletId());
                event = new BulletHitEvent(victim.getName(), energy, b);
            } else if (bullet.getVictimIndex() == me.getRobotIndex()) {
                IRobotSnapshot shooter = robotByIndex(robots, owner);
                if (shooter == null) {
                    return null;
                }
                // The engine resolves bullet collisions in updateBullets(), before
                // the victim turns in updateRobots(), so the bearing uses the
                // hero's body heading from the start of the turn (post-physics of
                // the previous turn), not this turn's post-physics heading.
                // Engine bearing: normalRelativeAngle(bulletHeading + PI - victimBodyHeading).
                double victimBodyHeading = Double.isNaN(prevBodyHeading) ? me.getBodyHeading() : prevBodyHeading;
                double bearing = Geometry.normalRelativeAngle(
                        bullet.getHeading() + Math.PI - victimBodyHeading);
                double[] pos = eventBulletPosition(bullet);
                Bullet b = new Bullet(bullet.getHeading(), pos[0], pos[1],
                        bullet.getPower(), shooter.getName(), me.getName(), false, bullet.getBulletId());
                event = new HitByBulletEvent(bearing, b);
            }
            break;
        case HIT_WALL:
            if (heroOwned) {
                Bullet b = new Bullet(bullet.getHeading(), bullet.getX(), bullet.getY(),
                        bullet.getPower(), me.getName(), null, false, bullet.getBulletId());
                event = new BulletMissedEvent(b);
            }
            break;
        case HIT_BULLET:
            if (heroOwned) {
                IBulletSnapshot other = nearestOpposingHitBullet(bullet, cur);
                if (other == null) {
                    return null;
                }
                IRobotSnapshot otherOwner = robotByIndex(robots, other.getOwnerIndex());
                String otherName = otherOwner == null ? null : otherOwner.getName();
                Bullet b = new Bullet(bullet.getHeading(), bullet.getX(), bullet.getY(),
                        bullet.getPower(), me.getName(), null, false, bullet.getBulletId());
                Bullet hitBullet = new Bullet(other.getHeading(), other.getX(), other.getY(),
                        other.getPower(), otherName, null, false, other.getBulletId());
                event = new BulletHitBulletEvent(b, hitBullet);
            }
            break;
        default:
            break;
        }

        if (event != null) {
            HiddenAccess.setEventTime(event, cur.getTurn());
        }
        return event;
    }

    /**
     * The bullet position embedded in a {@code HIT_VICTIM} event. The engine
     * records the event before snapping a victim-hitting bullet back to its
     * pre-collision position, so the embedded position is the post-movement
     * position: one velocity step further along the heading than the bullet's
     * end-of-previous-turn position. Replaying that step ({@code lastPos +
     * bulletSpeed * (sin, cos)(heading)}) reproduces it exactly and is
     * independent of the per-turn bullet shuffle order. Falls back to the current
     * snapshot position for a bullet first seen this turn (no recorded previous
     * position), which the snapshot then carries unsnapped.
     */
    private double[] eventBulletPosition(IBulletSnapshot bullet) {
        double[] last = bulletLastPos.get(bulletKey(bullet));
        if (last == null) {
            return new double[] {bullet.getX(), bullet.getY()};
        }
        double v = Rules.getBulletSpeed(bullet.getPower());
        double heading = bullet.getHeading();
        return new double[] {
                last[0] + v * Math.sin(heading),
                last[1] + v * Math.cos(heading)};
    }

    /**
     * The opposing {@code HIT_BULLET} bullet nearest to {@code bullet}. Both
     * colliding bullets are reset to their pre-move positions on the collision
     * turn, so the nearest opposing terminal bullet is the one it struck.
     * <p>
     * Only bullets entering {@code HIT_BULLET} this turn (rising edge) are
     * considered: a struck bullet lingers in {@code HIT_BULLET} through its
     * explosion animation, so an already-exploded opponent bullet sitting near
     * the hero bullet's snapped-back position would otherwise be mistaken for the
     * partner. The genuine partner just collided this turn and so was active last
     * turn ({@code bulletLastState} not terminal).
     */
    private IBulletSnapshot nearestOpposingHitBullet(IBulletSnapshot bullet, ITurnSnapshot cur) {
        IBulletSnapshot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (IBulletSnapshot other : cur.getBullets()) {
            if (other == bullet
                    || other.getState() != BulletState.HIT_BULLET
                    || other.getOwnerIndex() == bullet.getOwnerIndex()
                    || !isRisingHitBullet(other)) {
                continue;
            }
            double distance = Math.hypot(other.getX() - bullet.getX(), other.getY() - bullet.getY());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    /**
     * Whether {@code bullet} entered {@code HIT_BULLET} this turn rather than
     * lingering in it through the explosion animation: it was either unseen or
     * not yet terminal at the end of the previous turn.
     */
    private boolean isRisingHitBullet(IBulletSnapshot bullet) {
        BulletState prev = bulletLastState.get(bulletKey(bullet));
        return prev == null || !isTerminal(prev);
    }

    /** A bullet identity key stable within a round across owners. */
    private static long bulletKey(IBulletSnapshot bullet) {
        // Pack owner in the high word and the bullet id in the low word, so the key
        // stays collision-free for any int id.
        return (((long) bullet.getOwnerIndex()) << 32) | (bullet.getBulletId() & 0xFFFFFFFFL);
    }

    /** Whether a bullet state carries a one-shot terminal hero event. */
    private static boolean isTerminal(BulletState state) {
        return state == BulletState.HIT_VICTIM
                || state == BulletState.HIT_WALL
                || state == BulletState.HIT_BULLET;
    }

    /**
     * The engine {@code DEFAULT_PRIORITY} for the class of {@code event}, used to
     * stamp reconstructed events before the per-tick dispatch sort. System
     * (critical) events are intentionally absent: they are never passed here
     * because their {@code getPriority()} override already returns their fixed
     * value (e.g. WinEvent -> 100, DeathEvent -> -1).
     */
    private static int defaultPriority(Event event) {
        if (event instanceof StatusEvent) {
            return 99;
        }
        if (event instanceof RobotDeathEvent) {
            return 70;
        }
        if (event instanceof BulletMissedEvent) {
            return 60;
        }
        if (event instanceof BulletHitBulletEvent) {
            return 55;
        }
        if (event instanceof BulletHitEvent) {
            return 50;
        }
        if (event instanceof HitRobotEvent) {
            return 40;
        }
        if (event instanceof HitWallEvent) {
            return 30;
        }
        if (event instanceof HitByBulletEvent) {
            return 20;
        }
        if (event instanceof ScannedRobotEvent) {
            return 10;
        }
        return 0;
    }

    /** The robot snapshot with the given robot index, or {@code null}. */
    private static IRobotSnapshot robotByIndex(IRobotSnapshot[] robots, int index) {
        if (index < 0) {
            return null;
        }
        if (index < robots.length && robots[index].getRobotIndex() == index) {
            return robots[index];
        }
        for (IRobotSnapshot robot : robots) {
            if (robot.getRobotIndex() == index) {
                return robot;
            }
        }
        return null;
    }

    /** Wall-clamp inset: half the robot width/height. */
    private static final double WALL_OFFSET = 18.0;
    /** Minimum per-axis displacement that counts as "moved into" a wall. */
    private static final double MOVE_EPSILON = 1e-6;

    /**
     * Emit a {@link HitWallEvent} when the hero rammed a wall this turn. The
     * engine sets {@code RobotState.HIT_WALL} on exactly the ticks it adds the
     * event, so the snapshot state is a faithful gate.
     * <p>
     * The engine's {@code HitWallEvent} bearing depends only on which wall was
     * struck and the body heading ({@code RobotPeer.checkWallCollision}): the
     * struck wall's outward direction less the body heading. A wall-struck robot
     * is clamped exactly against the boundary it hit, so the struck wall is
     * recovered from the post-physics position alone &mdash; X first, then Y
     * overwrites at a corner, matching the engine's {@code if (x...) ... if (y...)}
     * ordering. No pre-collision velocity estimate is involved, so the bearing is
     * exact, not best-effort, and carries no uncertainty flag.
     */
    private void buildWallEvent(ITurnSnapshot prev, ITurnSnapshot cur, List<Event> events) {
        if (heroDead) {
            return;
        }
        IRobotSnapshot me = cur.getRobots()[heroIndex];
        if (me.getState() != RobotState.HIT_WALL) {
            return;
        }

        double angle = wallBearing(prev, cur);
        if (Double.isNaN(angle)) {
            // The reconstructed pre-clamp position did not cross any boundary.
            // This cannot occur for a genuine HIT_WALL tick (the engine reaches
            // this state only by overrunning a wall), so emit nothing rather
            // than guess.
            return;
        }

        HitWallEvent event = new HitWallEvent(angle);
        HiddenAccess.setEventTime(event, cur.getTurn());
        events.add(event);
    }

    /**
     * The engine wall bearing for a {@code HIT_WALL} tick, recovered from the
     * original snapshot fields on both sides. {@code checkWallCollision} tests the
     * position the robot reached after moving this turn (previous position plus
     * this turn's translational velocity along the post-turn body heading) before
     * clamping it back inside the field, and the bearing is the struck wall's
     * outward direction less the body heading, with X tested first and a Y
     * violation overwriting at a corner.
     * <p>
     * The snapshot velocity is zeroed by the collision, so the pre-clamp position
     * is rebuilt two ways, both snapshot-pure:
     * <ol>
     * <li>Primary: advance the previous turn's velocity one acceleration step
     * ({@link Physics#preCollisionVelocity}) and project it along the body heading.
     * This recovers the case where the robot was already pressed against the wall
     * and kept driving into it (the clamped position does not move, but the robot
     * still overran the boundary).</li>
     * <li>Fallback (when the estimate crosses no boundary because the robot was
     * reversing or braking into the wall, a turn whose realised velocity sign the
     * estimate cannot recover): the impact clamps the robot exactly onto the wall
     * it overran, so the struck wall is the boundary the {@code cur} position sits
     * on. The {@code prev}-was-inside test is preferred so a wall merely driven
     * along is not mistaken for the struck one; only if that is inconclusive is the
     * clamped {@code cur} position used directly (it is always pinned to the wall
     * the engine fired for).</li>
     * </ol>
     * Returns {@code NaN} only when neither method finds a struck boundary.
     */
    private double wallBearing(ITurnSnapshot prev, ITurnSnapshot cur) {
        IRobotSnapshot me = cur.getRobots()[heroIndex];
        IRobotSnapshot before = prev.getRobots()[heroIndex];

        double bodyHeading = me.getBodyHeading();

        // Mirror the engine's integer field boundaries (checkWallCollision).
        int minX = (int) WALL_OFFSET;
        int maxX = (int) battlefieldWidth - (int) WALL_OFFSET;
        int minY = (int) WALL_OFFSET;
        int maxY = (int) battlefieldHeight - (int) WALL_OFFSET;

        double beforeX = before.getX();
        double beforeY = before.getY();

        // Primary: reconstruct the pre-clamp position from the previous velocity
        // advanced one acceleration step, exactly as the robot-collision geometry
        // does. X first, then Y overwrites at a corner (engine branch order).
        double velocity = Physics.preCollisionVelocity(before.getVelocity());
        double preX = beforeX + velocity * Math.sin(bodyHeading);
        double preY = beforeY + velocity * Math.cos(bodyHeading);
        double angle = Double.NaN;
        if (preX < minX) {
            angle = Geometry.normalRelativeAngle(3 * Math.PI / 2 - bodyHeading);
        } else if (preX > maxX) {
            angle = Geometry.normalRelativeAngle(Math.PI / 2 - bodyHeading);
        }
        if (preY < minY) {
            angle = Geometry.normalRelativeAngle(Math.PI - bodyHeading);
        } else if (preY > maxY) {
            angle = Geometry.normalRelativeAngle(-bodyHeading);
        }
        if (!Double.isNaN(angle)) {
            return angle;
        }

        // Fallback: the estimate under-shot (reversing/braking impact). The impact
        // clamps the robot onto the wall it overran. Prefer a boundary reached from
        // strictly inside (so a wall merely driven along is not mistaken for the
        // struck one); fall back to the clamped cur position, which is always
        // pinned to the struck wall. Same X-then-Y branch order.
        double x = me.getX();
        double y = me.getY();
        if (x <= minX && beforeX > minX) {
            angle = Geometry.normalRelativeAngle(3 * Math.PI / 2 - bodyHeading);
        } else if (x >= maxX && beforeX < maxX) {
            angle = Geometry.normalRelativeAngle(Math.PI / 2 - bodyHeading);
        }
        if (y <= minY && beforeY > minY) {
            angle = Geometry.normalRelativeAngle(Math.PI - bodyHeading);
        } else if (y >= maxY && beforeY < maxY) {
            angle = Geometry.normalRelativeAngle(-bodyHeading);
        }
        if (!Double.isNaN(angle)) {
            return angle;
        }

        // Last resort: re-ram of a wall the robot was already pinned against. The
        // clamped cur position alone identifies the struck wall (X first, Y
        // overwrites at a corner).
        if (x <= minX) {
            angle = Geometry.normalRelativeAngle(3 * Math.PI / 2 - bodyHeading);
        } else if (x >= maxX) {
            angle = Geometry.normalRelativeAngle(Math.PI / 2 - bodyHeading);
        }
        if (y <= minY) {
            angle = Geometry.normalRelativeAngle(Math.PI - bodyHeading);
        } else if (y >= maxY) {
            angle = Geometry.normalRelativeAngle(-bodyHeading);
        }
        return angle;
    }

    /**
     * Emit {@link HitRobotEvent}s for a robot-robot collision. The hero receives
     * an at-fault event whenever its own state is {@code HIT_ROBOT} (it rammed
     * the opponent) and a not-at-fault event whenever the opponent's state is
     * {@code HIT_ROBOT} (the opponent rammed the hero); both may fire on the same
     * tick when the two robots ram each other simultaneously.
     */
    private void buildRobotCollisionEvents(ITurnSnapshot prev, ITurnSnapshot cur, List<Event> events,
            Map<Event, EnumSet<DriftReason>> eventFlags) {
        if (heroDead) {
            return;
        }
        IRobotSnapshot me = cur.getRobots()[heroIndex];
        IRobotSnapshot opp = cur.getRobots()[1 - heroIndex];
        boolean heroAtFault = me.getState() == RobotState.HIT_ROBOT;
        boolean oppAtFault = opp.getState() == RobotState.HIT_ROBOT;
        if (!heroAtFault && !oppAtFault) {
            return;
        }

        double bodyHeading = me.getBodyHeading();

        // The engine resolves robot collisions inside updateRobots(), iterating
        // robots in RNG-shuffled order, so the ramming robot reads the other
        // robot's position as it stands at that moment - either start-of-turn or
        // post-move depending on the shuffle. We only have the final post-physics
        // positions, so the bearing is exact only when neither the opponent's
        // mid-turn position nor the ramming robot's own pre-collision position is
        // an estimate. The opponent's mid-turn position is unrecoverable when it
        // moved this turn; the ramming robot's pre-bounce position is rebuilt
        // from its reconstructed (single-acceleration-step) pre-collision
        // velocity, which is itself an estimate whenever that velocity is
        // non-zero (the realised velocity depends on the unrecorded movement
        // command). In either case the bearing is flagged ROBOT_BEARING_UNRESOLVED.
        boolean opponentMoved = !Double.isNaN(prevOpponentX)
                && Math.hypot(opp.getX() - prevOpponentX, opp.getY() - prevOpponentY) > MOVE_EPSILON;

        if (heroAtFault) {
            // The hero bounced back to its pre-move position, so its pre-bounce
            // (overlap) position is prev + the realised pre-collision velocity.
            double velPre = Physics.preCollisionVelocity(prevHeroVelocity);
            double heroPreX = prevX + velPre * Math.sin(bodyHeading);
            double heroPreY = prevY + velPre * Math.cos(bodyHeading);
            double angle = Math.atan2(opp.getX() - heroPreX, opp.getY() - heroPreY);
            double bearing = Geometry.normalRelativeAngle(angle - bodyHeading);
            HitRobotEvent event = new HitRobotEvent(opp.getName(), bearing, opp.getEnergy(), true);
            HiddenAccess.setEventTime(event, cur.getTurn());
            events.add(event);
            // The bearing is a best-effort estimate when the opponent's mid-turn
            // position is unknown (it moved) or the hero's own pre-bounce position
            // was rebuilt from a non-zero, estimated pre-collision velocity.
            if (opponentMoved || Math.abs(velPre) > MOVE_EPSILON) {
                addEventFlag(eventFlags, event, DriftReason.ROBOT_BEARING_UNRESOLVED);
            }
        }

        if (oppAtFault) {
            // The opponent rammed the hero: it bounced back to its pre-move
            // position, so its pre-bounce (overlap) position is the current
            // position plus its realised pre-collision velocity along its
            // heading. The bearing is measured in the opponent's frame and
            // reflected by PI into the hero's frame.
            double oppVelPre = Physics.preCollisionVelocity(prevOpponentVelocity);
            double oppHeading = opp.getBodyHeading();
            double oppPreX = opp.getX() + oppVelPre * Math.sin(oppHeading);
            double oppPreY = opp.getY() + oppVelPre * Math.cos(oppHeading);
            double angle = Math.atan2(me.getX() - oppPreX, me.getY() - oppPreY);
            double bearing = Geometry.normalRelativeAngle(Math.PI + angle - bodyHeading);
            HitRobotEvent event = new HitRobotEvent(opp.getName(), bearing, opp.getEnergy(), false);
            HiddenAccess.setEventTime(event, cur.getTurn());
            events.add(event);
            // The opponent (the ramming robot) bounced from a pre-collision
            // position rebuilt from its estimated pre-collision velocity; when
            // that velocity is non-zero the bearing is a best-effort estimate.
            if (opponentMoved || Math.abs(oppVelPre) > MOVE_EPSILON) {
                addEventFlag(eventFlags, event, DriftReason.ROBOT_BEARING_UNRESOLVED);
            }
        }
    }

    /**
     * Emit the death / win events delivered to the hero on the turn captured by
     * {@code cur}.
     * <p>
     * The engine resolves these within a single turn (see {@code Battle.runTurn}):
     * {@code updateRobots} kills a robot whose energy reaches zero (adding its
     * own {@link DeathEvent}); {@code handleDeadRobots} then publishes a
     * {@link RobotDeathEvent} about each freshly dead robot to every still-living
     * robot; and, once only one team remains, {@code shutdownTurn} (with
     * {@code endTimer == 0}) awards the surviving robot a {@link WinEvent}.
     * <ul>
     * <li>Opponent died this turn and the hero is still alive &rarr;
     *     {@code RobotDeathEvent} and, since the hero is then the last robot
     *     standing in 1v1, {@code WinEvent}. Both are published to the living
     *     hero, which reads them out the same turn, so they map deterministically
     *     to the snapshot tick on which the opponent first appears dead.</li>
     * <li>Hero died this turn while the opponent survives &rarr; {@code DeathEvent},
     *     best-effort: the engine adds the {@code DeathEvent} to the dying robot's
     *     queue, but whether that robot's thread reads it out before being
     *     stopped is scheduler-dependent and not observable from snapshots (e.g.
     *     {@code sample.SittingDuck}/{@code sample.Crazy} receive it, while a
     *     busy {@code sample.Fire} may not). It is emitted on the snapshot tick
     *     on which the hero first appears dead.</li>
     * <li>Hero and opponent died on the same turn &rarr; nothing: the round ends
     *     with no team remaining, so no {@code WinEvent} is awarded, the dead hero
     *     receives no {@code RobotDeathEvent} (deaths are published to living
     *     robots only), and its own {@code DeathEvent} is not read out.</li>
     * </ul>
     * Events are appended in descending engine priority ({@code WinEvent} 100,
     * {@code RobotDeathEvent} 70, {@code DeathEvent} -1).
     */
    private void buildDeathWinEvents(ITurnSnapshot cur, List<Event> events) {
        // Once the hero has died on a previous turn its thread no longer reads
        // out events, so nothing further is delivered. The death turn itself is
        // still live, so test the state the hero entered this turn with (prevState)
        // rather than heroDead, which buildBulletEvents has already raised to true
        // when the hero dies on this very turn.
        if (prevState.isDead()) {
            return;
        }
        IRobotSnapshot[] robots = cur.getRobots();
        IRobotSnapshot me = robots[heroIndex];
        IRobotSnapshot opp = robots[1 - heroIndex];

        boolean heroNowDead = me.getState().isDead();
        boolean oppDiedThisTurn = opp.getState().isDead() && !prevOpponentState.isDead();

        if (heroNowDead && oppDiedThisTurn) {
            // Simultaneous double-death: the round ends with no team remaining,
            // so neither robot is awarded a win nor reads out a death.
            return;
        }

        if (oppDiedThisTurn) {
            // Last robot standing: WinEvent (priority 100) precedes the
            // RobotDeathEvent (priority 70) in engine dispatch order.
            WinEvent win = new WinEvent();
            HiddenAccess.setEventTime(win, cur.getTurn());
            events.add(win);

            RobotDeathEvent death = new RobotDeathEvent(opp.getName());
            HiddenAccess.setEventTime(death, cur.getTurn());
            events.add(death);
        }

        if (heroNowDead) {
            DeathEvent death = new DeathEvent();
            HiddenAccess.setEventTime(death, cur.getTurn());
            events.add(death);
        }
    }

    private void advanceState(ITurnSnapshot cur) {
        IRobotSnapshot[] robots = cur.getRobots();
        IRobotSnapshot me = robots[heroIndex];
        IRobotSnapshot opponent = robots[1 - heroIndex];
        prevRadarHeading = me.getRadarHeading();
        prevBodyHeading = me.getBodyHeading();
        prevGunHeading = me.getGunHeading();
        prevX = me.getX();
        prevY = me.getY();
        prevHeroVelocity = me.getVelocity();
        prevState = me.getState();
        prevOpponentState = opponent.getState();
        prevOpponentEnergy = opponent.getEnergy();
        prevOpponentVelocity = opponent.getVelocity();
        prevOpponentX = opponent.getX();
        prevOpponentY = opponent.getY();
    }
}
