/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.battle.snapshot;


import robocode.control.snapshot.ICollisionSnapshot;

import java.io.Serializable;


/**
 * A snapshot of a single robot-to-robot collision resolved by the engine during
 * a turn.
 *
 * @author Pavel Savara (original)
 *
 * @since 1.9.5.4
 */
public final class CollisionSnapshot implements Serializable, ICollisionSnapshot {

	private static final long serialVersionUID = 1L;

	private final int otherRobotIndex;
	private final boolean atFault;
	private final double bearingRadians;
	private final double otherEnergyAtHit;

	public CollisionSnapshot(int otherRobotIndex, boolean atFault, double bearingRadians, double otherEnergyAtHit) {
		this.otherRobotIndex = otherRobotIndex;
		this.atFault = atFault;
		this.bearingRadians = bearingRadians;
		this.otherEnergyAtHit = otherEnergyAtHit;
	}

	public int getOtherRobotIndex() {
		return otherRobotIndex;
	}

	public boolean isAtFault() {
		return atFault;
	}

	public double getBearingRadians() {
		return bearingRadians;
	}

	public double getOtherEnergyAtHit() {
		return otherEnergyAtHit;
	}

	@Override
	public String toString() {
		return "collision[other=" + otherRobotIndex + " atFault=" + atFault
				+ " bearing=" + bearingRadians + " otherEnergy=" + otherEnergyAtHit + "]";
	}
}
