package dev.rubymod.game.convoy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import dev.rubymod.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The road the Ruby Convoy drives: one long strip of country in the arena dimension,
 * built fresh every run (its bends, trees, houses and boulders land somewhere new each
 * time). It runs north to south (+Z): the Ruby Mine where the truck is loaded, then five
 * stages, each with a depot before it:
 *
 * <ol>
 * <li>Greenwood Road: forest, bandits, and fallen trees across the road.</li>
 * <li>Ember Town: a burning town, gunmen in the windows, tanks in the square.</li>
 * <li>Red Canyon: cliffs, a gunship, and the bridge over the gorge is out.</li>
 * <li>Frost Pass: snowy mountains, snipers, boulders rolling down, an avalanche.</li>
 * <li>Iron Fortress: the Iron Dreadnought guards the vault gate.</li>
 * </ol>
 *
 * The road's height never changes (players stand on {@link #Y} + 1), so the truck rolls
 * along without climbing; the land around it rises and falls instead.
 */
public final class ConvoyRoute {
	/** The road's middle (in a straight stretch). */
	public static final int X0 = -160000;
	/** The road surface (the flat world's grass level). */
	public static final int Y = 64;
	/** How far either side of the road gets built. */
	static final int HALF = 42;
	static final int ROAD = 3;
	/** Each stage: where it starts and ends along the road (Z). Each has a depot just before it. */
	static final int[] DEPOT = {0, 176, 352, 528, 704};
	static final int[] START = {26, 202, 378, 554, 730};
	static final int[] END = {176, 352, 528, 704, 830};
	/** The fortress: its courtyard, the gate across the road, and the vault behind. */
	static final int COURTYARD = 776;
	static final int GATE = 808;
	static final int FINISH = 822;
	/** The canyon's gorge (the bridge is out). */
	static final int GAP_FROM = START[2] + 64;
	static final int GAP_TO = GAP_FROM + 14;
	static final int BUILD_FROM = -24;
	static final int BUILD_TO = 846;

	public static final String[] NAMES = {"Greenwood Road", "Ember Town", "Red Canyon", "Frost Pass", "Iron Fortress"};
	static final String[] BLURBS = {"Bandits in the trees. Fallen logs block the road!", "The town is burning. Gunmen in the windows, tanks in the square!",
			"Cliffs and a gunship. The bridge is out: guard the engineers!", "Snipers on the ridges and boulders rolling down. Watch for red circles!",
			"The Iron Dreadnought guards the vault. Bring it down!"};
	static final int[] COLORS = {0xFF5BC85A, 0xFFFF8A3D, 0xFFD8643A, 0xFF9AD8FF, 0xFFB04060};

	enum Theme { MINE, FOREST, TOWN, CANYON, FROST, FORTRESS, DEPOT }

	/** This run's bends. */
	static double amp = 8;
	static double wave = 34;
	static double phase;
	/** Ember Town's windows and doorways (where its gunmen appear), filled in while it's built. */
	static final List<BlockPos> WINDOWS = new ArrayList<>();
	/** Burning houses (smoke and flames), and where the canyon's cliff-top raiders stand. */
	static final List<BlockPos> FIRES = new ArrayList<>();
	static final List<BlockPos> LEDGES = new ArrayList<>();

	private ConvoyRoute() {
	}

	static void reroll(Random random) {
		amp = 6 + random.nextDouble() * 6;
		wave = 26 + random.nextDouble() * 14;
		phase = random.nextDouble() * Math.PI * 2;
		WINDOWS.clear();
		FIRES.clear();
		LEDGES.clear();
	}

	// ----- Where things are -----

	/** Which stage (0-4) this point of the road is in, counting its depot as part of it; -1 before the first. */
	static int stageAt(double z) {
		for (int i = DEPOT.length - 1; i >= 0; i--) {
			if (z >= DEPOT[i]) {
				return i;
			}
		}
		return -1;
	}

	static boolean inDepot(double z) {
		int s = stageAt(z);
		return s >= 0 && z < START[s];
	}

	static Theme themeAt(double z) {
		if (z < START[0]) {
			return z < 6 ? Theme.MINE : Theme.DEPOT;
		}
		if (inDepot(z)) {
			return Theme.DEPOT;
		}
		return switch (stageAt(z)) {
			case 0 -> Theme.FOREST;
			case 1 -> Theme.TOWN;
			case 2 -> Theme.CANYON;
			case 3 -> Theme.FROST;
			default -> Theme.FORTRESS;
		};
	}

