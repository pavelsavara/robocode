# Déjàvu — Design

`robocode.dejavu` reconstructs, from offline turn snapshots, the exact stream of
**events** a single "hero" robot received each tick and the **realized commands**
it emitted, then replays them onto a target robot. It lets you re-run a recorded
battle from one robot's point of view without the live engine.

This document explains *how it works*. For how it is validated, see
[dejavu-testing.md](dejavu-testing.md).

---

## 1. Scope and guarantees

- **Battle shape:** standard 1v1 on a fixed battlefield (default 800×600). One
  `Dejavu` instance per hero; the opponent is reconstructed by a second instance.
- **Input:** the ordinary per-turn `ITurnSnapshot` stream (the same data a `.br`
  recording carries) plus the `BattleRules`. Reconstruction is **snapshot-pure**:
  it never consults the live engine and never reads any privileged ground truth.
- **Output per tick:** a `TickResult` bundling
  - `TickEvents` — the events delivered to the hero, in engine dispatch order;
  - `TickCommands` — the realized body/gun/radar turns, move distance, and fire;
  - `EnergyBreakdown` — the decomposition of the hero's energy change.
- **Uncertainty is explicit:** wherever a value cannot be recovered exactly from
  two post-physics snapshots, the reconstructor attaches a `DriftReason`
  uncertainty flag rather than guessing silently.

The key timing fact the whole module is built on: **the snapshot for turn `T`
captures the post-physics state of turn `T`**, so the events delivered on turn
`T` are derived from the pair `prev = snapshot[T-1]`, `cur = snapshot[T]`.

---

## 2. Component map

```
Dejavu (IBattleListener)
  └─ Reconstructor                  per-round orchestrator, holds prev snapshot
       ├─ EventReconstructor        diffs prev→cur into the hero's Event stream
       │    ├─ FireDetector         "did the hero fire this turn" (shared)
       │    ├─ EnergyLedger         per-tick energy decomposition + consistency check
       │    └─ Geometry / Physics   stateless engine-physics helpers
       └─ CommandReconstructor      inverse physics prev→cur into realized commands

Replay side (drives the reconstructed result back into a robot):
  RobotReplayDriver   delivers TickEvents to an IAdvancedRobot's listeners
  PeerReplayDriver    records TickCommands onto a fake peer
  ReplayRobotPeer     state-backed IAdvancedRobotPeer (getters from snapshot)
```

Model objects (`net.sf.robocode.dejavu.model`): `TickEvents`, `TickCommands`,
`EnergyBreakdown`, `FireDetection`, `DriftReason`, `BattleEndEvent`.

---

## 3. Top-level flow — `Dejavu`

`Dejavu` is a `BattleAdaptor` (an `IBattleListener`). It is wired to a target
`IAdvancedRobot` (whose event handlers receive the reconstructed events) and an
`IAdvancedRobotPeer` (which records the reconstructed commands).

| Callback | Action |
|---|---|
| `onBattleStarted` | validate it is a 1v1 battle, capture battlefield size + round count, build the replay drivers, push battle rules to the peer if it is an `IReplayRobotPeer`. |
| `onRoundStarted` | resolve the hero (by index or by name — `resolveHeroIndex` *throws* if the name is absent rather than defaulting to 0), construct the `Reconstructor`, seed it from the spawn snapshot. |
| `onTurnEnded` | feed `cur` to the `Reconstructor`; back-fill the fake peer's getters from the hero's ground-truth state for the turn; deliver events via `RobotReplayDriver`; record commands via `PeerReplayDriver`. |
| `onBattleCompleted` | capture a `BattleEndEvent`. |

The hero must be selected explicitly. There is no implicit "index 0" fallback.

---

## 4. Per-round orchestration — `Reconstructor`

### 4.1 Snapshot processing sequence

There is **one `Reconstructor` per hero** (the opponent is reconstructed by a
second `Dejavu`/`Reconstructor` instance). A `Reconstructor` never holds more
than two snapshots at a time — the previous baseline `prev` and the current
`cur` — and processes the snapshot stream strictly in arrival order, one tick at
a time:

