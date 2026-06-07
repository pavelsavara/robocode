/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import robocode.Rules;

/**
 * Engine physics helpers used during reconstruction that are not already a
 * direct {@link robocode.Rules} call. Stateless.
 */
public final class Physics {

    private Physics() {
    }

    /**
     * Reconstruct a robot's pre-collision velocity by advancing the previous
     * turn's velocity one acceleration step (sign preserving, clamped to
     * {@link Rules#MAX_VELOCITY}). On a collision tick the snapshot velocity is
     * zeroed by the engine, so this estimate stands in for the
     * post-{@code updateMovement} velocity used by the collision geometry and the
     * wall-hit damage ledger. It is an estimate: the engine's realised velocity
     * depends on the (unrecorded) movement command, so a robot that was
     * decelerating into the impact is not exactly recoverable.
     */
    public static double preCollisionVelocity(double prevVel) {
        if (Double.isNaN(prevVel)) {
            return 0;
        }
        if (prevVel >= 0) {
            return Math.min(prevVel + Rules.ACCELERATION, Rules.MAX_VELOCITY);
        }
        return Math.max(prevVel - Rules.ACCELERATION, -Rules.MAX_VELOCITY);
    }
}
