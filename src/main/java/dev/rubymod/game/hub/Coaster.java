package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Ruby Rocket: a proper rollercoaster round the plaza. Red rails on a dark spine with steel supports, a covered
 * station at the Fun Park with a queue and an exit, and a two-car train (four seats). Talk to Rita to get on; the train
 * waits a few seconds for others, clanks up the lift hill, drops, loops the islands, and lets you off at the exit.
 */
final class Coaster {
	private Coaster() {
	}

	/** Station, lift hill and the first drop (x, z, height). */
	private static final double[][] PART_1 = {{46, 0, 1}, {42, 0, 1}, {38, 0, 1}, {34, -1, 1.5}, {30, -6, 6}, {26, -12, 13}, {22, -17, 19}, {17, -21, 22},
		{12, -25, 18}, {8, -29, 8}, {4, -31.5, 3}, {-1, -32, 3}, {-7, -32, 3}};
	private static final double LOOP_R = 6;
	/** After the loop, up to the barrel roll. */
	private static final double[][] PART_2 = {{-7, -33.6, 3}, {-10, -33.6, 3}, {-14, -31, 4.5}, {-19, -25, 8.5}, {-22, -21, 9}};
	/** The barrel roll runs straight from here to there. */
	private static final double[] ROLL_FROM = {-22, -21, 9};
	private static final double[] ROLL_TO = {-28, -9, 9};
	/** Down through the rock tunnel, a big swooping hill, and home. */
	private static final double[][] PART_3 = {{-28, -9, 9}, {-29, 0, 7}, {-27, 8, 5}, {-23, 16, 3.5}, {-17, 24, 3}, {-8, 29.5, 3}, {0, 31, 3}, {8, 30.5, 6},
		{16, 27, 11}, {24, 20, 9}, {30, 12, 4}, {34, 6, 1.5}, {38, 2, 1}, {42, 0, 1}, {46, 0, 1}};
	/** The old wooden track's points (its slabs and fence posts are cleared away). */
	private static final double[][] OLD_TRACK = {{43, 0, 1}, {38, 0, 1.5}, {33, -4, 3}, {30, -10, 8}, {24, -20, 14}, {14, -27, 16}, {0, -31, 6}, {-14, -28, 3},
		{-24, -20, 7}, {-30, -8, 12}, {-31, 6, 9}, {-27, 17, 4}, {-18, 26, 2}, {-6, 31, 8}, {8, 30, 13}, {20, 24, 9}, {28, 14, 5}, {34, 6, 3}, {39, 2, 1.5},
		{43, 1, 1}};
	private static final double STEP = 0.25;
	private static final double CAR_GAP = 2.3;
	/** Where the front car stops in the station. */
	private static final double HOME = 3.2;
	private static final Vec3 EXIT = new Vec3(40.5, FLOOR + 1, -2.5);

	private static List<Vec3> path = List.of();
	/** Which way is "up" for the car at each point of the path (round the loop and the roll it turns). */
	private static List<Vec3> ups = List.of();
	private static final Set<Integer> LOOP = new HashSet<>();
	private static double[] speed = new double[0];
	private static double length;
	private static int liftEnd;

	private enum State {
		IDLE, COUNTDOWN, RUNNING
	}

	private static State state = State.IDLE;
	private static int timer;
	private static double front = HOME;
	private static double travelled;
	private static final UUID[] CARS = new UUID[2];
	private static final UUID[] SEATS = new UUID[4];
	/** Who's on this run (so anyone who hops off mid-ride is put back at the station). */
	private static final Set<UUID> RIDERS = new HashSet<>();

	static void build(ServerLevel level) {
		state = State.IDLE;
		front = HOME;
		RIDERS.clear();
		clearOldTrack(level);
		clearFootings(level);
		layout();
		length = (path.size() - 1) * STEP;
		speeds();
		station(level);
		track(level);
		tunnel(level);
		train(level);
		HubHosts.add(level, new Vec3(46.5, FLOOR + 1, 2.5), 90, VillagerProfession.TOOLSMITH, VillagerType.SAVANNA,
				new HubHosts.Host("coaster", "Rita", "Ruby Rocket", 0xFF4050, "minecraft:minecart",
						"The biggest ride in the park! Up the giant lift hill, down the drop, round the LOOP, through the barrel roll and the "
								+ "rock tunnel. The train waits a few seconds for friends. You get off at the exit when it's back.",
						"Ruby Rocket: right-click Rita to ride!", HubHosts.rideButtons("Ride the Ruby Rocket!"), (p, key) -> {
							for (ServerPlayer q : HubHosts.group(p, key)) {
								board(q);
							}
						}));
	}

