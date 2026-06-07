/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package robocode.control.snapshot;


/**
 * A snapshot of a single robot-to-robot collision the engine resolved for a
 * robot during a turn.
 * <p>
 * The bearing recorded here is the exact bearing the engine placed on the
 * corresponding {@code HitRobotEvent}; it cannot be recovered from two
 * post-physics turn snapshots because both robots are pushed apart (and their
 * velocities zeroed) before the next snapshot is taken.
 *
 * @author Pavel Savara (original)
 *
 * @since 1.9.5.4
 */
public interface ICollisionSnapshot {

	/**
	 * Returns the robot index of the other robot involved in the collision.
	 *
	 * @return the other robot's index.
	 */
	int getOtherRobotIndex();

	/**
	 * Checks whether this robot was at fault for the collision, i.e. it was
	 * driving into the other robot.
	 *
	 * @return {@code true} if this robot was at fault; {@code false} otherwise.
	 */
	boolean isAtFault();

	/**
	 * Returns the bearing, in radians, from this robot to the other robot, as
	 * recorded on the {@code HitRobotEvent} the engine delivered.
	 *
	 * @return the collision bearing in radians.
	 */
	double getBearingRadians();

	/**
	 * Returns the energy level of the other robot at the moment the collision
	 * was resolved.
	 *
	 * @return the other robot's energy at the time of the collision.
	 */
	double getOtherEnergyAtHit();
}
