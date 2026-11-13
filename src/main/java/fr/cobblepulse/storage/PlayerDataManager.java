package fr.cobblepulse.storage;

import com.mojang.authlib.GameProfile;
import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.PlayerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Cache RAM des dossiers joueurs (charges a la connexion, ou a la demande pour les joueurs hors-ligne). */
public final class PlayerDataManager {
    private static final Map<UUID, PlayerData> CACHE = new HashMap<>();
    private static final Map<UUID, Long> LAST_ACCESS = new HashMap<>();
    private static final Map<UUID, List<Consumer<PlayerData>>> WAITING = new HashMap<>();

    private PlayerDataManager() {}

    public static void start() {
        Scheduler.repeat(20 * 60, 20 * 60 * 5, PlayerDataManager::evict);
    }

    public static PlayerData getIfLoaded(UUID uuid) {
        PlayerData d = CACHE.get(uuid);
        if (d != null) LAST_ACCESS.put(uuid, TimeFmt.now());
        return d;
    }

    /** Joueur en ligne : ses donnees sont toujours chargees (precharge a la connexion). */
    public static PlayerData online(ServerPlayer p) {
        PlayerData d = getIfLoaded(p.getUUID());
        if (d == null) {
            d = create(p.getUUID(), p.getGameProfile().getName());
            CACHE.put(p.getUUID(), d);
            load(p.getUUID(), p.getGameProfile().getName(), null);
        }
        return d;
    }

    /** Recupere (ou charge de maniere asynchrone) le dossier d'un joueur et appelle le consommateur sur le thread principal. */
    public static void with(UUID uuid, String nameHint, Consumer<PlayerData> consumer) {
        if (!WAITING.containsKey(uuid)) {
            PlayerData d = getIfLoaded(uuid);
            if (d != null) {
                if (nameHint != null && d.name == null) d.name = nameHint;
                consumer.accept(d);
                return;
            }
        }
        load(uuid, nameHint, consumer);
    }

    private static void load(UUID uuid, String nameHint, Consumer<PlayerData> consumer) {
        List<Consumer<PlayerData>> waiters = WAITING.get(uuid);
        if (waiters != null) {
            if (consumer != null) waiters.add(consumer);
            return;
        }
        waiters = new ArrayList<>();
        if (consumer != null) waiters.add(consumer);
        WAITING.put(uuid, waiters);
        DataService.async(s -> s.loadPlayer(uuid)).whenComplete((loaded, err) -> DataService.main(() -> {
            PlayerData cur = CACHE.get(uuid);
            PlayerData result;
            if (loaded != null) {
                result = loaded;
                if (cur != null) mergeInto(result, cur);
            } else {
                result = cur != null ? cur : create(uuid, nameHint);
            }
            if (nameHint != null) result.name = nameHint;
            if (result.name == null) result.name = uuid.toString().substring(0, 8);
            CACHE.put(uuid, result);
            LAST_ACCESS.put(uuid, TimeFmt.now());
            List<Consumer<PlayerData>> list = WAITING.remove(uuid);
            if (cur != null && cur != result) DataService.markPlayer(result);
            if (list != null) {
                for (Consumer<PlayerData> c : list) {
                    try {
                        c.accept(result);
                    } catch (Throwable t) {
                        CobblePulse.LOG.error("[CobblePulse] Erreur de traitement des donnees joueur", t);
                    }
                }
            }
        }));
    }

    /** Modifie un dossier (en ligne ou non) puis le marque pour sauvegarde. */
    public static void modify(UUID uuid, String nameHint, Consumer<PlayerData> mutation) {
        with(uuid, nameHint, d -> {
            mutation.accept(d);
            DataService.markPlayer(d);
        });
    }

    public static void save(PlayerData d) {
        DataService.markPlayer(d);
    }

    private static PlayerData create(UUID uuid, String name) {
        PlayerData d = new PlayerData();
        d.uuid = uuid.toString();
        d.name = name;
        return d;
    }

    /** Si un dossier temporaire a ete modifie avant la fin du chargement, on conserve les ajouts. */
    private static void mergeInto(PlayerData target, PlayerData temp) {
        temp.badges.forEach(target.badges::putIfAbsent);
        temp.badgeCooldowns.forEach((k, v) -> target.badgeCooldowns.merge(k, v, Math::max));
        target.placeCooldownUntil = Math.max(target.placeCooldownUntil, temp.placeCooldownUntil);
        target.audit.addAll(temp.audit);
        target.lastSeen = Math.max(target.lastSeen, temp.lastSeen);
        target.leagueWins += temp.leagueWins;
        target.leagueLosses += temp.leagueLosses;
        target.tournamentMatchWins += temp.tournamentMatchWins;
        target.tournamentMatchLosses += temp.tournamentMatchLosses;
        target.tournamentsWon += temp.tournamentsWon;
        target.tournamentsPlayed += temp.tournamentsPlayed;
        temp.pokemonUsage.forEach((k, v) -> target.pokemonUsage.merge(k, v, Integer::sum));
        if (temp.favoritePokemon != null) target.favoritePokemon = temp.favoritePokemon;
        if (temp.pendingRewards != null && !temp.pendingRewards.isEmpty() && (target.pendingRewards == null || target.pendingRewards.isEmpty())) {
            target.pendingRewards = temp.pendingRewards;
        }
    }

    public static void onJoin(ServerPlayer p) {
        with(p.getUUID(), p.getGameProfile().getName(), d -> {
            d.lastSeen = TimeFmt.now();
            DataService.markPlayer(d);
        });
    }

    public static void onQuit(ServerPlayer p) {
        PlayerData d = CACHE.get(p.getUUID());
        if (d != null) {
            d.lastSeen = TimeFmt.now();
            DataService.markPlayer(d);
        }
        LAST_ACCESS.put(p.getUUID(), TimeFmt.now());
    }

    private static void evict() {
        MinecraftServer server = CobblePulse.server();
        if (server == null) return;
        DataService.flushDirty();
        long limit = TimeFmt.now() - 30 * TimeFmt.MINUTE;
        CACHE.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null
                && LAST_ACCESS.getOrDefault(uuid, 0L) < limit && !WAITING.containsKey(uuid));
        LAST_ACCESS.keySet().retainAll(CACHE.keySet());
    }

    public static void clearAll() {
        CACHE.clear();
        LAST_ACCESS.clear();
        WAITING.clear();
    }

    public static java.util.Collection<PlayerData> cached() {
        return CACHE.values();
    }

    /** Resolution nom -> profil (joueur en ligne, puis cache des profils du serveur). */
    public static Optional<GameProfile> resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(online.getGameProfile());
        for (PlayerData d : CACHE.values()) {
            if (d.name != null && d.name.equalsIgnoreCase(name)) return Optional.of(new GameProfile(UUID.fromString(d.uuid), d.name));
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> p = cache.get(name);
            if (p.isPresent()) return p;
        }
        return Optional.empty();
    }

    public static String nameOf(UUID uuid) {
        MinecraftServer server = CobblePulse.server();
        if (server != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) return p.getGameProfile().getName();
            PlayerData d = CACHE.get(uuid);
            if (d != null && d.name != null) return d.name;
            GameProfileCache cache = server.getProfileCache();
            if (cache != null) {
                Optional<GameProfile> gp = cache.get(uuid);
                if (gp.isPresent()) return gp.get().getName();
            }
        }
        return uuid.toString().substring(0, 8);
    }
}
