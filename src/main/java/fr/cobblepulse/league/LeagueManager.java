package fr.cobblepulse.league;

import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.battle.CobblemonBridge;
import fr.cobblepulse.battle.MatchService;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.Loc;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.LeagueData;
import fr.cobblepulse.data.LeagueData.PostData;
import fr.cobblepulse.data.PlayerData;
import fr.cobblepulse.integration.LuckPermsBridge;
import fr.cobblepulse.storage.DataService;
import fr.cobblepulse.storage.PlayerDataManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ligue PvP : 13 postes, arenes, file d'attente, defis de badge/place, cooldowns et securite. */
public final class LeagueManager {
    public enum ChallengeType { BADGE, PLACE }

    public record Queued(UUID uuid, String name, ChallengeType type, long time) {}

    static final class Runtime {
        final Deque<Queued> queue = new ArrayDeque<>();
        Queued pending;
        long pendingUntil;
        MatchService.Match match;
        Queued current;
        boolean nextIsAuto;
    }

    private static LeagueData data = new LeagueData();
    private static final Map<String, PostData> POSTS = new LinkedHashMap<>();
    private static final Map<String, Runtime> RT = new HashMap<>();

    private LeagueManager() {}

    // ------------------------------------------------------------------ chargement

    public static void load() {
        LeagueData loaded = DataService.blocking(s -> s.loadLeague());
        data = loaded != null ? loaded : new LeagueData();
        POSTS.clear();
        Map<String, PostData> byId = new HashMap<>();
        for (PostData p : data.posts) byId.put(p.id, p);
        List<PostData> ordered = new ArrayList<>();
        for (PostKind k : PostKind.values()) {
            for (int i = 1; i <= k.count; i++) {
                String id = k == PostKind.MASTER ? "master" : k.name().toLowerCase() + "_" + i;
                PostData p = byId.get(id);
                if (p == null) {
                    p = new PostData();
                    p.id = id;
                    p.kind = k.name();
                }
                if (p.types == null) p.types = new ArrayList<>();
                ordered.add(p);
                POSTS.put(id, p);
            }
        }
        data.posts = ordered;
        save();
        Scheduler.repeat(20, 20, LeagueManager::tickSecond);
        Scheduler.repeat(20 * 30, 20L * 60 * Math.max(1, Cfg.league.i("inactivity.check_interval_minutes", 10)), LeagueManager::checkInactivity);
    }

    public static void save() {
        DataService.markLeague(data);
    }

    public static List<PostData> posts() {
        return data.posts;
    }

    public static List<PostData> posts(PostKind kind) {
        List<PostData> out = new ArrayList<>();
        for (PostData p : data.posts) if (kind(p) == kind) out.add(p);
        return out;
    }

    public static PostData post(String id) {
        return id == null ? null : POSTS.get(id.toLowerCase());
    }

    public static PostKind kind(PostData p) {
        return PostKind.of(p.kind);
    }

    public static PostData postOf(UUID holder) {
        if (holder == null) return null;
        String s = holder.toString();
        for (PostData p : data.posts) if (s.equals(p.holderUuid)) return p;
        return null;
    }

