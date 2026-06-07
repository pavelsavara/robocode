# Déjàvu Fidelity & Drift Report

Deterministic live 1v1 battles (800×600, 5 rounds) for 54 heroes vs `sample.Fire`.

The four bundled sample heroes are the *gold* regression set and must reconstruct at full fidelity; the rumble top-50 robots are driven for coverage and observed per-hero.

- Heroes reconstructed at full fidelity: **53 / 54**
- Heroes with residual drift or reconstruction error: **0**
- Heroes excluded (crashed in their own code, not a déjàvu fault): **1**
- Overall match rate (across cleanly-reconstructed ticks): **100.0000%**
- Residual mismatches: **0**

## Per-hero reconstruction status

| Hero | Gold | Status | Rate | Detail |
|---|:---:|---|---:|---|
| `sample.Walls` | ★ | ✅ full | 100.0000% |  |
| `sample.Fire` | ★ | ✅ full | 100.0000% |  |
| `sample.Crazy` | ★ | ✅ full | 100.0000% |  |
| `sample.SittingDuck` | ★ | ✅ full | 100.0000% |  |
| `kc.mega.BeepBoop` |  | ✅ full | 100.0000% |  |
| `aaa.r.ScalarR` |  | ✅ full | 100.0000% |  |
| `jk.mega.DrussGT` |  | ✅ full | 100.0000% |  |
| `voidious.Diamond` |  | ✅ full | 100.0000% |  |
| `rsalesc.mega.Knight` |  | ✅ full | 100.0000% |  |
| `rsalesc.roborio.Roborio` |  | ✅ full | 100.0000% |  |
| `lxx.Tomcat` |  | ✅ full | 100.0000% |  |
| `cb.fire.Firestarter` |  | ✅ full | 100.0000% |  |
| `xander.cat.XanderCat` |  | ✅ full | 100.0000% |  |
| `aw.Gilgalad` |  | ⏭️ robot crash | 100.0000% | NullPointerException: Cannot invoke "aw.waves.MovementDataWave.getBulletPower()" because "wave" is null |
| `oog.mega.saguaro.Saguaro` |  | ✅ full | 100.0000% |  |
| `dsekercioglu.mega.Raven` |  | ✅ full | 100.0000% |  |
| `kc.serpent.WaveSerpent` |  | ✅ full | 100.0000% |  |
| `voidious.Dookious` |  | ✅ full | 100.0000% |  |
| `darkcanuck.Pris` |  | ✅ full | 100.0000% |  |
| `abc.Shadow` |  | ✅ full | 100.0000% |  |
| `dsekercioglu.mega.WhiteFang` |  | ✅ full | 100.0000% |  |
| `mn.Combat` |  | ✅ full | 100.0000% |  |
| `gh.GresSuffurd` |  | ✅ full | 100.0000% |  |
| `cs.Nene` |  | ✅ full | 100.0000% |  |
| `jk.melee.Neuromancer` |  | ✅ full | 100.0000% |  |
| `tjk.deBroglie` |  | ✅ full | 100.0000% |  |
| `mue.Ascendant` |  | ✅ full | 100.0000% |  |
| `davidalves.Phoenix` |  | ✅ full | 100.0000% |  |
| `zyx.mega.YersiniaPestis` |  | ✅ full | 100.0000% |  |
| `darkcanuck.Holden` |  | ✅ full | 100.0000% |  |
| `Krabb.sliNk.Garm` |  | ✅ full | 100.0000% |  |
| `pez.rumble.CassiusClay` |  | ✅ full | 100.0000% |  |
| `jk.precise.Wintermute` |  | ✅ full | 100.0000% |  |
| `fromHell.BlackBox` |  | ✅ full | 100.0000% |  |
| `axeBots.SilverSurfer` |  | ✅ full | 100.0000% |  |
| `florent.XSeries.X2` |  | ✅ full | 100.0000% |  |
| `ar.horizon.Horizon` |  | ✅ full | 100.0000% |  |
| `florent.test.Toad` |  | ✅ full | 100.0000% |  |
| `pulsar.PulsarMax` |  | ✅ full | 100.0000% |  |
| `sheldor.mini.Foilist` |  | ✅ full | 100.0000% |  |
| `cjm.chalk.Chalk` |  | ✅ full | 100.0000% |  |
| `cb.Domogled` |  | ✅ full | 100.0000% |  |
| `cs.s2.Seraphim` |  | ✅ full | 100.0000% |  |
| `ags.Midboss` |  | ✅ full | 100.0000% |  |
| `dft.Cardigan` |  | ✅ full | 100.0000% |  |
| `kc.serpent.Hydra` |  | ✅ full | 100.0000% |  |
| `pez.rumble.Ali` |  | ✅ full | 100.0000% |  |
| `davidalves.Firebird` |  | ✅ full | 100.0000% |  |
| `wcsv.Engineer.Engineer` |  | ✅ full | 100.0000% |  |
| `wcsv.PowerHouse.PowerHouse` |  | ✅ full | 100.0000% |  |
| `fromHell.CHCl3` |  | ✅ full | 100.0000% |  |
| `dft.Cyanide` |  | ✅ full | 100.0000% |  |
| `jk.mini.CunobelinDC` |  | ✅ full | 100.0000% |  |
| `ags.rougedc.RougeDC` |  | ✅ full | 100.0000% |  |

