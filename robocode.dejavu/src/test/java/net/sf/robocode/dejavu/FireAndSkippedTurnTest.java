/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu;

import net.sf.robocode.dejavu.core.CommandReconstructor;
import net.sf.robocode.dejavu.core.Geometry;
import net.sf.robocode.dejavu.harness.BattleCaptureHarness;
import net.sf.robocode.dejavu.harness.CapturedTurn;
import net.sf.robocode.dejavu.harness.StubBulletSnapshot;
import net.sf.robocode.dejavu.harness.StubRobotSnapshot;
import net.sf.robocode.dejavu.harness.StubTurnSnapshot;
import net.sf.robocode.dejavu.model.DriftReason;
import net.sf.robocode.dejavu.model.TickCommands;
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 2 step 12 (the Phase 2 completion gate): fire-command finalization and
 * skipped-turn flags (design &sect;5/&sect;9, plan step 12).
 * <p>
 * Fire finalization reads the realized bullet power straight back from the fresh
 * hero-owned {@link BulletState#FIRED} bullet that materializes in {@code cur}
 * (the engine has already clamped it into
 * {@code [MIN_BULLET_POWER, MAX_BULLET_POWER]}); a power outside that range is a
 * real-engine impossibility and raises {@link IllegalStateException}.
 * <p>
 * Skipped-turn detection raises {@link DriftReason#SKIPPED_TURN_SUSPECTED} when a
 * turn gap ({@code cur.turn \u2212 prev.turn > 1}) means an engine advance went
 * unobserved &mdash; the lost commands are unrecoverable, so the reconstructor
 * replays a no-op tick &mdash; or, conservatively, when a single captured tick's
 * body-heading delta or velocity change exceeds the per-tick physical caps
 * (a forced stop to {@code 0}, e.g. a wall hit, stays exempt).
 * <p>
 * The integration test is the Phase 2 gate: across every bundled sample hero it
 * drives the full inferencer + reconstructor command stream and asserts the
 * reconstructed turns, move and fire reproduce the snapshot ground truth on
 * every comparable (unflagged) tick, while flagged/skipped ticks are excluded
 * from the denominator and counted separately.
 */
public class FireAndSkippedTurnTest {

    private static final double EPSILON = 1e-6;
    private static final double CAP_SLACK = 1e-4;

    private static final String ENEMY = "sample.Fire";
    private static final int ROUNDS = 3;
    private static final String[] HEROES = {
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"
    };

    @BeforeClass
    public static void initEngine() {
        // Constructing any harness initializes the Robocode engine.
        new BattleCaptureHarness("sample.SittingDuck", ENEMY, 1);
    }

    // ---- Unit tests: fire finalization ----------------------------------

    /**
     * A fresh hero-owned FIRED bullet in {@code cur} finalizes the realized fire:
     * {@code fired} is set and {@code firePower} equals the born bullet's power
     * exactly, with no confidence flags.
     */
    @Test
    public void firePowerMatchesBornBullet() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        ITurnSnapshot prev = snap(0, 0);
        ITurnSnapshot cur = snapWithHeroBullet(1, 0, 2.4, 100);

        TickCommands tc = reconstructor.reconstruct(prev, cur);

        assertTrue("fired", tc.isFired());
        assertEquals("realized fire power == born bullet power", 2.4, tc.getFirePower(), EPSILON);
        assertFalse("a normal fire tick is not a skipped turn",
                tc.getFlags().contains(DriftReason.SKIPPED_TURN_SUSPECTED));
    }

    /**
     * No fresh hero bullet means no fire was issued; a bullet that already existed
     * in {@code prev} (same id) is the hero's older bullet flying on, not a new
     * shot.
     */
    @Test
    public void noFreshBulletMeansNoFire() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        IBulletSnapshot flying = new StubBulletSnapshot()
                .ownerIndex(0).state(BulletState.MOVING).power(1.5).bulletId(42);
        ITurnSnapshot prev = snapWithBullets(0, 0, flying);
        ITurnSnapshot cur = snapWithBullets(1, 0, flying);

        TickCommands tc = reconstructor.reconstruct(prev, cur);

        assertFalse("no fresh bullet => not fired", tc.isFired());
        assertEquals("no fire power", 0, tc.getFirePower(), EPSILON);
    }

    // ---- Unit tests: skipped-turn detection -----------------------------

    /**
     * A turn gap ({@code cur.turn \u2212 prev.turn > 1}) flags
     * {@link DriftReason#SKIPPED_TURN_SUSPECTED} and replays a no-op tick: zero
     * turns, zero move, no fire (the lost commands are unrecoverable).
     */
    @Test
    public void turnGapFlagsSkippedAndReplaysNoOp() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        // prev at turn 5, cur at turn 7: turn 6 went unobserved. Even though the
        // headings/velocity moved, the realized tick command cannot be trusted.
        ITurnSnapshot prev = snapFull(5, 0.0, 0.0, 0.0, 0);
        ITurnSnapshot cur = snapFull(7, 0.3, 0.7, 1.1, 4);

        TickCommands tc = reconstructor.reconstruct(prev, cur);

        assertTrue("turn gap => SKIPPED_TURN_SUSPECTED",
                tc.getFlags().contains(DriftReason.SKIPPED_TURN_SUSPECTED));
        assertEquals("no-op turnBody", 0, tc.getTurnBody(), EPSILON);
        assertEquals("no-op turnGun", 0, tc.getTurnGun(), EPSILON);
        assertEquals("no-op turnRadar", 0, tc.getTurnRadar(), EPSILON);
        assertEquals("no-op move", 0, tc.getMoveDistance(), EPSILON);
        assertFalse("no-op tick does not fire", tc.isFired());
        assertEquals("turn label is cur's turn", 7, tc.getTurn());
    }

    /**
     * A single-tick body-heading delta beyond the velocity-dependent turn-rate
     * cap is physically impossible for one engine advance, so it raises
     * {@link DriftReason#SKIPPED_TURN_SUSPECTED} (the realized split is still
     * surfaced; only the confidence flag changes).
     */
    @Test
    public void bodyOverRateFlagsSkipped() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        // At velocity 0 the body cap is MAX_TURN_RATE_RADIANS (~0.175 rad); 0.5 rad
        // in one tick is impossible.
        ITurnSnapshot prev = snapFull(0, 0.0, 0.0, 0.0, 0);
        ITurnSnapshot cur = snapFull(1, 0.5, 0.5, 0.5, 0);

        TickCommands tc = reconstructor.reconstruct(prev, cur);

        assertTrue("body over-rate => SKIPPED_TURN_SUSPECTED",
                tc.getFlags().contains(DriftReason.SKIPPED_TURN_SUSPECTED));
    }

    /**
     * A velocity change beyond the accel/decel cap (here a full +8 to -8 reversal
     * in one tick) raises {@link DriftReason#SKIPPED_TURN_SUSPECTED}.
     */
    @Test
    public void velocityOverRateFlagsSkipped() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        ITurnSnapshot prev = snapFull(0, 0.0, 0.0, 0.0, Rules.MAX_VELOCITY);
        ITurnSnapshot cur = snapFull(1, 0.0, 0.0, 0.0, -Rules.MAX_VELOCITY);

        TickCommands tc = reconstructor.reconstruct(prev, cur);

        assertTrue("velocity over-rate => SKIPPED_TURN_SUSPECTED",
                tc.getFlags().contains(DriftReason.SKIPPED_TURN_SUSPECTED));
    }

    /**
     * A forced stop to ~0 (a wall hit collapsing velocity from the cap to 0) is
     * physically legal and must <em>not</em> be flagged, even though
     * {@code |Δvelocity|} exceeds the decel cap.
     */
    @Test
    public void wallStopIsNotFlaggedSkipped() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        ITurnSnapshot prev = snapFull(0, 0.0, 0.0, 0.0, Rules.MAX_VELOCITY);
        ITurnSnapshot cur = snapFull(1, 0.0, 0.0, 0.0, 0);

        TickCommands tc = reconstructor.reconstruct(prev, cur);

        assertFalse("a wall stop is not a skipped turn",
                tc.getFlags().contains(DriftReason.SKIPPED_TURN_SUSPECTED));
        assertEquals("realized move is the forced-stop velocity", 0, tc.getMoveDistance(), EPSILON);
    }

    // ---- Integration test (Phase 2 gate) --------------------------------

    @Test
    public void fullCommandStreamMatchesGroundTruthForAllHeroes() {
        int totalCompared = 0;
        int totalFires = 0;
        for (String hero : HEROES) {
            int[] stats = verifyHero(hero);
            totalCompared += stats[0];
            totalFires += stats[2];
        }
        assertTrue("no comparable ticks across any hero", totalCompared > 0);
        assertTrue("sample.Fire should fire at least once across the battle", totalFires > 0);
    }

    /** @return {@code [comparedTicks, skippedTicks, fireTicks]} for the hero. */
    private int[] verifyHero(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        CommandReconstructor reconstructor = new CommandReconstructor(0);
        int compared = 0;
        int skipped = 0;
        int fires = 0;
        int round = -1;
        ITurnSnapshot prev = null;

        for (CapturedTurn ct : captured) {
            if (ct.getRound() != round) {
                round = ct.getRound();
                prev = harness.getRoundStartSnapshot(round);
            }

            ITurnSnapshot cur = ct.getSnapshot();
            ITurnSnapshot base = prev == null ? cur : prev;

            TickCommands tc = reconstructor.reconstruct(base, cur);

            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";

            if (tc.getFlags().contains(DriftReason.SKIPPED_TURN_SUSPECTED)) {
                // Excluded from the comparable denominator and counted separately.
                skipped++;
                prev = cur;
                continue;
            }

            IRobotSnapshot p = base.getRobots()[0];
            IRobotSnapshot c = cur.getRobots()[0];

            // (a) The realized turns, forward-applied under the always-false coupling,
            // reproduce the snapshot absolute headings.
            double predBody = p.getBodyHeading() + tc.getTurnBody();
            double predGun = p.getGunHeading() + tc.getTurnBody() + tc.getTurnGun();
            double predRadar = p.getRadarHeading()
                    + tc.getTurnBody() + tc.getTurnGun() + tc.getTurnRadar();
            assertEquals(at + "body heading", 0,
                    Geometry.normalRelativeAngle(predBody - c.getBodyHeading()), EPSILON);
            assertEquals(at + "gun heading", 0,
                    Geometry.normalRelativeAngle(predGun - c.getGunHeading()), EPSILON);
            assertEquals(at + "radar heading", 0,
                    Geometry.normalRelativeAngle(predRadar - c.getRadarHeading()), EPSILON);

            // (b) The realized move equals the snapshot velocity.
            assertEquals(at + "realized move == snapshot velocity",
                    c.getVelocity(), tc.getMoveDistance(), EPSILON);

            // (c) The realized fire matches the independent fresh-bullet oracle.
            IBulletSnapshot oracle = freshHeroBullet(base, cur);
            if (oracle == null) {
                assertFalse(at + "no fresh bullet => not fired", tc.isFired());
            } else {
                assertTrue(at + "fresh bullet => fired", tc.isFired());
                assertEquals(at + "realized fire power == born bullet power",
                        oracle.getPower(), tc.getFirePower(), EPSILON);
                assertTrue(at + "fire power in legal range (" + tc.getFirePower() + ")",
                        tc.getFirePower() >= Rules.MIN_BULLET_POWER - EPSILON
                                && tc.getFirePower() <= Rules.MAX_BULLET_POWER + EPSILON);
                fires++;
            }

            prev = cur;
            compared++;
        }

        assertTrue("[" + hero + "] expected comparable ticks", compared > 0);
        return new int[] { compared, skipped, fires };
    }

    /**
     * Independent (test-owned) oracle for the realized fire: a hero-owned bullet
     * present in {@code cur} whose id is not in {@code prev}. Captured snapshots
     * expose a freshly fired bullet as {@link BulletState#MOVING} (the transient
     * {@code FIRED} state is never captured), so the fresh-id test is the cue.
     */
    private static IBulletSnapshot freshHeroBullet(ITurnSnapshot prev, ITurnSnapshot cur) {
        IBulletSnapshot[] curBullets = cur.getBullets();
        if (curBullets == null) {
            return null;
        }
        for (IBulletSnapshot b : curBullets) {
            if (b.getOwnerIndex() == 0 && !hasBulletId(prev, b.getBulletId())) {
                return b;
            }
        }
        return null;
    }

    private static boolean hasBulletId(ITurnSnapshot snapshot, int bulletId) {
        IBulletSnapshot[] bullets = snapshot.getBullets();
        if (bullets == null) {
            return false;
        }
        for (IBulletSnapshot b : bullets) {
            if (b.getBulletId() == bulletId) {
                return true;
            }
        }
        return false;
    }

    // ---- Synthetic snapshot helpers -------------------------------------

    private static IRobotSnapshot hero(double body, double velocity) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(80)
                .bodyHeading(body).gunHeading(body).radarHeading(body)
                .x(400).y(300).velocity(velocity);
    }

    private static IRobotSnapshot opponent() {
        return new StubRobotSnapshot()
                .name(ENEMY).robotIndex(1).energy(60).x(600).y(300);
    }

    /** Hero with body=gun=radar at {@code body}, given velocity, no bullets. */
    private static ITurnSnapshot snap(int t, double body) {
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { hero(body, 0), opponent() });
    }

    /** Hero with independent body/gun/radar headings and velocity, no bullets. */
    private static ITurnSnapshot snapFull(int t, double body, double gun, double radar, double velocity) {
        IRobotSnapshot h = new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(80)
                .bodyHeading(body).gunHeading(gun).radarHeading(radar)
                .x(400).y(300).velocity(velocity);
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { h, opponent() });
    }

    private static ITurnSnapshot snapWithBullets(int t, double body, IBulletSnapshot... bullets) {
        return new StubTurnSnapshot(0, t,
                new IRobotSnapshot[] { hero(body, 0), opponent() }, bullets);
    }

    private static ITurnSnapshot snapWithHeroBullet(int t, double body, double power, int bulletId) {
        IBulletSnapshot bullet = new StubBulletSnapshot()
                .ownerIndex(0).state(BulletState.FIRED).power(power).bulletId(bulletId)
                .x(400).y(300);
        return snapWithBullets(t, body, bullet);
    }
}
