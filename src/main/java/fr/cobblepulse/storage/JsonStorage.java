package fr.cobblepulse.storage;

import fr.cobblepulse.core.Json;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.LeagueData;
import fr.cobblepulse.data.PlayerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Mode 1 : fichiers JSON locaux.
 * data/clans/[clan_id].json, data/league/holders.json, data/badge_cases/[uuid].json, data/state/[cle].json
 * Ecritures atomiques : fichier .tmp puis renommage.
 */
public final class JsonStorage implements Storage {
    private final Path root;
    private final Path clans;
    private final Path league;
    private final Path badgeCases;
    private final Path state;

    public JsonStorage(Path dataDir) {
        this.root = dataDir;
        this.clans = dataDir.resolve("clans");
        this.league = dataDir.resolve("league");
        this.badgeCases = dataDir.resolve("badge_cases");
        this.state = dataDir.resolve("state");
    }

    @Override
    public String name() {
        return "JSON (" + root + ")";
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(clans);
        Files.createDirectories(league);
        Files.createDirectories(badgeCases);
        Files.createDirectories(state);
    }

    @Override
    public void close() {}

    static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> T read(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) return null;
        String s = Files.readString(file, StandardCharsets.UTF_8);
        if (s.isBlank()) return null;
        return Json.GSON.fromJson(s, type);
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    @Override
    public List<ClanData> loadClans() throws IOException {
        List<ClanData> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(clans)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json")).toList()) {
                ClanData c = read(p, ClanData.class);
                if (c != null && c.id != null) out.add(c);
            }
        }
        return out;
    }

    @Override
    public void saveClan(ClanData clan) throws IOException {
        writeAtomic(clans.resolve(safe(clan.id) + ".json"), Json.GSON.toJson(clan));
    }

    @Override
    public void deleteClan(String clanId) throws IOException {
        Files.deleteIfExists(clans.resolve(safe(clanId) + ".json"));
    }

    @Override
    public LeagueData loadLeague() throws IOException {
        return read(league.resolve("holders.json"), LeagueData.class);
    }

    @Override
    public void saveLeague(LeagueData data) throws IOException {
        writeAtomic(league.resolve("holders.json"), Json.GSON.toJson(data));
    }

    @Override
    public PlayerData loadPlayer(UUID uuid) throws IOException {
        return read(badgeCases.resolve(uuid + ".json"), PlayerData.class);
    }

    @Override
    public void savePlayer(PlayerData data) throws IOException {
        writeAtomic(badgeCases.resolve(safe(data.uuid) + ".json"), Json.GSON.toJson(data));
    }

    @Override
    public List<UUID> listPlayers() throws IOException {
        List<UUID> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(badgeCases)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json")).toList()) {
                String n = p.getFileName().toString().replace(".json", "");
                try {
                    out.add(UUID.fromString(n));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    @Override
    public String loadKv(String key) throws IOException {
        Path f = state.resolve(safe(key) + ".json");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    @Override
    public void saveKv(String key, String json) throws IOException {
        writeAtomic(state.resolve(safe(key) + ".json"), json);
    }
}
