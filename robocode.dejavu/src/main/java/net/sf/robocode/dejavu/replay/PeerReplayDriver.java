/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.replay;

import net.sf.robocode.dejavu.model.TickCommands;
import robocode.robotinterfaces.peer.IAdvancedRobotPeer;

/**
 * Issues reconstructed commands onto a fake, state-backed
 * {@link IAdvancedRobotPeer}. The peer's getters return ground-truth state
 * populated from the snapshot; the {@code set*} calls recorded here represent
 * the hero's realized intent for the tick.
 */
public final class PeerReplayDriver {

    private final IAdvancedRobotPeer peer;

    public PeerReplayDriver(IAdvancedRobotPeer peer) {
        this.peer = peer;
    }

    /** Issue a tick's commands onto the fake peer. */
    public void record(TickCommands cmd) {
        peer.setTurnBody(cmd.getTurnBody());
        peer.setTurnGun(cmd.getTurnGun());
        peer.setTurnRadar(cmd.getTurnRadar());
        peer.setMove(cmd.getMoveDistance());
        if (cmd.isFired()) {
            peer.setFire(cmd.getFirePower());
        }
        peer.execute();
    }
}
