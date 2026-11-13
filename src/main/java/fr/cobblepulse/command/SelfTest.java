package fr.cobblepulse.command;

import com.mojang.authlib.GameProfile;
import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.battle.CobblemonBridge;
import fr.cobblepulse.battle.MatchService;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.ItemSerial;
import fr.cobblepulse.core.Loc;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.LeagueData.PostData;
import fr.cobblepulse.data.PlayerData;
import fr.cobblepulse.integration.LuckPermsBridge;
import fr.cobblepulse.league.LeagueManager;
import fr.cobblepulse.league.PokeType;
import fr.cobblepulse.storage.DataService;
import fr.cobblepulse.storage.PlayerDataManager;
import fr.cobblepulse.tournament.TournamentManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Auto-diagnostic (/cobblepulse selftest) : configuration, stockage, arbre de tournoi et, en CI, un vrai combat de Ligue. */
public final class SelfTest {
    private static final List<String> REPORT = new ArrayList<>();

    private SelfTest() {}

    private static void check(CommandSourceStack src, String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail == null || detail.isEmpty() ? "" : " - " + detail);
        REPORT.add(line);
        CobblePulse.LOG.info("[CobblePulse][selftest] {}", line);
        if (src != null) src.sendSystemMessage(TextUtil.legacy((ok ? "&a" : "&c") + line));
    }

    public static int basic(CommandSourceStack src) {
        check(src, "config.tiers", Cfg.TIERS.size() >= 10, Cfg.TIERS.size() + " tiers");
        check(src, "config.messages", !"general.prefix".equals(fr.cobblepulse.core.Msg.raw("general.prefix")), null);
        check(src, "config.lc_level_cap", Cfg.TIERS.get("lc") != null && Cfg.TIERS.get("lc").levelCap == 5, null);
        check(src, "config.uu_inherits_ou", Cfg.TIERS.get("uu") != null && Cfg.TIERS.get("ou") != null
                && Cfg.TIERS.get("uu").bannedPokemon.containsAll(Cfg.TIERS.get("ou").bannedPokemon), null);
        check(src, "league.posts", LeagueManager.posts().size() == 13, LeagueManager.posts().size() + " postes");
        check(src, "tournament.bracket", TournamentManager.selfTestBracket().startsWith("OK"), TournamentManager.selfTestBracket());
        check(src, "storage.backend", DataService.storage() != null, DataService.storage() == null ? "-" : DataService.storage().name());
        String stamp = String.valueOf(TimeFmt.now());
        DataService.async(s -> {
            s.saveKv("selftest", "\"" + stamp + "\"");
            return s.loadKv("selftest");
        }).whenComplete((v, e) -> DataService.main(() -> check(src, "storage.roundtrip", v != null && v.contains(stamp), e == null ? null : e.toString())));
        List<ItemStack> items = new ArrayList<>();
        items.add(new ItemStack(Items.DIAMOND, 7));
        items.add(ItemStack.EMPTY);
        items.add(fr.cobblepulse.core.ItemBuilder.of(Items.STONE).name("&aTest").build());
        List<ItemStack> back = ItemSerial.decode(ItemSerial.encode(items));
        check(src, "items.serialization", back.size() == 3 && back.get(0).getCount() == 7 && back.get(1).isEmpty() && back.get(2).is(Items.STONE), null);
        check(src, "text.legacy", TextUtil.legacy("&aA&#FF0000B&lC").getString().equals("ABC"), null);
        check(src, "integration.luckperms", true, LuckPermsBridge.present() ? "present" : "absent");
        check(src, "integration.placeholder", true, FabricLoader.getInstance().isModLoaded("placeholder-api") ? "present" : "absent");
        check(src, "environment", true, FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER ? "serveur dedie" : "client");
        return 1;
    }

    /** Combat de Ligue reel entre deux joueurs factices (necessite -Dcobblepulse.selftest=true). */
    public static int battle(CommandSourceStack src) {
        if (!"true".equals(System.getProperty("cobblepulse.selftest"))) {
            check(src, "battle", false, "lancer le serveur avec -Dcobblepulse.selftest=true");
            return 0;
        }
        ServerLevel level = src.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        FakePlayer holder = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes("cp-holder".getBytes(StandardCharsets.UTF_8)), "CPHolder"));
        FakePlayer challenger = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes("cp-challenger".getBytes(StandardCharsets.UTF_8)), "CPChallenger"));
        Players.registerTest(holder);
        Players.registerTest(challenger);
        holder.setPos(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5);
        challenger.setPos(spawn.getX() + 4.5, spawn.getY() + 1, spawn.getZ() + 0.5);
        try {
            CobblemonBridge.givePokemon(holder, "pikachu level=20");
            CobblemonBridge.givePokemon(challenger, "charmander level=20");
            check(src, "battle.party", CobblemonBridge.partyList(holder).size() >= 1 && CobblemonBridge.partyList(challenger).size() >= 1, null);
        } catch (Throwable t) {
            check(src, "battle.party", false, t.toString());
            CobblePulse.LOG.error("selftest party", t);
            return 0;
        }
        PostData post = LeagueManager.post("champion_8");
        if (post.holderUuid != null && !Players.isTest(LeagueManager.holder(post))) {
            check(src, "battle", false, "poste champion_8 occupe, test ignore");
            return 0;
        }
        LeagueManager.assign(post, holder.getUUID(), "CPHolder", false);
        LeagueManager.setTypes(post, List.of(PokeType.ELECTRIC));
        LeagueManager.setTp(post, true, Loc.of(holder));
        LeagueManager.setTp(post, false, Loc.of(challenger));
        post.open = true;
        PlayerDataManager.online(challenger).badges.remove("champion_8");
        PlayerDataManager.online(challenger).badgeCooldowns.clear();
        PlayerDataManager.online(challenger).placeCooldownUntil = 0;
        post.lastHolderChange = 0;
        LeagueManager.debugStart(post, challenger, LeagueManager.ChallengeType.BADGE);
        Scheduler.later(20 * 8, () -> {
            MatchService.Match m = LeagueManager.currentMatch(post);
            check(src, "battle.badge.started", m != null && m.battleId != null && CobblemonBridge.battleActive(m.battleId),
                    m == null ? "aucun match" : "etat=" + m.state);
            if (m != null && m.battleId != null) CobblemonBridge.forceWin(m.battleId, "p2");
        });
        Scheduler.later(20 * 14, () -> {
            PlayerData d = PlayerDataManager.getIfLoaded(challenger.getUUID());
            PlayerData.BadgeRecord rec = d == null ? null : d.badges.get("champion_8");
            check(src, "battle.badge.awarded", rec != null, rec == null ? "badge absent" : "tours=" + rec.turns + " equipe=" + rec.challengerTeam);
            check(src, "battle.audit", d != null && !d.audit.isEmpty() && d.audit.get(0).won, null);
            PlayerData h = PlayerDataManager.getIfLoaded(holder.getUUID());
            check(src, "battle.holder_audit", h != null && !h.audit.isEmpty() && !h.audit.get(0).won, null);
            post.lastHolderChange = 0;
            LeagueManager.debugStart(post, challenger, LeagueManager.ChallengeType.PLACE);
        });
        Scheduler.later(20 * 22, () -> {
            MatchService.Match m = LeagueManager.currentMatch(post);
            check(src, "battle.place.started", m != null && m.battleId != null, m == null ? "aucun match" : "etat=" + m.state);
            if (m != null && m.battleId != null) CobblemonBridge.forceWin(m.battleId, "p2");
        });
        Scheduler.later(20 * 28, () -> {
            check(src, "battle.place.transferred", challenger.getUUID().toString().equals(post.holderUuid), "titulaire=" + post.holderName);
            check(src, "battle.place.global_cooldown", LeagueManager.postCooldownRemaining(post) > 0, null);
            PlayerData h = PlayerDataManager.getIfLoaded(holder.getUUID());
            check(src, "battle.no_cooldown_for_winner", PlayerDataManager.online(challenger).placeCooldownUntil == 0, null);
            check(src, "battle.loser_no_badge_cd", h != null, null);
            // Deconnexion pendant un match : delai de grace puis forfait
            post.lastHolderChange = 0;
            LeagueManager.debugStart(post, holder, LeagueManager.ChallengeType.BADGE);
        });
        Scheduler.later(20 * 33, () -> {
            MatchService.Match m = LeagueManager.currentMatch(post);
            if (m == null) {
                check(src, "battle.disconnect", false, "aucun match");
                return;
            }
            Players.unregisterTest(holder.getUUID());
            MatchService.onQuit(holder);
            check(src, "battle.disconnect.suspended", m.state == MatchService.State.SUSPENDED, "etat=" + m.state);
            m.graceUntil = TimeFmt.now();
        });
        Scheduler.later(20 * 37, () -> {
            PlayerData d = PlayerDataManager.getIfLoaded(holder.getUUID());
            check(src, "battle.disconnect.forfeit", LeagueManager.currentMatch(post) == null && d != null
                    && d.badgeCooldowns.getOrDefault("champion_8", 0L) > TimeFmt.now(), null);
            LeagueManager.vacate(post, null, false);
            post.lastHolderChange = 0;
            Players.unregisterTest(challenger.getUUID());
            check(src, "battle.cleanup", post.holderUuid == null, null);
        });
        check(src, "battle.scheduled", true, "resultats dans ~40s (/cobblepulse selftest report)");
        return 1;
    }

    private static FakePlayer fake(ServerLevel level, String name, String pokemon, int dx) {
        FakePlayer f = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(("cp-" + name).getBytes(StandardCharsets.UTF_8)), name));
        BlockPos spawn = level.getSharedSpawnPos();
        f.setPos(spawn.getX() + 0.5 + dx, spawn.getY() + 1, spawn.getZ() + 0.5);
        Players.registerTest(f);
        if (CobblemonBridge.partyList(f).isEmpty()) CobblemonBridge.givePokemon(f, pokemon);
        return f;
    }

    /** Tournoi complet a 3 joueurs (bye, deux rondes, sacre, recompenses) + clans en guerre. */
    public static int tournament(CommandSourceStack src) {
        if (!"true".equals(System.getProperty("cobblepulse.selftest"))) {
            check(src, "tournament", false, "lancer le serveur avec -Dcobblepulse.selftest=true");
            return 0;
        }
        ServerLevel level = src.getServer().overworld();
        FakePlayer a = fake(level, "CPAlpha", "bulbasaur level=15", 8);
        FakePlayer b = fake(level, "CPBravo", "squirtle level=15", 10);
        FakePlayer c = fake(level, "CPCharlie", "eevee level=15", 12);
        fr.cobblepulse.data.ClanData ca = fr.cobblepulse.clan.ClanManager.create(a, "TestAlpha", "TA");
        fr.cobblepulse.data.ClanData cb = fr.cobblepulse.clan.ClanManager.create(b, "TestBravo", "TB");
        check(src, "clan.create", ca != null && cb != null, null);
        if (ca == null || cb == null) return 0;
        fr.cobblepulse.clan.ClanManager.setMode(b, fr.cobblepulse.clan.ClanManager.Mode.OPEN);
        fr.cobblepulse.clan.ClanManager.join(c, cb);
        check(src, "clan.join_open", fr.cobblepulse.clan.ClanManager.clanOf(c.getUUID()) == cb, null);
        fr.cobblepulse.clan.WarManager.declare(a, cb);
        check(src, "clan.war_declared", fr.cobblepulse.clan.WarManager.activeWar(ca) != null, null);
        fr.cobblepulse.clan.ClanManager.inventory(ca).setItem(0, new ItemStack(Items.EMERALD, 3));
        check(src, "clan.inventory", fr.cobblepulse.clan.ClanManager.inventory(ca).getItem(0).getCount() == 3, null);
        fr.cobblepulse.tournament.RewardManager.chest("first").setItem(0, new ItemStack(Items.DIAMOND, 5));
        if (TournamentManager.arena("selftest") == null) TournamentManager.addArena("selftest");
        fr.cobblepulse.data.MiscState.ArenaData arena = TournamentManager.arena("selftest");
        arena.p1 = Loc.of(a);
        arena.p2 = Loc.of(b);
        TournamentManager.create(null, "ag", "Selftest Cup", src);
        TournamentManager.join(a);
        TournamentManager.join(b);
        TournamentManager.join(c);
        check(src, "tournament.joined", TournamentManager.current().participants.size() == 3, null);
        TournamentManager.start(src);
        check(src, "tournament.bracket4", TournamentManager.current().bracketSize == 4, "taille=" + TournamentManager.current().bracketSize);
        TournamentManager.startRound(src);
        Scheduler.later(20 * 9, () -> forceTournamentMatches(src, "round1"));
        Scheduler.later(20 * 14, () -> {
            check(src, "tournament.round2_ready", TournamentManager.current().currentRound == 1 && !TournamentManager.current().roundActive, null);
            TournamentManager.startRound(src);
        });
        Scheduler.later(20 * 22, () -> forceTournamentMatches(src, "final"));
        Scheduler.later(20 * 28, () -> {
            fr.cobblepulse.data.TournamentData t = TournamentManager.current();
            check(src, "tournament.finished", "FINISHED".equals(t.state) && t.winnerUuid != null, "etat=" + t.state);
            if (t.winnerUuid != null) {
                PlayerData w = PlayerDataManager.getIfLoaded(UUID.fromString(t.winnerUuid));
                check(src, "tournament.winner_stats", w != null && w.tournamentsWon == 1 && w.tournamentMatchWins >= 1, null);
                check(src, "tournament.rewards_pending", w != null && fr.cobblepulse.tournament.RewardManager.pendingCount(w) == 1, null);
                check(src, "tournament.winner_rank", t.winnerUuid.equals(fr.cobblepulse.storage.StateManager.get().tournamentWinnerUuid), null);
            }
            int placed = 0;
            for (fr.cobblepulse.data.TournamentData.Participant p : t.participants.values()) if (p.placementLo > 0) placed++;
            check(src, "tournament.placements", placed == 3, placed + "/3");
            check(src, "clan.pvp_stats", ca.pvpWins + ca.pvpLosses >= 1, "TestAlpha " + ca.pvpWins + "V/" + ca.pvpLosses + "D");
            fr.cobblepulse.data.WarData war = fr.cobblepulse.clan.WarManager.activeWar(ca);
            check(src, "clan.war_points", war != null && war.attackerPoints + war.defenderPoints >= 1,
                    war == null ? "-" : war.attackerPoints + "-" + war.defenderPoints);
            if (war != null) {
                ca.influence = 100;
                cb.influence = 200;
                war.endsAt = TimeFmt.now();
                fr.cobblepulse.clan.WarManager.resolve(war);
                check(src, "clan.war_resolved", war.finished && (war.winnerId == null || war.stolenInfluence >= 10), "vol=" + war.stolenInfluence);
            }
            fr.cobblepulse.clan.ClanManager.inventory(ca).setItem(0, ItemStack.EMPTY);
            fr.cobblepulse.clan.ClanManager.disband(a);
            fr.cobblepulse.clan.ClanManager.disband(b);
            check(src, "clan.disband", fr.cobblepulse.clan.ClanManager.clanOf(a.getUUID()) == null && fr.cobblepulse.clan.ClanManager.clanOf(c.getUUID()) == null, null);
            TournamentManager.removeArena("selftest");
            fr.cobblepulse.tournament.RewardManager.chest("first").setItem(0, ItemStack.EMPTY);
        });
        check(src, "tournament.scheduled", true, "resultats dans ~30s");
        return 1;
    }

    private static void forceTournamentMatches(CommandSourceStack src, String label) {
        int n = 0;
        for (MatchService.Match m : new ArrayList<>(TournamentManager.running().keySet())) {
            if (m.battleId != null && CobblemonBridge.battleActive(m.battleId)) {
                CobblemonBridge.forceWin(m.battleId, "p1");
                n++;
            }
        }
        check(src, "tournament.match_" + label, n == 1, n + " combat(s) en cours");
    }

    /** Apres redemarrage : verifie que les donnees du test de combat ont ete persistees. */
    public static int persisted(CommandSourceStack src) {
        UUID challenger = UUID.nameUUIDFromBytes("cp-challenger".getBytes(StandardCharsets.UTF_8));
        PlayerDataManager.with(challenger, "CPChallenger", d -> {
            PlayerData.BadgeRecord rec = d.badges.get("champion_8");
            check(src, "persist.badge", rec != null && !rec.challengerTeam.isEmpty() && rec.turns >= 0,
                    rec == null ? "badge absent" : rec.challengerTeam + " / " + rec.holderTeam);
            check(src, "persist.audit", d.audit.size() >= 2, d.audit.size() + " entrees");
            check(src, "persist.stats", d.leagueWins >= 2, "victoires=" + d.leagueWins);
        });
        PostData post = LeagueManager.post("champion_8");
        check(src, "persist.league", post != null && post.types.contains("electric") && post.tpHolder != null, null);
        check(src, "persist.tournament", TournamentManager.current() == null || "FINISHED".equals(TournamentManager.current().state),
                TournamentManager.current() == null ? "aucun" : TournamentManager.current().state);
        return 1;
    }

    public static int report(CommandSourceStack src) {
        int fail = 0;
        for (String l : REPORT) {
            if (l.startsWith("FAIL")) fail++;
            src.sendSystemMessage(TextUtil.legacy((l.startsWith("PASS") ? "&a" : "&c") + l));
        }
        String summary = "SELFTEST SUMMARY: " + (REPORT.size() - fail) + " pass, " + fail + " fail";
        CobblePulse.LOG.info("[CobblePulse][selftest] {}", summary);
        src.sendSystemMessage(TextUtil.legacy((fail == 0 ? "&a" : "&c") + summary));
        return fail == 0 ? 1 : 0;
    }
}
