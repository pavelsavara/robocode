/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu;

import net.sf.robocode.dejavu.core.CommandReconstructor;
import net.sf.robocode.dejavu.harness.BattleCaptureHarness;
import net.sf.robocode.dejavu.harness.CapturedTurn;
import net.sf.robocode.dejavu.harness.StubRobotSnapshot;
import net.sf.robocode.dejavu.harness.StubTurnSnapshot;
import net.sf.robocode.dejavu.model.TickCommands;
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.Rules;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 2 step 11: realized move command (signed per-tick displacement; design
 * &sect;5, plan step 11).
 * <p>
 * In Robocode a robot advances {@code velocity} pixels along its body heading
 * each tick, where {@code velocity} already carries the sign (positive =
 * forward, negative = backward) and has been bounded/accelerated by the engine
 * ({@link Rules#MAX_VELOCITY}, {@link Rules#ACCELERATION},
 * {@link Rules#DECELERATION}; a wall hit forces it to 0). The realized move is
 * therefore exactly the post-physics snapshot velocity, so
 * {@link CommandReconstructor} sets {@code moveDistance = cur.getVelocity()} with
 * no inversion needed.
 * <p>
 * The unit tests drive accel/decel/reverse velocity sequences (and a wall-stop
 * tick where velocity collapses to 0) and assert each tick reconstructs the
 * realized velocity exactly. The integration test is the step gate: across the
 * bundled sample heroes it asserts the reconstructed {@code moveDistance}
 * reproduces the snapshot velocity within {@code EPSILON} every comparable tick
 * (constant-motion {@code sample.Walls} / {@code sample.Crazy} move non-trivially;
 * {@code sample.SittingDuck} stays at exactly 0).
 */
public class MoveCommandTest {

    private static final double EPSILON = 1e-6;

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

    // ---- Unit tests (synthetic velocity sequences) ----------------------

    /**
     * A forward acceleration ramp to the velocity cap, a deceleration back down,
     * a reverse through zero into backward motion, and a backward cap — every
     * realized velocity reconstructs as the move command exactly. The sequence is
     * built from the engine constants ({@code +ACCELERATION} per accel tick,
     * {@code -DECELERATION} per decel tick, clamped to {@code +/-MAX_VELOCITY}),
     * so it mirrors the post-physics velocities the engine would emit.
     */
    @Test
    public void accelDecelReverseSequenceReconstructsRealizedVelocity() {
        double[] velocities = buildAccelDecelReverseSequence();

        CommandReconstructor reconstructor = new CommandReconstructor(0);
        for (int t = 1; t < velocities.length; t++) {
            ITurnSnapshot prev = snap(t - 1, velocities[t - 1]);
            ITurnSnapshot cur = snap(t, velocities[t]);

            TickCommands tc = reconstructor.reconstruct(prev, cur);

            assertEquals("tick " + t + " realized move", velocities[t], tc.getMoveDistance(), EPSILON);
            // The move command equals the post-physics snapshot velocity (the oracle).
            assertEquals("tick " + t + " move == snapshot velocity",
                    cur.getRobots()[0].getVelocity(), tc.getMoveDistance(), EPSILON);
        }
    }

    /**
     * A wall hit forces velocity to 0 on the contact tick; the reconstructed move
     * command must read exactly 0 (and resume the realized velocity afterwards).
     */
    @Test
    public void wallStopTickReconstructsZeroVelocity() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        // Cruising forward at the cap, then a wall hit collapses velocity to 0.
        ITurnSnapshot cruising = snap(0, Rules.MAX_VELOCITY);
        ITurnSnapshot wallStop = snap(1, 0);

        TickCommands stopped = reconstructor.reconstruct(cruising, wallStop);
        assertEquals("wall-stop realized move", 0, stopped.getMoveDistance(), EPSILON);

        // The very next tick the robot accelerates away from the wall again.
        ITurnSnapshot resumed = snap(2, Rules.ACCELERATION);
        TickCommands moving = reconstructor.reconstruct(wallStop, resumed);
        assertEquals("post-wall realized move", Rules.ACCELERATION, moving.getMoveDistance(), EPSILON);
    }

    /**
     * A negative snapshot velocity (backward motion) reconstructs with its sign
     * preserved &mdash; the move command is the signed displacement, never the
     * magnitude.
     */
    @Test
    public void backwardVelocityKeepsSign() {
        CommandReconstructor reconstructor = new CommandReconstructor(0);

        ITurnSnapshot prev = snap(0, -3);
        ITurnSnapshot cur = snap(1, -5);

        TickCommands tc = reconstructor.reconstruct(prev, cur);

        assertEquals("backward move keeps sign", -5, tc.getMoveDistance(), EPSILON);
    }

    // ---- Integration test (step 11 gate) --------------------------------

    @Test
    public void realizedMoveMatchesGroundTruthForAllHeroes() {
        int comparedTurns = 0;
        for (String hero : HEROES) {
            comparedTurns += verifyHero(hero);
        }
        assertTrue("no turns were compared", comparedTurns > 0);
    }

    /** @return number of ticks compared for the hero. */
    private int verifyHero(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        boolean sittingDuck = "sample.SittingDuck".equals(hero);

        CommandReconstructor reconstructor = new CommandReconstructor(0);
        int compared = 0;
        int round = -1;
        ITurnSnapshot prev = null;
        double maxAbsMove = 0;

        for (CapturedTurn ct : captured) {
            if (ct.getRound() != round) {
                round = ct.getRound();
                prev = harness.getRoundStartSnapshot(round);
            }

            ITurnSnapshot cur = ct.getSnapshot();
            ITurnSnapshot base = prev == null ? cur : prev;
            IRobotSnapshot c = cur.getRobots()[0];

            // The realized move (flag-independent) must equal the snapshot velocity.
            TickCommands tc = reconstructor.reconstruct(base, cur);

            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            assertEquals(at + "realized move == snapshot velocity",
                    c.getVelocity(), tc.getMoveDistance(), EPSILON);

            if (sittingDuck) {
                assertEquals(at + "SittingDuck never moves", 0, tc.getMoveDistance(), EPSILON);
            }

            maxAbsMove = Math.max(maxAbsMove, Math.abs(tc.getMoveDistance()));
            prev = cur;
            compared++;
        }

        if (sittingDuck) {
            assertEquals("[" + hero + "] SittingDuck stays at 0 throughout", 0, maxAbsMove, EPSILON);
        } else {
            assertTrue("[" + hero + "] expected non-trivial motion (maxAbsMove=" + maxAbsMove + ")",
                    maxAbsMove > 1.0);
        }
        return compared;
    }

    // ---- Helpers --------------------------------------------------------

    /**
     * Build a realistic post-physics velocity sequence: accelerate forward to the
     * cap (+ACCELERATION/tick), decelerate (-DECELERATION/tick) through zero,
     * accelerate backward to the negative cap. Mirrors the engine's bounded
     * accel/decel so the reconstructed move equals each realized velocity.
     */
    private static double[] buildAccelDecelReverseSequence() {
        java.util.ArrayList<Double> seq = new java.util.ArrayList<>();
        double v = 0;
        seq.add(v);
        // Accelerate forward to the cap.
        while (v < Rules.MAX_VELOCITY) {
            v = Math.min(Rules.MAX_VELOCITY, v + Rules.ACCELERATION);
            seq.add(v);
        }
        // Decelerate and reverse through zero down to the backward cap.
        while (v > -Rules.MAX_VELOCITY) {
            v = Math.max(-Rules.MAX_VELOCITY, v - Rules.DECELERATION);
            seq.add(v);
        }
        double[] out = new double[seq.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = seq.get(i);
        }
        return out;
    }

    private static ITurnSnapshot snap(int t, double velocity) {
        IRobotSnapshot hero = new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(80)
                .bodyHeading(0).gunHeading(0).radarHeading(0).x(400).y(300)
                .velocity(velocity);
        IRobotSnapshot opponent = new StubRobotSnapshot()
                .name(ENEMY).robotIndex(1).energy(60).x(600).y(300);
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { hero, opponent });
    }
}
