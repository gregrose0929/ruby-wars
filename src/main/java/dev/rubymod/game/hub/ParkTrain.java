package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.rubymod.mixin.DisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Park Train: a little steam train on a railway high over the park, going round every island. There's a station (and
 * a conductor down on the ground) on each: get on anywhere and it goes once round and back. It stops at every station on
 * the way: crouch there to get off (you're popped down to the ground by the conductor).
 *
 * The railway is all models (smooth curves, sleepers and rails, wooden trestles down to the islands, little stations
 * with canopies), not blocks: nothing to walk into, and it's rebuilt fresh every start.
 */
final class ParkTrain {
	private ParkTrain() {
	}

	/** A station: its name, where the track goes over it, the way the train goes through (a straight bit), where you land. */
	private record Station(String name, double x, double z, int dx, int dz, Vec3 ground) {
	}

	private static final Station[] STATIONS = {
		new Station("The Plaza", -28, 1, 0, 1, new Vec3(-24.5, FLOOR + 1, 1.5)),
		new Station("Mini Golf", -40, 60, 1, 0, new Vec3(-39.5, FLOOR + 1, 57.5)),
		new Station("Toyland", 6, 46, 1, 0, new Vec3(6.5, FLOOR + 1, 44.5)),
		new Station("Haunted Mansion", 21, 50, 0, -1, new Vec3(27.5, FLOOR + 1, 44.5)),
		new Station("Fun Park", 46, 8, 0, -1, new Vec3(46.5, FLOOR + 1, 5.5)),
		new Station("Splash Island", 54, -60, 0, -1, new Vec3(54.5, FLOOR + 1, -76.5)),
		new Station("Home Street", -60, -34, -1, 0, new Vec3(-61.5, FLOOR + 1, -40.5)),
		new Station("Speedway", -106, -44, -1, 0, new Vec3(-108.5, FLOOR + 1, -40.5))};
	/** The bends between one station and the next. */
	private static final double[][][] BETWEEN = {
		{{-36, 30}, {-54, 50}},
		{{-16, 54}},
		{{15, 58}},
		{{22, 27}, {38, 18}},
		{{48, -24}},
		{{49, -71}, {36, -70}, {14, -64}, {-12, -54}, {-38, -38}},
		{{-84, -38}},
		{{-128, -36}, {-130, -12}, {-112, 14}, {-70, 8}, {-48, -14}, {-34, -20}}};
	/** Where the old block-built track's stations were (cleared away once). */
	private static final int[][] OLD_STATIONS = {{-12, -12}, {-40, 60}, {6, 42}, {28, 46}, {46, 8}, {54, -74}, {-60, -36}, {-106, -42}};
	private static final double[] STOP_AT = new double[STATIONS.length];
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
		clearOldTrack(level);
		for (int s = 0; s < STATIONS.length; s++) {
			LAND[s] = ground(level, STATIONS[s].ground(), null);
			HOST[s] = ground(level, LAND[s].add(2, 0, 0), LAND[s]);
		}
		// The way round: straight through every station, bending between.
		List<Vec3> ctrl = new ArrayList<>();
		for (int s = 0; s < STATIONS.length; s++) {
			Station st = STATIONS[s];
			for (int k = -8; k <= 8; k += 4) {
				ctrl.add(new Vec3(st.x() + st.dx() * k + 0.5, 0, st.z() + st.dz() * k + 0.5));
			}
			for (double[] v : BETWEEN[s]) {
				ctrl.add(new Vec3(v[0] + 0.5, 0, v[1] + 0.5));
			}
		}
		ctrl.add(ctrl.get(0));
		ctrl.add(0, ctrl.get(ctrl.size() - 2));
		List<Vec3> flat = HubRides.spline(ctrl, TrackRide.STEP);
		// Drop the lead-in (from the last bend round to the first station), so the loop starts at the Plaza.
		int start = 0;
		Vec3 first = ctrl.get(1);
		for (int i = 0; i < flat.size(); i++) {
			if (flat.get(i).distanceToSqr(first) < flat.get(start).distanceToSqr(first)) {
				start = i;
			}
			if (i > 400) {
				break;
			}
		}
		flat = new ArrayList<>(flat.subList(start, flat.size()));
		// High enough to pass over everything on the way (the tallest thing near the line, plus room for your head).
		int top = FLOOR + 20;
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
		java.util.Arrays.fill(speed, 0.34);
		for (int s = 0; s < STATIONS.length; s++) {
			int best = 0;
			for (int i = 0; i < path.size(); i++) {
				if (Math.hypot(path.get(i).x - STATIONS[s].x() - 0.5, path.get(i).z - STATIONS[s].z() - 0.5)
						< Math.hypot(path.get(best).x - STATIONS[s].x() - 0.5, path.get(best).z - STATIONS[s].z() - 0.5)) {
					best = i;
				}
			}
			STOP_AT[s] = best * TrackRide.STEP;
			// Slow down coming in, and speed up again going out.
			for (int k = -50; k <= 50; k++) {
				int i = Math.floorMod(best + k, path.size() - 1);
				speed[i] = Math.min(speed[i], 0.07 + Math.abs(k) * 0.0055);
			}
		}
		train = new TrackRide("train", path, speed, "train", 3.0F, LAND[0], "a conductor");
		train.loop = true;
		train.seatsPer = 4;
		train.upright = true;
		train.seatHeight = 0.7;
		train.waitTicks = 80;
		train.again = "All the way round! Talk to a conductor to ride again.";
		train.exitNear = at -> LAND[nearest(at)];
		for (int s = 0; s < STATIONS.length; s++) {
			Station st = STATIONS[s];
			int i = (int) (STOP_AT[s] / TrackRide.STEP);
			train.events.put(s == 0 ? path.size() - 3 : i, (lv, car) -> {
				car.pause = 90;
				train.tell(lv, car, st.name() + " Station! Crouch to get off here, or stay on for the next stop.");
				lv.playSound(null, BlockPos.containing(car.pos), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.AMBIENT, 1.5F, 1.4F);
			});
			train.events.put(Math.floorMod(i - 140, path.size() - 1), (lv, car) -> {
				train.tell(lv, car, "Next stop: " + st.name() + "!");
				lv.playSound(null, BlockPos.containing(car.pos), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.AMBIENT, 1.5F, 1.0F);
			});
		}
		// A toot as it sets off.
		train.events.put(8, (lv, car) -> lv.playSound(null, BlockPos.containing(car.pos), SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.AMBIENT, 1.5F, 1.6F));
		track(level, path);
		for (int s = 0; s < STATIONS.length; s++) {
			station(level, s);
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

	/** The first (block-built) railway: its rails, deck and stations go, once. */
	private static void clearOldTrack(ServerLevel level) {
		java.nio.file.Path mark = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("rubywars").resolve("train_v2");
		if (java.nio.file.Files.exists(mark)) {
			return;
		}
		int n = 0;
		for (int x = -175; x <= 175; x++) {
			for (int z = -175; z <= 175; z++) {
				for (int y = FLOOR + 17; y <= FLOOR + 48; y++) {
					BlockPos p = new BlockPos(x, y, z);
					if (!level.getBlockState(p).is(Blocks.RAIL)) {
						continue;
					}
					level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
					n++;
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							BlockPos d = p.offset(dx, -1, dz);
							if (level.getBlockState(d).is(Blocks.DARK_OAK_PLANKS)) {
								level.setBlock(d, Blocks.AIR.defaultBlockState(), 18);
							}
						}
					}
				}
			}
		}
		for (int[] o : OLD_STATIONS) {
			for (int x = o[0] - 5; x <= o[0] + 5; x++) {
				for (int z = o[1] - 5; z <= o[1] + 5; z++) {
					for (int y = FLOOR + 16; y <= FLOOR + 52; y++) {
						BlockPos p = new BlockPos(x, y, z);
						BlockState s = level.getBlockState(p);
						if (s.is(Blocks.SMOOTH_STONE_SLAB) || s.is(Blocks.RED_NETHER_BRICK_SLAB) || s.is(Blocks.DARK_OAK_FENCE) || s.is(Blocks.LANTERN)
								|| s.is(Blocks.DARK_OAK_PLANKS)) {
							level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
						}
					}
				}
			}
		}
		dev.rubymod.RubyMod.LOGGER.info("Park Train: cleared the old track ({} rails)", n);
		try {
			java.nio.file.Files.createDirectories(mark.getParent());
			java.nio.file.Files.writeString(mark, "done");
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't mark the old track cleared", e);
		}
	}

	// ----- The railway -----

	/** Track pieces all the way round, and wooden trestles down to the islands wherever there's ground under the line. */
	private static void track(ServerLevel level, List<Vec3> path) {
		double every = 1.8;
		double next = 0;
		double along = 0;
		double nextLeg = 6;
		int pieces = 0;
		for (int i = 1; i < path.size(); i++) {
			Vec3 a = path.get(i - 1);
			Vec3 b = path.get(i);
			along += a.distanceTo(b);
			if (along < next) {
				continue;
			}
			next = along + every;
			Vec3 ahead = path.get(Math.min(path.size() - 1, i + 3));
			Vec3 back = path.get(Math.max(0, i - 3));
			double dx = ahead.x - back.x;
			double dz = ahead.z - back.z;
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			Display.ItemDisplay d = HubPark.model(level, b, yaw, "track", 2.0F, 0.5625F);
			if (d != null) {
				((DisplayAccessor) d).rubymod$setViewRange(1.6F);
			}
			// Every so often, a little lamp at the side of the line (alternating sides): it twinkles along the track at night.
			if (++pieces % 9 == 0) {
				double len = Math.hypot(dx, dz);
				double side = pieces % 18 == 0 ? 1.05 : -1.05;
				Vec3 foot = b.add(-dz / len * side, -0.1, dx / len * side);
				HubPark.beam(level, foot, foot.add(0, 1.3, 0), 0.08F, 0.08F, Blocks.DARK_OAK_PLANKS.defaultBlockState());
				Display.BlockDisplay lamp = HubPark.piece(level, foot.add(0, 1.5, 0), Blocks.LANTERN.defaultBlockState(), new Vector3f(0.45F, 0.45F, 0.45F),
						new Quaternionf());
				if (lamp != null) {
					((DisplayAccessor) lamp).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
				}
			}
			if (along >= nextLeg) {
				if (trestle(level, b, dx, dz)) {
					nextLeg = along + 14;
				} else {
					nextLeg = along + 3;
				}
			}
		}
	}

	/** A trestle under the line at {@code p}, if there's island under it and nothing in the way. */
	private static boolean trestle(ServerLevel level, Vec3 p, double dx, double dz) {
		int x = (int) Math.floor(p.x);
		int z = (int) Math.floor(p.z);
		int g = Integer.MIN_VALUE;
		for (int y = height - 3; y >= FLOOR - 4; y--) {
			BlockState s = level.getBlockState(new BlockPos(x, y, z));
			if (!s.isAir()) {
				g = s.isSolidRender() && s.getFluidState().isEmpty() ? y : Integer.MIN_VALUE;
				break;
			}
		}
		if (g == Integer.MIN_VALUE) {
			return false;
		}
		double len = Math.hypot(dx, dz);
		Vec3 side = new Vec3(-dz / len, 0, dx / len);
		// Keep clear of anything standing near the legs.
		for (int k = -2; k <= 2; k++) {
			for (int y = g + 1; y < height - 2; y++) {
				if (!level.getBlockState(BlockPos.containing(p.x + side.x * k * 0.8, y, p.z + side.z * k * 0.8)).isAir()) {
					return false;
				}
			}
		}
		BlockState wood = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState();
		double top = height - 0.35;
		double base = g + 1;
		Vec3 l1 = p.add(side.scale(1.6));
		Vec3 l2 = p.add(side.scale(-1.6));
		Vec3 t1 = p.add(side.scale(0.55));
		Vec3 t2 = p.add(side.scale(-0.55));
		HubPark.beam(level, new Vec3(l1.x, base, l1.z), new Vec3(t1.x, top, t1.z), 0.32F, 0.32F, wood);
		HubPark.beam(level, new Vec3(l2.x, base, l2.z), new Vec3(t2.x, top, t2.z), 0.32F, 0.32F, wood);
		// Cross-pieces up the trestle.
		for (double y = base + 3; y < top - 1; y += 4) {
			double f = (y - base) / (top - base);
			Vec3 c1 = l1.lerp(t1, f);
			Vec3 c2 = l2.lerp(t2, f);
			HubPark.beam(level, new Vec3(c1.x, y, c1.z), new Vec3(c2.x, y, c2.z), 0.22F, 0.22F, wood);
		}
		HubPark.beam(level, new Vec3(t1.x, top - 0.1, t1.z), new Vec3(t2.x, top - 0.1, t2.z), 0.3F, 0.3F, wood);
		// Stone footings.
		for (Vec3 l : new Vec3[] {l1, l2}) {
			HubPark.piece(level, new Vec3(l.x, base + 0.2, l.z), Blocks.STONE_BRICKS.defaultBlockState(), new Vector3f(0.7F, 0.4F, 0.7F), new Quaternionf());
		}
		return true;
	}

	// ----- Stations -----

	/** A station by the track: a platform with a yellow edge, a canopy on posts, lanterns, benches, flowers, and the name. */
	private static void station(ServerLevel level, int s) {
		Station st = STATIONS[s];
		// Local axes: u along the track, v across (the platform's on the +v side), y up.
		double cx = st.x() + 0.5;
		double cz = st.z() + 0.5;
		double y0 = height;
		BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
		part(level, st, cx, cz, 0, 2.9, y0 - 0.45, 12, 2.8, 0.9, stone);
		part(level, st, cx, cz, 0, 1.6, y0 - 0.02, 12, 0.25, 0.06, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		part(level, st, cx, cz, 0, 2.9, y0 - 1.1, 11, 2.2, 0.5, Blocks.STONE_BRICKS.defaultBlockState());
		// Canopy: posts along the back, a sloping roof, a red fascia.
		for (int u = -5; u <= 5; u += 5) {
			part(level, st, cx, cz, u, 4.0, y0 + 1.55, 0.25, 0.25, 3.2, Blocks.SPRUCE_LOG.defaultBlockState());
			part(level, st, cx, cz, u, 3.0, y0 + 3.05, 0.2, 2.2, 0.2, Blocks.SPRUCE_PLANKS.defaultBlockState());
		}
		Display.BlockDisplay roof = roofPiece(level, st, cx, cz, y0 + 3.35);
		if (roof == null) {
			return;
		}
		part(level, st, cx, cz, 0, 1.75, y0 + 3.05, 12.4, 0.12, 0.45, Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
		// Lanterns under the canopy, benches against the back.
		for (int u : new int[] {-3, 3}) {
			part(level, st, cx, cz, u, 2.7, y0 + 2.45, 0.5, 0.5, 0.5, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			part(level, st, cx, cz, u, 3.65, y0 + 0.4, 1.8, 0.5, 0.12, Blocks.SPRUCE_PLANKS.defaultBlockState());
			part(level, st, cx, cz, u, 3.9, y0 + 0.75, 1.8, 0.1, 0.5, Blocks.SPRUCE_PLANKS.defaultBlockState());
			for (int e = -1; e <= 1; e += 2) {
				part(level, st, cx, cz, u + e * 0.8, 3.65, y0 + 0.2, 0.12, 0.45, 0.4, Blocks.DARK_OAK_PLANKS.defaultBlockState());
			}
		}
		// Flower tubs at the ends.
		for (int u : new int[] {-5, 5}) {
			part(level, st, cx, cz, u + (u < 0 ? -0.6 : 0.6), 2.4, y0 + 0.25, 0.7, 0.7, 0.5, Blocks.SPRUCE_PLANKS.defaultBlockState());
			part(level, st, cx, cz, u + (u < 0 ? -0.6 : 0.6), 2.4, y0 + 0.7, 0.6, 0.6, 0.5, Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState());
		}
		// The name board, over the platform.
		part(level, st, cx, cz, 0, 3.0, y0 + 3.9, 5.4, 0.12, 0.9, Blocks.CONCRETE.pick(DyeColor.GREEN).defaultBlockState());
		Vec3 sign = at(st, cx, cz, 0, 2.9, y0 + 3.62);
		HubPark.label(level, sign.x, sign.y, sign.z, st.name().toUpperCase(), ChatFormatting.WHITE, 1.1F);
		// Down to the ground: a stone pier, if there's island under the platform.
		Vec3 mid = at(st, cx, cz, 0, 2.9, y0);
		int g = Integer.MIN_VALUE;
		for (int y = height - 3; y >= FLOOR - 4; y--) {
			BlockState b = level.getBlockState(BlockPos.containing(mid.x, y, mid.z));
			if (!b.isAir()) {
				g = b.isSolidRender() ? y : Integer.MIN_VALUE;
				break;
			}
		}
		if (g != Integer.MIN_VALUE) {
			for (int u : new int[] {-4, 4}) {
				Vec3 c = at(st, cx, cz, u, 2.9, (g + 1 + y0 - 1.35) / 2);
				boolean clear = true;
				for (int y = g + 1; y < height - 2 && clear; y++) {
					clear = level.getBlockState(BlockPos.containing(c.x, y, c.z)).isAir();
				}
				if (clear) {
					part(level, st, cx, cz, u, 2.9, (g + 1 + y0 - 1.35) / 2, 1.1, 1.1, (float) (y0 - 1.35 - g - 1), Blocks.STONE_BRICKS.defaultBlockState());
				}
			}
		}
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

	/** A point in a station's own axes (u along the track, v across to the platform side). */
	private static Vec3 at(Station st, double cx, double cz, double u, double v, double y) {
		double sx = -st.dz();
		double sz = st.dx();
		return new Vec3(cx + st.dx() * u + sx * v, y, cz + st.dz() * u + sz * v);
	}

	/** A box in a station's axes: {@code lu} long along the track, {@code lv} across, {@code ly} tall. */
	private static Display.BlockDisplay part(ServerLevel level, Station st, double cx, double cz, double u, double v, double y, double lu, double lv, double ly,
			BlockState state) {
		Vec3 c = at(st, cx, cz, u, v, y);
		boolean alongX = st.dx() != 0;
		Vector3f size = alongX ? new Vector3f((float) lu, (float) ly, (float) lv) : new Vector3f((float) lv, (float) ly, (float) lu);
		return HubPark.piece(level, c, state, size, new Quaternionf());
	}

	/** The canopy roof: dark planks, tipped back a little. */
	private static Display.BlockDisplay roofPiece(ServerLevel level, Station st, double cx, double cz, double y) {
		Vec3 c = at(st, cx, cz, 0, 2.9, y);
		boolean alongX = st.dx() != 0;
		// Tip it so the platform side's edge is lower: a turn about the track's direction.
		double sx = -st.dz();
		double sz = st.dx();
		float tilt = 0.18F;
		Quaternionf rot = alongX ? new Quaternionf().rotateX((float) (sz > 0 ? tilt : -tilt)) : new Quaternionf().rotateZ((float) (sx > 0 ? -tilt : tilt));
		Vector3f size = alongX ? new Vector3f(12.6F, 0.18F, 3.4F) : new Vector3f(3.4F, 0.18F, 12.6F);
		return HubPark.piece(level, c, Blocks.DARK_OAK_PLANKS.defaultBlockState(), size, rot);
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
