/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the stateless {@link Geometry} angle helpers. These are
 * runnable now and pin the normalization contract the reconstructors rely on.
 */
public class GeometryTest {

    private static final double EPS = 1e-9;

    @Test
    public void normalRelativeAngleWrapsIntoMinusPiToPi() {
        assertEquals(0.0, Geometry.normalRelativeAngle(2 * Math.PI), EPS);
        assertEquals(-Math.PI / 2, Geometry.normalRelativeAngle(3 * Math.PI / 2), EPS);
        assertEquals(Math.PI / 4, Geometry.normalRelativeAngle(Math.PI / 4 + 2 * Math.PI), EPS);
    }

    @Test
    public void normalAbsoluteAngleWrapsIntoZeroToTwoPi() {
        assertEquals(0.0, Geometry.normalAbsoluteAngle(0.0), EPS);
        assertEquals(3 * Math.PI / 2, Geometry.normalAbsoluteAngle(-Math.PI / 2), EPS);
        double a = Geometry.normalAbsoluteAngle(5 * Math.PI);
        assertTrue(a >= 0 && a < 2 * Math.PI);
        assertEquals(Math.PI, a, EPS);
    }
}
