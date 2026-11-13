package fr.cobblepulse.storage;

import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.LeagueData;
import fr.cobblepulse.data.PlayerData;

import java.util.List;
import java.util.UUID;

/**
 * Couche d'abstraction de persistance (DAO). Deux implementations : JSON local (atomique) et MySQL (HikariCP).
 * Toutes les methodes sont bloquantes et ne doivent etre appelees que depuis le thread d'E/S ({@link DataService}).
 */
public interface Storage {
    String name();

    void init() throws Exception;

    void close();

    List<ClanData> loadClans() throws Exception;

    void saveClan(ClanData clan) throws Exception;

    void deleteClan(String clanId) throws Exception;

    LeagueData loadLeague() throws Exception;

    void saveLeague(LeagueData data) throws Exception;

    PlayerData loadPlayer(UUID uuid) throws Exception;

    void savePlayer(PlayerData data) throws Exception;

    List<UUID> listPlayers() throws Exception;

    /** Stockage cle/valeur JSON pour l'etat annexe (guerres, tournoi, recompenses...). */
    String loadKv(String key) throws Exception;

    void saveKv(String key, String json) throws Exception;
}
