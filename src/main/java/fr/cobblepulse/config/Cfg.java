package fr.cobblepulse.config;

import fr.cobblepulse.CobblePulse;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Acces centralise a tous les fichiers de configuration de config/cobblepulse/. */
public final class Cfg {
    public static final List<String> DEFAULT_TIERS = List.of("ag", "uber", "ou", "uu", "ru", "pu", "zu", "monotype", "nfe", "lc");

    public static TomlFile storage;
    public static TomlFile league;
    public static TomlFile items;
    public static TomlFile clans;
    public static TomlFile wars;
    public static TomlFile tournament;
    public static TomlFile rewards;
    public static TomlFile messages;
    public static final Map<String, TierRules> TIERS = new LinkedHashMap<>();

    private Cfg() {}

    public static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("cobblepulse");
    }

    public static Path dataDir() {
        return root().resolve("data");
    }

    public static void loadAll() {
        Path root = root();
        storage = TomlFile.load(root, "storage.toml");
        league = TomlFile.load(root, "league.toml");
        items = TomlFile.load(root, "league_items.toml");
        clans = TomlFile.load(root, "clans.toml");
        wars = TomlFile.load(root, "clan_wars.toml");
        tournament = TomlFile.load(root, "tournament.toml");
        rewards = TomlFile.load(root, "tournament_rewards.toml");
        messages = TomlFile.load(root, "messages.toml");
        loadTiers(root);
    }

    private static void loadTiers(Path root) {
        TIERS.clear();
        Map<String, TomlFile> files = new LinkedHashMap<>();
        for (String t : DEFAULT_TIERS) {
            files.put(t, TomlFile.load(root, "tournament_tiers/" + t + ".toml"));
        }
        // Tiers personnalises ajoutes par l'administrateur
        Path dir = root.resolve("tournament_tiers");
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".toml")).forEach(p -> {
                String id = p.getFileName().toString().replace(".toml", "").toLowerCase(Locale.ROOT);
                if (!files.containsKey(id)) files.put(id, TomlFile.loadPlain(p));
            });
        } catch (Exception e) {
            CobblePulse.LOG.warn("[CobblePulse] Lecture de tournament_tiers impossible: {}", e.toString());
        }
        for (Map.Entry<String, TomlFile> e : files.entrySet()) {
            TIERS.put(e.getKey(), TierRules.from(e.getKey(), e.getValue()));
        }
        for (TierRules r : TIERS.values()) r.resolveInheritance(TIERS);
    }

    public static boolean resourceExists(String path) {
        try (InputStream in = Cfg.class.getResourceAsStream(path)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }
}
