/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.model;

import robocode.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The events reconstructed for a single turn, in engine dispatch order
 * (time ascending, then descending {@code DEFAULT_PRIORITY}), plus provenance
 * flags describing reconstruction confidence for that tick.
 * <p>
 * DriftReason comes at two granularities. <em>Tick-level</em> flags (returned by
 * {@link #getFlags()}) concern the whole turn. <em>Event-level</em> flags
 * (returned by {@link #flagsFor(Event)}) concern a single reconstructed event;
 * they are also folded into the tick-level set so a coarse consumer can detect
 * them with {@link #hasFlag(DriftReason)}, while a precise consumer can apply a
 * targeted per-event exclusion with {@link #hasFlag(Event, DriftReason)}.
 */
public final class TickEvents {

    private final long turn;
    private final List<Event> events;
    private final EnumSet<DriftReason> flags;
    private final Map<Event, EnumSet<DriftReason>> eventFlags;

    public TickEvents(long turn, List<Event> events, EnumSet<DriftReason> flags) {
        this(turn, events, flags, null);
    }

    public TickEvents(long turn, List<Event> events, EnumSet<DriftReason> flags,
            Map<Event, EnumSet<DriftReason>> eventFlags) {
        this.turn = turn;
        this.events = new ArrayList<Event>(events);

        EnumSet<DriftReason> tickFlags = flags == null
                ? EnumSet.noneOf(DriftReason.class) : EnumSet.copyOf(flags);

        this.eventFlags = new IdentityHashMap<Event, EnumSet<DriftReason>>();
        if (eventFlags != null) {
            for (Map.Entry<Event, EnumSet<DriftReason>> e : eventFlags.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    EnumSet<DriftReason> copy = EnumSet.copyOf(e.getValue());
                    this.eventFlags.put(e.getKey(), copy);
                    // Fold event-level flags into the tick-level union.
                    tickFlags.addAll(copy);
                }
            }
        }
        this.flags = tickFlags;
    }

    public long getTurn() {
        return turn;
    }

    /** Events in engine dispatch order. */
    public List<Event> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public EnumSet<DriftReason> getFlags() {
        return flags;
    }

    public boolean hasFlag(DriftReason flag) {
        return flags.contains(flag);
    }

    /** Add a tick-level provenance flag after construction. */
    public void addTickFlag(DriftReason flag) {
        flags.add(flag);
    }

    /**
     * The event-level provenance flags attached to {@code event}, or an empty
     * set if it carries none. The returned set is a copy and safe to mutate.
     */
    public EnumSet<DriftReason> flagsFor(Event event) {
        EnumSet<DriftReason> f = eventFlags.get(event);
        return f == null ? EnumSet.noneOf(DriftReason.class) : EnumSet.copyOf(f);
    }

    /** Whether {@code event} carries the given event-level provenance flag. */
    public boolean hasFlag(Event event, DriftReason flag) {
        EnumSet<DriftReason> f = eventFlags.get(event);
        return f != null && f.contains(flag);
    }
}
