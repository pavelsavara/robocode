/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;

/**
 * Geometry helpers mirroring the engine: radar scan arc, wall bearing, robot
 * bounding boxes and angle normalization. Stateless.
 */
public final class Geometry {

    /** Robot bounding box side length in pixels. */
    public static final double ROBOT_SIZE = 36.0;

    /** Radar scan radius in pixels (engine RADAR_SCAN_RADIUS). */
    public static final double SCAN_RADIUS = 1200.0;

    private Geometry() {
    }

    /** Normalize an angle to the range [-PI, +PI). */
    public static double normalRelativeAngle(double angle) {
        double a = angle % (2 * Math.PI);
        if (a >= Math.PI) {
            a -= 2 * Math.PI;
        } else if (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }

    /** Normalize an angle to the range [0, 2*PI). */
    public static double normalAbsoluteAngle(double angle) {
        double a = angle % (2 * Math.PI);
        return a >= 0 ? a : a + 2 * Math.PI;
    }

    /** Axis-aligned 36x36 bounding box centered on (x, y). */
    public static Rectangle2D.Double robotBox(double x, double y) {
        double half = ROBOT_SIZE / 2;
        return new Rectangle2D.Double(x - half, y - half, ROBOT_SIZE, ROBOT_SIZE);
    }

    /**
     * Build the PIE scan arc swept from {@code prevRadarHeading} to
     * {@code radarHeading} centered at (x, y).
     */
    public static Arc2D.Double scanArc(double x, double y, double prevRadarHeading, double scanRadians) {
        double startAngle = normalAbsoluteAngle(prevRadarHeading - Math.PI / 2);
        double r = SCAN_RADIUS;
        return new Arc2D.Double(x - r, y - r, 2 * r, 2 * r,
                Math.toDegrees(startAngle), Math.toDegrees(scanRadians), Arc2D.PIE);
    }

    /**
     * True if the scan arc intersects the target rectangle (matches engine test).
     */
    public static boolean arcIntersects(Arc2D arc, Rectangle2D rect) {
        return rect.intersectsLine(arc.getCenterX(), arc.getCenterY(),
                arc.getStartPoint().getX(), arc.getStartPoint().getY())
                || arc.intersects(rect);
    }
}
