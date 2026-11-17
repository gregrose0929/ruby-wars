package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.HashMap;
import java.util.Map;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Little bits of magic: fireflies drifting over the grass at night, and a rainbow over Splash Island on sunny days.
 */
final class Wonder {
	private Wonder() {
	}

	private static UUID rainbow;
	private static Vec3 rainbowAt;
	private static int tick;

	static void reset() {
		rainbow = null;
	}

	static void build(ServerLevel level, Vec3 fall) {
		// The rainbow: always turned to face you (so it never looks like a thin line).
		if (fall != null) {
			rainbowAt = fall.add(0, -4, 0);
			Display.ItemDisplay d = HubPark.model(level, rainbowAt, 0, "rainbow", 18.0F, 0);
			if (d != null) {
				((DisplayAccessor) d).rubymod$setBillboard(Display.BillboardConstraints.VERTICAL);
				((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
				((DisplayAccessor) d).rubymod$setViewRange(6.0F);
				rainbow = d.getUUID();
			}
		}
	}

	static void tick(ServerLevel level) {
		tick++;
		long time = level.getOverworldClockTime() % 24000;
		boolean night = time > 13000 && time < 23000;
		var random = level.getRandom();
		// Fireflies round everyone, over the grass, at night.
		if (night && tick % 10 == 0) {
			for (ServerPlayer p : level.players()) {
				for (int i = 0; i < 5; i++) {
					double x = p.getX() + random.nextGaussian() * 9;
					double z = p.getZ() + random.nextGaussian() * 9;
					BlockPos g = BlockPos.containing(x, FLOOR, z);
					if (level.getBlockState(g).is(Blocks.GRASS_BLOCK) && level.getBlockState(g.above()).canBeReplaced()) {
						level.sendParticles(ParticleTypes.FIREFLY, x, FLOOR + 1.3 + random.nextDouble() * 1.8, z, 1, 0.2, 0.2, 0.2, 0);
					}
				}
			}
		}
		// The rainbow's out in the day, gone at night.
		if (rainbow != null && tick % 100 == 0) {
			Entity e = level.getEntity(rainbow);
			if (e instanceof Display d) {
				((DisplayAccessor) d).rubymod$setViewRange(night ? 0.0F : 6.0F);
			}
		}
	}

}
