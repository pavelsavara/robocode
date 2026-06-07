/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

/**
 * The single source of truth for "did the hero fire this turn", shared by the
 * event, energy and command reconstructors so the born-bullet detection has one
 * implementation rather than three (design: solve each result once).
 * <p>
 * A {@code setFire(power)} issued on turn {@code T-1} first materializes as a
 * fresh hero-owned bullet in the turn-{@code T} snapshot &mdash; identified by a
 * bullet id absent from the turn-{@code T-1} snapshot. The engine fires at most
 * one bullet per robot per turn, so there is at most one such bullet; its
 * {@link IBulletSnapshot#getPower()} is the realized, engine-clamped fire power.
 */
final class FireDetector {

    private FireDetector() {
    }

    /**
     * The hero-owned bullet born on the turn captured by {@code cur} (its id is
     * absent from {@code prev}), or {@code null} when the hero did not fire.
     *
     * @param prev          snapshot of turn T-1 (the pre-fire bullet baseline)
     * @param cur           snapshot of turn T (carries the born bullet)
     * @param heroOwnerIndex the bullet owner index identifying the hero's bullets
     */
    static IBulletSnapshot bornHeroBullet(ITurnSnapshot prev, ITurnSnapshot cur, int heroOwnerIndex) {
        IBulletSnapshot[] curBullets = cur.getBullets();
        if (curBullets == null) {
            return null;
        }
        for (IBulletSnapshot bullet : curBullets) {
            if (bullet.getOwnerIndex() == heroOwnerIndex
                    && !hasBullet(prev, heroOwnerIndex, bullet.getBulletId())) {
                return bullet;
            }
        }
        return null;
    }

    /**
     * Whether {@code snapshot} already carries a bullet with the given owner and
     * id. Bullet ids are numbered per owner (each robot numbers its own bullets),
     * so the owner must be matched as well as the id &mdash; otherwise a hero
     * bullet whose id coincides with a prior opponent bullet's id would be
     * wrongly treated as already present and the fire missed.
     */
    private static boolean hasBullet(ITurnSnapshot snapshot, int ownerIndex, int bulletId) {
        IBulletSnapshot[] bullets = snapshot.getBullets();
        if (bullets == null) {
            return false;
        }
        for (IBulletSnapshot bullet : bullets) {
            if (bullet.getOwnerIndex() == ownerIndex && bullet.getBulletId() == bulletId) {
                return true;
            }
        }
        return false;
    }
}
