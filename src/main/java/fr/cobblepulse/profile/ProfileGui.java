package fr.cobblepulse.profile;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.authlib.GameProfile;
import fr.cobblepulse.battle.CobblemonBridge;
import fr.cobblepulse.clan.ClanManager;
import fr.cobblepulse.core.ItemBuilder;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.LeagueData.PostData;
import fr.cobblepulse.data.PlayerData;
import fr.cobblepulse.gui.Gui;
import fr.cobblepulse.league.LeagueGuis;
import fr.cobblepulse.league.LeagueManager;
import fr.cobblepulse.menu.MainMenu;
import fr.cobblepulse.storage.PlayerDataManager;
import fr.cobblepulse.storage.StateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Carte de Dresseur : identite competitive d'un joueur (/profile <joueur>). */
public final class ProfileGui {
    private ProfileGui() {}

    public static void open(ServerPlayer viewer, UUID target, String name) {
        PlayerDataManager.with(target, name, d -> show(viewer, target, d.name != null ? d.name : name, d));
    }

    private static void show(ServerPlayer viewer, UUID target, String name, PlayerData d) {
        boolean self = viewer.getUUID().equals(target);
        ServerPlayer online = Players.get(target);
        Gui gui = new Gui(5, "&8Carte de Dresseur &f" + name);
        GameProfile gp = online != null ? online.getGameProfile() : new GameProfile(target, name);
        gui.set(13, ItemBuilder.of(Items.PLAYER_HEAD).head(gp).name("&e&l" + name)
                .lore(online != null ? "&aEn ligne" : "&8Vu le " + TimeFmt.date(d.lastSeen)).build());

        ClanData clan = ClanManager.clanOf(target);
        if (clan == null) {
            gui.set(20, ItemBuilder.of(Items.WHITE_BANNER).name("&7Aucun clan").hideExtra().build());
        } else {
            ClanManager.Role role = ClanManager.role(clan, target);
            gui.set(20, ItemBuilder.of(Items.CYAN_BANNER).name("&b&lClan : &f" + clan.name).lore("&7Tag : " + ClanManager.tagString(clan, true),
                    "&7Rôle : &f" + (role == null ? "-" : role.name().toLowerCase(Locale.ROOT)),
                    "&7Influence : &d" + clan.influence + " &8(#" + ClanManager.rank(clan) + ")").hideExtra().build());
        }

        PostData post = LeagueManager.postOf(target);
        boolean winner = target.toString().equals(StateManager.get().tournamentWinnerUuid);
        ItemBuilder grade = ItemBuilder.of(post != null ? Items.GOLDEN_HELMET : Items.LEATHER_HELMET).hideExtra();
        grade.name(post != null ? "&6&l" + LeagueManager.grade(post) : "&7Aucun grade de Ligue");
        if (post != null) grade.lore("&7" + LeagueManager.plainName(post), "&7En poste depuis le " + TimeFmt.date(post.assignedAt));
        if (winner) grade.lore("&e[Vainqueur Tournoi]");
        gui.set(22, grade.glow(post != null || winner).build());

        int badges = d.badges.size();
        StringBuilder bar = new StringBuilder("&a");
        for (int i = 0; i < 13; i++) {
            if (i == badges) bar.append("&8");
            bar.append("■");
        }
        gui.button(24, ItemBuilder.of(Items.NETHER_STAR).name("&e&lBadges : &f" + badges + "&7/13").lore(bar.toString(),
                "&7Ligue : &a" + d.leagueWins + "V &7/ &c" + d.leagueLosses + "D", "&eCliquez pour voir l'étui").glow(badges >= 13).build(),
                x -> LeagueGuis.openBadgeCase(x, target, name, d, false));

        int total = d.tournamentMatchWins + d.tournamentMatchLosses;
        String ratio = total == 0 ? "-" : String.format(Locale.ROOT, "%.2f", d.tournamentMatchLosses == 0 ? d.tournamentMatchWins : (double) d.tournamentMatchWins / d.tournamentMatchLosses);
        gui.set(30, ItemBuilder.of(Items.GOLDEN_SWORD).name("&6&lTournois").lore("&7Victoires : &a" + d.tournamentMatchWins,
                "&7Défaites : &c" + d.tournamentMatchLosses, "&7Ratio V/D : &f" + ratio, "&7Tournois gagnés : &e" + d.tournamentsWon,
                "&7Participations : &f" + d.tournamentsPlayed).hideExtra().build());

        String fav = favorite(d);
        ItemStack favIcon = fav == null ? new ItemStack(Items.EGG) : CobblemonBridge.speciesIcon(fav);
        ItemBuilder fb = ItemBuilder.of(favIcon).name(fav == null ? "&7Pokémon favori : aucun" : "&d&lPokémon favori : &f" + fav);
        if (self) fb.lore("&eCliquez pour choisir dans votre équipe");
        gui.button(32, fb.build(), x -> {
            if (self) openFavoritePicker(x, d);
        });
        gui.button(40, ItemBuilder.of(Items.OAK_DOOR).name("&cFermer").build(), x -> {
            if (self) MainMenu.open(x);
            else x.closeContainer();
        });
        gui.fill(Gui.pane());
        gui.open(viewer);
    }

    private static String favorite(PlayerData d) {
        if (d.favoritePokemon != null && !d.favoritePokemon.isEmpty()) return d.favoritePokemon;
        String best = null;
        int max = 0;
        for (Map.Entry<String, Integer> e : d.pokemonUsage.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static void openFavoritePicker(ServerPlayer p, PlayerData d) {
        Gui gui = new Gui(1, "&dChoisir mon Pokémon favori");
        int slot = 0;
        for (Pokemon pk : CobblemonBridge.partyList(p)) {
            String species = pk.getSpecies().getName();
            gui.button(slot++, ItemBuilder.of(CobblemonBridge.icon(pk)).name("&f" + species).lore("&7Niveau " + pk.getLevel()).build(), x -> {
                d.favoritePokemon = species;
                PlayerDataManager.save(d);
                Msg.send(x, "profile.favorite_set", "pokemon", species);
                open(x, x.getUUID(), x.getGameProfile().getName());
            });
        }
        gui.button(8, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), x -> open(x, x.getUUID(), x.getGameProfile().getName()));
        gui.open(p);
    }
}