1. `startRound(start)` resets the `EventReconstructor`, seeds its inter-tick
   state from the spawn snapshot, and sets `prev = start`.
2. each `onTurn(cur)` reconstructs the single tick `prev → cur`, then advances
   `prev = cur`. (If `prev` is somehow still `null`, the first `onTurn` only
   seeds `prev = cur` and returns `null` — no tick is reconstructed from a
   missing baseline.)

So tick `T` is always reconstructed from the **pair** `(snapshot[T-1],
snapshot[T])`; consecutive ticks overlap by one snapshot. The stream is processed
in order and never revisited — reconstruction is forward-only and single-pass.

Within one `onTurn(cur)` the order is:

1. `EventReconstructor.reconstruct(prev, cur)` → `TickEvents` (this also runs the
   energy ledger internally — see §4.2);
2. read back the energy breakdown via `getLastEnergyBreakdown()`;
3. `CommandReconstructor.reconstruct(prev, cur)` → `TickCommands`;
4. advance `prev = cur` and return the `TickResult`.

### 4.2 Both robots, and where energy fits

A single `Reconstructor` reconstructs only **one** hero's results, but each tick
*reads* both robots' fields out of the snapshot pair as context (the opponent's
position drives the scan and collision geometry; the opponent's energy and
both robots' bullets drive the energy ledger's battle-global inactivity counter).

The energy step is **not** processed independently of the events — it is the
fifth stage *inside* `EventReconstructor.reconstruct` (§5), and it runs **after**
the energy-bearing events have already been built for this tick:

```
detectFire → buildBulletEvents → buildWallEvent → buildRobotCollisionEvents
           → EnergyLedger.account(...)        ← energy decomposed HERE
           → buildDeathWinEvents → buildScanEvent → buildStatusEvent
```

`EnergyLedger.account` consumes the bullet-hit, hit-by-bullet, hit-wall and
hit-robot events that the earlier stages appended, so those must be present
first. The ledger then reads the hero's own `prev`/`cur` energy for the main
decomposition and *both* robots' snapshots (all robots + all bullets) for the
shared inactivity counter. The death/win, scan and status stages run *after*
energy. So the answer to "does it process both robots before energy?": within a
tick, the only steps that read both robots and feed energy — the bullet and
collision events — are completed before `account` runs, and `account` itself also
consults both robots for the inactivity zap.

---

## 5. Event reconstruction — `EventReconstructor`

One instance per round. It diffs `prev → cur` and emits the hero-perspective
events, in this order, then sorts them into engine dispatch order:

1. **Fire detection** (`detectFire`) — a hero-owned bullet whose id is absent
   from `prev` is a fresh fire this turn; its `getPower()` is the realized,
   engine-clamped power. Not itself an event, but it feeds bullet-hit events and
   the energy ledger. The gun-heat and pre-fire-energy cross-checks are
   real-engine invariants; a violation means the input is not a faithful
   recording and raises `IllegalStateException`.
2. **Bullet lifecycle** (`buildBulletEvents`) — `BulletHitEvent`,
   `HitByBulletEvent`, `BulletMissedEvent`, `BulletHitBulletEvent`. A bullet
   entering a terminal state (`HIT_VICTIM`/`HIT_WALL`/`HIT_BULLET`) carries
   exactly one engine event, but the engine keeps the bullet in that state during
   the explosion animation; events are emitted only on the **rising edge** —
   tracked per bullet identity (`bulletLastState`). The event position is
   recovered by replaying one velocity step from the bullet's previous-turn
   position (`bulletLastPos`), independent of the engine's per-turn bullet
   shuffle.
3. **Collisions** — `buildWallEvent` (hero rammed a wall) and
   `buildRobotCollisionEvents` (bounding boxes touched).
4. **Energy ledger** — `EnergyLedger.account(...)` decomposes and validates the
   hero's energy change (see §7).
5. **Death / win** (`buildDeathWinEvents`) — `RobotDeathEvent` (opponent died),
   `WinEvent` (hero last standing), `DeathEvent` (hero died). Once the hero is
   dead the engine stops delivering it events, so the `heroDead` guard suppresses
   everything afterward for the round.
