package fr.cobblepulse.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.clan.ClanCommands;
import fr.cobblepulse.clan.ClanManager;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.league.LeagueCommands;
import fr.cobblepulse.league.LeagueManager;
import fr.cobblepulse.menu.MainMenu;
import fr.cobblepulse.profile.ProfileGui;
import fr.cobblepulse.storage.DataService;
import fr.cobblepulse.storage.PlayerDataManager;
import fr.cobblepulse.tournament.TournamentCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Enregistrement de toutes les commandes du mod. */
public final class RootCommands {
    private RootCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralCommandNode<CommandSourceStack> root = d.register(literal("cobblepulse").requires(Perms.playerReq(Perms.MENU))
                .executes(ctx -> {
                    MainMenu.open(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(literal("reload").requires(Perms.adminReq(Perms.ADMIN_RELOAD)).executes(ctx -> {
                    Cfg.loadAll();
                    Msg.send(ctx.getSource(), "general.reloaded", "tiers", Cfg.TIERS.size());
                    return 1;
                }))
                .then(literal("selftest").requires(Perms.adminReq(Perms.ADMIN))
                        .executes(ctx -> SelfTest.basic(ctx.getSource()))
                        .then(literal("battle").executes(ctx -> SelfTest.battle(ctx.getSource())))
                        .then(literal("tournament").executes(ctx -> SelfTest.tournament(ctx.getSource())))
                        .then(literal("persisted").executes(ctx -> SelfTest.persisted(ctx.getSource())))
                        .then(literal("report").executes(ctx -> SelfTest.report(ctx.getSource()))))
                .then(literal("payinfluence").requires(Perms.adminReq(Perms.ADMIN)).executes(ctx -> {
                    ClanManager.payInfluence();
                    Msg.send(ctx.getSource(), "general.influence_paid");
                    return 1;
                }))
                .then(literal("season").requires(Perms.adminReq(Perms.ADMIN_SEASON))
                        .then(literal("reset").then(argument("cible", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("badges", "league", "clans", "all"), b))
                                .executes(ctx -> {
                                    Msg.send(ctx.getSource(), "general.season_confirm", "target", StringArgumentType.getString(ctx, "cible"));
                                    return 1;
                                })
                                .then(literal("confirm").executes(ctx -> seasonReset(ctx.getSource(), StringArgumentType.getString(ctx, "cible"))))))));
        d.register(literal("cp").requires(Perms.playerReq(Perms.MENU)).executes(ctx -> {
            MainMenu.open(ctx.getSource().getPlayerOrException());
            return 1;
        }).redirect(root));

        d.register(literal("profile").requires(Perms.playerReq(Perms.PROFILE_VIEW))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ProfileGui.open(p, p.getUUID(), p.getGameProfile().getName());
                    return 1;
                })
                .then(argument("joueur", StringArgumentType.word()).suggests(Cmd.ONLINE).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    return Cmd.profile(ctx.getSource(), StringArgumentType.getString(ctx, "joueur"), gp -> ProfileGui.open(p, gp.getId(), gp.getName())) ? 1 : 0;
                })));

        LeagueCommands.register(d);
        ClanCommands.register(d);
        TournamentCommands.register(d);
    }

    private static int seasonReset(CommandSourceStack src, String target) {
        boolean all = target.equals("all");
        if (all || target.equals("league")) LeagueManager.resetAll();
        if (all || target.equals("clans")) ClanManager.resetInfluence();
        if (all || target.equals("badges")) {
            for (var d : PlayerDataManager.cached()) {
                d.badges.clear();
                d.badgeCooldowns.clear();
                d.placeCooldownUntil = 0;
                PlayerDataManager.save(d);
            }
            DataService.async(s -> s.listPlayers()).thenAccept(list -> DataService.main(() -> {
                for (UUID u : list) {
                    PlayerDataManager.modify(u, null, d -> {
                        d.badges.clear();
                        d.badgeCooldowns.clear();
                        d.placeCooldownUntil = 0;
                    });
                }
                CobblePulse.LOG.info("[CobblePulse] Remise a zero des badges : {} dossiers", list.size());
            }));
        }
        Msg.broadcast("general.season_reset", "target", target);
        return 1;
    }
}
