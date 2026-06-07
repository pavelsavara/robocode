# Déjàvu — Testing & Validation

Déjàvu reconstructs a hero's events and commands purely from offline snapshots
(see [dejavu-design.md](dejavu-design.md)). The validation question is therefore:
**does the reconstruction match what the live engine actually did?** This document
describes how that is proven.

Validation has two tiers:

1. **Unit tests** — synthetic snapshots exercise each reconstruction mechanism in
   isolation against hand-computed expectations.
2. **Live fidelity gate** — real battles are run through the live engine; the
   engine's *ground truth* is captured and the reconstruction (driven from the
   same battle's snapshots) is scored against it.

---

## 1. The ground-truth oracle

Two post-physics snapshots cannot, by themselves, reveal everything the engine
did mid-turn (collapsed energy events, pre-bounce collision bearings, whether a
scan was actually delivered). So the engine was instrumented to expose that
hidden truth — **only while testing is enabled** — as the comparison oracle. It
is never an input to reconstruction.

- `TurnEndedEvent.getGroundTruthEvents()` — the exact `Event[]` the engine
  delivered to each robot this turn.
- `TurnEndedEvent.getGroundTruthCommands()` — each robot's realized
  `IExecCommands` (the actuator turns/move/fire it actually performed).
- `TurnEndedEvent.getGroundTruthStatus()` — the `RobotStatus` delivered to each
  robot.
- `IRobotSnapshot.getEnergyChanges()` (an `ITurnEnergyBreakdown`),
  `getRealizedVelocity()`, `getCollisions()` (`ICollisionSnapshot[]`),
  `isScanning()`, `wasTurnSkipped()`; `IBulletSnapshot.getVictimEnergyAtHit()`.

All of these return `null`/empty during normal play.

---

## 2. Capture harness — `BattleCaptureHarness`

A `RobotTestBed` that drives a deterministic 1v1 battle through the live engine
and records, for every turn, three **turn-aligned** artifacts kept in memory (no
`.br` round-trip):

1. the `ITurnSnapshot` stream (the reconstruction *input*);
2. the ground-truth **event** stream (the oracle);
3. the ground-truth realized-**command** stream (the oracle).

`SnapshotReplaySource` replays the captured snapshots back as the reconstruction
input, so the very stream the engine produced becomes the reconstructor's feed.

The dejavu module's test task runs the engine on **JDK 21** (so newer-bytecode
competitive robots load) and in **`debug=true`** mode, which gives robot threads
an unbounded per-turn budget. That removes skipped-turn races, making
end-of-round event capture deterministic and reproducible.

---

## 3. Scoring — `FidelityComparator` + `FidelityReport`

`FidelityComparator` compares the reconstructed per-tick `TickEvents`/
`TickCommands` against the recorded ground-truth streams and feeds the outcome
into a `FidelityReport` (a scored report, not a hard assert).

**Events** — the reconstructed list (the hero's own `DeathEvent` and the
host-side `StatusEvent` removed) must equal the ground-truth delivered events in
the engine's canonical dispatch order (time ascending, then default-priority
descending). Only turns where both robots are alive post-physics and neither is
mid-ram are scored; a surplus reconstructed scan on a zero-movement turn is
tolerated.

**Commands** — the realized body/gun/radar turns (forward-applied under the
always-false coupling assumption) must reproduce the snapshot headings within
`EPSILON`; the realized move must equal the snapshot velocity; the realized fire
must match the independent fresh-bullet oracle. `SKIPPED_TURN_SUSPECTED` ticks
are excluded from the denominator.

The comparator scores the **snapshot forward-apply error** (always ~0 by
construction — the authoritative ground truth) and, for actuators, enforces the
engine's per-tick physical **rate caps** and **saturation floor**: a
reconstructed own-turn must lie within its physical limit (else it throws — "we
don't care about an invalid engine"), and a saturated actuator (post-tick
remaining counter non-zero, same sign) must have realized its cap. The captured
remaining-counter is *not* used as an oracle — every actuator is treated as if
issued fresh each tick.

### Drift census

Where the reconstructor attached a `DriftReason` uncertainty flag, the comparator
challenges it against the oracle and records a `CONFIRMED` outcome (the oracle
proved the labeled cause) or an `ANOMALY` (it could not). The report tallies both
into "types of drift" tables. The comparator only *censuses* reconstructor flags
plus emits oracle outcomes — it never mints its own uncertainty flag.

---

## 4. The fidelity gate — `DejavuFidelityTest`

The slow, authoritative end-to-end gate. It runs live battles for **54 heroes**
(the 4 bundled samples + the rumble **top-50** competitive robots) against
`sample.Fire`, `GATE_ROUNDS = 5` rounds each, and scores every reconstructed tick.

| Test | What it proves |
|---|---|
| Phase 0 capture test | the oracle captures and turn-aligns the three artifacts; snapshots replay back as the input stream. (No reconstruction exercised.) |
| `reconstructsGroundTruthAtFullFidelityForAllHeroes` | the real fidelity gate — scores all 54 heroes and writes `DejavuFidelityTest.md` (in the module dir) with fidelity + drift tables. |

**Gold vs observed:**

