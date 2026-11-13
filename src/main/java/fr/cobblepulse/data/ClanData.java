package fr.cobblepulse.data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Donnees persistantes d'un clan. */
public class ClanData {
    public String id;
    public String name;
    public String tag;
    public String tagColor = "&7";
    public String leader;
    public String mode = "INVITE_ONLY";
    public String description = "";
    public long influence;
    public int warWins;
    public int warLosses;
    public int warDraws;
    public int pvpWins;
    public int pvpLosses;
    public long createdAt;
    public Map<String, ClanMember> members = new LinkedHashMap<>();
    public Map<String, Long> invites = new HashMap<>();
    public Map<String, JoinRequest> requests = new LinkedHashMap<>();
    /** Inventaire partage (54 emplacements, NBT Base64). */
    public String inventory = "";

    public static class ClanMember {
        public String name;
        public String role = "MEMBER";
        public long joinedAt;

        public ClanMember() {}

        public ClanMember(String name, String role, long joinedAt) {
            this.name = name;
            this.role = role;
            this.joinedAt = joinedAt;
        }
    }

    public static class JoinRequest {
        public String name;
        public long time;

        public JoinRequest() {}

        public JoinRequest(String name, long time) {
            this.name = name;
            this.time = time;
        }
    }
}