6. **Scan** (`buildScanEvent`) — the radar PIE arc swept from the previous radar
   heading to the current one, tested against the opponent's bounding box,
   mirroring `RobotPeer.scan`.
7. **Status** (`buildStatusEvent`) — one `StatusEvent` per living tick.

### Dispatch order

Reconstructed events carry no engine priority (we never enqueue them), so each
event is stamped with its **class default priority** via
`HiddenAccess.setEventPriority` and then `Collections.sort`ed. This reproduces
the engine's ordering (time ascending, then priority descending) including the
`ScannedRobotEvent`/`HitRobotEvent` `compareTo` tie-breaks. `DeathEvent` is left
untouched (its `getPriority()` override returns `-1`, which `setPriorityHidden`
would clamp to 0 and warn about).

> Priority-stamp gotcha: the public `setPriority` is a no-op once a hero event's
> `addedToQueue` flag is set (which happens when its time is set), so the
> priority is stamped through the hidden `setPriorityHidden` path instead.

### Scan gate (snapshot-purity compromise)

The engine scans every turn but only delivers a `ScannedRobotEvent` when the
robot moved or called `scan()` manually. A manual `scan()` leaves no trace in the
snapshots. To stay snapshot-pure, the reconstructor assumes every robot scans
even with zero movement: a stationary tick collapses to a zero-width sweep (the
radial-line scan a manual `scan()` would produce). On such a degenerate sweep the
event's *presence* is uncertain, so it is flagged `SCAN_UNCERTAIN`; its fields
(bearing, distance, energy, heading, velocity) are still exact when the scan did
occur.

---

## 6. Command reconstruction — `CommandReconstructor`

Recovers the realized commands by inverse physics from `prev → cur`. The three
`adjust*` gun/radar coupling flags are assumed **false** (the engine default):
they do not affect the battle outcome, so déjàvu does not infer them and always
applies the additive heading split:

```
turnBody  = normalRelative(Δbody)
turnGun   = normalRelative(Δgun)   − turnBody
turnRadar = normalRelative(Δradar) − turnBody − turnGun
```

- For the four bundled `sample` heroes the flags really are false, so the split
  recovers the exact actuator turns. A hero that actually decoupled an actuator
  folds that coupling into the reported gun/radar command, which is accepted
  under the always-false assumption.
- **Move** is the signed snapshot velocity (`c.getVelocity()`).
- **Fire** uses the same `FireDetector.bornHeroBullet` oracle; power outside the
  legal `[MIN_BULLET_POWER, MAX_BULLET_POWER]` range raises `IllegalStateException`.
- **Skipped turns** are flagged `SKIPPED_TURN_SUSPECTED` from two cues:
  - *turn gap* — `cur.turn − prev.turn > 1`; the lost commands are unrecoverable,
    so a no-op tick is replayed;
  - *over-rate* — a single tick whose body-heading delta exceeds the
    velocity-dependent turn-rate cap, or whose velocity change exceeds the
    accel/decel cap (a forced stop to ~0, e.g. a wall hit, is legal and exempt).
    Conservative caps (plus `CAP_SLACK`) keep the sample heroes from false flags.

---

## 7. Energy reconstruction — `EnergyLedger`

The hero's per-tick energy change is the sum of a few independently observable
components:

| Component | Value | Source event |
|---|---|---|
| fire cost | `−firePower` | a new hero bullet appeared |
| bullet-hit bonus | `+getBulletHitBonus(power)` | `BulletHitEvent` |
| bullet damage taken | `−getBulletDamage(power)` | `HitByBulletEvent` |
| wall damage | `−max(|v|/2−1, 0)` | `HitWallEvent` (uses reconstructed pre-collision velocity) |
| ram damage | `−0.6` per contact | `HitRobotEvent` |
| inactivity zap | `−0.1` once idle past 450 turns | (battle-global) |

