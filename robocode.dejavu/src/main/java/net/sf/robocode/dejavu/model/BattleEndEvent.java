package net.sf.robocode.dejavu.model;

import robocode.BattleResults;
import robocode.control.events.BattleCompletedEvent;

/** Battle-end score event reconstructed from Robocode's completed-battle callback. */
public final class BattleEndEvent {
    private final int rounds;
    private final Score[] scores;

    public BattleEndEvent(int rounds, Score[] scores) {
        this.rounds = rounds;
        this.scores = scores.clone();
    }

    public int rounds() {
        return rounds;
    }

    public Score[] scores() {
        return scores.clone();
    }

    public Score score(int robotIndex) {
        return scores[robotIndex];
    }

    public int resultForPerspective(int robotIndex) {
        Score ours = scores[robotIndex];
        Score theirs = scores[1 - robotIndex];
        int firsts = Integer.compare(ours.firsts(), theirs.firsts());
        if (firsts != 0) {
            return firsts;
        }
        return Integer.compare(ours.score(), theirs.score());
    }

    public static BattleEndEvent from(BattleCompletedEvent event) {
        BattleResults[] results = event.getIndexedResults();
        if (results == null || results.length == 0) {
            results = event.getSortedResults();
        }
        Score[] scores = new Score[results.length];
        for (int i = 0; i < results.length; i++) {
            BattleResults result = results[i];
            scores[i] = new Score(
                    result.getTeamLeaderName(),
                    result.getRank(),
                    result.getScore(),
                    result.getFirsts(),
                    result.getSeconds(),
                    result.getSurvival(),
                    result.getBulletDamage(),
                    result.getRamDamage());
        }
        int rounds = event.getBattleRules() != null ? event.getBattleRules().getNumRounds() : 1;
        return new BattleEndEvent(rounds, scores);
    }

    public static final class Score {
        private final String robotName;
        private final int rank;
        private final int score;
        private final int firsts;
        private final int seconds;
        private final int survival;
        private final int bulletDamage;
        private final int ramDamage;

        public Score(String robotName, int rank, int score, int firsts, int seconds,
                int survival, int bulletDamage, int ramDamage) {
            this.robotName = robotName;
            this.rank = rank;
            this.score = score;
            this.firsts = firsts;
            this.seconds = seconds;
            this.survival = survival;
            this.bulletDamage = bulletDamage;
            this.ramDamage = ramDamage;
        }

        public String robotName() {
            return robotName;
        }

        public int rank() {
            return rank;
        }

        public int score() {
            return score;
        }

        public int firsts() {
            return firsts;
        }

        public int seconds() {
            return seconds;
        }

        public int survival() {
            return survival;
        }

        public int bulletDamage() {
            return bulletDamage;
        }

        public int ramDamage() {
            return ramDamage;
        }
    }
}