package fr.cobblepulse.clan;

import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.battle.CobblemonBridge;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.ItemSerial;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.MiscState;
import fr.cobblepulse.league.LeagueManager;
import fr.cobblepulse.league.PostKind;
import fr.cobblepulse.storage.DataService;
import fr.cobblepulse.storage.StateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Clans (sans claims) : adhesion, inventaire partage, statistiques PvP, influence et tag de chat. */
public final class ClanManager {
    public enum Role { LEADER, OFFICER, MEMBER }

    public enum Mode { OPEN, INVITE_ONLY, CLOSED }

    public static final int INVENTORY_SIZE = 54;
    private static final Pattern COLOR = Pattern.compile("^(&[0-9a-fA-F]|&#[0-9a-fA-F]{6})(&[lnom])?$");

    private static final Map<String, ClanData> CLANS = new HashMap<>();
    private static final Map<UUID, String> INDEX = new HashMap<>();
    private static final Map<String, SimpleContainer> INVENTORIES = new HashMap<>();

    private ClanManager() {}

    // ------------------------------------------------------------------ chargement

    public static void load() {
        CLANS.clear();
        INDEX.clear();
        INVENTORIES.clear();
        List<ClanData> list = DataService.blocking(s -> s.loadClans());
        if (list != null) {
            for (ClanData c : list) {
                if (c.members == null) c.members = new java.util.LinkedHashMap<>();
                if (c.invites == null) c.invites = new HashMap<>();
                if (c.requests == null) c.requests = new java.util.LinkedHashMap<>();
                CLANS.put(c.id, c);
                for (String m : c.members.keySet()) INDEX.put(UUID.fromString(m), c.id);
            }
        }
        DataService.setClanPreSave(c -> {
            SimpleContainer inv = INVENTORIES.get(c.id);
            if (inv != null) c.inventory = ItemSerial.encode(inv);
        });
        CobblemonBridge.onPvpResult(ClanManager::onPvp);
        Scheduler.repeat(20 * 60, 20 * 60 * 5, ClanManager::influenceTick);
        CobblePulse.LOG.info("[CobblePulse] {} clans charges", CLANS.size());
    }

    public static void save(ClanData c) {
        DataService.markClan(c);
    }

    public static Collection<ClanData> all() {
        return CLANS.values();
    }

    public static ClanData get(String id) {
        return id == null ? null : CLANS.get(id);
    }

    public static ClanData clanOf(UUID player) {
        String id = INDEX.get(player);
        return id == null ? null : CLANS.get(id);
    }

