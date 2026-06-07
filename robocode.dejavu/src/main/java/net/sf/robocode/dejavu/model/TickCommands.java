/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.model;

import java.util.EnumSet;

/**
 * The realized per-tick commands the hero issued, reconstructed by inverse
 * physics from the snapshot delta {@code prev -> cur}.
 * <p>
 * All turn values are in radians. The three {@code adjust*} coupling flags are
 * assumed {@code false} (the engine default), so the heading split is the plain
 * additive decomposition:
 *
 * <pre>
 *   turnBody  = normalRelative(cur.bodyHeading  - prev.bodyHeading)
 *   turnGun   = normalRelative(cur.gunHeading   - prev.gunHeading)  - turnBody
 *   turnRadar = normalRelative(cur.radarHeading - prev.radarHeading) - turnBody - turnGun
 * </pre>
 *
 * For the four bundled {@code sample} heroes all three flags really are
 * {@code false}, so this split is exact.
 */
public final class TickCommands {

    private final long turn;
    private final double turnBody;
    private final double turnGun;
    private final double turnRadar;
    private final double moveDistance;
    private final boolean fired;
    private final double firePower;
    private final EnumSet<DriftReason> flags;

    public TickCommands(long turn, double turnBody, double turnGun, double turnRadar,
            double moveDistance, boolean fired, double firePower, EnumSet<DriftReason> flags) {
        this.turn = turn;
        this.turnBody = turnBody;
        this.turnGun = turnGun;
        this.turnRadar = turnRadar;
        this.moveDistance = moveDistance;
        this.fired = fired;
        this.firePower = firePower;
        this.flags = flags == null ? EnumSet.noneOf(DriftReason.class) : EnumSet.copyOf(flags);
    }

    public long getTurn() {
        return turn;
    }

    public double getTurnBody() {
        return turnBody;
    }

    public double getTurnGun() {
        return turnGun;
    }

    public double getTurnRadar() {
        return turnRadar;
    }

    /**
     * Signed realized displacement along the body heading for this tick (pixels).
     */
    public double getMoveDistance() {
        return moveDistance;
    }

    public boolean isFired() {
        return fired;
    }

    public double getFirePower() {
        return firePower;
    }

    public EnumSet<DriftReason> getFlags() {
        return flags;
    }
}
