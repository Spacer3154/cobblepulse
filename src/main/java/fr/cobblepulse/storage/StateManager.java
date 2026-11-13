package fr.cobblepulse.storage;

import fr.cobblepulse.core.Json;
import fr.cobblepulse.data.MiscState;

/** Etat global annexe (guerres, versement d'influence, vainqueur de tournoi, arenes de tournoi). */
public final class StateManager {
    private static MiscState state = new MiscState();

    private StateManager() {}

    public static void load() {
        String json = DataService.blocking(s -> s.loadKv("state"));
        MiscState loaded = json == null ? null : Json.GSON.fromJson(json, MiscState.class);
        state = loaded != null ? loaded : new MiscState();
        if (state.wars == null) state.wars = new java.util.ArrayList<>();
        if (state.warCooldowns == null) state.warCooldowns = new java.util.HashMap<>();
        if (state.tournamentArenas == null) state.tournamentArenas = new java.util.ArrayList<>();
    }

    public static MiscState get() {
        return state;
    }

    public static void save() {
        DataService.markKv("state", () -> state);
    }
}
