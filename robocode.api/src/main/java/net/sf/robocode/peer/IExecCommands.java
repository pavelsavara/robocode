/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.peer;

/**
 * Read-only view of a robot's realized command state for a single turn.
 * <p>
 * This is the API-visible projection of the engine-internal command record
 * ({@code net.sf.robocode.peer.ExecCommands}, which implements this interface),
 * exposed so test instrumentation can read the ground-truth commands without
 * referencing the concrete engine type. Because this interface lives in the
 * {@code robocode.api} module &mdash; loaded by the shared parent class loader
 * &mdash; instances can be read across the engine's isolated class loader
 * boundary, whereas the concrete {@code ExecCommands} type cannot. It is only
 * surfaced while testing is enabled.
 *
 * @author Pavel Savara (original)
 *
 * @since 1.9.6.0
 */
public interface IExecCommands {

    /**
     * Returns the remaining body turn in radians.
     *
     * @return the remaining body turn in radians.
     */
    double getBodyTurnRemaining();

    /**
     * Returns the remaining gun turn in radians.
     *
     * @return the remaining gun turn in radians.
     */
    double getGunTurnRemaining();

    /**
     * Returns the remaining radar turn in radians.
     *
     * @return the remaining radar turn in radians.
     */
    double getRadarTurnRemaining();

    /**
     * Returns the remaining distance to move.
     *
     * @return the remaining distance to move.
     */
    double getDistanceRemaining();

    /**
     * Returns the maximum turn rate in radians.
     *
     * @return the maximum turn rate in radians.
     */
    double getMaxTurnRate();

    /**
     * Returns the maximum velocity.
     *
     * @return the maximum velocity.
     */
    double getMaxVelocity();

    /**
     * Returns whether the gun is set to turn independently of the body.
     *
     * @return {@code true} if the gun turn is adjusted for body turn.
     */
    boolean isAdjustGunForBodyTurn();

    /**
     * Returns whether the radar is set to turn independently of the gun.
     *
     * @return {@code true} if the radar turn is adjusted for gun turn.
     */
    boolean isAdjustRadarForGunTurn();

    /**
     * Returns whether the radar is set to turn independently of the body.
     *
     * @return {@code true} if the radar turn is adjusted for body turn.
     */
    boolean isAdjustRadarForBodyTurn();

    /**
     * Returns the fire powers of the bullets fired on the turn, one entry per
     * bullet, or an empty array if none were fired.
     *
     * @return the fire powers of the bullets fired on the turn.
     */
    double[] getFirePowers();
}
