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
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.Event;
import robocode.HitByBulletEvent;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1 step 4: bullet lifecycle events &mdash; {@link BulletHitEvent},
 * {@link HitByBulletEvent}, {@link BulletMissedEvent} and
 * {@link BulletHitBulletEvent} (design &sect;6, plan step 4).
 * <p>
 * The unit tests drive {@link EventReconstructor#reconstruct} with synthetic
 * snapshots and verify that each terminal bullet transition emits exactly one
 * event with the correct fields; that a terminal state persisting across ticks
 * does not re-emit; that {@code BulletHitBulletEvent} pairs the correct two
 * bullets when several are present; and that the {@code HitByBulletEvent}
 * bearing matches a hand-worked case.
 * <p>
 * The integration test reconstructs the bullet event stream from the captured
 * snapshots of live battles and asserts it matches the engine ground truth
 * (the events the engine actually delivered to the hero) turn by turn &mdash;
 * counts, ids, fields and relative ordering of the four bullet event types.
 */
public class BulletEventsTest {

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
        // Constructing any RobotTestBed initializes the Robocode engine, which
        // installs the HiddenAccess helpers the reconstruction relies on.
        new BattleCaptureHarness("sample.SittingDuck", ENEMY, 1);
    }

    // ---- Unit tests (synthetic snapshots) -------------------------------

    @Test
    public void heroBulletHittingVictimEmitsBulletHitEvent() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        IBulletSnapshot moving = bullet(BulletState.MOVING, 0, 7, 3.0, 1.2, 410, 305);
        reconstructor.reconstruct(turn(40, hero(80), opponent(55), moving),
                turn(40, hero(80), opponent(55), moving));

        IBulletSnapshot hit = bullet(BulletState.HIT_VICTIM, 0, 7, 3.0, 1.2, 420, 310)
                .victimIndex(1);
        // The hero earns getBulletHitBonus(3.0) = 9 for the hit: 80 -> 89.
        List<Event> events = bulletEvents(reconstructor.reconstruct(
                turn(40, hero(80), opponent(55), moving),
                turn(41, hero(89), opponent(39), hit)).getEvents());

        assertEquals("exactly one bullet event", 1, events.size());
        BulletHitEvent e = (BulletHitEvent) events.get(0);
        assertEquals("victim name", "sample.Fire", e.getName());
        assertEquals("victim energy after damage", 39.0, e.getEnergy(), EPSILON);
        assertEquals("bullet id", 7, e.getBullet().hashCode());
        assertEquals("bullet power", 3.0, e.getBullet().getPower(), EPSILON);
        assertEquals("bullet heading", 1.2, e.getBullet().getHeadingRadians(), EPSILON);
    }

    @Test
    public void opponentBulletHittingHeroEmitsHitByBulletEvent() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        // Opponent-owned bullet (ownerIndex 1) strikes the hero (victimIndex 0).
        // Bearing = normalRelativeAngle(bulletHeading + PI - heroBodyHeading).
        double bulletHeading = 0.5;
        double heroBodyHeading = 1.0;
        IBulletSnapshot moving = bullet(BulletState.MOVING, 1, 1002, 2.0, bulletHeading, 405, 305);
        reconstructor.reconstruct(turn(40, hero(80, heroBodyHeading), opponent(55), moving),
                turn(40, hero(80, heroBodyHeading), opponent(55), moving));

        IBulletSnapshot hit = bullet(BulletState.HIT_VICTIM, 1, 1002, 2.0, bulletHeading, 400, 300)
                .victimIndex(0);
        // The hero takes getBulletDamage(2.0) = 10 of damage: 80 -> 70.
        List<Event> events = bulletEvents(reconstructor.reconstruct(
                turn(40, hero(80, heroBodyHeading), opponent(55), moving),
                turn(41, hero(70, heroBodyHeading), opponent(55), hit)).getEvents());

        assertEquals("exactly one bullet event", 1, events.size());
        HitByBulletEvent e = (HitByBulletEvent) events.get(0);
        double expectedBearing = normalRelative(bulletHeading + Math.PI - heroBodyHeading);
        assertEquals("bearing", expectedBearing, e.getBearingRadians(), EPSILON);
        assertEquals("bullet heading", bulletHeading, e.getBullet().getHeadingRadians(), EPSILON);
        assertEquals("bullet power", 2.0, e.getBullet().getPower(), EPSILON);
        assertEquals("shooter name", "sample.Fire", e.getName());
        assertEquals("bullet id", 1002, e.getBullet().hashCode());
    }

    @Test
    public void heroBulletHittingWallEmitsBulletMissedEvent() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        IBulletSnapshot moving = bullet(BulletState.MOVING, 0, 4, 1.5, 0.0, 780, 300);
        reconstructor.reconstruct(turn(40, hero(80), opponent(55), moving),
                turn(40, hero(80), opponent(55), moving));

        IBulletSnapshot hit = bullet(BulletState.HIT_WALL, 0, 4, 1.5, 0.0, 800, 300);
        List<Event> events = bulletEvents(reconstructor.reconstruct(
                turn(40, hero(80), opponent(55), moving),
                turn(41, hero(80), opponent(55), hit)).getEvents());

        assertEquals("exactly one bullet event", 1, events.size());
        BulletMissedEvent e = (BulletMissedEvent) events.get(0);
        assertEquals("bullet id", 4, e.getBullet().hashCode());
        assertEquals("bullet power", 1.5, e.getBullet().getPower(), EPSILON);
    }

    @Test
    public void heroBulletHittingBulletEmitsBulletHitBulletEvent() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        IBulletSnapshot heroMoving = bullet(BulletState.MOVING, 0, 5, 2.0, 0.7, 495, 300);
        IBulletSnapshot oppMoving = bullet(BulletState.MOVING, 1, 1009, 3.0, 3.8, 505, 300);
        reconstructor.reconstruct(turn(40, hero(80), opponent(55), heroMoving, oppMoving),
                turn(40, hero(80), opponent(55), heroMoving, oppMoving));

        // Both bullets reset to their pre-move positions on collision.
        IBulletSnapshot heroHit = bullet(BulletState.HIT_BULLET, 0, 5, 2.0, 0.7, 499, 300);
        IBulletSnapshot oppHit = bullet(BulletState.HIT_BULLET, 1, 1009, 3.0, 3.8, 501, 300);
        List<Event> events = bulletEvents(reconstructor.reconstruct(
                turn(40, hero(80), opponent(55), heroMoving, oppMoving),
                turn(41, hero(80), opponent(55), heroHit, oppHit)).getEvents());

        assertEquals("exactly one bullet event", 1, events.size());
        BulletHitBulletEvent e = (BulletHitBulletEvent) events.get(0);
        assertEquals("the hero's bullet", 5, e.getBullet().hashCode());
        assertEquals("the bullet it struck", 1009, e.getHitBullet().hashCode());
        assertEquals("hit bullet power", 3.0, e.getHitBullet().getPower(), EPSILON);
    }

    @Test
    public void bulletHitBulletPairsTheNearestOpposingBullet() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        IBulletSnapshot heroMoving = bullet(BulletState.MOVING, 0, 5, 2.0, 0.7, 300, 300);
        IBulletSnapshot near = bullet(BulletState.MOVING, 1, 1009, 3.0, 3.8, 305, 300);
        IBulletSnapshot far = bullet(BulletState.MOVING, 1, 1010, 1.0, 3.8, 700, 300);
        reconstructor.reconstruct(turn(40, hero(80), opponent(55), heroMoving, near, far),
                turn(40, hero(80), opponent(55), heroMoving, near, far));

        IBulletSnapshot heroHit = bullet(BulletState.HIT_BULLET, 0, 5, 2.0, 0.7, 302, 300);
        IBulletSnapshot nearHit = bullet(BulletState.HIT_BULLET, 1, 1009, 3.0, 3.8, 303, 300);
        // A second, unrelated opposing HIT_BULLET far away must not be paired.
        IBulletSnapshot farHit = bullet(BulletState.HIT_BULLET, 1, 1010, 1.0, 3.8, 702, 300);
        List<Event> events = bulletEvents(reconstructor.reconstruct(
                turn(40, hero(80), opponent(55), heroMoving, near, far),
                turn(41, hero(80), opponent(55), heroHit, nearHit, farHit)).getEvents());

        assertEquals("one event for the hero's single bullet", 1, events.size());
        BulletHitBulletEvent e = (BulletHitBulletEvent) events.get(0);
        assertEquals("paired with the nearest opposing bullet", 1009, e.getHitBullet().hashCode());
    }

    @Test
    public void persistingTerminalStateDoesNotReEmit() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();

        IBulletSnapshot moving = bullet(BulletState.MOVING, 0, 7, 3.0, 1.2, 410, 305);
        reconstructor.reconstruct(turn(40, hero(80), opponent(55), moving),
                turn(40, hero(80), opponent(55), moving));

        IBulletSnapshot hit = bullet(BulletState.HIT_VICTIM, 0, 7, 3.0, 1.2, 420, 310).victimIndex(1);
        // The hero earns getBulletHitBonus(3.0) = 9 on the rising edge: 80 -> 89.
        List<Event> first = bulletEvents(reconstructor.reconstruct(
                turn(40, hero(80), opponent(55), moving),
                turn(41, hero(89), opponent(39), hit)).getEvents());
        assertEquals("rising edge emits once", 1, first.size());

        // The engine keeps the bullet in HIT_VICTIM through the explosion frames;
        // those repeated snapshots must not re-emit the event.
        IBulletSnapshot stillHit = bullet(BulletState.HIT_VICTIM, 0, 7, 3.0, 1.2, 420, 310).victimIndex(1);
        List<Event> second = bulletEvents(reconstructor.reconstruct(
                turn(41, hero(89), opponent(39), hit),
                turn(42, hero(89), opponent(39), stillHit)).getEvents());
        assertTrue("a persisting terminal frame does not re-emit", second.isEmpty());
    }

    // ---- Integration test (live ground truth) ---------------------------

    @Test
    public void reconstructedBulletEventsMatchGroundTruthForAllHeroes() {
        int bulletHit = 0;
        int hitByBullet = 0;
        int bulletMissed = 0;
        int bulletHitBullet = 0;

        for (String hero : HEROES) {
            int[] counts = verifyBulletEvents(hero);
            bulletHit += counts[0];
            hitByBullet += counts[1];
            bulletMissed += counts[2];
            bulletHitBullet += counts[3];
        }

        // Across the matchups the four event types must all be exercised so the
        // reconstruction is validated, not merely matched against an empty set.
        assertTrue("no BulletHitEvent exercised", bulletHit > 0);
        assertTrue("no HitByBulletEvent exercised", hitByBullet > 0);
        assertTrue("no BulletMissedEvent exercised", bulletMissed > 0);
        assertTrue("no BulletHitBulletEvent exercised", bulletHitBullet > 0);
    }

    /** @return counts of matched [BulletHit, HitByBullet, BulletMissed, BulletHitBullet]. */
    private int[] verifyBulletEvents(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int[] counts = new int[4];
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
            // Always advance the reconstructor so its per-bullet state stays in
            // step with the snapshot stream.
            List<Event> reconstructed = bulletEvents(
                    reconstructor.reconstruct(prev == null ? cur : prev, cur).getEvents());
            prev = cur;

            // Determinism-independent bound: only the turns where both robots
            // survive post-physics are reproducible. On a death turn the engine
            // delivers the killing-blow event from whichever robot its shuffled
            // death-processing order reaches first before that robot's thread
            // stops reading out events -- an order driven by a shared global RNG
            // that is not recoverable from snapshots and varies across runs
            // (most visibly in sample.Fire self-play). Skip those turns; the
            // four event types are all exercised abundantly during active play.
            if (!bothAlive(cur)) {
                continue;
            }

            List<Event> groundTruth = bulletEvents(java.util.Arrays.asList(ct.getHeroEvents()));
            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            matchBulletEvents(at, groundTruth, reconstructed, counts);
        }
        return counts;
    }

    /** @return true when both robots are alive in the post-physics snapshot. */
    private static boolean bothAlive(ITurnSnapshot cur) {
        for (robocode.control.snapshot.IRobotSnapshot r : cur.getRobots()) {
            if (r.getState().isDead()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Assert that the reconstructed bullet events for a turn are exactly the
     * engine ground truth: same count, and every ground-truth event has a
     * field-matching reconstructed counterpart (paired by bullet id).
     */
    private static void matchBulletEvents(String at, List<Event> groundTruth,
            List<Event> reconstructed, int[] counts) {
        assertEquals(at + "bullet event count", groundTruth.size(), reconstructed.size());

        // A BulletHitEvent carries the victim's energy at the instant the engine
        // processed that bullet inside its RNG-shuffled updateBullets() loop. When
        // the victim also lands a bullet on the hero the same turn, the engine
        // credits the victim a 3*power hit bonus (BulletPeer.checkRobotCollision)
        // somewhere in that same loop. Whether the bonus is applied before or
        // after the hero's hit is decided by Collections.shuffle on the shared
        // global RNG (Battle.getBulletsAtRandom) and is not recoverable from
        // snapshots. Both orderings yield the identical end-of-turn energy, so the
        // reconstruction reports the bonus-after value; accept the bonus-before
        // value too by widening the BulletHitEvent energy match by any subset sum
        // of the victim's same-turn bonuses.
        List<Double> energyOffsets = victimBonusSubsetSums(groundTruth);

        List<Event> remaining = new ArrayList<Event>(reconstructed);
        for (Event expected : groundTruth) {
            Event match = null;
            for (Event candidate : remaining) {
                if (eventsEqual(expected, candidate, energyOffsets)) {
                    match = candidate;
                    break;
                }
            }
            assertNotNull(at + "no reconstructed match for " + describe(expected)
                    + " RC=" + describeAll(reconstructed), match);
            remaining.remove(match);

            if (expected instanceof BulletHitEvent) {
                counts[0]++;
            } else if (expected instanceof HitByBulletEvent) {
                counts[1]++;
            } else if (expected instanceof BulletMissedEvent) {
                counts[2]++;
            } else if (expected instanceof BulletHitBulletEvent) {
                counts[3]++;
            }
        }
    }

    private static boolean eventsEqual(Event a, Event b) {
        return eventsEqual(a, b, java.util.Collections.singletonList(0.0));
    }

    /**
     * Field equality where a ground-truth {@link BulletHitEvent} energy matches
     * the reconstructed energy plus any of {@code energyOffsets} &mdash; the
     * unrecoverable victim hit-bonus orderings (see {@link #matchBulletEvents}).
     */
    private static boolean eventsEqual(Event a, Event b, List<Double> energyOffsets) {
        if (a.getClass() != b.getClass()) {
            return false;
        }
        if (a instanceof BulletHitEvent) {
            BulletHitEvent x = (BulletHitEvent) a;
            BulletHitEvent y = (BulletHitEvent) b;
            return x.getBullet().hashCode() == y.getBullet().hashCode()
                    && x.getName().equals(y.getName())
                    && energyMatches(x.getEnergy(), y.getEnergy(), energyOffsets)
                    && near(x.getBullet().getPower(), y.getBullet().getPower())
                    && near(x.getBullet().getHeadingRadians(), y.getBullet().getHeadingRadians());
        }
        if (a instanceof HitByBulletEvent) {
            HitByBulletEvent x = (HitByBulletEvent) a;
            HitByBulletEvent y = (HitByBulletEvent) b;
            return x.getBullet().hashCode() == y.getBullet().hashCode()
                    && near(x.getBearingRadians(), y.getBearingRadians())
                    && near(x.getBullet().getPower(), y.getBullet().getPower())
                    && near(x.getBullet().getHeadingRadians(), y.getBullet().getHeadingRadians());
        }
        if (a instanceof BulletMissedEvent) {
            BulletMissedEvent x = (BulletMissedEvent) a;
            BulletMissedEvent y = (BulletMissedEvent) b;
            return x.getBullet().hashCode() == y.getBullet().hashCode()
                    && near(x.getBullet().getPower(), y.getBullet().getPower())
                    && near(x.getBullet().getHeadingRadians(), y.getBullet().getHeadingRadians());
        }
        if (a instanceof BulletHitBulletEvent) {
            BulletHitBulletEvent x = (BulletHitBulletEvent) a;
            BulletHitBulletEvent y = (BulletHitBulletEvent) b;
            return x.getBullet().hashCode() == y.getBullet().hashCode()
                    && x.getHitBullet().hashCode() == y.getHitBullet().hashCode()
                    && near(x.getHitBullet().getPower(), y.getHitBullet().getPower());
        }
        return false;
    }

    private static String describe(Event e) {
        if (e instanceof BulletHitEvent) {
            BulletHitEvent x = (BulletHitEvent) e;
            return "BulletHitEvent id=" + x.getBullet().hashCode() + " name=" + x.getName()
                    + " energy=" + x.getEnergy();
        }
        if (e instanceof HitByBulletEvent) {
            HitByBulletEvent x = (HitByBulletEvent) e;
            return "HitByBulletEvent id=" + x.getBullet().hashCode()
                    + " bearing=" + x.getBearingRadians();
        }
        if (e instanceof BulletMissedEvent) {
            return "BulletMissedEvent id=" + ((BulletMissedEvent) e).getBullet().hashCode();
        }
        if (e instanceof BulletHitBulletEvent) {
            BulletHitBulletEvent x = (BulletHitBulletEvent) e;
            return "BulletHitBulletEvent id=" + x.getBullet().hashCode()
                    + " hit=" + x.getHitBullet().hashCode();
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

    /**
     * True when the ground-truth victim energy equals the reconstructed energy
     * plus one of the candidate hit-bonus offsets.
     */
    private static boolean energyMatches(double groundTruth, double reconstructed,
            List<Double> offsets) {
        for (double offset : offsets) {
            if (near(groundTruth, reconstructed + offset)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The distinct subset sums (including 0) of the 3*power hit bonuses the
     * victim earns this turn &mdash; one per {@link HitByBulletEvent} the hero
     * receives, i.e. one per opponent bullet that strikes the hero. The hero is
     * the shooter for every {@link BulletHitEvent} this turn, so the victim is
     * the opponent, and every opponent bullet landing on the hero credits the
     * opponent {@code Rules.getBulletHitBonus(power)} in the same shuffled loop.
     */
    private static List<Double> victimBonusSubsetSums(List<Event> groundTruth) {
        List<Double> bonuses = new ArrayList<Double>();
        for (Event e : groundTruth) {
            if (e instanceof HitByBulletEvent) {
                bonuses.add(robocode.Rules.getBulletHitBonus(
                        ((HitByBulletEvent) e).getBullet().getPower()));
            }
        }
        List<Double> sums = new ArrayList<Double>();
        sums.add(0.0);
        for (double bonus : bonuses) {
            int size = sums.size();
            for (int i = 0; i < size; i++) {
                sums.add(sums.get(i) + bonus);
            }
        }
        return sums;
    }

    private static double normalRelative(double angle) {
        double a = angle;
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a <= -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }

    private static List<Event> bulletEvents(List<Event> events) {
        List<Event> out = new ArrayList<Event>();
        for (Event e : events) {
            if (e instanceof BulletHitEvent || e instanceof HitByBulletEvent
                    || e instanceof BulletMissedEvent || e instanceof BulletHitBulletEvent) {
                out.add(e);
            }
        }
        return out;
    }

    private static StubBulletSnapshot bullet(BulletState state, int ownerIndex, int id,
            double power, double heading, double x, double y) {
        return new StubBulletSnapshot()
                .state(state).ownerIndex(ownerIndex).bulletId(id)
                .power(power).heading(heading).x(x).y(y);
    }

    private static IRobotSnapshot hero(double energy) {
        return hero(energy, 0.0);
    }

    private static IRobotSnapshot hero(double energy, double bodyHeading) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(energy).bodyHeading(bodyHeading)
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
