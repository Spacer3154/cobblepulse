package fr.cobblepulse.data;

import java.util.HashMap;
import java.util.Map;

/** Guerre d'influence entre deux clans. */
public class WarData {
    public String id;
    public String attackerId;
    public String defenderId;
    public String attackerName;
    public String defenderName;
    public long startedAt;
    public long endsAt;
    public int attackerPoints;
    public int defenderPoints;
    /** "uuidA|uuidB|jour" -> nombre de points deja comptes (anti-farm). */
    public Map<String, Integer> pairCounts = new HashMap<>();
    public boolean finished;
    public String winnerId;
    public long stolenInfluence;
}
