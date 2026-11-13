package fr.cobblepulse.clan;

import com.mojang.authlib.GameProfile;
import fr.cobblepulse.core.ItemBuilder;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.TextUtil;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.WarData;
import fr.cobblepulse.gui.Gui;
import fr.cobblepulse.gui.Guis;
import fr.cobblepulse.gui.Paged;
import fr.cobblepulse.gui.SharedChestMenu;
import fr.cobblepulse.gui.SubContainer;
import fr.cobblepulse.menu.MainMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Interfaces du module Clan : gestion, membres, inventaire partage, recrutement, guerres, classement. */
public final class ClanGuis {
    private static final String[][] COLORS = {
            {"&0", "Noir"}, {"&1", "Bleu foncé"}, {"&2", "Vert foncé"}, {"&3", "Cyan"}, {"&4", "Rouge foncé"}, {"&5", "Violet"},
            {"&6", "Or"}, {"&7", "Gris"}, {"&8", "Gris foncé"}, {"&9", "Bleu"}, {"&a", "Vert"}, {"&b", "Aqua"}, {"&c", "Rouge"},
            {"&d", "Rose"}, {"&e", "Jaune"}, {"&f", "Blanc"}};
    private static final Item[] COLOR_ITEMS = {
            Items.BLACK_WOOL, Items.BLUE_WOOL, Items.GREEN_WOOL, Items.CYAN_WOOL, Items.RED_WOOL, Items.PURPLE_WOOL, Items.ORANGE_WOOL,
            Items.LIGHT_GRAY_WOOL, Items.GRAY_WOOL, Items.LIGHT_BLUE_WOOL, Items.LIME_WOOL, Items.LIGHT_BLUE_WOOL, Items.RED_WOOL,
            Items.PINK_WOOL, Items.YELLOW_WOOL, Items.WHITE_WOOL};

    private ClanGuis() {}

    public static void open(ServerPlayer p) {
        ClanData c = ClanManager.clanOf(p.getUUID());
        if (c == null) openNoClan(p);
        else openClan(p, c);
    }

    private static List<String> clanInfo(ClanData c) {
        List<String> lore = new ArrayList<>();
        ClanData.ClanMember leader = c.members.get(c.leader);
        lore.add("&7Tag : " + ClanManager.tagString(c, true));
        lore.add("&7Chef : &f" + (leader == null ? "?" : leader.name));
        lore.add("&7Membres : &f" + c.members.size());
        lore.add("&7Recrutement : " + ClanManager.modeLabel(ClanManager.mode(c)));
        lore.add("&7Influence : &d" + c.influence + " &8(#" + ClanManager.rank(c) + ")");
        lore.add("&7PvP inter-clans : &a" + c.pvpWins + "V &7/ &c" + c.pvpLosses + "D");
        lore.add("&7Guerres : &a" + c.warWins + "V &7/ &c" + c.warLosses + "D &7/ &e" + c.warDraws + "N");
        WarData w = WarManager.activeWar(c);
        if (w != null) {
            String enemy = w.attackerId.equals(c.id) ? w.defenderName : w.attackerName;
            lore.add("&c⚔ En guerre contre " + enemy + " &7(" + WarManager.score(w, c) + ")");
        }
        return lore;
    }

    // ------------------------------------------------------------------ sans clan

