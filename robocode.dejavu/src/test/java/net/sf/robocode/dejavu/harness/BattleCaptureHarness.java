/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import net.sf.robocode.peer.IExecCommands;
import robocode.Event;
import robocode.RobotStatus;
import robocode.control.RobotTestBed;
import robocode.control.events.BattleStartedEvent;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.robotinterfaces.IBasicRobot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 0 ground-truth capture harness (the oracle).
 * <p>
 * Runs a deterministic 1v1 battle through the live engine and records, for
 * every turn, the {@code .br}-equivalent turn snapshot together with the engine
 * ground truth ({@link TurnEndedEvent#getGroundTruthEvents()} and
 * {@link TurnEndedEvent#getGroundTruthCommands()}): the exact events the engine
 * delivered to each robot and the realized command state. Snapshots are kept in
 * memory (no {@code .br} round-trip) and aligned with the ground truth by turn,
 * producing the three aligned artifacts Phase 0 calls for: the snapshot stream,
 * the ground-truth event stream, and the ground-truth realized-command stream.
 * <p>
 * Capture is only populated while testing is enabled; the engine attaches no
 * ground truth during normal play.
 */
public final class BattleCaptureHarness extends RobotTestBed<IBasicRobot> {

    private final String heroName;
    private final String enemyName;
    private final int numRounds;
    private final String initialPositions;

    private final List<CapturedTurn> capturedTurns = new ArrayList<CapturedTurn>();
    private final Map<Integer, ITurnSnapshot> roundStartSnapshots = new HashMap<Integer, ITurnSnapshot>();
    private BattleStartedEvent battleStartedEvent;

    public BattleCaptureHarness(String heroName, String enemyName, int numRounds) {
        this(heroName, enemyName, numRounds, null);
    }

    public BattleCaptureHarness(String heroName, String enemyName, int numRounds, String initialPositions) {
        this.heroName = heroName;
        this.enemyName = enemyName;
        this.numRounds = numRounds;
        this.initialPositions = initialPositions;
    }

    @Override
    public String getRobotName() {
        return heroName;
    }

    @Override
    public String getEnemyName() {
        return enemyName;
    }

    @Override
    public int getNumRounds() {
        return numRounds;
    }

    @Override
    public String getInitialPositions() {
        return initialPositions;
    }

    /**
     * Runs the configured battle and captures every turn. May be called once
     * per instance.
     *
     * @return the captured turns, in battle order.
     */
    public List<CapturedTurn> capture() {
        before();
        try {
            run();
        } finally {
            after();
        }
        return capturedTurns;
    }

    public List<CapturedTurn> getCapturedTurns() {
        return capturedTurns;
    }

    /**
     * The round-start (spawn) snapshot for {@code round}, captured from
     * {@link RoundStartedEvent}. It carries the spawn radar heading needed to
     * reconstruct the turn-1 radar sweep, which has no preceding captured turn.
     *
     * @return the start snapshot, or {@code null} if the round was not seen.
     */
    public ITurnSnapshot getRoundStartSnapshot(int round) {
        return roundStartSnapshots.get(round);
    }

    /**
     * The {@link BattleStartedEvent} the engine fired for this battle (carrying
     * the {@link robocode.BattleRules}), captured so callers can replay the same
     * battle setup through an offline listener.
     *
     * @return the battle-started event, or {@code null} if not seen.
     */
    public BattleStartedEvent getBattleStartedEvent() {
        return battleStartedEvent;
    }

    @Override
    public void onBattleStarted(BattleStartedEvent event) {
        super.onBattleStarted(event);
        this.battleStartedEvent = event;
    }

    @Override
    public void onRoundStarted(RoundStartedEvent event) {
        super.onRoundStarted(event);

        final ITurnSnapshot start = event.getStartSnapshot();
        if (start != null) {
            roundStartSnapshots.put(start.getRound(), start);
        }
    }

    @Override
    public void onTurnEnded(TurnEndedEvent event) {
        super.onTurnEnded(event);

        final ITurnSnapshot snapshot = event.getTurnSnapshot();
        if (snapshot == null) {
            return;
        }

        // Ground truth arrays are parallel to the snapshot robots; the hero is index 0.
        final Event[][] events = event.getGroundTruthEvents();
        final IExecCommands[] commands = event.getGroundTruthCommands();
        final RobotStatus[] status = event.getGroundTruthStatus();

        capturedTurns.add(new CapturedTurn(snapshot.getRound(), snapshot.getTurn(), snapshot, events, commands, status));
    }
}
