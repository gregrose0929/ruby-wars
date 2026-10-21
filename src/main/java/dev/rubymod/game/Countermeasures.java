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
 * Flares and smoke. An aircraft drops a burst of hot flares behind it; a missile
 * homing on that aircraft that passes near a fresh flare may chase the flare
 * instead. A tank pops a smoke screen around itself: while a target sits in
 * smoke, missiles lose their lock and launchers can't lock it.
 */
public final class Countermeasures {
	private record Flare(Entity dropper, Vec3 pos, long expires, Set<Integer> tested) {
	}

	private static final List<Flare> FLARES = new ArrayList<>();

	private record Smoke(ServerLevel level, Vec3 pos, double radius, long expires) {
	}

	private static final List<Smoke> SMOKE = new ArrayList<>();

	/** A smoke screen of {@code radius} around {@code pos} for {@code ticks}. */
	public static void smokeScreen(ServerLevel level, Vec3 pos, double radius, int ticks) {
		SMOKE.add(new Smoke(level, pos, radius, level.getGameTime() + ticks));
		level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 2.0F, 0.5F);
	}

	/** Is this entity hidden in smoke right now? */
	public static boolean obscured(ServerLevel level, Entity target) {
		Vec3 p = target.getBoundingBox().getCenter();
		for (Smoke s : SMOKE) {
			if (s.level() == level && s.pos().distanceTo(p) < s.radius() && level.getGameTime() < s.expires()) {
				return true;
			}
		}
		return false;
	}

	/** Every server tick: thick smoke for the screens, and forget old ones. */
	public static void tick() {
		SMOKE.removeIf(s -> s.level().getGameTime() >= s.expires());
		for (Smoke s : SMOKE) {
			if (s.level().getGameTime() % 3 == 0) {
				s.level().sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, false, s.pos().x, s.pos().y + 1, s.pos().z, 6,
						s.radius() * 0.5, 1.2, s.radius() * 0.5, 0.01);
			}
		}
	}
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
