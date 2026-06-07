/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package robocode.control.events;

import net.sf.robocode.peer.IExecCommands;
import robocode.Event;
import robocode.RobotStatus;
import robocode.control.snapshot.ITurnSnapshot;


/**
 * A TurnEndedEvent is sent to {@link IBattleListener#onTurnEnded(TurnEndedEvent)
 * onTurnEnded()} when the current turn in a battle round is ended. 
 *
 * @see IBattleListener
 * @see TurnStartedEvent
 *
 * @author Pavel Savara (original)
 * @author Flemming N. Larsen (contributor)
 *
 * @since 1.6.2
 */
public class TurnEndedEvent extends BattleEvent {
	private final ITurnSnapshot turnSnapshot;
	private final Event[][] groundTruthEvents;
	private final IExecCommands[] groundTruthCommands;
	private final RobotStatus[] groundTruthStatus;

	/**
	 * Called by the game to create a new TurnEndedEvent.
	 * Please don't use this constructor as it might change.
	 *
	 * @param turnSnapshot a snapshot of the turn that has ended.
	 */
	public TurnEndedEvent(ITurnSnapshot turnSnapshot) {
		this(turnSnapshot, null, null, null);
	}

	/**
	 * Called by the game to create a new TurnEndedEvent carrying per-robot
	 * ground truth. Please don't use this constructor as it might change.
	 *
	 * @param turnSnapshot        a snapshot of the turn that has ended.
	 * @param groundTruthEvents   the events the engine delivered to each robot on
	 *                            the turn, indexed in the same order as
	 *                            {@link ITurnSnapshot#getRobots()}, or
	 *                            {@code null} when not testing.
	 * @param groundTruthCommands the realized command state of each robot on the
	 *                            turn, indexed in the same order as
	 *                            {@link ITurnSnapshot#getRobots()}, or
	 *                            {@code null} when not testing.
	 * @param groundTruthStatus   the {@link RobotStatus} the engine delivered to
	 *                            each robot on the turn (surfaced as a
	 *                            {@code StatusEvent}), indexed in the same order
	 *                            as {@link ITurnSnapshot#getRobots()}, or
	 *                            {@code null} when not testing.
	 */
	public TurnEndedEvent(ITurnSnapshot turnSnapshot, Event[][] groundTruthEvents,
			IExecCommands[] groundTruthCommands, RobotStatus[] groundTruthStatus) {
		super();
		this.turnSnapshot = turnSnapshot;
		this.groundTruthEvents = groundTruthEvents;
		this.groundTruthCommands = groundTruthCommands;
		this.groundTruthStatus = groundTruthStatus;
	}

	/**
	 * Returns a snapshot of the turn that has ended.
	 *
	 * @return a snapshot of the turn that has ended.
	 */
	public ITurnSnapshot getTurnSnapshot() {
		return turnSnapshot;
	}

	/**
	 * Returns the events the engine delivered to each robot on the turn, indexed
	 * in the same order as {@link ITurnSnapshot#getRobots()}.
	 * <p>
	 * This is only populated while testing is enabled and is {@code null}
	 * during normal play.
	 *
	 * @return the per-robot delivered events, or {@code null} when not
	 *         available.
	 */
	public Event[][] getGroundTruthEvents() {
		return groundTruthEvents;
	}

	/**
	 * Returns the realized command state of each robot on the turn, indexed in
	 * the same order as {@link ITurnSnapshot#getRobots()}.
	 * <p>
	 * This is only populated while testing is enabled and is {@code null}
	 * during normal play.
	 *
	 * @return the per-robot realized commands, or {@code null} when not
	 *         available.
	 */
	public IExecCommands[] getGroundTruthCommands() {
		return groundTruthCommands;
	}

	/**
	 * Returns the {@link RobotStatus} the engine delivered to each robot on the
	 * turn (the status surfaced to the robot as a {@code StatusEvent}), indexed
	 * in the same order as {@link ITurnSnapshot#getRobots()}.
	 * <p>
	 * This is only populated while testing is enabled and is {@code null}
	 * during normal play. Individual entries may be {@code null} for a robot
	 * that did not execute on the turn.
	 *
	 * @return the per-robot delivered status, or {@code null} when not
	 *         available.
	 */
	public RobotStatus[] getGroundTruthStatus() {
		return groundTruthStatus;
	}
}
