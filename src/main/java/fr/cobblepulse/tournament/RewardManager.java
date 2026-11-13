package fr.cobblepulse.tournament;

import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.ItemSerial;
import fr.cobblepulse.core.Json;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.data.PlayerData;
import fr.cobblepulse.data.TournamentData;
import fr.cobblepulse.gui.SharedChestMenu;
import fr.cobblepulse.storage.DataService;
import fr.cobblepulse.storage.PlayerDataManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recompenses hybrides : coffres d'items edites en GUI par palier + commandes console (tournament_rewards.toml). */
public final class RewardManager {
    public static final List<String> TIERS = List.of("first", "second", "third", "top5", "top6_10", "top11_20", "top21_50", "top51_100", "top101_500");
    private static final Map<String, SimpleContainer> CHESTS = new HashMap<>();

    private RewardManager() {}

    public static String label(String tier) {
        return Cfg.rewards.str("tiers." + tier + ".label", tier);
    }

    /** Palier correspondant a un classement (meilleure place du groupe d'egalite). */
    public static String tierFor(int placement) {
        if (placement <= 0) return null;
        for (String tier : TIERS) {
            int min = Cfg.rewards.i("tiers." + tier + ".min", 0);
            int max = Cfg.rewards.i("tiers." + tier + ".max", 0);
            if (placement >= min && placement <= max) return tier;
        }
        return null;
    }

    /** Chargement des coffres au demarrage (aucune E/S bloquante ensuite). */
    public static void preload() {
        CHESTS.clear();
        for (String tier : TIERS) chest(tier);
    }

    public static SimpleContainer chest(String tier) {
        SimpleContainer c = CHESTS.get(tier);
        if (c == null) {
            c = new SimpleContainer(54);
            String json = DataService.blocking(s -> s.loadKv("rewards_" + tier));
            if (json != null) {
                String data = Json.GSON.fromJson(json, String.class);
                ItemSerial.decodeInto(data, c);
            }
            SimpleContainer finalC = c;
            c.addListener(x -> DataService.markKv("rewards_" + tier, () -> ItemSerial.encode(finalC)));
            CHESTS.put(tier, c);
        }
        return c;
    }

    /** /tournoi rewards edit [palier] : le staff glisse-depose directement les items (vanilla ou mods tiers). */
    public static void openEditor(ServerPlayer staff, String tier) {
        SimpleContainer c = chest(tier);
        staff.openMenu(new SimpleMenuProvider((id, inv, p) -> new SharedChestMenu(id, inv, c, 6,
                pl -> pl instanceof ServerPlayer sp && Perms.staff(sp, Perms.T_REWARDS),
                pl -> DataService.markKv("rewards_" + tier, () -> ItemSerial.encode(c))),
                fr.cobblepulse.core.TextUtil.legacy("&6Récompenses : " + label(tier))));
    }

    /** Distribution de fin de tournoi : items a recuperer via /tournoi claim + commandes console. */
    public static void distribute(TournamentData t) {
        MinecraftServer server = CobblePulse.server();
        for (TournamentData.Participant p : t.participants.values()) {
            String tier = tierFor(p.placementLo);
            if (tier == null) continue;
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack s : ItemSerial.nonEmpty(contents(chest(tier)))) items.add(s.copy());
            UUID u = UUID.fromString(p.uuid);
            String place = p.placementLo == p.placementHi ? String.valueOf(p.placementLo) : p.placementLo + "-" + p.placementHi;
            if (!items.isEmpty()) {
                PlayerDataManager.modify(u, p.name, d -> {
                    List<ItemStack> pending = ItemSerial.decode(d.pendingRewards);
                    pending = ItemSerial.nonEmpty(pending);
                    pending.addAll(items);
                    d.pendingRewards = ItemSerial.encode(pending);
                    Msg.send(u, "tournament.rewards_available", "place", place, "tier", label(tier));
                });
            }
            for (String cmd : Cfg.rewards.list("tiers." + tier + ".commands")) {
                String c = cmd.replace("%player%", p.name).replace("%place%", place).replace("%tournament%", t.name).replace("%tier%", t.tier);
                if (c.startsWith("/")) c = c.substring(1);
                try {
                    if (server != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                } catch (Throwable e) {
                    CobblePulse.LOG.error("[CobblePulse] Commande de recompense en echec : {}", c, e);
                }
            }
        }
    }

    private static List<ItemStack> contents(SimpleContainer c) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) out.add(c.getItem(i));
        return out;
    }

    public static int pendingCount(PlayerData d) {
        return ItemSerial.nonEmpty(ItemSerial.decode(d.pendingRewards)).size();
    }

    /** /tournoi claim : remet les items en attente dans l'inventaire (le surplus reste en attente). */
    public static void claim(ServerPlayer p) {
        if (!Perms.player(p, Perms.TOURNAMENT_CLAIM)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        PlayerData d = PlayerDataManager.online(p);
        List<ItemStack> pending = ItemSerial.nonEmpty(ItemSerial.decode(d.pendingRewards));
        if (pending.isEmpty()) {
            Msg.send(p, "tournament.no_rewards");
            return;
        }
        int given = 0;
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack s : pending) {
            int before = s.getCount();
            p.getInventory().add(s);
            if (s.getCount() < before) given++;
            if (!s.isEmpty()) left.add(s);
        }
        d.pendingRewards = left.isEmpty() ? "" : ItemSerial.encode(left);
        PlayerDataManager.save(d);
        p.containerMenu.broadcastChanges();
        Msg.send(p, left.isEmpty() ? "tournament.rewards_claimed" : "tournament.rewards_partial", "count", given, "left", left.size());
    }
}
