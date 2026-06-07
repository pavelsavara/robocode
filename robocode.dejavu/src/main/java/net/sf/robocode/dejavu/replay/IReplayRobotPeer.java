/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.replay;

import robocode.control.snapshot.IRobotSnapshot;
import robocode.robotinterfaces.peer.IAdvancedRobotPeer;

/**
 * An {@link IAdvancedRobotPeer} whose read-only getters are populated from
 * offline ground-truth snapshots rather than a live engine, so a replayed hero
 * sees the same physical state it saw during the original battle.
 * <p>
 * {@link net.sf.robocode.dejavu.Dejavu} pushes the battle-constant rules once at
 * the start and the hero's per-turn state each tick (via {@link #loadState}),
 * just before the reconstructed commands are recorded through
 * {@link PeerReplayDriver}. A caller that supplies a plain
 * {@link IAdvancedRobotPeer} (not implementing this interface) simply does not
 * get its getters back-filled.
 */
public interface IReplayRobotPeer extends IAdvancedRobotPeer {

    /**
     * Set the battle-constant values the peer reports for the whole battle.
     *
     * @param battlefieldWidth  battlefield width in pixels
     * @param battlefieldHeight battlefield height in pixels
     * @param numRounds         the number of rounds in the battle
     */
    void loadBattleRules(double battlefieldWidth, double battlefieldHeight, int numRounds);

    /**
     * Back-fill the per-turn state the peer's getters return, from the hero's
     * ground-truth snapshot for the turn.
     *
     * @param hero     the hero's robot snapshot for this turn
     * @param time     the turn number within the round
     * @param roundNum the current round number (0-based)
     * @param others   the number of other robots still alive this turn
     */
    void loadState(IRobotSnapshot hero, long time, int roundNum, int others);
}
