/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

/**
 * A single recorded {@code set*}/action call issued onto the peer, tagged with
 * the turn on which it was issued. Used as the ground-truth command stream when
 * tee-wrapping a live robot's peer.
 */
public final class CommandRecord {

    /** The category of command issued. */
    public enum Kind {
        MOVE, TURN_BODY, TURN_GUN, TURN_RADAR, FIRE, SET_FIRE,
        STOP, RESUME, EXECUTE, OTHER
    }

    private final long turn;
    private final Kind kind;
    private final double value;

    public CommandRecord(long turn, Kind kind, double value) {
        this.turn = turn;
        this.kind = kind;
        this.value = value;
    }

    public long getTurn() {
        return turn;
    }

    public Kind getKind() {
        return kind;
    }

    /** The scalar argument (radians, pixels or fire power); 0 when not applicable. */
    public double getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "CommandRecord{turn=" + turn + ", kind=" + kind + ", value=" + value + '}';
    }
}
