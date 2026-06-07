/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.replay;

import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.Condition;
import robocode.Event;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.control.snapshot.IRobotSnapshot;

import java.awt.Color;
import java.awt.Graphics2D;
import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * A state-backed {@link IReplayRobotPeer} for offline replay.
 * <p>
 * Its read-only getters return the hero's ground-truth state, back-filled each
 * turn from the {@code .br} snapshot via {@link #loadState}; its {@code set*}
 * mutators record the reconstructed command the hero realized for the tick (so
 * they can be inspected) but do not run any game mechanics. Blocking calls
 * ({@code waitFor}, {@code move}, {@code turnBody}, ...) and side-effecting
 * helpers ({@code getGraphics}, data-file access, custom events, colors) are
 * inert: a replayed hero never advances the engine, it only reads its recorded
 * state and reacts to delivered events.
 * <p>
 * Not thread-safe; intended to be driven from a single replay loop.
 */
public final class ReplayRobotPeer implements IReplayRobotPeer {

    private static final double DEFAULT_GUN_COOLING_RATE = 0.1;

    // Battle-constant state.
    private double battlefieldWidth = 800;
    private double battlefieldHeight = 600;
    private int numRounds = 1;

    // Per-turn state (back-filled from the snapshot).
    private String name = "";
    private double x;
    private double y;
    private double velocity;
    private double bodyHeading;
    private double gunHeading;
    private double radarHeading;
    private double energy;
    private double gunHeat;
    private long time;
    private int roundNum;
    private int others;
    private IRobotSnapshot lastLoadedHero;

    // Recorded command intent for the current tick.
    private double lastTurnBody;
    private double lastTurnGun;
    private double lastTurnRadar;
    private double lastMove;
    private boolean lastFired;
    private double lastFirePower;
    private int executeCount;

    // Coupling flags (echoed back through the isAdjust* getters).
    private boolean adjustGunForBodyTurn;
    private boolean adjustRadarForGunTurn;
    private boolean adjustRadarForBodyTurn;

    // ---- IReplayRobotPeer back-fill -------------------------------------

    @Override
    public void loadBattleRules(double battlefieldWidth, double battlefieldHeight, int numRounds) {
        this.battlefieldWidth = battlefieldWidth;
        this.battlefieldHeight = battlefieldHeight;
        this.numRounds = numRounds;
    }

    @Override
    public void loadState(IRobotSnapshot hero, long time, int roundNum, int others) {
        this.lastLoadedHero = hero;
        this.name = hero.getName();
        this.x = hero.getX();
        this.y = hero.getY();
        this.velocity = hero.getVelocity();
        this.bodyHeading = hero.getBodyHeading();
        this.gunHeading = hero.getGunHeading();
        this.radarHeading = hero.getRadarHeading();
        this.energy = hero.getEnergy();
        this.gunHeat = hero.getGunHeat();
        this.time = time;
        this.roundNum = roundNum;
        this.others = others;
    }

    /** The hero snapshot most recently loaded via {@link #loadState}, or {@code null}. */
    public IRobotSnapshot getLastLoadedHero() {
        return lastLoadedHero;
    }

    public double getLastTurnBody() {
        return lastTurnBody;
    }

    public double getLastTurnGun() {
        return lastTurnGun;
    }

    public double getLastTurnRadar() {
        return lastTurnRadar;
    }

    public double getLastMove() {
        return lastMove;
    }

    public boolean isLastFired() {
        return lastFired;
    }

    public double getLastFirePower() {
        return lastFirePower;
    }

    /** Number of times {@link #execute()} was called (one per recorded tick). */
    public int getExecuteCount() {
        return executeCount;
    }

    // ---- Read-only state getters ----------------------------------------

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getTime() {
        return time;
    }

    @Override
    public double getEnergy() {
        return energy;
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
    public double getBattleFieldWidth() {
        return battlefieldWidth;
    }

    @Override
    public double getBattleFieldHeight() {
        return battlefieldHeight;
    }

    @Override
    public int getOthers() {
        return others;
    }

    @Override
    public int getNumSentries() {
        return 0;
    }

    @Override
    public int getNumRounds() {
        return numRounds;
    }

    @Override
    public int getRoundNum() {
        return roundNum;
    }

    @Override
    public int getSentryBorderSize() {
        return 0;
    }

    @Override
    public double getGunCoolingRate() {
        return DEFAULT_GUN_COOLING_RATE;
    }

    @Override
    public double getDistanceRemaining() {
        return lastMove;
    }

    @Override
    public double getBodyTurnRemaining() {
        return lastTurnBody;
    }

    @Override
    public double getGunTurnRemaining() {
        return lastTurnGun;
    }

    @Override
    public double getRadarTurnRemaining() {
        return lastTurnRadar;
    }

    // ---- Recorded command intent ----------------------------------------

    @Override
    public void setMove(double distance) {
        this.lastMove = distance;
    }

    @Override
    public void setTurnBody(double radians) {
        this.lastTurnBody = radians;
    }

    @Override
    public void setTurnGun(double radians) {
        this.lastTurnGun = radians;
    }

    @Override
    public void setTurnRadar(double radians) {
        this.lastTurnRadar = radians;
    }

    @Override
    public Bullet setFire(double power) {
        this.lastFired = true;
        this.lastFirePower = power;
        return null;
    }

    @Override
    public void execute() {
        executeCount++;
    }

    // ---- Coupling flags --------------------------------------------------

    @Override
    public void setAdjustGunForBodyTurn(boolean adjust) {
        this.adjustGunForBodyTurn = adjust;
    }

    @Override
    public void setAdjustRadarForGunTurn(boolean adjust) {
        this.adjustRadarForGunTurn = adjust;
    }

    @Override
    public void setAdjustRadarForBodyTurn(boolean adjust) {
        this.adjustRadarForBodyTurn = adjust;
    }

    @Override
    public boolean isAdjustGunForBodyTurn() {
        return adjustGunForBodyTurn;
    }

    @Override
    public boolean isAdjustRadarForGunTurn() {
        return adjustRadarForGunTurn;
    }

    @Override
    public boolean isAdjustRadarForBodyTurn() {
        return adjustRadarForBodyTurn;
    }

    // ---- Inert engine-advancing / side-effecting operations -------------

    @Override
    public void move(double distance) {
        // Inert: replay never advances the engine.
    }

    @Override
    public void turnBody(double radians) {
        // Inert.
    }

    @Override
    public void turnGun(double radians) {
        // Inert.
    }

    @Override
    public void turnRadar(double radians) {
        // Inert.
    }

    @Override
    public Bullet fire(double power) {
        return null;
    }

    @Override
    public void stop(boolean overwrite) {
        // Inert.
    }

    @Override
    public void resume() {
        // Inert.
    }

    @Override
    public void setStop(boolean overwrite) {
        // Inert.
    }

    @Override
    public void setResume() {
        // Inert.
    }

    @Override
    public void setMaxTurnRate(double newMaxTurnRate) {
        // Inert.
    }

    @Override
    public void setMaxVelocity(double newMaxVelocity) {
        // Inert.
    }

    @Override
    public void waitFor(Condition condition) {
        // Inert: a replayed hero never blocks waiting for engine conditions.
    }

    @Override
    public void setInterruptible(boolean interruptible) {
        // Inert.
    }

    @Override
    public void setEventPriority(String eventClass, int priority) {
        // Inert.
    }

    @Override
    public int getEventPriority(String eventClass) {
        return 0;
    }

    @Override
    public void addCustomEvent(Condition condition) {
        // Inert.
    }

    @Override
    public void removeCustomEvent(Condition condition) {
        // Inert.
    }

    @Override
    public void clearAllEvents() {
        // Inert.
    }

    @Override
    public List<Event> getAllEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<StatusEvent> getStatusEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<BulletMissedEvent> getBulletMissedEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<BulletHitBulletEvent> getBulletHitBulletEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<BulletHitEvent> getBulletHitEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<HitByBulletEvent> getHitByBulletEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<HitRobotEvent> getHitRobotEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<HitWallEvent> getHitWallEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<RobotDeathEvent> getRobotDeathEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<ScannedRobotEvent> getScannedRobotEvents() {
        return Collections.emptyList();
    }

    @Override
    public File getDataDirectory() {
        return null;
    }

    @Override
    public File getDataFile(String filename) {
        return null;
    }

    @Override
    public long getDataQuotaAvailable() {
        return 0;
    }

    @Override
    public void setBodyColor(Color color) {
        // Inert.
    }

    @Override
    public void setGunColor(Color color) {
        // Inert.
    }

    @Override
    public void setRadarColor(Color color) {
        // Inert.
    }

    @Override
    public void setBulletColor(Color color) {
        // Inert.
    }

    @Override
    public void setScanColor(Color color) {
        // Inert.
    }

    @Override
    public void getCall() {
        // Inert.
    }

    @Override
    public void setCall() {
        // Inert.
    }

    @Override
    public Graphics2D getGraphics() {
        return null;
    }

    @Override
    public void setDebugProperty(String key, String value) {
        // Inert.
    }

    @Override
    public void rescan() {
        // Inert.
    }
}
