package fr.cobblepulse.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** Appels directs a l'API LuckPerms. Ne jamais referencer cette classe si LuckPerms est absent. */
final class LuckPermsHook {
    private LuckPermsHook() {}

    static Boolean check(ServerPlayer p, String node) {
        LuckPerms lp = LuckPermsProvider.get();
        User user = lp.getUserManager().getUser(p.getUUID());
        if (user == null) return null;
        Tristate t = user.getCachedData().getPermissionData().checkPermission(node);
        return t == Tristate.UNDEFINED ? null : t.asBoolean();
    }

    /** Cree (si besoin) et met a jour un groupe : nom affiche, prefixe, poids, permissions. */
    static void syncGroup(String name, String display, String prefix, int priority, List<String> perms, String parent) {
        LuckPerms lp = LuckPermsProvider.get();
        lp.getGroupManager().createAndLoadGroup(name).thenAccept(g -> {
            g.data().clear(n -> NodeType.PREFIX.matches(n) || NodeType.DISPLAY_NAME.matches(n) || NodeType.WEIGHT.matches(n));
            if (display != null && !display.isBlank()) g.data().add(net.luckperms.api.node.types.DisplayNameNode.builder(display).build());
            if (prefix != null && !prefix.isEmpty()) g.data().add(PrefixNode.builder(prefix, priority).build());
            g.data().add(net.luckperms.api.node.types.WeightNode.builder(priority).build());
            for (String perm : perms) g.data().add(Node.builder(perm).build());
            if (parent != null && !parent.isBlank()) g.data().add(InheritanceNode.builder(parent.trim()).build());
            lp.getGroupManager().saveGroup(g);
        });
    }

    /** Auto-test : groupes herites par le joueur + prefixe du groupe. */
    static java.util.concurrent.CompletableFuture<String> describe(UUID uuid, String group) {
        LuckPerms lp = LuckPermsProvider.get();
        return lp.getUserManager().loadUser(uuid).thenCombine(lp.getGroupManager().loadGroup(group), (u, g) -> {
            String groups = String.join(",", u.getInheritedGroups(net.luckperms.api.query.QueryOptions.nonContextual()).stream().map(x -> x.getName()).toList());
            String prefix = g.map(x -> x.getCachedData().getMetaData().getPrefix()).orElse("<aucun groupe>");
            return groups + " | " + prefix;
        });
    }

    static String meta(ServerPlayer p, boolean prefix) {
        User user = LuckPermsProvider.get().getUserManager().getUser(p.getUUID());
        if (user == null) return null;
        var meta = user.getCachedData().getMetaData();
        return prefix ? meta.getPrefix() : meta.getSuffix();
    }

    static void grant(UUID uuid, List<String> perms, String prefix, int priority, String group) {
        update(uuid, List.of(), null, 0, null, perms, prefix, priority, group);
    }

    static void revoke(UUID uuid, List<String> perms, String prefix, int priority, String group) {
        update(uuid, perms, prefix, priority, group, List.of(), null, 0, null);
    }

    /** Retraits puis ajouts dans une seule modification (pas de course entre deux sauvegardes). Groupes separes par des virgules. */
    static void update(UUID uuid, List<String> rmPerms, String rmPrefix, int rmPriority, String rmGroups,
                       List<String> addPerms, String addPrefix, int addPriority, String addGroups) {
        LuckPerms lp = LuckPermsProvider.get();
        lp.getUserManager().modifyUser(uuid, user -> {
            for (String perm : rmPerms) user.data().remove(Node.builder(perm).build());
            if (rmPrefix != null && !rmPrefix.isEmpty()) {
                user.data().clear(n -> NodeType.PREFIX.matches(n)
                        && NodeType.PREFIX.cast(n).getMetaValue().equals(rmPrefix)
                        && NodeType.PREFIX.cast(n).getPriority() == rmPriority);
            }
            for (String g : split(rmGroups)) user.data().remove(InheritanceNode.builder(g).build());
            for (String perm : addPerms) user.data().add(Node.builder(perm).build());
            if (addPrefix != null && !addPrefix.isEmpty()) user.data().add(PrefixNode.builder(addPrefix, addPriority).build());
            for (String g : split(addGroups)) user.data().add(InheritanceNode.builder(g).build());
        });
    }

    private static List<String> split(String groups) {
        List<String> out = new java.util.ArrayList<>();
        if (groups == null) return out;
        for (String g : groups.split(",")) if (!g.isBlank()) out.add(g.trim().toLowerCase(java.util.Locale.ROOT));
        return out;
    }
}
