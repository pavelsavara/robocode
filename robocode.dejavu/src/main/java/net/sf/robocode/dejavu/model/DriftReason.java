/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.model;

/**
 * Unified drift taxonomy for reconstruction (formerly split between the
 * production {@code Provenance} flags and the harness {@code DriftReason}
 * outcomes). A {@code DriftReason} is one of three kinds:
 * <ul>
 *   <li>an <em>uncertainty</em> bucket ({@link Kind#UNCERTAINTY}) &mdash; a flag
 *       the reconstructor attaches to a tick or a single event where
 *       reconstruction could not be made exactly certain. These are the only
 *       reasons emitted by the production reconstructor; the validation harness
 *       reads them to triage mismatches. An uncertainty flag is either
 *       <em>tick-level</em> (it concerns the whole turn) or <em>event-level</em>
 *       (attached to a single reconstructed event via
 *       {@link TickEvents#flagsFor(robocode.Event)}); event-level flags are also
 *       folded into the tick-level set so a coarse consumer can still detect
 *       them;</li>
 *   <li>a <em>confirmed</em> outcome ({@link Kind#CONFIRMED}) &mdash; the engine
 *       snapshot oracle proved the labeled cause of a tolerated uncertainty;</li>
 *   <li>an <em>anomaly</em> outcome ({@link Kind#ANOMALY}) &mdash; the oracle
 *       could <em>not</em> confirm the labeled cause, surfaced for triage.</li>
 * </ul>
 * A confirmed/anomaly outcome refines an {@link #origin() originating}
 * uncertainty bucket; {@code ENERGY_*} outcomes carry no origin because the
 * per-tick energy reconciliation has no uncertainty flag of its own.
 */
public enum DriftReason {

    // ===== Uncertainty buckets (reconstructor-emitted tick / event flags) =====

    /**
     * A skipped turn is suspected (turn gap or physics deltas exceed single-tick
     * limits). Tick-level.
     */
    SKIPPED_TURN_SUSPECTED(Kind.UNCERTAINTY, null),

    /**
     * Two or more hero bullets struck the same victim on the same turn while the
     * victim still had recordable energy, so the engine's processing order
     * &mdash; and therefore the per-event victim energy carried on each
     * {@link robocode.BulletHitEvent} &mdash; is RNG-dependent and not
     * snapshot-recoverable. The final victim energy is exact; only the
     * intermediate per-event {@code getEnergy()} labels may be swapped.
     * Event-level: attached to each {@code BulletHitEvent} in the colliding
     * group.
     */
    DOUBLE_HIT(Kind.UNCERTAINTY, null),

    /**
     * A {@link robocode.HitWallEvent} was emitted on a {@code HIT_WALL} tick on
     * which the pre-collision movement direction could not be recovered (the
     * reconstructed impact velocity resolved to ~0, e.g. a robot that was already
     * stationary or moving parallel to the wall). The event is present and its
     * energy is exact, but its {@code getBearing()} is a best-effort estimate.
     * Event-level: attached to the {@code HitWallEvent}.
     */
    WALL_BEARING_UNRESOLVED(Kind.UNCERTAINTY, null),

    /**
     * A {@link robocode.HitRobotEvent} (robot ram) whose bearing could not be
     * recovered exactly. The engine resolves robot collisions inside
     * {@code Battle.updateRobots()}, iterating the robots in RNG-shuffled order
     * ({@code getRobotsAtRandom()}); the ramming robot reads the other robot's
     * position <em>as it stands at that moment</em>, and the ram bearing is
     * measured from the ramming robot's own pre-bounce (overlap) position. Two
     * inputs to that bearing may be estimates: (1) the opponent's mid-turn
     * position, which is not snapshot-recoverable when the opponent moved this
     * turn (only the final post-physics positions survive), and (2) the ramming
     * robot's pre-bounce position, which is rebuilt from its reconstructed
     * single-acceleration-step pre-collision velocity &mdash; itself an estimate
     * whenever that velocity is non-zero, since the realised velocity depends on
     * the unrecorded movement command. The event, its {@code isMyFault()} and its
     * {@code getEnergy()} are exact; only {@code getBearingRadians()} is a
     * best-effort estimate. Event-level: attached to the {@code HitRobotEvent}.
     */
    ROBOT_BEARING_UNRESOLVED(Kind.UNCERTAINTY, null),

    /**
     * A hero {@link robocode.BulletHitEvent} struck the opponent on the same turn
     * that an opponent bullet struck the hero. Because the opponent is both the
     * victim of the hero's bullet (taking {@link robocode.Rules#getBulletDamage}
     * damage) and the shooter of its own bullet (earning
     * {@link robocode.Rules#getBulletHitBonus} for hitting the hero), the
     * opponent's energy is written by two operations this turn. Both bullets are
     * resolved inside {@code Battle.updateBullets()} iterating in RNG-shuffled
     * order ({@code getBulletsAtRandom()}), so whether the opponent's hit bonus
     * has already been added when the hero's bullet records the victim energy is
     * RNG-dependent and not snapshot-recoverable. The opponent's final energy is
     * exact; only the per-event {@code getEnergy()} carried on the hero's
     * {@code BulletHitEvent} may differ by the bonus amount. The event, its
     * {@code getName()} and {@code isMyFault()} are exact. Event-level: attached
     * to the hero's {@code BulletHitEvent}.
     */
    SHOOTER_BONUS_UNRESOLVED(Kind.UNCERTAINTY, null),

