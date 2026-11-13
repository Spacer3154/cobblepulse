package fr.cobblepulse.battle;

import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.Fx;
import fr.cobblepulse.core.Loc;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.PlayerData.MonEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Gestion des matchs officiels (Ligue et Tournoi) : teleportation, compte a rebours, lancement du combat Cobblemon,
 * detection de l'issue, delai de grace en cas de deconnexion puis forfait automatique.
 */
public final class MatchService {
    public enum State { PREPARING, RUNNING, SUSPENDED, FINISHED }

    public static final class Match {
        public final UUID id = UUID.randomUUID();
        public final String owner;
        public final UUID p1;
        public final UUID p2;
        public final String n1;
        public final String n2;
        public final Loc loc1;
        public final Loc loc2;
        public boolean returnP1;
        public boolean returnP2;
        public Loc back1;
        public Loc back2;
        public UUID battleId;
        public long startedAt;
        public State state = State.PREPARING;
        public List<MonEntry> team1 = new ArrayList<>();
        public List<MonEntry> team2 = new ArrayList<>();
        public final Map<UUID, Long> disconnectedAt = new HashMap<>();
        public long graceUntil;
        public boolean heal = true;
        public String format = "singles";
        public int countdown = 3;
        public String label = "";
        public Object context;
        public Consumer<Result> onEnd = r -> {};
        /** Verification juste avant le combat : renvoie un message d'erreur pour le joueur fautif, ou null. */
        public Function<ServerPlayer, String> preCheck = p -> null;
        long battleGoneSince;
        int generation;

        public Match(String owner, UUID p1, String n1, Loc loc1, UUID p2, String n2, Loc loc2) {
            this.owner = owner;
            this.p1 = p1;
            this.n1 = n1;
            this.loc1 = loc1;
            this.p2 = p2;
            this.n2 = n2;
            this.loc2 = loc2;
        }

        public UUID other(UUID u) {
            return p1.equals(u) ? p2 : p1;
        }

        public String nameOf(UUID u) {
            return p1.equals(u) ? n1 : n2;
        }

        public boolean involves(UUID u) {
            return p1.equals(u) || p2.equals(u);
        }
    }

    /** Issue d'un match. winner/loser null si le match est annule. */
    public record Result(Match match, UUID winner, UUID loser, boolean forfeit, boolean aborted, int turns,
                         long durationSeconds, String reason) {}

    private static final Map<UUID, Match> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Match> BY_BATTLE = new HashMap<>();
    private static final List<Match> ACTIVE = new ArrayList<>();

    private MatchService() {}

    public static void init() {
        CobblemonBridge.onPvpResult(MatchService::onPvp);
        Scheduler.repeat(20, 20, MatchService::tickSecond);
    }

    public static boolean inMatch(UUID player) {
        return BY_PLAYER.containsKey(player);
    }

    public static Match matchOf(UUID player) {
        return BY_PLAYER.get(player);
    }

    public static List<Match> active() {
        return new ArrayList<>(ACTIVE);
    }

    private static long graceMs() {
        return Math.max(5, Cfg.league.i("disconnect.grace_seconds", 60)) * 1000L;
    }

    /** Demarre un match officiel. */
    public static void start(Match m) {
        BY_PLAYER.put(m.p1, m);
        BY_PLAYER.put(m.p2, m);
        ACTIVE.add(m);
        List<UUID> offline = new ArrayList<>();
        if (!Players.online(m.p1)) offline.add(m.p1);
        if (!Players.online(m.p2)) offline.add(m.p2);
        if (!offline.isEmpty()) {
            m.state = State.SUSPENDED;
            m.graceUntil = TimeFmt.now() + graceMs();
            for (UUID u : offline) m.disconnectedAt.put(u, TimeFmt.now());
            for (UUID u : offline) {
                Msg.send(m.other(u), "match.opponent_offline_start", "player", m.nameOf(u), "seconds", graceMs() / 1000);
            }
            return;
        }
        prepare(m);
    }