	/** 0 in the depots and the fortress (straight road), easing up to 1 between them. */
	private static double bendWeight(double z) {
		double nearest = 999;
		for (int i = 0; i < DEPOT.length; i++) {
			if (z >= DEPOT[i] - 2 && z <= START[i] + 2) {
				return 0;
			}
			nearest = Math.min(nearest, Math.min(Math.abs(z - DEPOT[i]), Math.abs(z - START[i])));
		}
		if (z >= START[4] || z >= GAP_FROM - 12 && z <= GAP_TO + 12) {
			return 0;
		}
		nearest = Math.min(nearest, Math.min(Math.abs(z - (GAP_FROM - 12)), Math.abs(z - (GAP_TO + 12))));
		double t = Math.min(1, nearest / 16.0);
		return t * t * (3 - 2 * t);
	}

	/** The road's middle at this Z. */
	static double cx(double z) {
		return X0 + 0.5 + amp * Math.sin(z / wave * Math.PI * 2 + phase) * bendWeight(z);
	}

	/** Which way the road heads here (degrees, Minecraft yaw: 0 = south). */
	static float yaw(double z) {
		double dx = cx(z + 1.5) - cx(z - 1.5);
		return (float) -Math.toDegrees(Math.atan2(dx, 3.0));
	}

	// ----- Building -----

	private static BlockState b(Block block) {
		return block.defaultBlockState();
	}

	private static BlockState leaves(Block block) {
		return block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
	}

	/** Queue up building the road from {@code from} to {@code to} (columns first, then trees and houses). */
	static void build(ArrayDeque<Runnable> work, ServerLevel level, int from, int to, long seed) {
		for (int z = from; z < to; z++) {
			int zz = z;
			work.add(() -> column(level, zz));
		}
		for (int z = from; z < to; z++) {
			int zz = z;
			work.add(() -> features(level, zz, new Random(seed * 31 + zz * 7919L)));
		}
	}

