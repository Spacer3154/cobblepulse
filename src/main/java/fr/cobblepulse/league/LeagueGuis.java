package fr.cobblepulse.league;

import com.mojang.authlib.GameProfile;
import fr.cobblepulse.battle.MatchService;
import fr.cobblepulse.config.Cfg;
import fr.cobblepulse.core.ItemBuilder;
import fr.cobblepulse.core.Loc;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.core.Perms;
import fr.cobblepulse.core.Players;
import fr.cobblepulse.core.TimeFmt;
import fr.cobblepulse.data.LeagueData.PostData;
import fr.cobblepulse.data.PlayerData;
import fr.cobblepulse.gui.Gui;
import fr.cobblepulse.gui.Guis;
import fr.cobblepulse.gui.Paged;
import fr.cobblepulse.menu.MainMenu;
import fr.cobblepulse.storage.PlayerDataManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Interfaces de la Ligue : vue publique, menu titulaire, editeur admin, etui a badges et inspection staff. */
public final class LeagueGuis {
    public static final int[] CHAMPION_SLOTS = {9, 10, 11, 12, 14, 15, 16, 17};
    public static final int[] ELITE_SLOTS = {28, 29, 33, 34};
    public static final int MASTER_SLOT = 40;

    private LeagueGuis() {}

    private static int slotOf(PostData p) {
        int n = LeagueManager.number(p) - 1;
        return switch (LeagueManager.kind(p)) {
            case CHAMPION -> CHAMPION_SLOTS[Math.min(n, 7)];
            case ELITE -> ELITE_SLOTS[Math.min(n, 3)];
            case MASTER -> MASTER_SLOT;
        };
    }

    static ItemStack holderHead(PostData p) {
        UUID u = LeagueManager.holder(p);
        if (u == null) {
            return ItemBuilder.of(Items.BARRIER).build();
        }
        ServerPlayer online = Players.get(u);
        GameProfile gp = online != null ? online.getGameProfile() : new GameProfile(u, p.holderName == null ? "?" : p.holderName);
        return ItemBuilder.of(Items.PLAYER_HEAD).head(gp).build();
    }

    // ------------------------------------------------------------------ vue publique

