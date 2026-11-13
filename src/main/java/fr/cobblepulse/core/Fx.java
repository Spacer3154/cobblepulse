package fr.cobblepulse.core;

import fr.cobblepulse.config.Cfg;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Effets visuels et sonores (titres, particules d'entree en arene). */
public final class Fx {
    private Fx() {}

    public static void title(ServerPlayer p, String title, String subtitle) {
        if (p == null || p.connection == null) return;
        try {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(4, 22, 6));
            p.connection.send(new ClientboundSetSubtitleTextPacket(TextUtil.legacy(subtitle == null ? "" : subtitle)));
            p.connection.send(new ClientboundSetTitleTextPacket(TextUtil.legacy(title == null ? "" : title)));
        } catch (Throwable ignored) {
        }
    }

    public static void sound(ServerPlayer p, SoundEvent sound, float pitch) {
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, 0.8f, pitch);
    }

    public static void click(ServerPlayer p) {
        sound(p, SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
    }

    /** Effet d'entree en arene reserve aux joueurs ayant cobblepulse.cosmetic.arena_effect. */
    public static void arenaEntrance(ServerPlayer p) {
        if (!Perms.vip(p, Perms.ARENA_EFFECT)) return;
        ParticleOptions particle = ParticleTypes.TOTEM_OF_UNDYING;
        ResourceLocation id = ResourceLocation.tryParse(Cfg.league.str("cosmetics.arena_particle", "minecraft:totem_of_undying"));
        if (id != null && BuiltInRegistries.PARTICLE_TYPE.containsKey(id)
                && BuiltInRegistries.PARTICLE_TYPE.get(id) instanceof SimpleParticleType simple) {
            particle = simple;
        }
        p.serverLevel().sendParticles(particle, p.getX(), p.getY() + 1.0, p.getZ(),
                Math.max(1, Cfg.league.i("cosmetics.arena_particle_count", 60)), 0.6, 1.0, 0.6, 0.25);
        ResourceLocation sid = ResourceLocation.tryParse(Cfg.league.str("cosmetics.arena_sound", "minecraft:entity.player.levelup"));
        SoundEvent sound = sid != null && BuiltInRegistries.SOUND_EVENT.containsKey(sid) ? BuiltInRegistries.SOUND_EVENT.get(sid) : SoundEvents.PLAYER_LEVELUP;
        sound(p, sound, 1.0f);
    }
}
