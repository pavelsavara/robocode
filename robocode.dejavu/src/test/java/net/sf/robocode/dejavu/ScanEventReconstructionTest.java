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
import net.sf.robocode.dejavu.model.TickEvents;
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.Event;
import robocode.ScannedRobotEvent;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1 step 2: {@link ScannedRobotEvent} reconstruction (design &sect;5, step 2).
 * <p>
 * The unit tests drive {@link EventReconstructor} with synthetic snapshots and
 * verify the radar sweep gate: a scan is produced only when the hero moved and
 * the swept arc covers the opponent's bounding box, carrying the engine's
 * {@code bearing}/{@code distance}/{@code energy}/{@code heading}/{@code velocity};
 * sweeping away from the opponent (or never seeding the turn-1 radar heading)
 * yields none.
 * <p>
 * The integration test reconstructs the {@code ScannedRobotEvent} stream from
 * the captured turn snapshots of live battles and asserts it matches the engine
 * ground-truth {@code onScannedRobot} stream 1:1 (turn alignment, no missed or
 * spurious scans, every field within {@link #EPSILON}) for a spread of sample
 * heroes &mdash; rotating-radar bots reach full coverage while the passive
 * {@code sample.SittingDuck} produces none.
 */
public class ScanEventReconstructionTest {

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
        // installs the HiddenAccess helpers the reconstruction relies on. The
        // battle itself is not run here.
        new BattleCaptureHarness(SITTING_DUCK, ENEMY, 1);
    }

    // ---- Unit tests (synthetic snapshots) -------------------------------

    @Test
    public void scanWhenSweepCoversOpponent() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();
        reconstructor.seedRoundStart(spawn(0.0, Math.PI / 2 - 0.4, 400, 300));

        // Hero at (400,300) heading north; opponent due east at (600,300). The
        // radar sweeps across east, so the opponent is inside the swept arc.
        IRobotSnapshot hero = hero(0.0, Math.PI / 2 + 0.4, 400, 300);
        IRobotSnapshot opponent = new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1)
                .energy(55).bodyHeading(1.0).velocity(4).x(600).y(300);
        ITurnSnapshot cur = new StubTurnSnapshot(0, 1, new IRobotSnapshot[] { hero, opponent });

        ScannedRobotEvent scan = onlyScan(reconstructor.reconstruct(cur, cur));
        assertNotNull("expected a scan when the sweep covers the opponent", scan);
        assertEquals("name", "sample.Fire", scan.getName());
        assertEquals("energy", 55, scan.getEnergy(), EPSILON);
        assertEquals("bearing", Math.PI / 2, scan.getBearingRadians(), EPSILON);
        assertEquals("distance", 200, scan.getDistance(), EPSILON);
        assertEquals("heading", 1.0, scan.getHeadingRadians(), EPSILON);
        assertEquals("velocity", 4, scan.getVelocity(), EPSILON);
        assertFalse("sentry", scan.isSentryRobot());
        assertEquals("event time", 1, scan.getTime());
    }

    @Test
    public void noScanWhenSweepMissesOpponent() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();
        reconstructor.seedRoundStart(spawn(0.0, 0.0, 400, 300));

        // Radar sweeps from north (0) to ~57 deg east; opponent is due south,
        // outside the swept arc.
        IRobotSnapshot hero = hero(0.0, 1.0, 400, 300);
        IRobotSnapshot opponent = new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(55).x(400).y(100);
        ITurnSnapshot cur = new StubTurnSnapshot(0, 1, new IRobotSnapshot[] { hero, opponent });

        assertNull("no scan when the sweep misses the opponent",
                onlyScan(reconstructor.reconstruct(cur, cur)));
    }

    @Test
    public void scanHandlesWrapAroundSweep() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();
        // Previous radar just west of north (6.0 rad), current just east (0.5
        // rad): the sweep wraps across 0/2*PI through north.
        reconstructor.seedRoundStart(spawn(0.0, 6.0, 400, 300));

        IRobotSnapshot hero = hero(0.0, 0.5, 400, 300);
        IRobotSnapshot opponent = new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(30).x(400).y(500);
        ITurnSnapshot cur = new StubTurnSnapshot(0, 1, new IRobotSnapshot[] { hero, opponent });

        ScannedRobotEvent scan = onlyScan(reconstructor.reconstruct(cur, cur));
        assertNotNull("expected a scan when the wrap-around sweep covers the opponent", scan);
        assertEquals("bearing", 0.0, scan.getBearingRadians(), EPSILON);
        assertEquals("distance", 200, scan.getDistance(), EPSILON);
    }

    @Test
    public void firstTickSweepRequiresSeededRadarHeading() {
        // Without seeding, the turn-1 radar baseline is unknown: no scan.
        EventReconstructor unseeded = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        unseeded.resetRound();

        IRobotSnapshot hero = hero(0.0, Math.PI / 2 + 0.4, 400, 300);
        IRobotSnapshot opponent = new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(55).x(600).y(300);
        ITurnSnapshot cur = new StubTurnSnapshot(0, 1, new IRobotSnapshot[] { hero, opponent });

        assertNull("turn-1 scan must not be reconstructed without a seeded radar heading",
                onlyScan(unseeded.reconstruct(cur, cur)));

        // With the spawn radar heading seeded, the turn-1 sweep is reconstructed.
        EventReconstructor seeded = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        seeded.resetRound();
        seeded.seedRoundStart(spawn(0.0, Math.PI / 2 - 0.4, 400, 300));

        assertNotNull("turn-1 scan should be reconstructed from the seeded radar heading",
                onlyScan(seeded.reconstruct(cur, cur)));
    }

    @Test
    public void stationaryHeroScansAlongTheRadarLine() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();
        reconstructor.seedRoundStart(spawn(0.0, Math.PI / 2, 400, 300));

        // Identical kinematics to the seed: the engine's movement scan gate is
        // not set, but every robot is assumed to call scan(), collapsing to a
        // radial-line scan along the (unchanged) radar heading. The opponent due
        // east lies on that line, so a scan is still produced.
        IRobotSnapshot hero = hero(0.0, Math.PI / 2, 400, 300);
        IRobotSnapshot opponent = new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1)
                .energy(55).bodyHeading(1.0).velocity(2).x(600).y(300);
        ITurnSnapshot cur = new StubTurnSnapshot(0, 1, new IRobotSnapshot[] { hero, opponent });

        ScannedRobotEvent scan = onlyScan(reconstructor.reconstruct(cur, cur));
        assertNotNull("a stationary hero still scans along its radar line", scan);
        assertEquals("bearing", Math.PI / 2, scan.getBearingRadians(), EPSILON);
        assertEquals("distance", 200, scan.getDistance(), EPSILON);
    }

    @Test
    public void stationaryHeroDoesNotScanOffTheRadarLine() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();
        reconstructor.seedRoundStart(spawn(0.0, Math.PI / 2, 400, 300));

        // Stationary radar pointing east, opponent due south: off the radial
        // line, so even an assumed scan() produces nothing.
        IRobotSnapshot hero = hero(0.0, Math.PI / 2, 400, 300);
        IRobotSnapshot opponent = new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(55).x(400).y(100);
        ITurnSnapshot cur = new StubTurnSnapshot(0, 1, new IRobotSnapshot[] { hero, opponent });

        assertNull("a stationary radar line missing the opponent produces no scan",
                onlyScan(reconstructor.reconstruct(cur, cur)));
    }

    @Test
    public void noScanWhenOpponentIsDead() {
        EventReconstructor reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, 1);
        reconstructor.resetRound();
        reconstructor.seedRoundStart(spawn(0.0, Math.PI / 2 - 0.4, 400, 300));

        IRobotSnapshot hero = hero(0.0, Math.PI / 2 + 0.4, 400, 300);
        IRobotSnapshot deadOpponent = new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).state(RobotState.DEAD).x(600).y(300);
        ITurnSnapshot cur = new StubTurnSnapshot(0, 1, new IRobotSnapshot[] { hero, deadOpponent });

        assertNull("a dead opponent is not scanned",
                onlyScan(reconstructor.reconstruct(cur, cur)));
    }

    // ---- Integration test (live ground truth) ---------------------------

    @Test
    public void reconstructedScansMatchGroundTruthForAllHeroes() {
        for (String hero : HEROES) {
            verifyHero(hero);
        }
    }

    private void verifyHero(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int matchedScans = 0;
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

            ScannedRobotEvent reconstructed = onlyScan(tick);
            ScannedRobotEvent groundTruth = onlyGroundTruthScan(ct.getHeroEvents());
            boolean moved = heroMoved(baseline, cur);
            prev = cur;
            baseline = cur;

            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            if (groundTruth != null) {
                // Every scan the engine delivered must be reconstructed exactly.
                assertNotNull(at + "missed a scan the engine delivered", reconstructed);
                assertScanEquals(at, groundTruth, reconstructed);
                matchedScans++;
            } else if (reconstructed != null) {
                // A scan with no ground truth is only acceptable on a zero-movement
                // turn: there the engine scans solely via a manual scan() whose
                // per-turn cadence leaves no trace in the snapshots, so we assume
                // every robot scans and necessarily over-produce. On a moving turn
                // the snapshot fully determines the sweep, so this is a real defect.
                assertFalse(at + "reconstructed a spurious scan on a moving turn", moved);
            }
        }

        if (SITTING_DUCK.equals(hero)) {
            assertEquals("[" + hero + "] a passive radar delivers no engine scans", 0, matchedScans);
        } else {
            assertTrue("[" + hero + "] no scans matched", matchedScans > 0);
        }
        System.out.printf("[Phase1.2] %-18s vs %s: matched %d scans%n", hero, ENEMY, matchedScans);
    }

    private void assertScanEquals(String at, ScannedRobotEvent expected, ScannedRobotEvent actual) {
        assertEquals(at + "name", expected.getName(), actual.getName());
        assertEquals(at + "energy", expected.getEnergy(), actual.getEnergy(), EPSILON);
        assertEquals(at + "bearing", expected.getBearingRadians(), actual.getBearingRadians(), EPSILON);
        assertEquals(at + "distance", expected.getDistance(), actual.getDistance(), EPSILON);
        assertEquals(at + "heading", expected.getHeadingRadians(), actual.getHeadingRadians(), EPSILON);
        assertEquals(at + "velocity", expected.getVelocity(), actual.getVelocity(), EPSILON);
        assertEquals(at + "sentry", expected.isSentryRobot(), actual.isSentryRobot());
    }

    // ---- Helpers --------------------------------------------------------

    private static boolean heroMoved(ITurnSnapshot baseline, ITurnSnapshot cur) {
        IRobotSnapshot a = baseline.getRobots()[0];
        IRobotSnapshot b = cur.getRobots()[0];
        return a.getBodyHeading() != b.getBodyHeading()
                || a.getGunHeading() != b.getGunHeading()
                || a.getRadarHeading() != b.getRadarHeading()
                || a.getX() != b.getX()
                || a.getY() != b.getY();
    }

    private static IRobotSnapshot hero(double bodyHeading, double radarHeading, double x, double y) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0)
                .bodyHeading(bodyHeading).gunHeading(bodyHeading).radarHeading(radarHeading)
                .x(x).y(y);
    }

    private static ITurnSnapshot spawn(double bodyHeading, double radarHeading, double x, double y) {
        IRobotSnapshot me = hero(bodyHeading, radarHeading, x, y);
        IRobotSnapshot opponent = new StubRobotSnapshot().name("sample.Fire").robotIndex(1);
        return new StubTurnSnapshot(0, 0, new IRobotSnapshot[] { me, opponent });
    }

    private ScannedRobotEvent onlyScan(TickEvents tick) {
        ScannedRobotEvent found = null;
        for (Event e : tick.getEvents()) {
            if (e instanceof ScannedRobotEvent) {
                assertNull("more than one ScannedRobotEvent reconstructed", found);
                found = (ScannedRobotEvent) e;
            }
        }
        return found;
    }

    private ScannedRobotEvent onlyGroundTruthScan(Event[] events) {
        ScannedRobotEvent found = null;
        for (Event e : events) {
            if (e instanceof ScannedRobotEvent) {
                assertNull("ground truth carried more than one ScannedRobotEvent", found);
                found = (ScannedRobotEvent) e;
            }
        }
        return found;
    }
}
