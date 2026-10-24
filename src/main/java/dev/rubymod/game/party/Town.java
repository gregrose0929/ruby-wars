package dev.rubymod.game.party;

import dev.rubymod.world.WarzoneTerrain;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A small walled village for Hide & Seek and Infection: houses with doorways and
 * windows, a market square with a fountain, gardens, trees and lots of barrels, hay
 * and crates (plenty of blocks to disguise as, and corners to run around).
 */
final class Town {
	static final int HALF = 36;

	private Town() {
	}

	static void build(PartyGame g, ServerLevel level) {
		g.flatArena(level, HALF, HALF, 20, Blocks.GRASS_BLOCK.defaultBlockState(), true);
		BlockState gravel = Blocks.GRAVEL.defaultBlockState();
		BlockState path = Blocks.DIRT_PATH.defaultBlockState();
		// Streets: a cross, and a ring road.
		g.box(level, -2, 0, -HALF, 2, 0, HALF, gravel);
		g.box(level, -HALF, 0, -2, HALF, 0, 2, gravel);
		for (int i = -24; i <= 24; i++) {
			g.set(level, i, 0, -24, path);
			g.set(level, i, 0, 24, path);
			g.set(level, -24, 0, i, path);
			g.set(level, 24, 0, i, path);
		}
		// Market square with a fountain.
		g.box(level, -6, 0, -6, 6, 0, 6, Blocks.STONE_BRICKS.defaultBlockState());
		g.box(level, -2, 1, -2, 2, 1, 2, Blocks.STONE_BRICK_WALL.defaultBlockState());
		g.box(level, -1, 1, -1, 1, 1, 1, Blocks.WATER.defaultBlockState());
		g.set(level, 0, 1, 0, Blocks.STONE_BRICKS.defaultBlockState());
		g.set(level, 0, 2, 0, Blocks.STONE_BRICKS.defaultBlockState());
		g.set(level, 0, 3, 0, Blocks.WATER.defaultBlockState());
		// Market stalls.
		BlockState[] goods = {Blocks.HAY_BLOCK.defaultBlockState(), Blocks.BARREL.defaultBlockState(), Blocks.PUMPKIN.defaultBlockState(),
				Blocks.MELON.defaultBlockState(), Blocks.CRAFTING_TABLE.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState()};
		int[][] stalls = {{-5, -5}, {5, -5}, {-5, 5}, {5, 5}, {-5, 0}, {5, 0}, {0, -5}, {0, 5}};
		for (int i = 0; i < stalls.length; i++) {
			g.set(level, stalls[i][0], 1, stalls[i][1], goods[i % goods.length]);
		}
		// Houses: {x, z, width, depth, material, door side}.
		int[][] houses = {{-20, -20, 9, 7, 0, 0}, {10, -20, 7, 7, 1, 0}, {-20, 10, 7, 9, 2, 1}, {12, 12, 9, 9, 3, 2},
				{-32, -8, 6, 6, 1, 3}, {27, -8, 6, 7, 0, 1}, {-10, 28, 8, 6, 2, 0}, {-10, -33, 8, 6, 3, 2}, {28, 28, 6, 6, 0, 3},
				{-31, 27, 6, 6, 3, 0}};
		BlockState[][] materials = {
				{Blocks.OAK_PLANKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState()},
				{Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.DARK_OAK_PLANKS.defaultBlockState()},
				{Blocks.BRICKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState()},
				{Blocks.COBBLESTONE.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()}};
		for (int[] h : houses) {
			house(g, level, h[0], h[1], h[2], h[3], materials[h[4]], h[5]);
		}
		// Gardens with hay, composters and pumpkins.
		for (int[] p : new int[][] {{-16, 2}, {16, 4}, {4, -16}, {-4, 17}, {30, 14}, {-30, -22}}) {
			g.box(level, p[0] - 2, 0, p[1] - 1, p[0] + 2, 0, p[1] + 1, Blocks.FARMLAND.defaultBlockState());
			g.box(level, p[0] - 3, 1, p[1] - 2, p[0] + 3, 1, p[1] - 2, Blocks.OAK_FENCE.defaultBlockState());
			g.set(level, p[0] + 3, 1, p[1], Blocks.HAY_BLOCK.defaultBlockState());
			g.set(level, p[0] - 3, 1, p[1], Blocks.COMPOSTER.defaultBlockState());
			g.set(level, p[0], 1, p[1] + 2, Blocks.PUMPKIN.defaultBlockState());
		}
		// Trees.
		BlockState log = Blocks.OAK_LOG.defaultBlockState();
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int[] t : new int[][] {{-8, 14}, {8, -12}, {20, 0}, {-22, 0}, {0, 20}, {-14, -28}, {16, 30}, {32, -30}, {-30, 32}, {33, 4}}) {
			g.box(level, t[0] - 2, 4, t[1] - 2, t[0] + 2, 5, t[1] + 2, leaves);
			g.box(level, t[0] - 1, 6, t[1] - 1, t[0] + 1, 6, t[1] + 1, leaves);
			g.box(level, t[0], 1, t[1], t[0], 5, t[1], log);
		}
		// Crates and hay scattered about (lots to hide among).
		BlockState[] clutter = {Blocks.BARREL.defaultBlockState(), Blocks.HAY_BLOCK.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
				Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.PUMPKIN.defaultBlockState()};
		g.later(() -> clutter(g, level, clutter));
		// Lamp posts.
		for (int[] p : new int[][] {{-3, -12}, {3, 12}, {-12, 3}, {12, -3}, {-3, 25}, {3, -25}, {25, 3}, {-25, -3}}) {
			g.box(level, p[0], 1, p[1], p[0], 3, p[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			g.set(level, p[0], 4, p[1], Blocks.LANTERN.defaultBlockState());
		}
	}

	private static void clutter(PartyGame g, ServerLevel level, BlockState[] clutter) {
		for (int i = 0; i < 70; i++) {
			int x = (int) (WarzoneTerrain.hash3(i, 11, 5) * (HALF * 2 - 4)) - HALF + 2;
			int z = (int) (WarzoneTerrain.hash3(i, 12, 9) * (HALF * 2 - 4)) - HALF + 2;
			if (Math.abs(x) <= 3 || Math.abs(z) <= 3) {
				continue; // keep the streets clear
			}
			if (level.getBlockState(g.center().offset(x, 0, z)).isAir() && level.getBlockState(g.center().offset(x, -1, z)).isSolid()) {
				g.set(level, x, 1, z, clutter[i % clutter.length]);
				if (i % 4 == 0) {
					g.set(level, x, 2, z, clutter[(i + 1) % clutter.length]);
				}
			}
		}
	}

	/** A small house: walls, a doorway, windows, a flat roof, a few things inside. */
	private static void house(PartyGame g, ServerLevel level, int x0, int z0, int w, int d, BlockState[] m, int doorSide) {
		BlockState wall = m[0];
		BlockState corner = m[1];
		BlockState roof = m[2];
		BlockState air = Blocks.AIR.defaultBlockState();
		g.box(level, x0, 0, z0, x0 + w - 1, 0, z0 + d - 1, Blocks.OAK_PLANKS.defaultBlockState());
		g.box(level, x0, 1, z0, x0 + w - 1, 3, z0 + d - 1, wall);
		g.box(level, x0 + 1, 1, z0 + 1, x0 + w - 2, 3, z0 + d - 2, air);
		for (int[] c : new int[][] {{x0, z0}, {x0 + w - 1, z0}, {x0, z0 + d - 1}, {x0 + w - 1, z0 + d - 1}}) {
			g.box(level, c[0], 1, c[1], c[0], 3, c[1], corner);
		}
		g.box(level, x0 - 1, 4, z0 - 1, x0 + w, 4, z0 + d, roof);
		// Doorway and windows.
		int mx = x0 + w / 2;
		int mz = z0 + d / 2;
		switch (doorSide) {
			case 0 -> g.box(level, mx, 1, z0 + d - 1, mx, 2, z0 + d - 1, air);
			case 1 -> g.box(level, x0 + w - 1, 1, mz, x0 + w - 1, 2, mz, air);
			case 2 -> g.box(level, mx, 1, z0, mx, 2, z0, air);
			default -> g.box(level, x0, 1, mz, x0, 2, mz, air);
		}
		BlockState glass = Blocks.GLASS_PANE.defaultBlockState();
		if (doorSide != 1) {
			g.set(level, x0 + w - 1, 2, mz, glass);
		}
		if (doorSide != 3) {
			g.set(level, x0, 2, mz, glass);
		}
		// Inside: a table, barrels and a bookshelf.
		g.set(level, x0 + 1, 1, z0 + 1, Blocks.CRAFTING_TABLE.defaultBlockState());
		g.set(level, x0 + w - 2, 1, z0 + 1, Blocks.BARREL.defaultBlockState());
		g.set(level, x0 + 1, 1, z0 + d - 2, Blocks.BOOKSHELF.defaultBlockState());
		g.set(level, x0 + w - 2, 1, z0 + d - 2, Blocks.BARREL.defaultBlockState());
		if (w > 7) {
			g.set(level, x0 + 2, 1, z0 + 1, Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.FACING, Direction.SOUTH));
		}
	}
}
