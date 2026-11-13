package fr.cobblepulse;

import fr.cobblepulse.battle.CobblemonBridge;
import fr.cobblepulse.battle.MatchService;
import fr.cobblepulse.clan.ClanManager;
import fr.cobblepulse.clan.WarManager;
import fr.cobblepulse.command.RootCommands;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.Scheduler;
import fr.cobblepulse.integration.PlaceholderBridge;
import fr.cobblepulse.league.LeagueManager;
import fr.cobblepulse.storage.DataService;
import fr.cobblepulse.storage.PlayerDataManager;
import fr.cobblepulse.storage.StateManager;
import fr.cobblepulse.tournament.RewardManager;
import fr.cobblepulse.tournament.TournamentManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Point d'entree de CobblePulse : Clans & Guerres d'Influence, Ligue PvP et Tournois pour Cobblemon. */
public final class CobblePulse implements ModInitializer {
    public static final String MOD_ID = "cobblepulse";
    public static final Logger LOG = LoggerFactory.getLogger("CobblePulse");
    private static MinecraftServer server;
    private static boolean ready;

    public static MinecraftServer server() {
        return server;
    }

    public static boolean ready() {
        return ready;
    }

    @Override
    public void onInitialize() {
        Cfg.loadAll();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> RootCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STARTED.register(CobblePulse::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(CobblePulse::onStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
        ServerTickEvents.END_SERVER_TICK.register(Scheduler::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> onQuit(handler.getPlayer()));
        CobblemonBridge.register();
        PlaceholderBridge.init();
        LOG.info("[CobblePulse] Initialise");
    }

    private static void onStarted(MinecraftServer s) {
        server = s;
        long t0 = System.currentTimeMillis();
        DataService.start();
        StateManager.load();
        PlayerDataManager.start();
        MatchService.init();
        LeagueManager.load();
        ClanManager.load();
        WarManager.init();
        TournamentManager.load();
        RewardManager.preload();
        ready = true;
        LOG.info("[CobblePulse] Pret en {} ms", System.currentTimeMillis() - t0);
    }

    private static void onStopping(MinecraftServer s) {
        ready = false;
        try {
            DataService.shutdown();
        } finally {
            Scheduler.clear();
            MatchService.clear();
            CobblemonBridge.clearListeners();
            PlayerDataManager.clearAll();
        }
    }

    private static void onJoin(ServerPlayer p) {
        if (!ready) return;
        PlayerDataManager.onJoin(p);
        MatchService.onJoin(p);
        LeagueManager.onJoin(p);
    }

    private static void onQuit(ServerPlayer p) {
        if (!ready) return;
        MatchService.onQuit(p);
        LeagueManager.onQuit(p);
        PlayerDataManager.onQuit(p);
    }
}
