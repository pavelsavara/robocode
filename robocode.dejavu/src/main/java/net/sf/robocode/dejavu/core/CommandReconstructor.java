/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import net.sf.robocode.dejavu.model.DriftReason;
import net.sf.robocode.dejavu.model.TickCommands;
import robocode.Rules;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.BulletState;

import java.util.EnumSet;

/**
 * Reconstructs the hero's realized per-tick commands by inverse physics from
 * {@code prev -> cur}. The three {@code adjust*} coupling flags are assumed
 * {@code false} (the engine default): they do not affect the battle outcome, so
 * déjàvu does not infer them and always applies the additive heading split:
 *
 * <pre>
 *   turnBody  = Δbody
 *   turnGun   = Δgun   − turnBody   (= Δgun − Δbody)
 *   turnRadar = Δradar − turnBody − turnGun   (= Δradar − Δgun)
 * </pre>
 *
 * For the four bundled {@code sample} heroes all three flags really are
 * {@code false}, so this split recovers the exact actuator turns the robot
 * issued. A hero that actually decoupled an actuator folds that coupling into
 * the reported gun/radar command, which is accepted under the always-false
 * assumption. The velocity-dependent turn-rate cap is never used to clamp the
 * realized delta, so a turn-rate-capped tick still reconstructs it exactly.
 * <p>
 * <b>Fire finalization.</b> A {@code setFire(power)} issued on turn {@code T-1}
 * first materializes as a fresh hero-owned bullet in {@code cur} (turn
 * {@code T}) &mdash; identified by a bullet id absent from {@code prev}; its
 * realized power is read back exactly from {@link IBulletSnapshot#getPower()}
 * (the engine has already
 * clamped it to {@code [MIN_BULLET_POWER, MAX_BULLET_POWER]} and charged the
 * gun heat). The legal range is a real-engine invariant, so a power outside it
 * indicates corrupt or non-faithful input (or an engine bug) and raises
 * {@link IllegalStateException} rather than being silently trusted.
 * <p>
 * <b>Skipped turns.</b> Two cues (design &sect;5/&sect;9) mark a tick whose
 * realized inputs cannot be trusted as a single engine advance:
 * <ul>
 *   <li><i>Turn gap</i> &mdash; {@code cur.turn − prev.turn > 1}. The hero
 *       issued nothing for the lost tick(s); the lost commands are
 *       unrecoverable, so we surface {@link DriftReason#SKIPPED_TURN_SUSPECTED}
 *       and replay a no-op tick (zero turns, zero move, no fire).</li>
 *   <li><i>Over-rate</i> &mdash; a single captured tick whose body-heading
 *       delta exceeds the velocity-dependent turn-rate cap, or whose velocity
 *       change exceeds the accel/decel cap (a forced stop to {@code 0}, e.g. a
 *       wall hit, is physically legal and exempt). This only raises the flag;
 *       the conservative caps (plus slack) keep the bundled sample heroes from
 *       being falsely flagged.</li>
 * </ul>
 */
public final class CommandReconstructor {

    /** Slack for per-tick physical caps (turn rate, accel/decel). */
    private static final double CAP_SLACK = 1e-4;

    /** Slack for the legal bullet-power range check. */
    private static final double FIRE_POWER_SLACK = 1e-6;

    private final int heroIndex;

    public CommandReconstructor(int heroIndex) {
        this.heroIndex = heroIndex;
    }

    /**
     * Reconstruct the tick under the always-false coupling assumption.
     *
     * @param prev snapshot of turn T-1
     * @param cur  snapshot of turn T (motion realized between prev and cur)
     */
    public TickCommands reconstruct(ITurnSnapshot prev, ITurnSnapshot cur) {
        IRobotSnapshot p = prev.getRobots()[heroIndex];
        IRobotSnapshot c = cur.getRobots()[heroIndex];
        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);

        // Turn-gap skipped turn: one or more engine advances went unobserved. The
        // lost commands are unrecoverable (the robot issued nothing on the skipped
        // tick), so replay a flagged no-op tick (design §5/§9).
        if (cur.getTurn() - prev.getTurn() > 1) {
            flags.add(DriftReason.SKIPPED_TURN_SUSPECTED);
            return new TickCommands(cur.getTurn(), 0, 0, 0, 0, false, 0, flags);
        }

        // Absolute per-tick heading deltas, each normalized to [-PI, PI).
        double dBody = Geometry.normalRelativeAngle(c.getBodyHeading() - p.getBodyHeading());
        double dGun = Geometry.normalRelativeAngle(c.getGunHeading() - p.getGunHeading());
        double dRadar = Geometry.normalRelativeAngle(c.getRadarHeading() - p.getRadarHeading());

        // Additive heading split under the always-false coupling assumption.
        double turnBody = dBody;
        double turnGun = dGun - turnBody;
        double turnRadar = dRadar - turnBody - turnGun;

        // Realized signed displacement: velocity carries sign relative to body heading.
        double moveDistance = c.getVelocity();

        // Finalize the realized fire command: a fresh hero-owned FIRED bullet in
        // cur carries the exact (engine-clamped) realized power.
        boolean fired = false;
        double firePower = 0;
        IBulletSnapshot fresh = findFreshHeroBullet(prev, cur);
        if (fresh != null) {
            fired = true;
            firePower = fresh.getPower();
            if (firePower < Rules.MIN_BULLET_POWER - FIRE_POWER_SLACK
                    || firePower > Rules.MAX_BULLET_POWER + FIRE_POWER_SLACK) {
                throw new IllegalStateException(
                        "Fire power out of legal range on turn " + cur.getTurn() + ": " + firePower
                        + " not in [" + Rules.MIN_BULLET_POWER + ", " + Rules.MAX_BULLET_POWER + "]");
            }
        }

        // Over-rate skipped turn (secondary, conservative): a single captured tick
        // whose deltas exceed the per-tick physical caps suggests an unobserved
        // engine advance. Caps use the most permissive (slowest) velocity plus
        // slack so the bundled sample heroes are not falsely flagged.
        double slowestVelocity = Math.min(Math.abs(p.getVelocity()), Math.abs(c.getVelocity()));
        boolean bodyOverRate = Math.abs(dBody) > Rules.getTurnRateRadians(slowestVelocity) + CAP_SLACK;
        double dVelocity = c.getVelocity() - p.getVelocity();
        boolean velocityOverRate =
                Math.abs(dVelocity) > Math.max(Rules.ACCELERATION, Rules.DECELERATION) + CAP_SLACK
                        // A forced stop to ~0 (e.g. a wall hit) is physically legal.
                        && Math.abs(c.getVelocity()) > CAP_SLACK;
        if (bodyOverRate || velocityOverRate) {
            flags.add(DriftReason.SKIPPED_TURN_SUSPECTED);
        }

        return new TickCommands(cur.getTurn(), turnBody, turnGun, turnRadar,
                moveDistance, fired, firePower, flags);
    }

    /**
     * A bullet the hero fired on turn T-1 first appears in {@code cur} (turn T)
     * with an id not present in {@code prev}. In captured snapshots a freshly
     * fired bullet is already in flight ({@link BulletState#MOVING}); the
     * transient {@code FIRED} state is not exposed, so the fresh-id test is the
     * reliable cue. Its {@link IBulletSnapshot#getPower()} is the realized power.
     * Detection is shared with the event and energy reconstructors via
     * {@link FireDetector}.
     */
    private IBulletSnapshot findFreshHeroBullet(ITurnSnapshot prev, ITurnSnapshot cur) {
        return FireDetector.bornHeroBullet(prev, cur, heroIndex);
    }
}