	private static void clearOldTrack(ServerLevel level) {
		List<Vec3> pts = new ArrayList<>();
		for (double[] c : OLD_TRACK) {
			pts.add(new Vec3(c[0] + 0.5, FLOOR + c[2], c[1] + 0.5));
		}
		List<Vec3> old = HubRides.spline(pts, 0.5);
		for (int i = 0; i < old.size(); i++) {
			Vec3 p = old.get(i);
			BlockPos at = BlockPos.containing(p.x, p.y - 0.3, p.z);
			if (level.getBlockState(at).is(Blocks.DARK_OAK_SLAB)) {
				level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
			}
			for (int d = 1; d <= 4; d++) {
				if (level.getBlockState(at.below(d)).is(Blocks.DARK_OAK_FENCE)) {
					level.setBlock(at.below(d), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}

	/** Earlier footings (a stone block over a cobblestone one, hanging in the sky) are tidied away. */
	private static void clearFootings(ServerLevel level) {
		for (int x = -40; x <= 50; x++) {
			for (int z = -40; z <= 40; z++) {
				BlockPos a = new BlockPos(x, FLOOR - 6, z);
				if (level.getBlockState(a).is(Blocks.STONE) && level.getBlockState(a.below()).is(Blocks.COBBLESTONE) && level.getBlockState(a.above()).isAir()) {
					level.setBlock(a, Blocks.AIR.defaultBlockState(), 2);
					level.setBlock(a.below(), Blocks.AIR.defaultBlockState(), 2);
				}
				BlockPos g = new BlockPos(x, FLOOR - 5, z);
				if (level.getBlockState(g).is(Blocks.GRASS_BLOCK) && level.getBlockState(g.below()).is(Blocks.DIRT) && level.getBlockState(g.below(2)).is(Blocks.STONE)
						&& level.getBlockState(g.below(3)).isAir()) {
					for (int[] d : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						if (level.getBlockState(g.offset(d[0], 0, d[1])).is(Blocks.GRASS_BLOCK)) {
							level.setBlock(g.offset(d[0], 0, d[1]), Blocks.AIR.defaultBlockState(), 2);
						}
					}
					level.setBlock(g.below(), Blocks.AIR.defaultBlockState(), 2);
					level.setBlock(g.below(2), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}

	/** The whole track as points (STEP apart) with which way is up at each. */
	private static void layout() {
		List<Vec3> pts = new ArrayList<>();
		List<Vec3> up = new ArrayList<>();
		LOOP.clear();
		spline(pts, up, PART_1);
		// The loop: a vertical circle, drifting sideways a little so the track doesn't run into itself.
		Vec3 centre = new Vec3(-7 + 0.5, FLOOR + 3 + LOOP_R, -32 + 0.5);
		int n = (int) Math.ceil(Math.PI * 2 * LOOP_R / STEP);
		for (int i = 1; i <= n; i++) {
			double t = i * Math.PI * 2 / n;
			Vec3 p = new Vec3(centre.x - LOOP_R * Math.sin(t), centre.y - LOOP_R * Math.cos(t), centre.z - 1.6 * t / (Math.PI * 2));
			LOOP.add(pts.size());
			pts.add(p);
			up.add(new Vec3(centre.x - p.x, centre.y - p.y, 0).normalize());
		}
		spline(pts, up, PART_2);
		// The barrel roll: straight, with "up" turning once all the way round.
		Vec3 a = new Vec3(ROLL_FROM[0] + 0.5, FLOOR + ROLL_FROM[2], ROLL_FROM[1] + 0.5);
		Vec3 b = new Vec3(ROLL_TO[0] + 0.5, FLOOR + ROLL_TO[2], ROLL_TO[1] + 0.5);
		Vec3 axis = b.subtract(a).normalize();
		int m = (int) Math.ceil(a.distanceTo(b) / STEP);
		for (int i = 1; i <= m; i++) {
			double t = i / (double) m;
			pts.add(a.lerp(b, t));
			up.add(rotate(new Vec3(0, 1, 0), axis, t * Math.PI * 2));
		}
		spline(pts, up, PART_3);
		path = pts;
		ups = up;
	}

	private static void spline(List<Vec3> pts, List<Vec3> up, double[][] part) {
		List<Vec3> ctl = new ArrayList<>();
		for (double[] c : part) {
			ctl.add(new Vec3(c[0] + 0.5, FLOOR + c[2], c[1] + 0.5));
		}
		List<Vec3> seg = HubRides.spline(ctl, STEP);
		for (int i = pts.isEmpty() ? 0 : 1; i < seg.size(); i++) {
			pts.add(seg.get(i));
			up.add(new Vec3(0, 1, 0));
		}
	}

	/** v turned by {@code angle} round the unit vector {@code k}. */
	private static Vec3 rotate(Vec3 v, Vec3 k, double angle) {
		double c = Math.cos(angle);
		double sn = Math.sin(angle);
		return v.scale(c).add(k.cross(v).scale(sn)).add(k.scale(k.dot(v) * (1 - c)));
	}

	private static Vec3 upAt(double d) {
		double m = ((d % length) + length) % length;
		double f = m / STEP;
		int i = (int) Math.floor(f);
		Vec3 a = ups.get(Math.min(ups.size() - 1, i));
		Vec3 b = ups.get(Math.min(ups.size() - 1, i + 1));
		return a.lerp(b, f - i).normalize();
	}

	/** The car's frame at d: forward along the track, up (square to it), and right. */
	private static Vec3[] frame(double d) {
		Vec3 fwd = at(d + 0.3).subtract(at(d - 0.3)).normalize();
		Vec3 up = upAt(d);
		up = up.subtract(fwd.scale(fwd.dot(up)));
		if (up.lengthSqr() < 1e-4) {
			up = new Vec3(0, 1, 0);
		}
		up = up.normalize();
		Vec3 right = up.cross(fwd).normalize();
		return new Vec3[] {fwd, up, right};
	}

	/** The rock tunnel: a tube of stone round the track where it runs low past the pond. */
	private static void tunnel(ServerLevel level) {
		BlockState[] rock = {Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
			Blocks.STONE.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
		for (int i = 0; i < path.size(); i++) {
			Vec3 p = path.get(i);
			if (p.x < -20 || p.x > 1 || p.z < 20 || p.y > FLOOR + 4.5) {
				continue;
			}
			for (int dx = -4; dx <= 4; dx++) {
				for (int dy = -4; dy <= 4; dy++) {
					for (int dz = -4; dz <= 4; dz++) {
						BlockPos b = BlockPos.containing(p.x + dx, p.y + dy, p.z + dz);
						double d = Vec3.atCenterOf(b).distanceTo(p.add(0, 0.6, 0));
						if (d > 2.4 && d <= 3.6) {
							HubPark.ifAir(level, b.getX(), b.getY(), b.getZ(), rock[Math.floorMod(b.getX() * 7 + b.getY() * 3 + b.getZ() * 11, rock.length)]);
						}
					}
				}
			}
			if (i % 24 == 0) {
				HubPark.ifAir(level, (int) Math.floor(p.x), (int) Math.floor(p.y) + 2, (int) Math.floor(p.z), Blocks.SHROOMLIGHT.defaultBlockState());
			}
		}
	}

	/** Slow out of the station and up the lift hill, then gravity: faster the further below the top, braking home. */
	private static void speeds() {
		speed = new double[path.size()];
		int crest = 0;
		for (int i = 0; i < path.size() / 2; i++) {
			if (path.get(i).y > path.get(crest).y) {
				crest = i;
			}
		}
		liftEnd = crest;
		double top = path.get(crest).y;
		for (int i = 0; i < path.size(); i++) {
			double v;
			if (i <= crest) {
				v = path.get(i).x > 35 ? 0.2 : 0.22;
			} else {
				v = Math.sqrt(0.22 * 0.22 + 2 * 0.045 * Math.max(0, top - path.get(i).y));
				v = Math.max(0.3, Math.min(1.15, v));
			}
			double left = (path.size() - 1 - i) * STEP;
			if (left < 16) {
				v = Math.min(v, 0.18 + left / 16 * 0.7);
			}
			speed[i] = v;
		}
	}

	private static Vec3 at(double d) {
		double m = ((d % length) + length) % length;
		double f = m / STEP;
		int i = (int) Math.floor(f);
		Vec3 a = path.get(Math.min(path.size() - 1, i));
		Vec3 b = path.get(Math.min(path.size() - 1, i + 1));
		return a.lerp(b, f - i);
	}

	// ----- Building -----

	private static void station(ServerLevel level) {
		HubPark.clear(level, 34, FLOOR + 1, -5, 48, FLOOR + 7, 5);
		BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState();
		HubPark.box(level, 35, FLOOR, -3, 47, FLOOR, 3, floor);
		HubPark.box(level, 35, FLOOR, -1, 47, FLOOR, 1, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
		HubPark.box(level, 35, FLOOR, 2, 47, FLOOR, 2, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		HubPark.box(level, 35, FLOOR, -2, 47, FLOOR, -2, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		// Posts and a striped roof.
		for (int x : new int[] {35, 41, 47}) {
			for (int z : new int[] {-3, 3}) {
				HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 4, z, Blocks.QUARTZ_PILLAR.defaultBlockState());
			}
		}
		for (int x = 34; x <= 48; x++) {
			for (int z = -4; z <= 4; z++) {
				boolean edge = z == -4 || z == 4;
				BlockState roof = edge ? Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState()
						: Blocks.WOOL.pick(x % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState();
				HubPark.set(level, x, FLOOR + 5, z, roof);
			}
			HubPark.set(level, x, FLOOR + 6, 0, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
		}
		// Queue rail on the boarding side (a gap by Rita), exit rail on the other (a gap at the far end).
		for (int x = 35; x <= 47; x++) {
			if (x < 44) {
				HubPark.set(level, x, FLOOR + 1, 3, Blocks.IRON_BARS.defaultBlockState());
			}
			if (x > 37) {
				HubPark.set(level, x, FLOOR + 1, -3, Blocks.IRON_BARS.defaultBlockState());
			}
		}
		HubPark.label(level, 41.5, FLOOR + 7.6, 0.5, "RUBY ROCKET", ChatFormatting.RED, 2.2F);
		HubPark.label(level, 36.5, FLOOR + 2.6, -3.5, "EXIT", ChatFormatting.GREEN, 0.9F);
		for (int x : new int[] {36, 46}) {
			HubPark.set(level, x, FLOOR + 4, 0, Blocks.SEA_LANTERN.defaultBlockState());
		}
	}

	private static void track(ServerLevel level) {
		BlockState rail = Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();
		BlockState spine = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		BlockState tie = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
		BlockState steel = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
		int seg = (int) Math.round(2.0 / STEP);
		for (int i = 0; i + seg < path.size(); i += seg) {
			Vec3 a = path.get(i);
			Vec3 b = path.get(i + seg);
			Vec3[] fa = frame(i * STEP);
			Vec3[] fb = frame((i + seg) * STEP);
			for (int s : new int[] {-1, 1}) {
				HubPark.beam(level, a.add(fa[2].scale(0.42 * s)).add(fa[1].scale(-0.06)), b.add(fb[2].scale(0.42 * s)).add(fb[1].scale(-0.06)), 0.14F, 0.14F, rail);
			}
			HubPark.beam(level, a.add(fa[1].scale(-0.32)), b.add(fb[1].scale(-0.32)), 0.26F, 0.26F, spine);
			Vec3 mid = a.lerp(b, 0.5).add(fa[1].scale(-0.15));
			HubPark.beam(level, mid.add(fa[2].scale(-0.5)), mid.add(fa[2].scale(0.5)), 0.08F, 0.14F, tie);
		}
		// Steel supports down to the ground (or a little rock footing over the sky).
		for (int i = 0; i < path.size(); i += (int) Math.round(3.5 / STEP)) {
			Vec3 p = path.get(i);
			if (p.y < FLOOR + 1.6 || LOOP.contains(i)) {
				continue;
			}
			int ground = Integer.MIN_VALUE;
			BlockPos col = BlockPos.containing(p.x, p.y, p.z);
			for (int y = (int) Math.floor(p.y) - 1; y >= FLOOR - 10; y--) {
				if (!level.getBlockState(new BlockPos(col.getX(), y, col.getZ())).isAir()) {
					ground = y + 1;
					break;
				}
			}
			boolean sky = ground == Integer.MIN_VALUE;
			if (sky && (i / (int) Math.round(3.5 / STEP)) % 2 == 1) {
				continue;
			}
			double bottom = sky ? FLOOR - 4 : ground;
			HubPark.beam(level, new Vec3(p.x, p.y - 0.45, p.z), new Vec3(p.x, bottom, p.z), 0.32F, 0.32F, steel);
			if (sky) {
				// Over the sky: a tiny floating island for the support to stand on.
				int x = col.getX();
				int z = col.getZ();
				for (int[] d : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					HubPark.set(level, x + d[0], FLOOR - 5, z + d[1], Blocks.GRASS_BLOCK.defaultBlockState());
				}
				HubPark.set(level, x, FLOOR - 6, z, Blocks.DIRT.defaultBlockState());
				HubPark.set(level, x, FLOOR - 7, z, Blocks.STONE.defaultBlockState());
			}
		}
	}

	private static void train(ServerLevel level) {
		for (int k = 0; k < 2; k++) {
			Vec3 p = at(HOME - k * CAR_GAP);
			Display.ItemDisplay car = HubPark.tempModel(level, p, 0, k == 0 ? "coaster_car" : "coaster_car_blue", 2.0F, 1.0F);
			CARS[k] = car == null ? null : car.getUUID();
			for (int s = 0; s < 2; s++) {
				Display.BlockDisplay seat = HubPark.seat(level, p);
				SEATS[k * 2 + s] = seat == null ? null : seat.getUUID();
			}
		}
		place(level);
	}

	// ----- Riding -----

	private static void board(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (state == State.RUNNING) {
			int left = (int) ((length - travelled) / 0.6 / 20) + 1;
			p.sendOverlayMessage(Component.literal("The train's out on the track. It'll be back in about " + left + " seconds!").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		for (UUID id : SEATS) {
			Entity seat = id == null ? null : level.getEntity(id);
			if (seat != null && !seat.isVehicle()) {
				p.startRiding(seat, true, true);
				RIDERS.add(p.getUUID());
				if (state == State.IDLE) {
					state = State.COUNTDOWN;
					timer = 100;
					HubHosts.callOut(level, "coaster", "The Ruby Rocket leaves in 5 seconds! Right-click me to hop on.", q -> RIDERS.contains(q.getUUID()));
				} else {
					timer = Math.max(timer, 60);
				}
				level.playSound(null, p.blockPosition(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 0.8F, 1.2F);
				p.sendOverlayMessage(Component.literal("Lap bar down! Hold on tight...").withStyle(ChatFormatting.RED));
				return;
			}
		}
		p.sendOverlayMessage(Component.literal("The train's full! The next one goes in a moment.").withStyle(ChatFormatting.YELLOW));
	}

	static void tick(ServerLevel level, int tick) {
		if (path.isEmpty()) {
			return;
		}
		// Anyone who hopped off mid-ride goes back to the station.
		for (var it = RIDERS.iterator(); it.hasNext();) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(it.next());
			if (p == null || p.level() != level) {
				it.remove();
			} else if (!onTrain(level, p)) {
				it.remove();
				if (state == State.RUNNING) {
					letOff(p, "You hopped off! Talk to Rita to ride again.");
				}
			}
		}
		switch (state) {
			case IDLE -> {
			}
			case COUNTDOWN -> {
				if (RIDERS.isEmpty()) {
					state = State.IDLE;
					return;
				}
				if (timer % 20 == 0) {
					for (UUID id : RIDERS) {
						ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
						if (p != null) {
							p.sendOverlayMessage(Component.literal("Leaving in " + timer / 20 + "...").withStyle(ChatFormatting.GOLD));
						}
					}
					level.playSound(null, BlockPos.containing(at(HOME)), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.8F, 1.6F);
				}
				if (--timer <= 0) {
					state = State.RUNNING;
					travelled = 0;
					for (UUID id : RIDERS) {
						ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
						if (p != null) {
							Alerts.title(p, "", "Here we go!", ChatFormatting.RED);
						}
					}
				}
			}
			case RUNNING -> {
				int i = (int) Math.min(path.size() - 1, Math.max(0, ((front % length) + length) % length / STEP));
				double v = speed[i];
				front += v;
				travelled += v;
				Vec3 here = at(front);
				if (i < liftEnd && tick % 6 == 0) {
					level.playSound(null, BlockPos.containing(here), SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7F, 0.6F);
				}
				if (i == liftEnd + 1 || i == liftEnd + 2) {
					level.playSound(null, BlockPos.containing(here), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.8F, 1.2F);
				}
				if (v > 0.7 && tick % 10 == 0) {
					level.playSound(null, BlockPos.containing(here), SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 0.7F, 1.4F);
				}
				if (travelled >= length) {
					front = HOME;
					state = State.IDLE;
					for (UUID id : new ArrayList<>(RIDERS)) {
						ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
						if (p != null) {
							letOff(p, "What a ride! Talk to Rita to go again.");
						}
					}
					RIDERS.clear();
				}
			}
		}
		place(level);
	}

	private static boolean onTrain(ServerLevel level, ServerPlayer p) {
		Entity v = p.getVehicle();
		for (UUID id : SEATS) {
			if (v != null && v.getUUID().equals(id)) {
				return true;
			}
		}
		return false;
	}

	private static void letOff(ServerPlayer p, String message) {
		if (p.isPassenger()) {
			p.stopRiding();
		}
		p.teleportTo(EXIT.x, EXIT.y, EXIT.z);
		p.setYRot(180);
		p.resetFallDistance();
		HubPark.landed(p);
		p.sendOverlayMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	/** Put the cars and seats where the train is. */
	private static void place(ServerLevel level) {
		for (int k = 0; k < 2; k++) {
			double d = front - k * CAR_GAP;
			Vec3 p = at(d);
			Vec3[] f = frame(d);
			float yaw = (float) Math.toDegrees(Math.atan2(-f[0].x, f[0].z));
			Entity car = CARS[k] == null ? null : level.getEntity(CARS[k]);
			if (car instanceof Display disp) {
				car.teleportTo(p.x, p.y, p.z);
				org.joml.Quaternionf q = new org.joml.Matrix3f(new org.joml.Vector3f((float) f[2].x, (float) f[2].y, (float) f[2].z),
						new org.joml.Vector3f((float) f[1].x, (float) f[1].y, (float) f[1].z), new org.joml.Vector3f((float) f[0].x, (float) f[0].y, (float) f[0].z))
						.getNormalizedRotation(new org.joml.Quaternionf());
				((dev.rubymod.mixin.DisplayAccessor) disp).rubymod$setTransformationInterpolationDelay(0);
				((dev.rubymod.mixin.DisplayAccessor) disp).rubymod$setTransformationInterpolationDuration(2);
				((dev.rubymod.mixin.DisplayAccessor) disp).rubymod$setTransformation(new com.mojang.math.Transformation(q.transform(new org.joml.Vector3f(0, 1.0F, 0)),
						new org.joml.Quaternionf(q).rotateY((float) Math.PI), new org.joml.Vector3f(2.0F, 2.0F, 2.0F), new org.joml.Quaternionf()));
			}
			for (int s = 0; s < 2; s++) {
				Entity seat = SEATS[k * 2 + s] == null ? null : level.getEntity(SEATS[k * 2 + s]);
				if (seat != null) {
					Vec3 sp = p.add(f[0].scale(s == 0 ? 0.35 : -0.45)).add(f[1].scale(0.55));
					seat.teleportTo(sp.x, sp.y, sp.z);
					seat.setYRot(yaw);
					for (Entity rider : seat.getPassengers()) {
						rider.resetFallDistance();
					}
				}
			}
		}
	}

	static void reset() {
		state = State.IDLE;
		RIDERS.clear();
		path = List.of();
	}
}