    public static ClanData byName(String name) {
        for (ClanData c : CLANS.values()) if (c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    public static Role role(ClanData c, UUID player) {
        ClanData.ClanMember m = c.members.get(player.toString());
        if (m == null) return null;
        try {
            return Role.valueOf(m.role);
        } catch (Exception e) {
            return Role.MEMBER;
        }
    }

    public static boolean isOfficer(ClanData c, UUID player) {
        Role r = role(c, player);
        return r == Role.LEADER || r == Role.OFFICER;
    }

    public static Mode mode(ClanData c) {
        try {
            return Mode.valueOf(c.mode);
        } catch (Exception e) {
            return Mode.INVITE_ONLY;
        }
    }

    public static String modeLabel(Mode m) {
        return switch (m) {
            case OPEN -> "&aOuvert";
            case INVITE_ONLY -> "&eSur invitation";
            case CLOSED -> "&cFermé";
        };
    }

    private static int maxMembers() {
        return Math.max(1, Cfg.clans.i("max_members", 30));
    }

    // ------------------------------------------------------------------ cycle de vie

    public static ClanData create(ServerPlayer p, String name, String tag) {
        if (!Perms.player(p, Perms.CLAN_CREATE)) {
            Msg.send(p, "general.no_permission");
            return null;
        }
        if (clanOf(p.getUUID()) != null) {
            Msg.send(p, "clan.err_already_in_clan");
            return null;
        }
        int nMin = Cfg.clans.i("name_min_length", 3), nMax = Cfg.clans.i("name_max_length", 16);
        int tMin = Cfg.clans.i("tag_min_length", 2), tMax = Cfg.clans.i("tag_max_length", 5);
        if (!name.matches("[A-Za-z0-9_]{" + nMin + "," + nMax + "}")) {
            Msg.send(p, "clan.err_invalid_name", "min", nMin, "max", nMax);
            return null;
        }
        if (!tag.matches("[A-Za-z0-9]{" + tMin + "," + tMax + "}")) {
            Msg.send(p, "clan.err_invalid_tag", "min", tMin, "max", tMax);
            return null;
        }
        for (ClanData c : CLANS.values()) {
            if (c.name.equalsIgnoreCase(name)) {
                Msg.send(p, "clan.err_name_taken");
                return null;
            }
            if (c.tag.equalsIgnoreCase(tag)) {
                Msg.send(p, "clan.err_tag_taken");
                return null;
            }
        }
        ClanData c = new ClanData();
        c.id = UUID.randomUUID().toString();
        c.name = name;
        c.tag = tag.toUpperCase(Locale.ROOT);
        c.tagColor = Cfg.clans.str("default_tag_color", "&7");
        c.leader = p.getUUID().toString();
        try {
            c.mode = Mode.valueOf(Cfg.clans.str("default_mode", "INVITE_ONLY").toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            c.mode = Mode.INVITE_ONLY.name();
        }
        c.createdAt = TimeFmt.now();
        c.members.put(p.getUUID().toString(), new ClanData.ClanMember(p.getGameProfile().getName(), Role.LEADER.name(), TimeFmt.now()));
        CLANS.put(c.id, c);
        INDEX.put(p.getUUID(), c.id);
        save(c);
        Msg.send(p, "clan.created", "clan", c.name, "tag", c.tag);
        if (Cfg.clans.b("announce_creation", true)) Msg.broadcast("clan.created_broadcast", "player", p.getGameProfile().getName(), "clan", c.name);
        return c;
    }

    private static void addMember(ClanData c, UUID uuid, String name) {
        c.members.put(uuid.toString(), new ClanData.ClanMember(name, Role.MEMBER.name(), TimeFmt.now()));
        c.invites.remove(uuid.toString());
        c.requests.remove(uuid.toString());
        INDEX.put(uuid, c.id);
        for (ClanData other : CLANS.values()) {
            other.requests.remove(uuid.toString());
        }
        save(c);
        broadcastClan(c, "clan.member_joined", "player", name);
    }

    public static void join(ServerPlayer p, ClanData c) {
        if (!Perms.player(p, Perms.CLAN_USE)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        if (clanOf(p.getUUID()) != null) {
            Msg.send(p, "clan.err_already_in_clan");
            return;
        }
        if (c.members.size() >= maxMembers()) {
            Msg.send(p, "clan.err_full");
            return;
        }
        Mode m = mode(c);
        Long invite = c.invites.get(p.getUUID().toString());
        boolean invited = invite != null && invite > TimeFmt.now();
        if (m == Mode.CLOSED) {
            Msg.send(p, "clan.err_closed", "clan", c.name);
            return;
        }
        if (m == Mode.OPEN || invited) {
            addMember(c, p.getUUID(), p.getGameProfile().getName());
            Msg.send(p, "clan.joined", "clan", c.name);
            return;
        }
        request(p, c);
    }

    public static void request(ServerPlayer p, ClanData c) {
        if (mode(c) == Mode.CLOSED) {
            Msg.send(p, "clan.err_closed", "clan", c.name);
            return;
        }
        if (c.requests.containsKey(p.getUUID().toString())) {
            Msg.send(p, "clan.err_request_exists");
            return;
        }
        c.requests.put(p.getUUID().toString(), new ClanData.JoinRequest(p.getGameProfile().getName(), TimeFmt.now()));
        save(c);
        Msg.send(p, "clan.request_sent", "clan", c.name);
        for (String m : c.members.keySet()) {
            UUID u = UUID.fromString(m);
            if (isOfficer(c, u)) Msg.send(u, "clan.request_received", "player", p.getGameProfile().getName());
        }
    }

    public static void acceptRequest(ServerPlayer officer, UUID target) {
        ClanData c = clanOf(officer.getUUID());
        if (c == null || !isOfficer(c, officer.getUUID())) {
            Msg.send(officer, "clan.err_not_officer");
            return;
        }
        ClanData.JoinRequest r = c.requests.remove(target.toString());
        if (r == null) {
            Msg.send(officer, "clan.err_no_request");
            return;
        }
        if (clanOf(target) != null) {
            Msg.send(officer, "clan.err_target_in_clan", "player", r.name);
            save(c);
            return;
        }
        if (c.members.size() >= maxMembers()) {
            Msg.send(officer, "clan.err_full");
            return;
        }
        addMember(c, target, r.name);
        Msg.send(target, "clan.request_accepted", "clan", c.name);
    }

    public static void denyRequest(ServerPlayer officer, UUID target) {
        ClanData c = clanOf(officer.getUUID());
        if (c == null || !isOfficer(c, officer.getUUID())) {
            Msg.send(officer, "clan.err_not_officer");
            return;
        }
        ClanData.JoinRequest r = c.requests.remove(target.toString());
        save(c);
        if (r != null) {
            Msg.send(officer, "clan.request_denied_officer", "player", r.name);
            Msg.send(target, "clan.request_denied", "clan", c.name);
        }
    }

    public static void invite(ServerPlayer inviter, ServerPlayer target) {
        ClanData c = clanOf(inviter.getUUID());
        if (c == null || !isOfficer(c, inviter.getUUID())) {
            Msg.send(inviter, "clan.err_not_officer");
            return;
        }
        if (mode(c) == Mode.CLOSED) {
            Msg.send(inviter, "clan.err_invite_closed");
            return;
        }
        if (clanOf(target.getUUID()) != null) {
            Msg.send(inviter, "clan.err_target_in_clan", "player", target.getGameProfile().getName());
            return;
        }
        long expiry = TimeFmt.now() + Cfg.clans.l("invite_expiry_minutes", 10) * TimeFmt.MINUTE;
        c.invites.put(target.getUUID().toString(), expiry);
        save(c);
        Msg.send(inviter, "clan.invite_sent", "player", target.getGameProfile().getName());
        MutableComponent msg = Msg.get("clan.invite_received", "clan", c.name, "tag", c.tag, "player", inviter.getGameProfile().getName());
        msg.append(Component.literal(" "));
        msg.append(TextUtil.button(Msg.raw("clan.button_accept"), "/clan accept " + c.name, null));
        msg.append(Component.literal(" "));
        msg.append(TextUtil.button(Msg.raw("clan.button_deny"), "/clan deny " + c.name, null));
        target.sendSystemMessage(msg);
    }

    public static void acceptInvite(ServerPlayer p, ClanData c) {
        Long invite = c.invites.get(p.getUUID().toString());
        if (invite == null || invite < TimeFmt.now()) {
            Msg.send(p, "clan.err_no_invite", "clan", c.name);
            return;
        }
        if (clanOf(p.getUUID()) != null) {
            Msg.send(p, "clan.err_already_in_clan");
            return;
        }
        if (c.members.size() >= maxMembers()) {
            Msg.send(p, "clan.err_full");
            return;
        }
        addMember(c, p.getUUID(), p.getGameProfile().getName());
        Msg.send(p, "clan.joined", "clan", c.name);
    }

    public static void denyInvite(ServerPlayer p, ClanData c) {
        if (c.invites.remove(p.getUUID().toString()) != null) {
            save(c);
            Msg.send(p, "clan.invite_denied", "clan", c.name);
        }
    }

    public static void leave(ServerPlayer p) {
        ClanData c = clanOf(p.getUUID());
        if (c == null) {
            Msg.send(p, "clan.err_no_clan");
            return;
        }
        if (role(c, p.getUUID()) == Role.LEADER) {
            if (c.members.size() > 1) {
                Msg.send(p, "clan.err_leader_leave");
                return;
            }
            disband(p);
            return;
        }
        removeMember(c, p.getUUID());
        Msg.send(p, "clan.left", "clan", c.name);
        broadcastClan(c, "clan.member_left", "player", p.getGameProfile().getName());
    }

    private static void removeMember(ClanData c, UUID u) {
        c.members.remove(u.toString());
        INDEX.remove(u);
        save(c);
    }

    public static void kick(ServerPlayer actor, UUID target) {
        ClanData c = clanOf(actor.getUUID());
        if (c == null || !isOfficer(c, actor.getUUID())) {
            Msg.send(actor, "clan.err_not_officer");
            return;
        }
        Role tr = role(c, target);
        if (tr == null) {
            Msg.send(actor, "clan.err_not_member");
            return;
        }
        Role ar = role(c, actor.getUUID());
        if (tr == Role.LEADER || (ar == Role.OFFICER && tr == Role.OFFICER) || actor.getUUID().equals(target)) {
            Msg.send(actor, "clan.err_cannot_kick");
            return;
        }
        String name = c.members.get(target.toString()).name;
        removeMember(c, target);
        Msg.send(target, "clan.kicked", "clan", c.name);
        broadcastClan(c, "clan.member_kicked", "player", name, "by", actor.getGameProfile().getName());
    }

    public static void setRole(ServerPlayer leader, UUID target, Role role) {
        ClanData c = clanOf(leader.getUUID());
        if (c == null || role(c, leader.getUUID()) != Role.LEADER) {
            Msg.send(leader, "clan.err_not_leader");
            return;
        }
        ClanData.ClanMember m = c.members.get(target.toString());
        if (m == null || target.equals(leader.getUUID())) {
            Msg.send(leader, "clan.err_not_member");
            return;
        }
        m.role = role.name();
        save(c);
        broadcastClan(c, role == Role.OFFICER ? "clan.promoted" : "clan.demoted", "player", m.name);
    }

    public static void transfer(ServerPlayer leader, UUID target) {
        ClanData c = clanOf(leader.getUUID());
        if (c == null || role(c, leader.getUUID()) != Role.LEADER) {
            Msg.send(leader, "clan.err_not_leader");
            return;
        }
        ClanData.ClanMember m = c.members.get(target.toString());
        if (m == null || target.equals(leader.getUUID())) {
            Msg.send(leader, "clan.err_not_member");
            return;
        }
        c.members.get(leader.getUUID().toString()).role = Role.OFFICER.name();
        m.role = Role.LEADER.name();
        c.leader = target.toString();
        save(c);
        broadcastClan(c, "clan.transferred", "player", m.name);
    }

    public static void disband(ServerPlayer leader) {
        ClanData c = clanOf(leader.getUUID());
        if (c == null || role(c, leader.getUUID()) != Role.LEADER) {
            Msg.send(leader, "clan.err_not_leader");
            return;
        }
        SimpleContainer inv = inventory(c);
        if (!inv.isEmpty()) {
            Msg.send(leader, "clan.err_inventory_not_empty");
            return;
        }
        broadcastClan(c, "clan.disbanded", "clan", c.name);
        WarManager.onClanDisband(c);
        for (String m : c.members.keySet()) INDEX.remove(UUID.fromString(m));
        CLANS.remove(c.id);
        INVENTORIES.remove(c.id);
        DataService.deleteClan(c.id);
        if (Cfg.clans.b("announce_creation", true)) Msg.broadcast("clan.disbanded_broadcast", "clan", c.name);
    }

    public static void setMode(ServerPlayer leader, Mode m) {
        ClanData c = clanOf(leader.getUUID());
        if (c == null || role(c, leader.getUUID()) != Role.LEADER) {
            Msg.send(leader, "clan.err_not_leader");
            return;
        }
        c.mode = m.name();
        if (m == Mode.CLOSED) {
            c.requests.clear();
            c.invites.clear();
        }
        save(c);
        broadcastClan(c, "clan.mode_changed", "mode", modeLabel(m));
    }

    public static boolean setTagColor(ServerPlayer leader, String color) {
        ClanData c = clanOf(leader.getUUID());
        if (c == null || !isOfficer(c, leader.getUUID())) {
            Msg.send(leader, "clan.err_not_officer");
            return false;
        }
        if (!Perms.vip(leader, Perms.CLAN_CHAT_COLOR)) {
            Msg.send(leader, "clan.err_color_vip");
            return false;
        }
        if (!COLOR.matcher(color).matches()) {
            Msg.send(leader, "clan.err_color_invalid");
            return false;
        }
        c.tagColor = color;
        save(c);
        Msg.send(leader, "clan.color_set", "tag", c.tag, "color", color);
        return true;
    }

    public static void broadcastClan(ClanData c, String key, Object... kv) {
        MutableComponent msg = Msg.get(key, kv);
        for (String m : c.members.keySet()) {
            ServerPlayer p = Players.get(UUID.fromString(m));
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    public static List<ServerPlayer> onlineMembers(ClanData c) {
        List<ServerPlayer> out = new ArrayList<>();
        for (String m : c.members.keySet()) {
            ServerPlayer p = Players.get(UUID.fromString(m));
            if (p != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ inventaire partage

    public static SimpleContainer inventory(ClanData c) {
        SimpleContainer inv = INVENTORIES.get(c.id);
        if (inv == null) {
            inv = new SimpleContainer(INVENTORY_SIZE);
            ItemSerial.decodeInto(c.inventory, inv);
            inv.addListener(container -> DataService.markClan(c));
            INVENTORIES.put(c.id, inv);
        }
        return inv;
    }

    /** Nombre d'emplacements visibles : 27 de base, 54 pour les VIP. */
    public static int inventorySlots(ServerPlayer p) {
        int base = Cfg.clans.i("inventory_base_slots", 27);
        int vip = Cfg.clans.i("inventory_vip_slots", 54);
        return Perms.vip(p, Perms.CLAN_INV_EXPAND) ? vip : base;
    }

    // ------------------------------------------------------------------ chat (ShadowChat / PlaceholderAPI)

    /** Tag de clan formate pour le chat (couleur personnalisee reservee aux VIP). */
    public static MutableComponent formattedTag(ServerPlayer p) {
        ClanData c = clanOf(p.getUUID());
        if (c == null) return Component.empty();
        return TextUtil.legacy(tagString(c, Perms.vip(p, Perms.CLAN_CHAT_COLOR)));
    }

    public static String tagString(ClanData c, boolean colored) {
        String color = colored && c.tagColor != null ? c.tagColor : Cfg.clans.str("default_tag_color", "&7");
        return Cfg.clans.str("tag_format", "&8[%color%%tag%&8] ").replace("%color%", color).replace("%tag%", c.tag).replace("%clan%", c.name);
    }

    // ------------------------------------------------------------------ PvP & influence

    private static void onPvp(CobblemonBridge.PvpResult r) {
        if (r.winners().size() != 1 || r.losers().size() != 1) return;
        if (r.turns() < Cfg.clans.i("pvp_stats_min_turns", 1)) return;
        UUID w = r.winners().get(0);
        UUID l = r.losers().get(0);
        ClanData cw = clanOf(w);
        ClanData cl = clanOf(l);
        if (cw == null || cl == null || cw == cl) return;
        cw.pvpWins++;
        cl.pvpLosses++;
        save(cw);
        save(cl);
        WarManager.onPvp(cw, cl, w, l);
    }

    private static void influenceTick() {
        MiscState st = StateManager.get();
        long now = TimeFmt.now();
        if (st.lastInfluencePayout == 0) {
            st.lastInfluencePayout = now;
            StateManager.save();
            return;
        }
        int guard = 0;
        while (now - st.lastInfluencePayout >= TimeFmt.DAY && guard++ < 7) {
            st.lastInfluencePayout += TimeFmt.DAY;
            payInfluence();
        }
        StateManager.save();
    }

    /** Versement quotidien : chaque titulaire de la Ligue rapporte de l'influence a son clan. */
    public static void payInfluence() {
        Map<String, Long> gains = new HashMap<>();
        for (Map.Entry<UUID, PostKind> e : LeagueManager.holders().entrySet()) {
            ClanData c = clanOf(e.getKey());
            if (c == null) continue;
            long amount = switch (e.getValue()) {
                case CHAMPION -> Cfg.clans.l("influence.champion_per_day", 10);
                case ELITE -> Cfg.clans.l("influence.elite_per_day", 25);
                case MASTER -> Cfg.clans.l("influence.master_per_day", 50);
            };
            gains.merge(c.id, amount, Long::sum);
        }
        for (Map.Entry<String, Long> g : gains.entrySet()) {
            ClanData c = CLANS.get(g.getKey());
            if (c == null) continue;
            c.influence += g.getValue();
            save(c);
            broadcastClan(c, "clan.influence_gained", "amount", g.getValue(), "total", c.influence);
        }
    }

    public static List<ClanData> leaderboard() {
        List<ClanData> list = new ArrayList<>(CLANS.values());
        list.sort(Comparator.comparingLong((ClanData c) -> c.influence).reversed()
                .thenComparing(Comparator.comparingInt((ClanData c) -> c.warWins).reversed())
                .thenComparing(c -> c.name.toLowerCase(Locale.ROOT)));
        return list;
    }

    public static int rank(ClanData c) {
        return leaderboard().indexOf(c) + 1;
    }

    /** Remise a zero de saison de l'influence et des statistiques de guerre. */
    public static void resetInfluence() {
        for (ClanData c : CLANS.values()) {
            c.influence = 0;
            c.warWins = 0;
            c.warLosses = 0;
            c.warDraws = 0;
            c.pvpWins = 0;
            c.pvpLosses = 0;
            save(c);
        }
    }
}
