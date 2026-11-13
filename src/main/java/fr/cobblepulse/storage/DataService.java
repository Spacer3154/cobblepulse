package fr.cobblepulse.storage;

import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.Json;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.LeagueData;
import fr.cobblepulse.data.PlayerData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Service de persistance asynchrone : toutes les E/S (JSON ou MySQL) s'executent sur un thread dedie
 * pour ne jamais impacter le TPS. Les objets vivants (thread principal) sont copies avant ecriture.
 */
public final class DataService {
    @FunctionalInterface
    public interface IoCall<T> {
        T call(Storage s) throws Exception;
    }

    private static ExecutorService io;
    private static Storage storage;
    private static final Map<String, ClanData> DIRTY_CLANS = new LinkedHashMap<>();
    private static final Map<UUID, PlayerData> DIRTY_PLAYERS = new LinkedHashMap<>();
    private static final Map<String, Supplier<Object>> DIRTY_KV = new LinkedHashMap<>();
    private static LeagueData dirtyLeague;
    private static Consumer<ClanData> clanPreSave = c -> {};
    private static Scheduler.Task flushTask;

    private DataService() {}

    public static Storage storage() {
        return storage;
    }

    /** Initialise le backend choisi dans storage.toml (bloquant, au demarrage du serveur). */
    public static void start() {
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CobblePulse-IO");
            t.setDaemon(true);
            return t;
        });
        String mode = Cfg.storage.str("mode", "json").toLowerCase(Locale.ROOT).trim();
        Storage s = mode.equals("mysql") ? new MySqlStorage(Cfg.storage) : new JsonStorage(Cfg.dataDir());
        try {
            io.submit(() -> {
                s.init();
                return null;
            }).get(30, TimeUnit.SECONDS);
            storage = s;
        } catch (Exception e) {
            CobblePulse.LOG.error("[CobblePulse] Echec d'initialisation du stockage {} ; repli sur JSON local.", s.name(), e);
            JsonStorage fallback = new JsonStorage(Cfg.dataDir());
            try {
                fallback.init();
            } catch (Exception ex) {
                CobblePulse.LOG.error("[CobblePulse] Stockage JSON indisponible", ex);
            }
            storage = fallback;
        }
        CobblePulse.LOG.info("[CobblePulse] Stockage actif : {}", storage.name());
        flushTask = Scheduler.repeat(60, 60, DataService::flushDirty);
    }

    public static void setClanPreSave(Consumer<ClanData> hook) {
        clanPreSave = hook;
    }

    /** Execute un appel de stockage sur le thread d'E/S. */
    public static <T> CompletableFuture<T> async(IoCall<T> call) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (io == null || io.isShutdown()) {
            f.completeExceptionally(new IllegalStateException("Stockage arrete"));
            return f;
        }
        io.execute(() -> {
            try {
                f.complete(call.call(storage));
            } catch (Throwable t) {
                CobblePulse.LOG.error("[CobblePulse] Erreur de stockage", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Execute une operation bloquante (demarrage uniquement). */
    public static <T> T blocking(IoCall<T> call) {
        try {
            return async(call).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            CobblePulse.LOG.error("[CobblePulse] Operation de stockage bloquante echouee", e);
            return null;
        }
    }

    /** Planifie un retour sur le thread principal du serveur. */
    public static void main(Runnable r) {
        if (CobblePulse.server() != null) CobblePulse.server().execute(r);
    }

    public static void markClan(ClanData c) {
        DIRTY_CLANS.put(c.id, c);
    }

    public static void markPlayer(PlayerData p) {
        DIRTY_PLAYERS.put(UUID.fromString(p.uuid), p);
    }

    public static void markLeague(LeagueData d) {
        dirtyLeague = d;
    }

    public static void markKv(String key, Supplier<Object> supplier) {
        DIRTY_KV.put(key, supplier);
    }

    public static void deleteClan(String id) {
        DIRTY_CLANS.remove(id);
        async(s -> {
            s.deleteClan(id);
            return null;
        });
    }

    /** Copie les objets modifies (thread principal) puis les ecrit sur le thread d'E/S. */
    public static void flushDirty() {
        if (storage == null) return;
        if (!DIRTY_CLANS.isEmpty()) {
            List<ClanData> snaps = new ArrayList<>();
            for (ClanData c : DIRTY_CLANS.values()) {
                clanPreSave.accept(c);
                snaps.add(Json.copy(c, ClanData.class));
            }
            DIRTY_CLANS.clear();
            async(s -> {
                for (ClanData c : snaps) s.saveClan(c);
                return null;
            });
        }
        if (!DIRTY_PLAYERS.isEmpty()) {
            List<PlayerData> snaps = new ArrayList<>();
            for (PlayerData p : DIRTY_PLAYERS.values()) snaps.add(Json.copy(p, PlayerData.class));
            DIRTY_PLAYERS.clear();
            async(s -> {
                for (PlayerData p : snaps) s.savePlayer(p);
                return null;
            });
        }
        if (dirtyLeague != null) {
            LeagueData snap = Json.copy(dirtyLeague, LeagueData.class);
            dirtyLeague = null;
            async(s -> {
                s.saveLeague(snap);
                return null;
            });
        }
        if (!DIRTY_KV.isEmpty()) {
            Map<String, String> snaps = new LinkedHashMap<>();
            for (Map.Entry<String, Supplier<Object>> e : DIRTY_KV.entrySet()) {
                snaps.put(e.getKey(), Json.GSON.toJson(e.getValue().get()));
            }
            DIRTY_KV.clear();
            async(s -> {
                for (Map.Entry<String, String> e : snaps.entrySet()) s.saveKv(e.getKey(), e.getValue());
                return null;
            });
        }
    }

    /** Ecrit tout et arrete proprement le thread d'E/S (arret du serveur). */
    public static void shutdown() {
        if (flushTask != null) flushTask.cancel();
        flushDirty();
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                    CobblePulse.LOG.warn("[CobblePulse] Ecritures en attente non terminees apres 30s");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (storage != null) storage.close();
    }
}
