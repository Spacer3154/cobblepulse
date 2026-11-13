package fr.cobblepulse.clan;

import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.MiscState;
import fr.cobblepulse.data.WarData;
import fr.cobblepulse.storage.StateManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Guerres d'Influence : 7 jours, points de guerre en PvP, transfert d'influence et recompenses. */
public final class WarManager {
    private WarManager() {}

    public static void init() {
        Scheduler.repeat(20 * 10, 20 * 60, WarManager::tick);
    }

    private static MiscState st() {
        return StateManager.get();
    }

    public static WarData activeWar(ClanData c) {
        for (WarData w : st().wars) {
            if (!w.finished && (w.attackerId.equals(c.id) || w.defenderId.equals(c.id))) return w;
        }
        return null;
    }

    public static List<WarData> history(ClanData c, int max) {
        List<WarData> out = new ArrayList<>();
        for (int i = st().wars.size() - 1; i >= 0 && out.size() < max; i--) {
            WarData w = st().wars.get(i);
            if (w.finished && (w.attackerId.equals(c.id) || w.defenderId.equals(c.id))) out.add(w);
        }
        return out;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    public static void declare(ServerPlayer leader, ClanData target) {
        ClanData c = ClanManager.clanOf(leader.getUUID());
        if (c == null || ClanManager.role(c, leader.getUUID()) != ClanManager.Role.LEADER) {
            Msg.send(leader, "clan.err_not_leader");
            return;
        }
        if (target == null || target == c) {
            Msg.send(leader, "war.err_invalid_target");
            return;
        }
        if (activeWar(c) != null) {
            Msg.send(leader, "war.err_already_at_war");
            return;
        }
        if (activeWar(target) != null) {
            Msg.send(leader, "war.err_target_at_war", "clan", target.name);
            return;
        }
        int minMembers = Cfg.wars.i("min_members_to_declare", 1);
        if (c.members.size() < minMembers || target.members.size() < minMembers) {
            Msg.send(leader, "war.err_min_members", "count", minMembers);
            return;
        }
        long cdUntil = st().warCooldowns.getOrDefault(pairKey(c.id, target.id), 0L);
        if (cdUntil > TimeFmt.now()) {
            Msg.send(leader, "war.err_cooldown", "time", TimeFmt.duration(cdUntil - TimeFmt.now()));
            return;
        }
        long cost = Cfg.wars.l("declare_cost_influence", 0);
        if (cost > 0) {
            if (c.influence < cost) {
                Msg.send(leader, "war.err_cost", "cost", cost);
                return;
            }
            c.influence -= cost;
            ClanManager.save(c);
        }
        WarData w = new WarData();
        w.id = UUID.randomUUID().toString();
        w.attackerId = c.id;
        w.defenderId = target.id;
        w.attackerName = c.name;
        w.defenderName = target.name;
        w.startedAt = TimeFmt.now();
        w.endsAt = w.startedAt + Math.max(1, Cfg.wars.l("duration_days", 7)) * TimeFmt.DAY;
        st().wars.add(w);
        StateManager.save();
        Msg.broadcast("war.declared", "attacker", c.name, "defender", target.name, "days", Cfg.wars.l("duration_days", 7));
    }

    /** Combat PvP entre deux clans differents : points de guerre s'ils sont en guerre l'un contre l'autre. */
    static void onPvp(ClanData winner, ClanData loser, UUID w, UUID l) {
        WarData war = activeWar(winner);
        if (war == null) return;
        boolean match = (war.attackerId.equals(winner.id) && war.defenderId.equals(loser.id))
                || (war.attackerId.equals(loser.id) && war.defenderId.equals(winner.id));
        if (!match) return;
        long day = TimeFmt.now() / TimeFmt.DAY;
        String pair = (w.compareTo(l) < 0 ? w + "|" + l : l + "|" + w) + "|" + day;
        int max = Cfg.wars.i("max_points_per_pair_per_day", 3);
        int used = war.pairCounts.getOrDefault(pair, 0);
        if (max > 0 && used >= max) {
            Msg.send(w, "war.pair_limit");
            return;
        }
        war.pairCounts.put(pair, used + 1);
        int pts = Cfg.wars.i("points_per_win", 1);
        if (war.attackerId.equals(winner.id)) war.attackerPoints += pts;
        else war.defenderPoints += pts;
        StateManager.save();
        ClanManager.broadcastClan(winner, "war.point_won", "points", pts, "score", score(war, winner));
        ClanManager.broadcastClan(loser, "war.point_lost", "score", score(war, loser));
    }

    public static String score(WarData w, ClanData pov) {
        boolean att = w.attackerId.equals(pov.id);
        return (att ? w.attackerPoints : w.defenderPoints) + " - " + (att ? w.defenderPoints : w.attackerPoints);
    }

    private static void tick() {
        long now = TimeFmt.now();
        boolean changed = false;
        for (WarData w : st().wars) {
            if (!w.finished && now >= w.endsAt) {
                resolve(w);
                changed = true;
            }
        }
        // Historique limite
        while (st().wars.size() > 200) st().wars.remove(0);
        if (changed) StateManager.save();
    }

    /** Fin de guerre : le vainqueur s'empare d'un pourcentage de l'influence du vaincu et recoit ses recompenses. */
    public static void resolve(WarData w) {
        w.finished = true;
        ClanData a = ClanManager.get(w.attackerId);
        ClanData d = ClanManager.get(w.defenderId);
        st().warCooldowns.put(pairKey(w.attackerId, w.defenderId),
                TimeFmt.now() + Cfg.wars.l("declare_cooldown_hours", 24) * TimeFmt.HOUR);
        if (w.attackerPoints == w.defenderPoints || a == null || d == null) {
            if (a != null) {
                a.warDraws++;
                ClanManager.save(a);
            }
            if (d != null) {
                d.warDraws++;
                ClanManager.save(d);
            }
            Msg.broadcast("war.ended_draw", "attacker", w.attackerName, "defender", w.defenderName,
                    "score", w.attackerPoints + " - " + w.defenderPoints);
            return;
        }
        ClanData win = w.attackerPoints > w.defenderPoints ? a : d;
        ClanData lose = win == a ? d : a;
        double pct = Math.max(0, Math.min(100, Cfg.wars.d("influence_steal_percent", 10.0)));
        long stolen = Math.round(lose.influence * pct / 100.0);
        lose.influence -= stolen;
        win.influence += stolen;
        win.warWins++;
        lose.warLosses++;
        w.winnerId = win.id;
        w.stolenInfluence = stolen;
        ClanManager.save(win);
        ClanManager.save(lose);
        Msg.broadcast("war.ended", "winner", win.name, "loser", lose.name, "score",
                Math.max(w.attackerPoints, w.defenderPoints) + " - " + Math.min(w.attackerPoints, w.defenderPoints), "influence", stolen);
        giveRewards(win, "rewards.winner_member_commands", "rewards.winner_leader_commands");
        giveRewards(lose, "rewards.loser_member_commands", null);
    }

    private static void giveRewards(ClanData c, String memberKey, String leaderKey) {
        MinecraftServer server = CobblePulse.server();
        if (server == null) return;
        boolean onlineOnly = Cfg.wars.b("rewards.online_only", false);
        List<String> memberCmds = Cfg.wars.list(memberKey);
        for (var e : c.members.entrySet()) {
            UUID u = UUID.fromString(e.getKey());
            if (onlineOnly && !Players.online(u)) continue;
            for (String cmd : memberCmds) run(server, cmd, e.getValue().name, c.name);
        }
        if (leaderKey != null) {
            ClanData.ClanMember leader = c.members.get(c.leader);
            if (leader != null) for (String cmd : Cfg.wars.list(leaderKey)) run(server, cmd, leader.name, c.name);
        }
    }

    static void run(MinecraftServer server, String cmd, String player, String clan) {
        String c = cmd.replace("%player%", player).replace("%clan%", clan);
        if (c.startsWith("/")) c = c.substring(1);
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
        } catch (Throwable t) {
            CobblePulse.LOG.error("[CobblePulse] Commande de recompense en echec : {}", c, t);
        }
    }

    static void onClanDisband(ClanData c) {
        for (WarData w : st().wars) {
            if (!w.finished && (w.attackerId.equals(c.id) || w.defenderId.equals(c.id))) {
                w.finished = true;
                String other = w.attackerId.equals(c.id) ? w.defenderName : w.attackerName;
                Msg.broadcast("war.cancelled_disband", "clan", c.name, "other", other);
            }
        }
        StateManager.save();
    }
}
