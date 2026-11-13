package fr.cobblepulse.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dossier joueur : etui a badges, cooldowns, journal d'audit et statistiques. */
public class PlayerData {
    public String uuid;
    public String name;
    public Map<String, BadgeRecord> badges = new LinkedHashMap<>();
    /** postId -> fin du cooldown de badge (epoch ms). */
    public Map<String, Long> badgeCooldowns = new HashMap<>();
    public long placeCooldownUntil;
    public List<AuditEntry> audit = new ArrayList<>();
    public int leagueWins;
    public int leagueLosses;
    public int tournamentMatchWins;
    public int tournamentMatchLosses;
    public int tournamentsWon;
    public int tournamentsPlayed;
    public String favoritePokemon;
    public Map<String, Integer> pokemonUsage = new HashMap<>();
    /** Recompenses de tournoi en attente (/tournoi claim), NBT Base64. */
    public String pendingRewards = "";
    public long lastSeen;

    public static class BadgeRecord {
        public String postId;
        public String itemId;
        public long obtainedAt;
        public String holderUuid;
        public String holderName;
        public List<String> holderTypes = new ArrayList<>();
        public long durationSeconds;
        public int turns;
        public List<MonEntry> challengerTeam = new ArrayList<>();
        public List<MonEntry> holderTeam = new ArrayList<>();
        public String battleId;
        public String note;
    }

    public static class MonEntry {
        public String species;
        public int level;
        public String held;
        public boolean shiny;

        public MonEntry() {}

        public MonEntry(String species, int level, String held, boolean shiny) {
            this.species = species;
            this.level = level;
            this.held = held;
            this.shiny = shiny;
        }

        @Override
        public String toString() {
            return species + " Nv." + level;
        }
    }

    public static class AuditEntry {
        public long time;
        public String context;
        public String postId;
        public String kind;
        public String opponentUuid;
        public String opponentName;
        public boolean asChallenger;
        public boolean won;
        public boolean forfeit;
        public long durationSeconds;
        public int turns;
        public List<MonEntry> myTeam = new ArrayList<>();
        public List<MonEntry> opponentTeam = new ArrayList<>();
        public String note;
    }
}
