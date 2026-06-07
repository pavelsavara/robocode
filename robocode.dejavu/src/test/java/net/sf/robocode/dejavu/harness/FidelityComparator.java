/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import net.sf.robocode.dejavu.core.Geometry;
import net.sf.robocode.dejavu.model.EnergyBreakdown;
import net.sf.robocode.dejavu.model.DriftReason;
import net.sf.robocode.dejavu.model.TickCommands;
import net.sf.robocode.dejavu.model.TickEvents;
import net.sf.robocode.peer.IExecCommands;
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
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.ICollisionSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnEnergyBreakdown;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the reconstructed per-tick events/commands against the recorded
 * ground-truth streams and feeds the outcome into a {@link FidelityReport}.
 * <p>
 * This is the Phase 3 end-to-end scorer. It mirrors the exact tolerances and
 * exclusions proven green by the per-step integration tests (Steps 8&ndash;12),
 * so the cumulative report reaches {@code overallRate() == 1.0} with an empty
 * mismatch list for the four bundled {@code sample} heroes:
 * <ul>
 *   <li><b>events</b> &mdash; the reconstructed event list (the hero's own
 *       {@code DeathEvent} and the host-side {@code StatusEvent} removed) must
 *       equal the ground-truth delivered events arranged in the engine's
 *       canonical dispatch order (time ascending, then {@code DEFAULT_PRIORITY}
 *       descending). Only turns where both robots are alive post-physics and
 *       neither side is mid-ram are scored; a surplus reconstructed scan on a
 *       zero-movement turn is tolerated.</li>
 *   <li><b>commands</b> &mdash; the realized body/gun/radar turns
 *       (forward-applied under the always-false coupling assumption) must reproduce
 *       the snapshot headings within {@code EPSILON}, the realized move must
 *       equal the snapshot velocity, and the realized fire must match the
 *       independent fresh-bullet oracle. {@code SKIPPED_TURN_SUSPECTED} ticks
 *       are excluded from the denominator.</li>
 * </ul>
 * Non-comparable ticks are skipped entirely (not recorded), exactly as the
 * documented Phase 1/2 limitations prescribe.
 */
public final class FidelityComparator {

    /** Tolerance for treating two scalar values as equal. */
    public static final double EPSILON = 1e-6;

    /**
     * Slack added to a per-tick actuator cap when validating that a ground-truth
     * remaining-counter decrement is a physically plausible realized step. Absorbs
     * floating-point rounding in the engine's rate clamping.
     */
    private static final double CAP_SLACK = 1e-4;

    /**
     * Magnitude above which a post-tick remaining counter is treated as evidence
     * that the actuator saturated this tick (the engine wanted to turn further but
     * was clamped to the per-tick cap). The engine sets the counter to exactly
     * {@code 0} once the requested turn completes, so any non-trivial residual of
     * the same sign as the realized own-turn means the cap was hit.
     */
    private static final double SATURATION_EPSILON = 1e-6;

    private static final int HERO_INDEX = 0;

    /**
     * Robot half-size in pixels (Robocode robots are 36&times;36). A robot that
     * struck a wall is clamped so its centre sits exactly this far inside the
    /**
     * Tolerance for reconciling a reconstructed energy component against the engine
     * breakdown. Mirrors the engine's {@code <0.01 -> 0} disabled-energy clamp so a
     * sub-clamp residual is not reported as unexplained.
     */
    private static final double ENERGY_TOLERANCE = 0.01 + EPSILON;

    private final FidelityReport report;

    public FidelityComparator(FidelityReport report) {
        this.report = report;
    }

    /**
     * Compare the reconstructed events for one tick against the ground-truth
     * events the engine delivered the hero on the same turn.
     * <p>
     * Only turns where both robots survive post-physics and neither side is
     * mid-ram are scored (death turns mix in the non-deterministic killing blow
     * and the scheduler-dependent hero {@code DeathEvent}; a double-ram's
     * not-at-fault {@code HitRobotEvent} is not snapshot-recoverable). Such ticks
     * are skipped without being recorded.
     *
     * @param prevSnap the previous snapshot (round start for the first tick)
     * @param cur      the current (post-physics) snapshot
     */
    public void compareEvents(int round, long turn, TickEvents reconstructed,
            Event[] groundTruth, ITurnSnapshot prevSnap, ITurnSnapshot cur) {
        if (!bothAlive(cur) || bothRammed(cur)) {
            return;
        }

        List<String> rcOrder = comparableTypes(reconstructed.getEvents());
        List<String> gtOrder = groundTruthDispatchOrder(groundTruth, turn);

        // Tolerate surplus reconstructed scans that were flagged SCAN_UNCERTAIN
        // (zero-width radar sweep, where the snapshot-pure scan gate over-produces
        // because a manual scan()/degenerate sweep is not snapshot-recoverable).
        int uncertainScans = countUncertainScans(reconstructed);
        boolean engineScanned = cur.getRobots()[HERO_INDEX].isScanning();
        while (uncertainScans-- > 0
                && count(rcOrder, "ScannedRobotEvent") > count(gtOrder, "ScannedRobotEvent")) {
            rcOrder.remove("ScannedRobotEvent");
            report.recordDrift(DriftReason.SCAN_UNCERTAIN);
            // Oracle: confirm the surplus scan coincided with a turn the engine
            // actually scanned (moved/turned or called scan()), else flag it spurious.
            report.recordDriftReason(
                    engineScanned ? DriftReason.SCAN_PRESENT_CONFIRMED : DriftReason.SCAN_SPURIOUS,
                    round, turn, "isScanning=" + engineScanned);
        }

        if (rcOrder.equals(gtOrder)) {
            report.record("events", true);
        } else {
            report.addMismatch(round, turn, "events", "RC=" + rcOrder + " GT=" + gtOrder);
        }

        compareEventFields(round, turn, reconstructed, groundTruth, cur);
    }

