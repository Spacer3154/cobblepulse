package fr.cobblepulse.integration;

import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import fr.cobblepulse.clan.ClanManager;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.LeagueData;
import fr.cobblepulse.league.LeagueManager;
import fr.cobblepulse.storage.PlayerDataManager;
import fr.cobblepulse.storage.StateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Function;

/** Placeholders %cobblepulse:...% (Text Placeholder API). Ne jamais referencer si le mod est absent. */
final class PlaceholderHook {
    private PlaceholderHook() {}

    private static void reg(String id, Function<ServerPlayer, Component> fn) {
        Placeholders.register(ResourceLocation.fromNamespaceAndPath("cobblepulse", id), (ctx, arg) -> {
            ServerPlayer p = ctx.player();
            if (p == null) return PlaceholderResult.invalid("No player!");
            return PlaceholderResult.value(fn.apply(p));
        });
    }

    static void register() {
        reg("clan_tag", ClanManager::formattedTag);
        reg("clan_name", p -> {
            ClanData c = ClanManager.clanOf(p.getUUID());
            return Component.literal(c == null ? "" : c.name);
        });
        reg("clan_role", p -> {
            ClanData c = ClanManager.clanOf(p.getUUID());
            ClanManager.Role r = c == null ? null : ClanManager.role(c, p.getUUID());
            return Component.literal(r == null ? "" : r.name());
        });
        reg("clan_influence", p -> {
            ClanData c = ClanManager.clanOf(p.getUUID());
            return Component.literal(c == null ? "0" : String.valueOf(c.influence));
        });
        reg("league_grade", p -> {
            LeagueData.PostData post = LeagueManager.postOf(p.getUUID());
            return post == null ? Component.empty() : TextUtil.legacy(LeagueManager.prefix(post));
        });
        reg("chat_prefix", p -> {
            net.minecraft.network.chat.MutableComponent out = Component.empty();
            LeagueData.PostData post = LeagueManager.postOf(p.getUUID());
            if (post != null) out.append(TextUtil.legacy(LeagueManager.prefix(post)));
            if (p.getUUID().toString().equals(StateManager.get().tournamentWinnerUuid)) {
                out.append(TextUtil.legacy(Cfg.tournament.str("winner_prefix", "&e[Vainqueur Tournoi] ")));
            }
            out.append(ClanManager.formattedTag(p));
            return out;
        });
        reg("badges", p -> Component.literal(String.valueOf(PlayerDataManager.online(p).badges.size())));
        reg("tournament_winner", p -> p.getUUID().toString().equals(StateManager.get().tournamentWinnerUuid)
                ? TextUtil.legacy(Cfg.tournament.str("winner_prefix", "&e[Vainqueur Tournoi] ")) : Component.empty());
    }
}