## Overall fidelity by category

| Category | Matched | Total | Rate |
|---|---:|---:|---:|
| events | 139123 | 139123 | 100.0000% |
| commands | 179139 | 179139 | 100.0000% |
| event-fields | 123469 | 123469 | 100.0000% |
| **OVERALL** | 441731 | 441731 | **100.0000%** |

## Types of drift (tolerated reconstruction uncertainty)

Drift flags mark ticks where reconstruction could not be made exactly certain, yet the reconstructed tick still matched ground truth. The harness tolerates these documented uncertainties; the counts below census how often each was exercised.

| Drift type | Scope | Occurrences | Tolerated field / effect |
|---|---|---:|---|
| `SKIPPED_TURN_SUSPECTED` | tick | 0 | tick excluded from command scoring (turn gap / over-rate cue) |
| `DOUBLE_HIT` | event | 0 | `BulletHitEvent.getEnergy()` (RNG-ordered intermediate victim energy) |
| `WALL_BEARING_UNRESOLVED` | event | 0 | `HitWallEvent.getBearingRadians()` (best-effort impact bearing) |
| `ROBOT_BEARING_UNRESOLVED` | event | 25 | `HitRobotEvent.getBearingRadians()` (ram bearing from mid-turn position) |
| `SHOOTER_BONUS_UNRESOLVED` | event | 6 | `BulletHitEvent.getEnergy()` (may include RNG-ordered shooter bonus) |
| `SCAN_UNCERTAIN` | event | 4501 | presence of a zero-width-sweep `ScannedRobotEvent` |

## Oracle-confirmed drift reasons

Each tolerated drift above is cross-checked against the engine snapshot oracle (the new snapshot APIs). A `*_CONFIRMED` reason means the oracle proves the labeled cause; an anomaly reason (`*_MISMATCH` / `*_SPURIOUS` / `*_UNEXPLAINED`) flags a case the oracle could not confirm. Reporting only — the gate is unchanged.

| Drift reason | Origin | Kind | Occurrences |
|---|---|---|---:|
| `SKIPPED_TURN_SUSPECTED` | — | ✅ confirmed | 0 |
| `DOUBLE_HIT` | — | ✅ confirmed | 0 |
| `WALL_BEARING_UNRESOLVED` | — | ✅ confirmed | 0 |
| `ROBOT_BEARING_UNRESOLVED` | — | ✅ confirmed | 0 |
| `SHOOTER_BONUS_UNRESOLVED` | — | ✅ confirmed | 0 |
| `SCAN_UNCERTAIN` | — | ✅ confirmed | 0 |
| `ENERGY_ZAP_CONFIRMED` | — | ✅ confirmed | 0 |
| `ENERGY_WALL_BACKSOLVE_CONFIRMED` | — | ✅ confirmed | 125 |
| `ENERGY_RESIDUAL_UNEXPLAINED` | — | ⚠️ anomaly | 0 |
| `HIT_ENERGY_ORDER_CONFIRMED` | `DOUBLE_HIT` | ✅ confirmed | 0 |
| `HIT_ENERGY_BONUS_CONFIRMED` | `SHOOTER_BONUS_UNRESOLVED` | ✅ confirmed | 3 |
| `HIT_ENERGY_MISMATCH` | — | ⚠️ anomaly | 3 |
| `WALL_BEARING_CONFIRMED` | `WALL_BEARING_UNRESOLVED` | ✅ confirmed | 0 |
| `WALL_BEARING_MISMATCH` | `WALL_BEARING_UNRESOLVED` | ⚠️ anomaly | 0 |
| `RAM_BEARING_CONFIRMED` | `ROBOT_BEARING_UNRESOLVED` | ✅ confirmed | 21 |
| `RAM_BEARING_MISMATCH` | `ROBOT_BEARING_UNRESOLVED` | ⚠️ anomaly | 4 |
| `SCAN_PRESENT_CONFIRMED` | `SCAN_UNCERTAIN` | ✅ confirmed | 0 |
| `SCAN_SPURIOUS` | `SCAN_UNCERTAIN` | ⚠️ anomaly | 2003 |
| `SKIP_CONFIRMED` | `SKIPPED_TURN_SUSPECTED` | ✅ confirmed | 0 |
| `SKIP_FALSE_POSITIVE` | `SKIPPED_TURN_SUSPECTED` | ⚠️ anomaly | 0 |