    /**
     * Validate the per-field values of every reconstructed event against its
     * ground-truth counterpart. Events are paired by type and, when more than one
     * of a type occurs on the same turn, by their exact snapshot-derived fields
     * (bullet position, distance, or bearing) so the pairing is order-independent.
     * <p>
     * When a population mismatch exists for a type (different counts on either
     * side) the per-type pairing is skipped here; that discrepancy is already
     * surfaced by the type-ordering check in {@link #compareEvents}.
     * <p>
     * Per-event provenance flags drive very targeted field exclusions:
     * {@link DriftReason#DOUBLE_HIT} excludes only {@link BulletHitEvent#getEnergy()}
     * (the RNG-ordered intermediate victim energy),
     * {@link DriftReason#ROBOT_BEARING_UNRESOLVED} excludes only
     * {@link HitRobotEvent#getBearingRadians()} (the ram bearing, which depends on
     * the opponent's not-snapshot-recoverable mid-turn position) and
     * {@link DriftReason#SHOOTER_BONUS_UNRESOLVED} excludes only
     * {@link BulletHitEvent#getEnergy()} (the victim energy, which may or may not
     * include the opponent's RNG-ordered same-turn shooter bonus).
     * All other fields of those events are still validated exactly.
     */
    private void compareEventFields(int round, long turn, TickEvents reconstructed,
            Event[] groundTruth, ITurnSnapshot cur) {
        Map<String, List<Event>> rcByType = groupComparable(reconstructed.getEvents());
        Map<String, List<Event>> gtByType = groupComparable(Arrays.asList(groundTruth));

        for (Map.Entry<String, List<Event>> entry : rcByType.entrySet()) {
            String type = entry.getKey();
            List<Event> rcList = entry.getValue();
            List<Event> gtList = gtByType.get(type);
            if (gtList == null || gtList.size() != rcList.size()) {
                // Population mismatch already surfaced by the ordering check.
                continue;
            }
            Collections.sort(rcList, FIELD_PAIRING_ORDER);
            Collections.sort(gtList, FIELD_PAIRING_ORDER);
            for (int i = 0; i < rcList.size(); i++) {
                Event rcEvent = rcList.get(i);
                Event gtEvent = gtList.get(i);
                EnumSet<DriftReason> flags = reconstructed.flagsFor(rcEvent);
                report.recordDrift(flags);
                // Oracle: confirm each excluded field's tolerated cause against the
                // engine snapshot, recording the granular reason (design §2). The
                // field exclusion in diffFields is unchanged, so the gate is unaffected.
                confirmExcludedFields(round, turn, rcEvent, gtEvent, flags, cur);
                List<String> diffs = diffFields(rcEvent, gtEvent, flags);
                if (diffs.isEmpty()) {
                    report.record("event-fields", true);
                } else {
                    report.addMismatch(round, turn, "event-fields",
                            type + " " + diffs
                                    + (flags.isEmpty() ? "" : " (flags=" + flags + ")"));
                }
            }
        }
    }

    /**
     * For each field {@link #diffFields} excludes under a provenance flag, read the
     * matching engine snapshot oracle and record whether the labeled cause is
     * confirmed (design §2). Reporting only &mdash; never changes the gate.
     */
    private void confirmExcludedFields(int round, long turn, Event rc, Event gt,
            EnumSet<DriftReason> flags, ITurnSnapshot cur) {
        IRobotSnapshot hero = cur.getRobots()[HERO_INDEX];
        if (rc instanceof BulletHitEvent
                && (flags.contains(DriftReason.DOUBLE_HIT)
                        || flags.contains(DriftReason.SHOOTER_BONUS_UNRESOLVED))) {
            double rcEnergy = ((BulletHitEvent) rc).getEnergy();
            double oracleEnergy = victimEnergyOracle((BulletHitEvent) rc, cur);
            if (Double.isNaN(oracleEnergy)) {
                // No per-bullet oracle (the snapshot did not flag a victim hit);
                // fall back to the ground-truth event's recorded victim energy.
                oracleEnergy = ((BulletHitEvent) gt).getEnergy();
            }
            boolean ok = Math.abs(rcEnergy - oracleEnergy) <= EPSILON;
            DriftReason reason;
            if (!ok) {
                reason = DriftReason.HIT_ENERGY_MISMATCH;
            } else if (flags.contains(DriftReason.DOUBLE_HIT)) {
                reason = DriftReason.HIT_ENERGY_ORDER_CONFIRMED;
            } else {
                reason = DriftReason.HIT_ENERGY_BONUS_CONFIRMED;
            }
            report.recordDriftReason(reason, round, turn,
                    "rcEnergy=" + rcEnergy + " oracle=" + oracleEnergy);
        }
        if (rc instanceof HitRobotEvent && flags.contains(DriftReason.ROBOT_BEARING_UNRESOLVED)) {
            double rcBearing = ((HitRobotEvent) rc).getBearingRadians();
            double oracleBearing = ramBearingOracle((HitRobotEvent) rc, hero);
            if (Double.isNaN(oracleBearing)) {
                return;
            }
            boolean ok = Math.abs(Geometry.normalRelativeAngle(rcBearing - oracleBearing)) <= EPSILON;
            report.recordDriftReason(
                    ok ? DriftReason.RAM_BEARING_CONFIRMED : DriftReason.RAM_BEARING_MISMATCH,
                    round, turn, "rcBearing=" + rcBearing + " oracle=" + oracleBearing);
        }
    }

