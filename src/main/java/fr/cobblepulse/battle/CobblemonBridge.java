package fr.cobblepulse.battle;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleStartError;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import fr.cobblepulse.CobblePulse;
import fr.cobblepulse.data.PlayerData.MonEntry;
import kotlin.Unit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Unique point de contact avec l'API Cobblemon (combats, equipes, evenements).
 */
public final class CobblemonBridge {
    /** Resultat d'un combat entre joueurs. */
    public record PvpResult(UUID battleId, List<UUID> winners, List<UUID> losers, int turns) {}

    /** Resume d'un Pokemon de l'equipe (validation des formats de tournoi). */
    public record MonInfo(String species, String speciesId, String formId, int level, String heldItem, String ability,
                          List<String> moves, Set<String> types, boolean hasPreEvolution, boolean canEvolve, boolean shiny) {}

    private static final List<Consumer<PvpResult>> LISTENERS = new CopyOnWriteArrayList<>();

    private CobblemonBridge() {}

    public static void onPvpResult(Consumer<PvpResult> listener) {
        LISTENERS.add(listener);
    }

    public static void clearListeners() {
        LISTENERS.clear();
    }

    public static void register() {
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            try {
                handleVictory(event);
            } catch (Throwable t) {
                CobblePulse.LOG.error("[CobblePulse] Erreur dans BATTLE_VICTORY", t);
            }
            return Unit.INSTANCE;
        });
    }

    private static void handleVictory(BattleVictoryEvent event) {
        PokemonBattle battle = event.getBattle();
        if (!battle.isPvP()) return;
        List<UUID> winners = playerIds(event.getWinners());
        List<UUID> losers = playerIds(event.getLosers());
        if (winners.isEmpty() && losers.isEmpty()) return;
        PvpResult r = new PvpResult(battle.getBattleId(), winners, losers, battle.getTurn());
        for (Consumer<PvpResult> l : LISTENERS) {
            try {
                l.accept(r);
            } catch (Throwable t) {
                CobblePulse.LOG.error("[CobblePulse] Erreur de traitement d'un resultat de combat", t);
            }
        }
    }

    private static List<UUID> playerIds(List<? extends BattleActor> actors) {
        List<UUID> out = new ArrayList<>();
        if (actors == null) return out;
        for (BattleActor a : actors) {
            for (UUID id : a.getPlayerUUIDs()) out.add(id);
        }
        return out;
    }

    /** Lance un combat 1v1 officiel. @return identifiant du combat, ou null avec un message d'erreur dans errorOut. */
    public static UUID startPvp(ServerPlayer p1, ServerPlayer p2, boolean heal, String format, StringBuilder errorOut) {
        try {
            if (heal) {
                party(p1).heal();
                party(p2).heal();
            }
            BattleFormat fmt = "doubles".equalsIgnoreCase(format) ? BattleFormat.Companion.getGEN_9_DOUBLES() : BattleFormat.Companion.getGEN_9_SINGLES();
            BattleStartResult result = BattleBuilder.INSTANCE.pvp1v1(p1, p2, null, null, fmt, false, false,
                    player -> Cobblemon.INSTANCE.getStorage().getParty(player));
            if (result instanceof SuccessfulBattleStart ok) {
                return ok.getBattle().getBattleId();
            }
            if (result instanceof ErroredBattleStart err) {
                List<String> msgs = new ArrayList<>();
                for (BattleStartError e : err.getErrors()) {
                    try {
                        msgs.add(e.getMessageFor(p1).getString());
                    } catch (Throwable t) {
                        msgs.add(String.valueOf(e));
                    }
                }
                errorOut.append(String.join(", ", msgs));
                return null;
            }
            errorOut.append("resultat inconnu");
            return null;
        } catch (Throwable t) {
            CobblePulse.LOG.error("[CobblePulse] Lancement du combat impossible", t);
            errorOut.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            return null;
        }
    }

    public static boolean isInBattle(ServerPlayer p) {
        try {
            return BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(p) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean battleActive(UUID battleId) {
        if (battleId == null) return false;
        PokemonBattle b = BattleRegistry.INSTANCE.getBattle(battleId);
        return b != null && !b.getEnded();
    }

    /** Termine un combat sans tenir compte de son issue (forfait gere par CobblePulse). */
    public static void stopBattle(UUID battleId) {
        if (battleId == null) return;
        try {
            PokemonBattle b = BattleRegistry.INSTANCE.getBattle(battleId);
            if (b != null && !b.getEnded()) b.stop();
        } catch (Throwable t) {
            CobblePulse.LOG.warn("[CobblePulse] Arret du combat {} impossible : {}", battleId, t.toString());
        }
    }

    /** Force la victoire d'un cote (auto-test) : side = "p1" ou "p2". */
    public static void forceWin(UUID battleId, String side) {
        PokemonBattle b = BattleRegistry.INSTANCE.getBattle(battleId);
        if (b != null) b.writeShowdownAction(">forcewin " + side);
    }

    public static int turn(UUID battleId) {
        PokemonBattle b = battleId == null ? null : BattleRegistry.INSTANCE.getBattle(battleId);
        return b == null ? 0 : b.getTurn();
    }

    public static PlayerPartyStore party(ServerPlayer p) {
        return Cobblemon.INSTANCE.getStorage().getParty(p);
    }

    public static List<Pokemon> partyList(ServerPlayer p) {
        List<Pokemon> out = new ArrayList<>();
        for (Pokemon pk : party(p)) {
            if (pk != null) out.add(pk);
        }
        return out;
    }

    /** Composition d'equipe (especes et niveaux) pour le journal d'audit. */
    public static List<MonEntry> team(ServerPlayer p) {
        List<MonEntry> out = new ArrayList<>();
        try {
            for (Pokemon pk : partyList(p)) {
                ItemStack held = pk.heldItem();
                String heldId = held == null || held.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                out.add(new MonEntry(pk.getSpecies().getName(), pk.getLevel(), heldId, pk.getShiny()));
            }
        } catch (Throwable t) {
            CobblePulse.LOG.warn("[CobblePulse] Lecture de l'equipe de {} impossible : {}", p.getGameProfile().getName(), t.toString());
        }
        return out;
    }

    public static List<MonInfo> teamInfo(ServerPlayer p) {
        List<MonInfo> out = new ArrayList<>();
        for (Pokemon pk : partyList(p)) out.add(info(pk));
        return out;
    }

    public static MonInfo info(Pokemon pk) {
        Species sp = pk.getSpecies();
        FormData form = pk.getForm();
        String speciesId = norm(sp.getName());
        String formName = form == null ? "" : form.getName();
        String formId = formName == null || formName.isEmpty() || formName.equalsIgnoreCase("normal") ? speciesId : norm(sp.getName() + formName);
        ItemStack held = pk.heldItem();
        String heldId = held == null || held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        String ability = "";
        try {
            ability = pk.getAbility().getName();
        } catch (Throwable ignored) {
        }
        List<String> moves = new ArrayList<>();
        try {
            for (Move m : pk.getMoveSet().getMoves()) moves.add(m.getName());
        } catch (Throwable ignored) {
        }
        Set<String> types = new LinkedHashSet<>();
        for (ElementalType t : pk.getTypes()) types.add(t.getName().toLowerCase(Locale.ROOT));
        boolean pre = false;
        boolean evo = false;
        try {
            pre = form.getPreEvolution() != null;
            evo = !form.getEvolutions().isEmpty();
        } catch (Throwable ignored) {
        }
        return new MonInfo(sp.getName(), speciesId, formId, pk.getLevel(), heldId, ability, moves, types, pre, evo, pk.getShiny());
    }

    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Icone 3D du Pokemon (rendue par Cobblemon cote client). */
    public static ItemStack icon(Pokemon pk) {
        try {
            return PokemonItem.from(pk);
        } catch (Throwable t) {
            return new ItemStack(net.minecraft.world.item.Items.EGG);
        }
    }

    public static ItemStack speciesIcon(String speciesName) {
        try {
            Species sp = PokemonSpecies.INSTANCE.getByName(norm(speciesName));
            if (sp != null) return PokemonItem.from(sp);
        } catch (Throwable ignored) {
        }
        return new ItemStack(net.minecraft.world.item.Items.EGG);
    }

    /** Ajoute un Pokemon a l'equipe d'un joueur (auto-test). */
    public static void givePokemon(ServerPlayer p, String properties) {
        Pokemon pk = PokemonProperties.Companion.parse(properties).create();
        party(p).add(pk);
    }
}
