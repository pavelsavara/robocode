/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.IScoreSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

/**
 * Minimal {@link ITurnSnapshot} stub for unit tests that drive the
 * reconstructors with synthetic snapshots. Carries the round/turn coordinates
 * plus the participating robot snapshots and (optionally) bullets.
 */
public final class StubTurnSnapshot implements ITurnSnapshot {

    private final int round;
    private final int turn;
    private final IRobotSnapshot[] robots;
    private final IBulletSnapshot[] bullets;

    public StubTurnSnapshot(int round, int turn, IRobotSnapshot[] robots) {
        this(round, turn, robots, new IBulletSnapshot[0]);
    }

    public StubTurnSnapshot(int round, int turn, IRobotSnapshot[] robots, IBulletSnapshot[] bullets) {
        this.round = round;
        this.turn = turn;
        this.robots = robots;
        this.bullets = bullets;
    }

    @Override
    public IRobotSnapshot[] getRobots() {
        return robots;
    }

    @Override
    public IBulletSnapshot[] getBullets() {
        return bullets;
    }

    @Override
    public int getTPS() {
        return 0;
    }

    @Override
    public int getRound() {
        return round;
    }

    @Override
    public int getTurn() {
        return turn;
    }

    @Override
    public IScoreSnapshot[] getSortedTeamScores() {
        return new IScoreSnapshot[0];
    }

    @Override
    public IScoreSnapshot[] getIndexedTeamScores() {
        return new IScoreSnapshot[0];
    }
}
