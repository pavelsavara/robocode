/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import robocode.control.snapshot.ICollisionSnapshot;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.IScoreSnapshot;
import robocode.control.snapshot.ITurnEnergyBreakdown;
import robocode.control.snapshot.RobotState;

/**
 * Minimal fluent {@link IRobotSnapshot} stub for unit tests that drive the
 * reconstructors with synthetic snapshots. Only the physics fields relevant to
 * reconstruction are configurable; the remaining cosmetic/metadata accessors
 * return harmless defaults.
 */
public final class StubRobotSnapshot implements IRobotSnapshot {

    private String name = "stub";
    private int robotIndex;
    private RobotState state = RobotState.ACTIVE;
    private double energy;
    private double velocity;
    private double realizedVelocity = Double.NaN;
    private double bodyHeading;
    private double gunHeading;
    private double radarHeading;
    private double gunHeat;
    private double x;
    private double y;

    public StubRobotSnapshot name(String value) {
        this.name = value;
        return this;
    }

    public StubRobotSnapshot robotIndex(int value) {
        this.robotIndex = value;
        return this;
    }

    public StubRobotSnapshot state(RobotState value) {
        this.state = value;
        return this;
    }

    public StubRobotSnapshot energy(double value) {
        this.energy = value;
        return this;
    }

    public StubRobotSnapshot velocity(double value) {
        this.velocity = value;
        return this;
    }

    public StubRobotSnapshot realizedVelocity(double value) {
        this.realizedVelocity = value;
        return this;
    }

    public StubRobotSnapshot bodyHeading(double value) {
        this.bodyHeading = value;
        return this;
    }

    public StubRobotSnapshot gunHeading(double value) {
        this.gunHeading = value;
        return this;
    }

    public StubRobotSnapshot radarHeading(double value) {
        this.radarHeading = value;
        return this;
    }

    public StubRobotSnapshot gunHeat(double value) {
        this.gunHeat = value;
        return this;
    }

    public StubRobotSnapshot x(double value) {
        this.x = value;
        return this;
    }

    public StubRobotSnapshot y(double value) {
        this.y = value;
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getShortName() {
        return name;
    }

    @Override
    public String getVeryShortName() {
        return name;
    }

    @Override
    public String getTeamName() {
        return name;
    }

    @Override
    public int getRobotIndex() {
        return robotIndex;
    }

    @Override
    public int getTeamIndex() {
        return -1;
    }

    @Override
    public int getContestantIndex() {
        return robotIndex;
    }

    @Override
    public RobotState getState() {
        return state;
    }

    @Override
    public double getEnergy() {
        return energy;
    }

    @Override
    public double getVelocity() {
        return velocity;
    }

    @Override
    public double getBodyHeading() {
        return bodyHeading;
    }

    @Override
    public double getGunHeading() {
        return gunHeading;
    }

    @Override
    public double getRadarHeading() {
        return radarHeading;
    }

    @Override
    public double getGunHeat() {
        return gunHeat;
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
    public int getBodyColor() {
        return 0;
    }

    @Override
    public int getGunColor() {
        return 0;
    }

    @Override
    public int getRadarColor() {
        return 0;
    }

    @Override
    public int getScanColor() {
        return 0;
    }

    @Override
    public boolean isDroid() {
        return false;
    }

    @Override
    public boolean isSentryRobot() {
        return false;
    }

    @Override
    public boolean isPaintRobot() {
        return false;
    }

    @Override
    public boolean isPaintEnabled() {
        return false;
    }

    @Override
    public boolean isSGPaintEnabled() {
        return false;
    }

    @Override
    public IDebugProperty[] getDebugProperties() {
        return new IDebugProperty[0];
    }

    @Override
    public String getOutputStreamSnapshot() {
        return "";
    }

    @Override
    public IScoreSnapshot getScoreSnapshot() {
        return null;
    }

    @Override
    public ITurnEnergyBreakdown getEnergyChanges() {
        return null;
    }

    @Override
    public double getRealizedVelocity() {
        return Double.isNaN(realizedVelocity) ? velocity : realizedVelocity;
    }

    @Override
    public ICollisionSnapshot[] getCollisions() {
        return new ICollisionSnapshot[0];
    }

    @Override
    public boolean isScanning() {
        return false;
    }

    @Override
    public boolean wasTurnSkipped() {
        return false;
    }
}