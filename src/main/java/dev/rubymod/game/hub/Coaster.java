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

	/** Track control points: x, z, height above the floor. The station's at the start (and end), heading west. */
	private static final double[][] TRACK = {{46, 0, 1}, {42, 0, 1}, {38, 0, 1}, {34, -1, 1.5}, {31, -4, 3.5}, {28, -9, 7.5}, {25, -14, 12}, {21, -18, 15},
		{16, -22, 15.5}, {11, -27, 8}, {4, -31, 3}, {-5, -32, 4.5}, {-14, -29, 9}, {-21, -23, 12}, {-26, -14, 9}, {-28, -4, 4}, {-27, 7, 6}, {-23, 17, 10},
		{-15, 25, 7}, {-5, 30, 3}, {6, 31, 5}, {16, 27, 9}, {24, 20, 6}, {30, 12, 3}, {34, 6, 1.5}, {38, 2, 1}, {42, 0, 1}, {46, 0, 1}};
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
		List<Vec3> pts = new ArrayList<>();
		for (double[] c : TRACK) {
			pts.add(new Vec3(c[0] + 0.5, FLOOR + c[2], c[1] + 0.5));
		}
		path = HubRides.spline(pts, STEP);
		length = (path.size() - 1) * STEP;
		speeds();
		station(level);
		track(level);
		train(level);
		HubHosts.add(level, new Vec3(46.5, FLOOR + 1, 2.5), 90, VillagerProfession.TOOLSMITH, VillagerType.SAVANNA,
				new HubHosts.Host("coaster", "Rita", "Ruby Rocket", 0xFF4050, "minecraft:minecart",
						"The fastest ride in the park! The train waits a few seconds for friends, climbs the big hill, then WHOOSH. "
								+ "You get off at the exit when it's back.",
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
			Vec3 dir = b.subtract(a).normalize();
			Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize();
			Vec3 down = new Vec3(0, -0.06, 0);
			for (int s : new int[] {-1, 1}) {
				HubPark.beam(level, a.add(side.scale(0.42 * s)).add(down), b.add(side.scale(0.42 * s)).add(down), 0.14F, 0.14F, rail);
			}
			HubPark.beam(level, a.add(0, -0.32, 0), b.add(0, -0.32, 0), 0.26F, 0.26F, spine);
			Vec3 mid = a.lerp(b, 0.5).add(0, -0.15, 0);
			HubPark.beam(level, mid.add(side.scale(-0.5)), mid.add(side.scale(0.5)), 0.08F, 0.14F, tie);
		}
		// Steel supports down to the ground (or a little rock footing over the sky).
		for (int i = 0; i < path.size(); i += (int) Math.round(3.5 / STEP)) {
			Vec3 p = path.get(i);
			if (p.y < FLOOR + 1.6) {
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
			Display.ItemDisplay car = HubPark.tempModel(level, p, 90, k == 0 ? "coaster_car" : "coaster_car_blue", 2.0F, 1.0F);
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
			Vec3 dir = at(d + 0.6).subtract(at(d - 0.6)).normalize();
			float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
			float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.y))));
			Entity car = CARS[k] == null ? null : level.getEntity(CARS[k]);
			if (car != null) {
				car.teleportTo(p.x, p.y, p.z);
				car.setYRot(yaw);
				car.setXRot(pitch);
			}
			Vec3 flat = new Vec3(dir.x, 0, dir.z).normalize();
			for (int s = 0; s < 2; s++) {
				Entity seat = SEATS[k * 2 + s] == null ? null : level.getEntity(SEATS[k * 2 + s]);
				if (seat != null) {
					Vec3 sp = p.add(flat.scale(s == 0 ? 0.35 : -0.45)).add(0, 0.55 + dir.y * (s == 0 ? 0.35 : -0.45), 0);
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
