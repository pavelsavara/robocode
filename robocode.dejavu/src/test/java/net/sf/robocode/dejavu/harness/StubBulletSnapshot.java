/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;

/**
 * Minimal fluent {@link IBulletSnapshot} stub for unit tests that drive the
 * reconstructors with synthetic bullets. Only the fields relevant to
 * reconstruction are configurable; the remaining cosmetic accessors return
 * harmless defaults.
 */
public final class StubBulletSnapshot implements IBulletSnapshot {

    private BulletState state = BulletState.MOVING;
    private double power;
    private double x;
    private double y;
    private double heading;
    private int bulletId;
    private int ownerIndex = -1;
    private int victimIndex = -1;

    public StubBulletSnapshot state(BulletState value) {
        this.state = value;
        return this;
    }

    public StubBulletSnapshot power(double value) {
        this.power = value;
        return this;
    }

    public StubBulletSnapshot x(double value) {
        this.x = value;
        return this;
    }

    public StubBulletSnapshot y(double value) {
        this.y = value;
        return this;
    }

    public StubBulletSnapshot heading(double value) {
        this.heading = value;
        return this;
    }

    public StubBulletSnapshot bulletId(int value) {
        this.bulletId = value;
        return this;
    }

    public StubBulletSnapshot ownerIndex(int value) {
        this.ownerIndex = value;
        return this;
    }

    public StubBulletSnapshot victimIndex(int value) {
        this.victimIndex = value;
        return this;
    }

    @Override
    public BulletState getState() {
        return state;
    }

    @Override
    public double getPower() {
        return power;
    }

    @Override
    public double getX() {
        return x;
    }

    @Override
    public double getY() {
        return y;
    }

    @Override
    public double getPaintX() {
        return x;
    }

    @Override
    public double getPaintY() {
        return y;
    }

    @Override
    public int getColor() {
        return 0;
    }

    @Override
    public int getFrame() {
        return 0;
    }

    @Override
    public boolean isExplosion() {
        return false;
    }

    @Override
    public int getExplosionImageIndex() {
        return 0;
    }

    @Override
    public int getBulletId() {
        return bulletId;
    }

    @Override
    public double getHeading() {
        return heading;
    }

    @Override
    public int getVictimIndex() {
        return victimIndex;
    }

    @Override
    public int getOwnerIndex() {
        return ownerIndex;
    }

    @Override
    public double getVictimEnergyAtHit() {
        return Double.NaN;
    }
}
