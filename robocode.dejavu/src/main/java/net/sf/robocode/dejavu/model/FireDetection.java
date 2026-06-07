/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.model;

import robocode.Bullet;

import java.util.EnumSet;

/**
 * A realized hero fire detected on a single turn: a new hero-owned bullet
 * appeared in {@code snapshot[T]} with state {@code FIRED}. Carries the exact
 * realized fire power (the engine-clamped value the born bullet was given) and
 * a reconstructed {@link Bullet} built with the real {@code bulletId} so it
 * hashes and compares identically to the ground-truth bullet.
 * <p>
 * The fire power is the input to the downstream bullet-hit events (Step 4) and
 * to the energy ledger's fire cost (Step 6), which is why fire detection is
 * resolved here during event reconstruction.
 */
public final class FireDetection {

    private final long turn;
    private final double firePower;
    private final Bullet bullet;
    private final EnumSet<DriftReason> flags;

    public FireDetection(long turn, double firePower, Bullet bullet, EnumSet<DriftReason> flags) {
        this.turn = turn;
        this.firePower = firePower;
        this.bullet = bullet;
        this.flags = flags == null ? EnumSet.noneOf(DriftReason.class) : EnumSet.copyOf(flags);
    }

    public long getTurn() {
        return turn;
    }

    /** The realized (engine-clamped) fire power of the born bullet. */
    public double getFirePower() {
        return firePower;
    }

    /** The reconstructed bullet, built with the real {@code bulletId}. */
    public Bullet getBullet() {
        return bullet;
    }

    public EnumSet<DriftReason> getFlags() {
        return flags;
    }

    public boolean hasFlag(DriftReason flag) {
        return flags.contains(flag);
    }
}