    /**
     * The per-bullet victim energy the engine recorded for the bullet behind this
     * reconstructed {@link BulletHitEvent}, matched by hero ownership and bullet
     * position; {@code NaN} when no such victim-hit bullet snapshot exists.
     */
    private static double victimEnergyOracle(BulletHitEvent rc, ITurnSnapshot cur) {
        double rcX = rc.getBullet().getX();
        double best = Double.NaN;
        double bestDx = Double.POSITIVE_INFINITY;
        for (IBulletSnapshot b : cur.getBullets()) {
            if (b == null || b.getOwnerIndex() != HERO_INDEX
                    || Double.isNaN(b.getVictimEnergyAtHit())) {
                continue;
            }
            double dx = Math.abs(b.getX() - rcX);
            if (dx < bestDx) {
                bestDx = dx;
                best = b.getVictimEnergyAtHit();
            }
        }
        return best;
    }

    /**
     * The engine-recorded ram bearing for this reconstructed {@link HitRobotEvent},
     * matched by at-fault flag; {@code NaN} when no matching collision was recorded.
     */
    private static double ramBearingOracle(HitRobotEvent rc, IRobotSnapshot hero) {
        ICollisionSnapshot[] collisions = hero.getCollisions();
        if (collisions == null || collisions.length == 0) {
            return Double.NaN;
        }
        for (ICollisionSnapshot c : collisions) {
            if (c.isAtFault() == rc.isMyFault()) {
                return c.getBearingRadians();
            }
        }
        // No at-fault match (e.g. a single recorded collision): fall back to the first.
        return collisions[0].getBearingRadians();
    }

    /** Group comparable events (excludes {@link DeathEvent}/{@link StatusEvent}) by simple type name. */
    private static Map<String, List<Event>> groupComparable(List<Event> events) {
        Map<String, List<Event>> byType = new LinkedHashMap<String, List<Event>>();
        for (Event e : events) {
            if (e instanceof DeathEvent || e instanceof StatusEvent) {
                continue;
            }
            String type = e.getClass().getSimpleName();
            List<Event> list = byType.get(type);
            if (list == null) {
                list = new ArrayList<Event>();
                byType.put(type, list);
            }
            list.add(e);
        }
        return byType;
    }

    /**
     * Order-independent pairing key for events of the same type: bullet x for
     * bullet-bearing events, distance for scans, bearing for robot collisions.
     * These fields are exact in both reconstruction and ground truth, so sorting
     * both sides by this key aligns same-type events deterministically.
     */
    private static final Comparator<Event> FIELD_PAIRING_ORDER = new Comparator<Event>() {
        @Override
        public int compare(Event a, Event b) {
            return Double.compare(pairingKey(a), pairingKey(b));
        }
    };

    private static double pairingKey(Event e) {
        if (e instanceof BulletHitEvent) {
            return ((BulletHitEvent) e).getBullet().getX();
        }
        if (e instanceof BulletMissedEvent) {
            return ((BulletMissedEvent) e).getBullet().getX();
        }
        if (e instanceof HitByBulletEvent) {
            return ((HitByBulletEvent) e).getBullet().getX();
        }
        if (e instanceof BulletHitBulletEvent) {
            return ((BulletHitBulletEvent) e).getBullet().getX();
        }
        if (e instanceof ScannedRobotEvent) {
            return ((ScannedRobotEvent) e).getDistance();
        }
        if (e instanceof HitRobotEvent) {
            return ((HitRobotEvent) e).getBearingRadians();
        }
        return 0;
    }

