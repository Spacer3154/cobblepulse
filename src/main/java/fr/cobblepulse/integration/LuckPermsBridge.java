package fr.cobblepulse.integration;

import fr.cobblepulse.CobblePulse;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** Facade sans dependance dure vers LuckPerms (les classes LP ne sont touchees que si le mod est present). */
public final class LuckPermsBridge {
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("luckperms");
    private static boolean warned;

    private LuckPermsBridge() {}

    public static boolean present() {
        return PRESENT;
    }

    /** @return true/false si le noeud est defini, null sinon (ou LuckPerms absent). */
    public static Boolean check(ServerPlayer p, String node) {
        if (!PRESENT || p == null) return null;
        try {
            return LuckPermsHook.check(p, node);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    public static void update(UUID uuid, List<String> rmPerms, String rmPrefix, int rmPriority, String rmGroups,
                              List<String> addPerms, String addPrefix, int addPriority, String addGroups) {
        if (!PRESENT || uuid == null) return;
        try {
            LuckPermsHook.update(uuid, rmPerms, rmPrefix, rmPriority, rmGroups, addPerms, addPrefix, addPriority, addGroups);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    public static java.util.concurrent.CompletableFuture<String> describe(UUID uuid, String group) {
        if (!PRESENT) return java.util.concurrent.CompletableFuture.completedFuture("luckperms absent");
        try {
            return LuckPermsHook.describe(uuid, group);
        } catch (Throwable t) {
            return java.util.concurrent.CompletableFuture.completedFuture("erreur " + t);
        }
    }

    public static void syncGroup(String name, String display, String prefix, int priority, List<String> perms, String parent) {
        if (!PRESENT || name == null || name.isBlank()) return;
        try {
            LuckPermsHook.syncGroup(name, display, prefix, priority, perms, parent);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /** Prefixe (true) ou suffixe (false) LuckPerms actif du joueur, ou null. */
    public static String meta(ServerPlayer p, boolean prefix) {
        if (!PRESENT || p == null) return null;
        try {
            return LuckPermsHook.meta(p, prefix);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /** Accorde des permissions + prefixe (+ groupe optionnel). Fonctionne joueur hors-ligne. */
    public static void grant(UUID uuid, List<String> perms, String prefix, int priority, String group) {
        if (!PRESENT || uuid == null) return;
        try {
            LuckPermsHook.grant(uuid, perms, prefix, priority, group);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    public static void revoke(UUID uuid, List<String> perms, String prefix, int priority, String group) {
        if (!PRESENT || uuid == null) return;
        try {
            LuckPermsHook.revoke(uuid, perms, prefix, priority, group);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    private static void warnOnce(Throwable t) {
        if (!warned) {
            warned = true;
            CobblePulse.LOG.warn("[CobblePulse] API LuckPerms indisponible : {}", t.toString());
        }
    }
}
