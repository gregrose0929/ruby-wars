package dev.rubymod.game.hub;

import dev.rubymod.game.Hub;

import net.minecraft.world.level.Level;

/** Where the park's ice boats are limited, and to what (see BoatLimitMixin). */
public final class ParkBoats {
	private ParkBoats() {
	}

	/** Top speed (blocks a tick) for a boat at x, z, or 0 if it's not in one of the park's ice rides. */
	public static double maxSpeed(Level level, double x, double z) {
		if (!level.dimension().equals(Hub.HUB)) {
			return 0;
		}
		if (Math.hypot(x - Speedway.CX, z - Speedway.CZ) < Speedway.RADIUS) {
			// The blue boost strips let you go faster.
			boolean boost = level.getBlockState(net.minecraft.core.BlockPos.containing(x, Speedway.Y - 0.5, z)).is(net.minecraft.world.level.block.Blocks.BLUE_ICE);
			return boost ? 1.15 : 0.78;
		}
		if (x > BumperBoats.X1 - 2 && x < BumperBoats.X2 + 3 && z > BumperBoats.Z1 - 2 && z < BumperBoats.Z2 + 3) {
			return 0.42;
		}
		return 0;
	}

	/** How fast it can spin (degrees a tick). */
	public static float maxTurn(Level level, double x, double z) {
		return Math.hypot(x - Speedway.CX, z - Speedway.CZ) < Speedway.RADIUS ? 4.5F : 6.0F;
	}
}
