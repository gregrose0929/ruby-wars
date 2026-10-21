package dev.rubymod.client;

import java.util.HashMap;
import java.util.Map;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.registry.ModSounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * Engine sounds: every crewed (or moving) tank, jeep, jet and helicopter nearby
 * runs a looping engine whose pitch and volume follow its speed and throttle.
 */
public final class EngineSounds {
	private static final Map<Integer, Engine> PLAYING = new HashMap<>();

	private EngineSounds() {
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			PLAYING.clear();
			return;
		}
		PLAYING.values().removeIf(Engine::isStopped);
		if (mc.level.getGameTime() % 10 != 0) {
			return;
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof VehicleEntity || e instanceof AircraftEntity) || PLAYING.containsKey(e.getId()) || !running(e)
					|| e.distanceToSqr(mc.player) > 160 * 160) {
				continue;
			}
			Engine engine = new Engine(e);
			PLAYING.put(e.getId(), engine);
			mc.getSoundManager().play(engine);
		}
	}

	private static boolean running(Entity e) {
		return e.isAlive() && (e.isVehicle() || e.getDeltaMovement().horizontalDistanceSqr() > 0.0004 || !e.onGround());
	}

	private static SoundEvent soundFor(Entity e) {
		if (e instanceof VehicleEntity v) {
			return v.isTank() ? ModSounds.TANK_ENGINE : ModSounds.JEEP_ENGINE;
		}
		return ((AircraftEntity) e).kind() == AircraftType.HELICOPTER ? ModSounds.HELI_ROTOR : ModSounds.JET_ENGINE;
	}

	private static final class Engine extends AbstractTickableSoundInstance {
		private final Entity entity;
		private float level;
		private int idleTicks;

		Engine(Entity entity) {
			super(soundFor(entity), SoundSource.NEUTRAL, RandomSource.create());
			this.entity = entity;
			this.looping = true;
			this.delay = 0;
			this.volume = 0.01F;
			this.pitch = 0.6F;
			this.x = entity.getX();
			this.y = entity.getY();
			this.z = entity.getZ();
		}

		@Override
		public void tick() {
			if (!entity.isAlive() || entity.isRemoved()) {
				stop();
				return;
			}
			x = entity.getX();
			y = entity.getY();
			z = entity.getZ();
			double speed = entity.getDeltaMovement().length() * 20.0; // blocks per second
			float target;
			float targetPitch;
			if (entity instanceof AircraftEntity a) {
				float throttle = a.throttle();
				boolean heli = a.kind() == AircraftType.HELICOPTER;
				target = entity.isVehicle() || !entity.onGround() ? 0.55F + 0.45F * (heli ? 1.0F : throttle) : 0.0F;
				targetPitch = heli ? 0.85F + (float) Math.min(0.3, speed / 120.0) : 0.6F + 0.7F * throttle;
			} else {
				target = entity.isVehicle() ? 0.45F + (float) Math.min(0.55, speed / 14.0) : 0.0F;
				targetPitch = 0.75F + (float) Math.min(0.6, speed / 16.0);
			}
			level = Mth.lerp(0.08F, level, target);
			volume = Math.max(0.0F, level);
			pitch = Mth.lerp(0.1F, pitch, targetPitch);
			if (target <= 0.0F && level < 0.02F && ++idleTicks > 20) {
				stop();
			} else if (target > 0.0F) {
				idleTicks = 0;
			}
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
