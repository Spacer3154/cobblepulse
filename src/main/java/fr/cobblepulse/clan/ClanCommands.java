package fr.cobblepulse.clan;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.cobblepulse.command.Cmd;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.WarData;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.gui.Guis;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Commandes /clan. */
public final class ClanCommands {
    private static final SuggestionProvider<CommandSourceStack> CLANS = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        for (ClanData c : ClanManager.all()) names.add(c.name);
        return SharedSuggestionProvider.suggest(names, b);
    };

    private static final SuggestionProvider<CommandSourceStack> MEMBERS = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        if (ctx.getSource().getPlayer() != null) {
            ClanData c = ClanManager.clanOf(ctx.getSource().getPlayer().getUUID());
            if (c != null) c.members.values().forEach(m -> names.add(m.name));
        }
        return SharedSuggestionProvider.suggest(names, b);
    };

    private ClanCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("clan").requires(Perms.playerReq(Perms.CLAN_USE))
                .executes(ctx -> {
                    ClanGuis.open(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(literal("create").requires(Perms.playerReq(Perms.CLAN_CREATE))
                        .then(argument("nom", StringArgumentType.word()).then(argument("tag", StringArgumentType.word()).executes(ctx -> {
                            ClanManager.create(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "nom"),
                                    StringArgumentType.getString(ctx, "tag"));
                            return 1;
                        }))))
                .then(literal("join").then(argument("nom", StringArgumentType.word()).suggests(CLANS).executes(ctx -> {
                    ClanData c = clan(ctx);
                    if (c != null) ClanManager.join(ctx.getSource().getPlayerOrException(), c);
                    return 1;
                })))
                .then(literal("request").then(argument("nom", StringArgumentType.word()).suggests(CLANS).executes(ctx -> {
                    ClanData c = clan(ctx);
                    if (c != null) ClanManager.request(ctx.getSource().getPlayerOrException(), c);
                    return 1;
                })))
                .then(literal("accept").then(argument("nom", StringArgumentType.word()).suggests(CLANS).executes(ctx -> {
                    ClanData c = clan(ctx);
                    if (c != null) ClanManager.acceptInvite(ctx.getSource().getPlayerOrException(), c);
                    return 1;
                })))
                .then(literal("deny").then(argument("nom", StringArgumentType.word()).suggests(CLANS).executes(ctx -> {
                    ClanData c = clan(ctx);
                    if (c != null) ClanManager.denyInvite(ctx.getSource().getPlayerOrException(), c);
                    return 1;
                })))
                .then(literal("invite").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.ONLINE).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ServerPlayer t = p.server.getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "joueur"));
                    if (t == null) {
                        Msg.send(p, "general.player_not_found", "player", StringArgumentType.getString(ctx, "joueur"));
                        return 0;
                    }
                    ClanManager.invite(p, t);
                    return 1;
                })))
                .then(literal("requests").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ClanData c = ClanManager.clanOf(p.getUUID());
                    if (c == null) {
                        Msg.send(p, "clan.err_no_clan");
                        return 0;
                    }
                    if (c.requests.isEmpty()) Msg.send(p, "clan.no_requests");
                    for (Map.Entry<String, ClanData.JoinRequest> e : c.requests.entrySet()) {
                        p.sendSystemMessage(TextUtil.legacy("&7- &f" + e.getValue().name + " ")
                                .append(TextUtil.button("&a[✔]", "/clan requests accept " + e.getValue().name, null))
                                .append(TextUtil.legacy(" "))
                                .append(TextUtil.button("&c[✘]", "/clan requests deny " + e.getValue().name, null)));
                    }
                    return 1;
                }).then(literal("accept").then(argument("joueur", StringArgumentType.word()).executes(ctx -> handleRequest(ctx, true))))
                        .then(literal("deny").then(argument("joueur", StringArgumentType.word()).executes(ctx -> handleRequest(ctx, false)))))
                .then(literal("leave").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Guis.confirm(p, "&cQuitter le clan ?", "Quitter votre clan", () -> ClanManager.leave(p), null);
                    return 1;
                }))
                .then(literal("kick").then(argument("joueur", StringArgumentType.word()).suggests(MEMBERS).executes(ctx -> member(ctx, "kick"))))
                .then(literal("promote").then(argument("joueur", StringArgumentType.word()).suggests(MEMBERS).executes(ctx -> member(ctx, "promote"))))
                .then(literal("demote").then(argument("joueur", StringArgumentType.word()).suggests(MEMBERS).executes(ctx -> member(ctx, "demote"))))
                .then(literal("transfer").then(argument("joueur", StringArgumentType.word()).suggests(MEMBERS).executes(ctx -> member(ctx, "transfer"))))
                .then(literal("disband").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Guis.confirm(p, "&4Dissoudre le clan ?", "Action définitive", () -> ClanManager.disband(p), null);
                    return 1;
                }))
                .then(literal("mode").then(argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("open", "invite", "closed"), b)).executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String m = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
                            ClanManager.Mode mode = switch (m) {
                                case "open", "ouvert" -> ClanManager.Mode.OPEN;
                                case "closed", "ferme", "fermé" -> ClanManager.Mode.CLOSED;
                                default -> ClanManager.Mode.INVITE_ONLY;
                            };
                            ClanManager.setMode(p, mode);
                            return 1;
                        })))
                .then(literal("inventory").requires(Perms.playerReq(Perms.CLAN_INVENTORY)).executes(ctx -> {
                    ClanGuis.openInventory(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(literal("tagcolor").then(argument("couleur", StringArgumentType.greedyString()).executes(ctx -> {
                    ClanManager.setTagColor(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "couleur").trim());
                    return 1;
                })))
                .then(literal("list").executes(ctx -> {
                    ClanGuis.openList(ctx.getSource().getPlayerOrException(), 0);
                    return 1;
                }))
                .then(literal("top").executes(ctx -> {
                    if (ctx.getSource().isPlayer()) {
                        ClanGuis.openLeaderboard(ctx.getSource().getPlayerOrException());
                    } else {
                        int i = 1;
                        for (ClanData c : ClanManager.leaderboard()) {
                            if (i > 10) break;
                            ctx.getSource().sendSystemMessage(TextUtil.legacy("&e#" + i++ + " &f" + c.name + " &7- influence &d" + c.influence
                                    + " &7- guerres " + c.warWins + "V/" + c.warLosses + "D"));
                        }
                    }
                    return 1;
                }))
                .then(literal("info").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ClanData c = ClanManager.clanOf(p.getUUID());
                    if (c == null) {
                        Msg.send(p, "clan.err_no_clan");
                        return 0;
                    }
                    info(ctx.getSource(), c);
                    return 1;
                }).then(argument("nom", StringArgumentType.word()).suggests(CLANS).executes(ctx -> {
                    ClanData c = clan(ctx);
                    if (c != null) info(ctx.getSource(), c);
                    return 1;
                })))
                .then(literal("war")
                        .then(literal("declare").then(argument("nom", StringArgumentType.word()).suggests(CLANS).executes(ctx -> {
                            ClanData c = clan(ctx);
                            if (c != null) WarManager.declare(ctx.getSource().getPlayerOrException(), c);
                            return 1;
                        })))
                        .then(literal("status").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ClanData c = ClanManager.clanOf(p.getUUID());
                            WarData w = c == null ? null : WarManager.activeWar(c);
                            if (w == null) {
                                Msg.send(p, "war.none");
                                return 0;
                            }
                            String enemy = w.attackerId.equals(c.id) ? w.defenderName : w.attackerName;
                            Msg.send(p, "war.status", "clan", enemy, "score", WarManager.score(w, c), "time", TimeFmt.duration(w.endsAt - TimeFmt.now()));
                            return 1;
                        }))));
    }

    private static ClanData clan(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "nom");
        ClanData c = ClanManager.byName(name);
        if (c == null) Msg.send(ctx.getSource(), "clan.err_unknown", "clan", name);
        return c;
    }

    private static int handleRequest(CommandContext<CommandSourceStack> ctx, boolean accept) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        ClanData c = ClanManager.clanOf(p.getUUID());
        if (c == null) {
            Msg.send(p, "clan.err_no_clan");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "joueur");
        for (Map.Entry<String, ClanData.JoinRequest> e : c.requests.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) {
                UUID u = UUID.fromString(e.getKey());
                if (accept) ClanManager.acceptRequest(p, u);
                else ClanManager.denyRequest(p, u);
                return 1;
            }
        }
        Msg.send(p, "clan.err_no_request");
        return 0;
    }

    private static int member(CommandContext<CommandSourceStack> ctx, String action) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        ClanData c = ClanManager.clanOf(p.getUUID());
        if (c == null) {
            Msg.send(p, "clan.err_no_clan");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "joueur");
        UUID target = null;
        for (Map.Entry<String, ClanData.ClanMember> e : c.members.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) target = UUID.fromString(e.getKey());
        }
        if (target == null) {
            Msg.send(p, "clan.err_not_member");
            return 0;
        }
        UUID t = target;
        switch (action) {
            case "kick" -> ClanManager.kick(p, t);
            case "promote" -> ClanManager.setRole(p, t, ClanManager.Role.OFFICER);
            case "demote" -> ClanManager.setRole(p, t, ClanManager.Role.MEMBER);
            case "transfer" -> Guis.confirm(p, "&6Transférer la direction ?", "Nouveau chef : " + name, () -> ClanManager.transfer(p, t), null);
            default -> {
            }
        }
        return 1;
    }

    private static void info(CommandSourceStack src, ClanData c) {
        ClanData.ClanMember leader = c.members.get(c.leader);
        src.sendSystemMessage(TextUtil.legacy("&8&m          &r &f&l" + c.name + " " + ClanManager.tagString(c, true) + "&8&m          "));
        src.sendSystemMessage(TextUtil.legacy("&7Chef : &f" + (leader == null ? "?" : leader.name) + " &7- Membres : &f" + c.members.size()
                + " &7- Recrutement : " + ClanManager.modeLabel(ClanManager.mode(c))));
        src.sendSystemMessage(TextUtil.legacy("&7Influence : &d" + c.influence + " &8(#" + ClanManager.rank(c) + ") &7- PvP : &a" + c.pvpWins
                + "&7/&c" + c.pvpLosses + " &7- Guerres : &a" + c.warWins + "&7/&c" + c.warLosses + "&7/&e" + c.warDraws));
    }
}
