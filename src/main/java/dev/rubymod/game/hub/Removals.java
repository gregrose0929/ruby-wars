package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Things taken out of the hub (to make it lighter): Toyland, the Haunted Mansion, the Park Train's tunnel mountain, the
 * cable car's towers, the bandstand, the rope bridges, the waterfalls, and the plaza's launch pads to the islands that
 * went. Their models go by themselves (they're rebuilt fresh every start, and these just aren't built any more); the
 * blocks they left in the world are tidied away here, once.
 */
final class Removals {
	private Removals() {
	}

	/** The islands that went: Toyland's circles, and the Haunted Mansion's. */
	private static final int[][] GONE = {{4, 66, 22}, {22, 66, 14}, {-9, 87, 11}, {23, 86, 7}, {44, 40, 16}};

	static void run(ServerLevel level) {
		Path mark = level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("removed_v1");
		if (Files.exists(mark)) {
			return;
		}
		islands(level);
		clearTunnel(level);
		towers(level);
		bandstand(level);
		bridges(level);
		waterfalls(level);
		pads(level);
		dev.rubymod.RubyMod.LOGGER.info("Removals: Toyland, the Haunted Mansion, the train's tunnel, the cable car, the bandstand, bridges and waterfalls tidied away");
		try {
			Files.createDirectories(mark.getParent());
			Files.writeString(mark, "done");
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't mark the removals done", e);
		}
	}

