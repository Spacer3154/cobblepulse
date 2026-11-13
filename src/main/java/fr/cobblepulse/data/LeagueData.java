package fr.cobblepulse.data;

import fr.cobblepulse.core.Loc;

import java.util.ArrayList;
import java.util.List;

/** Etat persistant de la Ligue (holders.json / cp_league_holders). */
public class LeagueData {
    public List<PostData> posts = new ArrayList<>();

    public static class PostData {
        public String id;
        public String kind;
        public String holderUuid;
        public String holderName;
        public List<String> types = new ArrayList<>();
        public boolean open;
        public Loc tpHolder;
        public Loc tpChallenger;
        public long assignedAt;
        /** Dernier changement de titulaire (cooldown global de place). */
        public long lastHolderChange;
        public int consecutiveRefusals;
        public long holderLastSeen;
        /** Prefixe LuckPerms applique au titulaire actuel. */
        public String appliedPrefix;
    }
}
