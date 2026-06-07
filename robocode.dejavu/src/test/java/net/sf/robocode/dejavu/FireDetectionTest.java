/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu;

import net.sf.robocode.dejavu.core.EventReconstructor;
import net.sf.robocode.dejavu.harness.BattleCaptureHarness;
import net.sf.robocode.dejavu.harness.CapturedTurn;
import net.sf.robocode.dejavu.harness.StubBulletSnapshot;
import net.sf.robocode.dejavu.harness.StubRobotSnapshot;
import net.sf.robocode.dejavu.harness.StubTurnSnapshot;
import net.sf.robocode.dejavu.model.FireDetection;
import net.sf.robocode.peer.IExecCommands;
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.Bullet;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 1 step 3: fire detection &mdash; bullet birth and realized fire power
 * (design &sect;5, plan step 3).
 * <p>
 * The unit tests drive {@link EventReconstructor#detectFire} with synthetic
 * snapshots and verify that a new hero-owned {@code FIRED} bullet is detected
 * with its exact (engine-clamped) power and a reconstructed {@link Bullet}
 * carrying the real {@code bulletId}; that a non-{@code FIRED} or absent bullet
 * yields nothing; that the gun-heat / energy cross-checks flag an inconsistent
 * fire; and that simultaneous fires are attributed to the hero by
 * {@code ownerIndex}.
 * <p>
 * The integration test reconstructs the realized fire stream from the captured
 * turn snapshots of live battles and asserts it matches an independent ground
 * truth derived from the engine's captured fire commands (the requested power
 * gated by the pre-fire gun heat / energy and clamped to the bullet-power
 * range): firing heroes detect every realized fire (turn + power) with no
 * spurious fires, while the passive {@code sample.SittingDuck} never fires.
 */
public class FireDetectionTest {

    private static final double EPSILON = 1e-6;
    private static final int BF_WIDTH = 800;
    private static final int BF_HEIGHT = 600;

    private static final String ENEMY = "sample.Fire";
    private static final int ROUNDS = 3;
    private static final String[] HEROES = {
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"
    };
    private static final String SITTING_DUCK = "sample.SittingDuck";

    @BeforeClass
    public static void initEngine() {
        // Constructing any RobotTestBed initializes the Robocode engine, which
        // installs the HiddenAccess helpers the reconstruction relies on.
        new BattleCaptureHarness(SITTING_DUCK, ENEMY, 1);
    }

    // ---- Unit tests (synthetic snapshots) -------------------------------

    @Test
    public void newFiredBulletDetectedWithExactPower() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Before firing: gun cool, energy ample. After firing: gun heat risen to
        // ~1 + 3/5 (then cooled one step), energy reduced by the fire power.
        ITurnSnapshot prev = turn(40, hero(80, 0.0), opponent(55));
        IBulletSnapshot born = new StubBulletSnapshot()
                .state(BulletState.FIRED).ownerIndex(0).bulletId(7)
                .power(3).heading(1.2).x(400).y(300);
        ITurnSnapshot cur = turn(41, hero(77, 1.5), opponent(55), born);

        FireDetection fire = reconstructor.detectFire(prev, cur);
        assertNotNull("a new FIRED hero bullet is a fire", fire);
        assertEquals("fire power", 3.0, fire.getFirePower(), EPSILON);
        assertEquals("fire turn", 41, fire.getTurn());

