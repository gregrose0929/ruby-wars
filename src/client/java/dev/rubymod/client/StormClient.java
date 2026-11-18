package dev.rubymod.client;

import dev.rubymod.game.Storms;
import dev.rubymod.world.WarzoneTerrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

/** What a storm looks like from inside: thick fog in its colour, and sand or snow blowing past. */
public final class StormClient {
	private StormClient() {
	}

	/** How thick the fog is right now (0 = none, 1 = full storm): eases in and out as you walk in and out. */
	private static float thickness;

	private static boolean inside(Minecraft mc) {
		Entity cam = mc.getCameraEntity();
		return cam != null && Storms.inStorm(mc.level, cam.getX(), cam.getZ());
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			thickness = 0;
			return;
		}
		boolean in = inside(mc);
		thickness = Math.max(0, Math.min(1, thickness + (in ? 0.03F : -0.05F)));
		if (!in || mc.isPaused()) {
			return;
		}
		Entity cam = mc.getCameraEntity();
		var random = mc.level.getRandom();
		boolean snow = Storms.theme() == WarzoneTerrain.THEME_SNOW;
		// The wind blows from the middle of the map outward.
		double wx = cam.getX() / Math.max(1, Math.hypot(cam.getX(), cam.getZ()));
		double wz = cam.getZ() / Math.max(1, Math.hypot(cam.getX(), cam.getZ()));
		for (int i = 0; i < 40; i++) {
			double x = cam.getX() + random.nextGaussian() * 10;
			double y = cam.getY() + random.nextDouble() * 8 - 2;
			double z = cam.getZ() + random.nextGaussian() * 10;
			if (snow) {
				mc.level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, wx * 0.6, -0.15, wz * 0.6);
			} else {
				mc.level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()), x, y, z, 0, 0, 0);
				if (i % 4 == 0) {
					mc.level.addParticle(ParticleTypes.WHITE_ASH, x, y, z, wx * 0.5, 0, wz * 0.5);
				}
			}
		}
	}

	/** Thicken the fog (FogRenderer.setupFog's answer), when you're in a storm. */
	public static void fog(FogData fog) {
		if (thickness <= 0) {
			return;
		}
		boolean snow = Storms.theme() == WarzoneTerrain.THEME_SNOW;
		float end = snow ? 26 : 20;
		float t = thickness;
		fog.environmentalStart = lerp(fog.environmentalStart, 0, t);
		fog.environmentalEnd = lerp(fog.environmentalEnd, end, t);
		fog.renderDistanceStart = lerp(fog.renderDistanceStart, 0, t);
		fog.renderDistanceEnd = lerp(fog.renderDistanceEnd, end, t);
		fog.skyEnd = lerp(fog.skyEnd, end, t);
		fog.cloudEnd = lerp(fog.cloudEnd, end, t);
		float r = snow ? 0.86F : 0.78F;
		float g = snow ? 0.89F : 0.62F;
		float b = snow ? 0.94F : 0.40F;
		fog.color.set(lerp(fog.color.x, r, t), lerp(fog.color.y, g, t), lerp(fog.color.z, b, t), fog.color.w);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** For autotests. */
	public static float thickness() {
		return thickness;
	}
}