Because every component is individually derivable, `account(...)` is primarily a
**consistency check**: it sums the components and compares against
`cur.energy − prev.energy`. When the event set is complete the residual is zero.
When it is not (a dropped/mis-attributed event, or an ambiguous pre-collision
velocity), it runs a bounded search over the snapshot-derivable degrees of
freedom (notably back-solving the wall-hit velocity) to repair the decomposition.

The engine's `< 0.01 → 0` disabled-energy clamp is mirrored by the ledger. A
faithful living (non-death) tick is always reconcilable, so if no exact
combination is found the recording is not faithful and `account` throws
`IllegalStateException`. Inactivity is solved from battle-global state on both
robots (the zap quantum and the 450-turn idle counter are mirrored as
constants), because it cannot be inferred from the hero alone.

---

## 8. Helpers — `Geometry` and `Physics`

- **`Geometry`** — stateless engine-faithful geometry: `normalRelativeAngle`
  (the single normalization standard for the module), the radar `scanArc` (PIE
  arc), the 36×36 `robotBox`, and `arcIntersects`.
- **`Physics.preCollisionVelocity(prevVel)`** — the single home for the
  pre-collision velocity estimate (one acceleration step, sign-preserving,
  clamped to `MAX_VELOCITY`). On a collision tick the snapshot velocity is zeroed
  by the engine, so this estimate stands in for the post-`updateMovement`
  velocity used by collision geometry and the wall-hit damage ledger. It is an
  estimate: the realized velocity depends on the unrecorded movement command.

---

## 9. Drift taxonomy — `DriftReason`

`DriftReason` is a unified taxonomy with three kinds:

- **`UNCERTAINTY`** — the *only* kind the production reconstructor emits. A flag
  attached to a tick or to a single event where reconstruction could not be made
  exactly certain. Event-level flags are also folded into the tick-level set
  (`TickEvents` exposes both `getFlags()` and `flagsFor(event)`).
- **`CONFIRMED`** — a *harness-only* outcome: the engine snapshot oracle proved
  the labeled cause of a tolerated uncertainty.
- **`ANOMALY`** — a *harness-only* outcome: the oracle could not confirm the
  labeled cause; surfaced for triage.