## Fidelity by hero

|---|---:|---:|---:|---:|
| `sample.Walls` | 100.0000% (3982/3982) | 100.0000% (391/391) | 100.0000% (4738/4738) | 100.0000% |
| `sample.Fire` | 100.0000% (11451/11451) | 100.0000% (2692/2692) | 100.0000% (12206/12206) | 100.0000% |
| `sample.Crazy` | 100.0000% (8405/8405) | 100.0000% (910/910) | 100.0000% (9160/9160) | 100.0000% |
| `sample.SittingDuck` | 100.0000% (1915/1915) | 100.0000% (120/120) | 100.0000% (2670/2670) | 100.0000% |
| `kc.mega.BeepBoop` | 100.0000% (2108/2108) | 100.0000% (2230/2230) | 100.0000% (2863/2863) | 100.0000% |
| `aaa.r.ScalarR` | 100.0000% (2199/2199) | 100.0000% (2323/2323) | 100.0000% (2954/2954) | 100.0000% |
| `jk.mega.DrussGT` | 100.0000% (7297/7297) | 100.0000% (7766/7766) | 100.0000% (8052/8052) | 100.0000% |
| `voidious.Diamond` | 100.0000% (1695/1695) | 100.0000% (1780/1780) | 100.0000% (2450/2450) | 100.0000% |
| `rsalesc.mega.Knight` | 100.0000% (1835/1835) | 100.0000% (1915/1915) | 100.0000% (2590/2590) | 100.0000% |
| `rsalesc.roborio.Roborio` | 100.0000% (9171/9171) | 100.0000% (9658/9658) | 100.0000% (9926/9926) | 100.0000% |
| `lxx.Tomcat` | 100.0000% (1980/1980) | 100.0000% (2091/2091) | 100.0000% (2735/2735) | 100.0000% |
| `cb.fire.Firestarter` | 100.0000% (1542/1542) | 100.0000% (1622/1622) | 100.0000% (2297/2297) | 100.0000% |
| `xander.cat.XanderCat` | 100.0000% (7312/7312) | 100.0000% (7796/7796) | 100.0000% (8067/8067) | 100.0000% |
| `aw.Gilgalad` | — | — | — | 100.0000% |
| `oog.mega.saguaro.Saguaro` | 100.0000% (4460/4460) | 100.0000% (4750/4750) | 100.0000% (5215/5215) | 100.0000% |
| `dsekercioglu.mega.Raven` | 100.0000% (1872/1872) | 100.0000% (1960/1960) | 100.0000% (2627/2627) | 100.0000% |
| `kc.serpent.WaveSerpent` | 100.0000% (1798/1798) | 100.0000% (1880/1880) | 100.0000% (2553/2553) | 100.0000% |
| `voidious.Dookious` | 100.0000% (1142/1142) | 100.0000% (1185/1185) | 100.0000% (1897/1897) | 100.0000% |
| `darkcanuck.Pris` | 100.0000% (1760/1760) | 100.0000% (1851/1851) | 100.0000% (2515/2515) | 100.0000% |
| `abc.Shadow` | 100.0000% (1515/1515) | 100.0000% (1589/1589) | 100.0000% (2270/2270) | 100.0000% |
| `dsekercioglu.mega.WhiteFang` | 100.0000% (7047/7047) | 100.0000% (7510/7510) | 100.0000% (7802/7802) | 100.0000% |
| `mn.Combat` | 100.0000% (2341/2341) | 100.0000% (2476/2476) | 100.0000% (3096/3096) | 100.0000% |
| `gh.GresSuffurd` | 100.0000% (1507/1507) | 100.0000% (1576/1576) | 100.0000% (2262/2262) | 100.0000% |
| `cs.Nene` | 100.0000% (1970/1970) | 100.0000% (2084/2084) | 100.0000% (2725/2725) | 100.0000% |
| `jk.melee.Neuromancer` | 100.0000% (2174/2174) | 100.0000% (2270/2270) | 100.0000% (2929/2929) | 100.0000% |
| `tjk.deBroglie` | 100.0000% (1768/1768) | 100.0000% (1889/1889) | 100.0000% (2523/2523) | 100.0000% |
| `mue.Ascendant` | 100.0000% (1673/1673) | 100.0000% (1745/1745) | 100.0000% (2428/2428) | 100.0000% |
| `davidalves.Phoenix` | 100.0000% (1451/1451) | 100.0000% (1516/1516) | 100.0000% (2206/2206) | 100.0000% |
| `zyx.mega.YersiniaPestis` | 100.0000% (1933/1933) | 100.0000% (2024/2024) | 100.0000% (2688/2688) | 100.0000% |
| `darkcanuck.Holden` | 100.0000% (1491/1491) | 100.0000% (1561/1561) | 100.0000% (2246/2246) | 100.0000% |
| `Krabb.sliNk.Garm` | 100.0000% (1935/1935) | 100.0000% (2025/2025) | 100.0000% (2690/2690) | 100.0000% |
| `pez.rumble.CassiusClay` | 100.0000% (1517/1517) | 100.0000% (1562/1562) | 100.0000% (2272/2272) | 100.0000% |
| `jk.precise.Wintermute` | 100.0000% (1523/1523) | 100.0000% (1580/1580) | 100.0000% (2278/2278) | 100.0000% |
| `fromHell.BlackBox` | 100.0000% (1663/1663) | 100.0000% (1736/1736) | 100.0000% (2418/2418) | 100.0000% |
| `axeBots.SilverSurfer` | 100.0000% (1670/1670) | 100.0000% (1729/1729) | 100.0000% (2425/2425) | 100.0000% |
| `florent.XSeries.X2` | 100.0000% (1328/1328) | 100.0000% (1383/1383) | 100.0000% (2083/2083) | 100.0000% |
| `ar.horizon.Horizon` | 100.0000% (1698/1698) | 100.0000% (1778/1778) | 100.0000% (2453/2453) | 100.0000% |
| `florent.test.Toad` | 100.0000% (1138/1138) | 100.0000% (1176/1176) | 100.0000% (1893/1893) | 100.0000% |
| `pulsar.PulsarMax` | 100.0000% (1599/1599) | 100.0000% (1654/1654) | 100.0000% (2354/2354) | 100.0000% |
| `sheldor.mini.Foilist` | 100.0000% (2566/2566) | 100.0000% (2700/2700) | 100.0000% (3321/3321) | 100.0000% |
| `cjm.chalk.Chalk` | 100.0000% (2224/2224) | 100.0000% (2337/2337) | 100.0000% (2979/2979) | 100.0000% |
| `cb.Domogled` | 100.0000% (1227/1227) | 100.0000% (1267/1267) | 100.0000% (1982/1982) | 100.0000% |
| `cs.s2.Seraphim` | 100.0000% (1872/1872) | 100.0000% (1975/1975) | 100.0000% (2627/2627) | 100.0000% |
| `ags.Midboss` | 100.0000% (2134/2134) | 100.0000% (2265/2265) | 100.0000% (2889/2889) | 100.0000% |
| `dft.Cardigan` | 100.0000% (1318/1318) | 100.0000% (1379/1379) | 100.0000% (2073/2073) | 100.0000% |
| `kc.serpent.Hydra` | 100.0000% (1784/1784) | 100.0000% (1869/1869) | 100.0000% (2539/2539) | 100.0000% |
| `pez.rumble.Ali` | 100.0000% (1964/1964) | 100.0000% (2068/2068) | 100.0000% (2719/2719) | 100.0000% |
| `davidalves.Firebird` | 100.0000% (1736/1736) | 100.0000% (1816/1816) | 100.0000% (2491/2491) | 100.0000% |
| `wcsv.Engineer.Engineer` | 100.0000% (1900/1900) | 100.0000% (2002/2002) | 100.0000% (2655/2655) | 100.0000% |
| `wcsv.PowerHouse.PowerHouse` | 100.0000% (1953/1953) | 100.0000% (2057/2057) | 100.0000% (2708/2708) | 100.0000% |
| `fromHell.CHCl3` | 100.0000% (2067/2067) | 100.0000% (2170/2170) | 100.0000% (2822/2822) | 100.0000% |
| `dft.Cyanide` | 100.0000% (1793/1793) | 100.0000% (1877/1877) | 100.0000% (2548/2548) | 100.0000% |
| `jk.mini.CunobelinDC` | 100.0000% (2285/2285) | 100.0000% (2404/2404) | 100.0000% (3040/3040) | 100.0000% |
| `ags.rougedc.RougeDC` | 100.0000% (1435/1435) | 100.0000% (1500/1500) | 100.0000% (2190/2190) | 100.0000% |