    /**
     * Compare the fields of a reconstructed event against its ground-truth pair,
     * honouring per-event provenance exclusions. Returns the list of field
     * mismatches (empty when the events match within tolerance).
     */
    private static List<String> diffFields(Event rc, Event gt, EnumSet<DriftReason> flags) {
        List<String> d = new ArrayList<String>();
        if (rc instanceof ScannedRobotEvent) {
            ScannedRobotEvent a = (ScannedRobotEvent) rc;
            ScannedRobotEvent b = (ScannedRobotEvent) gt;
            checkStr(d, "name", a.getName(), b.getName());
            checkNum(d, "energy", a.getEnergy(), b.getEnergy());
            checkAngle(d, "bearing", a.getBearingRadians(), b.getBearingRadians());
            checkNum(d, "distance", a.getDistance(), b.getDistance());
            checkAngle(d, "heading", a.getHeadingRadians(), b.getHeadingRadians());
            checkNum(d, "velocity", a.getVelocity(), b.getVelocity());
        } else if (rc instanceof HitByBulletEvent) {
            HitByBulletEvent a = (HitByBulletEvent) rc;
            HitByBulletEvent b = (HitByBulletEvent) gt;
            checkAngle(d, "bearing", a.getBearingRadians(), b.getBearingRadians());
            checkNum(d, "velocity", a.getVelocity(), b.getVelocity());
            checkStr(d, "name", a.getName(), b.getName());
            diffBullet(d, "", a.getBullet(), b.getBullet());
        } else if (rc instanceof BulletHitEvent) {
            BulletHitEvent a = (BulletHitEvent) rc;
            BulletHitEvent b = (BulletHitEvent) gt;
            checkStr(d, "name", a.getName(), b.getName());
            if (!flags.contains(DriftReason.DOUBLE_HIT)
                    && !flags.contains(DriftReason.SHOOTER_BONUS_UNRESOLVED)) {
                checkNum(d, "energy", a.getEnergy(), b.getEnergy());
            }
            diffBullet(d, "", a.getBullet(), b.getBullet());
        } else if (rc instanceof BulletMissedEvent) {
            diffBullet(d, "", ((BulletMissedEvent) rc).getBullet(),
                    ((BulletMissedEvent) gt).getBullet());
        } else if (rc instanceof BulletHitBulletEvent) {
            BulletHitBulletEvent a = (BulletHitBulletEvent) rc;
            BulletHitBulletEvent b = (BulletHitBulletEvent) gt;
            diffBullet(d, "bullet.", a.getBullet(), b.getBullet());
            diffBullet(d, "hitBullet.", a.getHitBullet(), b.getHitBullet());
        } else if (rc instanceof HitRobotEvent) {
            HitRobotEvent a = (HitRobotEvent) rc;
            HitRobotEvent b = (HitRobotEvent) gt;
            checkStr(d, "name", a.getName(), b.getName());
            if (!flags.contains(DriftReason.ROBOT_BEARING_UNRESOLVED)) {
                checkAngle(d, "bearing", a.getBearingRadians(), b.getBearingRadians());
            }
            checkNum(d, "energy", a.getEnergy(), b.getEnergy());
            if (a.isMyFault() != b.isMyFault()) {
                d.add("myFault " + a.isMyFault() + "!=" + b.isMyFault());
            }
        } else if (rc instanceof HitWallEvent) {
            checkAngle(d, "bearing", ((HitWallEvent) rc).getBearingRadians(),
                    ((HitWallEvent) gt).getBearingRadians());
        } else if (rc instanceof RobotDeathEvent) {
            checkStr(d, "name", ((RobotDeathEvent) rc).getName(),
                    ((RobotDeathEvent) gt).getName());
        }
        // WinEvent carries no comparable fields.
        return d;
    }

    private static void diffBullet(List<String> d, String prefix, Bullet a, Bullet b) {
        checkNum(d, prefix + "power", a.getPower(), b.getPower());
        checkAngle(d, prefix + "heading", a.getHeadingRadians(), b.getHeadingRadians());
        checkNum(d, prefix + "x", a.getX(), b.getX());
        checkNum(d, prefix + "y", a.getY(), b.getY());
    }

    private static void checkNum(List<String> d, String field, double a, double b) {
        if (Math.abs(a - b) > EPSILON) {
            d.add(field + " " + a + "!=" + b);
        }
    }

    private static void checkAngle(List<String> d, String field, double a, double b) {
        if (Math.abs(Geometry.normalRelativeAngle(a - b)) > EPSILON) {
            d.add(field + " " + a + "!=" + b);
        }
    }

    private static void checkStr(List<String> d, String field, String a, String b) {
        if (a == null ? b != null : !a.equals(b)) {
            d.add(field + " " + a + "!=" + b);
        }
    }