    public static void openLeague(ServerPlayer viewer) {
        PlayerData me = PlayerDataManager.online(viewer);
        Gui gui = new Gui(6, Msg.raw("gui.league_title"));
        gui.set(4, ItemBuilder.of(Items.NETHER_STAR).name("&e&lLa Ligue CobblePulse")
                .lore("&7Badges obtenus : &f" + me.badges.size() + "&7/13",
                        "&7Combat de Badge : &fclic gauche",
                        "&7Combat de Place : &fclic droit",
                        "&8Le Maître ne se défie qu'en combat de place.").glow(true).build());
        gui.set(13, ItemBuilder.of(Items.GOLDEN_HELMET).name("&6&lChampions d'Arène").lore("&7Accessibles à tous.").hideExtra().build());
        gui.set(31, ItemBuilder.of(Items.DIAMOND_HELMET).name("&5&lConseil 4").lore("&7Requiert les 8 badges de Champions.").hideExtra().build());
        for (PostData p : LeagueManager.posts()) {
            gui.set(slotOf(p), postItem(p, me, viewer), (pl, click) -> {
                if (p.holderUuid == null) {
                    Msg.send(pl, "league.err_vacant");
                    return;
                }
                if (LeagueManager.kind(p) == PostKind.MASTER || click.right()) {
                    Guis.confirm(pl, "&cCombat de Place", "Défier " + p.holderName + " pour prendre sa place ?",
                            () -> LeagueManager.challenge(pl, p, LeagueManager.ChallengeType.PLACE), () -> openLeague(pl));
                } else {
                    pl.closeContainer();
                    LeagueManager.challenge(pl, p, LeagueManager.ChallengeType.BADGE);
                }
            });
        }
        PostData queued = LeagueManager.queuedPost(viewer.getUUID());
        gui.button(45, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour au menu").build(), MainMenu::open);
        gui.button(47, ItemBuilder.of(Items.BOOK).name("&e&lMon étui à badges").lore("&7" + me.badges.size() + "/13 badges").build(),
                pl -> openBadgeCase(pl, pl.getUUID(), pl.getGameProfile().getName(), PlayerDataManager.online(pl), false));
        if (queued != null) {
            gui.button(49, ItemBuilder.of(Items.CLOCK).name("&eFile d'attente").lore("&7Poste : " + LeagueManager.plainName(queued),
                    "&cCliquez pour quitter la file").build(), pl -> {
                LeagueManager.leaveQueue(pl);
                openLeague(pl);
            });
        } else {
            gui.set(49, ItemBuilder.of(Items.CLOCK).name("&7Aucune file d'attente").build());
        }
        PostData mine = LeagueManager.postOf(viewer.getUUID());
        if (mine != null) {
            gui.button(51, ItemBuilder.of(Items.LECTERN).name("&a&lMon arène").lore("&7" + LeagueManager.plainName(mine)).build(),
                    pl -> openHolderMenu(pl));
        }
        if (Perms.admin(viewer, Perms.ADMIN_LEAGUE)) {
            gui.button(53, ItemBuilder.of(Items.COMMAND_BLOCK).name("&c&lÉditeur de Ligue").build(), LeagueGuis::openAdmin);
        }
        gui.fill(Gui.pane());
        gui.open(viewer);
    }

    private static ItemStack postItem(PostData p, PlayerData me, ServerPlayer viewer) {
        ItemBuilder b = ItemBuilder.of(holderHead(p));
        b.name(LeagueManager.displayName(p));
        List<String> lore = new ArrayList<>();
        if (p.holderUuid == null) {
            lore.add("&8Poste vacant");
        } else {
            boolean online = Players.online(LeagueManager.holder(p));
            lore.add("&7Titulaire : &f" + p.holderName + (online ? " &a(en ligne)" : " &8(hors-ligne)"));
            lore.add("&7Arène : " + (p.open && online ? "&a[OUVERTE]" : "&c[FERMÉE]"));
            if (LeagueManager.inLeagueMatch(p)) lore.add("&6En combat");
            lore.add("&7File d'attente : &f" + LeagueManager.queueSize(p) + "&7/" + Cfg.league.i("challenges.queue_max_size", 5));
        }
        long global = LeagueManager.postCooldownRemaining(p);
        if (global > 0) lore.add("&7Poste stabilisé : &c" + TimeFmt.duration(global));
        lore.add("");
        if (LeagueManager.kind(p) != PostKind.MASTER) {
            if (me.badges.containsKey(p.id)) {
                lore.add("&a✔ Badge obtenu le " + TimeFmt.date(me.badges.get(p.id).obtainedAt));
            } else {
                long cd = me.badgeCooldowns.getOrDefault(p.id, 0L) - TimeFmt.now();
                if (cd > 0) lore.add("&cCooldown badge : " + TimeFmt.duration(cd));
                if (LeagueManager.kind(p) == PostKind.ELITE && !LeagueManager.hasAllChampionBadges(me)) {
                    lore.add("&cRequiert les 8 badges de Champions");
                }
            }
            lore.add("&eClic gauche &7: Combat de Badge");
            lore.add("&eClic droit &7: Combat de Place");
        } else {
            lore.add("&eClic &7: Combat de Place");
            if (!(LeagueManager.hasAllChampionBadges(me) && LeagueManager.hasAllEliteBadges(me))) lore.add("&cRequiert les 12 badges");
        }
        if (me.placeCooldownUntil > TimeFmt.now()) lore.add("&cCooldown de place : " + TimeFmt.duration(me.placeCooldownUntil - TimeFmt.now()));
        return b.lore(lore).build();
    }

    // ------------------------------------------------------------------ menu titulaire

    public static void openHolderMenu(ServerPlayer holder) {
        PostData p = LeagueManager.postOf(holder.getUUID());
        if (p == null) {
            Msg.send(holder, "league.err_not_holder");
            return;
        }
        Gui gui = new Gui(4, "&2Mon arène &8- " + LeagueManager.plainName(p));
        gui.set(4, ItemBuilder.of(holderHead(p)).name(LeagueManager.displayName(p)).lore("&7Grade : &f" + LeagueManager.grade(p),
                "&7Refus consécutifs : &f" + p.consecutiveRefusals + "&7/" + Cfg.league.i("inactivity.max_consecutive_refusals", 5)).build());
        if (Perms.player(holder, Perms.ARENA_TOGGLE)) {
            gui.button(10, ItemBuilder.of(p.open ? Items.LIME_CONCRETE : Items.RED_CONCRETE)
                    .name(p.open ? "&a&l[OUVERTE]" : "&c&l[FERMÉE]").lore("&7Cliquez pour basculer").build(), pl -> {
                LeagueManager.setOpen(p, !p.open);
                openHolderMenu(pl);
            });
        }
        if (Perms.player(holder, Perms.ARENA_SETTP)) {
            gui.button(12, ItemBuilder.of(Items.RED_BED).name("&ePoint de TP Titulaire").lore(
                    "&7Actuel : &f" + (p.tpHolder == null ? "non défini" : p.tpHolder.pretty()), "&eCliquez pour le placer ici").build(), pl -> {
                LeagueManager.setTp(p, true, Loc.of(pl));
                Msg.send(pl, "league.tp_set", "point", "Titulaire");
                openHolderMenu(pl);
            });
            gui.button(14, ItemBuilder.of(Items.BLUE_BED).name("&ePoint de TP Challenger").lore(
                    "&7Actuel : &f" + (p.tpChallenger == null ? "non défini" : p.tpChallenger.pretty()), "&eCliquez pour le placer ici").build(), pl -> {
                LeagueManager.setTp(p, false, Loc.of(pl));
                Msg.send(pl, "league.tp_set", "point", "Challenger");
                openHolderMenu(pl);
            });
        }
        LeagueManager.Queued pending = LeagueManager.pending(p);
        if (pending != null) {
            gui.button(16, ItemBuilder.of(Items.LIME_DYE).name("&a&lAccepter le défi").lore("&7" + pending.name() + " - " + LeagueManager.typeLabel(pending.type())).build(),
                    pl -> {
                        pl.closeContainer();
                        LeagueManager.accept(pl);
                    });
            gui.button(25, ItemBuilder.of(Items.RED_DYE).name("&c&lRefuser le défi").lore("&8Les refus répétés libèrent le poste.").build(), pl -> {
                LeagueManager.refuse(pl);
                openHolderMenu(pl);
            });
        } else {
            gui.set(16, ItemBuilder.of(Items.GRAY_DYE).name("&7Aucune demande en attente").build());
        }
        List<LeagueManager.Queued> queue = LeagueManager.queue(p);
        int slot = 19;
        for (LeagueManager.Queued q : queue) {
            if (slot > 23) break;
            gui.set(slot++, ItemBuilder.of(Items.PLAYER_HEAD).head(new GameProfile(q.uuid(), q.name())).name("&f" + q.name())
                    .lore("&7" + LeagueManager.typeLabel(q.type()), "&7Depuis " + TimeFmt.duration(TimeFmt.now() - q.time())).build());
        }
        gui.button(31, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), LeagueGuis::openLeague);
        gui.button(35, ItemBuilder.of(Items.TNT).name("&4Démissionner").lore("&7Libère votre poste.").build(),
                pl -> Guis.confirm(pl, "&4Démissionner ?", "Quitter le poste " + LeagueManager.plainName(p),
                        () -> {
                            LeagueManager.vacate(p, "league.resigned", true);
                        }, () -> openHolderMenu(pl)));
        gui.fill(Gui.pane());
        gui.open(holder);
    }