Production uncertainty buckets: `SKIPPED_TURN_SUSPECTED`, `DOUBLE_HIT`,
`WALL_BEARING_UNRESOLVED`, `ROBOT_BEARING_UNRESOLVED`, `SHOOTER_BONUS_UNRESOLVED`,
`SCAN_UNCERTAIN`. Each documents exactly which field is uncertain and why (e.g. a
ram bearing depends on the opponent's unrecoverable mid-turn position *and* the
rammer's estimated pre-collision velocity).

### Which component assigns which flag

Only the two reconstructor components emit `UNCERTAINTY` flags; the `CONFIRMED`/
`ANOMALY` kinds are emitted exclusively by the harness (`FidelityComparator`).
The `EnergyLedger` emits **no** flag at all — it *throws*
`IllegalStateException` on an irreconcilable residual rather than flagging.

| Flag | Assigned by | Stage | Level |
|---|---|---|---|
| `SKIPPED_TURN_SUSPECTED` | `CommandReconstructor.reconstruct` (turn-gap or over-rate cue, §6) | command | tick (on `TickCommands`) |
| `SCAN_UNCERTAIN` | `EventReconstructor.buildScanEvent` (zero-width sweep) | event | event (on the `ScannedRobotEvent`) |
| `DOUBLE_HIT` | `EventReconstructor.buildBulletEvents` (≥2 hero bullets hit the same victim this turn) | event | event (on each colliding `BulletHitEvent`) |
| `SHOOTER_BONUS_UNRESOLVED` | `EventReconstructor.buildBulletEvents` (hero hit opponent *and* was hit by opponent same turn) | event | event (on the hero's `BulletHitEvent`) |
| `ROBOT_BEARING_UNRESOLVED` | `EventReconstructor.buildRobotCollisionEvents` (ram bearing) | event | event (on the `HitRobotEvent`) |
| `WALL_BEARING_UNRESOLVED` | *declared but not currently emitted* — `buildWallEvent` reconstructs the wall bearing exactly (or emits nothing), so no production code raises this bucket. The harness still refines it (`WALL_BEARING_CONFIRMED`/`MISMATCH`) if it ever appears. | — | — |
| `ENERGY_*`, `*_CONFIRMED`, `*_MISMATCH`, `SCAN_PRESENT_*`, `SKIP_*`, `RAM_BEARING_*`, `HIT_ENERGY_*` | `FidelityComparator` (oracle outcomes, harness only — see [dejavu-testing.md](dejavu-testing.md)) | validation | — |

Event-level flags are folded into the tick-level union by the `TickEvents`
constructor, so a coarse consumer sees them via `getFlags()`/`hasFlag(reason)`
while a precise consumer applies a per-event exclusion via `flagsFor(event)`.

> **Architectural rule:** `DriftReason` uncertainty flags belong to the
> *reconstructor only*. The validation harness must never mint its own
> uncertainty flag — it may only *census* the flags the reconstructor already
> produced, or emit `CONFIRMED`/`ANOMALY` oracle outcomes. When the harness can
> decide match/no-match from authoritative snapshot + engine physics, it does so
> directly rather than inventing a flag to paper over the question.

---

## 10. Replay side

- **`RobotReplayDriver`** — delivers a `TickEvents` to a target
  `IAdvancedRobot`'s `IBasicEvents`/`IAdvancedEvents` listeners in dispatch
  order, mimicking the engine.
- **`ReplayRobotPeer`** (`IReplayRobotPeer`) — a state-backed
  `IAdvancedRobotPeer`. Its read-only getters return the hero's ground-truth
  state, back-filled each turn from the snapshot (`loadState`); its `set*`
  mutators record the reconstructed command (so it can be inspected) but run no
  game mechanics. Blocking calls (`waitFor`, `move`, `turnBody`, …) and
  side-effecting helpers (`getGraphics`, data files, custom events, colors) are
  inert — a replayed hero never advances the engine, it only reads its recorded
  state and reacts to delivered events.
- **`PeerReplayDriver`** — records the per-tick `TickCommands` onto the peer.

---

## 11. Engine instrumentation (test-only ground truth)

To *validate* reconstruction, the engine was instrumented to expose the true
events, energy decomposition and collision bearings it computed — data that
cannot be recovered from two post-physics snapshots. This is the **oracle**, used
exclusively by the test harness; the engine attaches no ground truth during
normal play (it is populated only while testing is enabled). It is *not* an input
to reconstruction. The additions:

- `TurnEndedEvent` gained `getGroundTruthEvents()`, `getGroundTruthCommands()`,
  `getGroundTruthStatus()` (all `null` outside testing).
- New snapshot interfaces `ITurnEnergyBreakdown` (per-turn signed energy
  decomposition) and `ICollisionSnapshot` (the exact `HitRobotEvent` bearing and
  other-robot energy at impact).
- `IRobotSnapshot` gained `getEnergyChanges()`, `getRealizedVelocity()`,
  `getCollisions()`, `isScanning()`, `wasTurnSkipped()`; `IBulletSnapshot` gained
  `getVictimEnergyAtHit()`.
- `RobotPeer`/`BulletPeer` record these during a turn; `RobotSnapshot`/
  `BulletSnapshot`/`CollisionSnapshot`/`TurnEnergyBreakdown` carry them.

See [dejavu-testing.md](dejavu-testing.md) for how the oracle is consumed.

---

## 12. Assumptions and limitations

- 1v1 standard battles only (`Dejavu` throws otherwise).
- The hero must be selected explicitly (index or name); no implicit default.
- `adjust*` gun/radar coupling is assumed false; a decoupling hero folds the
  coupling into the reported gun/radar command.
- Skipped turns surface a flag and replay a no-op tick — the lost commands are
  unrecoverable.
- A manual `scan()` on a stationary tick is indistinguishable from no scan; such
  scans are emitted but flagged `SCAN_UNCERTAIN`.
- A handful of advanced mechanics (some ram/wall bearings, double-hit per-event
  victim energy, shooter-bonus ordering) are inherently RNG-dependent at the
  snapshot level and are surfaced as uncertainty rather than reconstructed
  exactly.
