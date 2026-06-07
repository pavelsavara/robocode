/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.harness;

import net.sf.robocode.dejavu.model.DriftReason;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates per-category match/total counts and a list of mismatches while
 * comparing reconstructed events/commands against ground truth. Produces a
 * scored report (the iterative fidelity gate described in design §8.2), not a
 * hard assert.
 */
public final class FidelityReport {

    /** One unmatched item, keyed by (round, turn), with a human-readable reason. */
    public static final class Mismatch {
        public final int round;
        public final long turn;
        public final String category;
        public final String detail;

        public Mismatch(int round, long turn, String category, String detail) {
            this.round = round;
            this.turn = turn;
            this.category = category;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return "[round=" + round + ", turn=" + turn + "] " + category + ": " + detail;
        }
    }

    private final Map<String, long[]> counters = new LinkedHashMap<String, long[]>();
    private final List<Mismatch> mismatches = new ArrayList<Mismatch>();
    private final Map<DriftReason, long[]> driftCounts = new EnumMap<DriftReason, long[]>(DriftReason.class);
    private final Map<DriftReason, long[]> driftReasonCounts =
            new EnumMap<DriftReason, long[]>(DriftReason.class);
    private final List<Mismatch> anomalies = new ArrayList<Mismatch>();

    /** Record a comparison outcome for a category (matched true/false). */
    public void record(String category, boolean matched) {
        long[] c = counters.get(category);
        if (c == null) {
            c = new long[2];
            counters.put(category, c);
        }
        c[1]++;
        if (matched) {
            c[0]++;
        }
    }

    public void addMismatch(int round, long turn, String category, String detail) {
        mismatches.add(new Mismatch(round, turn, category, detail));
        record(category, false);
    }

    /**
     * Census one observed drift flag (a {@link DriftReason} marker the comparator
     * tolerated on a scored tick). This drives the "types of drift" report &mdash;
     * it counts where reconstruction was uncertain but still matched ground truth.
     */
    public void recordDrift(DriftReason flag) {
        long[] c = driftCounts.get(flag);
        if (c == null) {
            c = new long[1];
            driftCounts.put(flag, c);
        }
        c[0]++;
    }

    /** Census every drift flag in the given set. */
    public void recordDrift(java.util.Set<DriftReason> flags) {
        for (DriftReason flag : flags) {
            recordDrift(flag);
        }
    }

    /**
     * Census one oracle-confirmed {@link DriftReason}, the granular refinement of
     * a tolerated {@link DriftReason} drift (design §2). This is additive to the
     * coarse {@link #recordDrift(DriftReason)} roll-up: callers keep recording the
     * bare {@code DriftReason} so the existing "types of drift" table is unchanged,
     * and additionally record the granular reason here. An
     * {@link DriftReason#isAnomaly() anomaly} reason is also appended to the
     * anomaly list for triage.
     */
    public void recordDriftReason(DriftReason reason, int round, long turn, String detail) {
        long[] c = driftReasonCounts.get(reason);
        if (c == null) {
            c = new long[1];
            driftReasonCounts.put(reason, c);
        }
        c[0]++;
        if (reason.isAnomaly()) {
            anomalies.add(new Mismatch(round, turn, reason.name(), detail));
        }
    }

    /** Fold another report's counters, drift census and mismatches into this one. */
    public void merge(FidelityReport other) {
        for (Map.Entry<String, long[]> e : other.counters.entrySet()) {
            long[] c = counters.get(e.getKey());
            if (c == null) {
                c = new long[2];
                counters.put(e.getKey(), c);
            }
            c[0] += e.getValue()[0];
            c[1] += e.getValue()[1];
        }
        for (Map.Entry<DriftReason, long[]> e : other.driftCounts.entrySet()) {
            long[] c = driftCounts.get(e.getKey());
            if (c == null) {
                c = new long[1];
                driftCounts.put(e.getKey(), c);
            }
            c[0] += e.getValue()[0];
        }
        for (Map.Entry<DriftReason, long[]> e : other.driftReasonCounts.entrySet()) {
            long[] c = driftReasonCounts.get(e.getKey());
            if (c == null) {
                c = new long[1];
                driftReasonCounts.put(e.getKey(), c);
            }
            c[0] += e.getValue()[0];
        }
        mismatches.addAll(other.mismatches);
        anomalies.addAll(other.anomalies);
    }

    /** Category names in the order they were first recorded. */
    public java.util.Set<String> categories() {
        return counters.keySet();
    }

    /** Observed drift-flag census: flag &rarr; number of tolerated occurrences. */
    public Map<DriftReason, Long> driftCounts() {
        Map<DriftReason, Long> out = new EnumMap<DriftReason, Long>(DriftReason.class);
        for (Map.Entry<DriftReason, long[]> e : driftCounts.entrySet()) {
            out.put(e.getKey(), e.getValue()[0]);
        }
        return out;
    }

    /** Granular oracle-confirmed drift census: {@link DriftReason} &rarr; occurrences. */
    public Map<DriftReason, Long> driftReasonCounts() {
        Map<DriftReason, Long> out = new EnumMap<DriftReason, Long>(DriftReason.class);
        for (Map.Entry<DriftReason, long[]> e : driftReasonCounts.entrySet()) {
            out.put(e.getKey(), e.getValue()[0]);
        }
        return out;
    }

    /** Oracle anomalies (drift the oracle could not confirm), reported for triage. */
    public List<Mismatch> getAnomalies() {
        return anomalies;
    }

    public long matched(String category) {
        long[] c = counters.get(category);
        return c == null ? 0 : c[0];
    }

    public long total(String category) {
        long[] c = counters.get(category);
        return c == null ? 0 : c[1];
    }

    public double rate(String category) {
        long t = total(category);
        return t == 0 ? 1.0 : (double) matched(category) / t;
    }

    /** Overall match rate across all categories. */
    public double overallRate() {
        long m = 0;
        long t = 0;
        for (long[] c : counters.values()) {
            m += c[0];
            t += c[1];
        }
        return t == 0 ? 1.0 : (double) m / t;
    }

    public List<Mismatch> getMismatches() {
        return mismatches;
    }

    /** Render a human-readable summary with per-category percentages. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Déjàvu fidelity report\n");
        for (Map.Entry<String, long[]> e : counters.entrySet()) {
            long[] c = e.getValue();
            sb.append(String.format("  %-20s %d/%d (%.4f%%)%n",
                    e.getKey(), c[0], c[1], 100.0 * (c[1] == 0 ? 1.0 : (double) c[0] / c[1])));
        }
        sb.append(String.format("  %-20s %.4f%%%n", "OVERALL", 100.0 * overallRate()));
        sb.append("  mismatches: ").append(mismatches.size());
        return sb.toString();
    }
}