- **Gold heroes** — the 4 bundled samples (`sample.Walls`, `sample.Fire`,
  `sample.Crazy`, `sample.SittingDuck`). These **must** reconstruct at full
  fidelity: overall rate `1.0`, no mismatches, no reconstruction error. This is
  asserted; it fails the build if broken.
- **Observed heroes** — the rumble top-50. Driven for coverage and reported
  per-hero, but **not asserted**: advanced bots legitimately exercise mechanics
  déjàvu does not yet fully model (e.g. `kc.mega.BeepBoop` decouples gun/radar
  from body, which is folded into the reported command under the always-false
  assumption). `scoreHero` is wrapped in try/catch; a thrown `EnergyLedger`
  residual or NPE is recorded as a per-hero error in the report's status table
  rather than failing the build. Roughly 47/54 reconstruct fully; a handful
  (Knight/Roborio/Tomcat/YersiniaPestis/Cyanide energy residuals, Gilgalad NPE,
  Saguaro event-field mismatches) are tolerated drift.

The top-50 jars are staged into `../.sandbox/test-robots` by the `stageTopRobots`
Gradle `Copy` task from `d:/robocode-autopilot/pipeline/build/battle-stage`
(overridable via the `battleStageDir` property). The task no-ops when the
directory is absent, so it never breaks a build that lacks the external jars —
the gate then runs the 4 gold samples only.

---

## 5. End-to-end façade gate — `DejavuReplayIntegrationTest`

Where `DejavuFidelityTest` drives the `Reconstructor` directly, this test
exercises the **shipped public entry point**. It captures a live battle for each
gold sample hero, replays the battle-started / round-started / turn-ended events
through a `Dejavu` instance wired to a recording `IAdvancedRobot` and a
`ReplayRobotPeer`, and asserts the façade-delivered reconstruction scores at full
fidelity (rate `1.0`, no mismatches) against the engine ground truth.

---

## 6. Unit tests

Synthetic-snapshot tests cover each mechanism in isolation (74 `@Test` methods
across the module):

| Suite | Focus | Tests |
|---|---|---|
| `BulletEventsTest` | `BulletHit` / `HitByBullet` / `BulletMissed` / `BulletHitBullet`, rising-edge dedup | 7 |
| `CollisionEventsTest` | `HitWall` / `HitRobot`, bearings, energy debits | 11 |
| `DeathWinEventsTest` | `RobotDeath` / `Win` / `Death`, post-death suppression | 7 |
| `EnergyLedgerTest` | energy decomposition, wall back-solve, inactivity zap | 10 |
| `EventOrderingTest` | engine dispatch order + priority tie-breaks | 3 |
| `FireAndSkippedTurnTest` | fire finalization, skipped-turn cues | 7 |
| `FireDetectionTest` | born-bullet fire detection + cross-checks | 7 |
| `MoveCommandTest` | realized move/turn inverse physics | 4 |
| `ScanEventReconstructionTest` | radar sweep gate, `SCAN_UNCERTAIN` | 8 |
| `StatusEventReconstructionTest` | per-tick `StatusEvent` | 4 |
| `core/GeometryTest` | angle normalization / arc intersection | 2 |
| `DejavuFidelityTest` | live capture + fidelity gate | 3 |
| `DejavuReplayIntegrationTest` | public façade end-to-end | 1 |

The harness stubs (`StubRobotSnapshot`, `StubBulletSnapshot`, `StubTurnSnapshot`,
`CapturedTurn`, `CommandRecord`, `EventRecord`) let the unit tests build precise
synthetic snapshots. Synthetic hero energies in these tests are made
**energy-faithful** — they reflect the strict `EnergyLedger` predictions (fire
costs, hit bonuses, ram/wall debits) rather than relaxing the ledger, because the
ledger's strictness is intentional and the live gate depends on it.

---

## 7. Running the tests

```powershell
# Full module suite (unit tests + live gates)
.\gradlew.bat :robocode.dejavu:test --console=plain

# A single suite
.\gradlew.bat :robocode.dejavu:test --console=plain --tests "net.sf.robocode.dejavu.BulletEventsTest"

# The fidelity gate only (slow — runs live battles, ~1-2 min)
.\gradlew.bat :robocode.dejavu:test --console=plain --tests "net.sf.robocode.dejavu.DejavuFidelityTest"
```

The fidelity gate writes `DejavuFidelityTest.md` into the module directory
(`robocode.dejavu/`) with the per-hero fidelity rates, the per-hero
reconstruction-status table, and the drift census ("types of drift") tables.

---

## 8. What "passing" means

- **Gold heroes reconstruct at 100% fidelity** with zero mismatches and no thrown
  reconstruction trap — asserted and build-failing.
- **Energy ledger never silently fudges** — an unreconcilable living tick throws,
  so a faithful capture is provably reconcilable and a non-faithful one is caught.
- **Uncertainty is honest** — every value that cannot be recovered exactly is
  flagged, and the harness either confirms the flagged cause against the oracle
  (`CONFIRMED`) or surfaces it for triage (`ANOMALY`); no flag is invented to hide
  a real disagreement.
- **Observed top-50 heroes are reported, not gated** — their residuals quantify
  how much of real competitive play the current model explains (~47/54 fully)
  without blocking the build on mechanics déjàvu does not yet model.