    public static void openNoClan(ServerPlayer p) {
        Gui gui = new Gui(3, "&8Clans");
        gui.set(11, ItemBuilder.of(Items.WRITABLE_BOOK).name("&a&lCréer un clan").lore("&7Commande :", "&f/clan create <nom> <tag>").build());
        gui.button(13, ItemBuilder.of(Items.WHITE_BANNER).name("&e&lListe des clans").lore("&7Rejoindre ou postuler").build(), x -> openList(x, 0));
        gui.button(15, ItemBuilder.of(Items.GOLD_BLOCK).name("&6&lClassement").lore("&7Influence et guerres").build(), ClanGuis::openLeaderboard);
        List<String> invites = new ArrayList<>();
        for (ClanData c : ClanManager.all()) {
            Long exp = c.invites.get(p.getUUID().toString());
            if (exp != null && exp > TimeFmt.now()) invites.add("&f- " + c.name + " &8(/clan accept " + c.name + ")");
        }
        if (!invites.isEmpty()) {
            gui.set(4, ItemBuilder.of(Items.PAPER).name("&eInvitations en attente").lore(invites).glow(true).build());
        }
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), MainMenu::open);
        gui.fill(Gui.pane());
        gui.open(p);
    }

    public static void openList(ServerPlayer p, int page) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (ClanData c : ClanManager.leaderboard()) {
            ClanManager.Mode m = ClanManager.mode(c);
            List<String> lore = clanInfo(c);
            lore.add("");
            if (ClanManager.clanOf(p.getUUID()) == null) {
                lore.add(switch (m) {
                    case OPEN -> "&aCliquez pour rejoindre";
                    case INVITE_ONLY -> "&eCliquez pour envoyer une demande";
                    case CLOSED -> "&cRecrutement fermé";
                });
            }
            entries.add(new Paged.Entry(ItemBuilder.of(bannerFor(m)).name("&f&l" + c.name).lore(lore).hideExtra().build(), (pl, click) -> {
                if (ClanManager.clanOf(pl.getUUID()) != null) return;
                ClanManager.join(pl, c);
                pl.closeContainer();
            }));
        }
        Paged.open(p, "&8Liste des clans", entries, page, ClanGuis::open);
    }

    private static Item bannerFor(ClanManager.Mode m) {
        return switch (m) {
            case OPEN -> Items.LIME_BANNER;
            case INVITE_ONLY -> Items.YELLOW_BANNER;
            case CLOSED -> Items.RED_BANNER;
        };
    }

    // ------------------------------------------------------------------ gestion du clan

    public static void openClan(ServerPlayer p, ClanData c) {
        ClanManager.Role role = ClanManager.role(c, p.getUUID());
        boolean leader = role == ClanManager.Role.LEADER;
        boolean officer = ClanManager.isOfficer(c, p.getUUID());
        Gui gui = new Gui(6, "&8Clan &f" + c.name + " " + ClanManager.tagString(c, true));
        gui.set(4, ItemBuilder.of(Items.WHITE_BANNER).name("&f&l" + c.name).lore(clanInfo(c)).hideExtra().build());
        gui.button(19, ItemBuilder.of(Items.PLAYER_HEAD).name("&e&lMembres").lore("&7" + c.members.size() + " membres", "&7Gérer les rôles et exclusions").build(),
                x -> openMembers(x, c, 0));
        if (Perms.player(p, Perms.CLAN_INVENTORY)) {
            int slots = ClanManager.inventorySlots(p);
            gui.button(21, ItemBuilder.of(Items.CHEST).name("&6&lInventaire partagé").lore("&7Capacité : &f" + slots + " emplacements",
                    slots < ClanManager.INVENTORY_SIZE ? "&8VIP : 54 emplacements" : "&dAccès VIP étendu").build(), ClanGuis::openInventory);
        }
        ClanManager.Mode mode = ClanManager.mode(c);
        gui.button(23, ItemBuilder.of(bannerFor(mode)).name("&b&lRecrutement").lore("&7Mode : " + ClanManager.modeLabel(mode),
                leader ? "&eCliquez pour changer" : "&8Réservé au chef").hideExtra().build(), x -> {
            if (!leader) return;
            ClanManager.Mode next = ClanManager.Mode.values()[(mode.ordinal() + 1) % ClanManager.Mode.values().length];
            ClanManager.setMode(x, next);
            openClan(x, c);
        });
        if (officer) {
            gui.button(25, ItemBuilder.of(Items.PAPER).name("&e&lDemandes d'adhésion").lore("&7" + c.requests.size() + " en attente").glow(!c.requests.isEmpty()).build(),
                    x -> openRequests(x, c));
        }
        WarData w = WarManager.activeWar(c);
        List<String> warLore = new ArrayList<>();
        if (w != null) {
            String enemy = w.attackerId.equals(c.id) ? w.defenderName : w.attackerName;
            warLore.add("&cEn guerre contre &f" + enemy);
            warLore.add("&7Score : &f" + WarManager.score(w, c));
            warLore.add("&7Fin dans : &f" + TimeFmt.duration(w.endsAt - TimeFmt.now()));
        } else {
            warLore.add("&7Aucune guerre en cours");
            if (leader) warLore.add("&eCliquez pour déclarer la guerre");
        }
        for (WarData h : WarManager.history(c, 3)) {
            String enemy = h.attackerId.equals(c.id) ? h.defenderName : h.attackerName;
            String res = h.winnerId == null ? "&eNul" : h.winnerId.equals(c.id) ? "&aVictoire" : "&cDéfaite";
            warLore.add("&8- " + res + " &8vs " + enemy + " (" + WarManager.score(h, c) + ")");
        }
        gui.button(29, ItemBuilder.of(Items.IRON_SWORD).name("&c&lGuerres d'Influence").lore(warLore).hideExtra().build(), x -> {
            if (leader && WarManager.activeWar(c) == null) openDeclareWar(x, c, 0);
        });
        gui.button(31, ItemBuilder.of(Items.GOLD_BLOCK).name("&6&lClassement des clans").build(), ClanGuis::openLeaderboard);
        if (officer) {
            gui.button(33, ItemBuilder.of(Items.CYAN_DYE).name("&d&lCouleur du tag").lore("&7Actuel : " + ClanManager.tagString(c, true),
                    Perms.vip(p, Perms.CLAN_CHAT_COLOR) ? "&eCliquez pour choisir" : "&8Réservé aux VIP").build(), x -> {
                if (Perms.vip(x, Perms.CLAN_CHAT_COLOR)) openColors(x, c);
                else Msg.send(x, "clan.err_color_vip");
            });
        }
        if (leader) {
            gui.button(40, ItemBuilder.of(Items.TNT).name("&4&lDissoudre le clan").build(), x -> Guis.confirm(x, "&4Dissoudre " + c.name + " ?",
                    "Cette action est définitive", () -> ClanManager.disband(x), () -> openClan(x, c)));
        } else {
            gui.button(40, ItemBuilder.of(Items.BARRIER).name("&cQuitter le clan").build(), x -> Guis.confirm(x, "&cQuitter " + c.name + " ?",
                    "Quitter le clan", () -> ClanManager.leave(x), () -> openClan(x, c)));
        }
        gui.button(49, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), MainMenu::open);
        gui.fill(Gui.pane());
        gui.open(p);
    }

    public static void openInventory(ServerPlayer p) {
        ClanData c = ClanManager.clanOf(p.getUUID());
        if (c == null) {
            Msg.send(p, "clan.err_no_clan");
            return;
        }
        if (!Perms.player(p, Perms.CLAN_INVENTORY)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        SimpleContainer inv = ClanManager.inventory(c);
        int slots = Math.max(9, Math.min(54, (ClanManager.inventorySlots(p) / 9) * 9));
        int rows = slots / 9;
        Container view = slots >= ClanManager.INVENTORY_SIZE ? inv : new SubContainer(inv, slots);
        String clanId = c.id;
        p.openMenu(new SimpleMenuProvider((id, pinv, pl) -> new SharedChestMenu(id, pinv, view, rows,
                pl2 -> {
                    ClanData cur = ClanManager.clanOf(pl2.getUUID());
                    return cur != null && cur.id.equals(clanId);
                },
                pl2 -> ClanManager.save(c)), TextUtil.legacy("&6Inventaire de " + c.name)));
    }

    private static void openMembers(ServerPlayer p, ClanData c, int page) {
        boolean officer = ClanManager.isOfficer(c, p.getUUID());
        List<Paged.Entry> entries = new ArrayList<>();
        List<Map.Entry<String, ClanData.ClanMember>> members = new ArrayList<>(c.members.entrySet());
        members.sort((a, b) -> Integer.compare(roleOrder(a.getValue().role), roleOrder(b.getValue().role)));
        for (Map.Entry<String, ClanData.ClanMember> e : members) {
            UUID u = UUID.fromString(e.getKey());
            ClanData.ClanMember m = e.getValue();
            ServerPlayer online = Players.get(u);
            GameProfile gp = online != null ? online.getGameProfile() : new GameProfile(u, m.name);
            List<String> lore = new ArrayList<>();
            lore.add("&7Rôle : &f" + roleLabel(m.role));
            lore.add("&7Membre depuis : &f" + TimeFmt.date(m.joinedAt));
            lore.add(online != null ? "&aEn ligne" : "&8Hors-ligne");
            if (officer && !u.equals(p.getUUID())) lore.add("&eCliquez pour gérer");
            entries.add(new Paged.Entry(ItemBuilder.of(Items.PLAYER_HEAD).head(gp).name("&f" + m.name).lore(lore).build(), (pl, click) -> {
                if (ClanManager.isOfficer(c, pl.getUUID()) && !u.equals(pl.getUUID())) openMemberActions(pl, c, u);
            }));
        }
        Paged.open(p, "&8Membres de " + c.name, entries, page, x -> openClan(x, c));
    }

    private static int roleOrder(String role) {
        return switch (role) {
            case "LEADER" -> 0;
            case "OFFICER" -> 1;
            default -> 2;
        };
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "LEADER" -> "&6Chef";
            case "OFFICER" -> "&eOfficier";
            default -> "&7Membre";
        };
    }

    private static void openMemberActions(ServerPlayer p, ClanData c, UUID target) {
        ClanData.ClanMember m = c.members.get(target.toString());
        if (m == null) {
            openMembers(p, c, 0);
            return;
        }
        boolean leader = ClanManager.role(c, p.getUUID()) == ClanManager.Role.LEADER;
        Gui gui = new Gui(3, "&8Gérer " + m.name);
        if (leader) {
            if (!"OFFICER".equals(m.role)) {
                gui.button(10, ItemBuilder.of(Items.GOLD_INGOT).name("&ePromouvoir officier").build(), x -> {
                    ClanManager.setRole(x, target, ClanManager.Role.OFFICER);
                    openMembers(x, c, 0);
                });
            } else {
                gui.button(10, ItemBuilder.of(Items.IRON_INGOT).name("&7Rétrograder membre").build(), x -> {
                    ClanManager.setRole(x, target, ClanManager.Role.MEMBER);
                    openMembers(x, c, 0);
                });
            }
            gui.button(12, ItemBuilder.of(Items.GOLDEN_HELMET).name("&6Transférer la direction").hideExtra().build(),
                    x -> Guis.confirm(x, "&6Transférer ?", "Faire de " + m.name + " le chef du clan", () -> ClanManager.transfer(x, target), () -> openMembers(x, c, 0)));
        }
        gui.button(14, ItemBuilder.of(Items.BARRIER).name("&cExclure").build(),
                x -> Guis.confirm(x, "&cExclure " + m.name + " ?", "Exclure du clan", () -> ClanManager.kick(x, target), () -> openMembers(x, c, 0)));
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), x -> openMembers(x, c, 0));
        gui.fill(Gui.pane());
        gui.open(p);
    }

    private static void openRequests(ServerPlayer p, ClanData c) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ClanData.JoinRequest> e : new ArrayList<>(c.requests.entrySet())) {
            UUID u = UUID.fromString(e.getKey());
            entries.add(new Paged.Entry(ItemBuilder.of(Items.PLAYER_HEAD).head(new GameProfile(u, e.getValue().name)).name("&f" + e.getValue().name)
                    .lore("&7Demande du " + TimeFmt.date(e.getValue().time), "&aClic gauche : accepter", "&cClic droit : refuser").build(), (pl, click) -> {
                if (click.right()) ClanManager.denyRequest(pl, u);
                else ClanManager.acceptRequest(pl, u);
                openRequests(pl, c);
            }));
        }
        Paged.open(p, "&8Demandes d'adhésion", entries, 0, x -> openClan(x, c));
    }

    private static void openDeclareWar(ServerPlayer p, ClanData c, int page) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (ClanData other : ClanManager.leaderboard()) {
            if (other == c) continue;
            boolean busy = WarManager.activeWar(other) != null;
            List<String> lore = clanInfo(other);
            lore.add("");
            lore.add(busy ? "&cDéjà en guerre" : "&eCliquez pour déclarer la guerre");
            entries.add(new Paged.Entry(ItemBuilder.of(busy ? Items.GRAY_BANNER : Items.RED_BANNER).name("&f" + other.name).lore(lore).hideExtra().build(),
                    (pl, click) -> {
                        if (busy) return;
                        Guis.confirm(pl, "&cGuerre contre " + other.name + " ?", "Déclarer une Guerre d'Influence",
                                () -> WarManager.declare(pl, other), () -> openClan(pl, c));
                    }));
        }
        Paged.open(p, "&cDéclarer la guerre", entries, page, x -> openClan(x, c));
    }

    private static void openColors(ServerPlayer p, ClanData c) {
        Gui gui = new Gui(3, "&dCouleur du tag");
        for (int i = 0; i < COLORS.length; i++) {
            String code = COLORS[i][0];
            gui.button(i, ItemBuilder.of(COLOR_ITEMS[i]).name(code + COLORS[i][1] + " &8(" + code + "[" + c.tag + "]&8)").build(), x -> {
                ClanManager.setTagColor(x, code);
                openClan(x, c);
            });
        }
        gui.set(22, ItemBuilder.of(Items.NAME_TAG).name("&7Couleur hexadécimale").lore("&f/clan tagcolor &#RRGGBB").build());
        gui.button(26, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), x -> openClan(x, c));
        gui.fill(Gui.pane());
        gui.open(p);
    }

    /** Podium visuel : influence totale puis victoires en guerre. */
    public static void openLeaderboard(ServerPlayer p) {
        List<ClanData> top = ClanManager.leaderboard();
        Gui gui = new Gui(6, "&6Classement des clans");
        int[] podiumSlots = {13, 21, 23};
        Item[] podiumBlocks = {Items.GOLD_BLOCK, Items.IRON_BLOCK, Items.COPPER_BLOCK};
        Item[] banners = {Items.YELLOW_BANNER, Items.LIGHT_GRAY_BANNER, Items.ORANGE_BANNER};
        String[] medals = {"&6&l1er", "&7&l2e", "&c&l3e"};
        for (int i = 0; i < 3; i++) {
            int slot = podiumSlots[i];
            gui.set(slot + 9, ItemBuilder.of(podiumBlocks[i]).name(medals[i]).build());
            if (i == 0) gui.set(slot + 18, ItemBuilder.of(podiumBlocks[i]).name(medals[i]).build());
            if (i < top.size()) {
                ClanData c = top.get(i);
                gui.set(slot, ItemBuilder.of(banners[i]).name(medals[i] + " &f" + c.name).lore(clanInfo(c)).glow(i == 0).hideExtra().build());
            } else {
                gui.set(slot, ItemBuilder.of(Items.GLASS_PANE).name(medals[i] + " &8- libre").build());
            }
        }
        int slot = 37;
        for (int i = 3; i < Math.min(top.size(), 10); i++) {
            ClanData c = top.get(i);
            gui.set(slot++, ItemBuilder.of(Items.WHITE_BANNER).count(i + 1).name("&e#" + (i + 1) + " &f" + c.name).lore(clanInfo(c)).hideExtra().build());
        }
        ClanData mine = ClanManager.clanOf(p.getUUID());
        if (mine != null) {
            gui.set(4, ItemBuilder.of(Items.NAME_TAG).name("&eVotre clan : &f" + mine.name).lore("&7Rang : &f#" + ClanManager.rank(mine),
                    "&7Influence : &d" + mine.influence).build());
        }
        gui.button(49, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), ClanGuis::open);
        gui.fill(Gui.pane());
        gui.open(p);
    }
}
