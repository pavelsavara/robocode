/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu;

import net.sf.robocode.dejavu.core.EnergyLedger;
import net.sf.robocode.dejavu.core.EventReconstructor;
import net.sf.robocode.dejavu.harness.BattleCaptureHarness;
import net.sf.robocode.dejavu.harness.CapturedTurn;
import net.sf.robocode.dejavu.harness.StubBulletSnapshot;
import net.sf.robocode.dejavu.harness.StubRobotSnapshot;
import net.sf.robocode.dejavu.harness.StubTurnSnapshot;
import net.sf.robocode.dejavu.model.EnergyBreakdown;
import net.sf.robocode.dejavu.model.DriftReason;
import org.junit.BeforeClass;
import org.junit.Test;
import robocode.Bullet;
import robocode.BulletHitEvent;
import robocode.Event;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.Rules;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 1 step 6: energy ledger &mdash; per-tick decomposition and event-set
 * repair (design &sect;6, plan step 6).
 * <p>
 * The hero's energy change on a turn is the sum of a few independently
 * observable components (fire cost, bullet-hit bonus, bullet damage taken, wall
 * damage, ram damage). {@link EnergyLedger#account} decomposes the
 * reconstructed event set into those components and checks the sum against the
 * observed {@code cur.energy - prev.energy}.
 * <p>
 * The unit tests drive {@link EnergyLedger#account} with synthetic snapshots:
 * each single component reproduces the energy delta exactly (residual zero); a
 * hard combination of wall + bullet + ram on one tick decomposes to zero; a
 * dropped {@code HitByBulletEvent} is recovered from the snapshot by the repair
 * search (residual zero); and an energy drop with no explaining component is
 * rejected with {@link IllegalStateException}.
 * <p>
 * The integration test reconstructs live battles and asserts the hero's energy
 * is fully explained on every turn both robots survive &mdash; zero residual
 * &mdash; validating the decomposition against the engine ground truth turn by
 * turn.
 */
public class EnergyLedgerTest {

    private static final double EPSILON = 1e-6;
    private static final int BF_WIDTH = 800;
    private static final int BF_HEIGHT = 600;

    private static final String ENEMY = "sample.Fire";
    private static final int ROUNDS = 3;
    private static final String[] HEROES = {
            "sample.Walls", "sample.Fire", "sample.Crazy", "sample.SittingDuck"
    };

    @BeforeClass
    public static void initEngine() {
        // Constructing any RobotTestBed initializes the Robocode engine, which
        // installs the HiddenAccess helpers the reconstruction relies on.
        new BattleCaptureHarness("sample.SittingDuck", ENEMY, 1);
    }

    // ---- Unit tests (synthetic snapshots) -------------------------------

    @Test
    public void fireCostReproducesEnergyDelta() {
        // A new hero bullet of power 3 appears: energy drops by exactly 3.
        ITurnSnapshot prev = turn(40, hero(100, 0), opponent(50));
        ITurnSnapshot cur = turn(41, hero(97, 0), opponent(50),
                bullet(BulletState.FIRED, 0, 7, 3.0));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, noEvents(), flags);

        assertEquals("fire cost", -3.0, b.getFireCost(), EPSILON);
        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    @Test
    public void bulletHitBonusReproducesEnergyDelta() {
        // A hero bullet (already in flight) strikes the opponent: +3*power.
        ITurnSnapshot prev = turn(40, hero(50, 0), opponent(50),
                bullet(BulletState.MOVING, 0, 7, 3.0));
        ITurnSnapshot cur = turn(41, hero(59, 0), opponent(34),
                hitVictim(0, 7, 3.0, 1));

        List<Event> events = new ArrayList<Event>();
        events.add(new BulletHitEvent("sample.Fire", 34, heroBullet(7, 3.0)));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, events, flags);

        assertEquals("bullet hit bonus", Rules.getBulletHitBonus(3.0), b.getBulletHitBonus(), EPSILON);
        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    @Test
    public void bulletDamageReproducesEnergyDelta() {
        // An opponent bullet of power 3 strikes the hero: -getBulletDamage(3).
        double damage = Rules.getBulletDamage(3.0);
        ITurnSnapshot prev = turn(40, hero(50, 0), opponent(50),
                bullet(BulletState.MOVING, 1, 9, 3.0));
        ITurnSnapshot cur = turn(41, hero(50 - damage, 0), opponent(50),
                hitVictim(1, 9, 3.0, 0));

        List<Event> events = new ArrayList<Event>();
        events.add(new HitByBulletEvent(0.0, oppBullet(9, 3.0)));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, events, flags);

        assertEquals("bullet damage", -damage, b.getBulletDamage(), EPSILON);
        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    @Test
    public void wallDamageReproducesEnergyDelta() {
        // Hero hits a wall at full speed: pre-collision velocity 8 -> -3 energy.
        double wall = Rules.getWallHitDamage(8.0);
        ITurnSnapshot prev = turn(40, heroMoving(50, 8.0), opponent(50));
        ITurnSnapshot cur = turn(41, heroState(50 - wall, 0.0, RobotState.HIT_WALL), opponent(50));

        List<Event> events = new ArrayList<Event>();
        events.add(new HitWallEvent(0.0));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, events, flags);

        assertEquals("wall damage", -wall, b.getWallDamage(), EPSILON);
        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    @Test
    public void ramDamageReproducesEnergyDelta() {
        // Hero rams the opponent: -0.6 energy, one HitRobotEvent.
        ITurnSnapshot prev = turn(40, hero(50, 0), opponent(50));
        ITurnSnapshot cur = turn(41, heroState(49.4, 0.0, RobotState.HIT_ROBOT), opponent(50));

        List<Event> events = new ArrayList<Event>();
        events.add(new HitRobotEvent("sample.Fire", 0.0, 50, true));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, events, flags);

        assertEquals("ram damage", -Rules.ROBOT_HIT_DAMAGE, b.getRamDamage(), EPSILON);
        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    @Test
    public void hardComboWallBulletRamDecomposesToZero() {
        // One turn that combines a wall hit (pre-collision velocity 8), a bullet
        // of power 2 striking the hero, and a ram. All three must decompose so
        // the residual is zero.
        double wall = Rules.getWallHitDamage(8.0);
        double damage = Rules.getBulletDamage(2.0);
        double total = wall + damage + Rules.ROBOT_HIT_DAMAGE;

        ITurnSnapshot prev = turn(40, heroMoving(50, 8.0), opponent(50),
                bullet(BulletState.MOVING, 1, 9, 2.0));
        ITurnSnapshot cur = turn(41, heroState(50 - total, 0.0, RobotState.HIT_ROBOT), opponent(50),
                hitVictim(1, 9, 2.0, 0));

        List<Event> events = new ArrayList<Event>();
        events.add(new HitWallEvent(0.0));
        events.add(new HitByBulletEvent(0.0, oppBullet(9, 2.0)));
        events.add(new HitRobotEvent("sample.Fire", 0.0, 50, true));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, events, flags);

        assertEquals("wall damage", -wall, b.getWallDamage(), EPSILON);
        assertEquals("bullet damage", -damage, b.getBulletDamage(), EPSILON);
        assertEquals("ram damage", -Rules.ROBOT_HIT_DAMAGE, b.getRamDamage(), EPSILON);
        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    @Test
    public void droppedBulletEventRecoveredBySearch() {
        // The event set is MISSING the HitByBulletEvent, but the snapshot still
        // witnesses the opponent bullet entering HIT_VICTIM against the hero on
        // the rising edge. The repair search must recover the lost component.
        double damage = Rules.getBulletDamage(3.0);
        ITurnSnapshot prev = turn(40, hero(50, 0), opponent(50),
                bullet(BulletState.MOVING, 1, 9, 3.0));
        ITurnSnapshot cur = turn(41, hero(50 - damage, 0), opponent(50),
                hitVictim(1, 9, 3.0, 0));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, noEvents(), flags);

        assertEquals("recovered bullet damage", -damage, b.getBulletDamage(), EPSILON);
        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    @Test
    public void unexplainedEnergyDropIsRejected() {
        // Energy drops with no bullet, wall or ram in either the events or the
        // snapshot: a faithful living tick is always reconcilable, so an
        // unexplained drop means the recording is not a faithful capture and is
        // rejected rather than silently trusted.
        ITurnSnapshot prev = turn(40, hero(50, 0), opponent(50));
        ITurnSnapshot cur = turn(41, hero(40, 0), opponent(50));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        try {
            new EnergyLedger(0).account(prev, cur, noEvents(), flags);
            fail("an unexplained energy drop must be rejected");
        } catch (IllegalStateException expected) {
            // expected: the input is not a faithful recording of a real battle.
        }
    }

    @Test
    public void deadHeroIsNotAccounted() {
        ITurnSnapshot prev = turn(40, hero(0.5, 0), opponent(50));
        ITurnSnapshot cur = turn(41, heroState(0.0, 0.0, RobotState.DEAD), opponent(50));

        EnumSet<DriftReason> flags = EnumSet.noneOf(DriftReason.class);
        EnergyBreakdown b = new EnergyLedger(0).account(prev, cur, noEvents(), flags);

        assertEquals("residual", 0.0, b.getResidual(), EPSILON);
    }

    // ---- Integration test (live battles) --------------------------------

    @Test
    public void energyFullyExplainedForAllHeroes() {
        int accountedTurns = 0;
        for (String hero : HEROES) {
            accountedTurns += verifyEnergyLedger(hero);
        }
        // Guard against the loop silently exercising nothing.
        assertTrue("no turns accounted", accountedTurns > 0);
    }

    /** @return the number of living turns whose energy was checked. */
    private int verifyEnergyLedger(String hero) {
        BattleCaptureHarness harness = new BattleCaptureHarness(hero, ENEMY, ROUNDS);
        List<CapturedTurn> captured = harness.capture();
        assertFalse("[" + hero + "] no turns captured", captured.isEmpty());

        int checked = 0;
        int round = -1;
        EventReconstructor reconstructor = null;
        ITurnSnapshot prev = null;

        for (CapturedTurn ct : captured) {
            if (ct.getRound() != round) {
                round = ct.getRound();
                reconstructor = new EventReconstructor(0, BF_WIDTH, BF_HEIGHT, ROUNDS);
                reconstructor.resetRound();
                ITurnSnapshot start = harness.getRoundStartSnapshot(round);
                assertNotNull("[" + hero + "] r" + round + " missing round-start snapshot", start);
                reconstructor.seedRoundStart(start);
                prev = null;
            }

            ITurnSnapshot cur = ct.getSnapshot();
            reconstructor.reconstruct(prev == null ? cur : prev, cur);
            prev = cur;

            // Only turns where both robots survive post-physics are fully
            // reproducible (a death turn's killing-blow energy is delivered in a
            // shuffled, snapshot-irrecoverable order). The energy change of the
            // surviving turns must decompose exactly.
            if (!bothAlive(cur)) {
                continue;
            }

            EnergyBreakdown b = reconstructor.getLastEnergyBreakdown();
            assertNotNull("[" + hero + "] r" + round + " t" + ct.getTurn() + " no breakdown", b);
            String at = "[" + hero + "] r" + round + " t" + ct.getTurn() + " ";
            assertEquals(at + "energy residual (" + describe(b) + ")",
                    0.0, b.getResidual(), EPSILON);
            checked++;
        }
        return checked;
    }

    // ---- Helpers --------------------------------------------------------

    private static boolean bothAlive(ITurnSnapshot cur) {
        for (IRobotSnapshot r : cur.getRobots()) {
            if (r.getState().isDead()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(EnergyBreakdown b) {
        return "fire=" + b.getFireCost() + " bonus=" + b.getBulletHitBonus()
                + " dmg=" + b.getBulletDamage() + " wall=" + b.getWallDamage()
                + " ram=" + b.getRamDamage() + " resid=" + b.getResidual();
    }

    private static List<Event> noEvents() {
        return new ArrayList<Event>();
    }

    private static Bullet heroBullet(int id, double power) {
        return new Bullet(0.0, 0, 0, power, "hero", "sample.Fire", false, id);
    }

    private static Bullet oppBullet(int id, double power) {
        return new Bullet(0.0, 0, 0, power, "sample.Fire", "hero", false, id);
    }

    private static IBulletSnapshot bullet(BulletState state, int ownerIndex, int id, double power) {
        return new StubBulletSnapshot()
                .state(state).ownerIndex(ownerIndex).bulletId(id).power(power);
    }

    private static IBulletSnapshot hitVictim(int ownerIndex, int id, double power, int victimIndex) {
        return new StubBulletSnapshot()
                .state(BulletState.HIT_VICTIM).ownerIndex(ownerIndex).bulletId(id)
                .power(power).victimIndex(victimIndex);
    }

    private static IRobotSnapshot hero(double energy, double bodyHeading) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(energy).bodyHeading(bodyHeading)
                .x(400).y(300);
    }

    private static IRobotSnapshot heroMoving(double energy, double velocity) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(energy).velocity(velocity)
                .x(400).y(300);
    }

    private static IRobotSnapshot heroState(double energy, double velocity, RobotState state) {
        return new StubRobotSnapshot()
                .name("hero").robotIndex(0).energy(energy).velocity(velocity).state(state)
                .x(400).y(300);
    }

    private static IRobotSnapshot opponent(double energy) {
        return new StubRobotSnapshot()
                .name("sample.Fire").robotIndex(1).energy(energy).x(600).y(300);
    }

    private static ITurnSnapshot turn(int t, IRobotSnapshot hero, IRobotSnapshot opponent,
            IBulletSnapshot... bullets) {
        return new StubTurnSnapshot(0, t, new IRobotSnapshot[] { hero, opponent }, bullets);
    }
}