    // ------------------------------------------------------------------ editeur admin

    public static void openAdmin(ServerPlayer admin) {
        Gui gui = new Gui(6, "&4Éditeur de Ligue");
        gui.set(4, ItemBuilder.of(Items.COMMAND_BLOCK).name("&c&lÉditeur de Ligue").lore("&7Cliquez sur un poste pour le configurer.").build());
        for (PostData p : LeagueManager.posts()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Titulaire : &f" + (p.holderName == null ? "Vacant" : p.holderName));
            lore.add("&7Types : " + LeagueManager.typesLabel(p, true));
            lore.add("&7Arène : " + (p.open ? "&aouverte" : "&cfermée"));
            lore.add("&7TP titulaire : &f" + (p.tpHolder == null ? "-" : p.tpHolder.pretty()));
            lore.add("&7TP challenger : &f" + (p.tpChallenger == null ? "-" : p.tpChallenger.pretty()));
            gui.button(slotOf(p), ItemBuilder.of(holderHead(p)).name(LeagueManager.displayName(p)).lore(lore).build(), pl -> openPostEditor(pl, p));
        }
        gui.button(49, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), LeagueGuis::openLeague);
        gui.fill(Gui.pane());
        gui.open(admin);
    }

    public static void openPostEditor(ServerPlayer admin, PostData p) {
        Gui gui = new Gui(4, "&4Poste &8- " + LeagueManager.plainName(p));
        gui.set(4, ItemBuilder.of(holderHead(p)).name(LeagueManager.displayName(p)).lore("&7Titulaire : &f" + (p.holderName == null ? "Vacant" : p.holderName),
                "&7Grade : &f" + LeagueManager.grade(p)).build());
        gui.button(10, ItemBuilder.of(Items.PLAYER_HEAD).name("&aAssigner un titulaire").lore("&7Choisir parmi les joueurs en ligne",
                "&8(/league admin set " + p.id + " <joueur> pour un joueur hors-ligne)").build(),
                pl -> Guis.pickOnlinePlayer(pl, "&2Choisir le titulaire", target -> {
                    LeagueManager.assign(p, target.getUUID(), target.getGameProfile().getName(), true);
                    openPostEditor(pl, p);
                }, x -> openPostEditor(x, p)));
        gui.button(11, ItemBuilder.of(Items.BARRIER).name("&cLibérer le poste").build(), pl -> Guis.confirm(pl, "&cLibérer ?",
                "Rendre le poste vacant", () -> {
                    LeagueManager.vacate(p, "league.vacated_admin", true);
                    openPostEditor(pl, p);
                }, () -> openPostEditor(pl, p)));
        PostKind k = LeagueManager.kind(p);
        if (k != PostKind.MASTER) {
            gui.button(12, ItemBuilder.of(Items.PAINTING).name("&eDéfinir " + (k == PostKind.ELITE ? "les types (2)" : "le type"))
                    .lore("&7Actuel : " + LeagueManager.typesLabel(p, true)).build(), pl -> openTypePicker(pl, p, new ArrayList<>()));
        }
        gui.button(13, ItemBuilder.of(p.open ? Items.LIME_CONCRETE : Items.RED_CONCRETE).name(p.open ? "&aArène ouverte" : "&cArène fermée")
                .lore("&7Cliquez pour basculer").build(), pl -> {
            LeagueManager.setOpen(p, !p.open);
            openPostEditor(pl, p);
        });
        gui.button(14, ItemBuilder.of(Items.RED_BED).name("&eTP Titulaire ici").lore("&7Actuel : &f" + (p.tpHolder == null ? "-" : p.tpHolder.pretty()),
                "&8Clic droit : s'y téléporter").build(), (pl, c) -> {
            if (c.right() && p.tpHolder != null) {
                p.tpHolder.teleport(pl);
                return;
            }
            LeagueManager.setTp(p, true, Loc.of(pl));
            openPostEditor(pl, p);
        });
        gui.button(15, ItemBuilder.of(Items.BLUE_BED).name("&eTP Challenger ici").lore("&7Actuel : &f" + (p.tpChallenger == null ? "-" : p.tpChallenger.pretty()),
                "&8Clic droit : s'y téléporter").build(), (pl, c) -> {
            if (c.right() && p.tpChallenger != null) {
                p.tpChallenger.teleport(pl);
                return;
            }
            LeagueManager.setTp(p, false, Loc.of(pl));
            openPostEditor(pl, p);
        });
        long cd = LeagueManager.postCooldownRemaining(p);
        gui.button(16, ItemBuilder.of(Items.CLOCK).name("&eRéinitialiser le cooldown global").lore("&7Restant : &f" + (cd > 0 ? TimeFmt.duration(cd) : "aucun")).build(), pl -> {
            LeagueManager.resetPostCooldown(p);
            openPostEditor(pl, p);
        });
        MatchService.Match m = LeagueManager.currentMatch(p);
        if (m != null) {
            gui.button(22, ItemBuilder.of(Items.IRON_SWORD).name("&6Match en cours").lore("&7" + m.n1 + " vs " + m.n2, "&cClic : annuler le match").hideExtra().build(),
                    pl -> {
                        MatchService.abort(m, Msg.format("league.match_cancelled_admin"));
                        openPostEditor(pl, p);
                    });
        }
        gui.button(31, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), LeagueGuis::openAdmin);
        gui.fill(Gui.pane());
        gui.open(admin);
    }

    private static void openTypePicker(ServerPlayer admin, PostData p, List<PokeType> chosen) {
        int needed = LeagueManager.kind(p).typeCount;
        Gui gui = new Gui(3, "&eType " + (chosen.size() + 1) + "/" + needed);
        int i = 0;
        for (PokeType t : PokeType.values()) {
            if (chosen.contains(t)) continue;
            gui.button(i++, ItemBuilder.of(t.icon).name(t.colored()).hideExtra().build(), pl -> {
                List<PokeType> next = new ArrayList<>(chosen);
                next.add(t);
                if (next.size() >= needed) {
                    LeagueManager.setTypes(p, next);
                    Msg.send(pl, "league.types_set", "post", LeagueManager.plainName(p));
                    openPostEditor(pl, p);
                } else {
                    openTypePicker(pl, p, next);
                }
            });
        }
        gui.button(22, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), pl -> openPostEditor(pl, p));
        gui.fill(Gui.pane());
        gui.open(admin);
    }

    // ------------------------------------------------------------------ etui a badges

    public static ItemStack badgeStack(PostData p, PlayerData.BadgeRecord rec) {
        String id = rec != null && rec.itemId != null && ItemBuilder.exists(rec.itemId) ? rec.itemId : LeagueManager.badgeItemId(p);
        return ItemBuilder.of(ItemBuilder.item(id, Items.NETHER_STAR)).build();
    }

    private static String badgeName(PostData p) {
        return switch (LeagueManager.kind(p)) {
            case CHAMPION -> "&6Badge Champion n°" + LeagueManager.number(p);
            case ELITE -> "&5Badge Conseil 4 n°" + LeagueManager.number(p);
            case MASTER -> "&c&lBadge du Maître";
        };
    }

    /**
     * Etui a badges. Le joueur peut admirer ses badges sans pouvoir les deplacer/retirer/jeter (menu verrouille).
     * En mode staff, le survol affiche les metadonnees d'audit et un clic droit retire le badge.
     */
    public static void openBadgeCase(ServerPlayer viewer, UUID target, String targetName, PlayerData d, boolean staff) {
        Gui gui = new Gui(6, (staff ? "&4Inspection &8- " : "&8Étui de ") + targetName);
        int count = d.badges.size();
        StringBuilder bar = new StringBuilder("&a");
        for (int i = 0; i < 13; i++) {
            if (i == count) bar.append("&8");
            bar.append("■");
        }
        gui.set(4, ItemBuilder.of(Items.PLAYER_HEAD).head(Players.get(target) != null ? Players.get(target).getGameProfile() : new GameProfile(target, targetName))
                .name("&e" + targetName).lore("&7Progression : &f" + count + "&7/13", bar.toString(),
                        "&7Champions : &f" + countKind(d, "champion_") + "/8 &7- Conseil 4 : &f" + countKind(d, "elite_") + "/4 &7- Maître : &f"
                                + (d.badges.containsKey("master") ? 1 : 0) + "/1").build());
        gui.set(13, ItemBuilder.of(Items.GOLDEN_HELMET).name("&6Champions").hideExtra().build());
        gui.set(31, ItemBuilder.of(Items.DIAMOND_HELMET).name("&5Conseil 4").hideExtra().build());
        for (PostData p : LeagueManager.posts()) {
            PlayerData.BadgeRecord rec = d.badges.get(p.id);
            int slot = slotOf(p);
            if (rec == null) {
                gui.set(slot, ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name("&8" + TextStrip.of(badgeName(p)) + " &7- non obtenu").build());
                continue;
            }
            ItemBuilder b = ItemBuilder.of(badgeStack(p, rec)).name(badgeName(p)).glow(true).hideExtra();
            b.lore("&7Obtenu le &f" + TimeFmt.date(rec.obtainedAt), "&7Titulaire vaincu : &f" + rec.holderName);
            if (staff) {
                b.lore("&8----------------------",
                        "&7Date exacte : &f" + TimeFmt.date(rec.obtainedAt),
                        "&7Durée du combat : &f" + rec.durationSeconds + "s",
                        "&7Nombre de tours : &f" + rec.turns,
                        "&7Types du poste : &f" + String.join(", ", rec.holderTypes == null ? List.of() : rec.holderTypes),
                        "&7Équipe de " + targetName + " :");
                for (PlayerData.MonEntry mon : rec.challengerTeam) b.lore("  &f- " + mon.species + " &7Nv." + mon.level + (mon.held != null ? " &8(" + mon.held + ")" : ""));
                b.lore("&7Équipe de " + rec.holderName + " :");
                for (PlayerData.MonEntry mon : rec.holderTeam) b.lore("  &f- " + mon.species + " &7Nv." + mon.level + (mon.held != null ? " &8(" + mon.held + ")" : ""));
                if (rec.note != null) b.lore("&6" + rec.note);
                if (rec.battleId != null) b.lore("&8Combat " + rec.battleId);
                if (Perms.staff(viewer, Perms.STAFF_BADGE_REMOVE)) b.lore("", "&cClic droit : retirer ce badge");
            }
            gui.set(slot, b.build(), (pl, click) -> {
                if (!staff || !click.right() || !Perms.staff(pl, Perms.STAFF_BADGE_REMOVE)) return;
                Guis.confirm(pl, "&4Retirer le badge ?", "Retirer " + TextStrip.of(badgeName(p)) + " à " + targetName, () -> {
                    PlayerDataManager.modify(target, targetName, data -> {
                        data.badges.remove(p.id);
                        Msg.send(pl, "league.staff_badge_removed", "player", targetName, "badge", TextStrip.of(badgeName(p)));
                        Msg.send(target, "league.badge_removed_notice", "badge", TextStrip.of(badgeName(p)));
                        fr.cobblepulse.CobblePulse.LOG.info("[CobblePulse] {} a retire le badge {} de {}", pl.getGameProfile().getName(), p.id, targetName);
                        openBadgeCase(pl, target, targetName, data, true);
                    });
                }, () -> openBadgeCase(pl, target, targetName, d, true));
            });
        }
        if (staff) {
            gui.button(47, ItemBuilder.of(Items.WRITABLE_BOOK).name("&eJournal d'audit").lore("&7" + d.audit.size() + " combats officiels").build(),
                    pl -> openAudit(pl, target, targetName, d, 0));
            List<String> cds = new ArrayList<>();
            long now = TimeFmt.now();
            d.badgeCooldowns.forEach((post, until) -> {
                if (until > now) cds.add("&7" + post + " : &c" + TimeFmt.duration(until - now));
            });
            if (d.placeCooldownUntil > now) cds.add("&7Place : &c" + TimeFmt.duration(d.placeCooldownUntil - now));
            if (cds.isEmpty()) cds.add("&aAucun cooldown actif");
            if (Perms.staff(viewer, Perms.STAFF_COOLDOWN_RESET)) cds.add("&eCliquez pour tout réinitialiser");
            gui.button(51, ItemBuilder.of(Items.CLOCK).name("&eCooldowns actifs").lore(cds).build(), pl -> {
                if (!Perms.staff(pl, Perms.STAFF_COOLDOWN_RESET)) return;
                PlayerDataManager.modify(target, targetName, data -> {
                    data.badgeCooldowns.clear();
                    data.placeCooldownUntil = 0;
                    Msg.send(pl, "league.cooldown_reset", "player", targetName);
                    openBadgeCase(pl, target, targetName, data, true);
                });
            });
        } else {
            gui.button(49, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), LeagueGuis::openLeague);
        }
        gui.fill(Gui.pane());
        gui.open(viewer);
    }

    private static int countKind(PlayerData d, String prefix) {
        int c = 0;
        for (String k : d.badges.keySet()) if (k.startsWith(prefix)) c++;
        return c;
    }

    private static void openAudit(ServerPlayer viewer, UUID target, String targetName, PlayerData d, int page) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (PlayerData.AuditEntry e : d.audit) {
            PostData p = LeagueManager.post(e.postId);
            ItemBuilder b = ItemBuilder.of(e.won ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE)
                    .name((e.won ? "&aVictoire" : "&cDéfaite") + " &7- " + ("PLACE".equals(e.kind) ? "Combat de Place" : "Combat de Badge"));
            b.lore("&7Date : &f" + TimeFmt.date(e.time),
                    "&7Poste : &f" + (p == null ? e.postId : LeagueManager.plainName(p)),
                    "&7Rôle : &f" + (e.asChallenger ? "Challenger" : "Titulaire"),
                    "&7Adversaire : &f" + e.opponentName,
                    "&7Durée : &f" + e.durationSeconds + "s &7- Tours : &f" + e.turns + (e.forfeit ? " &6(forfait)" : ""));
            b.lore("&7Son équipe :");
            for (PlayerData.MonEntry m : e.myTeam) b.lore("  &f- " + m.species + " &7Nv." + m.level);
            b.lore("&7Équipe adverse :");
            for (PlayerData.MonEntry m : e.opponentTeam) b.lore("  &f- " + m.species + " &7Nv." + m.level);
            if (e.note != null && !e.note.isEmpty()) b.lore("&8" + e.note);
            entries.add(new Paged.Entry(b.build(), null));
        }
        Paged.open(viewer, "&4Audit &8- " + targetName, entries, page, pl -> openBadgeCase(pl, target, targetName, d, true));
    }

    /** Petit utilitaire de suppression des codes couleur. */
    static final class TextStrip {
        static String of(String s) {
            return fr.cobblepulse.core.TextUtil.strip(s);
        }
    }
}
