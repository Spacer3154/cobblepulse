package fr.cobblepulse.core;

import fr.cobblepulse.integration.LuckPermsBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * Verification des permissions. LuckPerms est utilise s'il est present ;
 * sinon (ou si le noeud n'est pas defini) on retombe sur des valeurs par defaut :
 * noeuds joueurs autorises, noeuds staff reserves aux operateurs.
 */
public final class Perms {
    // Joueurs
    public static final String MENU = "cobblepulse.menu.open";
    public static final String LEAGUE_VIEW = "cobblepulse.league.view";
    public static final String LEAGUE_BADGES = "cobblepulse.league.badges";
    public static final String CHALLENGE_BADGE = "cobblepulse.league.challenge.badge";
    public static final String CHALLENGE_PLACE = "cobblepulse.league.challenge.place";
    public static final String CLAN_CREATE = "cobblepulse.clan.create";
    public static final String CLAN_USE = "cobblepulse.clan.use";
    public static final String CLAN_INVENTORY = "cobblepulse.clan.inventory";
    public static final String TOURNAMENT_VIEW = "cobblepulse.tournament.view";
    public static final String TOURNAMENT_JOIN = "cobblepulse.tournament.join";
    public static final String TOURNAMENT_CLAIM = "cobblepulse.tournament.claim";
    public static final String PROFILE_VIEW = "cobblepulse.profile.view";
    // VIP
    public static final String CLAN_INV_EXPAND = "cobblepulse.clan.inventory.expand";
    public static final String CLAN_CHAT_COLOR = "cobblepulse.clan.chat.color";
    public static final String ARENA_EFFECT = "cobblepulse.cosmetic.arena_effect";
    // Roles dynamiques
    public static final String ROLE_CHAMPION = "cobblepulse.league.role.champion";
    public static final String ROLE_ELITE = "cobblepulse.league.role.elite";
    public static final String ROLE_MASTER = "cobblepulse.league.role.master";
    public static final String ARENA_SETTP = "cobblepulse.league.arena.settp";
    public static final String ARENA_TOGGLE = "cobblepulse.league.arena.toggle";
    public static final String TOURNAMENT_WINNER = "cobblepulse.tournament.winner";
    // Animateurs
    public static final String T_CREATE = "cobblepulse.tournament.create";
    public static final String T_MANAGE = "cobblepulse.tournament.manage";
    public static final String T_DISQUALIFY = "cobblepulse.tournament.disqualify";
    public static final String T_ARENA = "cobblepulse.tournament.arena.set";
    public static final String T_REWARDS = "cobblepulse.tournament.rewards";
    // Moderateurs
    public static final String STAFF_INSPECT = "cobblepulse.staff.inspect";
    public static final String STAFF_BADGE_REMOVE = "cobblepulse.staff.badges.remove";
    public static final String STAFF_COOLDOWN_RESET = "cobblepulse.staff.cooldown.reset";
    // Administrateurs
    public static final String ADMIN = "cobblepulse.admin";
    public static final String ADMIN_LEAGUE = "cobblepulse.admin.league";
    public static final String ADMIN_RELOAD = "cobblepulse.admin.reload";
    public static final String ADMIN_SEASON = "cobblepulse.admin.season";

    private Perms() {}

    /** Noeud accorde par defaut a tous les joueurs (sauf refus explicite). */
    public static boolean player(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        return v == null || v;
    }

    /** Noeud staff : par defaut reserve aux operateurs (niveau 2). */
    public static boolean staff(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        return v != null ? v : p.hasPermissions(2);
    }

    /** Noeud administrateur : par defaut operateurs niveau 3+. */
    public static boolean admin(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        if (v != null) return v;
        Boolean all = LuckPermsBridge.check(p, ADMIN);
        return all != null ? all : p.hasPermissions(3);
    }

    /** Noeud VIP : refuse par defaut. */
    public static boolean vip(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        return v != null && v;
    }

    public static Predicate<CommandSourceStack> playerReq(String node) {
        return src -> !src.isPlayer() || player(src.getPlayer(), node);
    }

    public static Predicate<CommandSourceStack> staffReq(String node) {
        return src -> !src.isPlayer() || staff(src.getPlayer(), node);
    }

    public static Predicate<CommandSourceStack> adminReq(String node) {
        return src -> !src.isPlayer() || admin(src.getPlayer(), node);
    }
}
