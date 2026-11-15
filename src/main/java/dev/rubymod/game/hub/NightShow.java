package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.mixin.DisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The park after dark: when night falls there's a big fireworks show over the whole park, the Sky Parade (glowing giant
 * toys floating slowly round the plaza all night), and the rides light up (the coaster's track glows). Nothing to be on
 * time for: it's just there when you're about at night.
 */
final class NightShow {
	private NightShow() {
	}

	/** Displays that glow at night (the coaster's rails, the top of the Swings). */
	private static final List<UUID> GLOW = new ArrayList<>();
	private static final List<UUID> FLOATS = new ArrayList<>();
	private static boolean wasNight;
	private static int showUntil;
	/** Where the show's fireworks go up from. */
	private static final double[][] LAUNCH = {{0, 0}, {30, -100}, {74, -100}, {84, 16}, {110, 26}, {-110, 0}, {-44, 44}, {44, 40}, {6, 66}};
	private static final String[] FLOAT_MODELS = {"teddy", "duck", "swan", "horse_gold", "balloon", "ghost", "teacup_pink"};

	static void glow(Entity e) {
		if (e != null) {
			GLOW.add(e.getUUID());
		}
	}

	static void tick(ServerLevel level, int tick, boolean night) {
		if (night != wasNight) {
			wasNight = night;
			lights(level, night);
			if (night) {
				showUntil = tick + 60 * 20;
				for (ServerPlayer p : level.players()) {
					p.sendSystemMessage(Component.literal("★ The night show's starting! Look up! ★").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
				}
				parade(level, true);
			} else {
				parade(level, false);
			}
		}
		// The fireworks: rockets streaking up all over the park, bursting high, faster towards the finale.
		if (tick < showUntil) {
			int left = showUntil - tick;
			int every = left < 200 ? 3 : 9;
			if (tick % every == 0) {
				var random = level.getRandom();
				double[] l = LAUNCH[random.nextInt(LAUNCH.length)];
				Vec3 from = new Vec3(l[0] + random.nextGaussian() * 8, FLOOR + 2, l[1] + random.nextGaussian() * 8);
				Vec3 at = from.add(0, 30 + random.nextDouble() * 18, 0);
				for (double y = from.y; y < at.y; y += 1.5) {
					level.sendParticles(ParticleTypes.FIREWORK, from.x, y, from.z, 1, 0.05, 0.2, 0.05, 0.01);
				}
				level.playSound(null, BlockPos.containing(from), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 2.0F, 0.9F);
				HubPark.burst(level, at);
			}
		}
		// The Sky Parade: round the plaza, slowly, all night.
		if (!FLOATS.isEmpty() && tick % 2 == 0) {
			for (int i = 0; i < FLOATS.size(); i++) {
				Entity f = level.getEntity(FLOATS.get(i));
				if (f == null) {
					continue;
				}
				double a = tick * 0.0025 + i * Math.PI * 2 / FLOATS.size();
				double y = FLOOR + 14 + Math.sin(tick * 0.03 + i) * 1.2;
				f.teleportTo(Math.cos(a) * 36, y, Math.sin(a) * 36);
				f.setYRot((float) Math.toDegrees(a) + 180);
				if (tick % 10 == 0) {
					level.sendParticles(ParticleTypes.END_ROD, f.getX(), y - 0.5, f.getZ(), 3, 0.6, 0.3, 0.6, 0.01);
				}
			}
		}
	}

	private static void parade(ServerLevel level, boolean on) {
		for (UUID id : FLOATS) {
			HubPark.discard(level, id);
		}
		FLOATS.clear();
		if (!on) {
			return;
		}
		for (String m : FLOAT_MODELS) {
			Display.ItemDisplay d = HubPark.tempModel(level, new Vec3(0, FLOOR + 14, 36), 0, m, 5.0F, 0);
			if (d != null) {
				((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
				((DisplayAccessor) d).rubymod$setViewRange(12.0F);
				FLOATS.add(d.getUUID());
			}
		}
	}

	private static void lights(ServerLevel level, boolean on) {
		for (UUID id : GLOW) {
			if (level.getEntity(id) instanceof Display d) {
				((DisplayAccessor) d).rubymod$setBrightness(on ? new net.minecraft.util.Brightness(15, 15) : null);
			}
		}
	}

	static void reset() {
		GLOW.clear();
		FLOATS.clear();
		wasNight = false;
		showUntil = 0;
	}
}
