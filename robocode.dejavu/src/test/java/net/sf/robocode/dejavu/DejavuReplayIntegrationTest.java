/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu;

import net.sf.robocode.dejavu.core.Reconstructor;
import net.sf.robocode.dejavu.harness.BattleCaptureHarness;
import net.sf.robocode.dejavu.harness.CapturedTurn;
import net.sf.robocode.dejavu.harness.FidelityComparator;
import net.sf.robocode.dejavu.harness.FidelityReport;
import net.sf.robocode.dejavu.replay.ReplayRobotPeer;
import net.sf.robocode.peer.IExecCommands;
import org.junit.Test;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.CustomEvent;
import robocode.DeathEvent;
import robocode.Event;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.SkippedTurnEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.robotinterfaces.IAdvancedEvents;
import robocode.robotinterfaces.IAdvancedRobot;
import robocode.robotinterfaces.IBasicEvents;
import robocode.robotinterfaces.peer.IBasicRobotPeer;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Phase 3 end-to-end façade gate (design &sect;8.2, plan Gate 3).
 * <p>
 * Where {@link DejavuFidelityTest} drives the {@link Reconstructor} directly,
 * this test exercises the shipped public entry point: it captures a live battle
 * for each gold sample hero, replays the captured battle-started / round-started
 * / turn-ended events through a {@link Dejavu} instance wired to a recording
 * {@link IAdvancedRobot} and a {@link ReplayRobotPeer}, and asserts that
 * <ol>
 *   <li>the reconstruction the façade delivers each turn scores at full fidelity
 *       (rate {@code 1.0}, no mismatches) against the engine ground truth;</li>
 *   <li>every reconstructed event is actually delivered to the robot's
 *       listeners, in order;</li>
 *   <li>the fake peer's getters are back-filled from the hero's ground-truth
 *       snapshot before each tick is delivered, and its commands are recorded.</li>
 * </ol>
 * Combined with the per-step reconstruction gate this proves the façade layer
 * (drivers, peer state-fill, wiring) forwards the validated reconstruction
 * faithfully, with no production TODO left on the path.
 */
public class DejavuReplayIntegrationTest {

    private static final String ENEMY = "sample.Fire";
    private static final int ROUNDS = 3;
    private static final double BF_WIDTH = 800;
    private static final double BF_HEIGHT = 600;
    private static final String[] GOLD_HEROES = {
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"
    };

    @Test
    public void deliversReconstructionThroughFacadeAtFullFidelityForGoldHeroes() {
        for (String hero : GOLD_HEROES) {
            driveHeroThroughFacade(hero);
        }
    }

    private void driveHeroThroughFacade(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());
        assertNotNull("[" + hero + "] no battle-started event captured",
                harness.getBattleStartedEvent());

        RecordingRobot robot = new RecordingRobot();
        ReplayRobotPeer peer = new ReplayRobotPeer();
        Dejavu dejavu = new Dejavu(0, robot, peer);

        FidelityReport report = new FidelityReport();
        FidelityComparator comparator = new FidelityComparator(report);

        dejavu.onBattleStarted(harness.getBattleStartedEvent());

        int round = -1;
        ITurnSnapshot prevSnap = null;
        int comparedTicks = 0;

        for (CapturedTurn ct : captured) {
            if (ct.getRound() != round) {
                round = ct.getRound();
                ITurnSnapshot start = harness.getRoundStartSnapshot(round);
                assertNotNull("[" + hero + "] r" + round + " missing round-start snapshot", start);
                dejavu.onRoundStarted(new RoundStartedEvent(start, round, null));
                prevSnap = start;
            }

            ITurnSnapshot cur = ct.getSnapshot();
            IExecCommands curCmd = ct.getHeroCommands();

            robot.delivered.clear();
            int execBefore = peer.getExecuteCount();
            dejavu.onTurnEnded(new TurnEndedEvent(cur));
            Reconstructor.TickResult result = dejavu.getLastResult();

            if (result != null) {
                // (2) every reconstructed (basic-dispatchable) event reached a listener, in order.
                List<Event> expected = dispatchable(result.events.getEvents());
                assertEquals("[" + hero + "] r" + round + " t" + ct.getTurn()
                        + " delivered events", expected, robot.delivered);

                // (3) the peer was state-filled from cur's hero snapshot and its commands recorded.
                assertSame("[" + hero + "] r" + round + " t" + ct.getTurn()
                        + " peer loaded from cur hero", cur.getRobots()[0], peer.getLastLoadedHero());
                assertEquals("[" + hero + "] r" + round + " t" + ct.getTurn()
                        + " peer execute()", execBefore + 1, peer.getExecuteCount());

                // (1) the delivered reconstruction scores at full fidelity vs ground truth.
                comparator.compareEvents(round, ct.getTurn(), result.events,
                        ct.getHeroEvents(), prevSnap, cur);
                comparator.compareCommands(round, ct.getTurn(), result.commands,
                        prevSnap, cur, curCmd);
                comparedTicks++;
            }

            prevSnap = cur;
        }

