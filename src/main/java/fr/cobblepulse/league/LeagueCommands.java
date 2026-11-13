package fr.cobblepulse.league;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobblepulse.command.Cmd;
import fr.cobblepulse.core.Loc;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.LeagueData.PostData;
import fr.cobblepulse.gui.Guis;
import fr.cobblepulse.storage.PlayerDataManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Commandes /league. */
public final class LeagueCommands {
    private LeagueCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("league").requires(Perms.playerReq(Perms.LEAGUE_VIEW))
                .executes(ctx -> {
                    LeagueGuis.openLeague(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(literal("badges").requires(Perms.playerReq(Perms.LEAGUE_BADGES)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    LeagueGuis.openBadgeCase(p, p.getUUID(), p.getGameProfile().getName(), PlayerDataManager.online(p), false);
                    return 1;
                }))
                .then(literal("inspect").requires(Perms.staffReq(Perms.STAFF_INSPECT))
                        .then(argument("joueur", StringArgumentType.word()).suggests(Cmd.ONLINE).executes(LeagueCommands::inspect)))
                .then(literal("challenge")
                        .then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS)
                                .then(literal("badge").executes(ctx -> challenge(ctx, LeagueManager.ChallengeType.BADGE)))
                                .then(literal("place").executes(ctx -> challenge(ctx, LeagueManager.ChallengeType.PLACE)))))
                .then(literal("queue").then(literal("leave").executes(ctx -> {
                    LeagueManager.leaveQueue(ctx.getSource().getPlayerOrException());
                    return 1;
                })))
                .then(literal("accept").executes(ctx -> {
                    LeagueManager.accept(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(literal("refuse").executes(ctx -> {
                    LeagueManager.refuse(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(literal("arena").executes(ctx -> {
                            LeagueGuis.openHolderMenu(ctx.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(literal("settp").requires(Perms.playerReq(Perms.ARENA_SETTP))
                                .then(literal("holder").executes(ctx -> holderTp(ctx, true)))
                                .then(literal("challenger").executes(ctx -> holderTp(ctx, false))))
                        .then(literal("toggle").requires(Perms.playerReq(Perms.ARENA_TOGGLE)).executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            PostData post = LeagueManager.postOf(p.getUUID());
                            if (post == null) {
                                Msg.send(p, "league.err_not_holder");
                                return 0;
                            }
                            LeagueManager.setOpen(post, !post.open);
                            Msg.send(p, post.open ? "league.arena_now_open" : "league.arena_now_closed");
                            return 1;
                        })))
                .then(literal("resign").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PostData post = LeagueManager.postOf(p.getUUID());
                    if (post == null) {
                        Msg.send(p, "league.err_not_holder");
                        return 0;
                    }
                    Guis.confirm(p, "&4Démissionner ?", "Quitter le poste " + LeagueManager.plainName(post),
                            () -> LeagueManager.vacate(post, "league.resigned", true), null);
                    return 1;
                }))
                .then(literal("cooldown").requires(Perms.staffReq(Perms.STAFF_COOLDOWN_RESET))
                        .then(literal("reset").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.ONLINE)
                                .executes(ctx -> resetCooldown(ctx, "all"))
                                .then(argument("cible", StringArgumentType.word()).suggests((c, b) -> {
                                    List<String> s = new ArrayList<>(List.of("all", "place"));
                                    LeagueManager.posts().forEach(p -> s.add(p.id));
                                    return SharedSuggestionProvider.suggest(s, b);
                                }).executes(ctx -> resetCooldown(ctx, StringArgumentType.getString(ctx, "cible")))))))
                .then(literal("admin").requires(Perms.adminReq(Perms.ADMIN_LEAGUE))
                        .executes(ctx -> {
                            LeagueGuis.openAdmin(ctx.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(literal("list").executes(LeagueCommands::list))
                        .then(literal("set").then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS)
                                .then(argument("joueur", StringArgumentType.word()).suggests(Cmd.ONLINE).executes(ctx -> {
                                    PostData post = post(ctx);
                                    if (post == null) return 0;
                                    return Cmd.profile(ctx.getSource(), StringArgumentType.getString(ctx, "joueur"), gp -> {
                                        LeagueManager.assign(post, gp.getId(), gp.getName(), true);
                                        Msg.send(ctx.getSource(), "league.admin_assigned", "player", gp.getName(), "post", LeagueManager.plainName(post));
                                    }) ? 1 : 0;
                                }))))
                        .then(literal("vacate").then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS).executes(ctx -> {
                            PostData post = post(ctx);
                            if (post == null) return 0;
                            LeagueManager.vacate(post, "league.vacated_admin", true);
                            Msg.send(ctx.getSource(), "league.admin_vacated", "post", LeagueManager.plainName(post));
                            return 1;
                        })))
                        .then(literal("types").then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS)
                                .then(argument("type1", StringArgumentType.word()).suggests(Cmd.TYPES).executes(ctx -> types(ctx, false))
                                        .then(argument("type2", StringArgumentType.word()).suggests(Cmd.TYPES).executes(ctx -> types(ctx, true))))))
                        .then(literal("settp").then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS)
                                .then(literal("holder").executes(ctx -> adminTp(ctx, true)))
                                .then(literal("challenger").executes(ctx -> adminTp(ctx, false)))))
                        .then(literal("open").then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS).executes(ctx -> setOpen(ctx, true))))
                        .then(literal("close").then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS).executes(ctx -> setOpen(ctx, false))))
                        .then(literal("resetcooldown").then(argument("poste", StringArgumentType.word()).suggests(Cmd.POSTS).executes(ctx -> {
                            PostData post = post(ctx);
                            if (post == null) return 0;
                            LeagueManager.resetPostCooldown(post);
                            Msg.send(ctx.getSource(), "league.admin_post_cooldown_reset", "post", LeagueManager.plainName(post));
                            return 1;
                        })))));
    }

    private static PostData post(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "poste");
        PostData p = LeagueManager.post(id);
        if (p == null) Msg.send(ctx.getSource(), "league.err_unknown_post", "post", id);
        return p;
    }

    private static int challenge(CommandContext<CommandSourceStack> ctx, LeagueManager.ChallengeType type) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        PostData post = post(ctx);
        if (post == null) return 0;
        LeagueManager.challenge(p, post, type);
        return 1;
    }

    private static int holderTp(CommandContext<CommandSourceStack> ctx, boolean holderPoint) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        PostData post = LeagueManager.postOf(p.getUUID());
        if (post == null) {
            Msg.send(p, "league.err_not_holder");
            return 0;
        }
        LeagueManager.setTp(post, holderPoint, Loc.of(p));
        Msg.send(p, "league.tp_set", "point", holderPoint ? "Titulaire" : "Challenger");
        return 1;
    }

    private static int adminTp(CommandContext<CommandSourceStack> ctx, boolean holderPoint) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        PostData post = post(ctx);
        if (post == null) return 0;
        LeagueManager.setTp(post, holderPoint, Loc.of(p));
        Msg.send(p, "league.tp_set", "point", holderPoint ? "Titulaire" : "Challenger");
        return 1;
    }

    private static int setOpen(CommandContext<CommandSourceStack> ctx, boolean open) {
        PostData post = post(ctx);
        if (post == null) return 0;
        LeagueManager.setOpen(post, open);
        Msg.send(ctx.getSource(), open ? "league.arena_now_open" : "league.arena_now_closed");
        return 1;
    }

    private static int types(CommandContext<CommandSourceStack> ctx, boolean two) {
        PostData post = post(ctx);
        if (post == null) return 0;
        List<PokeType> types = new ArrayList<>();
        PokeType t1 = PokeType.of(StringArgumentType.getString(ctx, "type1"));
        PokeType t2 = two ? PokeType.of(StringArgumentType.getString(ctx, "type2")) : null;
        if (t1 == null || (two && t2 == null)) {
            Msg.send(ctx.getSource(), "league.err_unknown_type");
            return 0;
        }
        types.add(t1);
        if (t2 != null) types.add(t2);
        int needed = LeagueManager.kind(post).typeCount;
        if (types.size() != needed) {
            Msg.send(ctx.getSource(), "league.err_type_count", "count", needed);
            return 0;
        }
        LeagueManager.setTypes(post, types);
        Msg.send(ctx.getSource(), "league.types_set", "post", LeagueManager.plainName(post));
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer viewer = ctx.getSource().getPlayerOrException();
        return Cmd.profile(ctx.getSource(), StringArgumentType.getString(ctx, "joueur"),
                gp -> PlayerDataManager.with(gp.getId(), gp.getName(), d -> LeagueGuis.openBadgeCase(viewer, gp.getId(), gp.getName(), d, true))) ? 1 : 0;
    }

    private static int resetCooldown(CommandContext<CommandSourceStack> ctx, String target) {
        return Cmd.profile(ctx.getSource(), StringArgumentType.getString(ctx, "joueur"), gp ->
                PlayerDataManager.modify(gp.getId(), gp.getName(), d -> {
                    if (target.equals("all")) {
                        d.badgeCooldowns.clear();
                        d.placeCooldownUntil = 0;
                    } else if (target.equals("place")) {
                        d.placeCooldownUntil = 0;
                    } else {
                        d.badgeCooldowns.remove(target);
                    }
                    Msg.send(ctx.getSource(), "league.cooldown_reset", "player", gp.getName());
                })) ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        for (PostData p : LeagueManager.posts()) {
            long cd = LeagueManager.postCooldownRemaining(p);
            src.sendSystemMessage(TextUtil.legacy("&7" + p.id + " &8| " + LeagueManager.displayName(p) + " &8| &f"
                    + (p.holderName == null ? "Vacant" : p.holderName) + " &8| " + (p.open ? "&aouverte" : "&cfermée")
                    + " &8| TP " + (p.tpHolder != null && p.tpChallenger != null ? "&aok" : "&cmanquant")
                    + (cd > 0 ? " &8| &ccooldown " + TimeFmt.duration(cd) : "")));
        }
        return 1;
    }
}