    private static void prepare(Match m) {
        ServerPlayer a = Players.get(m.p1);
        ServerPlayer b = Players.get(m.p2);
        if (a == null || b == null) return;
        m.state = State.PREPARING;
        int gen = ++m.generation;
        if (m.back1 == null && m.returnP1) m.back1 = Loc.of(a);
        if (m.back2 == null && m.returnP2) m.back2 = Loc.of(b);
        if (m.loc1 != null) m.loc1.teleport(a);
        if (m.loc2 != null) m.loc2.teleport(b);
        Fx.arenaEntrance(a);
        Fx.arenaEntrance(b);
        int cd = Math.max(0, m.countdown);
        for (int i = 0; i < cd; i++) {
            int remaining = cd - i;
            Scheduler.later(1 + i * 20L, () -> {
                if (m.state != State.PREPARING || m.generation != gen) return;
                for (UUID u : List.of(m.p1, m.p2)) {
                    ServerPlayer p = Players.get(u);
                    if (p == null) continue;
                    Fx.title(p, Msg.format("match.countdown_title", "seconds", remaining),
                            Msg.format("match.countdown_subtitle", "player", m.nameOf(m.other(u)), "label", m.label));
                    Fx.sound(p, SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f + (0.1f * (cd - remaining)));
                }
            });
        }
        Scheduler.later(1 + cd * 20L, () -> {
            if (m.state == State.PREPARING && m.generation == gen) launch(m);
        });
    }

    private static void launch(Match m) {
        ServerPlayer a = Players.get(m.p1);
        ServerPlayer b = Players.get(m.p2);
        if (a == null || b == null) return;
        for (ServerPlayer p : List.of(a, b)) {
            String err = m.preCheck.apply(p);
            if (err != null) {
                finish(m, m.other(p.getUUID()), p.getUUID(), true, false, 0, err);
                return;
            }
            if (CobblemonBridge.isInBattle(p)) {
                finish(m, null, null, false, true, 0, Msg.format("match.already_in_battle", "player", p.getGameProfile().getName()));
                return;
            }
        }
        m.team1 = CobblemonBridge.team(a);
        m.team2 = CobblemonBridge.team(b);
        StringBuilder err = new StringBuilder();
        UUID battleId = CobblemonBridge.startPvp(a, b, m.heal, m.format, err);
        if (battleId == null) {
            finish(m, null, null, false, true, 0, Msg.format("match.start_failed", "error", err.toString()));
            return;
        }
        m.battleId = battleId;
        m.battleGoneSince = 0;
        if (m.startedAt == 0) m.startedAt = TimeFmt.now();
        m.state = State.RUNNING;
        BY_BATTLE.put(battleId, m);
        Fx.title(a, Msg.format("match.go_title"), Msg.format("match.go_subtitle", "player", m.n2));
        Fx.title(b, Msg.format("match.go_title"), Msg.format("match.go_subtitle", "player", m.n1));
    }

    private static void onPvp(CobblemonBridge.PvpResult r) {
        Match m = BY_BATTLE.get(r.battleId());
        if (m == null) return;
        // Traitement differe d'un tick : laisse la deconnexion eventuelle etre enregistree d'abord
        Scheduler.next(() -> {
            if (m.state != State.RUNNING || !r.battleId().equals(m.battleId)) return;
            if (!Players.online(m.p1) || !Players.online(m.p2)) return;
            UUID winner = r.winners().contains(m.p1) ? m.p1 : r.winners().contains(m.p2) ? m.p2 : null;
            if (winner == null) {
                if (r.losers().contains(m.p1)) winner = m.p2;
                else if (r.losers().contains(m.p2)) winner = m.p1;
            }
            if (winner == null) {
                finish(m, null, null, false, true, r.turns(), Msg.format("match.no_winner"));
                return;
            }
            finish(m, winner, m.other(winner), false, false, r.turns(), Msg.format("match.reason_victory"));
        });
    }

    public static void onQuit(ServerPlayer p) {
        Match m = BY_PLAYER.get(p.getUUID());
        if (m == null || m.state == State.FINISHED) return;
        if (m.state != State.SUSPENDED) {
            if (m.battleId != null) {
                BY_BATTLE.remove(m.battleId);
                CobblemonBridge.stopBattle(m.battleId);
                m.battleId = null;
            }
            m.state = State.SUSPENDED;
            m.graceUntil = TimeFmt.now() + graceMs();
        }
        m.disconnectedAt.putIfAbsent(p.getUUID(), TimeFmt.now());
        long secs = Math.max(0, (m.graceUntil - TimeFmt.now()) / 1000);
        Msg.send(m.other(p.getUUID()), "match.opponent_disconnected", "player", p.getGameProfile().getName(), "seconds", secs);
    }

