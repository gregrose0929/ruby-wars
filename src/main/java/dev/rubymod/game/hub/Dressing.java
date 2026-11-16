package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.rubymod.mixin.DisplayAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Making the park look lovely, everywhere at once: waterfalls pouring off the island edges, glow-berry vines, roots and
 * lanterns hanging under the islands, flower patches, rocks, bushes and trees on open grass, warm lights in the hedges,
 * a big turning ruby over the fountain, and life in the sky (hot-air balloons, gulls, paper lanterns at night).
 */
final class Dressing {
	private Dressing() {
	}

	private static final int R = 175;
	private static final List<Vec3> FALLS = new ArrayList<>();
	private static final List<Mover> BALLOONS = new ArrayList<>();
	private static final List<Mover> BIRDS = new ArrayList<>();
	private static final List<Mover> LANTERNS = new ArrayList<>();
	private static UUID ruby;

	/** Something drifting round a circle: its display, centre, radius, height, speed, and where it's got to. */
	private static final class Mover {
		UUID id;
		double cx;
		double cz;
		double r;
		double y;
		double speed;
		double a;
		int phase;
	}

	/** The first waterfall (for the rainbow). */
	static Vec3 firstFall() {
		return FALLS.isEmpty() ? null : FALLS.get(0);
	}

	static void reset() {
		FALLS.clear();
		BALLOONS.clear();
		BIRDS.clear();
		LANTERNS.clear();
		ruby = null;
	}

	/** After everything else is built (so it only fills in what's left open). */
	static void build(ServerLevel level) {
		reset();
		Set<Long> keepOut = rideColumns();
		underneath(level);
		waterfalls(level, keepOut);
		ground(level, keepOut);
		plazaRuby(level);
		sky(level);
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	/** Every column a ride passes through or near (nothing gets planted in a ride's way). */
	private static Set<Long> rideColumns() {
		Set<Long> out = new HashSet<>();
		for (TrackRide r : TrackRide.ALL) {
			for (int i = 0; i < r.path.size(); i += 2) {
				Vec3 p = r.path.get(i);
				if (p.y > FLOOR + 12) {
					continue;
				}
				for (int dx = -4; dx <= 4; dx++) {
					for (int dz = -4; dz <= 4; dz++) {
						out.add(key((int) Math.floor(p.x) + dx, (int) Math.floor(p.z) + dz));
					}
				}
			}
		}
		return out;
	}

	private static int hash(int x, int z, int salt) {
		int h = x * 73856093 ^ z * 19349663 ^ salt * 83492791;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		return (h ^ (h >>> 15)) & 0x7fffffff;
	}

	private static boolean natural(BlockState s) {
		return s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.DIRT) || s.is(dev.rubymod.registry.ModBlocks.RUBY_BLOCK) || s.is(Blocks.GRASS_BLOCK)
				|| s.is(Blocks.SANDSTONE) || s.is(Blocks.SAND);
	}

	// ----- Under the islands -----