    public static UUID holder(PostData p) {
        try {
            return p.holderUuid == null ? null : UUID.fromString(p.holderUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Runtime rt(PostData p) {
        return RT.computeIfAbsent(p.id, k -> new Runtime());
    }

    public static int number(PostData p) {
        int idx = p.id.lastIndexOf('_');
        if (idx < 0) return 1;
        try {
            return Integer.parseInt(p.id.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public static List<PokeType> types(PostData p) {
        List<PokeType> out = new ArrayList<>();
        for (String t : p.types) {
            PokeType pt = PokeType.of(t);
            if (pt != null) out.add(pt);
        }
        return out;
    }

    public static String typesLabel(PostData p, boolean colored) {
        List<String> parts = new ArrayList<>();
        for (PokeType t : types(p)) parts.add(colored ? t.colored() : t.fr);
        if (parts.isEmpty()) return colored ? "&7?" : "?";
        return String.join(colored ? " &7/ " : " / ", parts);
    }

    /** Nom affiche du poste : "Champion n°1 (Feu)". */
    public static String displayName(PostData p) {
        return switch (kind(p)) {
            case CHAMPION -> "&6Champion n°" + number(p) + " &7(" + typesLabel(p, true) + "&7)";
            case ELITE -> "&5Conseil 4 n°" + number(p) + " &7(" + typesLabel(p, true) + "&7)";
            case MASTER -> "&c&lMaître de la Ligue";
        };
    }

    public static String plainName(PostData p) {
        return TextUtil.strip(displayName(p));
    }

    /** Grade : [Champion - Type], [Conseil 4 - Type 1 / Type 2], [Maitre]. */
    public static String grade(PostData p) {
        return switch (kind(p)) {
            case CHAMPION -> "[Champion - " + typesLabel(p, false) + "]";
            case ELITE -> "[Conseil 4 - " + typesLabel(p, false) + "]";
            case MASTER -> "[Maître]";
        };
    }

    public static String prefix(PostData p) {
        return switch (kind(p)) {
            case CHAMPION -> Cfg.league.str("ranks.champion_prefix", "&6[Champion - %type%] ").replace("%type%", typesLabel(p, false));
            case ELITE -> Cfg.league.str("ranks.elite_prefix", "&5[Conseil 4 - %types%] ").replace("%types%", typesLabel(p, false));
            case MASTER -> Cfg.league.str("ranks.master_prefix", "&c&l[Maître] ");
        };
    }

    private static String roleNode(PostData p) {
        return switch (kind(p)) {
            case CHAMPION -> Perms.ROLE_CHAMPION;
            case ELITE -> Perms.ROLE_ELITE;
            case MASTER -> Perms.ROLE_MASTER;
        };
    }

    private static String group(PostData p) {
        return Cfg.league.str("ranks." + kind(p).name().toLowerCase() + "_group", "");
    }

    private static List<String> rolePerms(PostData p) {
        return List.of(roleNode(p), Perms.ARENA_SETTP, Perms.ARENA_TOGGLE);
    }

    private static void grantRole(PostData p) {
        UUID u = holder(p);
        if (u == null) return;
        p.appliedPrefix = prefix(p);
        LuckPermsBridge.grant(u, rolePerms(p), p.appliedPrefix, Cfg.league.i("ranks.prefix_priority", 100), group(p));
    }

    private static void revokeRole(PostData p) {
        UUID u = holder(p);
        if (u == null) return;
        LuckPermsBridge.revoke(u, rolePerms(p), p.appliedPrefix != null ? p.appliedPrefix : prefix(p),
                Cfg.league.i("ranks.prefix_priority", 100), group(p));
        p.appliedPrefix = null;
    }

    // ------------------------------------------------------------------ administration

    /** Attribue un poste (editeur admin ou victoire d'un combat de place). */
    public static void assign(PostData p, UUID uuid, String name, boolean announce) {
        PostData other = postOf(uuid);
        if (other != null && other != p) {
            vacate(other, "league.left_for_other", false);
            Msg.broadcast("league.left_for_other", "player", name, "post", plainName(other), "new_post", plainName(p));
        }
        if (p.holderUuid != null) revokeRole(p);
        p.holderUuid = uuid.toString();
        p.holderName = name;
        p.assignedAt = TimeFmt.now();
        p.lastHolderChange = TimeFmt.now();
        p.consecutiveRefusals = 0;
        p.holderLastSeen = TimeFmt.now();
        grantRole(p);
        clearQueue(p, "league.queue_reset_holder_changed");
        save();
        if (announce) Msg.broadcast("league.assigned", "player", name, "post", plainName(p), "grade", grade(p));
    }

    /** Libere un poste (statut Vacant). */
    public static void vacate(PostData p, String reasonKey, boolean announce) {
        if (p.holderUuid == null) return;
        String old = p.holderName;
        revokeRole(p);
        p.holderUuid = null;
        p.holderName = null;
        p.open = false;
        p.consecutiveRefusals = 0;
        clearQueue(p, "league.queue_reset_vacant");
        Runtime r = rt(p);
        if (r.match != null) MatchService.abort(r.match, Msg.format("league.match_cancelled_vacant"));
        save();
        if (announce && reasonKey != null) Msg.broadcast(reasonKey, "player", old, "post", plainName(p));
    }

    public static void setTypes(PostData p, List<PokeType> types) {
        boolean held = p.holderUuid != null;
        if (held) revokeRole(p);
        p.types = new ArrayList<>();
        for (PokeType t : types) p.types.add(t.id);
        if (held) grantRole(p);
        save();
    }

    public static void setTp(PostData p, boolean holderPoint, Loc loc) {
        if (holderPoint) p.tpHolder = loc;
        else p.tpChallenger = loc;
        save();
    }

    public static void setOpen(PostData p, boolean open) {
        p.open = open;
        save();
        if (open) {
            Msg.broadcast("league.arena_opened", "player", p.holderName == null ? "-" : p.holderName, "post", plainName(p));
            processQueue(p);
        } else {
            Runtime r = rt(p);
            if (r.pending != null) {
                Msg.send(r.pending.uuid(), "league.request_cancelled_closed", "post", plainName(p));
                r.pending = null;
            }
        }
    }

    public static void resetPostCooldown(PostData p) {
        p.lastHolderChange = 0;
        save();
    }

    // ------------------------------------------------------------------ regles de defi

    public static boolean hasAllChampionBadges(PlayerData d) {
        for (int i = 1; i <= 8; i++) if (!d.badges.containsKey("champion_" + i)) return false;
        return true;
    }

    public static boolean hasAllEliteBadges(PlayerData d) {
        for (int i = 1; i <= 4; i++) if (!d.badges.containsKey("elite_" + i)) return false;
        return true;
    }

    public static long badgeCooldownMs() {
        return Cfg.league.l("challenges.badge_cooldown_hours", 24) * TimeFmt.HOUR;
    }

    public static long placeCooldownMs() {
        return Cfg.league.l("challenges.place_cooldown_days", 7) * TimeFmt.DAY;
    }

    public static long postCooldownMs() {
        return Cfg.league.l("challenges.post_change_cooldown_days", 7) * TimeFmt.DAY;
    }

    public static long postCooldownRemaining(PostData p) {
        if (p.lastHolderChange <= 0) return 0;
        return Math.max(0, p.lastHolderChange + postCooldownMs() - TimeFmt.now());
    }

    /** @return message d'erreur formate, ou null si le defi est autorise. */
    public static String checkEligibility(ServerPlayer challenger, PlayerData d, PostData p, ChallengeType type) {
        PostKind k = kind(p);
        UUID me = challenger.getUUID();
        if (p.holderUuid == null) return Msg.format("league.err_vacant");
        if (me.toString().equals(p.holderUuid)) return Msg.format("league.err_self");
        if (k == PostKind.MASTER && type == ChallengeType.BADGE) type = ChallengeType.PLACE;
        long now = TimeFmt.now();
        if (type == ChallengeType.BADGE) {
            if (!Perms.player(challenger, Perms.CHALLENGE_BADGE)) return Msg.format("general.no_permission");
            if (d.badges.containsKey(p.id)) return Msg.format("league.err_already_badge");
            if (k == PostKind.ELITE && !hasAllChampionBadges(d)) return Msg.format("league.err_need_8");
            long cd = d.badgeCooldowns.getOrDefault(p.id, 0L);
            if (cd > now) return Msg.format("league.err_badge_cooldown", "time", TimeFmt.duration(cd - now));
        } else {
            if (!Perms.player(challenger, Perms.CHALLENGE_PLACE)) return Msg.format("general.no_permission");
            if ((k == PostKind.ELITE || k == PostKind.MASTER) && !(hasAllChampionBadges(d) && hasAllEliteBadges(d))) {
                return Msg.format("league.err_need_12");
            }
            if (d.placeCooldownUntil > now) return Msg.format("league.err_place_cooldown", "time", TimeFmt.duration(d.placeCooldownUntil - now));
            long global = postCooldownRemaining(p);
            if (global > 0) return Msg.format("league.err_post_cooldown", "time", TimeFmt.duration(global));
        }
        return null;
    }

    public static Queued queuedEntry(UUID u) {
        for (Runtime r : RT.values()) {
            for (Queued q : r.queue) if (q.uuid().equals(u)) return q;
            if (r.pending != null && r.pending.uuid().equals(u)) return r.pending;
        }
        return null;
    }

    public static PostData queuedPost(UUID u) {
        for (PostData p : data.posts) {
            Runtime r = rt(p);
            for (Queued q : r.queue) if (q.uuid().equals(u)) return p;
            if (r.pending != null && r.pending.uuid().equals(u)) return p;
        }
        return null;
    }

    public static int queueSize(PostData p) {
        Runtime r = rt(p);
        return r.queue.size() + (r.pending != null ? 1 : 0);
    }

    public static List<Queued> queue(PostData p) {
        Runtime r = rt(p);
        List<Queued> out = new ArrayList<>();
        if (r.pending != null) out.add(r.pending);
        out.addAll(r.queue);
        return out;
    }

    public static Queued pending(PostData p) {
        return rt(p).pending;
    }

    public static boolean inLeagueMatch(PostData p) {
        return rt(p).match != null;
    }

    /** Defi lance par un joueur (GUI ou commande). */
    public static void challenge(ServerPlayer challenger, PostData p, ChallengeType type) {
        if (kind(p) == PostKind.MASTER) type = ChallengeType.PLACE;
        PlayerData d = PlayerDataManager.online(challenger);
        String err = checkEligibility(challenger, d, p, type);
        if (err != null) {
            challenger.sendSystemMessage(TextUtil.legacy(err));
            return;
        }
        if (!p.open) {
            Msg.send(challenger, "league.err_closed");
            return;
        }
        if (p.tpHolder == null || p.tpChallenger == null) {
            Msg.send(challenger, "league.err_no_tp");
            return;
        }
        ServerPlayer holder = Players.get(holder(p));
        if (holder == null) {
            Msg.send(challenger, "league.err_holder_offline");
            return;
        }
        if (queuedEntry(challenger.getUUID()) != null) {
            Msg.send(challenger, "league.err_already_queued");
            return;
        }
        if (MatchService.inMatch(challenger.getUUID())) {
            Msg.send(challenger, "league.err_in_match");
            return;
        }
        Runtime r = rt(p);
        int max = Math.max(1, Cfg.league.i("challenges.queue_max_size", 5));
        if (r.queue.size() >= max) {
            Msg.send(challenger, "league.err_queue_full", "max", max);
            return;
        }
        r.queue.addLast(new Queued(challenger.getUUID(), challenger.getGameProfile().getName(), type, TimeFmt.now()));
        boolean busy = r.match != null || r.pending != null || MatchService.inMatch(holder.getUUID()) || CobblemonBridge.isInBattle(holder);
        if (busy) {
            Msg.send(challenger, "league.queued", "post", plainName(p), "position", r.queue.size(), "max", max);
        } else {
            Msg.send(challenger, "league.request_sent", "post", plainName(p), "type", typeLabel(type));
        }
        processQueue(p);
    }

    public static String typeLabel(ChallengeType t) {
        return t == ChallengeType.BADGE ? Msg.format("league.type_badge") : Msg.format("league.type_place");
    }

    public static void leaveQueue(ServerPlayer p) {
        boolean removed = false;
        for (PostData post : data.posts) {
            Runtime r = rt(post);
            removed |= r.queue.removeIf(q -> q.uuid().equals(p.getUUID()));
            if (r.pending != null && r.pending.uuid().equals(p.getUUID())) {
                r.pending = null;
                removed = true;
                Msg.send(holder(post), "league.request_withdrawn", "player", p.getGameProfile().getName());
                processQueue(post);
            }
        }
        Msg.send(p, removed ? "league.queue_left" : "league.err_not_queued");
    }

    private static void clearQueue(PostData p, String msgKey) {
        Runtime r = rt(p);
        List<Queued> all = new ArrayList<>(r.queue);
        if (r.pending != null) all.add(r.pending);
        r.queue.clear();
        r.pending = null;
        for (Queued q : all) Msg.send(q.uuid(), msgKey, "post", plainName(p));
    }

    /** Fait avancer la file : demande d'acceptation au titulaire, ou enchainement automatique apres un match. */
    public static void processQueue(PostData p) {
        Runtime r = rt(p);
        if (r.match != null || r.pending != null || r.queue.isEmpty()) return;
        if (!p.open || p.holderUuid == null) return;
        ServerPlayer holder = Players.get(holder(p));
        if (holder == null || MatchService.inMatch(holder.getUUID()) || CobblemonBridge.isInBattle(holder)) return;
        Queued next = null;
        Iterator<Queued> it = r.queue.iterator();
        while (it.hasNext()) {
            Queued q = it.next();
            ServerPlayer c = Players.get(q.uuid());
            if (c == null) {
                it.remove();
                continue;
            }
            if (MatchService.inMatch(q.uuid()) || CobblemonBridge.isInBattle(c)) continue;
            next = q;
            it.remove();
            break;
        }
        if (next == null) return;
        if (r.nextIsAuto) {
            r.nextIsAuto = false;
            Msg.send(next.uuid(), "league.queue_your_turn", "post", plainName(p));
            startMatch(p, next);
            return;
        }
        r.pending = next;
        r.pendingUntil = TimeFmt.now() + Math.max(10, Cfg.league.i("challenges.accept_timeout_seconds", 60)) * 1000L;
        sendRequest(holder, p, next);
    }

    private static void sendRequest(ServerPlayer holder, PostData p, Queued q) {
        MutableComponent msg = Msg.get("league.request_received", "player", q.name(), "type", typeLabel(q.type()),
                "seconds", Math.max(10, Cfg.league.i("challenges.accept_timeout_seconds", 60)));
        msg.append(Component.literal(" "));
        msg.append(TextUtil.button(Msg.raw("league.button_accept"), "/league accept", Msg.raw("league.button_accept_hover")));
        msg.append(Component.literal(" "));
        msg.append(TextUtil.button(Msg.raw("league.button_refuse"), "/league refuse", Msg.raw("league.button_refuse_hover")));
        holder.sendSystemMessage(msg);
    }

    public static void accept(ServerPlayer holder) {
        PostData p = postOf(holder.getUUID());
        Runtime r = p == null ? null : rt(p);
        if (r == null || r.pending == null) {
            Msg.send(holder, "league.err_no_request");
            return;
        }
        Queued q = r.pending;
        r.pending = null;
        p.consecutiveRefusals = 0;
        save();
        if (!Players.online(q.uuid())) {
            Msg.send(holder, "league.err_challenger_left", "player", q.name());
            processQueue(p);
            return;
        }
        startMatch(p, q);
    }

    public static void refuse(ServerPlayer holder) {
        PostData p = postOf(holder.getUUID());
        Runtime r = p == null ? null : rt(p);
        if (r == null || r.pending == null) {
            Msg.send(holder, "league.err_no_request");
            return;
        }
        Queued q = r.pending;
        r.pending = null;
        Msg.send(q.uuid(), "league.request_refused", "player", holder.getGameProfile().getName(), "post", plainName(p));
        Msg.send(holder, "league.request_refused_holder", "player", q.name());
        countRefusal(p);
        processQueue(p);
    }

    private static void countRefusal(PostData p) {
        if (!p.open) return;
        p.consecutiveRefusals++;
        save();
        int max = Cfg.league.i("inactivity.max_consecutive_refusals", 5);
        if (max > 0 && p.consecutiveRefusals >= max) {
            vacate(p, "league.vacated_refusals", true);
        } else {
            Msg.send(holder(p), "league.refusal_warning", "count", p.consecutiveRefusals, "max", max);
        }
    }

    // ------------------------------------------------------------------ deroulement d'un match

    private static void startMatch(PostData p, Queued q) {
        Runtime r = rt(p);
        UUID holderId = holder(p);
        if (holderId == null) return;
        MatchService.Match m = new MatchService.Match("league", holderId, p.holderName, p.tpHolder, q.uuid(), q.name(), p.tpChallenger);
        m.returnP2 = Cfg.league.b("challenges.return_challenger_after_match", true);
        m.heal = Cfg.league.b("challenges.heal_before_battle", true);
        m.format = Cfg.league.str("challenges.battle_format", "singles");
        m.countdown = Cfg.league.i("challenges.countdown_seconds", 3);
        m.label = TextUtil.strip(plainName(p)) + " - " + TextUtil.strip(typeLabel(q.type()));
        m.context = p.id;
        m.preCheck = player -> {
            if (!player.getUUID().equals(q.uuid())) return null;
            String e = checkEligibility(player, PlayerDataManager.online(player), p, q.type());
            return e;
        };
        m.onEnd = res -> onMatchEnd(p, q, res);
        r.match = m;
        r.current = q;
        Msg.send(holderId, "league.match_starting", "player", q.name(), "type", typeLabel(q.type()));
        Msg.send(q.uuid(), "league.match_starting", "player", p.holderName, "type", typeLabel(q.type()));
        MatchService.start(m);
    }

    private static void onMatchEnd(PostData p, Queued q, MatchService.Result res) {
        Runtime r = rt(p);
        r.match = null;
        r.current = null;
        UUID holderId = res.match().p1;
        String holderName = res.match().n1;
        if (res.aborted()) {
            Msg.send(res.match().p1, "league.match_aborted", "reason", res.reason());
            Msg.send(res.match().p2, "league.match_aborted", "reason", res.reason());
        } else {
            boolean challengerWon = q.uuid().equals(res.winner());
            recordAudit(p, q, res, challengerWon);
            if (q.type() == ChallengeType.BADGE && kind(p) != PostKind.MASTER) {
                if (challengerWon) {
                    awardBadge(p, q, res);
                } else {
                    PlayerDataManager.modify(q.uuid(), q.name(), d -> d.badgeCooldowns.put(p.id, TimeFmt.now() + badgeCooldownMs()));
                    Msg.send(q.uuid(), "league.badge_lost", "post", plainName(p), "time", TimeFmt.duration(badgeCooldownMs()));
                    Msg.send(holderId, "league.badge_defended", "player", q.name());
                }
            } else {
                if (challengerWon) {
                    boolean wasMaster = kind(p) == PostKind.MASTER;
                    assign(p, q.uuid(), q.name(), false);
                    Msg.broadcast("league.place_taken", "player", q.name(), "old", holderName, "post", plainName(p), "grade", grade(p));
                    if (wasMaster) {
                        PlayerDataManager.modify(holderId, holderName, d -> {
                            d.badges.clear();
                            d.badgeCooldowns.clear();
                        });
                        Msg.send(holderId, "league.master_reset");
                    }
                } else {
                    PlayerDataManager.modify(q.uuid(), q.name(), d -> d.placeCooldownUntil = TimeFmt.now() + placeCooldownMs());
                    Msg.send(q.uuid(), "league.place_lost", "post", plainName(p), "time", TimeFmt.duration(placeCooldownMs()));
                    if (Cfg.league.b("challenges.announce_place_defended", true)) {
                        Msg.broadcast("league.place_defended", "player", holderName, "challenger", q.name(), "post", plainName(p));
                    }
                }
            }
            if (res.forfeit()) {
                Msg.send(res.winner(), "league.won_by_forfeit", "player", res.match().nameOf(res.loser()), "reason", res.reason());
            }
        }
        r.nextIsAuto = !res.aborted() && Cfg.league.b("challenges.queue_auto_start", true);
        long delay = Math.max(1, Cfg.league.i("challenges.queue_next_delay_seconds", 5)) * 20L;
        Scheduler.later(delay, () -> processQueue(p));
    }

    private static void awardBadge(PostData p, Queued q, MatchService.Result res) {
        PlayerData.BadgeRecord b = new PlayerData.BadgeRecord();
        b.postId = p.id;
        b.itemId = badgeItemId(p);
        b.obtainedAt = TimeFmt.now();
        b.holderUuid = res.match().p1.toString();
        b.holderName = res.match().n1;
        b.holderTypes = new ArrayList<>(p.types);
        b.durationSeconds = res.durationSeconds();
        b.turns = res.turns();
        b.challengerTeam = new ArrayList<>(res.match().team2);
        b.holderTeam = new ArrayList<>(res.match().team1);
        b.battleId = res.match().battleId == null ? null : res.match().battleId.toString();
        b.note = res.forfeit() ? "Victoire par forfait : " + TextUtil.strip(res.reason()) : null;
        PlayerDataManager.modify(q.uuid(), q.name(), d -> {
            d.badges.put(p.id, b);
            d.badgeCooldowns.remove(p.id);
            int count = d.badges.size();
            Msg.send(q.uuid(), "league.badge_won", "post", plainName(p), "count", count);
            if (Cfg.league.b("challenges.announce_badge_wins", true)) {
                Msg.broadcast("league.badge_won_broadcast", "player", q.name(), "post", plainName(p), "count", count);
            }
        });
    }

    private static void recordAudit(PostData p, Queued q, MatchService.Result res, boolean challengerWon) {
        int max = Math.max(10, Cfg.league.i("audit.max_entries", 100));
        MatchService.Match m = res.match();
        for (int side = 0; side < 2; side++) {
            boolean isChallenger = side == 1;
            UUID me = isChallenger ? m.p2 : m.p1;
            String myName = isChallenger ? m.n2 : m.n1;
            PlayerData.AuditEntry e = new PlayerData.AuditEntry();
            e.time = TimeFmt.now();
            e.context = "league";
            e.postId = p.id;
            e.kind = q.type().name();
            e.opponentUuid = (isChallenger ? m.p1 : m.p2).toString();
            e.opponentName = isChallenger ? m.n1 : m.n2;
            e.asChallenger = isChallenger;
            e.won = isChallenger == challengerWon;
            e.forfeit = res.forfeit();
            e.durationSeconds = res.durationSeconds();
            e.turns = res.turns();
            e.myTeam = new ArrayList<>(isChallenger ? m.team2 : m.team1);
            e.opponentTeam = new ArrayList<>(isChallenger ? m.team1 : m.team2);
            e.note = TextUtil.strip(res.reason());
            boolean won = e.won;
            PlayerDataManager.modify(me, myName, d -> {
                d.audit.add(0, e);
                while (d.audit.size() > max) d.audit.remove(d.audit.size() - 1);
                if (won) d.leagueWins++;
                else d.leagueLosses++;
                for (PlayerData.MonEntry mon : e.myTeam) d.pokemonUsage.merge(mon.species, 1, Integer::sum);
            });
        }
    }

    public static String badgeItemId(PostData p) {
        if (kind(p) == PostKind.CHAMPION && Cfg.items.b("use_type_badges", false) && !p.types.isEmpty()) {
            String byType = Cfg.items.str("type_badges." + p.types.get(0), "");
            if (!byType.isBlank()) return byType;
        }
        return Cfg.items.str("badges." + p.id, "minecraft:nether_star");
    }

    // ------------------------------------------------------------------ boucles

    private static void tickSecond() {
        long now = TimeFmt.now();
        for (PostData p : data.posts) {
            Runtime r = rt(p);
            if (r.pending != null) {
                if (!Players.online(r.pending.uuid())) {
                    r.pending = null;
                    processQueue(p);
                } else if (now >= r.pendingUntil) {
                    Queued q = r.pending;
                    r.pending = null;
                    Msg.send(q.uuid(), "league.request_timeout", "post", plainName(p));
                    Msg.send(holder(p), "league.request_timeout_holder", "player", q.name());
                    countRefusal(p);
                    processQueue(p);
                }
            } else if (r.match == null && !r.queue.isEmpty()) {
                processQueue(p);
            }
        }
    }

    private static void checkInactivity() {
        if (!Cfg.league.b("inactivity.enabled", true)) return;
        long limit = Cfg.league.l("inactivity.days", 7) * TimeFmt.DAY;
        long now = TimeFmt.now();
        for (PostData p : data.posts) {
            if (p.holderUuid == null) continue;
            if (Players.online(holder(p))) {
                p.holderLastSeen = now;
                continue;
            }
            if (p.holderLastSeen > 0 && now - p.holderLastSeen > limit) {
                vacate(p, "league.vacated_inactivity", true);
                CobblePulse.LOG.info("[CobblePulse] Poste {} libere pour inactivite", p.id);
            }
        }
        save();
    }

    public static void onJoin(ServerPlayer player) {
        PostData p = postOf(player.getUUID());
        if (p != null) {
            p.holderLastSeen = TimeFmt.now();
            p.holderName = player.getGameProfile().getName();
            save();
            Scheduler.later(60, () -> processQueue(p));
        }
    }

    public static void onQuit(ServerPlayer player) {
        UUID u = player.getUUID();
        PostData held = postOf(u);
        if (held != null) {
            held.holderLastSeen = TimeFmt.now();
            Runtime r = rt(held);
            if (r.pending != null) {
                r.queue.addFirst(r.pending);
                r.pending = null;
            }
            save();
        }
        for (PostData p : data.posts) {
            Runtime r = rt(p);
            r.queue.removeIf(q -> q.uuid().equals(u));
            if (r.pending != null && r.pending.uuid().equals(u)) r.pending = null;
        }
    }

    /** Remise a zero de saison : tous les postes vacants. */
    public static void resetAll() {
        for (PostData p : data.posts) {
            vacate(p, null, false);
            p.lastHolderChange = 0;
        }
        save();
    }

    public static Map<UUID, PostKind> holders() {
        Map<UUID, PostKind> out = new HashMap<>();
        for (PostData p : data.posts) {
            UUID u = holder(p);
            if (u != null) out.put(u, kind(p));
        }
        return out;
    }

    /** Auto-test : demarre directement un match de Ligue entre deux joueurs. */
    public static void debugStart(PostData p, ServerPlayer challenger, ChallengeType type) {
        startMatch(p, new Queued(challenger.getUUID(), challenger.getGameProfile().getName(), type, TimeFmt.now()));
    }

    public static MatchService.Match currentMatch(PostData p) {
        return rt(p).match;
    }
}
