package fr.cobblepulse.tournament;

import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.battle.MatchService;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.config.TierRules;
import fr.cobblepulse.core.Json;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.MiscState;
import fr.cobblepulse.data.TournamentData;
import fr.cobblepulse.integration.LuckPermsBridge;
import fr.cobblepulse.storage.DataService;
import fr.cobblepulse.storage.PlayerDataManager;
import fr.cobblepulse.storage.StateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Moteur de tournois : inscriptions, randomizer, rondes, arenes multiples, disqualifications, annonces et sacre. */
public final class TournamentManager {
    private static TournamentData t;
    private static final Map<String, MatchService.Match> ARENA_BUSY = new HashMap<>();
    private static final Map<MatchService.Match, TournamentData.Match> RUNNING = new HashMap<>();
    private static boolean warnedNoArena;

    private TournamentManager() {}

    // ------------------------------------------------------------------ etat

    public static void load() {
        String json = DataService.blocking(s -> s.loadKv("tournament"));
        t = json == null ? null : Json.GSON.fromJson(json, TournamentData.class);
        if (t != null) {
            for (TournamentData.Round r : t.rounds) {
                for (TournamentData.Match m : r.matches) if ("RUNNING".equals(m.status)) m.status = "PENDING";
            }
            if (t.thirdPlace != null && "RUNNING".equals(t.thirdPlace.status)) t.thirdPlace.status = "PENDING";
        }
        Scheduler.repeat(40, 40, TournamentManager::dispatch);
    }

    public static void save() {
        TournamentData cur = t;
        DataService.markKv("tournament", () -> cur);
    }

    public static TournamentData current() {
        return t;
    }

    public static boolean isOpen() {
        return t != null && "OPEN".equals(t.state);
    }

    public static boolean isRunning() {
        return t != null && "RUNNING".equals(t.state);
    }

    public static TierRules rules() {
        return t == null ? null : Cfg.TIERS.get(t.tier);
    }

    public static List<MiscState.ArenaData> arenas() {
        return StateManager.get().tournamentArenas;
    }

    public static TournamentData.Round round() {
        if (t == null || t.currentRound < 0 || t.currentRound >= t.rounds.size()) return null;
        return t.rounds.get(t.currentRound);
    }

    public static String roundName(int slots) {
        String key = Bracket.roundKey(slots);
        return Msg.format("tournament.rounds." + key, "players", slots, "matches", slots / 2);
    }

    // ------------------------------------------------------------------ creation / inscriptions

    public static boolean create(ServerPlayer creatorOrNull, String tierId, String name, net.minecraft.commands.CommandSourceStack src) {
        if (t != null && ("OPEN".equals(t.state) || "RUNNING".equals(t.state))) {
            Msg.send(src, "tournament.err_already_running");
            return false;
        }
        String tier = tierId.toLowerCase(Locale.ROOT);
        TierRules rules = Cfg.TIERS.get(tier);
        if (rules == null) {
            Msg.send(src, "tournament.err_unknown_tier", "tier", tierId, "tiers", String.join(", ", Cfg.TIERS.keySet()));
            return false;
        }
        if ("start".equalsIgnoreCase(Cfg.tournament.str("remove_winner_rank_on", "start"))) removeWinnerRank();
        t = new TournamentData();
        t.id = UUID.randomUUID().toString();
        t.tier = tier;
        t.name = name == null || name.isBlank() ? Msg.format("tournament.default_name", "tier", rules.displayName) : name;
        t.createdAt = TimeFmt.now();
        t.createdBy = creatorOrNull == null ? "Console" : creatorOrNull.getGameProfile().getName();
        t.state = "OPEN";
        ARENA_BUSY.clear();
        RUNNING.clear();
        save();
        MutableComponent msg = Msg.get("tournament.created", "name", t.name, "tier", rules.displayName);
        msg.append(Component.literal(" "));
        msg.append(TextUtil.button(Msg.raw("tournament.button_join"), "/tournoi join", Msg.raw("tournament.button_join_hover")));
        Msg.broadcast(msg);
        return true;
    }

