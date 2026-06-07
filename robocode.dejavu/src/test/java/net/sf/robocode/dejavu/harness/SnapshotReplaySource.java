/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import robocode.control.snapshot.ITurnSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Replays a captured in-memory snapshot stream as the reconstruction input,
 * standing in for the {@code .br} record reader. Yields the turn snapshots in
 * battle order so that an offline {@code Dejavu} reconstruction can be driven
 * from the same snapshots the {@link BattleCaptureHarness} captured live.
 */
public final class SnapshotReplaySource {

    private final List<ITurnSnapshot> snapshots;
    private int index;

    public SnapshotReplaySource(List<ITurnSnapshot> snapshots) {
        this.snapshots = new ArrayList<ITurnSnapshot>(snapshots);
    }

    /** Builds a replay source from the snapshots of the given captured turns. */
    public static SnapshotReplaySource fromCapturedTurns(List<CapturedTurn> capturedTurns) {
        List<ITurnSnapshot> snapshots = new ArrayList<ITurnSnapshot>(capturedTurns.size());
        for (CapturedTurn captured : capturedTurns) {
            snapshots.add(captured.getSnapshot());
        }
        return new SnapshotReplaySource(snapshots);
    }

    public boolean hasNext() {
        return index < snapshots.size();
    }

    public ITurnSnapshot next() {
        if (!hasNext()) {
            throw new NoSuchElementException("no more snapshots");
        }
        return snapshots.get(index++);
    }

    public int size() {
        return snapshots.size();
    }

    public void reset() {
        index = 0;
    }
}
