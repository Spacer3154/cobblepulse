package fr.cobblepulse.menu;

import fr.cobblepulse.clan.ClanGuis;
import fr.cobblepulse.clan.ClanManager;
import fr.cobblepulse.core.ItemBuilder;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.PlayerData;
import fr.cobblepulse.gui.Gui;
import fr.cobblepulse.league.LeagueGuis;
import fr.cobblepulse.profile.ProfileGui;
import fr.cobblepulse.storage.PlayerDataManager;
import fr.cobblepulse.tournament.TournamentGuis;
import fr.cobblepulse.tournament.TournamentManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Hub central /cobblepulse (/cp). */
public final class MainMenu {
    private MainMenu() {}

    public static void open(ServerPlayer p) {
        if (!Perms.player(p, Perms.MENU)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        PlayerData d = PlayerDataManager.online(p);
        ClanData clan = ClanManager.clanOf(p.getUUID());
        Gui gui = new Gui(3, Msg.raw("gui.main_title"));
        gui.button(10, ItemBuilder.of(Items.BOOK).name("&e&lÉtui à badges").lore("&7" + d.badges.size() + "/13 badges", "&8/league badges").glow(true).build(),
                x -> LeagueGuis.openBadgeCase(x, x.getUUID(), x.getGameProfile().getName(), PlayerDataManager.online(x), false));
        gui.button(12, ItemBuilder.of(Items.WHITE_BANNER).name("&b&lClan").lore(clan == null ? "&7Aucun clan" : "&7" + clan.name + " " + ClanManager.tagString(clan, true),
                "&8/clan").hideExtra().build(), ClanGuis::open);
        gui.button(14, ItemBuilder.of(Items.NETHER_STAR).name("&6&lLa Ligue").lore("&78 Champions, Conseil 4 et Maître", "&8/league").build(), LeagueGuis::openLeague);
        String t = TournamentManager.current() == null ? "&7Aucun tournoi" : "&7" + TournamentManager.current().name;
        gui.button(16, ItemBuilder.of(Items.GOLDEN_SWORD).name("&c&lTournois").lore(t, "&8/tournoi").hideExtra().build(), TournamentGuis::open);
        gui.button(22, ItemBuilder.of(Items.PLAYER_HEAD).head(p.getGameProfile()).name("&a&lMa carte de dresseur").lore("&8/profile").build(),
                x -> ProfileGui.open(x, x.getUUID(), x.getGameProfile().getName()));
        if (Perms.admin(p, Perms.ADMIN_LEAGUE)) {
            gui.button(26, ItemBuilder.of(Items.COMMAND_BLOCK).name("&4Éditeur de Ligue").build(), LeagueGuis::openAdmin);
        }
        gui.fill(Gui.pane());
        gui.open(p);
    }
}
