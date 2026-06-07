/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import robocode.Event;

/**
 * A single ground-truth event delivered to the hero's listener, tagged with the
 * turn on which it was delivered. Used as the ground-truth event stream when
 * tee-wrapping a live robot's event listener.
 */
public final class EventRecord {

    private final long turn;
    private final Event event;

    public EventRecord(long turn, Event event) {
        this.turn = turn;
        this.event = event;
    }

    public long getTurn() {
        return turn;
    }

    public Event getEvent() {
        return event;
    }

    @Override
    public String toString() {
        return "EventRecord{turn=" + turn + ", event=" + event.getClass().getSimpleName() + '}';
    }
}
