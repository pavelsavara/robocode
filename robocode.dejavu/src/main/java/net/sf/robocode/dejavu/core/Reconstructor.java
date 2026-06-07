/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.core;

import net.sf.robocode.dejavu.model.EnergyBreakdown;
import net.sf.robocode.dejavu.model.TickCommands;
import net.sf.robocode.dejavu.model.TickEvents;
import robocode.control.snapshot.ITurnSnapshot;

/**
 * Per-round orchestrator that wires together event, command and energy
 * reconstruction. Holds the previous snapshot and feeds consecutive ticks.
 */
public final class Reconstructor {

    private final int heroIndex;
    private final EventReconstructor eventReconstructor;
    private final CommandReconstructor commandReconstructor;

    private ITurnSnapshot prev;

    public Reconstructor(int heroIndex, double battlefieldWidth, double battlefieldHeight, int numRounds) {
        this.heroIndex = heroIndex;
        this.eventReconstructor = new EventReconstructor(heroIndex, battlefieldWidth, battlefieldHeight, numRounds);
        this.commandReconstructor = new CommandReconstructor(heroIndex);
    }

    /** Begin a new round, seeding from the spawn snapshot. */
    public void startRound(ITurnSnapshot start) {
        eventReconstructor.resetRound();
        eventReconstructor.seedRoundStart(start);
        prev = start;
    }

    /** Result bundle for one reconstructed tick. */
    public static final class TickResult {
        public final TickEvents events;
        public final TickCommands commands;
        public final EnergyBreakdown energy;

        TickResult(TickEvents events, TickCommands commands, EnergyBreakdown energy) {
            this.events = events;
            this.commands = commands;
            this.energy = energy;
        }
    }

    /**
     * Reconstruct the tick captured by {@code cur} relative to the stored
     * previous snapshot, then advance the baseline.
     *
     * @return reconstructed events, commands and energy decomposition, or
     *         {@code null} if there is no baseline yet
     */
    public TickResult onTurn(ITurnSnapshot cur) {
        if (prev == null) {
            prev = cur;
            return null;
        }
        TickEvents events = eventReconstructor.reconstruct(prev, cur);
        EnergyBreakdown energy = eventReconstructor.getLastEnergyBreakdown();
        // Split the realized turns under the always-false coupling assumption.
        TickCommands commands = commandReconstructor.reconstruct(prev, cur);
        prev = cur;
        return new TickResult(events, commands, energy);
    }

    public int getHeroIndex() {
        return heroIndex;
    }
}
