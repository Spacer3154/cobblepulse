package fr.cobblepulse.integration;

import fr.cobblepulse.CobblePulse;
import net.fabricmc.loader.api.FabricLoader;

/** Enregistre les placeholders si Text Placeholder API est installe (utilise par ShadowChat). */
public final class PlaceholderBridge {
    private PlaceholderBridge() {}

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            CobblePulse.LOG.info("[CobblePulse] Placeholder API absent : tag de clan non expose a ShadowChat");
            return;
        }
        try {
            PlaceholderHook.register();
            CobblePulse.LOG.info("[CobblePulse] Placeholders enregistres (%cobblepulse:clan_tag% ...)");
        } catch (Throwable t) {
            CobblePulse.LOG.warn("[CobblePulse] Enregistrement des placeholders impossible : {}", t.toString());
        }
    }
}
