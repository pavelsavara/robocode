/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu;

import net.sf.robocode.dejavu.core.Reconstructor;
import net.sf.robocode.dejavu.model.BattleEndEvent;
import net.sf.robocode.dejavu.replay.IReplayRobotPeer;
import net.sf.robocode.dejavu.replay.PeerReplayDriver;
import net.sf.robocode.dejavu.replay.RobotReplayDriver;
import robocode.BattleRules;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.BattleStartedEvent;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.robotinterfaces.IAdvancedRobot;
import robocode.robotinterfaces.peer.IAdvancedRobotPeer;

/**
 * An {@link robocode.control.events.IBattleListener} that reconstructs, from
 * offline {@code .br} snapshots, the exact events a single "hero" robot
 * received each tick and the realized commands it emitted, then replays them
 * onto a target {@link IAdvancedRobot} (events) and a fake state-backed
 * {@link IAdvancedRobotPeer} (commands).
 * <p>
 * Scope: standard 1v1 battles on a fixed battlefield (default 800x600). One
 * instance per hero; the opponent is reconstructed by a second instance.
 */
public class Dejavu extends BattleAdaptor {

    public static final double DEFAULT_BATTLEFIELD_WIDTH = 800;
    public static final double DEFAULT_BATTLEFIELD_HEIGHT = 600;

    private final String heroName;
    private final IAdvancedRobot robot;
    private final IAdvancedRobotPeer peer;

    private double battlefieldWidth = DEFAULT_BATTLEFIELD_WIDTH;
    private double battlefieldHeight = DEFAULT_BATTLEFIELD_HEIGHT;
    private int numRounds = 1;

    private int heroIndex = -1;
    private int roundNum = 0;
    private Reconstructor reconstructor;
    private RobotReplayDriver robotDriver;
    private PeerReplayDriver peerDriver;
    private Reconstructor.TickResult lastResult;
    private BattleEndEvent battleEndEvent;

    /**
     * Select the hero by robot index (0-based contestant index in the battle).
     */
    public Dejavu(int heroIndex, IAdvancedRobot robot, IAdvancedRobotPeer peer) {
        this.heroIndex = heroIndex;
        this.heroName = null;
        this.robot = robot;
        this.peer = peer;
    }

    /** Select the hero by name (matched against snapshot robot names). */
    public Dejavu(String heroName, IAdvancedRobot robot, IAdvancedRobotPeer peer) {
        this.heroName = heroName;
        this.robot = robot;
        this.peer = peer;
    }

    @Override
    public void onBattleStarted(BattleStartedEvent event) {
        int robotsCount = event.getRobotsCount();
        if (robotsCount > 0 && robotsCount != 2) {
            throw new IllegalStateException(
                    "Déjàvu supports standard 1v1 battles only; the battle has "
                            + robotsCount + " robots");
        }
        BattleRules rules = event.getBattleRules();
        if (rules != null) {
            battlefieldWidth = rules.getBattlefieldWidth();
            battlefieldHeight = rules.getBattlefieldHeight();
            numRounds = rules.getNumRounds();
        }
        // numRounds drives the per-round bullet-id range; keep it at least 1 so a
        // null/absent rules object never produces a zero-length range.
        if (numRounds < 1) {
            numRounds = 1;
        }
        robotDriver = new RobotReplayDriver(robot);
        peerDriver = new PeerReplayDriver(peer);
        battleEndEvent = null;
        if (peer instanceof IReplayRobotPeer) {
            ((IReplayRobotPeer) peer).loadBattleRules(battlefieldWidth, battlefieldHeight, numRounds);
        }
    }

    @Override
    public void onRoundStarted(RoundStartedEvent event) {
        ITurnSnapshot start = event.getStartSnapshot();
        IRobotSnapshot[] robots = start.getRobots();
        if (robots.length != 2) {
            throw new IllegalStateException(
                    "Déjàvu supports standard 1v1 battles only; the round snapshot has "
                            + robots.length + " robots");
        }
        if (heroIndex < 0) {
            heroIndex = resolveHeroIndex(start);
        }
        if (heroIndex >= robots.length) {
            throw new IllegalArgumentException(
                    "Hero index " + heroIndex + " is out of range for a battle with "
                            + robots.length + " robots");
        }
        roundNum = start.getRound();
        if (reconstructor == null) {
            reconstructor = new Reconstructor(heroIndex, battlefieldWidth, battlefieldHeight, numRounds);
        }
        reconstructor.startRound(start);
    }

    @Override
    public void onTurnEnded(TurnEndedEvent event) {
        if (reconstructor == null) {
            return;
        }
        ITurnSnapshot cur = event.getTurnSnapshot();
        Reconstructor.TickResult result = reconstructor.onTurn(cur);
        lastResult = result;
        if (result == null) {
            return;
        }
        // Back-fill the fake peer's getters from the hero's ground-truth state for
        // this turn, so a replayed hero reading e.g. getX()/getGunHeading() inside
        // a delivered event handler sees what it saw in the original battle.
        if (peer instanceof IReplayRobotPeer) {
            IRobotSnapshot[] robots = cur.getRobots();
            IRobotSnapshot hero = robots[heroIndex];
            int others = 0;
            for (int i = 0; i < robots.length; i++) {
                if (i != heroIndex && robots[i].getState().isAlive()) {
                    others++;
                }
            }
            ((IReplayRobotPeer) peer).loadState(hero, cur.getTurn(), roundNum, others);
        }
        robotDriver.deliver(result.events);
        peerDriver.record(result.commands);
    }

    @Override
    public void onBattleCompleted(BattleCompletedEvent event) {
        battleEndEvent = BattleEndEvent.from(event);
    }

    /** The {@link Reconstructor.TickResult} produced for the most recent turn, or {@code null}. */
    Reconstructor.TickResult getLastResult() {
        return lastResult;
    }

    public BattleEndEvent getBattleEndEvent() {
        return battleEndEvent;
    }

    private int resolveHeroIndex(ITurnSnapshot start) {
        IRobotSnapshot[] robots = start.getRobots();
        if (heroName == null) {
            throw new IllegalStateException(
                    "Cannot resolve hero index: no hero name was provided");
        }
        for (int i = 0; i < robots.length; i++) {
            if (heroName.equals(robots[i].getName())) {
                return i;
            }
        }
        StringBuilder available = new StringBuilder();
        for (int i = 0; i < robots.length; i++) {
            if (i > 0) {
                available.append(", ");
            }
            available.append(robots[i].getName());
        }
        throw new IllegalArgumentException(
                "Hero robot '" + heroName + "' not found in the battle snapshot; available robots: ["
                        + available + "]");
    }
}