	/** One slice across the road: the ground, the hills and cliffs, the air above (clearing whatever the last run left). */
	private static void column(ServerLevel level, int z) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		Theme theme = themeAt(z);
		double c = cx(z);
		for (int x = X0 - HALF; x <= X0 + HALF; x++) {
			double d = x + 0.5 - c;
			double ad = Math.abs(d);
			int top = Y;
			BlockState surface;
			BlockState under = b(Blocks.DIRT);
			BlockState deep = b(Blocks.STONE);
			int floor = 40; // nothing below this changes
			long h = hash(x, z);
			switch (theme) {
				case MINE -> {
					surface = ad <= ROAD ? b(Blocks.GRAVEL) : (h & 3) == 0 ? b(Blocks.COARSE_DIRT) : b(Blocks.STONE);
					under = b(Blocks.STONE);
					// The mountain the mine goes into, behind the start.
					if (z < 0) {
						int rise = (int) Math.min(26, (-z) * 2.2 + 4 - (ad < 4 && z > -12 ? 99 : 0));
						top = Y + Math.max(0, rise);
					}
				}
				case DEPOT -> {
					surface = ad <= ROAD ? b(Blocks.CONCRETE.pick(DyeColor.GRAY))
							: ad <= 13 ? ((x + z) % 2 == 0 ? b(Blocks.POLISHED_ANDESITE) : b(Blocks.ANDESITE)) : grassFor(themeAt(z + 30), h);
					if (ad <= ROAD && Math.abs(d) < 0.6 && z % 4 < 2) {
						surface = b(Blocks.CONCRETE.pick(DyeColor.YELLOW));
					}
					if (ad > 13) {
						top = Y + hills(themeAt(z + 30), x, z, ad);
					}
				}
				case FOREST -> {
					surface = ad <= ROAD ? ((h & 7) == 0 ? b(Blocks.COARSE_DIRT) : b(Blocks.DIRT_PATH))
							: ad <= 5 ? ((h & 3) == 0 ? b(Blocks.PODZOL) : b(Blocks.GRASS_BLOCK)) : (h % 9 == 0 ? b(Blocks.PODZOL) : b(Blocks.GRASS_BLOCK));
					top = Y + hills(theme, x, z, ad);
				}
				case TOWN -> {
					surface = ad <= ROAD ? ((h & 3) == 0 ? b(Blocks.MOSSY_COBBLESTONE) : b(Blocks.COBBLESTONE))
							: ad <= 5 ? b(Blocks.SMOOTH_STONE) : (h % 5 == 0 ? b(Blocks.COARSE_DIRT) : b(Blocks.GRASS_BLOCK));
				}
				case CANYON -> {
					surface = ad <= ROAD ? b(Blocks.PACKED_MUD) : ad <= 5 ? b(Blocks.RED_SAND) : (h % 4 == 0 ? b(Blocks.TERRACOTTA) : b(Blocks.RED_SAND));
					under = b(Blocks.RED_SANDSTONE);
					deep = b(Blocks.RED_SANDSTONE);
					top = Y + hills(theme, x, z, ad);
				}
				case FROST -> {
					surface = ad <= ROAD ? ((h & 3) == 0 ? b(Blocks.ANDESITE) : b(Blocks.COBBLESTONE)) : b(Blocks.SNOW_BLOCK);
					under = ad <= ROAD ? b(Blocks.STONE) : b(Blocks.SNOW_BLOCK);
					top = Y + hills(theme, x, z, ad);
				}
				default -> {
					// The fortress: a dark courtyard.
					surface = ad <= ROAD ? b(Blocks.POLISHED_BLACKSTONE)
							: ((x + z) & 1) == 0 ? b(Blocks.DEEPSLATE_TILES) : b(Blocks.POLISHED_DEEPSLATE);
					under = b(Blocks.DEEPSLATE);
					deep = b(Blocks.DEEPSLATE);
					if (z < COURTYARD) {
						top = Y + hills(theme, x, z, ad);
					}
				}
			}
			// The gorge across the canyon road: a drop to a river (soft landing).
			boolean gorge = theme == Theme.CANYON && z >= GAP_FROM && z < GAP_TO;
			for (int y = floor; y <= Y + 30; y++) {
				BlockState want;
				if (gorge) {
					want = y <= 41 ? b(Blocks.SAND) : y <= 46 ? b(Blocks.WATER) : Blocks.AIR.defaultBlockState();
					if (y > 46 && top > Y && y <= top && ad > 12) {
						want = canyonBand(y);
					}
				} else if (y < top - 3) {
					want = theme == Theme.CANYON && y > Y - 2 ? canyonBand(y) : theme == Theme.FROST && y > Y && (h & 7) == 0 ? b(Blocks.PACKED_ICE) : deep;
				} else if (y < top) {
					want = theme == Theme.CANYON && y > Y ? canyonBand(y) : theme == Theme.MINE && y > Y ? oreStone(h, y) : under;
				} else if (y == top) {
					want = top > Y && theme == Theme.CANYON ? canyonBand(y) : top > Y + 3 && theme == Theme.MINE ? b(Blocks.STONE) : surface;
				} else {
					want = Blocks.AIR.defaultBlockState();
				}
				set(level, m.set(x, y, z), want);
			}
		}
	}

	private static BlockState grassFor(Theme next, long h) {
		return switch (next) {
			case CANYON -> b(Blocks.RED_SAND);
			case FROST -> b(Blocks.SNOW_BLOCK);
			case FORTRESS -> b(Blocks.BLACKSTONE);
			case TOWN -> h % 3 == 0 ? b(Blocks.COARSE_DIRT) : b(Blocks.GRASS_BLOCK);
			default -> b(Blocks.GRASS_BLOCK);
		};
	}

	/** Stripes of colour in the canyon walls. */
	private static BlockState canyonBand(int y) {
		return switch (Math.floorMod(y, 7)) {
			case 0, 1 -> b(Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE));
			case 2 -> b(Blocks.TERRACOTTA);
			case 3 -> b(Blocks.DYED_TERRACOTTA.pick(DyeColor.RED));
			case 4 -> b(Blocks.DYED_TERRACOTTA.pick(DyeColor.YELLOW));
			case 5 -> b(Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE));
			default -> b(Blocks.RED_SANDSTONE);
		};
	}

	private static BlockState oreStone(long h, int y) {
		return h % 23 == 0 ? ModBlocks.RUBY_BLOCK.defaultBlockState() : (h + y) % 11 == 0 ? b(Blocks.COAL_ORE) : b(Blocks.STONE);
	}

	/** How far the land rises off the road here. */
	private static int hills(Theme theme, int x, int z, double ad) {
		double n = Math.sin(x * 0.11 + z * 0.07) * 0.5 + Math.sin(x * 0.043 - z * 0.091 + 2) * 0.5;
		return switch (theme) {
			case FOREST -> (int) Math.max(0, Math.min(4, (ad - 10) / 6.0) * (0.7 + 0.5 * n));
			case CANYON -> ad < 11 ? 0 : (int) Math.min(18, (ad - 11) * 2.0 * (0.85 + 0.25 * n));
			case FROST -> ad < 9 ? 0 : (int) Math.min(28, Math.pow(ad - 9, 1.25) * 1.5 * (0.8 + 0.3 * n));
			case FORTRESS -> ad < 9 ? 0 : (int) Math.min(14, (ad - 9) * 2.5);
			default -> 0;
		};
	}

	private static long hash(int x, int z) {
		long h = x * 73856093L ^ z * 19349663L;
		h ^= h >>> 13;
		return Math.abs(h * 0x5DEECE66DL >>> 7);
	}

	private static void set(ServerLevel level, BlockPos pos, BlockState state) {
		if (level.getBlockState(pos) != state) {
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		set(level, new BlockPos(x, y, z), state);
	}

	private static int ground(ServerLevel level, int x, int z) {
		for (int y = Y + 30; y > 40; y--) {
			BlockState s = level.getBlockState(new BlockPos(x, y, z));
			if (!s.isAir() && s.getFluidState().isEmpty()) {
				return y;
			}
		}
		return Y;
	}

	// ----- Trees, houses, depots -----

	private static void features(ServerLevel level, int z, Random random) {
		Theme theme = themeAt(z);
		int c = (int) Math.floor(cx(z));
		// The depots: one set piece each, built from its first row.
		for (int i = 0; i < DEPOT.length; i++) {
			if (z == DEPOT[i] + 1) {
				depot(level, i);
			}
		}
		if (z == -4) {
			mine(level);
		}
		if (z == START[0] - 1 || z == START[1] - 1 || z == START[2] - 1 || z == START[3] - 1 || z == START[4] - 1) {
			return;
		}
		switch (theme) {
			case FOREST -> {
				for (int side : new int[] {-1, 1}) {
					if (random.nextFloat() < 0.32F) {
						int x = c + side * (8 + random.nextInt(30));
						tree(level, x, ground(level, x, z), z, random);
					}
					if (random.nextFloat() < 0.5F) {
						int x = c + side * (5 + random.nextInt(34));
						int y = ground(level, x, z);
						if (level.getBlockState(new BlockPos(x, y + 1, z)).isAir()) {
							BlockState[] plants = {b(Blocks.SHORT_GRASS), b(Blocks.FERN), b(Blocks.SHORT_GRASS), b(Blocks.POPPY), b(Blocks.DANDELION),
									b(Blocks.SWEET_BERRY_BUSH), b(Blocks.BUSH)};
							set(level, x, y + 1, z, plants[random.nextInt(plants.length)]);
						}
					}
					if (random.nextFloat() < 0.03F) {
						int x = c + side * (7 + random.nextInt(25));
						boulder(level, x, ground(level, x, z), z, random, b(Blocks.MOSSY_COBBLESTONE), b(Blocks.COBBLESTONE));
					}
				}
			}
			case TOWN -> {
				if ((z - START[1]) % 13 == 3 && z < END[1] - 14 && Math.abs(z - (START[1] + ConvoyEvents.SQUARE)) > 12) {
					for (int side : new int[] {-1, 1}) {
						if (random.nextFloat() < 0.85F) {
							house(level, c + side * (8 + random.nextInt(3)), z, side, random);
						}
					}
				}
				if (Math.abs(z - (START[1] + ConvoyEvents.SQUARE)) <= 1 && z == START[1] + ConvoyEvents.SQUARE) {
					square(level, c, z);
				}
				if (random.nextFloat() < 0.08F) {
					int side = random.nextBoolean() ? 1 : -1;
					int x = c + side * (5 + random.nextInt(2));
					set(level, x, Y + 1, z, random.nextBoolean() ? b(Blocks.BARREL) : b(Blocks.HAY_BLOCK));
				}
				if ((z - START[1]) % 9 == 0) {
					for (int side : new int[] {-1, 1}) {
						int x = c + side * 5;
						for (int y = 1; y <= 3; y++) {
							set(level, x, Y + y, z, b(Blocks.DARK_OAK_FENCE));
						}
						set(level, x, Y + 4, z, b(Blocks.LANTERN));
					}
				}
			}
			case CANYON -> {
				for (int side : new int[] {-1, 1}) {
					if (random.nextFloat() < 0.06F) {
						int x = c + side * (5 + random.nextInt(6));
						int y = ground(level, x, z);
						if (y == Y) {
							int hgt = 1 + random.nextInt(3);
							for (int k = 1; k <= hgt; k++) {
								set(level, x, y + k, z, b(Blocks.CACTUS));
							}
						}
					} else if (random.nextFloat() < 0.08F) {
						int x = c + side * (5 + random.nextInt(8));
						int y = ground(level, x, z);
						if (level.getBlockState(new BlockPos(x, y + 1, z)).isAir()) {
							set(level, x, y + 1, z, b(Blocks.DEAD_BUSH));
						}
					}
					// Ledges on the cliffs where raiders stand.
					if ((z - START[2]) % 11 == 5 && random.nextFloat() < 0.8F) {
						int x = c + side * (13 + random.nextInt(3));
						int y = ground(level, x, z);
						if (y > Y + 3) {
							LEDGES.add(new BlockPos(x, y + 1, z));
						}
					}
				}
				if (z == GAP_FROM - 1 || z == GAP_TO) {
					// The broken bridge's stumps.
					for (int dx = -ROAD - 1; dx <= ROAD + 1; dx++) {
						set(level, c + dx, Y, z, b(Blocks.SPRUCE_PLANKS));
					}
					for (int side : new int[] {-1, 1}) {
						for (int y = 1; y <= 2; y++) {
							set(level, c + side * (ROAD + 1), Y + y, z, b(Blocks.SPRUCE_LOG));
						}
						set(level, c + side * (ROAD + 1), Y + 3, z, b(Blocks.LANTERN));
					}
				}
				if (z == GAP_FROM + 4) {
					// An enemy camp on the far cliffs, for looks.
					tent(level, c + 18, z + 30, random);
					tent(level, c - 19, z + 44, random);
				}
			}
			case FROST -> {
				for (int side : new int[] {-1, 1}) {
					if (random.nextFloat() < 0.13F) {
						int x = c + side * (9 + random.nextInt(18));
						int y = ground(level, x, z);
						if (y < Y + 16) {
							spruce(level, x, y, z, random);
						}
					}
					if ((z - START[3]) % 13 == 7 && random.nextFloat() < 0.8F) {
						int x = c + side * (12 + random.nextInt(4));
						int y = ground(level, x, z);
						if (y > Y + 3) {
							LEDGES.add(new BlockPos(x, y + 1, z));
						}
					}
					if (random.nextFloat() < 0.05F) {
						int x = c + side * (5 + random.nextInt(3));
						set(level, x, Y + 1, z, b(Blocks.SNOW));
					}
				}
			}
			case FORTRESS -> fortress(level, z, c, random);
			default -> {
			}
		}
	}

	/** The Ruby Mine: a tunnel into the mountain with rails and ruby veins, where the truck is loaded. */
	private static void mine(ServerLevel level) {
		int c = X0;
		for (int z = -14; z <= -2; z++) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int y = 1; y <= 6; y++) {
					boolean frame = Math.abs(dx) == 4 || y == 6;
					set(level, c + dx, Y + y, z, frame ? (z % 4 == 0 ? b(Blocks.STRIPPED_SPRUCE_LOG) : b(Blocks.STONE)) : Blocks.AIR.defaultBlockState());
				}
			}
			set(level, c - 2, Y + 1, z, b(Blocks.RAIL));
			set(level, c + 2, Y + 1, z, b(Blocks.RAIL));
			if (z % 4 == 0) {
				set(level, c - 3, Y + 4, z, b(Blocks.LANTERN));
				set(level, c + 3, Y + 4, z, b(Blocks.LANTERN));
			}
		}
		for (int dx = -5; dx <= 5; dx++) {
			for (int y = 1; y <= 7; y++) {
				if (Math.abs(dx) == 5 || y == 7) {
					set(level, c + dx, Y + y, -1, y == 7 && dx == 0 ? ModBlocks.RUBY_BLOCK.defaultBlockState() : b(Blocks.SPRUCE_LOG));
				}
			}
		}
		for (int[] p : new int[][] {{-6, 2}, {6, 3}, {-7, 8}, {7, 10}}) {
			set(level, c + p[0], Y + 1, p[1], b(Blocks.BARREL));
			set(level, c + p[0], Y + 2, p[1], ModBlocks.RUBY_BLOCK.defaultBlockState());
		}
	}

	/** A depot: a fenced yard round the road with lamps, crates and parking pads (and a helipad by the canyon). */
	private static void depot(ServerLevel level, int index) {
		int c = X0;
		int z0 = DEPOT[index];
		int z1 = START[index];
		for (int z = z0 + 1; z < z1 - 1; z++) {
			for (int side : new int[] {-1, 1}) {
				int x = c + side * 13;
				boolean gap = z > z0 + 10 && z < z0 + 15;
				for (int y = 1; y <= 2; y++) {
					set(level, x, Y + y, z, gap ? Blocks.AIR.defaultBlockState() : y == 2 ? b(Blocks.STONE_BRICK_WALL) : b(Blocks.STONE_BRICKS));
				}
			}
		}
		for (int[] p : new int[][] {{-6, 3}, {6, 3}, {-6, 21}, {6, 21}}) {
			for (int y = 1; y <= 4; y++) {
				set(level, c + p[0], Y + y, z0 + p[1], b(Blocks.IRON_BARS));
			}
			set(level, c + p[0], Y + 5, z0 + p[1], b(Blocks.SEA_LANTERN));
		}
		for (int[] p : new int[][] {{-11, 4}, {-11, 5}, {11, 19}, {10, 19}, {-11, 20}}) {
			set(level, c + p[0], Y + 1, z0 + p[1], b(Blocks.BARREL));
		}
		set(level, c - 11, Y + 2, z0 + 4, b(Blocks.BARREL));
		// Parking pads (vehicles appear here), and a helipad.
		for (int[] pad : ConvoyEvents.pads(index)) {
			int px = pad[0];
			int pz = pad[1];
			boolean heli = pad[2] == 1;
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					BlockState s = heli ? (rim ? b(Blocks.CONCRETE.pick(DyeColor.YELLOW))
							: (dx == -1 || dx == 1 || dz == 0 && dx == 0) && Math.abs(dz) <= 1 ? b(Blocks.CONCRETE.pick(DyeColor.WHITE))
									: b(Blocks.CONCRETE.pick(DyeColor.GRAY)))
							: rim ? b(Blocks.CONCRETE.pick(DyeColor.YELLOW)) : b(Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY));
					set(level, c + px + dx, Y, z0 + pz + dz, s);
				}
			}
		}
	}

	private static void tree(ServerLevel level, int x, int y, int z, Random random) {
		if (!level.getBlockState(new BlockPos(x, y + 1, z)).isAir()) {
			return;
		}
		boolean birch = random.nextInt(4) == 0;
		boolean big = !birch && random.nextInt(3) == 0;
		int height = (birch ? 5 : 4) + random.nextInt(3) + (big ? 2 : 0);
		BlockState log = birch ? b(Blocks.BIRCH_LOG) : b(Blocks.OAK_LOG);
		BlockState leaf = birch ? leaves(Blocks.BIRCH_LEAVES) : random.nextInt(5) == 0 ? leaves(Blocks.DARK_OAK_LEAVES) : leaves(Blocks.OAK_LEAVES);
		for (int k = 1; k <= height; k++) {
			set(level, x, y + k, z, log);
		}
		int r = big ? 3 : 2;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -2; dy <= 1; dy++) {
					double dist = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.5);
					if (dist <= r + 0.4 && !(dx == 0 && dz == 0 && dy < 1) && random.nextInt(9) != 0) {
						BlockPos p = new BlockPos(x + dx, y + height + dy, z + dz);
						if (level.getBlockState(p).isAir()) {
							set(level, p, leaf);
						}
					}
				}
			}
		}
	}

	private static void spruce(ServerLevel level, int x, int y, int z, Random random) {
		if (!level.getBlockState(new BlockPos(x, y + 1, z)).isAir()) {
			return;
		}
		int height = 6 + random.nextInt(4);
		for (int k = 1; k <= height; k++) {
			set(level, x, y + k, z, b(Blocks.SPRUCE_LOG));
		}
		for (int k = 2; k <= height + 1; k++) {
			int r = Math.max(0, (height + 1 - k) / 2 - (k % 2 == 0 ? 0 : 1));
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.abs(dx) + Math.abs(dz) <= r + 1 && !(dx == 0 && dz == 0 && k <= height)) {
						BlockPos p = new BlockPos(x + dx, y + k, z + dz);
						if (level.getBlockState(p).isAir()) {
							set(level, p, leaves(Blocks.SPRUCE_LEAVES));
							if (level.getBlockState(p.above()).isAir() && random.nextInt(3) == 0) {
								set(level, p.above(), b(Blocks.SNOW));
							}
						}
					}
				}
			}
		}
		set(level, x, y + height + 2, z, b(Blocks.SNOW));
	}

	private static void boulder(ServerLevel level, int x, int y, int z, Random random, BlockState a, BlockState b) {
		int r = 1 + random.nextInt(2);
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = 0; dy <= r; dy++) {
					if (dx * dx + dz * dz + dy * dy * 1.6 <= r * r + 0.5) {
						set(level, x + dx, y + 1 + dy, z + dz, random.nextBoolean() ? a : b);
					}
				}
			}
		}
	}

	/** An Ember Town house: two floors, windows (where gunmen appear), a roof, often burning. */
	private static void house(ServerLevel level, int nearX, int z, int side, Random random) {
		int w = 6 + random.nextInt(2);
		int d = 7 + random.nextInt(3);
		int x0 = side > 0 ? nearX : nearX - w + 1;
		int x1 = x0 + w - 1;
		int z0 = z;
		int z1 = z + d - 1;
		int floors = random.nextInt(4) == 0 ? 1 : 2;
		int height = floors * 4;
		BlockState[] walls = {b(Blocks.OAK_PLANKS), b(Blocks.SPRUCE_PLANKS), b(Blocks.STONE_BRICKS), b(Blocks.BRICKS), b(Blocks.MUD_BRICKS)};
		BlockState wall = walls[random.nextInt(walls.length)];
		BlockState frame = random.nextBoolean() ? b(Blocks.STRIPPED_OAK_LOG) : b(Blocks.DARK_OAK_LOG);
		boolean ruined = random.nextInt(4) == 0;
		boolean burning = random.nextInt(3) != 0;
		int facing = side > 0 ? x0 : x1; // the wall that faces the road
		for (int x = x0; x <= x1; x++) {
			for (int zz = z0; zz <= z1; zz++) {
				set(level, x, Y, zz, b(Blocks.SPRUCE_PLANKS));
				for (int y = 1; y <= height; y++) {
					boolean edgeX = x == x0 || x == x1;
					boolean edgeZ = zz == z0 || zz == z1;
					BlockState want = Blocks.AIR.defaultBlockState();
					if (edgeX || edgeZ) {
						boolean corner = edgeX && edgeZ;
						want = corner || y % 4 == 0 ? frame : wall;
						boolean windowRow = y % 4 == 2;
						boolean windowCol = (edgeX ? (zz - z0) % 3 == 1 : (x - x0) % 3 == 1) && !corner;
						if (windowRow && windowCol) {
							want = ruined && random.nextBoolean() ? Blocks.AIR.defaultBlockState() : b(Blocks.GLASS_PANE);
							if (x == facing && y > 4) {
								// An upstairs window over the road: a gunman's spot.
								want = Blocks.AIR.defaultBlockState();
								WINDOWS.add(new BlockPos(x + side, Y + y - 1, zz));
							}
						}
						if (x == facing && zz == z0 + d / 2 && y <= 2) {
							want = Blocks.AIR.defaultBlockState(); // the door
						}
						if (ruined && y > height - 2 && random.nextInt(3) == 0) {
							want = Blocks.AIR.defaultBlockState();
						}
					} else if (y == 4 && floors == 2) {
						want = b(Blocks.SPRUCE_PLANKS); // the upstairs floor
					}
					set(level, x, Y + y, zz, want);
				}
			}
		}
		// Roof: a stepped ridge of dark slabs.
		for (int k = 0; k <= w / 2; k++) {
			for (int zz = z0 - 1; zz <= z1 + 1; zz++) {
				if (ruined && random.nextInt(4) == 0) {
					continue;
				}
				BlockState roof = burning && random.nextInt(5) == 0 ? b(Blocks.BLACKSTONE) : b(Blocks.DEEPSLATE_TILES);
				set(level, x0 + k, Y + height + 1 + k, zz, roof);
				set(level, x1 - k, Y + height + 1 + k, zz, roof);
			}
		}
		// A ladder-free stair: a block step up to the upstairs (gunmen stand by the windows).
		if (floors == 2) {
			set(level, side > 0 ? x0 + 2 : x1 - 2, Y + 4, z0 + d / 2, Blocks.AIR.defaultBlockState());
		}
		if (burning) {
			FIRES.add(new BlockPos((x0 + x1) / 2, Y + height + 2, (z0 + z1) / 2));
			set(level, (x0 + x1) / 2, Y + 1, z0 + 1, b(Blocks.CAMPFIRE));
		}
	}

	/** The town square: an open space with a well, where the tanks wait. */
	private static void square(ServerLevel level, int c, int z) {
		for (int dx = -16; dx <= 16; dx++) {
			for (int dz = -8; dz <= 12; dz++) {
				int x = c + dx;
				if (Math.abs(dx) > ROAD) {
					set(level, x, Y, z + dz, (dx + dz) % 3 == 0 ? b(Blocks.STONE_BRICKS) : b(Blocks.COBBLESTONE));
				}
			}
		}
		for (int[] w : new int[][] {{-10, 2}, {10, 6}}) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					set(level, c + w[0] + dx, Y + 1, z + w[1] + dz, dx == 0 && dz == 0 ? b(Blocks.WATER) : b(Blocks.COBBLESTONE_WALL));
				}
			}
		}
	}

	private static void tent(ServerLevel level, int x, int z, Random random) {
		int y = ground(level, x, z);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int h = 3 - Math.abs(dx);
				set(level, x + dx, y + h, z + dz, b(Blocks.WOOL.pick(DyeColor.BROWN)));
			}
		}
		set(level, x + 3, y + 1, z, b(Blocks.CAMPFIRE));
	}

	/** The Iron Fortress: walls either side of the courtyard, towers, braziers, and the vault gate across the road. */
	private static void fortress(ServerLevel level, int z, int c, Random random) {
		if (z >= COURTYARD && z < GATE) {
			for (int side : new int[] {-1, 1}) {
				int x = c + side * 30;
				for (int t = 0; t < 3; t++) {
					for (int y = 1; y <= 11; y++) {
						BlockState s = y == 11 && (z % 2 == 0) ? b(Blocks.POLISHED_BLACKSTONE_BRICK_WALL)
								: y == 10 ? b(Blocks.POLISHED_BLACKSTONE_BRICKS) : (z * 3 + y) % 13 == 0 ? b(Blocks.CRACKED_DEEPSLATE_BRICKS) : b(Blocks.DEEPSLATE_BRICKS);
						if (y == 11 && z % 2 != 0) {
							s = Blocks.AIR.defaultBlockState();
						}
						set(level, x + side * t, Y + y, z, s);
					}
				}
				if ((z - COURTYARD) % 8 == 4) {
					for (int y = 1; y <= 7; y++) {
						set(level, x - side, Y + y, z, y == 7 ? b(Blocks.SOUL_CAMPFIRE) : b(Blocks.POLISHED_BLACKSTONE));
					}
					set(level, x - side, Y + 8, z, Blocks.AIR.defaultBlockState());
				}
			}
			if (z == COURTYARD) {
				// The courtyard's entrance arch.
				archway(level, c, z, 6, b(Blocks.POLISHED_BLACKSTONE_BRICKS), b(Blocks.GILDED_BLACKSTONE));
				for (int side : new int[] {-1, 1}) {
					tower(level, c + side * 30, z);
				}
			}
			if (z == GATE - 1) {
				for (int side : new int[] {-1, 1}) {
					tower(level, c + side * 30, z);
				}
			}
		}
		if (z == GATE) {
			// The vault gate: a wall right across, with the portcullis in the middle (blown open when the Dreadnought falls).
			for (int x = c - 32; x <= c + 32; x++) {
				for (int y = 1; y <= 14; y++) {
					boolean opening = Math.abs(x + 0.5 - cx(z)) <= ROAD + 1.5 && y <= 7;
					set(level, x, Y + y, z, opening ? b(Blocks.IRON_BARS) : y == 14 ? b(Blocks.POLISHED_BLACKSTONE_BRICK_WALL) : y == 8 && Math.abs(x - c) <= 6
							? b(Blocks.GILDED_BLACKSTONE) : b(Blocks.DEEPSLATE_BRICKS));
					set(level, x, Y + y, z + 1, opening ? Blocks.AIR.defaultBlockState() : b(Blocks.DEEPSLATE_BRICKS));
				}
			}
		}
		if (z > GATE + 1 && z < GATE + 18) {
			// The vault: rubies piled high either side.
			for (int side : new int[] {-1, 1}) {
				for (int k = 0; k < 6; k++) {
					int x = c + side * (6 + k);
					int h = 3 - k / 2 + random.nextInt(2);
					for (int y = 1; y <= h; y++) {
						set(level, x, Y + y, z, random.nextInt(3) == 0 ? b(Blocks.GOLD_BLOCK) : ModBlocks.RUBY_BLOCK.defaultBlockState());
					}
				}
				set(level, c + side * 12, Y + 1, z, b(Blocks.DEEPSLATE_BRICKS));
				for (int y = 1; y <= 8; y++) {
					set(level, c + side * 13, Y + y, z, b(Blocks.DEEPSLATE_BRICKS));
				}
			}
			for (int x = c - 13; x <= c + 13; x++) {
				set(level, x, Y + 9, z, z % 4 == 0 && Math.abs(x - c) % 4 == 0 ? b(Blocks.SHROOMLIGHT) : b(Blocks.DEEPSLATE_TILES));
			}
		}
		if (z == GATE + 18) {
			for (int x = c - 13; x <= c + 13; x++) {
				for (int y = 1; y <= 9; y++) {
					set(level, x, Y + y, z, Math.abs(x - c) <= 1 && y >= 2 && y <= 6 ? ModBlocks.RUBY_BLOCK.defaultBlockState() : b(Blocks.DEEPSLATE_BRICKS));
				}
			}
		}
	}

	private static void archway(ServerLevel level, int c, int z, int half, BlockState stone, BlockState trim) {
		for (int dx = -half - 1; dx <= half + 1; dx++) {
			for (int y = 1; y <= 10; y++) {
				boolean open = Math.abs(dx) <= half && y <= 7;
				if (!open) {
					set(level, c + dx, Y + y, z, y == 8 ? trim : stone);
				}
			}
		}
		for (int dx = -half - 1; dx <= half + 1; dx++) {
			if (Math.abs(dx) > half - 3 && Math.abs(dx) <= half) {
				for (int y = 1; y <= 10; y++) {
					if (Math.abs(dx) > half - 1 || y >= 7) {
						set(level, c + dx, Y + y, z, y == 8 ? trim : stone);
					}
				}
			}
		}
	}

	private static void tower(ServerLevel level, int x, int z) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int y = 1; y <= 16; y++) {
					boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					BlockState s = !edge && y < 15 ? Blocks.AIR.defaultBlockState()
							: y == 16 ? ((dx + dz) % 2 == 0 ? b(Blocks.POLISHED_BLACKSTONE_BRICK_WALL) : Blocks.AIR.defaultBlockState())
									: y == 15 ? b(Blocks.POLISHED_BLACKSTONE_BRICKS) : y % 5 == 0 && edge && (dx == 0 || dz == 0) ? b(Blocks.IRON_BARS)
											: b(Blocks.DEEPSLATE_BRICKS);
					set(level, x + dx, Y + y, z + dz, s);
				}
			}
		}
		set(level, x, Y + 16, z, b(Blocks.SOUL_CAMPFIRE));
	}

	/** Open the vault gate: the portcullis goes. */
	static void openGate(ServerLevel level) {
		double c = cx(GATE);
		for (int x = (int) Math.floor(c) - ROAD - 2; x <= (int) Math.floor(c) + ROAD + 2; x++) {
			for (int y = 1; y <= 7; y++) {
				BlockPos p = new BlockPos(x, Y + y, GATE);
				if (level.getBlockState(p).is(Blocks.IRON_BARS)) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
	}
}