        Bullet bullet = fire.getBullet();
        assertEquals("bullet id", 7, bullet.hashCode());
        assertEquals("bullet power", 3.0, bullet.getPower(), EPSILON);
        assertEquals("bullet owner", "hero", bullet.getName());
        assertEquals("bullet heading", 1.2, bullet.getHeadingRadians(), EPSILON);
        assertTrue("a born bullet is active", bullet.isActive());
    }

    @Test
    public void noNewBulletNoFire() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        ITurnSnapshot prev = turn(40, hero(80, 0.0), opponent(55));
        ITurnSnapshot cur = turn(41, hero(80, 0.0), opponent(55));

        assertNull("no bullet means no fire", reconstructor.detectFire(prev, cur));
    }

    @Test
    public void movingBulletIsNotANewFire() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // A hero bullet already in flight (present in the previous snapshot) is
        // not a birth this turn.
        IBulletSnapshot before = new StubBulletSnapshot()
                .state(BulletState.MOVING).ownerIndex(0).bulletId(3).power(2).x(410).y(310);
        IBulletSnapshot moving = new StubBulletSnapshot()
                .state(BulletState.MOVING).ownerIndex(0).bulletId(3).power(2).x(420).y(320);
        ITurnSnapshot prev = turn(40, hero(80, 1.4), opponent(55), before);
        ITurnSnapshot cur = turn(41, hero(80, 1.3), opponent(55), moving);

        assertNull("an in-flight bullet is not a new fire",
                reconstructor.detectFire(prev, cur));
    }

    @Test
    public void gunHeatCrossCheckRejectsInconsistency() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // A FIRED bullet, but the gun heat did not rise at all: physically
        // impossible under the real engine, so reconstruction rejects the input.
        IBulletSnapshot born = new StubBulletSnapshot()
                .state(BulletState.FIRED).ownerIndex(0).bulletId(9).power(3).x(400).y(300);
        ITurnSnapshot prev = turn(40, hero(80, 0.0), opponent(55));
        ITurnSnapshot cur = turn(41, hero(77, 0.0), opponent(55), born);

        try {
            reconstructor.detectFire(prev, cur);
            fail("a gun heat that did not rise must be rejected");
        } catch (IllegalStateException expected) {
            // expected: the input is not a faithful recording of a real battle.
        }
    }

    @Test
    public void energyCrossCheckRejectsUnaffordableFire() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Fire power exceeds the energy available before firing: inconsistent.
        IBulletSnapshot born = new StubBulletSnapshot()
                .state(BulletState.FIRED).ownerIndex(0).bulletId(11).power(3).x(400).y(300);
        ITurnSnapshot prev = turn(40, hero(2, 0.0), opponent(55));
        ITurnSnapshot cur = turn(41, hero(0, 1.5), opponent(55), born);

        try {
            reconstructor.detectFire(prev, cur);
            fail("firing more than the available energy must be rejected");
        } catch (IllegalStateException expected) {
            // expected: the input is not a faithful recording of a real battle.
        }
    }

    @Test
    public void twoRobotsFiringSameTickAttributedByOwnerIndex() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Both robots fire this tick; only the hero-owned (ownerIndex 0) bullet
        // is the hero's fire.
        IBulletSnapshot heroBullet = new StubBulletSnapshot()
                .state(BulletState.FIRED).ownerIndex(0).bulletId(5).power(2).heading(0.7).x(400).y(300);
        IBulletSnapshot oppBullet = new StubBulletSnapshot()
                .state(BulletState.FIRED).ownerIndex(1).bulletId(9).power(3).heading(2.0).x(600).y(300);
        ITurnSnapshot prev = turn(40, hero(50, 0.0), opponent(50));
        ITurnSnapshot cur = turn(41, hero(48, 1.3), opponent(47), heroBullet, oppBullet);

        FireDetection fire = reconstructor.detectFire(prev, cur);
        assertNotNull("the hero's own fire is detected", fire);
        assertEquals("the hero's fire power, not the opponent's", 2.0, fire.getFirePower(), EPSILON);
        assertEquals("the hero's bullet id, not the opponent's", 5, fire.getBullet().hashCode());
    }

    // ---- Integration test (live ground truth) ---------------------------

    @Test
    public void reconstructedFiresMatchGroundTruthForAllHeroes() {
        for (String hero : HEROES) {
            verifyFires(hero);
        }
    }

    private void verifyFires(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int detectedFires = 0;
        int round = -1;
        EventReconstructor reconstructor = null;
        ITurnSnapshot prev = null;

        for (CapturedTurn ct : captured) {
            if (ct.getRound() != round) {
                round = ct.getRound();
                reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, ROUNDS);
                reconstructor.resetRound();
                ITurnSnapshot start = harness.getRoundStartSnapshot(round);
                assertNotNull("[" + hero + "] r" + round + " missing round-start snapshot", start);
                reconstructor.seedRoundStart(start);
                prev = null;
            }

            ITurnSnapshot cur = ct.getSnapshot();
            reconstructor.reconstruct(prev == null ? cur : prev, cur);
            FireDetection fire = reconstructor.getLastFireDetection();

            // Ground truth: the engine fired this turn's requested bullet iff the
            // gun was cool and the robot had energy at the start of the turn
            // (the post-physics state of the previous turn).
            Double predicted = predictedFire(ct, prev);

            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            if (predicted == null) {
                assertNull(at + "reconstructed a spurious fire", fire);
            } else {
                assertNotNull(at + "missed a fire the engine realized", fire);
                assertEquals(at + "fire power", predicted.doubleValue(), fire.getFirePower(), EPSILON);
                assertEquals(at + "fire turn", ct.getTurn(), fire.getTurn());
                detectedFires++;
            }

            prev = cur;
        }

        if (SITTING_DUCK.equals(hero)) {
            assertEquals("[" + hero + "] a passive robot never fires", 0, detectedFires);
        } else {
            assertTrue("[" + hero + "] a firing robot should produce fires", detectedFires > 0);
        }
    }

    /**
     * The realized fire power the engine would have produced on the turn
     * captured by {@code ct}, or {@code null} if no bullet was fired. Mirrors
     * {@code RobotPeer.fireBullets}: the first requested fire power is realized
     * only when the gun is cool and the robot has energy at the start of the
     * turn (the previous turn's post-physics state), clamped to the bullet-power
     * range and to the available energy.
     */
    private static Double predictedFire(CapturedTurn ct, ITurnSnapshot prevSnapshot) {
        if (prevSnapshot == null) {
            return null;
        }
        IExecCommands commands = ct.getHeroCommands();
        if (commands == null) {
            return null;
        }
        double requested = Double.NaN;
        for (double power : commands.getFirePowers()) {
            if (!Double.isNaN(power)) {
                requested = power;
                break;
            }
        }
        if (Double.isNaN(requested)) {
            return null;
        }

        IRobotSnapshot pre = prevSnapshot.getRobots()[0];
        if (pre.getGunHeat() > EPSILON || pre.getEnergy() <= 0.0) {
            return null;
        }
        double clamped = Math.min(Math.max(requested, 0.1), 3.0);
        return Math.min(pre.getEnergy(), clamped);
    }

    // ---- Helpers --------------------------------------------------------

    private static IRobotSnapshot hero(double energy, double gunHeat) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(energy).gunHeat(gunHeat)
                .x(400).y(300);
    }

    private static IRobotSnapshot opponent(double energy) {
        return new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(energy).x(600).y(300);
    }

    private static ITurnSnapshot turn(int t, IRobotSnapshot hero, IRobotSnapshot opponent,
            IBulletSnapshot... bullets) {
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { hero, opponent }, bullets);
    }
}