	/** Glow-berry vines, roots, spore blossoms and the odd lantern on a chain, hanging from every island's underside. */
	private static void underneath(ServerLevel level) {
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				// The underside: the lowest of a run of rock with open sky beneath it.
				int bottom = Integer.MIN_VALUE;
				boolean solid = false;
				for (int y = FLOOR + 2; y >= FLOOR - 40; y--) {
					BlockState s = level.getBlockState(new BlockPos(x, y, z));
					if (!s.isAir()) {
						solid = true;
						bottom = natural(s) ? y : Integer.MIN_VALUE;
					} else if (solid) {
						break;
					}
				}
				if (bottom == Integer.MIN_VALUE || bottom > FLOOR - 1) {
					continue;
				}
				boolean open = true;
				for (int y = bottom - 1; y >= bottom - 8 && open; y--) {
					open = level.getBlockState(new BlockPos(x, y, z)).isAir();
				}
				if (!open) {
					continue;
				}
				int h = hash(x, z, 1) % 1000;
				BlockPos below = new BlockPos(x, bottom - 1, z);
				if (h < 60) {
					// Glow-berry vines, two to six long, berries here and there.
					int len = 2 + hash(x, z, 2) % 5;
					for (int k = 0; k < len; k++) {
						BlockPos p = below.below(k);
						boolean end = k == len - 1;
						BlockState v = (end ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT).defaultBlockState()
								.setValue(CaveVines.BERRIES, hash(x, z, 10 + k) % 3 == 0);
						level.setBlock(p, v, 18);
					}
				} else if (h < 150) {
					level.setBlock(below, Blocks.HANGING_ROOTS.defaultBlockState(), 18);
				} else if (h < 156) {
					level.setBlock(below, Blocks.SPORE_BLOSSOM.defaultBlockState(), 18);
				} else if (h < 159) {
					int len = 2 + hash(x, z, 3) % 3;
					for (int k = 0; k < len; k++) {
						level.setBlock(below.below(k), Blocks.IRON_CHAIN.defaultBlockState(), 18);
					}
					level.setBlock(below.below(len), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 18);
				}
			}
		}
	}

	// ----- Waterfalls -----

	/** Little streams running off the edges of the big islands and pouring down into the clouds. */
	private static void waterfalls(ServerLevel level, Set<Long> keepOut) {
		List<int[]> picks = new ArrayList<>();
		Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
		// Waterfalls from before (the water going over the edge, with open air under it) count: no piling up more each time.
		int had = 0;
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				BlockPos p = new BlockPos(x, FLOOR, z);
				if (level.getBlockState(p).is(Blocks.WATER) && level.getFluidState(p).isSource() && level.getBlockState(p.below()).is(Blocks.WATER)
						&& !level.getFluidState(p.below()).isSource() && level.getBlockState(p.below(2)).is(Blocks.WATER)) {
					boolean near = false;
					for (int[] q : picks) {
						near |= Math.hypot(q[0] - x, q[1] - z) < 6;
					}
					// Which way is the island (the stream comes from there): the waterfall goes the other way.
					for (Direction n : dirs) {
						BlockState in = level.getBlockState(p.relative(n));
						if (!near && (in.is(Blocks.GRASS_BLOCK) || in.is(Blocks.WATER) || in.is(Blocks.DIRT))) {
							Direction d = n.getOpposite();
							picks.add(new int[] {x - d.getStepX(), z - d.getStepZ(), d.ordinal()});
							had++;
							break;
						}
					}
				}
			}
		}
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				if (hash(x, z, 5) % 7 != 0 || keepOut.contains(key(x, z))) {
					continue;
				}
				if (!level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.GRASS_BLOCK)) {
					continue;
				}
				for (Direction d : dirs) {
					// The edge: open air beyond, all the way down.
					BlockPos out = new BlockPos(x + d.getStepX(), FLOOR, z + d.getStepZ());
					boolean edge = true;
					for (int y = FLOOR + 1; y >= FLOOR - 60 && edge; y--) {
						edge = level.getBlockState(new BlockPos(out.getX(), y, out.getZ())).isAir();
					}
					if (!edge) {
						continue;
					}
					// Three blocks of grass behind (the stream's bed), and nothing built nearby.
					boolean ok = true;
					for (int k = 1; k <= 3 && ok; k++) {
						BlockPos b = new BlockPos(x - d.getStepX() * k, FLOOR, z - d.getStepZ() * k);
						ok = level.getBlockState(b).is(Blocks.GRASS_BLOCK);
					}
					for (int dx = -3; dx <= 3 && ok; dx++) {
						for (int dz = -3; dz <= 3 && ok; dz++) {
							for (int y = 1; y <= 4 && ok; y++) {
								BlockState s = level.getBlockState(new BlockPos(x + dx, FLOOR + y, z + dz));
								ok = s.isAir() || s.is(Blocks.FLOWERING_AZALEA_LEAVES) || s.is(Blocks.SHORT_GRASS) || s.canBeReplaced();
							}
						}
					}
					if (!ok) {
						continue;
					}
					boolean far = true;
					for (int[] p : picks) {
						far &= Math.hypot(p[0] - x, p[1] - z) > 50;
					}
					if (far) {
						picks.add(new int[] {x, z, d.ordinal()});
					}
					break;
				}
			}
		}
		for (int[] p : picks.subList(0, Math.min(10, picks.size()))) {
			Direction d = Direction.values()[p[2]];
			int x = p[0];
			int z = p[1];
			// A stream bed: water a block deep, three long, mossy stones and ferns along the banks.
			for (int k = 0; k <= 3; k++) {
				int bx = x - d.getStepX() * k;
				int bz = z - d.getStepZ() * k;
				level.setBlock(new BlockPos(bx, FLOOR - 1, bz), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
				level.setBlock(new BlockPos(bx, FLOOR, bz), Blocks.WATER.defaultBlockState(), 3);
				level.setBlock(new BlockPos(bx, FLOOR + 1, bz), Blocks.AIR.defaultBlockState(), 2);
				for (int side = -1; side <= 1; side += 2) {
					int sx = bx + d.getClockWise().getStepX() * side;
					int sz = bz + d.getClockWise().getStepZ() * side;
					BlockPos bank = new BlockPos(sx, FLOOR, sz);
					if (level.getBlockState(bank).is(Blocks.GRASS_BLOCK)) {
						level.setBlock(bank, (k + side) % 2 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState(), 2);
					}
					BlockPos top = bank.above();
					if (level.getBlockState(top).isAir() || level.getBlockState(top).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
						level.setBlock(top, k % 2 == 0 ? Blocks.FERN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
					}
				}
			}
			// Where it goes over the edge, and the lip under it.
			level.setBlock(new BlockPos(x + d.getStepX(), FLOOR, z + d.getStepZ()), Blocks.WATER.defaultBlockState(), 3);
			FALLS.add(new Vec3(x + d.getStepX() + 0.5, FLOOR, z + d.getStepZ() + 0.5));
			dev.rubymod.RubyMod.LOGGER.info("Dressing: waterfall at {} {} going {}", x + d.getStepX(), z + d.getStepZ(), d);
		}
		// Each waterfall lands in a little mossy rock basin thirty blocks down (rather than pouring all the way into the void).
		for (Vec3 f : FALLS) {
			basin(level, (int) Math.floor(f.x), (int) Math.floor(f.z));
		}
		dev.rubymod.RubyMod.LOGGER.info("Dressing: {} waterfalls ({} from before)", FALLS.size(), had);
	}

	static final int BASIN_DROP = 30;

	/** A small floating rock with a pool in its top, under a waterfall at x, z: the water lands in it and stops. */
	static void basin(ServerLevel level, int x, int z) {
		int b = FLOOR - BASIN_DROP;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
				level.setBlock(new BlockPos(x + dx, b, z + dz), (dx + dz) % 2 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
				if (ring && !corner) {
					level.setBlock(new BlockPos(x + dx, b + 1, z + dz), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
				} else if (corner) {
					level.setBlock(new BlockPos(x + dx, b + 1, z + dz), Blocks.MOSS_BLOCK.defaultBlockState(), 2);
				}
				// A rocky underside, tapering.
				for (int k = 1; k <= 3; k++) {
					if (Math.abs(dx) + Math.abs(dz) <= 4 - k) {
						level.setBlock(new BlockPos(x + dx, b - k, z + dz), k == 1 ? Blocks.STONE.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState(), 2);
					}
				}
			}
		}
		// Ferns round the rim, and glow-berry vines hanging below.
		for (int[] c : new int[][] {{-2, -2}, {2, 2}}) {
			level.setBlock(new BlockPos(x + c[0], b + 2, z + c[1]), Blocks.FERN.defaultBlockState(), 2);
		}
		for (int[] c : new int[][] {{1, 0}, {-1, 1}, {0, -1}}) {
			BlockPos top = new BlockPos(x + c[0], b - 4 + (Math.abs(c[0]) + Math.abs(c[1]) <= 1 ? 0 : 1), z + c[1]);
			if (level.getBlockState(top).isAir() && !level.getBlockState(top.above()).isAir()) {
				level.setBlock(top, Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, true), 18);
			}
		}
	}

	// ----- On the ground -----

	private static final BlockState[] FLOWERS = {Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(),
		Blocks.ALLIUM.defaultBlockState(), Blocks.AZURE_BLUET.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(), Blocks.PINK_TULIP.defaultBlockState(),
		Blocks.WHITE_TULIP.defaultBlockState(), Blocks.RED_TULIP.defaultBlockState(), Blocks.LILY_OF_THE_VALLEY.defaultBlockState()};

	/** Flower patches, grass tufts, rocks, bushes and trees, on open grass away from rides and buildings. */
	private static void ground(ServerLevel level, Set<Long> keepOut) {
		int trees = 0;
		TREES.clear();
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				BlockPos g = new BlockPos(x, FLOOR, z);
				if (keepOut.contains(key(x, z)) || !level.getBlockState(g).is(Blocks.GRASS_BLOCK)) {
					continue;
				}
				// Mini Golf's greens and the Speedway stay as they are.
				if (Math.hypot(x + 44, z - 44) < 22 || Math.hypot(x - Speedway.CX, z - Speedway.CZ) < 46) {
					continue;
				}
				BlockPos up = g.above();
				if (!level.getBlockState(up).isAir()) {
					// The edge hedge: a warm light tucked in every so often.
					if (level.getBlockState(up).is(Blocks.FLOWERING_AZALEA_LEAVES) && hash(x, z, 7) % 11 == 0) {
						level.setBlock(up, Blocks.SHROOMLIGHT.defaultBlockState(), 2);
					}
					continue;
				}
				if (!clear(level, x, z, 1, 3)) {
					continue;
				}
				int h = hash(x, z, 8) % 1000;
				double patch = Math.sin(x * 0.19 + z * 0.07) * Math.cos(z * 0.21 - x * 0.05);
				if (h < 10 && trees < 160 && clear(level, x, z, 2, 9) && noRides(level, x, z, 4) && spaced(x, z)) {
					tree(level, x, FLOOR + 1, z, hash(x, z, 9) % 4);
					TREES.add(new int[] {x, z});
					trees++;
				} else if (h < 8 && clear(level, x, z, 2, 3) && noRides(level, x, z, 3)) {
					rock(level, x, z, hash(x, z, 11));
				} else if (h < 20 && clear(level, x, z, 2, 3) && noRides(level, x, z, 3)) {
					level.setBlock(up, (h % 2 == 0 ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState(), 2);
				} else if (patch > 0.55 ? h < 380 : h < 25) {
					level.setBlock(up, FLOWERS[(int) ((patch + 1) * 4 + hash(x, z, 12) % 3) % FLOWERS.length], 2);
				} else if (h < 140) {
					level.setBlock(up, (h % 5 == 0 ? Blocks.FERN : Blocks.SHORT_GRASS).defaultBlockState(), 2);
				}
			}
		}
		dev.rubymod.RubyMod.LOGGER.info("Dressing: {} trees", trees);
	}

	private static final List<int[]> TREES = new ArrayList<>();

	/** Trees not too close together. */
	private static boolean spaced(int x, int z) {
		for (int[] t : TREES) {
			if (Math.abs(t[0] - x) < 7 && Math.abs(t[1] - z) < 7) {
				return false;
			}
		}
		return true;
	}

	/** Nothing but air (or plants) in a square {@code r} round x, z, from the ground up to {@code up}. */
	private static boolean clear(ServerLevel level, int x, int z, int r, int up) {
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				BlockState floor = level.getBlockState(new BlockPos(x + dx, FLOOR, z + dz));
				if (!floor.is(Blocks.GRASS_BLOCK)) {
					return false;
				}
				for (int y = 1; y <= up; y++) {
					BlockState s = level.getBlockState(new BlockPos(x + dx, FLOOR + y, z + dz));
					if (!s.isAir() && !s.canBeReplaced()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** No rides, hosts or models nearby (they're entities: the block check can't see them). */
	private static boolean noRides(ServerLevel level, int x, int z, int r) {
		return level.getEntities((Entity) null, new AABB(x - r, FLOOR - 2, z - r, x + r + 1, FLOOR + 14, z + r + 1), e -> true).isEmpty();
	}

	private static void rock(ServerLevel level, int x, int z, int h) {
		BlockState[] stones = {Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(),
			Blocks.TUFF.defaultBlockState()};
		level.setBlock(new BlockPos(x, FLOOR + 1, z), stones[h % stones.length], 2);
		if (h % 3 == 0) {
			level.setBlock(new BlockPos(x + 1, FLOOR + 1, z), stones[(h / 3) % stones.length], 2);
		}
		if (h % 5 == 0) {
			level.setBlock(new BlockPos(x, FLOOR + 1, z + 1), Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState(), 2);
		}
	}

	/** A tree: 0 a big round oak, 1 a birch, 2 a cherry, 3 a little spruce. */
	static void tree(ServerLevel level, int x, int y, int z, int kind) {
		BlockState log = switch (kind) {
			case 1 -> Blocks.BIRCH_LOG.defaultBlockState();
			case 2 -> Blocks.CHERRY_LOG.defaultBlockState();
			case 3 -> Blocks.SPRUCE_LOG.defaultBlockState();
			default -> Blocks.OAK_LOG.defaultBlockState();
		};
		BlockState leaves = (switch (kind) {
			case 1 -> Blocks.BIRCH_LEAVES;
			case 2 -> Blocks.CHERRY_LEAVES;
			case 3 -> Blocks.SPRUCE_LEAVES;
			default -> Blocks.OAK_LEAVES;
		}).defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		int trunk = kind == 1 ? 5 : kind == 3 ? 2 : 4;
		for (int k = 0; k < trunk; k++) {
			level.setBlock(new BlockPos(x, y + k, z), log, 2);
		}
		if (kind == 3) {
			// A spruce: layers getting smaller to a point.
			int[] radii = {2, 2, 1, 1, 1, 0, 0};
			for (int k = 0; k < radii.length; k++) {
				int r = radii[k];
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) {
							continue;
						}
						BlockPos p = new BlockPos(x + dx, y + trunk + k - 1, z + dz);
						if (level.getBlockState(p).isAir()) {
							level.setBlock(p, leaves, 2);
						}
					}
				}
			}
			level.setBlock(new BlockPos(x, y + trunk, z), log, 2);
			level.setBlock(new BlockPos(x, y + trunk + 1, z), log, 2);
			return;
		}
		// A rounded crown.
		double rad = kind == 0 ? 3.2 : kind == 2 ? 3.0 : 2.3;
		int cy = y + trunk + 1;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -2; dy <= 3; dy++) {
				for (int dz = -4; dz <= 4; dz++) {
					double d = Math.sqrt(dx * dx + dy * dy * 1.6 + dz * dz);
					if (d > rad + (hash(x + dx, z + dz, dy + 20) % 10) * 0.04) {
						continue;
					}
					BlockPos p = new BlockPos(x + dx, cy + dy, z + dz);
					if (level.getBlockState(p).isAir()) {
						level.setBlock(p, leaves, 2);
					}
				}
			}
		}
		for (int k = trunk; k < trunk + 2; k++) {
			level.setBlock(new BlockPos(x, y + k, z), log, 2);
		}
	}

	// ----- The plaza's ruby -----

	private static void plazaRuby(ServerLevel level) {
		Display.ItemDisplay d = net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
		if (d == null) {
			return;
		}
		d.snapTo(0.5, FLOOR + 8.6, 0.5, 0, 0);
		((dev.rubymod.mixin.ItemDisplayAccessor) d).rubymod$setItemStack(new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.RUBY));
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(3.2F), new Quaternionf()));
		((DisplayAccessor) d).rubymod$setBrightness(new Brightness(15, 15));
		((DisplayAccessor) d).rubymod$setViewRange(6.0F);
		((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(10);
		HubPark.keep(d);
		level.addFreshEntity(d);
		ruby = d.getUUID();
	}

	// ----- In the sky -----

	private static void sky(ServerLevel level) {
		var random = new java.util.Random(12);
		String[] balloons = {"balloon_red", "balloon_blue", "balloon_rainbow", "balloon_red", "balloon_blue"};
		for (int i = 0; i < balloons.length; i++) {
			Mover m = new Mover();
			m.cx = 0;
			m.cz = 0;
			m.r = 150 + random.nextDouble() * 70;
			m.y = FLOOR + 25 + random.nextDouble() * 40;
			m.speed = (0.0006 + random.nextDouble() * 0.0005) * (i % 2 == 0 ? 1 : -1);
			m.a = random.nextDouble() * Math.PI * 2;
			Display.ItemDisplay d = HubPark.model(level, new Vec3(Math.cos(m.a) * m.r, m.y, Math.sin(m.a) * m.r), 0, balloons[i], 9.0F, 0);
			if (d != null) {
				((DisplayAccessor) d).rubymod$setViewRange(10.0F);
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(40);
				m.id = d.getUUID();
				BALLOONS.add(m);
			}
		}
		// Gulls, in little flocks circling over the big islands.
		double[][] flocks = {{60, 20}, {40, -100}, {-60, -70}, {0, 60}, {-110, 0}};
		for (double[] f : flocks) {
			for (int k = 0; k < 4; k++) {
				Mover m = new Mover();
				m.cx = f[0] + random.nextDouble() * 10;
				m.cz = f[1] + random.nextDouble() * 10;
				m.r = 14 + random.nextDouble() * 12;
				m.y = FLOOR + 32 + random.nextDouble() * 10;
				m.speed = 0.012 + random.nextDouble() * 0.006;
				m.a = random.nextDouble() * Math.PI * 2;
				m.phase = random.nextInt(20);
				Display.ItemDisplay d = HubPark.model(level, new Vec3(m.cx + Math.cos(m.a) * m.r, m.y, m.cz + Math.sin(m.a) * m.r), 0, "bird_up", 1.2F, 0);
				if (d != null) {
					((DisplayAccessor) d).rubymod$setViewRange(3.0F);
					((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(5);
					m.id = d.getUUID();
					BIRDS.add(m);
				}
			}
		}
	}

	static void tick(ServerLevel level, int tick) {
		// The ruby turns slowly and glints.
		if (ruby != null && tick % 10 == 0) {
			Entity e = level.getEntity(ruby);
			if (e != null) {
				e.setYRot(e.getYRot() + 9);
				if (tick % 40 == 0) {
					level.sendParticles(ParticleTypes.END_ROD, 0.5, FLOOR + 8.6, 0.5, 3, 0.8, 0.8, 0.8, 0.01);
				}
			}
		}
		if (tick % 5 == 0) {
			for (Mover m : BIRDS) {
				Entity e = level.getEntity(m.id);
				if (e == null) {
					continue;
				}
				m.a += m.speed * 5;
				double x = m.cx + Math.cos(m.a) * m.r;
				double z = m.cz + Math.sin(m.a) * m.r;
				double y = m.y + Math.sin(m.a * 3) * 1.5;
				e.teleportTo(x, y, z);
				// Flying round the circle: facing along it.
				e.setYRot((float) Math.toDegrees(Math.atan2(Math.sin(m.a), Math.cos(m.a))) + 180);
				if ((tick / 5 + m.phase) % 4 == 0) {
					setModel(e, ((tick / 5 + m.phase) / 4) % 2 == 0 ? "bird_up" : "bird_down");
				}
			}
		}
		if (tick % 40 == 0) {
			for (Mover m : BALLOONS) {
				Entity e = level.getEntity(m.id);
				if (e == null) {
					continue;
				}
				m.a += m.speed * 40;
				e.teleportTo(Math.cos(m.a) * m.r, m.y + Math.sin(m.a * 5) * 3, Math.sin(m.a) * m.r);
			}
			// Mist where the waterfalls go over.
			for (Vec3 f : FALLS) {
				level.sendParticles(ParticleTypes.CLOUD, f.x, f.y - 2, f.z, 2, 0.4, 0.6, 0.4, 0.01);
				level.sendParticles(ParticleTypes.SPLASH, f.x, f.y - BASIN_DROP + 1.2, f.z, 12, 0.6, 0.2, 0.6, 0.1);
				level.sendParticles(ParticleTypes.CLOUD, f.x, f.y - BASIN_DROP + 1.6, f.z, 2, 0.6, 0.3, 0.6, 0.01);
			}
		}
		lanterns(level, tick);
	}

	private static void setModel(Entity e, String id) {
		if (e instanceof Display.ItemDisplay d) {
			net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
			stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_" + id));
			((dev.rubymod.mixin.ItemDisplayAccessor) d).rubymod$setItemStack(stack);
		}
	}

	/** At night, paper lanterns float up from round the park, glowing, and fade away high up. */
	private static void lanterns(ServerLevel level, int tick) {
		long time = level.getOverworldClockTime() % 24000;
		boolean night = time > 13000 && time < 23000;
		if (tick % 10 != 0) {
			return;
		}
		var random = level.getRandom();
		for (var it = LANTERNS.iterator(); it.hasNext();) {
			Mover m = it.next();
			Entity e = level.getEntity(m.id);
			if (e == null) {
				it.remove();
				continue;
			}
			m.y += 0.6;
			m.a += 0.05;
			if (m.y > FLOOR + 70) {
				HubPark.discard(level, m.id);
				it.remove();
				continue;
			}
			e.teleportTo(m.cx + Math.sin(m.a) * 1.5, m.y, m.cz + Math.cos(m.a * 0.7) * 1.5);
		}
		if (night && LANTERNS.size() < 24 && random.nextInt(3) == 0) {
			Mover m = new Mover();
			double a = random.nextDouble() * Math.PI * 2;
			double r = 10 + random.nextDouble() * 120;
			m.cx = Math.cos(a) * r;
			m.cz = Math.sin(a) * r;
			m.y = FLOOR + 3;
			m.a = random.nextDouble() * 6;
			Display.BlockDisplay d = HubPark.piece(level, new Vec3(m.cx, m.y, m.cz), Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.ORANGE).defaultBlockState(),
					new Vector3f(0.45F, 0.6F, 0.45F), new Quaternionf());
			if (d != null) {
				((DisplayAccessor) d).rubymod$setBrightness(new Brightness(15, 15));
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(10);
				((DisplayAccessor) d).rubymod$setViewRange(6.0F);
				d.addTag(HubRides.TEMP);
				HubPark.live(d);
				m.id = d.getUUID();
				LANTERNS.add(m);
			}
		}
	}
}
