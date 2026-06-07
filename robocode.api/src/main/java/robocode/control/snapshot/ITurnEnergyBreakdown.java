/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package robocode.control.snapshot;


/**
 * A per-turn decomposition of the signed energy changes the engine applied to a
 * robot during a single turn.
 * <p>
 * Each component is the exact signed delta the engine passed to
 * {@code updateEnergy}/{@code setEnergy} for that category (losses are negative,
 * the hit-opponent bonus is positive). The components sum to {@link #getTotal()},
 * which equals {@code current.getEnergy() - previous.getEnergy()} for the turn,
 * modulo the engine's {@code < 0.01} disabled-energy clamp.
 * <p>
 * This snapshot is captured for diagnostic and reconstruction purposes only; the
 * decomposition cannot otherwise be recovered from two post-physics turn
 * snapshots because the engine collapses several mid-turn energy events into a
 * single visible energy level.
 *
 * @author Pavel Savara (original)
 *
 * @since 1.9.5.4
 */
public interface ITurnEnergyBreakdown {

	/**
	 * Returns the energy spent firing bullets this turn (the negated sum of the
	 * realized fire powers), or {@code 0} if no bullet was fired.
	 *
	 * @return the fire-cost energy delta this turn ({@code <= 0}).
	 */
	double getFireCostEnergy();

	/**
	 * Returns the energy lost to being hit by opponent bullets this turn (the
	 * negated sum of the bullet damages), or {@code 0} if not hit.
	 *
	 * @return the hit-by-bullet energy delta this turn ({@code <= 0}).
	 */
	double getHitByBulletEnergy();

	/**
	 * Returns the bonus energy gained from this robot's bullets hitting an
	 * opponent this turn (the sum of the bullet-hit bonuses), or {@code 0} if
	 * none of this robot's bullets hit.
	 *
	 * @return the hit-opponent bonus energy delta this turn ({@code >= 0}).
	 */
	double getHitOpponentEnergy();

	/**
	 * Returns the energy lost to ramming or being rammed by another robot this
	 * turn, or {@code 0} if there was no robot collision.
	 *
	 * @return the robot-collision energy delta this turn ({@code <= 0}).
	 */
	double getHitRobotEnergy();

	/**
	 * Returns the energy lost to hitting a wall this turn, or {@code 0} if the
	 * robot did not hit a wall.
	 *
	 * @return the wall-hit energy delta this turn ({@code <= 0}).
	 */
	double getHitWallEnergy();

	/**
	 * Returns the energy drained by the inactivity zap this turn, or {@code 0}
	 * if the robot was not zapped.
	 *
	 * @return the inactivity-zap energy delta this turn ({@code <= 0}).
	 */
	double getZapEnergy();

	/**
	 * Returns the total signed energy change this turn. This equals the sum of
	 * the individual components and matches {@code current.getEnergy() -
	 * previous.getEnergy()} modulo the engine's {@code < 0.01} disabled-energy
	 * clamp.
	 *
	 * @return the total energy delta this turn.
	 */
	double getTotal();
}