    /**
     * Compare the reconstructed commands for one tick against the engine
     * ground-truth realized commands ({@link IExecCommands}) the engine recorded
     * for the hero on the same turn.
     * <p>
     * The realized absolute headings and velocity carried in the snapshot are the
     * authoritative engine output, and the reconstructed turns reproduce them by
     * construction, so each actuator's realized rotation/move is scored against the
     * snapshot forward-apply &mdash; the exact ground truth. The three {@code adjust*}
     * coupling flags are assumed {@code false} throughout déjàvu, so the realized
     * gun/radar <em>split</em> is defined by the snapshot under the additive
     * convention. déjàvu treats every actuator as if its turn were issued fresh on
     * each tick (the {@code *Remaining()} counters are not consulted), so the only
     * remaining ground-truth constraint on a split is the engine's per-tick rate cap:
     * each reconstructed own-turn is clamped to its physical limit
     * ({@link Rules#getTurnRateRadians} for the body, {@link Rules#GUN_TURN_RATE_RADIANS}
     * for the gun, {@link Rules#RADAR_TURN_RATE_RADIANS} for the radar). The capped
     * value is a real-engine invariant, so a reconstructed own-turn beyond it indicates
     * corrupt or non-faithful input and raises {@link IllegalStateException} rather than
     * being scored (matching {@code CommandReconstructor}'s fire-power range check).
     * <p>
     * Fire is scored against the snapshot's fresh-bullet oracle (the authoritative
     * realized fire). A captured {@code getFirePowers()} is only what the robot
     * <em>asked</em>; the comparator counts it as a fire only when it is a
     * <em>valid</em> command the engine would realize ({@code gunHeat <= 0 &&
     * energy > 0} on the previous snapshot's hero, the gate
     * {@code RobotPeer.fireBullets} tested). An invalid request is treated as
     * no-fire; a valid request that spawned no bullet is a genuine mismatch.
     * {@code SKIPPED_TURN_SUSPECTED} ticks carry unrecoverable lost commands and are
     * excluded from the denominator without being recorded.
     *
     * @param prevSnap the previous snapshot (round start for the first tick)
     * @param cur      the current (post-physics) snapshot
     * @param curCmd   ground-truth realized commands for the current turn ({@code null} when absent)
     */
    public void compareCommands(int round, long turn, TickCommands reconstructed,
            ITurnSnapshot prevSnap, ITurnSnapshot cur,
            IExecCommands curCmd) {
        if (reconstructed.getFlags().contains(DriftReason.SKIPPED_TURN_SUSPECTED)) {
            report.recordDrift(DriftReason.SKIPPED_TURN_SUSPECTED);
            // Oracle: confirm the suspected skip was a real engine-recorded skip
            // versus a physics-delta false positive.
            boolean realSkip = cur.getRobots()[HERO_INDEX].wasTurnSkipped();
            report.recordDriftReason(
                    realSkip ? DriftReason.SKIP_CONFIRMED : DriftReason.SKIP_FALSE_POSITIVE,
                    round, turn, "wasTurnSkipped=" + realSkip);
            return;
        }

        IRobotSnapshot p = prevSnap.getRobots()[HERO_INDEX];
        IRobotSnapshot c = cur.getRobots()[HERO_INDEX];

        // Forward-apply the reconstructed turns under the always-false (additive)
        // coupling: the realized split must reproduce the snapshot absolute headings,
        // and the realized move the velocity.
        double predBody = p.getBodyHeading() + reconstructed.getTurnBody();
        double predGun = p.getGunHeading()
                + reconstructed.getTurnBody() + reconstructed.getTurnGun();
        double predRadar = p.getRadarHeading()
                + reconstructed.getTurnBody() + reconstructed.getTurnGun() + reconstructed.getTurnRadar();
        double bodyErrSnap = Math.abs(Geometry.normalRelativeAngle(predBody - c.getBodyHeading()));
        double gunErrSnap = Math.abs(Geometry.normalRelativeAngle(predGun - c.getGunHeading()));
        double radarErrSnap = Math.abs(Geometry.normalRelativeAngle(predRadar - c.getRadarHeading()));
        double moveErrSnap = Math.abs(reconstructed.getMoveDistance() - c.getVelocity());

        // The captured coupling flags never change the absolute headings (gun/radar
        // always physically follow the body, radar always follows the gun); déjàvu
        // assumes them false and does not consult the remaining counters. The only
        // ground-truth constraint left on the always-false split is the engine's
        // per-tick rate cap: each reconstructed own-turn is the actuator's own realized
        // rotation and must lie within its physical limit. The body cap is velocity-
        // dependent, so it is taken at the most permissive (slowest) velocity over the
        // tick, plus slack, exactly as CommandReconstructor's over-rate cue. A turn
        // beyond the cap is physically impossible for a faithful engine and signals
        // corrupt input, so it raises IllegalStateException rather than being scored.
        double slowestVelocity = Math.min(Math.abs(p.getVelocity()), Math.abs(c.getVelocity()));
        checkWithinCap(turn, "body", reconstructed.getTurnBody(),
                Rules.getTurnRateRadians(slowestVelocity) + CAP_SLACK);
        checkWithinCap(turn, "gun", reconstructed.getTurnGun(),
                Rules.GUN_TURN_RATE_RADIANS + CAP_SLACK);
        checkWithinCap(turn, "radar", reconstructed.getTurnRadar(),
                Rules.RADAR_TURN_RATE_RADIANS + CAP_SLACK);

        // Saturation floor (lower bound). The engine zeroes a remaining counter the
        // moment its requested turn completes, so a non-zero post-tick remaining of
        // the same sign means the actuator was clamped to its cap and the realized
        // own-turn must equal that cap. This pins the always-false split from below,
        // catching a mis-attribution the snapshot forward-apply cannot see (e.g. the
        // body absorbing rotation the gun motor actually produced). The floor uses the
        // *least* permissive cap over the tick so a faithful engine can never realize
        // less: the body cap is taken at the fastest velocity (lowest rate) and capped
        // by the robot's own maxTurnRate; a value below it is corrupt input and raises
        // IllegalStateException. The gun/radar own-turns only equal the true motor
        // rotation under the default coupling the split assumes, so their floors are
        // applied only when the relevant adjust* couplings are inactive.
        if (curCmd != null) {
            double fastestVelocity = Math.max(Math.abs(p.getVelocity()), Math.abs(c.getVelocity()));
            double bodyCapLower = Math.min(curCmd.getMaxTurnRate(),
                    Rules.getTurnRateRadians(fastestVelocity));
            checkSaturatedAtCap(turn, "body", reconstructed.getTurnBody(),
                    curCmd.getBodyTurnRemaining(), bodyCapLower);
            if (!curCmd.isAdjustGunForBodyTurn()) {
                checkSaturatedAtCap(turn, "gun", reconstructed.getTurnGun(),
                        curCmd.getGunTurnRemaining(), Rules.GUN_TURN_RATE_RADIANS);
            }
            if (!curCmd.isAdjustGunForBodyTurn()
                    && !curCmd.isAdjustRadarForGunTurn()
                    && !curCmd.isAdjustRadarForBodyTurn()) {
                checkSaturatedAtCap(turn, "radar", reconstructed.getTurnRadar(),
                        curCmd.getRadarTurnRemaining(), Rules.RADAR_TURN_RATE_RADIANS);
            }
        }

        // The realized absolute headings and velocity carried in the snapshot are the
        // authoritative engine output; the reconstructed turns reproduce them by
        // construction, so each actuator's realized rotation/move is scored against the
        // snapshot forward-apply — the exact ground truth.
        boolean bodyOk = bodyErrSnap <= EPSILON;
        boolean gunOk = gunErrSnap <= EPSILON;
        boolean radarOk = radarErrSnap <= EPSILON;
        boolean moveOk = moveErrSnap <= EPSILON;

        // Fire ground truth: a fresh hero-owned bullet in the snapshot is the
        // authoritative realized fire and carries the exact engine-clamped power. The
        // captured getFirePowers() is only what the robot *asked*; the comparator is
        // interested only in a *valid* fire, one the engine would realize. The engine
        // declines a fire when, at the start of the turn, the gun is still hot or the
        // robot has no energy (RobotPeer.fireBullets returns on gunHeat > 0 ||
        // energy == 0). It also skips a fire command it has *already consumed*: the
        // command list is shared across turns, so a robot that stops executing keeps
        // re-presenting the same getFirePowers() every turn while the engine fires it
        // only once (RobotPeer.fireBullets `if (bulletCmd.isConsumed()) continue`).
        // Consumption is not captured directly, but the engine leaves an
        // authoritative signal: a realized fire raises the gun heat by
        // 1 + power/5, whereas a turn that fires nothing only cools it. So an actual
        // engine fire is detected by the gun heat rising from prev to cur; a stale
        // (consumed) request leaves the gun cooling and is correctly no-fire. The
        // gate folds the engine's start-of-turn condition (read from the previous
        // snapshot's hero) together with this realized-fire signal: an invalid or
        // already-consumed request is treated as no-fire, and a valid request that
        // truly fired yet spawned no captured bullet is a genuine mismatch.
        boolean fireOk;
        String fireDetail;
        IBulletSnapshot oracle = freshHeroBullet(prevSnap, cur);
        double[] firePowers = curCmd != null ? curCmd.getFirePowers() : null;
        boolean gunHeatRose = c.getGunHeat() > p.getGunHeat() + EPSILON;
        boolean gtFired = firePowers != null && firePowers.length > 0
                && p.getGunHeat() <= 0 && p.getEnergy() > 0 && gunHeatRose;
        if (oracle != null) {
            fireOk = reconstructed.isFired()
                    && Math.abs(reconstructed.getFirePower() - oracle.getPower()) <= EPSILON
                    && (!gtFired || firePowerMatches(oracle.getPower(), firePowers));
            fireDetail = "fired=" + reconstructed.isFired()
                    + " power=" + reconstructed.getFirePower() + " oracle=" + oracle.getPower()
                    + (gtFired ? " gt=" + Arrays.toString(firePowers) : "");
        } else if (gtFired) {
            // A valid fire (gun cool, robot energized) that produced no bullet is a
            // genuine mismatch between the realized command and the reconstruction.
            fireOk = reconstructed.isFired()
                    && firePowerMatches(reconstructed.getFirePower(), firePowers);
            fireDetail = "expected realized fire, fired=" + reconstructed.isFired()
                    + " power=" + reconstructed.getFirePower()
                    + " gt=" + Arrays.toString(firePowers);
        } else {
            // No power requested, or the engine gate would have blocked the request:
            // not a fire at all, so the reconstructor's no-fire is the correct outcome.
            fireOk = !reconstructed.isFired();
            fireDetail = "expected no fire, fired=" + reconstructed.isFired();
        }

        boolean matched = bodyOk && gunOk && radarOk && moveOk && fireOk;
        if (matched) {
            report.record("commands", true);
        } else {
            report.addMismatch(round, turn, "commands",
                    "bodyOk=" + bodyOk + " gunOk=" + gunOk + " radarOk=" + radarOk
                            + " moveOk=" + moveOk + " fire{" + fireDetail + "}");
        }
    }

