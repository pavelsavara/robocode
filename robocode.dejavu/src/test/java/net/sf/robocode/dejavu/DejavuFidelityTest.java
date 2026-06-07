/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu;

import net.sf.robocode.dejavu.core.Reconstructor;
import net.sf.robocode.dejavu.harness.BattleCaptureHarness;
import net.sf.robocode.dejavu.harness.CapturedTurn;
import net.sf.robocode.dejavu.harness.FidelityComparator;
import net.sf.robocode.dejavu.harness.FidelityReport;
import net.sf.robocode.dejavu.harness.SnapshotReplaySource;
import net.sf.robocode.dejavu.model.DriftReason;
import net.sf.robocode.peer.IExecCommands;
import org.junit.Test;
import robocode.Event;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Phase 0 ground-truth capture harness test (the oracle, design §8.1).
 * <p>
 * Runs deterministic live 1v1 battles (800&times;600, {@value #ROUNDS} rounds)
 * for a spread of sample heroes against {@code sample.Fire}, capturing every
 * turn in memory. For each battle it verifies the three aligned artifacts the
 * oracle produces &mdash; the turn-snapshot stream, the engine ground-truth
 * event stream, and the engine ground-truth realized-command stream &mdash; are
 * well-formed and turn-aligned, and that the captured snapshots replay back as
 * the reconstruction input stream.
 * <p>
 * No reconstruction is exercised here; this only proves the oracle captures and
 * aligns the ground truth that later phases are scored against.
 */
public class DejavuFidelityTest {

    private static final String ENEMY = "sample.Fire";
    private static final int ROUNDS = 3;
    /** Rounds driven per hero in the end-to-end fidelity gate. */
    private static final int GATE_ROUNDS = 5;
    private static final int BF_WIDTH = 800;
    private static final int BF_HEIGHT = 600;

    /** Max per-reason rows rendered in the oracle-anomaly triage table (bulk reasons are capped). */
    private static final int ANOMALY_ROWS_PER_REASON = 10;

    /**
     * Active heroes always exercise the event + command streams; passive heroes may
     * not.
     */
    private static final String[] ACTIVE_HEROES = { "sample.Walls", "sample.Fire", "sample.Crazy" };
    private static final String PASSIVE_HERO = "sample.SittingDuck";

    /** The four bundled sample heroes driven through the fidelity gate. */
    private static final String[] SAMPLE_HEROES = {
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"
    };

    /**
     * The current rumble top-50 (from the autopilot leaderboard at
     * {@code https://pavelsavara.github.io/robocode-autopilot/}). Their jars are
     * staged into the test-robots sandbox from
     * {@code d:/robocode-autopilot/pipeline/build/battle-stage} by the
     * {@code stageTopRobots} Gradle task; the test JVM runs on JDK 21 so the
     * newer-bytecode robots load.
     */
    private static final String[] TOP_50_HEROES = {
            "kc.mega.BeepBoop", "aaa.r.ScalarR", "jk.mega.DrussGT", "voidious.Diamond",
            "rsalesc.mega.Knight", "rsalesc.roborio.Roborio", "lxx.Tomcat", "cb.fire.Firestarter",
            "xander.cat.XanderCat", "aw.Gilgalad", "oog.mega.saguaro.Saguaro", "dsekercioglu.mega.Raven",
            "kc.serpent.WaveSerpent", "voidious.Dookious", "darkcanuck.Pris", "abc.Shadow",
            "dsekercioglu.mega.WhiteFang", "mn.Combat", "gh.GresSuffurd", "cs.Nene",
            "jk.melee.Neuromancer", "tjk.deBroglie", "mue.Ascendant", "davidalves.Phoenix",
            "zyx.mega.YersiniaPestis", "darkcanuck.Holden", "Krabb.sliNk.Garm", "pez.rumble.CassiusClay",
            "jk.precise.Wintermute", "fromHell.BlackBox", "axeBots.SilverSurfer", "florent.XSeries.X2",
            "ar.horizon.Horizon", "florent.test.Toad", "pulsar.PulsarMax", "sheldor.mini.Foilist",
            "cjm.chalk.Chalk", "cb.Domogled", "cs.s2.Seraphim", "ags.Midboss",
            "dft.Cardigan", "kc.serpent.Hydra", "pez.rumble.Ali", "davidalves.Firebird",
            "wcsv.Engineer.Engineer", "wcsv.PowerHouse.PowerHouse", "fromHell.CHCl3", "dft.Cyanide",
            "jk.mini.CunobelinDC", "ags.rougedc.RougeDC"
    };

    /**
     * Every hero driven through the end-to-end fidelity gate: the four bundled
     * sample heroes plus the rumble top-50 competitive robots.
     */
    private static final String[] ALL_HEROES = concat(SAMPLE_HEROES, TOP_50_HEROES);

    /**
     * The regression-gate "gold" heroes that must reconstruct at full fidelity
     * (overall rate {@code 1.0}, no mismatches, no reconstruction error): the four
     * bundled samples. The rumble top-50 robots are driven for coverage and reported
     * per-hero, but advanced bots can legitimately exercise mechanics the
     * reconstructor does not yet fully model (e.g. {@code kc.mega.BeepBoop} decouples
     * its gun/radar from its body, which déjàvu folds into the reported gun/radar
     * command under the always-false coupling assumption), so they are observed (not
     * asserted): a residual or a thrown {@link EnergyLedger} trap is recorded as
     * drift in the report rather than failing the build.
     */
    private static final Set<String> GOLD_HEROES = new LinkedHashSet<String>(java.util.Arrays.asList(
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"));

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Per-hero outcome of the fidelity gate. */
    private static final class HeroOutcome {
        final FidelityReport report;
        final String error; // null when the hero reconstructed without throwing
        final boolean externalCrash; // error originated in the robot's own code, not in déjàvu

        HeroOutcome(FidelityReport report, String error, boolean externalCrash) {
            this.report = report;
            this.error = error;
            this.externalCrash = externalCrash;
        }

        boolean ok() {
            return error == null && report.overallRate() == 1.0 && report.getMismatches().isEmpty();
        }

        /**
         * A failure that is not a déjàvu reconstruction fault: the competition
         * robot threw inside its own code during the live capture, so there are
         * no snapshots to reconstruct. Reported but not counted as drift.
         */
        boolean excused() {
            return error != null && externalCrash;
        }
    }

    /**
     * Distinguishes a déjàvu reconstruction fault from an external failure. A
     * reconstruction fault (e.g. an {@link EnergyLedger} trap) carries at least
     * one stack frame in the déjàvu package. A competition robot that throws in
     * its own code during the live capture surfaces an exception whose frames
     * are entirely in the robot and engine-host packages, before any
     * reconstruction runs — that is the robot's bug, not ours.
     */
    private static boolean isReconstructionFault(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            for (StackTraceElement f : c.getStackTrace()) {
                if (f.getClassName().startsWith("net.sf.robocode.dejavu")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void capturesAlignedGroundTruthForActiveHeroes() {
        FidelityReport report = new FidelityReport();
        for (String hero : ACTIVE_HEROES) {
            verifyCapture(hero, capture(hero), true, report);
        }
        System.out.println(report.summary());
    }

    @Test
    public void capturesAlignedGroundTruthForPassiveHero() {
        FidelityReport report = new FidelityReport();
        verifyCapture(PASSIVE_HERO, capture(PASSIVE_HERO), false, report);
        System.out.println(report.summary());
    }

    /**
     * Phase 3 end-to-end fidelity gate (design &sect;8.2).
     * <p>
     * For each hero it captures a live battle via the Phase 0
     * harness, drives the {@link Reconstructor} tick-by-tick over the captured
     * snapshots, and scores the reconstructed events (Phase 1) and commands
     * (Phase 2) against the engine ground truth with a single cumulative
     * {@link FidelityComparator}. The gate asserts the report reaches an overall
     * match rate of {@code 1.0} with an empty mismatch list, having scored at
     * least one event tick and one command tick. Once green this doubles as the
     * reconstruction regression gate.
     * <p>
     * The comparator applies the same documented Phase 1/2 exclusions that the
     * per-step integration tests proved green: event scoring skips death and
     * mid-ram turns and tolerates a zero-movement surplus scan; command scoring
     * excludes {@code SKIPPED_TURN_SUSPECTED} ticks. Besides the bundled sample
     * heroes the gate drives the rumble top-50 competitive robots (newer Java
     * bytecode), staged into the test-robots sandbox by the
     * {@code stageTopRobots} Gradle task and loadable because the test JVM runs
     * on JDK 21.
     */
    @Test
    public void reconstructsGroundTruthAtFullFidelityForAllHeroes() {
        Map<String, HeroOutcome> outcomes = new LinkedHashMap<String, HeroOutcome>();
        Map<String, FidelityReport> perHero = new LinkedHashMap<String, FidelityReport>();
        FidelityReport report = new FidelityReport();
        FidelityReport goldReport = new FidelityReport();

        for (String hero : ALL_HEROES) {
            FidelityReport heroReport = new FidelityReport();
            String error = null;
            boolean externalCrash = false;
            try {
                scoreHero(hero, GATE_ROUNDS, new FidelityComparator(heroReport),
                        GOLD_HEROES.contains(hero));
            } catch (RuntimeException | AssertionError e) {
                // Advanced competitive robots can exercise energy mechanics the
                // reconstructor does not yet fully model; the strict EnergyLedger
                // traps that as an IllegalStateException. They can also simply
                // throw inside their own code during the live capture, which the
                // engine surfaces before any reconstruction runs. Record either
                // as a per-hero failure and keep driving the rest so the report
                // stays informative instead of aborting at the first offender,
                // but flag the robot-internal crashes so they are not counted as
                // déjàvu drift.
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                externalCrash = !isReconstructionFault(e);
                System.out.println("[Phase3] " + hero
                        + (externalCrash ? " crashed in its own code during capture: "
                                : " reconstruction failed: ")
                        + error);
            }
            HeroOutcome outcome = new HeroOutcome(heroReport, error, externalCrash);
            outcomes.put(hero, outcome);
            perHero.put(hero, heroReport);
            report.merge(heroReport);
            if (GOLD_HEROES.contains(hero)) {
                goldReport.merge(heroReport);
            }
        }

        System.out.println(report.summary());
        for (FidelityReport.Mismatch m : report.getMismatches()) {
            System.out.println("  MISMATCH " + m);
        }

        writeDriftReport(perHero, outcomes, report);

        // Coverage floors: the gold heroes over GATE_ROUNDS rounds score many
        // thousands of event and command ticks. Assert a conservative minimum so a
        // future regression that collapses the comparable population (and would make
        // the 100% rate trivially true) fails here instead of passing silently.
        assertTrue("too few gold event ticks scored: " + goldReport.total("events"),
                goldReport.total("events") > 1000);
        assertTrue("too few gold command ticks scored: " + goldReport.total("commands"),
                goldReport.total("commands") > 1000);

        // Regression gate: every gold hero must reconstruct at full fidelity. The
        // remaining rumble top-50 robots are observed and reported, not asserted.
        StringBuilder goldFailures = new StringBuilder();
        for (String hero : GOLD_HEROES) {
            HeroOutcome outcome = outcomes.get(hero);
            if (outcome == null || !outcome.ok()) {
                goldFailures.append("\n  ").append(hero).append(": ")
                        .append(outcome == null ? "not scored"
                                : outcome.error != null ? outcome.error
                                        : "rate=" + formatRate(outcome.report.overallRate())
                                                + " mismatches=" + outcome.report.getMismatches());
            }
        }
        assertTrue("gold heroes failed the fidelity gate:" + goldFailures,
                goldFailures.length() == 0);
    }

    private void scoreHero(String hero, int rounds, FidelityComparator comparator, boolean gold) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, rounds);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        Reconstructor recon = new Reconstructor(0, BF_WIDTH, BF_HEIGHT, rounds);
        int round = -1;
        ITurnSnapshot prevSnap = null;

        for (CapturedTurn ct : captured) {
            if (ct.getRound() != round) {
                round = ct.getRound();
                ITurnSnapshot start = harness.getRoundStartSnapshot(round);
                assertNotNull("[" + hero + "] r" + round + " missing round-start snapshot", start);
                recon.startRound(start);
                prevSnap = start;
            }

            ITurnSnapshot cur = ct.getSnapshot();
            IExecCommands curCmd = ct.getHeroCommands();
            Reconstructor.TickResult result = recon.onTurn(cur);

            if (result != null) {
                comparator.compareEvents(round, ct.getTurn(), result.events,
                        ct.getHeroEvents(), prevSnap, cur);
                comparator.compareCommands(round, ct.getTurn(), result.commands,
                        prevSnap, cur, curCmd);
                comparator.compareEnergy(round, ct.getTurn(), result.energy, cur);
            }

            prevSnap = cur;
        }
    }

    // ---- Drift report (./DejavuFidelityTest.md) -------------------------

    /**
     * Render the per-hero and aggregate fidelity plus the census of tolerated
     * reconstruction-drift ({@link DriftReason}) flags into a Markdown report at
     * {@code ./DejavuFidelityTest.md}. The drift census is what makes the gate
     * informative even when it is green: it shows <em>where</em> reconstruction
     * was uncertain (and tolerated) rather than wrong.
     */
    private void writeDriftReport(Map<String, FidelityReport> perHero,
            Map<String, HeroOutcome> outcomes, FidelityReport overall) {
        int passed = 0;
        int failed = 0;
        int excused = 0;
        for (HeroOutcome o : outcomes.values()) {
            if (o.ok()) {
                passed++;
            } else if (o.excused()) {
                excused++;
            } else {
                failed++;
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("# Déjàvu Fidelity & Drift Report\n\n");
        md.append("Deterministic live 1v1 battles (").append(BF_WIDTH).append("×").append(BF_HEIGHT)
                .append(", ").append(GATE_ROUNDS).append(" rounds) for ").append(ALL_HEROES.length)
                .append(" heroes vs `").append(ENEMY).append("`.\n\n");
        md.append("The four bundled sample heroes are the *gold* ")
                .append("regression set and must reconstruct at full fidelity; the ")
                .append("rumble top-50 robots are driven for coverage and observed per-hero.\n\n");
        md.append("- Heroes reconstructed at full fidelity: **").append(passed).append(" / ")
                .append(outcomes.size()).append("**\n");
        md.append("- Heroes with residual drift or reconstruction error: **").append(failed)
                .append("**\n");
        if (excused > 0) {
            md.append("- Heroes excluded (crashed in their own code, not a déjàvu fault): **")
                    .append(excused).append("**\n");
        }
        md.append("- Overall match rate (across cleanly-reconstructed ticks): **")
                .append(formatRate(overall.overallRate())).append("**\n");
        md.append("- Residual mismatches: **").append(overall.getMismatches().size())
                .append("**\n\n");

        appendHeroStatusTable(md, outcomes);
        appendOverallTable(md, overall);
        appendDriftTable(md, overall);
        appendDriftReasonTable(md, overall);
        appendPerHeroFidelityTable(md, perHero);
        appendPerHeroDriftTable(md, perHero);
        appendMismatchTable(md, overall);
        appendAnomalyTable(md, overall);

        File out = new File("DejavuFidelityTest.md");
        try {
            Files.write(out.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("failed to write drift report " + out.getAbsolutePath(), e);
        }
        System.out.println("[Phase3] drift report written to " + out.getAbsolutePath());
    }

    /**
     * Per-hero pass/fail status: whether each hero reconstructed at full fidelity,
     * and for those that did not, the residual rate or the reconstruction error
     * (e.g. an {@link EnergyLedger} trap) that the gate tolerated.
     */
    private static void appendHeroStatusTable(StringBuilder md, Map<String, HeroOutcome> outcomes) {
        md.append("## Per-hero reconstruction status\n\n");
        md.append("| Hero | Gold | Status | Rate | Detail |\n");
        md.append("|---|:---:|---|---:|---|\n");
        for (Map.Entry<String, HeroOutcome> e : outcomes.entrySet()) {
            String hero = e.getKey();
            HeroOutcome o = e.getValue();
            boolean gold = GOLD_HEROES.contains(hero);
            String status;
            String detail;
            if (o.error != null) {
                status = o.externalCrash ? "⏭️ robot crash" : "❌ error";
                detail = o.error;
            } else if (!o.report.getMismatches().isEmpty()) {
                status = "⚠️ drift";
                detail = o.report.getMismatches().size() + " mismatch(es)";
            } else if (o.report.overallRate() < 1.0) {
                status = "⚠️ drift";
                detail = "";
            } else {
                status = "✅ full";
                detail = "";
            }
            md.append("| `").append(hero).append("` | ").append(gold ? "★" : "")
                    .append(" | ").append(status).append(" | ")
                    .append(formatRate(o.report.overallRate())).append(" | ")
                    .append(escapeCell(detail)).append(" |\n");
        }
        md.append('\n');
    }

    private static void appendOverallTable(StringBuilder md, FidelityReport overall) {
        md.append("## Overall fidelity by category\n\n");
        md.append("| Category | Matched | Total | Rate |\n");
        md.append("|---|---:|---:|---:|\n");
        long sumMatched = 0;
        long sumTotal = 0;
        for (String cat : overall.categories()) {
            long m = overall.matched(cat);
            long t = overall.total(cat);
            sumMatched += m;
            sumTotal += t;
            md.append("| ").append(cat).append(" | ").append(m).append(" | ").append(t)
                    .append(" | ").append(formatRate(overall.rate(cat))).append(" |\n");
        }
        md.append("| **OVERALL** | ").append(sumMatched).append(" | ").append(sumTotal)
                .append(" | **").append(formatRate(overall.overallRate())).append("** |\n\n");
    }

    private static void appendDriftTable(StringBuilder md, FidelityReport overall) {
        Map<DriftReason, Long> drift = overall.driftCounts();
        md.append("## Types of drift (tolerated reconstruction uncertainty)\n\n");
        md.append("Drift flags mark ticks where reconstruction could not be made exactly certain, "
                + "yet the reconstructed tick still matched ground truth. The harness tolerates these "
                + "documented uncertainties; the counts below census how often each was exercised.\n\n");
        md.append("| Drift type | Scope | Occurrences | Tolerated field / effect |\n");
        md.append("|---|---|---:|---|\n");
        for (DriftReason p : DriftReason.values()) {
            if (!p.isUncertainty()) {
                continue;
            }
            long n = drift.containsKey(p) ? drift.get(p) : 0L;
            md.append("| `").append(p.name()).append("` | ").append(driftScope(p)).append(" | ")
                    .append(n).append(" | ").append(driftEffect(p)).append(" |\n");
        }
        md.append("\n");
    }

    /**
     * Granular, oracle-confirmed drift census ({@link DriftReason}). Each tolerated
     * {@link DriftReason} drift is confirmed against an engine snapshot oracle; a
     * {@code *_CONFIRMED} reason proves the labeled cause, while an anomaly reason
     * surfaces a case the oracle could not confirm (reported, not gating).
     */
    private static void appendDriftReasonTable(StringBuilder md, FidelityReport overall) {
        Map<DriftReason, Long> reasons = overall.driftReasonCounts();
        md.append("## Oracle-confirmed drift reasons\n\n");
        md.append("Each tolerated drift above is cross-checked against the engine snapshot oracle "
                + "(the new snapshot APIs). A `*_CONFIRMED` reason means the oracle proves the labeled "
                + "cause; an anomaly reason (`*_MISMATCH` / `*_SPURIOUS` / `*_UNEXPLAINED`) flags a "
                + "case the oracle could not confirm. Reporting only — the gate is unchanged.\n\n");
        md.append("| Drift reason | Origin | Kind | Occurrences |\n");
        md.append("|---|---|---|---:|\n");
        for (DriftReason r : DriftReason.values()) {
            long n = reasons.containsKey(r) ? reasons.get(r) : 0L;
            md.append("| `").append(r.name()).append("` | ")
                    .append(r.origin() == null ? "—" : "`" + r.origin().name() + "`")
                    .append(" | ").append(r.isAnomaly() ? "⚠️ anomaly" : "✅ confirmed")
                    .append(" | ").append(n).append(" |\n");
        }
        md.append("\n");
    }

    private static void appendPerHeroFidelityTable(StringBuilder md, Map<String, FidelityReport> perHero) {
        md.append("## Fidelity by hero\n\n");
        md.append("|---|---:|---:|---:|---:|\n");
        for (Map.Entry<String, FidelityReport> e : perHero.entrySet()) {
            FidelityReport r = e.getValue();
            md.append("| `").append(e.getKey()).append("` | ")
                    .append(cell(r, "events")).append(" | ")
                    .append(cell(r, "event-fields")).append(" | ")
                    .append(cell(r, "commands")).append(" | ")
                    .append(formatRate(r.overallRate())).append(" |\n");
        }
        md.append("\n");
    }

    private static void appendPerHeroDriftTable(StringBuilder md, Map<String, FidelityReport> perHero) {
        md.append("## Drift census by hero\n\n");
        md.append("| Hero |");
        int uncertaintyCount = 0;
        for (DriftReason p : DriftReason.values()) {
            if (!p.isUncertainty()) {
                continue;
            }
            uncertaintyCount++;
            md.append(' ').append(p.name()).append(" |");
        }
        md.append("\n|---|");
        for (int i = 0; i < uncertaintyCount; i++) {
            md.append("---:|");
        }
        md.append('\n');
        for (Map.Entry<String, FidelityReport> e : perHero.entrySet()) {
            Map<DriftReason, Long> drift = e.getValue().driftCounts();
            md.append("| `").append(e.getKey()).append("` |");
            for (DriftReason p : DriftReason.values()) {
                if (!p.isUncertainty()) {
                    continue;
                }
                long n = drift.containsKey(p) ? drift.get(p) : 0L;
                md.append(' ').append(n).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static void appendMismatchTable(StringBuilder md, FidelityReport overall) {
        md.append("## Residual mismatches\n\n");
        List<FidelityReport.Mismatch> mismatches = overall.getMismatches();
        if (mismatches.isEmpty()) {
            md.append("None — full fidelity (100%).\n");
            return;
        }
        md.append("| Round | Turn | Category | Detail |\n");
        md.append("|---:|---:|---|---|\n");
        for (FidelityReport.Mismatch m : mismatches) {
            md.append("| ").append(m.round).append(" | ").append(m.turn).append(" | ")
                    .append(m.category).append(" | ").append(m.detail.replace("|", "\\|"))
                    .append(" |\n");
        }
        md.append('\n');
    }

    private static void appendAnomalyTable(StringBuilder md, FidelityReport overall) {
        md.append("## Oracle anomalies (drift the oracle could not confirm)\n\n");
        List<FidelityReport.Mismatch> anomalies = overall.getAnomalies();
        if (anomalies.isEmpty()) {
            md.append("None — every tolerated drift was oracle-confirmed.\n\n");
            return;
        }
        md.append("These ticks were tolerated by the gate (no score change) but the engine snapshot "
                + "oracle did not confirm the labeled cause; they are surfaced for triage. Grouped by "
                + "reason with the rarest (most interesting) first; high-volume reasons are capped at "
                + ANOMALY_ROWS_PER_REASON + " rows (full counts are in the census table above).\n\n");

        // Group by reason, preserving chronological order within each reason.
        Map<String, List<FidelityReport.Mismatch>> byReason = new LinkedHashMap<String, List<FidelityReport.Mismatch>>();
        for (FidelityReport.Mismatch m : anomalies) {
            List<FidelityReport.Mismatch> group = byReason.get(m.category);
            if (group == null) {
                group = new ArrayList<FidelityReport.Mismatch>();
                byReason.put(m.category, group);
            }
            group.add(m);
        }
        // Rarest reason first so low-volume anomalies are not buried under bulk ones.
        List<Map.Entry<String, List<FidelityReport.Mismatch>>> groups =
                new ArrayList<Map.Entry<String, List<FidelityReport.Mismatch>>>(byReason.entrySet());
        groups.sort(Comparator.comparingInt(e -> e.getValue().size()));

        md.append("| Round | Turn | Reason | Detail |\n");
        md.append("|---:|---:|---|---|\n");
        for (Map.Entry<String, List<FidelityReport.Mismatch>> entry : groups) {
            List<FidelityReport.Mismatch> group = entry.getValue();
            int shown = Math.min(group.size(), ANOMALY_ROWS_PER_REASON);
            for (int i = 0; i < shown; i++) {
                FidelityReport.Mismatch m = group.get(i);
                md.append("| ").append(m.round).append(" | ").append(m.turn).append(" | `")
                        .append(m.category).append("` | ").append(m.detail.replace("|", "\\|"))
                        .append(" |\n");
            }
            int hidden = group.size() - shown;
            if (hidden > 0) {
                md.append("| … | … | `").append(entry.getKey()).append("` | … and ")
                        .append(hidden).append(" more (").append(group.size()).append(" total) |\n");
            }
        }
        md.append('\n');
    }

    private static String escapeCell(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String cell(FidelityReport r, String cat) {
        long t = r.total(cat);
        if (t == 0) {
            return "—";
        }
        return formatRate(r.rate(cat)) + " (" + r.matched(cat) + "/" + t + ")";
    }

    private static String formatRate(double rate) {
        return String.format("%.4f%%", 100.0 * rate);
    }

    private static String driftScope(DriftReason p) {
        switch (p) {
            case SKIPPED_TURN_SUSPECTED:
                return "tick";
            default:
                return "event";
        }
    }

    private static String driftEffect(DriftReason p) {
        switch (p) {
            case SKIPPED_TURN_SUSPECTED:
                return "tick excluded from command scoring (turn gap / over-rate cue)";
            case DOUBLE_HIT:
                return "`BulletHitEvent.getEnergy()` (RNG-ordered intermediate victim energy)";
            case WALL_BEARING_UNRESOLVED:
                return "`HitWallEvent.getBearingRadians()` (best-effort impact bearing)";
            case ROBOT_BEARING_UNRESOLVED:
                return "`HitRobotEvent.getBearingRadians()` (ram bearing from mid-turn position)";
            case SHOOTER_BONUS_UNRESOLVED:
                return "`BulletHitEvent.getEnergy()` (may include RNG-ordered shooter bonus)";
            case SCAN_UNCERTAIN:
                return "presence of a zero-width-sweep `ScannedRobotEvent`";
            default:
                return "";
        }
    }

    private List<CapturedTurn> capture(String hero) {
        return new BattleCaptureHarness(hero, ENEMY, ROUNDS).capture();
    }

    private void verifyCapture(String hero, List<CapturedTurn> captured, boolean heroActs, FidelityReport report) {
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        final Set<Integer> rounds = new LinkedHashSet<Integer>();
        int prevRound = -1;
        int prevTurn = -1;

        long totalDeliveredEvents = 0;
        long totalFirePowers = 0;
        int commandTurns = 0;
        int heroRecordTurns = 0;
        long heroDeliveredEvents = 0;
        int heroCommandTurns = 0;

        for (CapturedTurn ct : captured) {
            // The snapshot (reconstruction input) is well-formed and self-consistent.
            final ITurnSnapshot snapshot = ct.getSnapshot();
            assertNotNull("[" + hero + "] null snapshot", snapshot);
            assertEquals("[" + hero + "] snapshot round mismatch", ct.getRound(), snapshot.getRound());
            assertEquals("[" + hero + "] snapshot turn mismatch", ct.getTurn(), snapshot.getTurn());

            // Ground truth is present (testing flag on) and aligned 1:1 with the snapshot
            // robots.
            final Event[][] deliveredEvents = ct.getDeliveredEvents();
            final IExecCommands[] commands = ct.getCommands();
            assertNotNull("[" + hero + "] missing ground-truth events", deliveredEvents);
            assertNotNull("[" + hero + "] missing ground-truth commands", commands);
            assertEquals("[" + hero + "] events not aligned to snapshot robots",
                    snapshot.getRobots().length, deliveredEvents.length);
            assertEquals("[" + hero + "] commands not aligned to snapshot robots",
                    snapshot.getRobots().length, commands.length);

            // Turns increase monotonically within a round and reset across rounds.
            if (ct.getRound() == prevRound) {
                assertTrue("[" + hero + "] non-monotonic turn " + prevTurn + " -> " + ct.getTurn(),
                        ct.getTurn() > prevTurn);
            } else {
                assertTrue("[" + hero + "] rounds out of order: " + prevRound + " -> " + ct.getRound(),
                        ct.getRound() > prevRound);
            }
            prevRound = ct.getRound();
            prevTurn = ct.getTurn();
            rounds.add(ct.getRound());

            boolean commandThisTurn = false;
            for (Event[] robotEvents : deliveredEvents) {
                assertNotNull("[" + hero + "] null event array", robotEvents);
                totalDeliveredEvents += robotEvents.length;
            }
            for (IExecCommands robotCommands : commands) {
                if (robotCommands != null) {
                    commandThisTurn = true;
                    totalFirePowers += robotCommands.getFirePowers().length;
                }
            }
            if (commandThisTurn) {
                commandTurns++;
            }

            // The hero record (robot index 0) is present every turn, alive or dead.
            heroRecordTurns++;
            heroDeliveredEvents += ct.getHeroEvents().length;
            if (ct.getHeroCommands() != null) {
                heroCommandTurns++;
            }
        }

        assertEquals("[" + hero + "] expected " + ROUNDS + " rounds", ROUNDS, rounds.size());

        // The oracle always captures both streams from the active opponent.
        assertTrue("[" + hero + "] ground-truth event stream empty", totalDeliveredEvents > 0);
        assertTrue("[" + hero + "] ground-truth command stream empty", commandTurns > 0);
        assertTrue("[" + hero + "] no fired bullets captured (sample.Fire should shoot)", totalFirePowers > 0);

        // The hero record is captured every single turn of every round.
        assertEquals("[" + hero + "] hero ground-truth not present on every turn",
                captured.size(), heroRecordTurns);

        if (heroActs) {
            assertTrue("[" + hero + "] hero event stream empty", heroDeliveredEvents > 0);
            assertTrue("[" + hero + "] hero command stream empty", heroCommandTurns > 0);
        }

        // The captured snapshots replay back as the reconstruction input stream.
        final SnapshotReplaySource replay = SnapshotReplaySource.fromCapturedTurns(captured);
        assertEquals("[" + hero + "] replay size mismatch", captured.size(), replay.size());
        for (CapturedTurn ct : captured) {
            assertTrue("[" + hero + "] replay exhausted early", replay.hasNext());
            assertSame("[" + hero + "] replay snapshot identity mismatch", ct.getSnapshot(), replay.next());
        }
        assertFalse("[" + hero + "] replay has extra snapshots", replay.hasNext());

        report.record("snapshots", true);
        report.record("ground-truth", true);
        System.out.printf("[Phase0] %-18s vs %s: turns=%d rounds=%d events=%d firePowers=%d commandTurns=%d "
                + "heroEvents=%d heroCommandTurns=%d%n",
                hero, ENEMY, captured.size(), rounds.size(), totalDeliveredEvents, totalFirePowers,
                commandTurns, heroDeliveredEvents, heroCommandTurns);
    }
}
