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

    static void grant(UUID uuid, List<String> perms, String prefix, int priority, String group) {
        LuckPerms lp = LuckPermsProvider.get();
        lp.getUserManager().modifyUser(uuid, user -> {
            for (String perm : perms) user.data().add(Node.builder(perm).build());
            if (prefix != null && !prefix.isEmpty()) {
                user.data().add(PrefixNode.builder(prefix, priority).build());
            }
            if (group != null && !group.isBlank()) {
                user.data().add(InheritanceNode.builder(group.trim()).build());
            }
        });
    }

    static void revoke(UUID uuid, List<String> perms, String prefix, int priority, String group) {
        LuckPerms lp = LuckPermsProvider.get();
        lp.getUserManager().modifyUser(uuid, user -> {
            for (String perm : perms) user.data().remove(Node.builder(perm).build());
            if (prefix != null && !prefix.isEmpty()) {
                user.data().clear(n -> NodeType.PREFIX.matches(n)
                        && NodeType.PREFIX.cast(n).getMetaValue().equals(prefix)
                        && NodeType.PREFIX.cast(n).getPriority() == priority);
            }
            if (group != null && !group.isBlank()) {
                user.data().remove(InheritanceNode.builder(group.trim()).build());
            }
        });
    }
}