    /**
     * Assert a reconstructed own-turn lies within the engine's per-tick rate cap.
     * The cap is a real-engine invariant (the actuator cannot rotate faster), so a
     * value beyond it indicates corrupt or non-faithful input rather than a scorable
     * mismatch, mirroring {@code CommandReconstructor}'s fire-power range check.
     *
     * @param turn      the current turn (for the diagnostic)
     * @param actuator  the actuator name (for the diagnostic)
     * @param ownTurn   the reconstructed per-tick own-turn (radians)
     * @param cap       the per-tick rate cap including slack (radians)
     */
    private static void checkWithinCap(long turn, String actuator, double ownTurn, double cap) {
        if (Math.abs(ownTurn) > cap) {
            throw new IllegalStateException(
                    "Reconstructed " + actuator + " turn out of physical range on turn " + turn
                            + ": " + ownTurn + " exceeds per-tick cap " + cap);
        }
    }

    /**
     * Assert that a saturated actuator's reconstructed own-turn sits at its per-tick
     * cap. A post-tick remaining counter of the same sign as the realized own-turn
     * means the engine wanted to keep turning but was clamped, so the realized step
     * must equal the cap. {@code capLower} is the least permissive cap over the tick,
     * so a faithful engine can never realize less; a smaller magnitude indicates a
     * corrupt split (rotation mis-attributed to another actuator) and raises
     * {@code IllegalStateException}, mirroring {@link #checkWithinCap}.
     *
     * @param turn      the current turn (for the diagnostic)
     * @param actuator  the actuator name (for the diagnostic)
     * @param ownTurn   the reconstructed per-tick own-turn (radians)
     * @param remaining the captured post-tick remaining counter (radians)
     * @param capLower  the least permissive per-tick cap over the tick (radians)
     */
    private static void checkSaturatedAtCap(long turn, String actuator, double ownTurn,
            double remaining, double capLower) {
        boolean saturated = Math.abs(remaining) > SATURATION_EPSILON
                && Math.signum(remaining) == Math.signum(ownTurn);
        if (saturated && Math.abs(ownTurn) < capLower - CAP_SLACK) {
            throw new IllegalStateException(
                    "Reconstructed " + actuator + " turn below saturation floor on turn " + turn
                            + ": " + ownTurn + " under per-tick cap " + capLower
                            + " despite unfinished remaining " + remaining);
        }
    }

