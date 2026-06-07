/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.replay;

import net.sf.robocode.dejavu.model.TickEvents;
import robocode.Event;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.DeathEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.WinEvent;
import robocode.robotinterfaces.IAdvancedEvents;
import robocode.robotinterfaces.IAdvancedRobot;
import robocode.robotinterfaces.IBasicEvents;

/**
 * Delivers reconstructed events to a target {@link IAdvancedRobot}'s listeners,
 * mimicking the engine's dispatch.
 */
public final class RobotReplayDriver {

    private final IBasicEvents basic;
    private final IAdvancedEvents advanced;

    public RobotReplayDriver(IAdvancedRobot robot) {
        this.basic = robot.getBasicEventListener();
        this.advanced = robot.getAdvancedEventListener();
    }

    /** Deliver all events of a tick in their (already sorted) dispatch order. */
    public void deliver(TickEvents tick) {
        for (Event e : tick.getEvents()) {
            dispatch(e);
        }
    }

    private void dispatch(Event e) {
        if (basic == null) {
            return;
        }
        if (e instanceof StatusEvent) {
            basic.onStatus((StatusEvent) e);
        } else if (e instanceof ScannedRobotEvent) {
            basic.onScannedRobot((ScannedRobotEvent) e);
        } else if (e instanceof HitByBulletEvent) {
            basic.onHitByBullet((HitByBulletEvent) e);
        } else if (e instanceof BulletHitEvent) {
            basic.onBulletHit((BulletHitEvent) e);
        } else if (e instanceof BulletHitBulletEvent) {
            basic.onBulletHitBullet((BulletHitBulletEvent) e);
        } else if (e instanceof BulletMissedEvent) {
            basic.onBulletMissed((BulletMissedEvent) e);
        } else if (e instanceof HitRobotEvent) {
            basic.onHitRobot((HitRobotEvent) e);
        } else if (e instanceof HitWallEvent) {
            basic.onHitWall((HitWallEvent) e);
        } else if (e instanceof RobotDeathEvent) {
            basic.onRobotDeath((RobotDeathEvent) e);
        } else if (e instanceof DeathEvent) {
            basic.onDeath((DeathEvent) e);
        } else if (e instanceof WinEvent) {
            basic.onWin((WinEvent) e);
        }
        // Advanced events (e.g. SkippedTurnEvent) handled here when reconstructed.
        if (advanced != null) {
            // TODO: dispatch advanced events such as onSkippedTurn when applicable.
        }
    }
}
