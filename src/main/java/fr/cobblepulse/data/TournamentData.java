package fr.cobblepulse.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Etat persistant du tournoi courant. */
public class TournamentData {
    public String id;
    public String name;
    public String tier;
    /** OPEN, RUNNING, FINISHED, CANCELLED */
    public String state = "OPEN";
    public long createdAt;
    public String createdBy;
    public int bracketSize;
    public Map<String, Participant> participants = new LinkedHashMap<>();
    public List<Round> rounds = new ArrayList<>();
    public int currentRound = -1;
    public boolean roundActive;
    public Match thirdPlace;
    public String winnerUuid;
    public boolean rewardsDistributed;

    public static class Participant {
        public String uuid;
        public String name;
        public boolean disqualified;
        public String dqReason;
        public int placementLo;
        public int placementHi;
        public int wins;
        public int losses;

        public Participant() {}

        public Participant(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    public static class Round {
        public int index;
        /** Nombre d'emplacements de la ronde (64, 32, ..., 2). */
        public int slots;
        public boolean started;
        public List<Match> matches = new ArrayList<>();
    }

    public static class Match {
        public int index;
        public String p1;
        public String p2;
        public String n1;
        public String n2;
        public String winner;
        /** PENDING, RUNNING, DONE */
        public String status = "PENDING";
        public boolean bye;
        public boolean thirdPlace;
        public String note;
    }
}
