package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Splash Island: one big island for everything wet. The Splash Tower with its slides, the wave pool and the lazy river
 * (west), Flume Falls round Thunder Mountain (east), and the fishing village by the pond, with the swan boats (south-west).
 */
final class SplashIsland {
	private SplashIsland() {
	}

	/** x, z, radius of the circles the island's made of. */
	static final int[][] CIRCLES = {{30, -100, 30}, {74, -100, 28}, {8, -134, 16}, {52, -80, 12}, {40, -141, 18}, {1, -151, 8}};
	static final int MX = 44;
	static final int MZ = -104;

	static void build(ServerLevel level) {
		HubPark.blob(level, CIRCLES, FLOOR);
		HubPark.travelPad(level, 52, FLOOR, -78, "splash");
		HubPark.label(level, 52.5, FLOOR + 4, -74.5, "SPLASH ISLAND", ChatFormatting.AQUA, 2.4F);
		// Sandy paths from the pad to everything.
		path(level, 52, -78, 40, -88);
		path(level, 52, -78, 48, -98);
		path(level, 40, -88, 14, -122);
		for (int[] p : new int[][] {{58, -74}, {46, -74}, {36, -84}, {20, -112}, {60, -128}, {98, -88}}) {
			palm(level, p[0], p[1]);
		}
		for (int[] l : new int[][] {{55, -80}, {44, -92}, {26, -116}, {50, -110}}) {
			HubPark.lamp(level, l[0], FLOOR + 1, l[1]);
		}
		Flume.build(level);
		Rapids.build(level);
	}

	private static void path(ServerLevel level, int x1, int z1, int x2, int z2) {
		int n = (int) Math.ceil(Math.hypot(x2 - x1, z2 - z1));
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			int x = (int) Math.round(x1 + (x2 - x1) * t);
			int z = (int) Math.round(z1 + (z2 - z1) * t);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (level.getBlockState(new net.minecraft.core.BlockPos(x + dx, FLOOR, z + dz)).is(Blocks.GRASS_BLOCK)) {
						HubPark.set(level, x + dx, FLOOR, z + dz, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
					}
				}
			}
		}
	}

	static void palm(ServerLevel level, int x, int z) {
		BlockState trunk = Blocks.JUNGLE_LOG.defaultBlockState();
		for (int y = 1; y <= 6; y++) {
			HubPark.set(level, x + (y > 4 ? 1 : 0), FLOOR + y, z, trunk);
		}
		BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		int tx = x + 1;
		int ty = FLOOR + 7;
		HubPark.set(level, tx, ty, z, leaves);
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			for (int i = 1; i <= 2; i++) {
				HubPark.set(level, tx + d[0] * i, ty - (i == 2 ? 1 : 0), z + d[1] * i, leaves);
			}
		}
	}

	static void tick(ServerLevel level, int tick) {
		if (HubPark.near(level, Flume.CX, Flume.CZ, 50) || Flume.busy()) {
			Flume.tick(level, tick);
		}
		if (HubPark.near(level, Rapids.CX, Rapids.CZ, 45) || Rapids.busy()) {
			Rapids.tick(level, tick);
		}
	}
}
