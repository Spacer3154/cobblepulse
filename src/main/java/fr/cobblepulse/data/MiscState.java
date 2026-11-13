package fr.cobblepulse.data;

import java.util.ArrayList;
import java.util.List;

/** Etat global divers (guerres, versement d'influence, grade de vainqueur). */
public class MiscState {
    public long lastInfluencePayout;
    public List<WarData> wars = new ArrayList<>();
    /** "clanA|clanB" -> derniere fin de guerre (cooldown de redeclaration). */
    public java.util.Map<String, Long> warCooldowns = new java.util.HashMap<>();
    public String tournamentWinnerUuid;
    public String tournamentWinnerName;
    public List<ArenaData> tournamentArenas = new ArrayList<>();

    public static class ArenaData {
        public String name;
        public fr.cobblepulse.core.Loc p1;
        public fr.cobblepulse.core.Loc p2;
    }
}
