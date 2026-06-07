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
import robocode.control.snapshot.ITurnSnapshot;

/**
 * One captured turn from a live battle: the {@code .br}-equivalent turn
 * snapshot
 * aligned with the engine ground truth for the same turn.
 * <p>
 * This is the in-memory unit produced by {@link BattleCaptureHarness} and the
 * oracle that Phase 1+ reconstructions are validated against: the snapshot is
 * the reconstruction input, while the per-robot delivered events and realized
 * command snapshots carry exactly what the engine delivered each robot on the
 * turn. Both per-robot arrays are indexed in the same order as
 * {@link ITurnSnapshot#getRobots()}; the hero is robot index {@code 0}.
 */
public final class CapturedTurn {

    private final int round;
    private final int turn;
    private final ITurnSnapshot snapshot;
    private final Event[][] deliveredEvents;
    private final IExecCommands[] commands;
    private final RobotStatus[] deliveredStatus;

    public CapturedTurn(int round, int turn, ITurnSnapshot snapshot,
            Event[][] deliveredEvents, IExecCommands[] commands, RobotStatus[] deliveredStatus) {
        this.round = round;
        this.turn = turn;
        this.snapshot = snapshot;
        this.deliveredEvents = deliveredEvents;
        this.commands = commands;
        this.deliveredStatus = deliveredStatus;
    }

    public int getRound() {
        return round;
    }

    public int getTurn() {
        return turn;
    }

    /** The turn snapshot (reconstruction input). */
    public ITurnSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Per-robot delivered events for the turn, indexed like the snapshot robots.
     */
    public Event[][] getDeliveredEvents() {
        return deliveredEvents;
    }

    /**
     * Per-robot realized commands for the turn, indexed like the snapshot robots.
     */
    public IExecCommands[] getCommands() {
        return commands;
    }

    /** The hero's (robot index 0) delivered events, or an empty array if absent. */
    public Event[] getHeroEvents() {
        return (deliveredEvents != null && deliveredEvents.length > 0) ? deliveredEvents[0] : new Event[0];
    }

    /** The hero's (robot index 0) realized commands, or {@code null} if absent. */
    public IExecCommands getHeroCommands() {
        return (commands != null && commands.length > 0) ? commands[0] : null;
    }

    /**
     * Per-robot delivered status for the turn, indexed like the snapshot robots.
     */
    public RobotStatus[] getDeliveredStatus() {
        return deliveredStatus;
    }

    /** The hero's (robot index 0) delivered status, or {@code null} if absent. */
    public RobotStatus getHeroStatus() {
        return (deliveredStatus != null && deliveredStatus.length > 0) ? deliveredStatus[0] : null;
    }

    @Override
    public String toString() {
        return "CapturedTurn{round=" + round + ", turn=" + turn + '}';
    }
}