## Drift census by hero

| Hero | SKIPPED_TURN_SUSPECTED | DOUBLE_HIT | WALL_BEARING_UNRESOLVED | ROBOT_BEARING_UNRESOLVED | SHOOTER_BONUS_UNRESOLVED | SCAN_UNCERTAIN |
|---|---:|---:|---:|---:|---:|---:|
| `sample.Walls` | 0 | 0 | 0 | 2 | 0 | 232 |
| `sample.Fire` | 0 | 0 | 0 | 0 | 5 | 3936 |
| `sample.Crazy` | 0 | 0 | 0 | 23 | 1 | 289 |
| `sample.SittingDuck` | 0 | 0 | 0 | 0 | 0 | 0 |
| `kc.mega.BeepBoop` | 0 | 0 | 0 | 0 | 0 | 0 |
| `aaa.r.ScalarR` | 0 | 0 | 0 | 0 | 0 | 38 |
| `jk.mega.DrussGT` | 0 | 0 | 0 | 0 | 0 | 0 |
| `voidious.Diamond` | 0 | 0 | 0 | 0 | 0 | 0 |
| `rsalesc.mega.Knight` | 0 | 0 | 0 | 0 | 0 | 0 |
| `rsalesc.roborio.Roborio` | 0 | 0 | 0 | 0 | 0 | 0 |
| `lxx.Tomcat` | 0 | 0 | 0 | 0 | 0 | 0 |
| `cb.fire.Firestarter` | 0 | 0 | 0 | 0 | 0 | 0 |
| `xander.cat.XanderCat` | 0 | 0 | 0 | 0 | 0 | 0 |
| `aw.Gilgalad` | 0 | 0 | 0 | 0 | 0 | 0 |
| `oog.mega.saguaro.Saguaro` | 0 | 0 | 0 | 0 | 0 | 0 |
| `dsekercioglu.mega.Raven` | 0 | 0 | 0 | 0 | 0 | 0 |
| `kc.serpent.WaveSerpent` | 0 | 0 | 0 | 0 | 0 | 0 |
| `voidious.Dookious` | 0 | 0 | 0 | 0 | 0 | 0 |
| `darkcanuck.Pris` | 0 | 0 | 0 | 0 | 0 | 0 |
| `abc.Shadow` | 0 | 0 | 0 | 0 | 0 | 0 |
| `dsekercioglu.mega.WhiteFang` | 0 | 0 | 0 | 0 | 0 | 0 |
| `mn.Combat` | 0 | 0 | 0 | 0 | 0 | 0 |
| `gh.GresSuffurd` | 0 | 0 | 0 | 0 | 0 | 0 |
| `cs.Nene` | 0 | 0 | 0 | 0 | 0 | 0 |
| `jk.melee.Neuromancer` | 0 | 0 | 0 | 0 | 0 | 0 |
| `tjk.deBroglie` | 0 | 0 | 0 | 0 | 0 | 0 |
| `mue.Ascendant` | 0 | 0 | 0 | 0 | 0 | 0 |
| `davidalves.Phoenix` | 0 | 0 | 0 | 0 | 0 | 0 |
| `zyx.mega.YersiniaPestis` | 0 | 0 | 0 | 0 | 0 | 0 |
| `darkcanuck.Holden` | 0 | 0 | 0 | 0 | 0 | 0 |
| `Krabb.sliNk.Garm` | 0 | 0 | 0 | 0 | 0 | 0 |
| `pez.rumble.CassiusClay` | 0 | 0 | 0 | 0 | 0 | 1 |
| `jk.precise.Wintermute` | 0 | 0 | 0 | 0 | 0 | 0 |
| `fromHell.BlackBox` | 0 | 0 | 0 | 0 | 0 | 0 |
| `axeBots.SilverSurfer` | 0 | 0 | 0 | 0 | 0 | 0 |
| `florent.XSeries.X2` | 0 | 0 | 0 | 0 | 0 | 0 |
| `ar.horizon.Horizon` | 0 | 0 | 0 | 0 | 0 | 1 |
| `florent.test.Toad` | 0 | 0 | 0 | 0 | 0 | 0 |
| `pulsar.PulsarMax` | 0 | 0 | 0 | 0 | 0 | 0 |
| `sheldor.mini.Foilist` | 0 | 0 | 0 | 0 | 0 | 0 |
| `cjm.chalk.Chalk` | 0 | 0 | 0 | 0 | 0 | 0 |
| `cb.Domogled` | 0 | 0 | 0 | 0 | 0 | 2 |
| `cs.s2.Seraphim` | 0 | 0 | 0 | 0 | 0 | 0 |
| `ags.Midboss` | 0 | 0 | 0 | 0 | 0 | 0 |
| `dft.Cardigan` | 0 | 0 | 0 | 0 | 0 | 0 |
| `kc.serpent.Hydra` | 0 | 0 | 0 | 0 | 0 | 0 |
| `pez.rumble.Ali` | 0 | 0 | 0 | 0 | 0 | 2 |
| `davidalves.Firebird` | 0 | 0 | 0 | 0 | 0 | 0 |
| `wcsv.Engineer.Engineer` | 0 | 0 | 0 | 0 | 0 | 0 |
| `wcsv.PowerHouse.PowerHouse` | 0 | 0 | 0 | 0 | 0 | 0 |
| `fromHell.CHCl3` | 0 | 0 | 0 | 0 | 0 | 0 |
| `dft.Cyanide` | 0 | 0 | 0 | 0 | 0 | 0 |
| `jk.mini.CunobelinDC` | 0 | 0 | 0 | 0 | 0 | 0 |
| `ags.rougedc.RougeDC` | 0 | 0 | 0 | 0 | 0 | 0 |