    /** Whether the reconstructed fire power matches any of the ground-truth fired powers. */
    private static boolean firePowerMatches(double power, double[] firePowers) {
        for (double gt : firePowers) {
            if (Math.abs(power - gt) <= EPSILON) {
                return true;
            }
        }
        return false;
    }

    public FidelityReport getReport() {
        return report;
    }

    /**
     * Reconcile the reconstructed per-tick energy {@link EnergyBreakdown} against
     * the engine's authoritative {@link ITurnEnergyBreakdown} oracle, component by
     * component (design §2/§3.1). Confirms the inactivity zap and the wall-hit
     * back-solve that {@code EnergyLedger} currently <em>infers</em>; any component
     * the oracle cannot reconcile is recorded as {@code ENERGY_RESIDUAL_UNEXPLAINED}
     * for triage. Reporting only &mdash; never changes the gate.
     *
     * @param reconstructed the reconstructed breakdown for the hero this tick
     * @param cur           the current (post-physics) snapshot
     */
    public void compareEnergy(int round, long turn, EnergyBreakdown reconstructed,
            ITurnSnapshot cur) {
        if (reconstructed == null || !bothAlive(cur)) {
            return;
        }
        ITurnEnergyBreakdown oracle = cur.getRobots()[HERO_INDEX].getEnergyChanges();
        if (oracle == null) {
            return;
        }

        boolean zapReconciled = matches(reconstructed.getInactivityDrain(), oracle.getZapEnergy());
        boolean wallReconciled = matches(reconstructed.getWallDamage(), oracle.getHitWallEnergy());
        boolean allReconciled = zapReconciled && wallReconciled
                && matches(reconstructed.getFireCost(), oracle.getFireCostEnergy())
                && matches(reconstructed.getBulletDamage(), oracle.getHitByBulletEnergy())
                && matches(reconstructed.getBulletHitBonus(), oracle.getHitOpponentEnergy())
                && matches(reconstructed.getRamDamage(), oracle.getHitRobotEnergy())
                && Math.abs(reconstructed.getResidual()) <= ENERGY_TOLERANCE;

        // Confirm the inferred components when the engine actually exercised them.
        if (oracle.getZapEnergy() != 0 && zapReconciled) {
            report.recordDriftReason(DriftReason.ENERGY_ZAP_CONFIRMED, round, turn,
                    "zap=" + oracle.getZapEnergy());
        }
        if (oracle.getHitWallEnergy() != 0 && wallReconciled) {
            report.recordDriftReason(DriftReason.ENERGY_WALL_BACKSOLVE_CONFIRMED, round, turn,
                    "wall=" + oracle.getHitWallEnergy());
        }
        if (!allReconciled) {
            report.recordDriftReason(DriftReason.ENERGY_RESIDUAL_UNEXPLAINED, round, turn,
                    "rc{fire=" + reconstructed.getFireCost()
                            + " hitBy=" + reconstructed.getBulletDamage()
                            + " bonus=" + reconstructed.getBulletHitBonus()
                            + " ram=" + reconstructed.getRamDamage()
                            + " wall=" + reconstructed.getWallDamage()
                            + " zap=" + reconstructed.getInactivityDrain()
                            + " residual=" + reconstructed.getResidual()
                            + "} oracle{fire=" + oracle.getFireCostEnergy()
                            + " hitBy=" + oracle.getHitByBulletEnergy()
                            + " bonus=" + oracle.getHitOpponentEnergy()
                            + " ram=" + oracle.getHitRobotEnergy()
                            + " wall=" + oracle.getHitWallEnergy()
                            + " zap=" + oracle.getZapEnergy() + "}");
        }
    }

