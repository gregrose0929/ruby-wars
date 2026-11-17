package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Waterfall Plunge, on its little beach by Splash Island: a log boat floats along a wooden trough to the island's edge, tips over and
 * plunges down beside a waterfall into a pool on a little island far below (SPLASH!), then a chain winches it back up.
 */
final class Plunge {
	private Plunge() {
	}

	private static TrackRide ride;

	static void reset() {
		if (ride != null) {
			ride.reset();
		}
	}

	static void build(ServerLevel level) {
		beach(level);
		int f = FLOOR;
		// x, y, z: along the beach, over the edge, down, through the pool, round, up the chain lift, back to the beach.
		double[][] c = {{103, f + 1, -131}, {106, f + 1, -131}, {109, f + 1, -131}, {111.5, f + 0.6, -131}, {113.5, f - 4, -131}, {115.5, f - 13, -131},
			{117, f - 23, -131}, {119.5, f - 28.4, -131}, {123, f - 28.6, -131}, {126.5, f - 28.2, -134}, {125.5, f - 26, -139.5}, {119, f - 20.5, -144},
			{110, f - 13, -147}, {101, f - 6, -147}, {93, f - 1, -144}, {90.5, f + 1, -140}, {94, f + 1, -137}, {99, f + 1, -135.5}, {102, f + 1, -134}};
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : c) {
			pts.add(new Vec3(p[0] + 0.5, p[1], p[2] + 0.5));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		int plungeAt = -1;
		int poolAt = -1;
		for (int i = 0; i < path.size(); i++) {
			Vec3 p = path.get(i);
			Vec3 q = path.get(Math.min(path.size() - 1, i + 1));
			double dy = q.y - p.y;
			if (dy < -0.05) {
				speed[i] = 0.55;
				if (plungeAt < 0) {
					plungeAt = i;
				}
			} else if (dy > 0.02) {
				speed[i] = 0.13;
			} else {
				speed[i] = p.y < f - 20 ? 0.22 : 0.12;
				if (p.y < f - 20 && poolAt < 0 && plungeAt >= 0) {
					poolAt = i;
				}
			}
		}
		// Easing into the drop, and slowing in the pool.
		for (int i = Math.max(0, plungeAt - 30); i < plungeAt && plungeAt > 0; i++) {
			speed[i] = Math.max(speed[i], 0.12 + (i - plungeAt + 30) * 0.008);
		}
		ride = new TrackRide("plunge", path, speed, "log_boat", 1.8F, new Vec3(103.5, f + 1, -128.5), "Splashy Sam");
		ride.maxPitch = 70;
		ride.waitTicks = 60;
		ride.again = "SPLASH! Talk to Splashy Sam to go again.";
		if (plungeAt > 0) {
			ride.events.put(plungeAt, (lv, car) -> {
				ride.tell(lv, car, "Hold on tight!");
				lv.playSound(null, BlockPos.containing(car.pos), SoundEvents.GENERIC_SPLASH, SoundSource.AMBIENT, 1.0F, 1.4F);
			});
		}
		if (poolAt > 0) {
			ride.events.put(poolAt, (lv, car) -> {
				lv.sendParticles(ParticleTypes.SPLASH, car.pos.x, car.pos.y + 0.6, car.pos.z, 120, 1.6, 0.4, 1.6, 0.4);
				lv.sendParticles(ParticleTypes.CLOUD, car.pos.x, car.pos.y + 0.8, car.pos.z, 25, 1.2, 0.5, 1.2, 0.05);
				lv.playSound(null, BlockPos.containing(car.pos), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.AMBIENT, 2.0F, 0.9F);
				ride.tell(lv, car, "SPLASH!");
			});
		}
		trough(level, path);
		pool(level);
		HubPark.label(level, 103.5, f + 4.2, -128.5, "WATERFALL PLUNGE", ChatFormatting.AQUA, 1.3F);
		HubHosts.add(level, new Vec3(102.5, f + 1, -128.5), -90, VillagerProfession.FISHERMAN, VillagerType.SWAMP,
				new HubHosts.Host("plunge", "Splashy Sam", "Waterfall Plunge", 0x40C0FF, "minecraft:water_bucket",
						"Float along to the very edge of the island... then over the waterfall you go, all the way down into the pool below! "
								+ "Then the chain pulls you back up. Two to a log.",
						"Waterfall Plunge: right-click Splashy Sam!", HubHosts.rideButtons("Over the edge!"), (p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	/** A wooden trough with water in it all the way, and a chain up the lift. */
	private static void trough(ServerLevel level, List<Vec3> path) {
		BlockState wood = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState water = Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.LIGHT_BLUE).defaultBlockState();
		Vec3 last = null;
		for (int i = 0; i < path.size(); i += 5) {
			Vec3 p = path.get(i);
			if (last != null) {
				Vec3 d = p.subtract(last);
				Vec3 flat = new Vec3(d.x, 0, d.z);
				if (flat.lengthSqr() < 1e-6) {
					flat = new Vec3(1, 0, 0);
				}
				flat = flat.normalize();
				Vec3 side = new Vec3(-flat.z, 0, flat.x);
				Vec3 a = last.add(0, 0.02, 0);
				Vec3 b = p.add(0, 0.02, 0);
				HubPark.beam(level, a.add(0, -0.12, 0), b.add(0, -0.12, 0), 1.7F, 0.14F, wood);
				HubPark.beam(level, a.add(0, 0.05, 0), b.add(0, 0.05, 0), 1.35F, 0.06F, water);
				for (int s = -1; s <= 1; s += 2) {
					HubPark.beam(level, a.add(side.scale(0.85 * s)).add(0, 0.25, 0), b.add(side.scale(0.85 * s)).add(0, 0.25, 0), 0.14F, 0.6F, wood);
				}
				boolean up = p.y - last.y > 0.2;
				if (up) {
					HubPark.beam(level, a.add(0, 0.1, 0), b.add(0, 0.1, 0), 0.25F, 0.08F, Blocks.IRON_CHAIN.defaultBlockState());
				}
			}
			last = p;
		}
	}

	/** The little island down below with the splash pool in it. */
	private static void pool(ServerLevel level) {
		int top = FLOOR - 29;
		HubPark.island(level, 122, -132, 7, top);
		for (int x = 117; x <= 127; x++) {
			for (int z = -137; z <= -127; z++) {
				double d = Math.hypot(x - 122, z + 132);
				if (d <= 4.6) {
					level.setBlock(new BlockPos(x, top, z), Blocks.WATER.defaultBlockState(), 2);
					level.setBlock(new BlockPos(x, top - 1, z), Blocks.WATER.defaultBlockState(), 2);
					level.setBlock(new BlockPos(x, top - 2, z), Blocks.SAND.defaultBlockState(), 2);
					level.setBlock(new BlockPos(x, top + 1, z), Blocks.AIR.defaultBlockState(), 2);
				} else if (d <= 5.6) {
					level.setBlock(new BlockPos(x, top, z), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
					level.setBlock(new BlockPos(x, top + 1, z), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}

	static void tick(ServerLevel level) {
		if (ride != null) {
			ride.tick(level);
		}
	}

	static boolean busy() {
		return ride != null && !ride.cars.isEmpty();
	}

	/** The little beach the Plunge starts from (it was Fossil Beach). */
	static final int[][] BEACH = {{100, -130, 11}};

	private static void beach(ServerLevel level) {
		HubPark.blob(level, BEACH, FLOOR);
		int[] c = BEACH[0];
		var random = new java.util.Random(31);
		for (int x = c[0] - c[2] - 1; x <= c[0] + c[2] + 1; x++) {
			for (int z = c[1] - c[2] - 1; z <= c[1] + c[2] + 1; z++) {
				double d = Math.hypot(x - c[0], z - c[1]);
				if (d > c[2] - 0.4) {
					continue;
				}
				HubPark.set(level, x, FLOOR, z, Blocks.SAND.defaultBlockState());
				HubPark.set(level, x, FLOOR - 1, z, Blocks.SANDSTONE.defaultBlockState());
				BlockPos top = new BlockPos(x, FLOOR + 1, z);
				if (!level.getBlockState(top).isAir()) {
					level.setBlock(top, Blocks.AIR.defaultBlockState(), 2);
				}
				if (d >= c[2] - 1.5 && random.nextInt(4) == 0) {
					HubPark.set(level, x, FLOOR + 1, z, random.nextBoolean() ? Blocks.DEAD_BUSH.defaultBlockState() : Blocks.SHORT_DRY_GRASS.defaultBlockState());
				}
			}
		}
		// A path across to Splash Island.
		for (double t = 0; t <= 1.0; t += 0.05) {
			int x = (int) Math.round(92 + (85 - 92) * t);
			int z = (int) Math.round(-122 + (-114 - (-122)) * t);
			for (int w = -1; w <= 1; w++) {
				BlockPos p = new BlockPos(x + w, FLOOR, z);
				if (level.getBlockState(p).isAir() || level.getBlockState(p).is(Blocks.GRASS_BLOCK)) {
					level.setBlock(p, Blocks.SMOOTH_SANDSTONE.defaultBlockState(), 2);
				}
				BlockPos up = p.above();
				if (level.getBlockState(up).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
					level.setBlock(up, Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
		// Palm-ish trees.
		for (int[] t : new int[][] {{c[0] - 6, c[1] - 4}, {c[0] + 5, c[1] + 6}}) {
			HubPark.box(level, t[0], FLOOR + 1, t[1], t[0], FLOOR + 5, t[1], Blocks.JUNGLE_LOG.defaultBlockState());
			for (int[] l : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
				HubPark.set(level, t[0] + l[0], FLOOR + 6 - (Math.abs(l[0] + l[1]) == 2 ? 1 : 0), t[1] + l[1],
						Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
			}
			HubPark.set(level, t[0], FLOOR + 6, t[1], Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
		}
		// Beach umbrellas with towels, and a sandcastle.
		net.minecraft.world.item.DyeColor[][] stripes = {{net.minecraft.world.item.DyeColor.RED, net.minecraft.world.item.DyeColor.WHITE},
			{net.minecraft.world.item.DyeColor.LIGHT_BLUE, net.minecraft.world.item.DyeColor.YELLOW}};
		int[][] brollies = {{c[0] + 4, c[1] - 5}, {c[0] - 5, c[1] + 4}};
		for (int u = 0; u < brollies.length; u++) {
			int bx = brollies[u][0];
			int bz = brollies[u][1];
			HubPark.box(level, bx, FLOOR + 1, bz, bx, FLOOR + 3, bz, Blocks.BIRCH_FENCE.defaultBlockState());
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					boolean mid = dx == 0 && dz == 0;
					HubPark.set(level, bx + dx, FLOOR + 4, bz + dz, mid ? Blocks.WOOL.pick(stripes[u][0]).defaultBlockState()
							: Blocks.WOOL_SLAB.pick(stripes[u][Math.floorMod(dx + dz, 2)]).defaultBlockState());
				}
			}
			HubPark.set(level, bx, FLOOR + 5, bz, Blocks.CARPET.pick(stripes[u][1]).defaultBlockState());
			HubPark.set(level, bx + 1, FLOOR + 1, bz + 1, Blocks.CARPET.pick(stripes[u][1]).defaultBlockState());
			HubPark.set(level, bx + 1, FLOOR + 1, bz + 2, Blocks.CARPET.pick(stripes[u][1]).defaultBlockState());
		}
		int sx = c[0] - 2;
		int sz = c[1] + 6;
		HubPark.box(level, sx, FLOOR + 1, sz, sx + 2, FLOOR + 1, sz + 2, Blocks.SANDSTONE.defaultBlockState());
		for (int[] t : new int[][] {{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
			HubPark.set(level, sx + t[0], FLOOR + 2, sz + t[1], Blocks.SANDSTONE_WALL.defaultBlockState());
		}
		HubPark.set(level, sx + 1, FLOOR + 2, sz + 1, Blocks.CHISELED_SANDSTONE.defaultBlockState());
		HubPark.set(level, sx + 1, FLOOR + 3, sz + 1, Blocks.BANNER.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
	}
}
