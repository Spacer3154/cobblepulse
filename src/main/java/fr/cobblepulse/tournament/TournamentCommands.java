package fr.cobblepulse.tournament;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.Loc;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.data.MiscState;
import fr.cobblepulse.data.TournamentData;
import fr.cobblepulse.storage.StateManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Commandes /tournoi (alias /tournament). */
public final class TournamentCommands {
    private TournamentCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(build("tournoi"));
        d.register(build("tournament"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root) {
        return literal(root).requires(Perms.playerReq(Perms.TOURNAMENT_VIEW))
                .executes(ctx -> {
                    TournamentGuis.open(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(literal("join").requires(Perms.playerReq(Perms.TOURNAMENT_JOIN)).executes(ctx -> {
                    TournamentManager.join(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(literal("leave").executes(ctx -> {
                    TournamentManager.leave(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(literal("bracket").executes(ctx -> {
                    TournamentGuis.openBracket(ctx.getSource().getPlayerOrException(), -1);
                    return 1;
                }))
                .then(literal("claim").requires(Perms.playerReq(Perms.TOURNAMENT_CLAIM)).executes(ctx -> {
                    RewardManager.claim(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(literal("create").requires(Perms.staffReq(Perms.T_CREATE))
                        .then(argument("tier", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Cfg.TIERS.keySet(), b))
                                .executes(ctx -> create(ctx, null))
                                .then(argument("nom", StringArgumentType.greedyString())
                                        .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "nom"))))))
                .then(literal("start").requires(Perms.staffReq(Perms.T_MANAGE)).executes(ctx -> TournamentManager.start(ctx.getSource()) ? 1 : 0))
                .then(literal("nextround").requires(Perms.staffReq(Perms.T_MANAGE)).executes(ctx -> TournamentManager.startRound(ctx.getSource()) ? 1 : 0))
                .then(literal("cancel").requires(Perms.staffReq(Perms.T_MANAGE)).executes(ctx -> {
                    TournamentManager.cancel(ctx.getSource());
                    return 1;
                }))
                .then(literal("status").executes(TournamentCommands::status))
                .then(literal("disqualify").requires(Perms.staffReq(Perms.T_DISQUALIFY))
                        .then(argument("joueur", StringArgumentType.word()).suggests((c, b) -> {
                            List<String> names = new ArrayList<>();
                            TournamentData t = TournamentManager.current();
                            if (t != null) t.participants.values().forEach(p -> names.add(p.name));
                            return SharedSuggestionProvider.suggest(names, b);
                        }).executes(ctx -> dq(ctx, null))
                                .then(argument("raison", StringArgumentType.greedyString()).executes(ctx -> dq(ctx, StringArgumentType.getString(ctx, "raison"))))))
                .then(literal("rewards").requires(Perms.staffReq(Perms.T_REWARDS))
                        .then(literal("edit").then(argument("palier", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(RewardManager.TIERS, b))
                                .executes(ctx -> {
                                    String tier = StringArgumentType.getString(ctx, "palier");
                                    if (!RewardManager.TIERS.contains(tier)) {
                                        Msg.send(ctx.getSource(), "tournament.err_unknown_reward_tier", "tiers", String.join(", ", RewardManager.TIERS));
                                        return 0;
                                    }
                                    RewardManager.openEditor(ctx.getSource().getPlayerOrException(), tier);
                                    return 1;
                                }))))
                .then(literal("arena").requires(Perms.staffReq(Perms.T_ARENA))
                        .then(literal("add").then(argument("nom", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "nom");
                            if (TournamentManager.arena(name) != null) {
                                Msg.send(ctx.getSource(), "tournament.err_arena_exists");
                                return 0;
                            }
                            TournamentManager.addArena(name);
                            Msg.send(ctx.getSource(), "tournament.arena_added", "arena", name);
                            return 1;
                        })))
                        .then(literal("set").then(argument("nom", StringArgumentType.word()).suggests(TournamentCommands::arenaNames)
                                .then(literal("1").executes(ctx -> setArena(ctx, true)))
                                .then(literal("2").executes(ctx -> setArena(ctx, false)))))
                        .then(literal("remove").then(argument("nom", StringArgumentType.word()).suggests(TournamentCommands::arenaNames).executes(ctx -> {
                            boolean ok = TournamentManager.removeArena(StringArgumentType.getString(ctx, "nom"));
                            Msg.send(ctx.getSource(), ok ? "tournament.arena_removed" : "tournament.err_unknown_arena", "arena", StringArgumentType.getString(ctx, "nom"));
                            return ok ? 1 : 0;
                        })))
                        .then(literal("list").executes(ctx -> {
                            for (MiscState.ArenaData a : TournamentManager.arenas()) {
                                ctx.getSource().sendSystemMessage(TextUtil.legacy("&e" + a.name + " &7J1: &f" + (a.p1 == null ? "-" : a.p1.pretty())
                                        + " &7J2: &f" + (a.p2 == null ? "-" : a.p2.pretty())));
                            }
                            if (TournamentManager.arenas().isEmpty()) Msg.send(ctx.getSource(), "tournament.err_no_arena");
                            return 1;
                        })));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> arenaNames(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        List<String> names = new ArrayList<>();
        for (MiscState.ArenaData a : TournamentManager.arenas()) names.add(a.name);
        return SharedSuggestionProvider.suggest(names, b);
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer p = ctx.getSource().getPlayer();
        return TournamentManager.create(p, StringArgumentType.getString(ctx, "tier"), name, ctx.getSource()) ? 1 : 0;
    }

    private static int setArena(CommandContext<CommandSourceStack> ctx, boolean first) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        MiscState.ArenaData a = TournamentManager.arena(StringArgumentType.getString(ctx, "nom"));
        if (a == null) {
            Msg.send(p, "tournament.err_unknown_arena", "arena", StringArgumentType.getString(ctx, "nom"));
            return 0;
        }
        if (first) a.p1 = Loc.of(p);
        else a.p2 = Loc.of(p);
        StateManager.save();
        Msg.send(p, "tournament.arena_point_set", "arena", a.name, "point", first ? "1" : "2");
        return 1;
    }

    private static int dq(CommandContext<CommandSourceStack> ctx, String reason) {
        TournamentData t = TournamentManager.current();
        String name = StringArgumentType.getString(ctx, "joueur");
        if (t == null) {
            Msg.send(ctx.getSource(), "tournament.err_no_tournament");
            return 0;
        }
        for (TournamentData.Participant p : t.participants.values()) {
            if (p.name.equalsIgnoreCase(name)) {
                String r = reason != null ? reason : Msg.format("tournament.dq_default_reason", "staff", ctx.getSource().getTextName());
                TournamentManager.disqualify(ctx.getSource(), p.uuid, r);
                return 1;
            }
        }
        Msg.send(ctx.getSource(), "tournament.err_not_participant");
        return 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        TournamentData t = TournamentManager.current();
        CommandSourceStack src = ctx.getSource();
        if (t == null) {
            Msg.send(src, "tournament.err_no_tournament");
            return 0;
        }
        src.sendSystemMessage(TextUtil.legacy("&6" + t.name + " &7[" + t.tier + "] &8- &f" + t.state + " &8- &f" + t.participants.size() + " participants"));
        TournamentData.Round r = TournamentManager.round();
        if (r != null) {
            src.sendSystemMessage(TextUtil.legacy("&7Ronde : &f" + TournamentManager.roundName(r.slots) + (t.roundActive ? " &a(active)" : " &e(en attente)")));
            for (TournamentData.Match m : r.matches) {
                src.sendSystemMessage(TextUtil.legacy("&8#" + (m.index + 1) + " &f" + m.n1 + " &7vs &f" + (m.bye ? "(bye)" : m.n2) + " &8- " + m.status
                        + (m.winner != null ? " &a-> " + (m.winner.equals(m.p1) ? m.n1 : m.n2) : "")));
            }
        }
        return 1;
    }
}