    public static void onJoin(ServerPlayer p) {
        Match m = BY_PLAYER.get(p.getUUID());
        if (m == null || m.state != State.SUSPENDED) return;
        m.disconnectedAt.remove(p.getUUID());
        if (Players.online(m.p1) && Players.online(m.p2)) {
            Msg.send(m.p1, "match.resumed", "player", m.n2);
            Msg.send(m.p2, "match.resumed", "player", m.n1);
            Scheduler.later(40, () -> {
                if (m.state == State.SUSPENDED && Players.online(m.p1) && Players.online(m.p2)) prepare(m);
            });
        } else {
            Msg.send(p, "match.waiting_opponent", "player", m.nameOf(m.other(p.getUUID())));
        }
    }

    private static void tickSecond() {
        long now = TimeFmt.now();
        for (Match m : new ArrayList<>(ACTIVE)) {
            if (m.state == State.SUSPENDED && now >= m.graceUntil) {
                boolean on1 = Players.online(m.p1);
                boolean on2 = Players.online(m.p2);
                if (on1 && on2) {
                    prepare(m);
                } else if (on1 != on2) {
                    UUID loser = on1 ? m.p2 : m.p1;
                    finish(m, m.other(loser), loser, true, false, 0, Msg.format("match.reason_disconnect"));
                } else {
                    long d1 = m.disconnectedAt.getOrDefault(m.p1, now);
                    long d2 = m.disconnectedAt.getOrDefault(m.p2, now);
                    UUID loser = d1 <= d2 ? m.p1 : m.p2;
                    finish(m, m.other(loser), loser, true, false, 0, Msg.format("match.reason_disconnect"));
                }
            } else if (m.state == State.RUNNING && !CobblemonBridge.battleActive(m.battleId)) {
                if (m.battleGoneSince == 0) {
                    m.battleGoneSince = now;
                } else if (now - m.battleGoneSince >= 3000 && Players.online(m.p1) && Players.online(m.p2)) {
                    finish(m, null, null, false, true, 0, Msg.format("match.interrupted"));
                }
            }
        }
    }

    /** Forfait force (disqualification, refus...). */
    public static void forfeit(Match m, UUID loser, String reason) {
        if (m == null || m.state == State.FINISHED) return;
        finish(m, m.other(loser), loser, true, false, CobblemonBridge.turn(m.battleId), reason);
    }

    /** Annulation sans vainqueur. */
    public static void abort(Match m, String reason) {
        if (m == null || m.state == State.FINISHED) return;
        finish(m, null, null, false, true, 0, reason);
    }

    private static void finish(Match m, UUID winner, UUID loser, boolean forfeit, boolean aborted, int turns, String reason) {
        if (m.state == State.FINISHED) return;
        m.state = State.FINISHED;
        ACTIVE.remove(m);
        if (BY_PLAYER.get(m.p1) == m) BY_PLAYER.remove(m.p1);
        if (BY_PLAYER.get(m.p2) == m) BY_PLAYER.remove(m.p2);
        if (m.battleId != null) {
            BY_BATTLE.remove(m.battleId);
            if (CobblemonBridge.battleActive(m.battleId)) CobblemonBridge.stopBattle(m.battleId);
        }
        long duration = m.startedAt > 0 ? (TimeFmt.now() - m.startedAt) / 1000 : 0;
        Result res = new Result(m, winner, loser, forfeit, aborted, turns, duration, reason);
        try {
            m.onEnd.accept(res);
        } catch (Throwable t) {
            CobblePulse.LOG.error("[CobblePulse] Erreur de fin de match", t);
        }
        long delay = Math.max(1, Cfg.league.i("challenges.return_delay_seconds", 5)) * 20L;
        Scheduler.later(delay, () -> {
            if (m.returnP1 && m.back1 != null) {
                ServerPlayer p = Players.get(m.p1);
                if (p != null && !inMatch(m.p1)) m.back1.teleport(p);
            }
            if (m.returnP2 && m.back2 != null) {
                ServerPlayer p = Players.get(m.p2);
                if (p != null && !inMatch(m.p2)) m.back2.teleport(p);
            }
        });
    }

    public static void clear() {
        BY_PLAYER.clear();
        BY_BATTLE.clear();
        ACTIVE.clear();
    }
}
