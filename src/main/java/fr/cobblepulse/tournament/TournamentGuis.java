package fr.cobblepulse.tournament;

import com.mojang.authlib.GameProfile;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.config.TierRules;
import fr.cobblepulse.core.ItemBuilder;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.MiscState;
import fr.cobblepulse.data.PlayerData;
import fr.cobblepulse.data.TournamentData;
import fr.cobblepulse.gui.Gui;
import fr.cobblepulse.gui.Guis;
import fr.cobblepulse.gui.Paged;
import fr.cobblepulse.menu.MainMenu;
import fr.cobblepulse.storage.PlayerDataManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Interfaces /tournoi : inscription, arbre, randomizer, disqualification et coffres de recompenses. */
public final class TournamentGuis {
    private TournamentGuis() {}

    private static String stateLabel(TournamentData t) {
        return switch (t.state) {
            case "OPEN" -> "&aInscriptions ouvertes";
            case "RUNNING" -> "&6En cours";
            case "FINISHED" -> "&eTerminé";
            default -> "&cAnnulé";
        };
    }

    public static void open(ServerPlayer p) {
        TournamentData t = TournamentManager.current();
        boolean manage = Perms.staff(p, Perms.T_MANAGE);
        boolean create = Perms.staff(p, Perms.T_CREATE);
        Gui gui = new Gui(6, "&6Tournois");
        PlayerData me = PlayerDataManager.online(p);
        if (t == null) {
            gui.set(13, ItemBuilder.of(Items.CLOCK).name("&7Aucun tournoi en cours").lore("&8Surveillez les annonces !").build());
        } else {
            TierRules rules = Cfg.TIERS.get(t.tier);
            List<String> lore = new ArrayList<>();
            lore.add("&7Format : &f" + (rules == null ? t.tier : rules.displayName));
            lore.add("&7Statut : " + stateLabel(t));
            lore.add("&7Participants : &f" + t.participants.size());
            lore.add("&7Créé par &f" + t.createdBy + " &7le " + TimeFmt.date(t.createdAt));
            TournamentData.Round r = TournamentManager.round();
            if (r != null && "RUNNING".equals(t.state)) {
                lore.add("&7Ronde : &f" + TournamentManager.roundName(r.slots) + (t.roundActive ? " &a(en cours)" : " &e(en attente)"));
            }
            if (t.winnerUuid != null && t.participants.get(t.winnerUuid) != null) {
                lore.add("&6Vainqueur : &e" + t.participants.get(t.winnerUuid).name);
            }
            gui.set(4, ItemBuilder.of(Items.GOLDEN_SWORD).name("&6&l" + t.name).lore(lore).hideExtra().glow(true).build());
            if (rules != null) {
                gui.set(13, ItemBuilder.of(Items.BOOK).name("&eRègles " + rules.displayName).lore(TeamValidator.describe(rules)).build());
            }
            boolean joined = t.participants.containsKey(p.getUUID().toString());
            if ("OPEN".equals(t.state)) {
                if (joined) {
                    gui.button(20, ItemBuilder.of(Items.RED_CONCRETE).name("&cSe désinscrire").build(), x -> {
                        TournamentManager.leave(x);
                        open(x);
                    });
                } else {
                    gui.button(20, ItemBuilder.of(Items.LIME_CONCRETE).name("&a&lS'inscrire").lore("&7Votre équipe sera vérifiée.").build(), x -> {
                        TournamentManager.join(x);
                        open(x);
                    });
                }
            } else if (joined) {
                TournamentData.Participant me2 = t.participants.get(p.getUUID().toString());
                gui.set(20, ItemBuilder.of(me2.disqualified ? Items.BARRIER : Items.NAME_TAG).name("&eMon parcours")
                        .lore("&7Victoires : &a" + me2.wins + " &7Défaites : &c" + me2.losses,
                                me2.disqualified ? "&cDisqualifié : " + me2.dqReason : (me2.placementLo > 0 ? "&7Classement : &f" + me2.placementLo : "&aToujours en lice")).build());
            }
            gui.button(22, ItemBuilder.of(Items.MAP).name("&e&lArbre du tournoi").lore("&7Visualiser les matchs").build(), x -> openBracket(x, -1));
            gui.button(24, ItemBuilder.of(Items.PLAYER_HEAD).name("&eParticipants").lore("&f" + t.participants.size() + " inscrits").build(), x -> openParticipants(x, false));
        }
        int pending = RewardManager.pendingCount(me);
        gui.button(40, ItemBuilder.of(pending > 0 ? Items.CHEST_MINECART : Items.MINECART).name("&6Mes récompenses")
                .lore(pending > 0 ? "&a" + pending + " lot(s) à récupérer" : "&7Rien à récupérer", "&eCliquez pour récupérer").glow(pending > 0).build(), x -> {
            RewardManager.claim(x);
            open(x);
        });
        // Staff
        if (create && (t == null || !("OPEN".equals(t.state) || "RUNNING".equals(t.state)))) {
            gui.button(46, ItemBuilder.of(Items.WRITABLE_BOOK).name("&a&lCréer un tournoi").lore("&7Choisir le format").build(), TournamentGuis::openTierPicker);
        }
        if (manage && t != null) {
            if ("OPEN".equals(t.state)) {
                gui.button(47, ItemBuilder.of(Items.ENDER_EYE).name("&d&lLancer le Randomizer").lore("&7Clôture les inscriptions et génère l'arbre").build(),
                        x -> Guis.confirm(x, "&dLancer le tirage ?", t.participants.size() + " participants", () -> {
                            TournamentManager.start(x.createCommandSourceStack());
                            open(x);
                        }, () -> open(x)));
            } else if ("RUNNING".equals(t.state) && !t.roundActive) {
                TournamentData.Round r = TournamentManager.round();
                gui.button(47, ItemBuilder.of(Items.BLAZE_ROD).name("&6&lLancer la ronde").lore("&7" + (r == null ? "" : TournamentManager.roundName(r.slots))).build(), x -> {
                    TournamentManager.startRound(x.createCommandSourceStack());
                    open(x);
                });
            }
            if ("OPEN".equals(t.state) || "RUNNING".equals(t.state)) {
                gui.button(48, ItemBuilder.of(Items.TNT).name("&4Annuler le tournoi").build(), x -> Guis.confirm(x, "&4Annuler le tournoi ?", t.name,
                        () -> TournamentManager.cancel(x.createCommandSourceStack()), () -> open(x)));
            }
        }
        if (Perms.staff(p, Perms.T_DISQUALIFY) && t != null && ("OPEN".equals(t.state) || "RUNNING".equals(t.state))) {
            gui.button(50, ItemBuilder.of(Items.BARRIER).name("&cDisqualifier un joueur").build(), x -> openParticipants(x, true));
        }
        if (Perms.staff(p, Perms.T_REWARDS)) {
            gui.button(51, ItemBuilder.of(Items.ENDER_CHEST).name("&6Coffres de récompenses").lore("&7Éditer les items par palier").build(), TournamentGuis::openRewardTiers);
        }
        if (Perms.staff(p, Perms.T_ARENA)) {
            List<String> lore = new ArrayList<>();
            for (MiscState.ArenaData a : TournamentManager.arenas()) {
                lore.add("&f" + a.name + " &7- " + (a.p1 != null && a.p2 != null ? "&aprête" : "&cpoints manquants"));
            }
            if (lore.isEmpty()) lore.add("&cAucune arène : /tournoi arena add <nom>");
            gui.set(52, ItemBuilder.of(Items.LODESTONE).name("&eArènes de tournoi").lore(lore).build());
        }
        gui.button(45, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), MainMenu::open);
        gui.fill(Gui.pane());
        gui.open(p);
    }

    private static void openTierPicker(ServerPlayer p) {
        Gui gui = new Gui(3, "&aFormat du tournoi");
        int i = 0;
        for (TierRules r : Cfg.TIERS.values()) {
            if (i >= 18) break;
            gui.button(i++, ItemBuilder.of(Items.PAPER).name("&e" + r.displayName).lore(TeamValidator.describe(r)).build(), x -> {
                x.closeContainer();
                TournamentManager.create(x, r.id, null, x.createCommandSourceStack());
            });
        }
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), TournamentGuis::open);
        gui.fill(Gui.pane());
        gui.open(p);
    }

    private static void openParticipants(ServerPlayer p, boolean dqMode) {
        TournamentData t = TournamentManager.current();
        if (t == null) return;
        List<Paged.Entry> entries = new ArrayList<>();
        for (TournamentData.Participant part : t.participants.values()) {
            UUID u = UUID.fromString(part.uuid);
            ServerPlayer online = Players.get(u);
            GameProfile gp = online != null ? online.getGameProfile() : new GameProfile(u, part.name);
            List<String> lore = new ArrayList<>();
            lore.add("&7V/D : &a" + part.wins + "&7/&c" + part.losses);
            if (part.disqualified) lore.add("&cDisqualifié : " + part.dqReason);
            else if (part.placementLo > 0) lore.add("&7Éliminé (place " + part.placementLo + ")");
            lore.add(online != null ? "&aEn ligne" : "&8Hors-ligne");
            if (dqMode && !part.disqualified) lore.add("&cCliquez pour disqualifier");
            entries.add(new Paged.Entry(ItemBuilder.of(Items.PLAYER_HEAD).head(gp).name((part.disqualified ? "&c&m" : "&f") + part.name).lore(lore).build(),
                    (x, c) -> {
                        if (!dqMode || part.disqualified || !Perms.staff(x, Perms.T_DISQUALIFY)) return;
                        Guis.confirm(x, "&cDisqualifier " + part.name + " ?", "Son adversaire sera qualifié", () ->
                                TournamentManager.disqualify(x.createCommandSourceStack(), part.uuid,
                                        Msg.format("tournament.dq_default_reason", "staff", x.getGameProfile().getName())), () -> openParticipants(x, true));
                    }));
        }
        Paged.open(p, dqMode ? "&cDisqualification" : "&8Participants", entries, 0, TournamentGuis::open);
    }

    /** Arbre : une page par ronde. */
    public static void openBracket(ServerPlayer p, int roundIdx) {
        TournamentData t = TournamentManager.current();
        if (t == null) return;
        if (t.rounds.isEmpty()) {
            openParticipants(p, false);
            return;
        }
        int idx = roundIdx < 0 ? Math.max(0, Math.min(t.currentRound, t.rounds.size() - 1)) : Math.min(roundIdx, t.rounds.size() - 1);
        TournamentData.Round r = t.rounds.get(idx);
        boolean dq = Perms.staff(p, Perms.T_DISQUALIFY) && "RUNNING".equals(t.state);
        List<Paged.Entry> entries = new ArrayList<>();
        List<TournamentData.Match> matches = new ArrayList<>(r.matches);
        if (r.slots == 2 && t.thirdPlace != null) matches.add(t.thirdPlace);
        for (TournamentData.Match m : matches) entries.add(new Paged.Entry(matchItem(m, dq), (x, c) -> {
            if (!dq || !"PENDING".equals(m.status) && !"RUNNING".equals(m.status) || m.bye) return;
            openMatchDq(x, m, idx);
        }));
        final int cur = idx;
        Paged.open(p, "&6" + TournamentManager.roundName(r.slots), entries, 0, TournamentGuis::open, g -> {
            if (cur > 0) g.button(47, ItemBuilder.of(Items.SPECTRAL_ARROW).name("&eRonde précédente").build(), x -> openBracket(x, cur - 1));
            if (cur < t.rounds.size() - 1) g.button(51, ItemBuilder.of(Items.SPECTRAL_ARROW).name("&eRonde suivante").build(), x -> openBracket(x, cur + 1));
        });
    }

    private static ItemStack matchItem(TournamentData.Match m, boolean dq) {
        Item icon = switch (m.status) {
            case "RUNNING" -> Items.IRON_SWORD;
            case "DONE" -> Items.FILLED_MAP;
            default -> Items.PAPER;
        };
        String title = (m.thirdPlace ? "&6Petite finale" : "&fMatch #" + (m.index + 1));
        List<String> lore = new ArrayList<>();
        if (m.bye) {
            lore.add("&f" + (m.n1 == null ? "?" : m.n1) + " &7- qualifié d'office (bye)");
        } else {
            lore.add(side(m, m.p1, m.n1) + " &7vs " + side(m, m.p2, m.n2));
            lore.add(switch (m.status) {
                case "RUNNING" -> "&6Combat en cours";
                case "DONE" -> "&aTerminé" + (m.note != null ? " &8(" + m.note + ")" : "");
                default -> "&7En attente";
            });
            if (dq && !"DONE".equals(m.status)) lore.add("&cCliquez pour disqualifier un joueur");
        }
        return ItemBuilder.of(icon).name(title).lore(lore).hideExtra().build();
    }

    private static String side(TournamentData.Match m, String uuid, String name) {
        if (uuid == null) return "&8-";
        if (m.winner == null) return "&f" + name;
        return uuid.equals(m.winner) ? "&a&l" + name : "&c&m" + name;
    }

    private static void openMatchDq(ServerPlayer p, TournamentData.Match m, int roundIdx) {
        Gui gui = new Gui(3, "&cDisqualifier ?");
        int slot = 11;
        for (String[] side : new String[][]{{m.p1, m.n1}, {m.p2, m.n2}}) {
            if (side[0] == null) continue;
            UUID u = UUID.fromString(side[0]);
            String name = side[1];
            gui.button(slot, ItemBuilder.of(Items.PLAYER_HEAD).head(new GameProfile(u, name)).name("&c" + name).lore("&7Disqualifier ce joueur").build(),
                    x -> Guis.confirm(x, "&cDisqualifier " + name + " ?", "Son adversaire sera qualifié",
                            () -> TournamentManager.disqualify(x.createCommandSourceStack(), side[0],
                                    Msg.format("tournament.dq_default_reason", "staff", x.getGameProfile().getName())),
                            () -> openBracket(x, roundIdx)));
            slot = 15;
        }
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), x -> openBracket(x, roundIdx));
        gui.fill(Gui.pane());
        gui.open(p);
    }

    private static void openRewardTiers(ServerPlayer p) {
        Gui gui = new Gui(3, "&6Coffres de récompenses");
        int slot = 9;
        for (String tier : RewardManager.TIERS) {
            int items = 0;
            for (int i = 0; i < 54; i++) if (!RewardManager.chest(tier).getItem(i).isEmpty()) items++;
            List<String> lore = new ArrayList<>();
            lore.add("&7Places " + Cfg.rewards.i("tiers." + tier + ".min", 0) + " à " + Cfg.rewards.i("tiers." + tier + ".max", 0));
            lore.add("&7Items : &f" + items + " pile(s)");
            lore.add("&7Commandes : &f" + Cfg.rewards.list("tiers." + tier + ".commands").size());
            lore.add("&eCliquez pour éditer");
            gui.button(slot++, ItemBuilder.of(Items.CHEST).name("&6" + RewardManager.label(tier)).lore(lore).build(), x -> RewardManager.openEditor(x, tier));
        }
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), TournamentGuis::open);
        gui.fill(Gui.pane());
        gui.open(p);
    }
}
