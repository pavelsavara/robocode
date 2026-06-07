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
import net.sf.robocode.dejavu.harness.StubRobotSnapshot;
import net.sf.robocode.dejavu.harness.StubTurnSnapshot;
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.Event;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;
import robocode.util.Utils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1 step 5: collision events &mdash; {@link HitWallEvent} and
 * {@link HitRobotEvent} (design &sect;7, plan step 5).
 * <p>
 * The unit tests drive {@link EventReconstructor#reconstruct} with synthetic
 * snapshots: each wall edge yields the correct wall bearing, Y takes precedence
 * over X at the corners, and a head-on ram yields an at-fault
 * {@code HitRobotEvent} with the geometry-derived bearing.
 * <p>
 * The integration test reconstructs the collision event stream from the
 * captured snapshots of live battles and asserts it matches the engine ground
 * truth turn by turn: {@code sample.Walls} (which hugs the walls) exercises
 * {@code HitWallEvent}; {@code sample.Crazy} (which rams) exercises
 * {@code HitRobotEvent}; {@code sample.SittingDuck} never collides.
 */
public class CollisionEventsTest {

    private static final double EPSILON = 1e-6;
    private static final int BF_WIDTH = 800;
    private static final int BF_HEIGHT = 600;

    private static final String ENEMY = "sample.Fire";
    private static final int ROUNDS = 3;
    private static final String[] HEROES = {
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"
    };

    @BeforeClass
    public static void initEngine() {
        // Constructing any harness initializes the Robocode engine, which
        // installs the HiddenAccess helpers the reconstruction relies on.
        new BattleCaptureHarness("sample.SittingDuck", ENEMY, 1);
    }

    // ---- Unit tests: HitWallEvent ---------------------------------------

    @Test
    public void leftWallYieldsLeftBearing() {
        // Heading mostly west (sin < 0) so the robot moves into the left wall.
        double heading = 4.5;
        assertWallBearing(100, 300, 18, 300, heading, Utils.normalRelativeAngle(3 * Math.PI / 2 - heading));
    }

    @Test
    public void rightWallYieldsRightBearing() {
        // Heading mostly east (sin > 0) so the robot moves into the right wall.
        double heading = 1.5;
        assertWallBearing(BF_WIDTH - 100, 300, BF_WIDTH - 18, 300, heading,
                Utils.normalRelativeAngle(Math.PI / 2 - heading));
    }

    @Test
    public void bottomWallYieldsBottomBearing() {
        // Heading mostly south (cos < 0) so the robot moves into the bottom wall.
        double heading = 3.0;
        assertWallBearing(400, 100, 400, 18, heading, Utils.normalRelativeAngle(Math.PI - heading));
    }

    @Test
    public void topWallYieldsTopBearing() {
        // Heading mostly north (cos > 0) so the robot moves into the top wall.
        double heading = 0.3;
        assertWallBearing(400, BF_HEIGHT - 100, 400, BF_HEIGHT - 18, heading,
                Utils.normalRelativeAngle(-heading));
    }

    @Test
    public void cornerYieldsYAxisBearingByPrecedence() {
        // Bottom-left corner: heading into both minX (sin < 0) and minY (cos < 0).
        // The engine's Y branch overwrites the X branch, so the bottom bearing wins.
        double heading = 3.7;
        assertWallBearing(100, 100, 18, 18, heading, Utils.normalRelativeAngle(Math.PI - heading));
    }

    private void assertWallBearing(double prevX, double prevY, double x, double y, double heading,
            double expectedBearing) {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Seed a previous turn with the robot strictly inside the field, moving
        // toward the wall along the body heading.
        ITurnSnapshot prev = turn(40, heroAt(prevX, prevY, heading, 5.0, RobotState.ACTIVE), opp());
        reconstructor.reconstruct(prev, prev);

        // On the impact turn the engine clamps the robot onto the wall it overran;
        // the struck wall is recovered from the clamped position relative to the
        // strictly-inside previous position (no velocity is involved).
        IRobotSnapshot heroHitWall = heroAt(x, y, heading, 0.0, RobotState.HIT_WALL);
        ITurnSnapshot t = turn(41, heroHitWall, opp());
        List<Event> events = collisionEvents(reconstructor.reconstruct(prev, t).getEvents());

        assertEquals("exactly one collision event", 1, events.size());
        HitWallEvent e = (HitWallEvent) events.get(0);
        assertEquals("wall bearing", expectedBearing, e.getBearingRadians(), EPSILON);
    }

    // ---- Unit tests: HitRobotEvent --------------------------------------

    @Test
    public void headOnRamEmitsAtFaultHitRobotEvent() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Previous turn: hero at (400,300) heading north (0) moving at velocity 7.
        ITurnSnapshot prev = turn(40, heroAt(400, 300, 0.0, 7.0, RobotState.ACTIVE), opp(400, 320, 55));
        reconstructor.reconstruct(prev, prev);

        // This turn the hero rammed the opponent directly to the north and
        // bounced back to its pre-move position (400,300). The realised
        // pre-collision velocity is min(7+1, 8) = 8, so the pre-bounce hero
        // centre was (400, 308) and the opponent centre is (400, 320): the
        // bearing relative to the (north) body heading is 0. The hero loses one
        // ROBOT_HIT_DAMAGE (0.6) for the ram: 80 -> 79.4.
        ITurnSnapshot cur = turn(41, heroAt(400, 300, 0.0, 0.0, RobotState.HIT_ROBOT, 79.4), opp(400, 320, 54.4));
        List<Event> events = collisionEvents(reconstructor.reconstruct(prev, cur).getEvents());

        assertEquals("exactly one collision event", 1, events.size());
        HitRobotEvent e = (HitRobotEvent) events.get(0);
        assertEquals("opponent name", "sample.Fire", e.getName());
        assertTrue("hero is at fault", e.isMyFault());
        assertEquals("head-on bearing", 0.0, e.getBearingRadians(), EPSILON);
        assertEquals("opponent energy after ram damage", 54.4, e.getEnergy(), EPSILON);
    }

    @Test
    public void sideRamBearingMatchesRelativeGeometry() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Hero heading east (PI/2), velocity 7 -> pre-collision velocity 8.
        double heading = Math.PI / 2;
        ITurnSnapshot prev = turn(40, heroAt(400, 300, heading, 7.0, RobotState.ACTIVE), opp(420, 310, 55));
        reconstructor.reconstruct(prev, prev);

        // Pre-bounce hero centre = (400 + 8*sin(PI/2), 300 + 8*cos(PI/2)) = (408, 300).
        // The hero loses one ROBOT_HIT_DAMAGE (0.6) for the ram: 80 -> 79.4.
        ITurnSnapshot cur = turn(41, heroAt(400, 300, heading, 0.0, RobotState.HIT_ROBOT, 79.4), opp(420, 310, 54.4));
        List<Event> events = collisionEvents(reconstructor.reconstruct(prev, cur).getEvents());

        double angle = Math.atan2(420 - 408, 310 - 300);
        double expected = Utils.normalRelativeAngle(angle - heading);

        assertEquals("exactly one collision event", 1, events.size());
        HitRobotEvent e = (HitRobotEvent) events.get(0);
        assertTrue("hero is at fault", e.isMyFault());
        assertEquals("side-contact bearing", expected, e.getBearingRadians(), EPSILON);
    }

    @Test
    public void ramEmitsEveryContactTick() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        ITurnSnapshot prev = turn(40, heroAt(400, 300, 0.0, 7.0, RobotState.ACTIVE), opp(400, 320, 55));
        reconstructor.reconstruct(prev, prev);

        // Each contact tick costs the hero one ROBOT_HIT_DAMAGE (0.6): 80 -> 79.4 -> 78.8.
        ITurnSnapshot first = turn(41, heroAt(400, 300, 0.0, 0.0, RobotState.HIT_ROBOT, 79.4), opp(400, 320, 54.4));
        assertEquals("first contact tick emits", 1,
                collisionEvents(reconstructor.reconstruct(prev, first).getEvents()).size());

        ITurnSnapshot second = turn(42, heroAt(400, 300, 0.0, 0.0, RobotState.HIT_ROBOT, 78.8), opp(400, 320, 53.8));
        assertEquals("each subsequent contact tick re-emits", 1,
                collisionEvents(reconstructor.reconstruct(first, second).getEvents()).size());
    }

    @Test
    public void simultaneousDoubleRamEmitsAtFaultAndNotAtFaultEvents() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Both robots drive straight at each other and ram on the same tick: the
        // hero at (400,300) heading north (0), the opponent 20px due north at
        // (400,320) heading south (PI), each at velocity 7 -> pre-collision
        // velocity 8. Both end the tick in HIT_ROBOT, so each is at fault.
        ITurnSnapshot prev = turn(40,
                heroAt(400, 300, 0.0, 7.0, RobotState.ACTIVE),
                oppRamming(400, 320, Math.PI, 7.0, 55, RobotState.ACTIVE));
        reconstructor.reconstruct(prev, prev);

        // The hero is in two collisions (at-fault + not-at-fault), so it loses two
        // ROBOT_HIT_DAMAGE (2 * 0.6 = 1.2): 80 -> 78.8.
        ITurnSnapshot cur = turn(41,
                heroAt(400, 300, 0.0, 0.0, RobotState.HIT_ROBOT, 78.8),
                oppRamming(400, 320, Math.PI, 0.0, 54.4, RobotState.HIT_ROBOT));
        List<Event> events = collisionEvents(reconstructor.reconstruct(prev, cur).getEvents());

        assertEquals("two collision events (at-fault + not-at-fault)", 2, events.size());

        HitRobotEvent atFault = findHitRobot(events, true);
        HitRobotEvent notAtFault = findHitRobot(events, false);
        assertNotNull("at-fault HitRobotEvent present", atFault);
        assertNotNull("not-at-fault HitRobotEvent present", notAtFault);

        // The hero rammed due north into the opponent: head-on bearing 0.
        assertEquals("at-fault opponent name", "sample.Fire", atFault.getName());
        assertEquals("at-fault head-on bearing", 0.0, atFault.getBearingRadians(), EPSILON);
        assertEquals("at-fault opponent energy", 54.4, atFault.getEnergy(), EPSILON);

        // The opponent rammed due south into the hero: the bearing reflected into
        // the hero's (north) frame is also 0.
        assertEquals("not-at-fault opponent name", "sample.Fire", notAtFault.getName());
        assertEquals("not-at-fault reflected bearing", 0.0, notAtFault.getBearingRadians(), EPSILON);
        assertEquals("not-at-fault opponent energy", 54.4, notAtFault.getEnergy(), EPSILON);
    }

    // ---- Integration test (live ground truth) ---------------------------

    @Test
    public void reconstructedCollisionEventsMatchGroundTruthForAllHeroes() {
        int hitWall = 0;
        int hitRobot = 0;

        for (String hero : HEROES) {
            int[] counts = verifyCollisionEvents(hero);
            hitWall += counts[0];
            hitRobot += counts[1];
        }

        assertTrue("no HitWallEvent exercised", hitWall > 0);
        assertTrue("no HitRobotEvent exercised", hitRobot > 0);
    }

    /**
     * Simultaneous double-ram {@link HitRobotEvent}s: the ordering-sensitive case
     * (design &sect;7) the per-turn collision gate excludes.
     * <p>
     * When the hero and opponent ram each other on the same tick both robots are
     * at fault, so the engine delivers the hero <em>two</em> events: an at-fault
     * one (from the hero's own collision check) and a not-at-fault one (from the
     * opponent's). Each robot's check charges {@code ROBOT_HIT_DAMAGE} to both
     * robots, so the opponent energy a given event carries depends on whether the
     * hero's or the opponent's check ran first &mdash; an order driven by the
     * engine's shared-RNG robot processing and not recoverable from post-physics
     * snapshots (which only retain the doubly-decremented end-of-turn energy).
     * <p>
     * This test confronts that ordering problem directly on the turns the
     * {@link #reconstructedCollisionEventsMatchGroundTruthForAllHeroes} gate skips:
     * for every double-ram tick it asserts the reconstructed and ground-truth
     * hero {@code HitRobotEvent}s match exactly on the fully recoverable fields
     * (count, opponent name and at-fault flag), tolerating only the unrecoverable
     * order &mdash; the opponent energy must agree within an integer multiple of
     * {@code ROBOT_HIT_DAMAGE}, and the bearing within the documented geometry
     * tolerance.
     */
    @Test
    public void doubleRamHitRobotEventsMatchGroundTruthUnderOrderingTolerance() {
        int doubleRamTurns = 0;
        for (String hero : HEROES) {
            doubleRamTurns += verifyDoubleRamEvents(hero);
        }
        assertTrue("no simultaneous double-ram turn exercised", doubleRamTurns > 0);
    }

    /** @return the number of simultaneous double-ram turns verified. */
    private int verifyDoubleRamEvents(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int verified = 0;
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
            // Always advance the reconstructor so its per-turn state stays in step.
            List<Event> reconstructed = collisionEvents(
                    reconstructor.reconstruct(prev == null ? cur : prev, cur).getEvents());
            prev = cur;

            // Only the simultaneous double-ram turns are exercised here; the other
            // collision turns are covered exactly by the per-turn gate. A death
            // turn is never comparable.
            if (!bothAlive(cur) || !bothRammed(cur)) {
                continue;
            }

            List<Event> groundTruth = collisionEvents(java.util.Arrays.asList(ct.getHeroEvents()));
            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            matchDoubleRamEvents(at, groundTruth, reconstructed);
            verified++;
        }
        return verified;
    }

    private static void matchDoubleRamEvents(String at, List<Event> groundTruth,
            List<Event> reconstructed) {
        // A double-ram delivers the hero exactly the at-fault and not-at-fault
        // HitRobotEvent; the reconstruction emits the same pair.
        assertEquals(at + "double-ram hero HitRobotEvent count, RC=" + describeAll(reconstructed)
                + " GT=" + describeAll(groundTruth), groundTruth.size(), reconstructed.size());

        List<Event> remaining = new ArrayList<Event>(reconstructed);
        for (Event expected : groundTruth) {
            Event match = null;
            for (Event candidate : remaining) {
                if (doubleRamEventsEqual(expected, candidate)) {
                    match = candidate;
                    break;
                }
            }
            assertNotNull(at + "no reconstructed match (under ordering tolerance) for "
                    + describe(expected) + " RC=" + describeAll(reconstructed), match);
            remaining.remove(match);
        }
    }

    /**
     * Field equality for a double-ram {@link HitRobotEvent}: name and at-fault
     * flag exact; bearing within the geometry tolerance; opponent energy within
     * an integer multiple of {@link robocode.Rules#ROBOT_HIT_DAMAGE} (the
     * unrecoverable RNG decrement order).
     */
    private static boolean doubleRamEventsEqual(Event a, Event b) {
        if (!(a instanceof HitRobotEvent) || !(b instanceof HitRobotEvent)) {
            return false;
        }
        HitRobotEvent x = (HitRobotEvent) a;
        HitRobotEvent y = (HitRobotEvent) b;
        return x.getName().equals(y.getName())
                && x.isMyFault() == y.isMyFault()
                && nearBearing(x.getBearingRadians(), y.getBearingRadians())
                && energyNearUnderRamOrdering(x.getEnergy(), y.getEnergy());
    }

    /** True when the two energies agree within an integer multiple of the ram damage. */
    private static boolean energyNearUnderRamOrdering(double a, double b) {
        double diff = Math.abs(a - b);
        for (int k = 0; k <= 2; k++) {
            if (Math.abs(diff - k * robocode.Rules.ROBOT_HIT_DAMAGE) <= EPSILON) {
                return true;
            }
        }
        return false;
    }

    /** @return counts of matched [HitWall, HitRobot]. */
    private int[] verifyCollisionEvents(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int[] counts = new int[2];
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
            List<Event> reconstructed = collisionEvents(
                    reconstructor.reconstruct(prev == null ? cur : prev, cur).getEvents());
            prev = cur;

            // Determinism-independent bound: compare only turns where both robots
            // survive post-physics (see BulletEventsTest for the rationale).
            if (!bothAlive(cur)) {
                continue;
            }

            // A simultaneous double-collision (both robots ram each other on the
            // same tick) is excluded: the not-at-fault event then reports the
            // opponent's energy *before* the robot-hit damage is applied, and the
            // colliding geometry uses each robot's mid-update position. Both depend
            // on the engine's shared-RNG processing order and are not recoverable
            // from post-physics snapshots, mirroring the survival bound above.
            if (bothRammed(cur)) {
                continue;
            }

            List<Event> groundTruth = collisionEvents(java.util.Arrays.asList(ct.getHeroEvents()));
            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            matchCollisionEvents(at, groundTruth, reconstructed, counts);
        }
        return counts;
    }

    /** @return true when both robots are alive in the post-physics snapshot. */
    private static boolean bothAlive(ITurnSnapshot cur) {
        for (IRobotSnapshot r : cur.getRobots()) {
            if (r.getState().isDead()) {
                return false;
            }
        }
        return true;
    }

    /** @return true when both robots are at fault (simultaneous double-ram). */
    private static boolean bothRammed(ITurnSnapshot cur) {
        IRobotSnapshot[] robots = cur.getRobots();
        return robots[0].getState() == RobotState.HIT_ROBOT
                && robots[1].getState() == RobotState.HIT_ROBOT;
    }

    private static void matchCollisionEvents(String at, List<Event> groundTruth,
            List<Event> reconstructed, int[] counts) {
        assertEquals(at + "collision event count, RC=" + describeAll(reconstructed)
                + " GT=" + describeAll(groundTruth), groundTruth.size(), reconstructed.size());

        List<Event> remaining = new ArrayList<Event>(reconstructed);
        for (Event expected : groundTruth) {
            Event match = null;
            for (Event candidate : remaining) {
                if (eventsEqual(expected, candidate)) {
                    match = candidate;
                    break;
                }
            }
            assertNotNull(at + "no reconstructed match for " + describe(expected)
                    + " RC=" + describeAll(reconstructed), match);
            remaining.remove(match);

            if (expected instanceof HitWallEvent) {
                counts[0]++;
            } else if (expected instanceof HitRobotEvent) {
                counts[1]++;
            }
        }
    }

    private static boolean eventsEqual(Event a, Event b) {
        if (a.getClass() != b.getClass()) {
            return false;
        }
        if (a instanceof HitWallEvent) {
            return near(((HitWallEvent) a).getBearingRadians(), ((HitWallEvent) b).getBearingRadians());
        }
        if (a instanceof HitRobotEvent) {
            HitRobotEvent x = (HitRobotEvent) a;
            HitRobotEvent y = (HitRobotEvent) b;
            return x.getName().equals(y.getName())
                    && x.isMyFault() == y.isMyFault()
                    && nearBearing(x.getBearingRadians(), y.getBearingRadians())
                    && near(x.getEnergy(), y.getEnergy());
        }
        return false;
    }

    private static String describe(Event e) {
        if (e instanceof HitWallEvent) {
            return "HitWallEvent bearing=" + ((HitWallEvent) e).getBearingRadians();
        }
        if (e instanceof HitRobotEvent) {
            HitRobotEvent x = (HitRobotEvent) e;
            return "HitRobotEvent name=" + x.getName() + " fault=" + x.isMyFault()
                    + " bearing=" + x.getBearingRadians() + " energy=" + x.getEnergy();
        }
        return e.getClass().getSimpleName();
    }

    private static String describeAll(List<Event> events) {
        StringBuilder sb = new StringBuilder("[");
        for (Event e : events) {
            sb.append(describe(e)).append("; ");
        }
        return sb.append("]").toString();
    }

    // ---- Helpers --------------------------------------------------------

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPSILON;
    }

    // The HitRobot bearing is measured between the two robot centres at the
    // instant of contact. On a ram tick the engine bounces the at-fault robot
    // back to its pre-move position, so neither robot's sub-step velocity into
    // the collision survives in the post-physics snapshot; when the opponent is
    // also moving, the engine further uses its mid-update position, chosen by the
    // shared-RNG processing order. We reconstruct both centres from the previous
    // snapshot by advancing one acceleration step, which is exact when the rammer
    // accelerated cleanly and within a few degrees otherwise. Bearings are
    // therefore compared to this small, documented tolerance (the at-fault flag,
    // opponent name and energy are still matched exactly).
    private static final double BEARING_TOLERANCE = 0.1;

    private static boolean nearBearing(double a, double b) {
        return Math.abs(Utils.normalRelativeAngle(a - b)) <= BEARING_TOLERANCE;
    }

    private static List<Event> collisionEvents(List<Event> events) {
        List<Event> out = new ArrayList<Event>();
        for (Event e : events) {
            if (e instanceof HitWallEvent || e instanceof HitRobotEvent) {
                out.add(e);
            }
        }
        return out;
    }

    private static IRobotSnapshot heroAt(double x, double y, double bodyHeading,
            double velocity, RobotState state) {
        return heroAt(x, y, bodyHeading, velocity, state, 80);
    }

    private static IRobotSnapshot heroAt(double x, double y, double bodyHeading,
            double velocity, RobotState state, double energy) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(energy).state(state)
                .velocity(velocity).bodyHeading(bodyHeading).x(x).y(y);
    }

    private static IRobotSnapshot opp() {
        return opp(600, 300, 55);
    }

    private static IRobotSnapshot opp(double x, double y, double energy) {
        return new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(energy).x(x).y(y);
    }

    private static IRobotSnapshot oppRamming(double x, double y, double bodyHeading,
            double velocity, double energy, RobotState state) {
        return new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(energy).state(state)
                .velocity(velocity).bodyHeading(bodyHeading).x(x).y(y);
    }

    private static HitRobotEvent findHitRobot(List<Event> events, boolean myFault) {
        for (Event e : events) {
            if (e instanceof HitRobotEvent && ((HitRobotEvent) e).isMyFault() == myFault) {
                return (HitRobotEvent) e;
            }
        }
        return null;
    }

    private static ITurnSnapshot turn(int t, IRobotSnapshot hero, IRobotSnapshot opponent) {
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { hero, opponent }, new IBulletSnapshot[0]);
    }
}