    public static void join(ServerPlayer p) {
        if (!isOpen()) {
            Msg.send(p, "tournament.err_not_open");
            return;
        }
        if (!Perms.player(p, Perms.TOURNAMENT_JOIN)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        if (t.participants.containsKey(p.getUUID().toString())) {
            Msg.send(p, "tournament.err_already_joined");
            return;
        }
        int max = Cfg.tournament.i("max_participants", 512);
        if (t.participants.size() >= max) {
            Msg.send(p, "tournament.err_full", "max", max);
            return;
        }
        List<String> errors = TeamValidator.validate(p, rules());
        if (!errors.isEmpty()) {
            Msg.send(p, "tournament.team_invalid", "tier", rules().displayName);
            for (String e : errors) p.sendSystemMessage(TextUtil.legacy("&c - " + e));
            return;
        }
        t.participants.put(p.getUUID().toString(), new TournamentData.Participant(p.getUUID().toString(), p.getGameProfile().getName()));
        save();
        Msg.send(p, "tournament.joined", "name", t.name, "count", t.participants.size());
    }

    public static void leave(ServerPlayer p) {
        if (!isOpen()) {
            Msg.send(p, "tournament.err_not_open");
            return;
        }
        if (t.participants.remove(p.getUUID().toString()) != null) {
            save();
            Msg.send(p, "tournament.left");
        } else {
            Msg.send(p, "tournament.err_not_joined");
        }
    }

    // ------------------------------------------------------------------ randomizer & rondes

    /** Cloture des inscriptions, melange aleatoire et generation de l'arbre. */
    public static boolean start(net.minecraft.commands.CommandSourceStack src) {
        if (!isOpen()) {
            Msg.send(src, "tournament.err_not_open");
            return false;
        }
        int min = Math.max(2, Cfg.tournament.i("min_participants", 2));
        if (t.participants.size() < min) {
            Msg.send(src, "tournament.err_not_enough", "min", min);
            return false;
        }
        List<TournamentData.Participant> list = new ArrayList<>(t.participants.values());
        Collections.shuffle(list, new SecureRandom());
        TournamentData.Round r0 = Bracket.firstRound(list);
        t.bracketSize = r0.slots;
        t.rounds.clear();
        t.rounds.add(r0);
        t.currentRound = 0;
        t.roundActive = false;
        t.state = "RUNNING";
        for (TournamentData.Participant p : t.participants.values()) {
            PlayerDataManager.modify(UUID.fromString(p.uuid), p.name, d -> d.tournamentsPlayed++);
        }
        save();
        int byes = r0.slots - list.size();
        Msg.broadcast("tournament.bracket_generated", "name", t.name, "count", list.size(), "size", r0.slots, "byes", byes);
        if (Cfg.tournament.b("auto_start_rounds", false)) {
            Scheduler.later(100, () -> startRound(null));
        } else {
            Msg.send(src, "tournament.staff_next_round_hint");
        }
        return true;
    }

    /** Lance la ronde courante (byes et forfaits resolus immediatement, matchs repartis sur les arenes). */
    public static boolean startRound(net.minecraft.commands.CommandSourceStack src) {
        if (!isRunning()) {
            if (src != null) Msg.send(src, "tournament.err_not_running");
            return false;
        }
        TournamentData.Round r = round();
        if (r == null) return false;
        if (t.roundActive) {
            if (src != null) Msg.send(src, "tournament.err_round_active");
            return false;
        }
        if (arenas().isEmpty()) {
            if (src != null) Msg.send(src, "tournament.err_no_arena");
            return false;
        }
        t.roundActive = true;
        r.started = true;
        Msg.broadcast("tournament.round_started", "round", roundName(r.slots), "name", t.name);
        for (TournamentData.Match m : allMatches(r)) {
            if (m.bye && !"DONE".equals(m.status)) {
                m.winner = m.p1;
                m.status = "DONE";
                m.note = "bye";
                if (m.p1 != null) Msg.send(UUID.fromString(m.p1), "tournament.bye", "round", roundName(r.slots));
            }
        }
        resolveWalkovers(r);
        save();
        dispatch();
        checkRoundComplete();
        return true;
    }

    private static List<TournamentData.Match> allMatches(TournamentData.Round r) {
        List<TournamentData.Match> out = new ArrayList<>(r.matches);
        if (t.thirdPlace != null && r.slots == 2) out.add(t.thirdPlace);
        return out;
    }

    private static boolean dq(String uuid) {
        TournamentData.Participant p = uuid == null ? null : t.participants.get(uuid);
        return p != null && p.disqualified;
    }

    /** Matchs dont un joueur est disqualifie : victoire automatique de l'adversaire. */
    private static void resolveWalkovers(TournamentData.Round r) {
        for (TournamentData.Match m : allMatches(r)) {
            if (!"PENDING".equals(m.status) || m.bye) continue;
            boolean d1 = dq(m.p1);
            boolean d2 = dq(m.p2);
            if (d1 && d2) {
                m.winner = null;
                m.status = "DONE";
                m.note = "double-dq";
                eliminate(m.p1, r, m.thirdPlace, false);
                eliminate(m.p2, r, m.thirdPlace, false);
            } else if (d1 || d2) {
                String winner = d1 ? m.p2 : m.p1;
                String loser = d1 ? m.p1 : m.p2;
                completeMatch(r, m, winner, loser, true, Msg.format("tournament.reason_dq"));
            }
        }
    }

    /** Repartition des matchs en attente sur les arenes libres. */
    public static void dispatch() {
        if (!isRunning() || !t.roundActive) return;
        TournamentData.Round r = round();
        if (r == null) return;
        List<MiscState.ArenaData> arenas = arenas();
        if (arenas.isEmpty()) {
            if (!warnedNoArena) {
                warnedNoArena = true;
                CobblePulse.LOG.warn("[CobblePulse] Aucune arene de tournoi configuree (/tournoi arena add)");
            }
            return;
        }
        for (TournamentData.Match m : allMatches(r)) {
            if (!"PENDING".equals(m.status) || m.bye || m.p1 == null || m.p2 == null) continue;
            MiscState.ArenaData arena = freeArena(arenas);
            if (arena == null) return;
            startMatch(r, m, arena);
        }
    }

    private static MiscState.ArenaData freeArena(List<MiscState.ArenaData> arenas) {
        for (MiscState.ArenaData a : arenas) {
            if (a.p1 == null || a.p2 == null) continue;
            if (!ARENA_BUSY.containsKey(a.name.toLowerCase(Locale.ROOT))) return a;
        }
        return null;
    }

    private static void startMatch(TournamentData.Round r, TournamentData.Match tm, MiscState.ArenaData a) {
        String arenaKey = a.name.toLowerCase(Locale.ROOT);
        UUID u1 = UUID.fromString(tm.p1);
        UUID u2 = UUID.fromString(tm.p2);
        MatchService.Match m = new MatchService.Match("tournament", u1, tm.n1, a.p1, u2, tm.n2, a.p2);
        m.returnP1 = Cfg.tournament.b("return_players_after_match", true);
        m.returnP2 = m.returnP1;
        m.heal = Cfg.tournament.b("heal_before_battle", true);
        m.format = Cfg.tournament.str("battle_format", "singles");
        m.countdown = Cfg.tournament.i("countdown_seconds", 5);
        m.label = TextUtil.strip(tm.thirdPlace ? Msg.format("tournament.rounds.third_place") : roundName(r.slots));
        TierRules rules = rules();
        m.preCheck = player -> {
            List<String> errors = TeamValidator.validate(player, rules);
            if (errors.isEmpty()) return null;
            Msg.send(player, "tournament.team_invalid", "tier", rules.displayName);
            for (String e : errors) player.sendSystemMessage(TextUtil.legacy("&c - " + e));
            return Msg.format("tournament.reason_invalid_team", "player", player.getGameProfile().getName());
        };
        m.onEnd = res -> onMatchEnd(r, tm, arenaKey, res);
        tm.status = "RUNNING";
        ARENA_BUSY.put(arenaKey, m);
        RUNNING.put(m, tm);
        save();
        Msg.send(u1, "tournament.match_called", "player", tm.n2, "round", m.label, "arena", a.name);
        Msg.send(u2, "tournament.match_called", "player", tm.n1, "round", m.label, "arena", a.name);
        MatchService.start(m);
    }

    private static void onMatchEnd(TournamentData.Round r, TournamentData.Match tm, String arenaKey, MatchService.Result res) {
        ARENA_BUSY.remove(arenaKey);
        RUNNING.remove(res.match());
        if (t == null || !"RUNNING".equals(t.state)) return;
        if (res.aborted()) {
            tm.status = "PENDING";
            Msg.send(res.match().p1, "tournament.match_restart", "reason", res.reason());
            Msg.send(res.match().p2, "tournament.match_restart", "reason", res.reason());
            save();
            Scheduler.later(100, TournamentManager::dispatch);
            return;
        }
        completeMatch(r, tm, res.winner().toString(), res.loser().toString(), res.forfeit(), res.reason());
        Scheduler.later(60, TournamentManager::dispatch);
    }

    /** Enregistre l'issue d'un match, annonce globale avec l'echelon, statistiques et classement. */
    private static void completeMatch(TournamentData.Round r, TournamentData.Match m, String winner, String loser, boolean forfeit, String reason) {
        m.winner = winner;
        m.status = "DONE";
        m.note = forfeit ? TextUtil.strip(reason) : null;
        TournamentData.Participant pw = t.participants.get(winner);
        TournamentData.Participant pl = t.participants.get(loser);
        if (pw != null) pw.wins++;
        if (pl != null) pl.losses++;
        String wName = pw == null ? "?" : pw.name;
        String lName = pl == null ? "?" : pl.name;
        PlayerDataManager.modify(UUID.fromString(winner), wName, d -> d.tournamentMatchWins++);
        PlayerDataManager.modify(UUID.fromString(loser), lName, d -> d.tournamentMatchLosses++);
        String roundLabel = m.thirdPlace ? Msg.format("tournament.rounds.third_place") : roundName(r.slots);
        Msg.broadcast(forfeit ? "tournament.match_result_forfeit" : "tournament.match_result",
                "winner", wName, "loser", lName, "round", roundLabel, "name", t.name, "reason", TextUtil.strip(reason));
        eliminate(loser, r, m.thirdPlace, false);
        if (m.thirdPlace) {
            if (pw != null) {
                pw.placementLo = 3;
                pw.placementHi = 3;
            }
            if (pl != null) {
                pl.placementLo = 4;
                pl.placementHi = 4;
            }
        }
        save();
        checkRoundComplete();
    }

    private static void eliminate(String uuid, TournamentData.Round r, boolean thirdPlaceMatch, boolean ignored) {
        if (uuid == null) return;
        TournamentData.Participant p = t.participants.get(uuid);
        if (p == null || thirdPlaceMatch) return;
        p.placementLo = r.slots / 2 + 1;
        p.placementHi = r.slots;
    }

    private static void checkRoundComplete() {
        TournamentData.Round r = round();
        if (r == null || !t.roundActive) return;
        for (TournamentData.Match m : allMatches(r)) if (!"DONE".equals(m.status)) return;
        t.roundActive = false;
        if (r.slots <= 2) {
            finish();
            return;
        }
        TournamentData.Round next = Bracket.nextRound(r, t);
        if (r.slots == 4 && Cfg.tournament.b("third_place_match", true)) {
            List<String> losers = new ArrayList<>();
            for (TournamentData.Match m : r.matches) {
                if (m.bye || m.winner == null) continue;
                String loser = m.winner.equals(m.p1) ? m.p2 : m.p1;
                if (loser != null && !dq(loser)) losers.add(loser);
            }
            if (losers.size() == 2) {
                TournamentData.Match tp = new TournamentData.Match();
                tp.index = 1;
                tp.thirdPlace = true;
                tp.p1 = losers.get(0);
                tp.n1 = Bracket.name(t, tp.p1);
                tp.p2 = losers.get(1);
                tp.n2 = Bracket.name(t, tp.p2);
                t.thirdPlace = tp;
            }
        }
        t.rounds.add(next);
        t.currentRound = next.index;
        save();
        Msg.broadcast("tournament.round_ready", "round", roundName(next.slots), "name", t.name);
        if (Cfg.tournament.b("auto_start_rounds", false)) {
            Scheduler.later(20L * Math.max(1, Cfg.tournament.i("round_delay_seconds", 10)), () -> startRound(null));
        } else {
            notifyStaff("tournament.staff_next_round_hint");
        }
    }

    private static void notifyStaff(String key) {
        if (CobblePulse.server() == null) return;
        for (ServerPlayer p : CobblePulse.server().getPlayerList().getPlayers()) {
            if (Perms.staff(p, Perms.T_MANAGE)) Msg.send(p, key);
        }
    }

    // ------------------------------------------------------------------ fin du tournoi

    private static void finish() {
        TournamentData.Round fin = round();
        TournamentData.Match finalMatch = null;
        for (TournamentData.Match m : fin.matches) if (!m.thirdPlace) finalMatch = m;
        String winner = finalMatch == null ? null : finalMatch.winner;
        t.state = "FINISHED";
        t.winnerUuid = winner;
        if (winner != null) {
            TournamentData.Participant w = t.participants.get(winner);
            w.placementLo = 1;
            w.placementHi = 1;
            String loser = winner.equals(finalMatch.p1) ? finalMatch.p2 : finalMatch.p1;
            TournamentData.Participant l = loser == null ? null : t.participants.get(loser);
            if (l != null) {
                l.placementLo = 2;
                l.placementHi = 2;
            }
            if ("end".equalsIgnoreCase(Cfg.tournament.str("remove_winner_rank_on", "start"))) removeWinnerRank();
            grantWinnerRank(UUID.fromString(winner), w.name);
            PlayerDataManager.modify(UUID.fromString(winner), w.name, d -> d.tournamentsWon++);
            Msg.broadcast("tournament.winner", "player", w.name, "name", t.name, "tier", rules() == null ? t.tier : rules().displayName);
        } else {
            Msg.broadcast("tournament.finished_no_winner", "name", t.name);
        }
        save();
        RewardManager.distribute(t);
        t.rewardsDistributed = true;
        save();
    }

    private static void grantWinnerRank(UUID uuid, String name) {
        MiscState st = StateManager.get();
        st.tournamentWinnerUuid = uuid.toString();
        st.tournamentWinnerName = name;
        StateManager.save();
        LuckPermsBridge.grant(uuid, List.of(Perms.TOURNAMENT_WINNER),
                Cfg.tournament.b("winner_luckperms_prefix", true) ? Cfg.tournament.str("winner_prefix", "&e[Vainqueur Tournoi] ") : null,
                Cfg.tournament.i("winner_prefix_priority", 90), Cfg.tournament.str("winner_group", ""));
    }

    /** Retire le grade temporaire [Vainqueur Tournoi] du dernier vainqueur. */
    public static void removeWinnerRank() {
        MiscState st = StateManager.get();
        if (st.tournamentWinnerUuid == null) return;
        try {
            LuckPermsBridge.revoke(UUID.fromString(st.tournamentWinnerUuid), List.of(Perms.TOURNAMENT_WINNER),
                    Cfg.tournament.str("winner_prefix", "&e[Vainqueur Tournoi] "), Cfg.tournament.i("winner_prefix_priority", 90),
                    Cfg.tournament.str("winner_group", ""));
        } catch (IllegalArgumentException ignored) {
        }
        st.tournamentWinnerUuid = null;
        st.tournamentWinnerName = null;
        StateManager.save();
    }

    // ------------------------------------------------------------------ staff

    /** Disqualification en un clic : l'adversaire est automatiquement qualifie. */
    public static void disqualify(net.minecraft.commands.CommandSourceStack src, String uuid, String reason) {
        if (t == null || (!isOpen() && !isRunning())) {
            Msg.send(src, "tournament.err_no_tournament");
            return;
        }
        TournamentData.Participant p = t.participants.get(uuid);
        if (p == null) {
            Msg.send(src, "tournament.err_not_participant");
            return;
        }
        if (isOpen()) {
            t.participants.remove(uuid);
            save();
            Msg.broadcast("tournament.disqualified", "player", p.name, "reason", reason);
            return;
        }
        if (p.disqualified) {
            Msg.send(src, "tournament.err_already_dq");
            return;
        }
        p.disqualified = true;
        p.dqReason = reason;
        Msg.broadcast("tournament.disqualified", "player", p.name, "reason", reason);
        UUID u = UUID.fromString(uuid);
        for (Map.Entry<MatchService.Match, TournamentData.Match> e : new ArrayList<>(RUNNING.entrySet())) {
            if (e.getKey().involves(u)) {
                MatchService.forfeit(e.getKey(), u, Msg.format("tournament.reason_dq"));
                save();
                return;
            }
        }
        TournamentData.Round r = round();
        if (r != null && t.roundActive) resolveWalkovers(r);
        save();
    }

    public static void cancel(net.minecraft.commands.CommandSourceStack src) {
        if (t == null || (!isOpen() && !isRunning())) {
            Msg.send(src, "tournament.err_no_tournament");
            return;
        }
        for (MatchService.Match m : new ArrayList<>(RUNNING.keySet())) MatchService.abort(m, Msg.format("tournament.cancelled_reason"));
        RUNNING.clear();
        ARENA_BUSY.clear();
        t.state = "CANCELLED";
        save();
        Msg.broadcast("tournament.cancelled", "name", t.name);
    }

    public static boolean isPlaying(UUID u) {
        for (MatchService.Match m : RUNNING.keySet()) if (m.involves(u)) return true;
        return false;
    }

    public static Map<MatchService.Match, TournamentData.Match> running() {
        return RUNNING;
    }

    // ------------------------------------------------------------------ arenes

    public static MiscState.ArenaData arena(String name) {
        for (MiscState.ArenaData a : arenas()) if (a.name.equalsIgnoreCase(name)) return a;
        return null;
    }

    public static void addArena(String name) {
        MiscState.ArenaData a = new MiscState.ArenaData();
        a.name = name;
        arenas().add(a);
        StateManager.save();
        warnedNoArena = false;
    }

    public static boolean removeArena(String name) {
        boolean removed = arenas().removeIf(a -> a.name.equalsIgnoreCase(name));
        if (removed) StateManager.save();
        return removed;
    }

    /** Auto-test : arbre pour n participants fictifs. */
    public static String selfTestBracket() {
        StringBuilder out = new StringBuilder();
        for (int n = 2; n <= 70; n++) {
            List<TournamentData.Participant> list = new ArrayList<>();
            for (int i = 0; i < n; i++) list.add(new TournamentData.Participant(UUID.randomUUID().toString(), "P" + i));
            TournamentData.Round r = Bracket.firstRound(list);
            int byes = 0, players = 0;
            for (TournamentData.Match m : r.matches) {
                if (m.bye) {
                    byes++;
                    if (m.p1 == null) return "FAIL n=" + n + " bye sans joueur";
                }
                players += (m.p1 != null ? 1 : 0) + (m.p2 != null ? 1 : 0);
            }
            if (players != n || byes != r.slots - n || r.slots < n || r.slots >= 2 * n && n > 1) {
                return "FAIL n=" + n + " joueurs=" + players + " byes=" + byes + " taille=" + r.slots;
            }
        }
        out.append("OK (n=2..70)");
        return out.toString();
    }
}
