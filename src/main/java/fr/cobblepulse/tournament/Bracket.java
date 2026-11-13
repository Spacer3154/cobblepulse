package fr.cobblepulse.tournament;

import fr.cobblepulse.data.TournamentData;

import java.util.ArrayList;
import java.util.List;

/** Generation de l'arbre a elimination directe avec gestion automatique des byes. */
public final class Bracket {
    private Bracket() {}

    public static int nextPow2(int n) {
        int s = 1;
        while (s < n) s <<= 1;
        return Math.max(2, s);
    }

    /**
     * Premiere ronde : les participants (deja melanges) sont repartis de sorte que chaque bye affronte un joueur reel.
     * Pour n participants et une taille S = puissance de 2 superieure, il y a S - n byes, tous dans des matchs distincts.
     */
    public static TournamentData.Round firstRound(List<TournamentData.Participant> shuffled) {
        int n = shuffled.size();
        int size = nextPow2(n);
        int half = size / 2;
        int fullMatches = n - half;
        TournamentData.Round r = new TournamentData.Round();
        r.index = 0;
        r.slots = size;
        for (int k = 0; k < half; k++) {
            TournamentData.Match m = new TournamentData.Match();
            m.index = k;
            TournamentData.Participant a = shuffled.get(k);
            m.p1 = a.uuid;
            m.n1 = a.name;
            if (k < fullMatches) {
                TournamentData.Participant b = shuffled.get(half + k);
                m.p2 = b.uuid;
                m.n2 = b.name;
            } else {
                m.bye = true;
            }
            r.matches.add(m);
        }
        // Repartit les byes dans l'arbre (alternance) pour eviter qu'ils se concentrent dans une moitie
        List<TournamentData.Match> byes = new ArrayList<>();
        List<TournamentData.Match> full = new ArrayList<>();
        for (TournamentData.Match m : r.matches) (m.bye ? byes : full).add(m);
        List<TournamentData.Match> mixed = new ArrayList<>();
        int bi = 0, fi = 0;
        while (bi < byes.size() || fi < full.size()) {
            if (fi < full.size()) mixed.add(full.get(fi++));
            if (bi < byes.size()) mixed.add(byes.get(bi++));
        }
        for (int i = 0; i < mixed.size(); i++) mixed.get(i).index = i;
        r.matches = mixed;
        return r;
    }

    /** Ronde suivante : vainqueur du match 2k contre vainqueur du match 2k+1. */
    public static TournamentData.Round nextRound(TournamentData.Round prev, TournamentData t) {
        TournamentData.Round r = new TournamentData.Round();
        r.index = prev.index + 1;
        r.slots = prev.slots / 2;
        List<TournamentData.Match> main = new ArrayList<>();
        for (TournamentData.Match m : prev.matches) if (!m.thirdPlace) main.add(m);
        for (int k = 0; k + 1 < main.size(); k += 2) {
            TournamentData.Match m = new TournamentData.Match();
            m.index = k / 2;
            String w1 = main.get(k).winner;
            String w2 = main.get(k + 1).winner;
            m.p1 = w1;
            m.n1 = name(t, w1);
            m.p2 = w2;
            m.n2 = name(t, w2);
            if (w1 == null || w2 == null) {
                m.bye = true;
                if (w1 == null) {
                    m.p1 = w2;
                    m.n1 = name(t, w2);
                    m.p2 = null;
                    m.n2 = null;
                }
            }
            r.matches.add(m);
        }
        return r;
    }

    static String name(TournamentData t, String uuid) {
        if (uuid == null) return null;
        TournamentData.Participant p = t.participants.get(uuid);
        return p == null ? uuid.substring(0, 8) : p.name;
    }

    /** Cle de nom de ronde selon le nombre d'emplacements (64 -> 32e de finale ... 2 -> finale). */
    public static String roundKey(int slots) {
        return switch (slots) {
            case 2 -> "final";
            case 4 -> "semi";
            case 8 -> "quarter";
            case 16 -> "eighth";
            case 32 -> "sixteenth";
            case 64 -> "thirty_second";
            default -> "generic";
        };
    }
}
