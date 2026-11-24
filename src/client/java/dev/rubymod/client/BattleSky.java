package dev.rubymod.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Battle Day sky over the Ruby Wars world: the sky turns a smoky red, ash drifts down, and artillery flashes low on
 * the horizon with the boom arriving a moment later. Just for looks (on Battle Day, in the season's world).
 */
public final class BattleSky {
	private BattleSky() {
	}

	/** For autotests: Battle Day sky whatever the day. */
	public static boolean forced;
	/** How red it is (eases in and out). */
	private static float redness;
	private record Boom(Vec3 at, int when) {
	}
	private static final List<Boom> BOOMS = new ArrayList<>();
	private static int tick;

	/** Never (the help films, filmed in Open War, keep a plain sky). */
	public static boolean off;

	private static boolean on(Minecraft mc) {
		return !off && mc.level != null && mc.level.dimension() == Level.OVERWORLD && (forced || ClientState.inRubyWarsWorld() && ClientState.phase().isBattle());
	}

	public static void tick(Minecraft mc) {
		boolean on = on(mc);
		redness = Math.max(0, Math.min(1, redness + (on ? 0.01F : -0.02F)));
		if (!on || mc.player == null || mc.isPaused()) {
			BOOMS.clear();
			return;
		}
		tick++;
		var random = mc.level.getRandom();
		Vec3 eye = mc.player.getEyePosition();
		// Ash, drifting down all round.
		for (int i = 0; i < 12; i++) {
			mc.level.addParticle(ParticleTypes.WHITE_ASH, eye.x + random.nextGaussian() * 12, eye.y + random.nextDouble() * 10 - 2, eye.z + random.nextGaussian() * 12,
					0, -0.02, 0);
		}
		if (tick % 3 == 0) {
			mc.level.addParticle(ParticleTypes.ASH, eye.x + random.nextGaussian() * 10, eye.y + random.nextDouble() * 8, eye.z + random.nextGaussian() * 10, 0, 0, 0);
		}
		// Now and then, a gun far off: a flash low on the horizon, then the boom (sound takes its time to arrive).
		if (random.nextInt(70) == 0) {
			double a = random.nextDouble() * Math.PI * 2;
			double far = 80 + random.nextDouble() * 50;
			Vec3 at = eye.add(Math.cos(a) * far, 6 + random.nextDouble() * 18, Math.sin(a) * far);
			for (int k = 0; k < 3; k++) {
				mc.level.addParticle(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFB060), at.x + random.nextGaussian() * 2, at.y + random.nextGaussian(), at.z + random.nextGaussian() * 2, 0, 0, 0);
			}
			BOOMS.add(new Boom(at, tick + (int) (far / 17.0 * 20.0 / 10.0) + 20));
		}
		for (Iterator<Boom> it = BOOMS.iterator(); it.hasNext();) {
			Boom b = it.next();
			if (tick >= b.when()) {
				it.remove();
				// Heard from far away: low and quiet, from that direction.
				Vec3 near = eye.add(b.at().subtract(eye).normalize().scale(14));
				mc.level.playLocalSound(near.x, near.y, near.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 0.6F, 0.45F + random.nextFloat() * 0.1F, false);
			}
		}
	}

	/** Redden the sky (SkyRenderer.extractRenderState's answer). */
	public static void sky(SkyRenderState sky) {
		if (redness <= 0 || sky.skyColor == null) {
			return;
		}
		float t = redness * 0.8F;
		sky.skyColor = new Vector3f(lerp(sky.skyColor.x(), 0.55F, t), lerp(sky.skyColor.y(), 0.13F, t), lerp(sky.skyColor.z(), 0.08F, t));
		if (sky.sunriseAndSunsetColor != null) {
			Vector4f s = new Vector4f(sky.sunriseAndSunsetColor);
			sky.sunriseAndSunsetColor = s.set(lerp(s.x, 0.9F, t), lerp(s.y, 0.25F, t), lerp(s.z, 0.1F, t), s.w);
		}
	}

	/** And the haze near the horizon (the fog colour). */
	public static void fog(FogData fog) {
		if (redness <= 0) {
			return;
		}
		float t = redness * 0.6F;
		fog.color.set(lerp(fog.color.x, 0.5F, t), lerp(fog.color.y, 0.2F, t), lerp(fog.color.z, 0.14F, t), fog.color.w);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** For autotests. */
	public static float redness() {
		return redness;
	}
}
