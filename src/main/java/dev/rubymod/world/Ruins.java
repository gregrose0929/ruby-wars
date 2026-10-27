package dev.rubymod.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Crumbled ruins for the Ruby Wars map, built from math so any chunk can build its own
 * part: a broken house (walls fallen to different heights, a doorway, sometimes a
 * chest), a half-collapsed watchtower with its rubble spilled out, a row of broken
 * pillars, or an old arch. Stone bricks with moss and cracks; sandstone in the desert;
 * mossy cobble in the pine woods; snow on top in the snowy corner.
 */
final class Ruins {
	private Ruins() {
	}

	/** Writes only inside the chunk being built. */
	private record Writer(WorldGenLevel level, int minX, int minZ, int maxX, int maxZ) {
		boolean inside(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		void set(int x, int y, int z, BlockState state) {
			if (inside(x, z)) {
				level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
			}
		}
	}

	private static double h(MapFeatures.Ruin r, int a, int b, int c) {
		return WarzoneTerrain.hash3(a * 31 + (int) r.seed(), b, c * 17 + (int) (r.seed() >>> 32));
	}

	private static int ground(int x, int z) {
		return WarzoneTerrain.column(x, z).height();
	}

	/** The wall block at a spot: mostly the corner's stone, some cracked or mossy. */
	private static BlockState stone(MapFeatures.Ruin r, int x, int y, int z) {
		double v = h(r, x, y, z);
		return switch (r.theme()) {
			case WarzoneTerrain.THEME_DESERT -> (v < 0.5 ? Blocks.SANDSTONE : v < 0.8 ? Blocks.CUT_SANDSTONE : v < 0.92 ? Blocks.SMOOTH_SANDSTONE
					: Blocks.CHISELED_SANDSTONE).defaultBlockState();
			case WarzoneTerrain.THEME_PINE -> (v < 0.45 ? Blocks.MOSSY_COBBLESTONE : v < 0.75 ? Blocks.MOSSY_STONE_BRICKS : v < 0.9 ? Blocks.COBBLESTONE
					: Blocks.CRACKED_STONE_BRICKS).defaultBlockState();
			default -> (v < 0.45 ? Blocks.STONE_BRICKS : v < 0.7 ? Blocks.CRACKED_STONE_BRICKS : v < 0.88 ? Blocks.MOSSY_STONE_BRICKS
					: Blocks.COBBLESTONE).defaultBlockState();
		};
	}

	private static BlockState rubble(MapFeatures.Ruin r, int x, int z) {
		return r.theme() == WarzoneTerrain.THEME_DESERT ? Blocks.SANDSTONE_SLAB.defaultBlockState()
				: (h(r, x, 9, z) < 0.5 ? Blocks.COBBLESTONE_SLAB : Blocks.STONE_BRICK_SLAB).defaultBlockState();
	}

	/** A column of wall from the ground up to {@code top} (absolute y), snow-capped in the snowy corner. */
	private static void column(Writer w, MapFeatures.Ruin r, int x, int z, int top) {
		if (!w.inside(x, z)) {
			return;
		}
		int g = ground(x, z);
		if (g <= WarzoneTerrain.SEA) {
			return;
		}
		for (int y = g + 1; y <= top; y++) {
			w.set(x, y, z, stone(r, x, y, z));
		}
		if (top > g && r.theme() == WarzoneTerrain.THEME_SNOW && h(r, x, 3, z) < 0.7) {
			w.set(x, top + 1, z, Blocks.SNOW.defaultBlockState());
		}
	}

	static void build(WorldGenLevel level, MapFeatures.Ruin r, int minX, int minZ, int maxX, int maxZ) {
		Writer w = new Writer(level, minX, minZ, maxX, maxZ);
		int base = ground(r.x(), r.z());
		switch (r.kind()) {
			case 0 -> house(w, r, base);
			case 1 -> tower(w, r, base);
			case 2 -> pillars(w, r, base);
			default -> arch(w, r, base);
		}
		// Bits of fallen stone round about.
		for (int i = 0; i < 14; i++) {
			int x = r.x() + (int) Math.round((h(r, i, 20, 1) * 2 - 1) * 9);
			int z = r.z() + (int) Math.round((h(r, i, 21, 1) * 2 - 1) * 9);
			if (w.inside(x, z) && ground(x, z) > WarzoneTerrain.SEA && level.getBlockState(new BlockPos(x, ground(x, z) + 1, z)).isAir()) {
				w.set(x, ground(x, z) + 1, z, h(r, i, 22, 1) < 0.6 ? rubble(r, x, z) : stone(r, x, 1, z));
			}
		}
	}

