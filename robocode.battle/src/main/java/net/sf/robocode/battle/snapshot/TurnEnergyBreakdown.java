/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.battle.snapshot;


import robocode.control.snapshot.ITurnEnergyBreakdown;

import java.io.Serializable;


/**
 * A snapshot of the signed per-turn energy changes the engine applied to a
 * robot, decomposed by category.
 *
 * @author Pavel Savara (original)
 *
 * @since 1.9.5.4
 */
public final class TurnEnergyBreakdown implements Serializable, ITurnEnergyBreakdown {

	private static final long serialVersionUID = 1L;

	private final double fireCost;
	private final double hitByBullet;
	private final double hitOpponent;
	private final double hitRobot;
	private final double hitWall;
	private final double zap;

	public TurnEnergyBreakdown(double fireCost, double hitByBullet, double hitOpponent, double hitRobot,
			double hitWall, double zap) {
		this.fireCost = fireCost;
		this.hitByBullet = hitByBullet;
		this.hitOpponent = hitOpponent;
		this.hitRobot = hitRobot;
		this.hitWall = hitWall;
		this.zap = zap;
	}

	public double getFireCostEnergy() {
		return fireCost;
	}

	public double getHitByBulletEnergy() {
		return hitByBullet;
	}

	public double getHitOpponentEnergy() {
		return hitOpponent;
	}

	public double getHitRobotEnergy() {
		return hitRobot;
	}

	public double getHitWallEnergy() {
		return hitWall;
	}

	public double getZapEnergy() {
		return zap;
	}

	public double getTotal() {
		return fireCost + hitByBullet + hitOpponent + hitRobot + hitWall + zap;
	}

	@Override
	public String toString() {
		return "energy[fire=" + fireCost + " hitBy=" + hitByBullet + " hitOpp=" + hitOpponent
				+ " ram=" + hitRobot + " wall=" + hitWall + " zap=" + zap + " total=" + getTotal() + "]";
	}
}