## Residual mismatches

None — full fidelity (100%).
## Oracle anomalies (drift the oracle could not confirm)

These ticks were tolerated by the gate (no score change) but the engine snapshot oracle did not confirm the labeled cause; they are surfaced for triage. Grouped by reason with the rarest (most interesting) first; high-volume reasons are capped at 10 rows (full counts are in the census table above).

| Round | Turn | Reason | Detail |
|---:|---:|---|---|
| 2 | 1282 | `HIT_ENERGY_MISMATCH` | rcEnergy=44.0 oracle=47.0 |
| 2 | 1337 | `HIT_ENERGY_MISMATCH` | rcEnergy=40.0 oracle=43.0 |
| 3 | 91 | `HIT_ENERGY_MISMATCH` | rcEnergy=93.0 oracle=94.0 |
| 0 | 354 | `RAM_BEARING_MISMATCH` | rcBearing=-0.1688247771943856 oracle=-0.1910229389883682 |
| 0 | 1035 | `RAM_BEARING_MISMATCH` | rcBearing=3.1028308754673932 oracle=3.104512425320538 |
| 2 | 232 | `RAM_BEARING_MISMATCH` | rcBearing=0.5601689468271425 oracle=0.4928629761183658 |
| 4 | 914 | `RAM_BEARING_MISMATCH` | rcBearing=1.14796388229741 oracle=1.1183488119237532 |
| 2 | 542 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 544 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 546 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 548 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 550 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 552 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 554 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 556 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 558 | `SCAN_SPURIOUS` | isScanning=false |
| 2 | 560 | `SCAN_SPURIOUS` | isScanning=false |
| … | … | `SCAN_SPURIOUS` | … and 1993 more (2003 total) |

