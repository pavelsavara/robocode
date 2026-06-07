/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.model;

/**
 * Decomposition of a single tick's energy change into its independent
 * components. The sum of the components should equal the observed
 * {@code cur.energy - prev.energy} (within epsilon); any leftover is the
 * {@link #residual}.
 */
public final class EnergyBreakdown {

    private double fireCost; // -firePower
    private double bulletHitBonus; // +3 * power, per hero bullet hitting victim
    private double bulletDamage; // -getBulletDamage(power), per opponent bullet hitting hero
    private double wallDamage; // -max(|v|/2 - 1, 0)
    private double ramDamage; // -0.6 per contact tick
    private double inactivityDrain; // -0.1 per inactivity-zap tick
    private double residual; // observed - sum(components)

    public double getFireCost() {
        return fireCost;
    }

    public void setFireCost(double fireCost) {
        this.fireCost = fireCost;
    }

    public double getBulletHitBonus() {
        return bulletHitBonus;
    }

    public void setBulletHitBonus(double bulletHitBonus) {
        this.bulletHitBonus = bulletHitBonus;
    }

    public double getBulletDamage() {
        return bulletDamage;
    }

    public void setBulletDamage(double bulletDamage) {
        this.bulletDamage = bulletDamage;
    }

    public double getWallDamage() {
        return wallDamage;
    }

    public void setWallDamage(double wallDamage) {
        this.wallDamage = wallDamage;
    }

    public double getRamDamage() {
        return ramDamage;
    }

    public void setRamDamage(double ramDamage) {
        this.ramDamage = ramDamage;
    }

    public double getInactivityDrain() {
        return inactivityDrain;
    }

    public void setInactivityDrain(double inactivityDrain) {
        this.inactivityDrain = inactivityDrain;
    }

    public double getResidual() {
        return residual;
    }

    public void setResidual(double residual) {
        this.residual = residual;
    }

    /** Sum of all attributed components (excludes residual). */
    public double sum() {
        return fireCost + bulletHitBonus + bulletDamage + wallDamage + ramDamage + inactivityDrain;
    }
}
