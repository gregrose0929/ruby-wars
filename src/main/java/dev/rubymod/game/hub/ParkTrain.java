package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * The Park Train: a little steam train on a track high over the park, going round every island. There's a station (and
 * a conductor) on each: get on anywhere and it goes once round and back. It stops at every station on the way: crouch
 * there to get off (you're popped down to the ground by the conductor).
 */
final class ParkTrain {
	private ParkTrain() {
	}

	/** A station: its name, where the track goes over it, where you land when you get off (on the ground). */
	private record Station(String name, double x, double z, Vec3 ground) {
	}

	private static final Station[] STATIONS = {
		new Station("The Plaza", -12, -12, new Vec3(-10.5, FLOOR + 1, -9.5)),
		new Station("Mini Golf", -40, 60, new Vec3(-39.5, FLOOR + 1, 58.5)),
		new Station("Toyland", 6, 42, new Vec3(7.5, FLOOR + 1, 43.5)),
		new Station("Haunted Mansion", 28, 46, new Vec3(29.5, FLOOR + 1, 44.5)),
		new Station("Fun Park", 46, 8, new Vec3(46.5, FLOOR + 1, 5.5)),
		new Station("Splash Island", 54, -74, new Vec3(54.5, FLOOR + 1, -76.5)),
		new Station("Home Street", -60, -36, new Vec3(-61.5, FLOOR + 1, -40.5)),
		new Station("Speedway", -106, -42, new Vec3(-108.5, FLOOR + 1, -40.5))};
	private static final double[] STOP_AT = new double[STATIONS.length];
	/** Where each station's riders land, and its conductor stands: found on solid ground at build time. */
	private static final Vec3[] LAND = new Vec3[STATIONS.length];
	private static final Vec3[] HOST = new Vec3[STATIONS.length];
	private static TrackRide train;
	private static int height;

	static void reset() {
		if (train != null) {
			train.reset();
		}
	}

	static void build(ServerLevel level) {
		for (int s = 0; s < STATIONS.length; s++) {
			Vec3 g = STATIONS[s].ground();
			LAND[s] = ground(level, g, null);
			HOST[s] = ground(level, LAND[s].add(2, 0, 0), LAND[s]);
		}
		// The way round: through every station, with a bend or two between so it doesn't cut over the middle of anything.
		List<Vec3> ctrl = new ArrayList<>();
		double[][] via = {{-12, -12}, {-30, 20}, {-40, 60}, {-18, 52}, {6, 42}, {28, 46}, {40, 28}, {46, 8}, {40, -30}, {54, -74}, {20, -60}, {-20, -40},
			{-60, -36}, {-86, -40}, {-106, -42}, {-80, -24}, {-40, -16}, {-12, -12}};
		for (double[] v : via) {
			ctrl.add(new Vec3(v[0] + 0.5, 0, v[1] + 0.5));
		}
		List<Vec3> flat = HubRides.spline(ctrl, TrackRide.STEP);
		// High enough to pass over everything on the way (the tallest thing near the line, plus room for your head).
		int top = FLOOR + 18;
		for (Vec3 p : flat) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int y = FLOOR + 44; y > top - 5; y--) {
						if (!level.getBlockState(BlockPos.containing(p.x + dx, y, p.z + dz)).isAir()) {
							top = Math.max(top, y + 5);
							break;
						}
					}
				}
			}
		}
		height = Math.min(FLOOR + 44, top);
		dev.rubymod.RubyMod.LOGGER.info("Park Train: track at FLOOR + {}", height - FLOOR);
		List<Vec3> path = new ArrayList<>();
		for (Vec3 p : flat) {
			path.add(new Vec3(p.x, height, p.z));
		}
		double[] speed = new double[path.size()];
		java.util.Arrays.fill(speed, 0.32);
		for (int s = 0; s < STATIONS.length; s++) {
			int best = 0;
			for (int i = 0; i < path.size(); i++) {
				if (Math.hypot(path.get(i).x - STATIONS[s].x() - 0.5, path.get(i).z - STATIONS[s].z() - 0.5)
						< Math.hypot(path.get(best).x - STATIONS[s].x() - 0.5, path.get(best).z - STATIONS[s].z() - 0.5)) {
					best = i;
				}
			}
			if (s == 0) {
				best = 0;
			}
			STOP_AT[s] = best * TrackRide.STEP;
			// Slow down coming in, and speed up again going out.
			for (int k = -40; k <= 40; k++) {
				int i = Math.floorMod(best + k, path.size() - 1);
				speed[i] = Math.min(speed[i], 0.08 + Math.abs(k) * 0.006);
			}
		}
		train = new TrackRide("train", path, speed, "train", 3.0F, STATIONS[0].ground(), "a conductor");
		train.loop = true;
		train.seatsPer = 4;
		train.upright = true;
		train.seatHeight = 0.55;
		train.waitTicks = 80;
		train.again = "All the way round! Talk to a conductor to ride again.";
		train.exitNear = at -> LAND[nearest(at)];
		for (int s = 0; s < STATIONS.length; s++) {
			Station st = STATIONS[s];
			int i = (int) (STOP_AT[s] / TrackRide.STEP);
			if (s > 0) {
				train.events.put(i, (lv, car) -> {
					car.pause = 90;
					train.tell(lv, car, st.name() + " Station! Crouch to get off here, or stay on for the next stop.");
					lv.playSound(null, BlockPos.containing(car.pos), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.AMBIENT, 1.5F, 1.4F);
				});
			} else {
				train.events.put(path.size() - 3, (lv, car) -> {
					car.pause = 90;
					train.tell(lv, car, st.name() + " Station! Crouch to get off here, or stay on for the next stop.");
				});
			}
			train.events.put(Math.floorMod(i - 120, path.size() - 1), (lv, car) -> {
				train.tell(lv, car, "Next stop: " + st.name() + "!");
				lv.playSound(null, BlockPos.containing(car.pos), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.AMBIENT, 1.5F, 1.0F);
			});
		}
		track(level, path);
		for (int s = 0; s < STATIONS.length; s++) {
			station(level, s, path.get((int) (STOP_AT[s] / TrackRide.STEP)));
		}
	}

	private static int nearest(Vec3 at) {
		int best = 0;
		for (int s = 0; s < STATIONS.length; s++) {
			if (Math.hypot(STATIONS[s].x() - at.x, STATIONS[s].z() - at.z) < Math.hypot(STATIONS[best].x() - at.x, STATIONS[best].z() - at.z)) {
				best = s;
			}
		}
		return best;
	}

	/** The nearest open spot on solid ground to {@code near} (not {@code not}), looking outwards. */
	private static Vec3 ground(ServerLevel level, Vec3 near, Vec3 not) {
		int x0 = (int) Math.floor(near.x);
		int z0 = (int) Math.floor(near.z);
		for (int r = 0; r <= 8; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					BlockPos p = new BlockPos(x0 + dx, FLOOR + 1, z0 + dz);
					if (not != null && BlockPos.containing(not).equals(p)) {
						continue;
					}
					if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolidRender()
							&& level.getBlockState(p.below(2)).isSolidRender()) {
						return Vec3.atBottomCenterOf(p);
					}
				}
			}
		}
		return new Vec3(-0.5, FLOOR + 1, 6.5);
	}

	/** The track: a line of dark wood with rails on top. */
	private static void track(ServerLevel level, List<Vec3> path) {
		BlockState bed = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		java.util.Set<BlockPos> done = new java.util.HashSet<>();
		for (Vec3 p : path) {
			BlockPos b = BlockPos.containing(p.x, height - 1, p.z);
			if (!done.add(b)) {
				continue;
			}
			// A deck three wide, so the bends look smooth.
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos d = b.offset(dx, 0, dz);
					if (level.getBlockState(d).isAir()) {
						level.setBlock(d, bed, 2);
					}
				}
			}
		}
		for (BlockPos b : done) {
			BlockPos r = b.above();
			if (level.getBlockState(r).isAir()) {
				level.setBlock(r, Blocks.RAIL.defaultBlockState(), 3);
			}
		}
	}

	private static void station(ServerLevel level, int s, Vec3 at) {
		Station st = STATIONS[s];
		int cx = (int) Math.floor(at.x);
		int cz = (int) Math.floor(at.z);
		BlockState slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
		// A little platform round the track, with a roof on posts and lanterns.
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				BlockPos b = new BlockPos(cx + dx, height - 1, cz + dz);
				if (level.getBlockState(b).isAir()) {
					level.setBlock(b, slab, 2);
				}
				BlockPos roof = new BlockPos(cx + dx, height + 5, cz + dz);
				if (level.getBlockState(roof).isAir()) {
					level.setBlock(roof, Blocks.RED_NETHER_BRICK_SLAB.defaultBlockState(), 2);
				}
			}
		}
		for (int[] c : new int[][] {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
			for (int y = 0; y < 5; y++) {
				BlockPos b = new BlockPos(cx + c[0], height + y, cz + c[1]);
				if (level.getBlockState(b).isAir()) {
					level.setBlock(b, Blocks.DARK_OAK_FENCE.defaultBlockState(), 2);
				}
			}
			level.setBlock(new BlockPos(cx + c[0] / 3 * 2, height + 4, cz + c[1] / 3 * 2), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 2);
		}
		HubPark.label(level, cx + 0.5, height + 6.4, cz + 0.5, st.name().toUpperCase() + " STATION", ChatFormatting.GOLD, 1.3F);
		// On the ground: the conductor, and a sign.
		Vec3 host = HOST[s];
		HubPark.label(level, host.x, FLOOR + 3.8, host.z, "PARK TRAIN", ChatFormatting.GOLD, 1.0F);
		HubHosts.add(level, host, 180, VillagerProfession.MASON, VillagerType.TAIGA,
				new HubHosts.Host("train" + s, "Conductor", "Park Train", 0xE04030, "minecraft:rail",
						"All aboard the Park Train! It puffs round the whole park, high in the sky, stopping at every island. "
								+ "Crouch at any station to get off there, or stay on and go all the way round. Four seats: bring your friends!",
						"Park Train: right-click the Conductor to ride!", HubHosts.rideButtons("All aboard!"),
						(p, key) -> train.board(p, HubHosts.group(p, key), STOP_AT[s])));
	}

	static void tick(ServerLevel level) {
		if (train != null) {
			train.tick(level);
		}
	}

	static boolean busy() {
		return train != null && !train.cars.isEmpty();
	}
}