        assertTrue("[" + hero + "] no ticks compared through the façade", comparedTicks > 0);
        assertEquals("[" + hero + "] façade fidelity rate", 1.0, report.overallRate(), 0.0);
        assertTrue("[" + hero + "] façade mismatches: " + report.getMismatches(),
                report.getMismatches().isEmpty());
    }

    /**
     * The subsequence of {@code events} that {@link net.sf.robocode.dejavu.replay.RobotReplayDriver}
     * routes to the basic listener (mirroring its dispatch table); advanced
     * events (e.g. {@link SkippedTurnEvent}) are not yet reconstructed-and-delivered
     * and so are excluded from the delivery equality check.
     */
    private static List<Event> dispatchable(List<Event> events) {
        List<Event> out = new ArrayList<Event>(events.size());
        for (Event e : events) {
            if (e instanceof StatusEvent || e instanceof ScannedRobotEvent
                    || e instanceof HitByBulletEvent || e instanceof BulletHitEvent
                    || e instanceof BulletHitBulletEvent || e instanceof BulletMissedEvent
                    || e instanceof HitRobotEvent || e instanceof HitWallEvent
                    || e instanceof RobotDeathEvent || e instanceof DeathEvent
                    || e instanceof WinEvent) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * A recording {@link IAdvancedRobot} test double: its listeners append every
     * delivered event (by reference) so the façade's delivery can be checked
     * against the reconstruction.
     */
    private static final class RecordingRobot implements IAdvancedRobot, IBasicEvents, IAdvancedEvents {

        final List<Event> delivered = new ArrayList<Event>();

        @Override
        public Runnable getRobotRunnable() {
            return null;
        }

        @Override
        public IBasicEvents getBasicEventListener() {
            return this;
        }

        @Override
        public IAdvancedEvents getAdvancedEventListener() {
            return this;
        }

        @Override
        public void setOut(PrintStream out) {
            // no-op
        }

        @Override
        public void setPeer(IBasicRobotPeer peer) {
            // no-op
        }

        @Override
        public void onStatus(StatusEvent event) {
            delivered.add(event);
        }

        @Override
        public void onScannedRobot(ScannedRobotEvent event) {
            delivered.add(event);
        }

        @Override
        public void onHitByBullet(HitByBulletEvent event) {
            delivered.add(event);
        }

        @Override
        public void onBulletHit(BulletHitEvent event) {
            delivered.add(event);
        }

        @Override
        public void onBulletHitBullet(BulletHitBulletEvent event) {
            delivered.add(event);
        }

        @Override
        public void onBulletMissed(BulletMissedEvent event) {
            delivered.add(event);
        }

        @Override
        public void onHitRobot(HitRobotEvent event) {
            delivered.add(event);
        }

        @Override
        public void onHitWall(HitWallEvent event) {
            delivered.add(event);
        }

        @Override
        public void onRobotDeath(RobotDeathEvent event) {
            delivered.add(event);
        }

        @Override
        public void onDeath(DeathEvent event) {
            delivered.add(event);
        }

        @Override
        public void onWin(WinEvent event) {
            delivered.add(event);
        }

        @Override
        public void onSkippedTurn(SkippedTurnEvent event) {
            delivered.add(event);
        }

        @Override
        public void onCustomEvent(CustomEvent event) {
            delivered.add(event);
        }
    }
}
