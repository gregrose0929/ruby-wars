package dev.rubymod.game.rift;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The look of Ruby Rift: lightning arcs, rings, beams of light, bursts in each element's
 * colour, floating damage numbers and screen effects. Everything flashy goes through
 * here so it all looks of a piece.
 */
public final class RiftFx {
	private RiftFx() {
	}

	public static DustParticleOptions dust(int argb, float size) {
		return new DustParticleOptions(argb & 0xFFFFFF, size);
	}

	public static void at(ServerLevel level, ParticleOptions p, Vec3 pos, int count, double spread, double speed) {
		level.sendParticles(p, true, true, pos.x, pos.y, pos.z, count, spread, spread, spread, speed);
	}

	/** A jagged electric arc between two points. */
	public static void arc(ServerLevel level, Vec3 from, Vec3 to, int color) {
		RandomSource random = level.getRandom();
		Vec3 prev = from;
		int steps = Math.max(4, (int) (from.distanceTo(to) * 1.5));
		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			double jitter = i < steps ? 0.45 : 0;
			Vec3 p = from.lerp(to, t).add((random.nextDouble() - 0.5) * jitter * 2, (random.nextDouble() - 0.5) * jitter * 2,
					(random.nextDouble() - 0.5) * jitter * 2);
			for (int k = 0; k <= 3; k++) {
				Vec3 q = prev.lerp(p, k / 3.0);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, true, q.x, q.y, q.z, 1, 0, 0, 0, 0);
			}
			level.sendParticles(dust(color, 0.9F), true, true, p.x, p.y, p.z, 1, 0, 0, 0, 0);
			prev = p;
		}
	}

	/** A straight line of particles (tracers, leeches, beams between things). */
	public static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions p, double spacing) {
		double len = from.distanceTo(to);
		for (double d = 0; d < len; d += spacing) {
			Vec3 q = from.lerp(to, d / len);
			level.sendParticles(p, true, true, q.x, q.y, q.z, 1, 0, 0, 0, 0);
		}
	}

	/** A flat ring on the ground (shockwaves, warnings, auras). */
	public static void ring(ServerLevel level, Vec3 center, double radius, ParticleOptions p, int points) {
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			level.sendParticles(p, true, true, center.x + Math.cos(a) * radius, center.y, center.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
		}
	}

	/** A column of light from the sky (relics, orbital strikes, portals). */
	public static void beam(ServerLevel level, Vec3 base, double height, ParticleOptions p, int color) {
		for (double y = 0; y < height; y += 0.5) {
			level.sendParticles(p, true, true, base.x, base.y + y, base.z, 1, 0.05, 0, 0.05, 0);
			if (((int) (y * 2)) % 3 == 0) {
				level.sendParticles(dust(color, 1.6F), true, true, base.x, base.y + y, base.z, 1, 0.12, 0, 0.12, 0);
			}
		}
	}

	/** An outward burst in an element's colour. */
	public static void burst(ServerLevel level, Vec3 pos, int color, int count, double size) {
		level.sendParticles(dust(color, 1.8F), true, true, pos.x, pos.y, pos.z, count, size, size, size, 0);
		level.sendParticles(ParticleTypes.END_ROD, true, true, pos.x, pos.y, pos.z, count / 3, size * 0.5, size * 0.5, size * 0.5, 0.15);
	}

	public static void sound(ServerLevel level, Vec3 pos, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	public static void damageNumber(ServerPlayer to, Entity target, float amount, int color) {
		if (amount < 0.5F) {
			return;
		}
		Vec3 p = target.position().add((target.getRandom().nextDouble() - 0.5) * 0.6, target.getBbHeight() + 0.3, (target.getRandom().nextDouble() - 0.5) * 0.6);
		Net.send(to, new MorePayloads.DamageNumber((float) p.x, (float) p.y, (float) p.z, amount, color));
	}

	public static void screen(ServerPlayer player, String kind, int color, int ticks) {
		Net.send(player, new MorePayloads.ScreenFx(kind, color, ticks));
	}

	public static void shake(ServerPlayer player, float strength, int ticks) {
		Net.send(player, new Payloads.Shake(strength, ticks));
	}
}