	/** Four broken walls, a doorway, a mossy floor; a chest in a corner now and then. */
	private static void house(Writer w, MapFeatures.Ruin r, int base) {
		int hx = 3 + (int) (h(r, 1, 1, 1) * 3);
		int hz = 3 + (int) (h(r, 2, 1, 1) * 3);
		int door = (int) (h(r, 3, 1, 1) * 4);
		for (int dx = -hx; dx <= hx; dx++) {
			for (int dz = -hz; dz <= hz; dz++) {
				int x = r.x() + dx;
				int z = r.z() + dz;
				boolean wall = Math.abs(dx) == hx || Math.abs(dz) == hz;
				if (!wall) {
					// The floor: worn and mossy, open to the sky.
					int g = ground(x, z);
					if (w.inside(x, z) && g > WarzoneTerrain.SEA && h(r, dx, 2, dz) < 0.55) {
						w.set(x, g, z, (h(r, dx, 4, dz) < 0.5 ? Blocks.MOSSY_COBBLESTONE : Blocks.GRAVEL).defaultBlockState());
					}
					continue;
				}
				boolean doorway = door == 0 && dz == -hz && Math.abs(dx) <= 1 || door == 1 && dz == hz && Math.abs(dx) <= 1
						|| door == 2 && dx == -hx && Math.abs(dz) <= 1 || door == 3 && dx == hx && Math.abs(dz) <= 1;
				boolean corner = Math.abs(dx) == hx && Math.abs(dz) == hz;
				int height = doorway ? 0 : (int) Math.round((corner ? 3.5 : 1.0) + h(r, dx, 5, dz) * 3.2 - (h(r, dx, 6, dz) < 0.2 ? 3 : 0));
				column(w, r, x, z, base + Math.max(0, height));
			}
		}
		if (h(r, 7, 7, 7) < 0.45) {
			int cx = r.x() + hx - 1;
			int cz = r.z() + hz - 1;
			if (w.inside(cx, cz)) {
				MapFeatures.chest(w.level(), new BlockPos(cx, ground(cx, cz) + 1, cz), r.seed());
			}
		}
	}

	/** A round tower that's half fallen over, with its stones spilled out the way it fell. */
	private static void tower(Writer w, MapFeatures.Ruin r, int base) {
		double fall = h(r, 1, 1, 2) * Math.PI * 2;
		int tall = 8 + (int) (h(r, 2, 1, 2) * 6);
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d < 2.5 || d > 3.6) {
					continue;
				}
				double side = Math.cos(Math.atan2(dz, dx) - fall); // 1: the side it fell toward
				int height = (int) Math.round(tall * (0.55 - 0.45 * side) * (0.75 + 0.25 * h(r, dx, 3, dz)));
				boolean doorway = dz == 3 && Math.abs(dx) <= 0;
				column(w, r, r.x() + dx, r.z() + dz, base + (doorway ? 0 : Math.max(1, height)));
			}
		}
		// The fallen top, strewn out the way it went.
		for (int i = 4; i < 13; i++) {
			int x = r.x() + (int) Math.round(Math.cos(fall) * i + (h(r, i, 4, 2) - 0.5) * 3);
			int z = r.z() + (int) Math.round(Math.sin(fall) * i + (h(r, i, 5, 2) - 0.5) * 3);
			if (w.inside(x, z) && ground(x, z) > WarzoneTerrain.SEA) {
				int g = ground(x, z);
				w.set(x, g + 1, z, stone(r, x, g + 1, z));
				if (h(r, i, 6, 2) < 0.4) {
					w.set(x, g + 2, z, rubble(r, x, z));
				}
			}
		}
	}

	/** Two rows of pillars, most broken off, one lying where it fell. */
	private static void pillars(Writer w, MapFeatures.Ruin r, int base) {
		boolean alongX = h(r, 1, 1, 3) < 0.5;
		for (int i = -2; i <= 2; i++) {
			for (int row : new int[] {-3, 3}) {
				int x = r.x() + (alongX ? i * 3 : row);
				int z = r.z() + (alongX ? row : i * 3);
				int height = (int) Math.round(1 + h(r, i, 2, row) * 6);
				column(w, r, x, z, base + height);
				if (height >= 6 && w.inside(x, z)) {
					w.set(x, ground(x, z) + height + 1, z, (r.theme() == WarzoneTerrain.THEME_DESERT ? Blocks.CHISELED_SANDSTONE
							: Blocks.CHISELED_STONE_BRICKS).defaultBlockState());
				}
			}
		}
		// A fallen pillar on its side between the rows.
		for (int i = 0; i < 4; i++) {
			int x = r.x() + (alongX ? i - 1 : 0);
			int z = r.z() + (alongX ? 0 : i - 1);
			if (w.inside(x, z) && ground(x, z) > WarzoneTerrain.SEA) {
				w.set(x, ground(x, z) + 1, z, stone(r, x, 1, z));
			}
		}
	}

	/** A stretch of old wall with an arch through it, crumbling at both ends. */
	private static void arch(Writer w, MapFeatures.Ruin r, int base) {
		boolean alongX = h(r, 1, 1, 4) < 0.5;
		for (int i = -6; i <= 6; i++) {
			int x = r.x() + (alongX ? i : 0);
			int z = r.z() + (alongX ? 0 : i);
			int end = Math.abs(i);
			int height = end <= 4 ? 6 : 6 - (end - 4) * 2 - (int) (h(r, i, 2, 4) * 2);
			if (!w.inside(x, z) || ground(x, z) <= WarzoneTerrain.SEA) {
				continue;
			}
			int g = ground(x, z);
			for (int y = g + 1; y <= base + height; y++) {
				boolean opening = Math.abs(i) <= 1 && y <= base + 3 || Math.abs(i) == 2 && y <= base + 2;
				boolean gap = h(r, i, y, 5) < 0.12 && y > base + 4;
				if (!opening && !gap) {
					w.set(x, y, z, stone(r, x, y, z));
				}
			}
		}
	}
}
