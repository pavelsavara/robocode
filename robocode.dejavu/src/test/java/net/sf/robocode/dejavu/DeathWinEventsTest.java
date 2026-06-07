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
import robocode.DeathEvent;
import robocode.Event;
import robocode.RobotDeathEvent;
import robocode.WinEvent;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1 step 7: death / win events &mdash; {@link RobotDeathEvent},
 * {@link DeathEvent} and {@link WinEvent} (design &sect;7, plan step 7).
 * <p>
 * The engine resolves all three within a single turn: a robot whose energy
 * reaches zero in {@code updateRobots} is killed (receiving its own
 * {@code DeathEvent}); {@code handleDeadRobots} then publishes a
 * {@code RobotDeathEvent} about it to every still-living robot; and once only
 * one team remains {@code shutdownTurn} awards the survivor a {@code WinEvent}.
 * <p>
 * The {@code RobotDeathEvent} and {@code WinEvent} delivered to the surviving
 * hero map deterministically to the snapshot tick on which the opponent first
 * appears dead. The hero's own {@code DeathEvent} does not: the engine adds it
 * to the dying robot's queue, but whether that robot's thread reads it out
 * before being stopped is scheduler-dependent and not observable from snapshots
 * (e.g. {@code sample.SittingDuck} and {@code sample.Crazy} receive it on their
 * death tick, while a busy {@code sample.Fire} may not). A simultaneous
 * double-death delivers nothing &mdash; the round ends with no team remaining.
 * <p>
 * The unit tests drive {@link EventReconstructor#reconstruct} with synthetic
 * snapshots: an opponent dying yields {@code WinEvent} + {@code RobotDeathEvent}
 * (in that engine-priority order); the hero dying (opponent alive) yields a
 * single {@code DeathEvent} and nothing afterwards; an already-dead opponent
 * does not re-fire; and a simultaneous double-death yields nothing.
 * <p>
 * The integration test reconstructs the death/win stream from the captured
 * snapshots of live battles and asserts the {@code RobotDeathEvent} and
 * {@code WinEvent} stream matches the engine ground truth turn by turn for every
 * hero over all rounds; the non-deterministic {@code DeathEvent} is exercised
 * (it must fire on the captured losing rounds) but not oracle-matched per turn.
 */
public class DeathWinEventsTest {

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

    // ---- Unit tests -----------------------------------------------------

    @Test
    public void opponentDeathEmitsWinThenRobotDeath() {
        ITurnSnapshot start = turn(10, hero(50, RobotState.ACTIVE), opp(20, RobotState.ACTIVE));
        ITurnSnapshot death = turn(11, hero(50, RobotState.ACTIVE), opp(0, RobotState.DEAD));

        List<Event> events = deathWinEvents(reconstruct(start, start, death));

        assertEquals("two events (win + robot death)", 2, events.size());
        assertTrue("WinEvent first", events.get(0) instanceof WinEvent);
        assertTrue("RobotDeathEvent second", events.get(1) instanceof RobotDeathEvent);
        assertEquals("dead robot name", ENEMY, ((RobotDeathEvent) events.get(1)).getName());
    }

    @Test
    public void heroDeathEmitsDeathEvent() {
        ITurnSnapshot start = turn(10, hero(20, RobotState.ACTIVE), opp(50, RobotState.ACTIVE));
        ITurnSnapshot death = turn(11, hero(0, RobotState.DEAD), opp(50, RobotState.ACTIVE));

        List<Event> events = deathWinEvents(reconstruct(start, start, death));

        assertEquals("one event (death)", 1, events.size());
        assertTrue("DeathEvent", events.get(0) instanceof DeathEvent);
    }

    @Test
    public void heroDeathSuppressesSubsequentEvents() {
        ITurnSnapshot start = turn(10, hero(20, RobotState.ACTIVE), opp(50, RobotState.ACTIVE));
        ITurnSnapshot death = turn(11, hero(0, RobotState.DEAD), opp(50, RobotState.ACTIVE));
        // After the hero is dead the opponent later dies too, but the dead hero's
        // thread no longer reads out events, so nothing is delivered.
        ITurnSnapshot after = turn(12, hero(0, RobotState.DEAD), opp(0, RobotState.DEAD));

        EventReconstructor r = seeded(start);
        deathWinEvents(filter(r.reconstruct(start, death).getEvents()));
        List<Event> events = deathWinEvents(r.reconstruct(death, after).getEvents());

        assertTrue("no events after hero death", events.isEmpty());
    }

    @Test
    public void alreadyDeadOpponentDoesNotRefire() {
        ITurnSnapshot start = turn(10, hero(50, RobotState.ACTIVE), opp(0, RobotState.DEAD));
        ITurnSnapshot next = turn(11, hero(50, RobotState.ACTIVE), opp(0, RobotState.DEAD));

        List<Event> events = deathWinEvents(reconstruct(start, start, next));

        assertTrue("no death/win events for an already-dead opponent", events.isEmpty());
    }

    @Test
    public void simultaneousDeathEmitsNothing() {
        // When the hero and opponent die on the same turn the round ends with no
        // team remaining: no win is awarded, the dead hero receives no
        // RobotDeathEvent, and its own DeathEvent is not read out.
        ITurnSnapshot start = turn(10, hero(5, RobotState.ACTIVE), opp(5, RobotState.ACTIVE));
        ITurnSnapshot death = turn(11, hero(0, RobotState.DEAD), opp(0, RobotState.DEAD));

        List<Event> events = deathWinEvents(reconstruct(start, start, death));

        assertTrue("no events on simultaneous double-death", events.isEmpty());
    }

    // ---- Integration test (live battles) --------------------------------

    @Test
    public void winAndRobotDeathEventsMatchGroundTruthForAllHeroes() {
        int robotDeaths = 0;
        int wins = 0;
        int reconstructedDeaths = 0;
        for (String hero : HEROES) {
            int[] counts = verifyDeathWinEvents(hero);
            robotDeaths += counts[0];
            wins += counts[1];
            reconstructedDeaths += counts[2];
        }
        assertTrue("no RobotDeathEvent exercised", robotDeaths > 0);
        assertTrue("no WinEvent exercised", wins > 0);
        // The hero's own DeathEvent cannot be oracle-matched per turn (the engine's
        // delivery of a robot's death to itself is scheduler-dependent and not
        // observable from snapshots), but the reconstruction path must still fire
        // on the captured losing rounds.
        assertTrue("no DeathEvent reconstructed", reconstructedDeaths > 0);
    }

    /**
     * Hero {@code DeathEvent}: the ordering-sensitive case (design &sect;7).
     * <p>
     * The engine adds the hero's own {@code DeathEvent} to its queue on the tick
     * its energy reaches zero, but whether the dying robot's thread reads it out
     * before being stopped is scheduler-dependent and <em>not</em> observable
     * from snapshots. This test confronts that ordering problem directly: it
     * pins down every recoverable property and tolerates only the one
     * unrecoverable degree of freedom (whether the engine delivered it).
     * <p>
     * For every captured round it asserts the reconstruction emits the hero
     * {@code DeathEvent} deterministically &mdash; exactly once, on the snapshot
     * tick the hero first appears dead, with that tick stamped as its event time
     * &mdash; for a single hero death; never on a round the hero survives; and
     * never on a simultaneous double-death (no team remains, so nothing is read
     * out). Where the engine <em>did</em> deliver the {@code DeathEvent} to the
     * hero, the recoverable part &mdash; the tick it fell on &mdash; must equal
     * the reconstructed death tick. The suite is required to exercise both a real
     * single-death round and at least one engine-delivered {@code DeathEvent} so
     * the ordering tolerance is genuinely tested, not vacuous.
     */
    @Test
    public void heroDeathEventReconstructsOnDeathTickForAllHeroes() {
        int singleDeathRounds = 0;
        int groundTruthDelivered = 0;
        for (String hero : HEROES) {
            int[] counts = verifyHeroDeathEvent(hero);
            singleDeathRounds += counts[0];
            groundTruthDelivered += counts[1];
        }
        assertTrue("no single-hero-death round exercised", singleDeathRounds > 0);
        // The ordering problem is only exercised when the engine actually delivered
        // the hero its own DeathEvent on at least one losing round (e.g.
        // sample.SittingDuck reliably receives it).
        assertTrue("ground truth never delivered the hero DeathEvent (ordering case not exercised)",
                groundTruthDelivered > 0);
    }

    /**
     * @return counts of [single-hero-death rounds verified, rounds where the
     *         engine delivered the hero DeathEvent].
     */
    private int[] verifyHeroDeathEvent(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int[] totals = new int[2];
        int round = -1;
        EventReconstructor reconstructor = null;
        ITurnSnapshot prev = null;

        // Per-round accumulators.
        List<Long> rcDeathTurns = new ArrayList<Long>();
        List<Long> gtDeathTurns = new ArrayList<Long>();
        long heroDeathTurn = -1;
        boolean doubleDeath = false;
        boolean prevHeroDead = false;
        boolean prevOppDead = false;

        for (int i = 0; i <= captured.size(); i++) {
            boolean boundary = i == captured.size() || captured.get(i).getRound() != round;
            if (boundary && round != -1) {
                finalizeDeathRound(hero, round, rcDeathTurns, gtDeathTurns, heroDeathTurn,
                        doubleDeath, totals);
            }
            if (i == captured.size()) {
                break;
            }

            CapturedTurn ct = captured.get(i);
            if (ct.getRound() != round) {
                round = ct.getRound();
                reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, ROUNDS);
                reconstructor.resetRound();
                ITurnSnapshot start = harness.getRoundStartSnapshot(round);
                assertNotNull("[" + hero + "] r" + round + " missing round-start snapshot", start);
                reconstructor.seedRoundStart(start);
                prev = null;
                rcDeathTurns = new ArrayList<Long>();
                gtDeathTurns = new ArrayList<Long>();
                heroDeathTurn = -1;
                doubleDeath = false;
                prevHeroDead = false;
                prevOppDead = false;
            }

            ITurnSnapshot cur = ct.getSnapshot();
            List<Event> rc = reconstructor.reconstruct(prev == null ? cur : prev, cur).getEvents();
            prev = cur;

            for (Event e : rc) {
                if (e instanceof DeathEvent) {
                    rcDeathTurns.add(e.getTime());
                }
            }
            for (Event e : ct.getHeroEvents()) {
                if (e instanceof DeathEvent) {
                    gtDeathTurns.add(e.getTime());
                }
            }

            IRobotSnapshot me = cur.getRobots()[0];
            IRobotSnapshot opp = cur.getRobots()[1];
            boolean heroDeadNow = me.getState().isDead();
            boolean oppDeadNow = opp.getState().isDead();
            if (heroDeadNow && !prevHeroDead) {
                heroDeathTurn = ct.getTurn();
                // A double-death is the hero and opponent first appearing dead on
                // the same tick.
                doubleDeath = oppDeadNow && !prevOppDead;
            }
            prevHeroDead = heroDeadNow;
            prevOppDead = oppDeadNow;
        }
        return totals;
    }

    private static void finalizeDeathRound(String hero, int round, List<Long> rcDeathTurns,
            List<Long> gtDeathTurns, long heroDeathTurn, boolean doubleDeath, int[] totals) {
        String at = "[" + hero + "] r" + round + " ";

        // The reconstruction never emits more than one hero DeathEvent per round.
        assertTrue(at + "more than one reconstructed DeathEvent: " + rcDeathTurns,
                rcDeathTurns.size() <= 1);

        if (heroDeathTurn < 0) {
            // The hero survived the round: no DeathEvent on either side.
            assertTrue(at + "DeathEvent reconstructed though the hero never died",
                    rcDeathTurns.isEmpty());
            assertTrue(at + "ground truth delivered a DeathEvent though the hero never died",
                    gtDeathTurns.isEmpty());
            return;
        }

        if (doubleDeath) {
            // Simultaneous double-death: the round ends with no team remaining, so
            // the dying hero's own DeathEvent is never read out.
            assertTrue(at + "DeathEvent reconstructed on a simultaneous double-death: " + rcDeathTurns,
                    rcDeathTurns.isEmpty());
            return;
        }

        // Single hero death: the reconstruction deterministically emits exactly one
        // DeathEvent, stamped on the snapshot tick the hero first appeared dead.
        assertEquals(at + "expected exactly one reconstructed DeathEvent on the death tick",
                1, rcDeathTurns.size());
        assertEquals(at + "reconstructed DeathEvent not on the hero death tick",
                heroDeathTurn, (long) rcDeathTurns.get(0));
        totals[0]++;

        // Ordering problem: whether the engine read the hero's DeathEvent out
        // before stopping its thread is scheduler-dependent and not snapshot-
        // recoverable. When it did deliver it, the recoverable part -- the tick it
        // fell on -- must match the reconstructed death tick.
        for (long gt : gtDeathTurns) {
            assertEquals(at + "ground-truth DeathEvent not on the reconstructed death tick",
                    heroDeathTurn, gt);
        }
        if (!gtDeathTurns.isEmpty()) {
            totals[1]++;
        }
    }

    /**
     * @return counts of [oracle-matched RobotDeath, oracle-matched Win,
     *         reconstructed Death].
     */
    private int[] verifyDeathWinEvents(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int[] counts = new int[3];
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
            List<Event> allReconstructed = reconstructor.reconstruct(
                    prev == null ? cur : prev, cur).getEvents();
            prev = cur;

            counts[2] += countDeaths(allReconstructed);

            // RobotDeathEvent (opponent died) and WinEvent (hero last standing) are
            // delivered to the living hero on the turn the opponent first appears
            // dead, so they oracle-match exactly turn by turn.
            List<Event> reconstructed = winRobotDeathEvents(allReconstructed);
            List<Event> groundTruth = winRobotDeathEvents(Arrays.asList(ct.getHeroEvents()));
            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            matchWinRobotDeathEvents(at, groundTruth, reconstructed, counts);
        }
        return counts;
    }

    private static void matchWinRobotDeathEvents(String at, List<Event> groundTruth,
            List<Event> reconstructed, int[] counts) {
        assertEquals(at + "win/robot-death event count, RC=" + describeAll(reconstructed)
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

            if (expected instanceof RobotDeathEvent) {
                counts[0]++;
            } else if (expected instanceof WinEvent) {
                counts[1]++;
            }
        }
    }

    private static int countDeaths(List<Event> events) {
        int n = 0;
        for (Event e : events) {
            if (e instanceof DeathEvent) {
                n++;
            }
        }
        return n;
    }

    private static boolean eventsEqual(Event a, Event b) {
        if (a.getClass() != b.getClass()) {
            return false;
        }
        if (a instanceof RobotDeathEvent) {
            return ((RobotDeathEvent) a).getName().equals(((RobotDeathEvent) b).getName());
        }
        // WinEvent carries no distinguishing fields.
        return true;
    }

    // ---- Helpers --------------------------------------------------------

    private static EventReconstructor seeded(ITurnSnapshot start) {
        EventReconstructor r = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, ROUNDS);
        r.resetRound();
        r.seedRoundStart(start);
        return r;
    }

    /** Reconstruct one turn from a fresh reconstructor seeded at {@code start}. */
    private static List<Event> reconstruct(ITurnSnapshot start, ITurnSnapshot prev, ITurnSnapshot cur) {
        return filter(seeded(start).reconstruct(prev, cur).getEvents());
    }

    private static List<Event> deathWinEvents(List<Event> events) {
        return filter(events);
    }

    private static List<Event> filter(List<Event> events) {
        List<Event> out = new ArrayList<Event>();
        for (Event e : events) {
            if (e instanceof RobotDeathEvent || e instanceof DeathEvent || e instanceof WinEvent) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<Event> winRobotDeathEvents(List<Event> events) {
        List<Event> out = new ArrayList<Event>();
        for (Event e : events) {
            if (e instanceof RobotDeathEvent || e instanceof WinEvent) {
                out.add(e);
            }
        }
        return out;
    }

    private static String describe(Event e) {
        if (e instanceof RobotDeathEvent) {
            return "RobotDeathEvent name=" + ((RobotDeathEvent) e).getName();
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

    private static IRobotSnapshot hero(double energy, RobotState state) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(energy).state(state).x(400).y(300);
    }

    private static IRobotSnapshot opp(double energy, RobotState state) {
        return new StubRobotSnapshot()
                .name(ENEMY).robotIndex(1).energy(energy).state(state).x(600).y(300);
    }

    private static ITurnSnapshot turn(int t, IRobotSnapshot hero, IRobotSnapshot opponent) {
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { hero, opponent }, new IBulletSnapshot[0]);
    }
}
