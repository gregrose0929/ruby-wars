package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Flares. An aircraft drops a burst of hot flares behind it; a missile homing
 * on that aircraft that passes near a fresh flare may chase the flare instead.
 */
public final class Countermeasures {
	private record Flare(Entity dropper, Vec3 pos, long expires, Set<Integer> tested) {
	}

	private static final List<Flare> FLARES = new ArrayList<>();
	private static final double DECOY_RANGE = 40;
	private static final float DECOY_CHANCE = 0.75F;

	private Countermeasures() {
	}

	public static void dropFlares(ServerLevel level, Entity aircraft) {
		Vec3 behind = aircraft.position().add(aircraft.getDeltaMovement().scale(-3)).add(0, -0.5, 0);
		FLARES.add(new Flare(aircraft, behind, level.getGameTime() + 60, new HashSet<>()));
		for (int i = 0; i < 6; i++) {
			level.sendParticles(ParticleTypes.FLAME, behind.x, behind.y - i * 0.6, behind.z, 6, 0.6, 0.3, 0.6, 0.02);
			level.sendParticles(ParticleTypes.FIREWORK, behind.x, behind.y - i * 0.6, behind.z, 4, 0.8, 0.3, 0.8, 0.05);
		}
		level.playSound(null, behind.x, behind.y, behind.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 2.0F, 1.4F);
	}

	/** If a flare from {@code target} is near the missile, maybe return the flare's position to chase instead. */
	public static Vec3 decoyFor(ServerLevel level, Entity target, Entity missile) {
		long now = level.getGameTime();
		FLARES.removeIf(f -> f.expires() < now);
		Entity aircraft = target.getVehicle() != null ? target.getVehicle() : target;
		for (Flare flare : FLARES) {
			if (flare.dropper() != aircraft && flare.dropper() != target) {
				continue;
			}
			if (flare.pos().distanceTo(missile.position()) > DECOY_RANGE || !flare.tested().add(missile.getId())) {
				continue;
			}
			if (level.getRandom().nextFloat() < DECOY_CHANCE) {
				return flare.pos();
			}
		}
		return null;
	}
}
