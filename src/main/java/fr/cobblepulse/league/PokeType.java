package fr.cobblepulse.league;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** Les 18 types Pokemon (identifiant Cobblemon, nom francais, couleur, icone). */
public enum PokeType {
    NORMAL("normal", "Normal", "&f", Items.WHITE_WOOL),
    FIRE("fire", "Feu", "&c", Items.BLAZE_POWDER),
    WATER("water", "Eau", "&9", Items.HEART_OF_THE_SEA),
    GRASS("grass", "Plante", "&a", Items.OAK_SAPLING),
    ELECTRIC("electric", "Électrik", "&e", Items.GLOWSTONE_DUST),
    ICE("ice", "Glace", "&b", Items.PACKED_ICE),
    FIGHTING("fighting", "Combat", "&6", Items.IRON_SWORD),
    POISON("poison", "Poison", "&5", Items.SPIDER_EYE),
    GROUND("ground", "Sol", "&6", Items.COARSE_DIRT),
    FLYING("flying", "Vol", "&7", Items.FEATHER),
    PSYCHIC("psychic", "Psy", "&d", Items.ENDER_PEARL),
    BUG("bug", "Insecte", "&2", Items.COBWEB),
    ROCK("rock", "Roche", "&6", Items.COBBLESTONE),
    GHOST("ghost", "Spectre", "&5", Items.PHANTOM_MEMBRANE),
    DRAGON("dragon", "Dragon", "&1", Items.DRAGON_BREATH),
    DARK("dark", "Ténèbres", "&8", Items.INK_SAC),
    STEEL("steel", "Acier", "&7", Items.IRON_INGOT),
    FAIRY("fairy", "Fée", "&d", Items.PINK_DYE);

    public final String id;
    public final String fr;
    public final String color;
    public final Item icon;

    PokeType(String id, String fr, String color, Item icon) {
        this.id = id;
        this.fr = fr;
        this.color = color;
        this.icon = icon;
    }

    public String colored() {
        return color + fr;
    }

    public static PokeType of(String s) {
        if (s == null) return null;
        String v = s.toLowerCase(Locale.ROOT).trim();
        for (PokeType t : values()) {
            if (t.id.equals(v) || t.name().equalsIgnoreCase(v) || stripAccents(t.fr).equalsIgnoreCase(stripAccents(v))) return t;
        }
        return null;
    }

    private static String stripAccents(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
