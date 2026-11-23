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
 * Making the park look lovely, everywhere at once: glow-berry vines, roots and
 * lanterns hanging under the islands, flower patches, rocks, bushes and trees on open grass, warm lights in the hedges,
 * a big turning ruby over the fountain, and life in the sky (hot-air balloons, paper lanterns at night).
 */
final class Dressing {
	private Dressing() {
	}

	private static final int R = 175;
	private static final List<Mover> BALLOONS = new ArrayList<>();
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

	static void reset() {
		BALLOONS.clear();
		LANTERNS.clear();
		ruby = null;
	}

	/** After everything else is built (so it only fills in what's left open). */
	/** The islands get dressed a strip at a time over the first second or so (so the server isn't stuck at start-up). */
	private static Set<Long> keepOut = Set.of();
	private static int nextX = Integer.MAX_VALUE;
	private static int trees;
	private static final int STRIP = 24;

	static void build(ServerLevel level) {
		reset();
		keepOut = rideColumns();
		nextX = -R;
		trees = 0;
		TREES.clear();
		plazaRuby(level);
		sky(level);
	}

	/** One strip of the islands (x from {@code x1} to {@code x2}): under them, and on them. */
	private static void dressStrip(ServerLevel level, int x1, int x2) {
		underneath(level, x1, x2);
		ground(level, keepOut, x1, x2);
		if (x2 >= R) {
			dev.rubymod.RubyMod.LOGGER.info("Dressing: {} trees", trees);
		}
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	/** Every column a ride passes through or near (nothing gets planted in a ride's way). */
	private static Set<Long> rideColumns() {
		Set<Long> out = new HashSet<>();
		// The Ruby Rocket coaster too.
		for (Vec3 p : Coaster.pathPoints()) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					out.add(key((int) Math.floor(p.x) + dx, (int) Math.floor(p.z) + dz));
				}
			}
		}
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
	private static void underneath(ServerLevel level, int x1, int x2) {
		for (int x = x1; x <= x2; x++) {
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

	/** How far below the islands the old waterfalls' basins were (for tidying them away). */
	static final int BASIN_DROP = 30;

	// ----- On the ground -----

	private static final BlockState[] FLOWERS = {Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(),
		Blocks.ALLIUM.defaultBlockState(), Blocks.AZURE_BLUET.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(), Blocks.PINK_TULIP.defaultBlockState(),
		Blocks.WHITE_TULIP.defaultBlockState(), Blocks.RED_TULIP.defaultBlockState(), Blocks.LILY_OF_THE_VALLEY.defaultBlockState()};

	/** Flower patches, grass tufts, rocks, bushes and trees, on open grass away from rides and buildings. */
	private static void ground(ServerLevel level, Set<Long> keepOut, int x1, int x2) {
		for (int x = x1; x <= x2; x++) {
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
	}

	static void tick(ServerLevel level, int tick) {
		if (nextX <= R) {
			int x2 = Math.min(R, nextX + STRIP - 1);
			dressStrip(level, nextX, x2);
			nextX = x2 + 1;
		}
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
		if (tick % 40 == 0) {
			for (Mover m : BALLOONS) {
				Entity e = level.getEntity(m.id);
				if (e == null) {
					continue;
				}
				m.a += m.speed * 40;
				e.teleportTo(Math.cos(m.a) * m.r, m.y + Math.sin(m.a * 5) * 3, Math.sin(m.a) * m.r);
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