	/** The Park Train's tunnel mountain. */
	private static void clearTunnel(ServerLevel level) {
		for (int x = -84 - 12; x <= -84 + 12; x++) {
			for (int z = -38 - 12; z <= -38 + 12; z++) {
				for (int y = FLOOR + 10; y <= FLOOR + 56; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = level.getBlockState(p);
					if (s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
							|| s.is(Blocks.STONE_BRICKS) || s.is(Blocks.LIGHT) || s.is(Blocks.AZALEA) || s.is(Blocks.FLOWERING_AZALEA) || s.is(Blocks.MOSS_BLOCK)
							|| s.is(Blocks.SHORT_GRASS) || s.is(Blocks.LANTERN)) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
					}
				}
			}
		}
	}

	/** Whether a column belongs to an island that's staying (so it's never touched). */
	private static boolean kept(int x, int z) {
		if (Math.hypot(x, z) < 27) {
			return true; // the plaza
		}
		if (Math.hypot(x + 44, z - 44) < 19) {
			return true; // Mini Golf
		}
		for (int[][] group : new int[][][] {FunPark.CIRCLES, SplashIsland.CIRCLES, Speedway.CIRCLES, HomeStreet.CIRCLES, Museum.BEACH}) {
			for (int[] c : group) {
				if (Math.hypot(x - c[0], z - c[1]) < c[2] + 2) {
					return true;
				}
			}
		}
		return false;
	}

	private static void islands(ServerLevel level) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int[] c : GONE) {
			int r = c[2] + 3;
			for (int x = c[0] - r; x <= c[0] + r; x++) {
				for (int z = c[1] - r; z <= c[1] + r; z++) {
					if (Math.hypot(x - c[0], z - c[1]) > r || kept(x, z)) {
						continue;
					}
					for (int y = FLOOR - 36; y <= FLOOR + 48; y++) {
						BlockPos p = new BlockPos(x, y, z);
						if (!level.getBlockState(p).isAir()) {
							level.setBlock(p, air, 18);
						}
					}
				}
			}
		}
	}

	/** The cable car's two timber towers (where they stood on the family server, and anywhere the same build put them). */
	private static void towers(ServerLevel level) {
		for (int[] t : new int[][] {{-17, -10}, {-50, -31}}) {
			for (int x = t[0] - 3; x <= t[0] + 3; x++) {
				for (int z = t[1] - 3; z <= t[1] + 3; z++) {
					for (int y = FLOOR + 1; y <= FLOOR + 34; y++) {
						BlockPos p = new BlockPos(x, y, z);
						BlockState s = level.getBlockState(p);
						if (s.is(Blocks.STRIPPED_SPRUCE_LOG) || s.is(Blocks.SPRUCE_FENCE) || s.is(Blocks.SPRUCE_PLANKS) || s.is(Blocks.SPRUCE_SLAB)
								|| s.is(Blocks.LANTERN) || s.is(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED))) {
							level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
						}
					}
				}
			}
		}
	}

	/** The bandstand (it found its room on Splash Island). */
	private static void bandstand(ServerLevel level) {
		int cx = 66;
		int cz = -98;
		for (int x = cx - 8; x <= cx + 8; x++) {
			for (int z = cz - 8; z <= cz + 8; z++) {
				for (int y = FLOOR + 1; y <= FLOOR + 15; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = level.getBlockState(p);
					if (s.is(Blocks.STONE_BRICK_SLAB) || s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.BIRCH_FENCE) || s.is(Blocks.GOLD_BLOCK) || s.is(Blocks.LANTERN)
							|| s.is(Blocks.SPRUCE_STAIRS) || s.is(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.RED))
							|| s.is(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE))) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
					}
				}
			}
		}
	}

	/** The rope bridges: their plank decks (out over the sky) and the invisible walls beside them. */
	private static void bridges(ServerLevel level) {
		Path f = level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("bridges.txt");
		try {
			if (!Files.exists(f)) {
				return;
			}
			for (String line : Files.readAllLines(f)) {
				String[] q = line.trim().split(" ");
				if (q.length != 4) {
					continue;
				}
				int x1 = Integer.parseInt(q[0]);
				int z1 = Integer.parseInt(q[1]);
				int x2 = Integer.parseInt(q[2]);
				int z2 = Integer.parseInt(q[3]);
				int lx = Math.min(x1, x2) - 4;
				int hx = Math.max(x1, x2) + 4;
				int lz = Math.min(z1, z2) - 4;
				int hz = Math.max(z1, z2) + 4;
				double dx = x2 - x1;
				double dz = z2 - z1;
				double len = Math.max(1e-6, Math.hypot(dx, dz));
				for (int x = lx; x <= hx; x++) {
					for (int z = lz; z <= hz; z++) {
						// Distance from the bridge's line.
						double t = Math.max(-1, Math.min(len + 1, ((x - x1) * dx + (z - z1) * dz) / len));
						double px = x1 + dx / len * t;
						double pz = z1 + dz / len * t;
						double d = Math.hypot(x - px, z - pz);
						if (d > 3.2) {
							continue;
						}
						BlockPos deck = new BlockPos(x, FLOOR, z);
						if (level.getBlockState(deck).is(Blocks.SPRUCE_PLANKS) && level.getBlockState(deck.below()).isAir()) {
							level.setBlock(deck, Blocks.AIR.defaultBlockState(), 18);
						}
						for (int y = 1; y <= 2; y++) {
							if (level.getBlockState(deck.above(y)).is(Blocks.BARRIER)) {
								level.setBlock(deck.above(y), Blocks.AIR.defaultBlockState(), 18);
							}
						}
					}
				}
			}
			Files.delete(f);
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't tidy the bridges", e);
		}
	}

	/** The waterfalls: the water going over each edge, its stream, the falling water, and the basin it landed in. */
	private static void waterfalls(ServerLevel level) {
		int n = 0;
		for (int x = -175; x <= 175; x++) {
			for (int z = -175; z <= 175; z++) {
				BlockPos p = new BlockPos(x, FLOOR, z);
				if (!level.getBlockState(p).is(Blocks.WATER) || !level.getFluidState(p).isSource()) {
					continue;
				}
				BlockState under = level.getBlockState(p.below());
				boolean edge = under.isAir() || under.is(Blocks.WATER) && !level.getFluidState(p.below()).isSource();
				// A waterfall's lip: open below, with its stream (water on a mossy bed) running up to it from the island.
				boolean stream = false;
				for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
					stream |= level.getBlockState(p.relative(d)).is(Blocks.WATER) && level.getBlockState(p.relative(d).below()).is(Blocks.MOSSY_COBBLESTONE);
				}
				boolean plungeSpring = x == 112 && z == -133;
				if (!edge || !stream && !plungeSpring) {
					continue;
				}
				// Over the edge: which way the stream came from (the island side).
				for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
					BlockPos in = p.relative(d);
					if (!level.getBlockState(in).is(Blocks.WATER)) {
						continue;
					}
					for (int k = 1; k <= 4; k++) {
						BlockPos bed = p.relative(d, k);
						if (level.getBlockState(bed).is(Blocks.WATER)) {
							level.setBlock(bed, Blocks.GRASS_BLOCK.defaultBlockState(), 18);
							if (level.getBlockState(bed.below()).is(Blocks.MOSSY_COBBLESTONE)) {
								level.setBlock(bed.below(), Blocks.DIRT.defaultBlockState(), 18);
							}
						}
						for (Direction side : new Direction[] {d.getClockWise(), d.getCounterClockWise()}) {
							BlockPos bank = bed.relative(side);
							BlockState b = level.getBlockState(bank);
							if (b.is(Blocks.MOSSY_COBBLESTONE) || b.is(Blocks.MOSS_BLOCK)) {
								level.setBlock(bank, Blocks.GRASS_BLOCK.defaultBlockState(), 18);
							}
							if (level.getBlockState(bank.above()).is(Blocks.FERN)) {
								level.setBlock(bank.above(), Blocks.AIR.defaultBlockState(), 18);
							}
						}
					}
					break;
				}
				// The water going over, and all the way down.
				for (int y = FLOOR; y >= FLOOR - 70; y--) {
					BlockPos w = new BlockPos(x, y, z);
					if (level.getBlockState(w).is(Blocks.WATER)) {
						level.setBlock(w, Blocks.AIR.defaultBlockState(), 18);
					}
				}
				// The basin.
				int b = FLOOR - Dressing.BASIN_DROP;
				for (int bx = -3; bx <= 3; bx++) {
					for (int bz = -3; bz <= 3; bz++) {
						for (int y = b - 5; y <= b + 2; y++) {
							BlockPos q = new BlockPos(x + bx, y, z + bz);
							BlockState s = level.getBlockState(q);
							if (s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.FERN)
									|| s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT) || s.is(Blocks.WATER)) {
								level.setBlock(q, Blocks.AIR.defaultBlockState(), 18);
							}
						}
					}
				}
				n++;
			}
		}
		dev.rubymod.RubyMod.LOGGER.info("Removals: {} waterfalls taken away", n);
	}

	/** The plaza's launch pads to Toyland and the Haunted Mansion: back to plain plaza. */
	private static void pads(ServerLevel level) {
		for (int[] c : new int[][] {{11, 18}, {5, 21}}) {
			BlockState floor = level.getBlockState(new BlockPos(c[0] + 3, FLOOR, c[1]));
			if (floor.isAir() || floor.is(Blocks.EMERALD_BLOCK)) {
				floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos p = new BlockPos(c[0] + dx, FLOOR, c[1] + dz);
					BlockState s = level.getBlockState(p);
					if (s.is(Blocks.EMERALD_BLOCK) || s.is(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIME))) {
						level.setBlock(p, floor, 18);
					}
				}
			}
		}
	}
}
