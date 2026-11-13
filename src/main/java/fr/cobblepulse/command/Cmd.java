package fr.cobblepulse.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.cobblepulse.core.Msg;
import fr.cobblepulse.league.LeagueManager;
import fr.cobblepulse.league.PokeType;
import fr.cobblepulse.storage.PlayerDataManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Utilitaires partages par les commandes. */
public final class Cmd {
    public static final SuggestionProvider<CommandSourceStack> ONLINE =
            (ctx, b) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), b);

    public static final SuggestionProvider<CommandSourceStack> POSTS = (ctx, b) -> {
        List<String> ids = new ArrayList<>();
        LeagueManager.posts().forEach(p -> ids.add(p.id));
        return SharedSuggestionProvider.suggest(ids, b);
    };

    public static final SuggestionProvider<CommandSourceStack> TYPES = (ctx, b) -> {
        List<String> ids = new ArrayList<>();
        for (PokeType t : PokeType.values()) ids.add(t.id);
        return SharedSuggestionProvider.suggest(ids, b);
    };

    private Cmd() {}

    /** Resout un joueur (en ligne ou hors-ligne connu du serveur). */
    public static boolean profile(CommandSourceStack src, String name, Consumer<GameProfile> then) {
        Optional<GameProfile> gp = PlayerDataManager.resolve(src.getServer(), name);
        if (gp.isEmpty() || gp.get().getId() == null) {
            Msg.send(src, "general.player_not_found", "player", name);
            return false;
        }
        then.accept(gp.get());
        return true;
    }
}