    private static boolean matches(double a, double b) {
        return Math.abs(a - b) <= ENERGY_TOLERANCE;
    }

    // ---- Comparability predicates ---------------------------------------

    private static boolean bothAlive(ITurnSnapshot cur) {
        for (IRobotSnapshot r : cur.getRobots()) {
            if (r.getState().isDead()) {
                return false;
            }
        }
        return true;
    }

    private static boolean bothRammed(ITurnSnapshot cur) {
        IRobotSnapshot[] robots = cur.getRobots();
        return robots[0].getState() == RobotState.HIT_ROBOT
                && robots[1].getState() == RobotState.HIT_ROBOT;
    }

    /** Count reconstructed {@link ScannedRobotEvent}s flagged {@link DriftReason#SCAN_UNCERTAIN}. */
    private static int countUncertainScans(TickEvents reconstructed) {
        int n = 0;
        for (Event e : reconstructed.getEvents()) {
            if (e instanceof ScannedRobotEvent
                    && reconstructed.flagsFor(e).contains(DriftReason.SCAN_UNCERTAIN)) {
                n++;
            }
        }
        return n;
    }

    // ---- Event canonicalization -----------------------------------------

    /** Reconstructed event type names in emitted order, excluding the non-comparable
     *  hero {@code DeathEvent} and the host-side {@code StatusEvent}. */
    private static List<String> comparableTypes(List<Event> events) {
        List<String> out = new ArrayList<String>();
        for (Event e : events) {
            if (e instanceof DeathEvent || e instanceof StatusEvent) {
                continue;
            }
            out.add(e.getClass().getSimpleName());
        }
        return out;
    }

    /** Ground-truth event type names arranged in the engine's canonical dispatch
     *  order (time ascending, then priority descending). */
    private static List<String> groundTruthDispatchOrder(Event[] groundTruth, long turn) {
        List<Event> gt = new ArrayList<Event>();
        for (Event e : groundTruth) {
            if (e instanceof DeathEvent || e instanceof StatusEvent) {
                continue;
            }
            HiddenAccess.setEventTime(e, turn);
            HiddenAccess.setEventPriority(e, defaultPriority(e));
            gt.add(e);
        }
        Collections.sort(gt);
        List<String> out = new ArrayList<String>();
        for (Event e : gt) {
            out.add(e.getClass().getSimpleName());
        }
        return out;
    }

    private static int defaultPriority(Event e) {
        if (e instanceof StatusEvent) {
            return 99;
        }
        if (e instanceof WinEvent) {
            return 100;
        }
        if (e instanceof RobotDeathEvent) {
            return 70;
        }
        if (e instanceof BulletMissedEvent) {
            return 60;
        }
        if (e instanceof BulletHitBulletEvent) {
            return 55;
        }
        if (e instanceof BulletHitEvent) {
            return 50;
        }
        if (e instanceof HitRobotEvent) {
            return 40;
        }
        if (e instanceof HitWallEvent) {
            return 30;
        }
        if (e instanceof HitByBulletEvent) {
            return 20;
        }
        if (e instanceof ScannedRobotEvent) {
            return 10;
        }
        return 0;
    }

    private static int count(List<String> list, String value) {
        int n = 0;
        for (String s : list) {
            if (s.equals(value)) {
                n++;
            }
        }
        return n;
    }

    // ---- Fresh-bullet oracle --------------------------------------------

    /**
     * Independent oracle for the realized fire: a freshly fired hero bullet present
     * in {@code cur}. Captured snapshots expose a freshly fired bullet as already in
     * flight (the transient {@code FIRED} state is never captured), so a fresh bullet
     * is detected by its id being new.
     * <p>
     * The naive cue is &ldquo;an id absent from {@code prev}&rdquo;, but an unpatched
     * engine that re-fires a persisted command reuses a still-in-flight id (the
     * duplicate-bullet-id bug), defeating the id-absence test. Counting hero bullets
     * per id catches both cases: a fresh fire raises the count for its id
     * (0&rarr;1 for a brand-new id, or 1&rarr;2 when a live id is reused by a
     * re-fire). The freshly born instance &mdash; the one whose running count first
     * exceeds its {@code prev} count &mdash; is returned.
     */
    private static IBulletSnapshot freshHeroBullet(ITurnSnapshot prev, ITurnSnapshot cur) {
        IBulletSnapshot[] curBullets = cur.getBullets();
        if (curBullets == null) {
            return null;
        }
        Map<Integer, Integer> prevCounts = heroBulletCounts(prev);
        Map<Integer, Integer> seen = new HashMap<Integer, Integer>();
        for (IBulletSnapshot b : curBullets) {
            if (b.getOwnerIndex() != HERO_INDEX) {
                continue;
            }
            int id = b.getBulletId();
            int running = seen.merge(id, 1, Integer::sum);
            if (running > prevCounts.getOrDefault(id, 0)) {
                return b;
            }
        }
        return null;
    }

    private static Map<Integer, Integer> heroBulletCounts(ITurnSnapshot snapshot) {
        Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
        IBulletSnapshot[] bullets = snapshot.getBullets();
        if (bullets != null) {
            for (IBulletSnapshot b : bullets) {
                if (b.getOwnerIndex() == HERO_INDEX) {
                    counts.merge(b.getBulletId(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }
}
