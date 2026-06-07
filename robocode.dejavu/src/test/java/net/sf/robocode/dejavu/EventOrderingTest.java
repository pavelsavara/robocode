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
import net.sf.robocode.dejavu.model.TickEvents;
import net.sf.robocode.security.HiddenAccess;
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.DeathEvent;
import robocode.Event;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1 step 8: per-tick event ordering (design &sect;4 item 5, plan step 8).
 * <p>
 * Reconstructed events are freshly constructed and carry no priority (the engine
 * normally sets it when enqueuing), so {@link EventReconstructor} stamps each
 * event with its class {@code DEFAULT_PRIORITY} via
 * {@link HiddenAccess#setEventPriority} and then sorts the tick by natural order
 * &mdash; reproducing the engine dispatch order (time ascending, then priority
 * descending, with the {@code ScannedRobotEvent}/{@code HitRobotEvent}
 * tie-break overrides). {@code DeathEvent} is never stamped: its
 * {@code getPriority()} override already returns -1.
 * <p>
 * The unit tests drive synthetic snapshots and assert the exact emitted order
 * for a multi-type tick and the at-fault {@code HitRobotEvent} tie-break.
 * <p>
 * The integration test is the Phase 1 completion gate: for every hero it
 * reconstructs each turn of a live battle and asserts the reconstructed event
 * list is emitted in the engine's canonical dispatch order, equal to the
 * ground-truth delivered events arranged in that same canonical order. Two
 * documented limitations are honored: the hero's own {@code DeathEvent} is
 * excluded (its delivery is scheduler-dependent and not snapshot-recoverable),
 * and {@code StatusEvent} is excluded because the engine adds it on the host
 * side &mdash; it is therefore absent from the ground-truth delivered-event
 * capture and is oracle-matched separately by {@link StatusEventReconstructionTest}.
 * A surplus {@code ScannedRobotEvent} on a zero-movement turn is tolerated, as
 * the snapshot-pure scan gate over-produces there (see
 * {@link ScanEventReconstructionTest}). Only turns where both robots are alive
 * post-physics and neither side is mid-ram are compared (see
 * {@link CollisionEventsTest}/{@link BulletEventsTest}).
 */
public class EventOrderingTest {

    private static final int BF_WIDTH = 800;
    private static final int BF_HEIGHT = 600;

    private static final String ENEMY = "sample.Fire";
    private static final int ROUNDS = 3;
    private static final String[] HEROES = {
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"
    };

    @BeforeClass
    public static void initEngine() {
        // Constructing any harness initializes the Robocode engine, which installs
        // the HiddenAccess helpers the reconstruction relies on.
        new BattleCaptureHarness("sample.SittingDuck", ENEMY, 1);
    }

    // ---- Unit tests (synthetic snapshots) -------------------------------

    @Test
    public void multipleEventTypesOrderedByDescendingPriority() {
        EventReconstructor r = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        r.resetRound();
        r.seedRoundStart(spawn(Math.PI / 2 - 0.4));

        // Prime: radar unchanged (no sweep) so only the bullet's MOVING state is
        // recorded; the opponent lies off the radar line, so no scan is produced.
        IBulletSnapshot moving = new StubBulletSnapshot()
                .state(BulletState.MOVING).ownerIndex(0).bulletId(7).power(1.5).heading(0).x(700).y(300);
        ITurnSnapshot prime = turn(1, hero(Math.PI / 2 - 0.4), opponent(), moving);
        r.reconstruct(prime, prime);

        // This turn the radar sweeps across the (due-east) opponent -> scan; a
        // hero-owned bullet reaches the wall -> BulletMissed; the living hero gets
        // a Status. Engine dispatch order is by descending priority:
        // StatusEvent(99) > BulletMissedEvent(60) > ScannedRobotEvent(10).
        IBulletSnapshot wallHit = new StubBulletSnapshot()
                .state(BulletState.HIT_WALL).ownerIndex(0).bulletId(7).power(1.5).heading(0).x(800).y(300);
        ITurnSnapshot cur = turn(2, hero(Math.PI / 2 + 0.4), opponent(), wallHit);

        List<Event> events = r.reconstruct(prime, cur).getEvents();

        assertEquals("a status, a bullet-missed and a scan", 3, events.size());
        assertTrue("StatusEvent (priority 99) first", events.get(0) instanceof StatusEvent);
        assertTrue("BulletMissedEvent (priority 60) second", events.get(1) instanceof BulletMissedEvent);
        assertTrue("ScannedRobotEvent (priority 10) last", events.get(2) instanceof ScannedRobotEvent);
        assertTrue("priorities strictly descending",
                events.get(0).getPriority() > events.get(1).getPriority()
                        && events.get(1).getPriority() > events.get(2).getPriority());
    }

    @Test
    public void hitRobotTieBrokenByMyFault() {
        // Two HitRobotEvents at the same time and priority: the engine's
        // HitRobotEvent.compareTo override dispatches the at-fault one first.
        HitRobotEvent notMyFault = new HitRobotEvent(ENEMY, 0.5, 50, false);
        HitRobotEvent myFault = new HitRobotEvent(ENEMY, 0.5, 50, true);
        HiddenAccess.setEventTime(notMyFault, 10);
        HiddenAccess.setEventTime(myFault, 10);
        HiddenAccess.setEventPriority(notMyFault, 40);
        HiddenAccess.setEventPriority(myFault, 40);

        List<Event> events = new ArrayList<Event>(Arrays.asList(notMyFault, myFault));
        Collections.sort(events);

        assertTrue("at-fault HitRobotEvent dispatched first", ((HitRobotEvent) events.get(0)).isMyFault());
        assertFalse("not-at-fault HitRobotEvent dispatched second", ((HitRobotEvent) events.get(1)).isMyFault());
    }

    // ---- Integration test (Phase 1 completion gate) ---------------------

    @Test
    public void fullEventStreamMatchesGroundTruthOrderForAllHeroes() {
        int comparedTurns = 0;
        int multiEventTurns = 0;
        for (String hero : HEROES) {
            int[] counts = verifyOrdering(hero);
            comparedTurns += counts[0];
            multiEventTurns += counts[1];
        }
        assertTrue("no turns were order-compared", comparedTurns > 0);
        assertTrue("no multi-event turns exercised the ordering", multiEventTurns > 0);
    }

    /** @return [order-compared turns, multi-event turns]. */
    private int[] verifyOrdering(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int[] counts = new int[2];
        int round = -1;
        EventReconstructor reconstructor = null;
        ITurnSnapshot prev = null;
        ITurnSnapshot baseline = null;

        for (CapturedTurn ct : captured) {
            if (ct.getRound() != round) {
                round = ct.getRound();
                reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, ROUNDS);
                reconstructor.resetRound();
                ITurnSnapshot start = harness.getRoundStartSnapshot(round);
                assertNotNull("[" + hero + "] r" + round + " missing round-start snapshot", start);
                reconstructor.seedRoundStart(start);
                prev = null;
                baseline = start;
            }

            ITurnSnapshot cur = ct.getSnapshot();
            TickEvents tick = reconstructor.reconstruct(prev == null ? cur : prev, cur);
            boolean moved = heroMoved(baseline, cur);
            prev = cur;
            baseline = cur;

            // Determinism-independent bound: only turns where both robots survive
            // post-physics and neither side is mid-ram are reproducible (death turns
            // mix in the non-deterministic killing blow and the scheduler-dependent
            // hero DeathEvent; a simultaneous double-ram's not-at-fault HitRobotEvent
            // is not snapshot-recoverable).
            if (!bothAlive(cur) || bothRammed(cur)) {
                continue;
            }

            // Reconstructed events in emitted (engine dispatch) order, with the
            // non-comparable events removed.
            List<String> rcOrder = comparableTypes(tick.getEvents());
            // Ground truth arranged in the engine's canonical dispatch order so the
            // comparison is independent of the battle's event insertion order.
            List<String> gtOrder = groundTruthDispatchOrder(ct);

            if (!moved) {
                // Tolerate a surplus reconstructed scan on a zero-movement turn.
                while (count(rcOrder, "ScannedRobotEvent") > count(gtOrder, "ScannedRobotEvent")) {
                    rcOrder.remove("ScannedRobotEvent");
                }
            }

            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            assertEquals(at + "event dispatch order RC=" + rcOrder + " GT=" + gtOrder, gtOrder, rcOrder);

            // Independent guard: the full reconstructed stream (StatusEvent included)
            // is emitted in non-increasing engine priority -- fails fast if the
            // Step 8 priority stamping or sort is ever dropped.
            assertDescendingPriority(at, tick.getEvents());

            counts[0]++;
            if (rcOrder.size() >= 2) {
                counts[1]++;
            }
        }
        return counts;
    }

    // ---- Helpers --------------------------------------------------------

    /** Reconstructed event type names in emitted order, excluding the non-comparable
     *  hero DeathEvent and the host-side StatusEvent. */
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
    private static List<String> groundTruthDispatchOrder(CapturedTurn ct) {
        List<Event> gt = new ArrayList<Event>();
        for (Event e : ct.getHeroEvents()) {
            if (e instanceof DeathEvent || e instanceof StatusEvent) {
                continue;
            }
            HiddenAccess.setEventTime(e, ct.getTurn());
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

    private static void assertDescendingPriority(String at, List<Event> events) {
        for (int i = 1; i < events.size(); i++) {
            int hi = expectedPriority(events.get(i - 1));
            int lo = expectedPriority(events.get(i));
            assertTrue(at + "events out of dispatch order at " + i + ": "
                    + events.get(i - 1).getClass().getSimpleName() + "(" + hi + ") before "
                    + events.get(i).getClass().getSimpleName() + "(" + lo + ")", hi >= lo);
        }
    }

    private static int expectedPriority(Event e) {
        if (e instanceof DeathEvent) {
            return -1;
        }
        return defaultPriority(e);
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

    private static boolean heroMoved(ITurnSnapshot baseline, ITurnSnapshot cur) {
        IRobotSnapshot a = baseline.getRobots()[0];
        IRobotSnapshot b = cur.getRobots()[0];
        return a.getBodyHeading() != b.getBodyHeading()
                || a.getGunHeading() != b.getGunHeading()
                || a.getRadarHeading() != b.getRadarHeading()
                || a.getX() != b.getX()
                || a.getY() != b.getY();
    }

    private static IRobotSnapshot hero(double radarHeading) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(80)
                .bodyHeading(0).gunHeading(0).radarHeading(radarHeading).x(400).y(300);
    }

    private static IRobotSnapshot opponent() {
        return new StubRobotSnapshot()
                .name(ENEMY).robotIndex(1).energy(55).bodyHeading(1.0).velocity(2).x(600).y(300);
    }

    private static ITurnSnapshot spawn(double radarHeading) {
        return new StubTurnSnapshot(0, 0, new IRobotSnapshot[] { hero(radarHeading), opponent() });
    }

    private static ITurnSnapshot turn(int t, IRobotSnapshot h, IRobotSnapshot o, IBulletSnapshot... bullets) {
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { h, o }, bullets);
    }
}