    /**
     * A {@link robocode.ScannedRobotEvent} reconstructed on a turn whose radar
     * sweep was (near) zero-width &mdash; the radar heading did not change, so the
     * snapshot-pure scan gate cannot tell whether the engine actually delivered a
     * scan this turn. A robot may also trigger a scan by calling {@code scan()}
     * manually, which leaves no trace in the snapshots; on a degenerate
     * (zero-width) sweep the engine's exact line-intersection test against the
     * opponent's bounding box is not snapshot-recoverable, so the scan gate
     * over-produces here. The scan's fields (bearing, distance, energy, heading,
     * velocity) are exact when the scan did occur; only its <em>presence</em> is
     * uncertain. Event-level: attached to the {@code ScannedRobotEvent}.
     */
    SCAN_UNCERTAIN(Kind.UNCERTAINTY, null),

    // ===== Oracle outcomes (harness-emitted) =====

    /** Inactivity zap drained energy and the reconstructed breakdown matched it. */
    ENERGY_ZAP_CONFIRMED(Kind.CONFIRMED, null),
    /** Wall-hit energy was back-solved by reconstruction and matched the engine. */
    ENERGY_WALL_BACKSOLVE_CONFIRMED(Kind.CONFIRMED, null),
    /** A per-component energy delta could not be reconciled with the engine truth. */
    ENERGY_RESIDUAL_UNEXPLAINED(Kind.ANOMALY, null),

    /** Double-hit per-event victim energy matched the recorded per-bullet value. */
    HIT_ENERGY_ORDER_CONFIRMED(Kind.CONFIRMED, DOUBLE_HIT),
    /** Shooter-bonus per-event victim energy matched the recorded per-bullet value. */
    HIT_ENERGY_BONUS_CONFIRMED(Kind.CONFIRMED, SHOOTER_BONUS_UNRESOLVED),
    /** A reconstructed BulletHitEvent energy disagreed with the recorded oracle value. */
    HIT_ENERGY_MISMATCH(Kind.ANOMALY, null),

    /** The reconstructed wall bearing reproduced the ground-truth bearing. */
    WALL_BEARING_CONFIRMED(Kind.CONFIRMED, WALL_BEARING_UNRESOLVED),
    /** The reconstructed wall bearing disagreed with the ground-truth bearing. */
    WALL_BEARING_MISMATCH(Kind.ANOMALY, WALL_BEARING_UNRESOLVED),

    /** The reconstructed ram bearing matched the engine-recorded collision bearing. */
    RAM_BEARING_CONFIRMED(Kind.CONFIRMED, ROBOT_BEARING_UNRESOLVED),
    /** The reconstructed ram bearing disagreed with the engine-recorded bearing. */
    RAM_BEARING_MISMATCH(Kind.ANOMALY, ROBOT_BEARING_UNRESOLVED),

    /** A surplus reconstructed scan coincided with a turn the engine actually scanned. */
    SCAN_PRESENT_CONFIRMED(Kind.CONFIRMED, SCAN_UNCERTAIN),
    /** A surplus reconstructed scan had no matching engine scan (spurious). */
    SCAN_SPURIOUS(Kind.ANOMALY, SCAN_UNCERTAIN),

    /** A suspected skipped turn matched a real engine-recorded skip. */
    SKIP_CONFIRMED(Kind.CONFIRMED, SKIPPED_TURN_SUSPECTED),
    /** A suspected skipped turn was a physics-delta false positive. */
    SKIP_FALSE_POSITIVE(Kind.ANOMALY, SKIPPED_TURN_SUSPECTED);

    /** The three kinds of drift reason. */
    public enum Kind {
        /** A reconstructor-emitted uncertainty flag (the only kind production emits). */
        UNCERTAINTY,
        /** An oracle outcome that proved the labeled cause of a tolerated uncertainty. */
        CONFIRMED,
        /** An oracle outcome that could not confirm the labeled cause (reported for triage). */
        ANOMALY
    }

    private final Kind kind;
    private final DriftReason origin;

    DriftReason(Kind kind, DriftReason origin) {
        this.kind = kind;
        this.origin = origin;
    }

    /** The kind of this reason: uncertainty bucket, confirmed outcome, or anomaly. */
    public Kind kind() {
        return kind;
    }

    /**
     * The uncertainty bucket this outcome refines, or {@code null} for an
     * uncertainty bucket itself and for {@code ENERGY_*} outcomes (which have no
     * uncertainty flag of their own).
     */
    public DriftReason origin() {
        return origin;
    }

    /**
     * Whether this is a reconstructor-emitted uncertainty flag (as opposed to a
     * harness oracle outcome). Only uncertainty reasons appear on a reconstructed
     * {@link TickEvents}/{@link TickCommands}/{@link FireDetection}.
     */
    public boolean isUncertainty() {
        return kind == Kind.UNCERTAINTY;
    }

    /**
     * Whether this reason is an anomaly (the oracle could not confirm the labeled
     * cause). Anomalies are reported for triage.
     */
    public boolean isAnomaly() {
        return kind == Kind.ANOMALY;
    }
}
