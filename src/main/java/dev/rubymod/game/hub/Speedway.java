package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Speedway: a go-kart track with curbs and tyre walls, and Rex, who starts the races. Everyone who joins in the
 * 15 seconds before a race lines up on the grid; three red lights, GO, three laps. Drive with W A S D. The fastest lap
 * of the night goes up on the records board.
 */
final class Speedway {
	private Speedway() {
	}

	static final int CX = -88;
	static final int CZ = 6;
	static final Vec3 LAND = new Vec3(-90.5, FLOOR + 1, 2.5);
	private static final Vec3 PIT = new Vec3(-86.5, FLOOR + 1, 0.5);
	private static final double[][] TRACK = {{-15, -8}, {-6, -15}, {6, -15}, {15, -8}, {17, 2}, {13, 11}, {4, 13}, {0, 8}, {-5, 13}, {-13, 12}, {-17, 3}};
	private static final int LAPS = 3;
	private static final String[] COLORS = {"red", "blue", "green", "yellow", "pink", "purple", "orange", "white"};
	private static final double STEP = 0.5;

	private static List<Vec3> line = List.of();

	private static final class Kart {
		final UUID driver;
		UUID model;
		UUID seat;
		Vec3 pos;
		Vec3 vel = Vec3.ZERO;
		float yaw;
		int index;
		int lap;
		int furthest;
		int lapStart;
		int best = Integer.MAX_VALUE;
		int finished = -1;
		int place;
		int bonk;

		Kart(UUID driver) {
			this.driver = driver;
		}
	}

	private static final List<Kart> KARTS = new ArrayList<>();
	/** 0: nothing on; >0 lobby ticks left; then the countdown and the race. */
	private static int lobby;
	private static int countdown;
	private static boolean racing;
	private static int raceTick;
	private static int firstFinish = -1;
	private static int finishers;
	private static final List<BlockPos> LIGHTS = new ArrayList<>();

	static void build(ServerLevel level) {
		KARTS.clear();
		LIGHTS.clear();
		lobby = 0;
		racing = false;
		HubPark.island(level, CX, CZ, 22, FLOOR);
		HubPark.clear(level, CX - 22, FLOOR + 1, CZ - 22, CX + 22, FLOOR + 3, CZ + 22);
		// The racing line: a closed smooth loop through the points.
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : TRACK) {
			pts.add(new Vec3(CX + p[0] + 0.5, FLOOR + 1, CZ + p[1] + 0.5));
		}
		line = loop(pts);
		// Tarmac, curbs, tyre walls.
		for (int x = CX - 22; x <= CX + 22; x++) {
			for (int z = CZ - 22; z <= CZ + 22; z++) {
				BlockPos top = new BlockPos(x, FLOOR, z);
				if (!level.getBlockState(top).is(Blocks.GRASS_BLOCK)) {
					continue;
				}
				double d = Double.MAX_VALUE;
				for (Vec3 p : line) {
					d = Math.min(d, Math.hypot(p.x - (x + 0.5), p.z - (z + 0.5)));
				}
				if (d <= 2.6) {
					HubPark.set(level, x, FLOOR, z, Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState());
				} else if (d <= 3.4) {
					HubPark.set(level, x, FLOOR, z, Blocks.CONCRETE.pick(((x + z) & 1) == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
				} else if (d <= 4.3) {
					HubPark.set(level, x, FLOOR + 1, z, Blocks.WOOL.pick(((x * 3 + z) % 5 == 0) ? DyeColor.WHITE : DyeColor.BLACK).defaultBlockState());
				}
			}
		}
		// The start/finish line, and a gantry over it with the start lights.
		Vec3 s0 = line.get(0);
		Vec3 dir = line.get(2).subtract(s0).normalize();
		Vec3 side = new Vec3(-dir.z, 0, dir.x);
		for (int k = -5; k <= 5; k++) {
			for (int r = 0; r <= 1; r++) {
				Vec3 c = s0.add(side.scale(k * 0.5)).add(dir.scale(r * 0.5));
				HubPark.set(level, (int) Math.floor(c.x), FLOOR, (int) Math.floor(c.z),
						Blocks.CONCRETE.pick(((k + r) & 1) == 0 ? DyeColor.BLACK : DyeColor.WHITE).defaultBlockState());
			}
		}
		BlockState steel = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		Vec3 left = s0.add(side.scale(-4.6));
		Vec3 right = s0.add(side.scale(4.6));
		HubPark.beam(level, left.add(0, -1, 0), left.add(0, 4.5, 0), 0.4F, 0.4F, steel);
		HubPark.beam(level, right.add(0, -1, 0), right.add(0, 4.5, 0), 0.4F, 0.4F, steel);
		HubPark.beam(level, left.add(0, 4.5, 0), right.add(0, 4.5, 0), 0.5F, 0.6F, Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
		HubPark.label(level, s0.x, FLOOR + 6.6, s0.z, "START / FINISH", ChatFormatting.WHITE, 1.2F);
		for (int i = -1; i <= 1; i++) {
			Vec3 l = s0.add(side.scale(i * 1.2)).add(0, 3.3, 0);
			BlockPos lp = BlockPos.containing(l.x, FLOOR + 4, l.z);
			LIGHTS.add(lp);
			HubPark.set(level, lp.getX(), lp.getY(), lp.getZ(), Blocks.COAL_BLOCK.defaultBlockState());
		}
		lights(level, 0);
		HubPark.label(level, CX + 0.5, FLOOR + 14, CZ + 0.5, "SPEEDWAY", ChatFormatting.RED, 2.8F);
		// The pit: bleachers to watch from, and the karts lined up for show.
		for (int row = 0; row < 3; row++) {
			HubPark.box(level, CX - 3, FLOOR + 1 + row, CZ - 4 + row, CX + 3, FLOOR + 1 + row, CZ - 4 + row,
					Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X));
		}
		HubPark.travelPad(level, CX + 5, FLOOR, CZ - 2, "speedway");
		HubHosts.add(level, PIT, 180, VillagerProfession.TOOLSMITH, VillagerType.SNOW,
				new HubHosts.Host("karts", "Rex", "Speedway", 0xFF5050, "minecraft:minecart",
						"Go-kart racing! Join in and line up on the grid: three red lights, then GO. Three laps. "
								+ "W to drive, S to brake, A and D to steer. Race on your own to set a lap time, or with everyone!",
						"Speedway: right-click Rex to race!", p -> {
							List<String> b = new ArrayList<>(HubHosts.rideButtons("Join the race!").apply(p));
							if (racing) {
								return List.of("busy|A race is on: next one soon!");
							}
							return b;
						}, (p, key) -> {
							for (ServerPlayer q : HubHosts.group(p, key)) {
								join((ServerLevel) p.level(), q);
							}
						}));
	}

	/** A smooth closed loop (Catmull-Rom, wrapping round), sampled every STEP. */
	private static List<Vec3> loop(List<Vec3> p) {
		List<Vec3> out = new ArrayList<>();
		int n = p.size();
		for (int i = 0; i < n; i++) {
			Vec3 p0 = p.get((i - 1 + n) % n);
			Vec3 p1 = p.get(i);
			Vec3 p2 = p.get((i + 1) % n);
			Vec3 p3 = p.get((i + 2) % n);
			int k = Math.max(2, (int) Math.ceil(p1.distanceTo(p2) / STEP * 3));
			for (int j = 0; j < k; j++) {
				double t = j / (double) k;
				double t2 = t * t;
				double t3 = t2 * t;
				out.add(p0.scale(-0.5 * t3 + t2 - 0.5 * t).add(p1.scale(1.5 * t3 - 2.5 * t2 + 1)).add(p2.scale(-1.5 * t3 + 2 * t2 + 0.5 * t))
						.add(p3.scale(0.5 * t3 - 0.5 * t2)));
			}
		}
		out.add(out.get(0));
		List<Vec3> even = HubRides.even(out, STEP);
		even.remove(even.size() - 1);
		return even;
	}

	private static void lights(ServerLevel level, int lit) {
		for (int i = 0; i < LIGHTS.size(); i++) {
			BlockState s = lit < 0 ? Blocks.EMERALD_BLOCK.defaultBlockState() : i < lit ? Blocks.REDSTONE_BLOCK.defaultBlockState()
					: Blocks.COAL_BLOCK.defaultBlockState();
			level.setBlock(LIGHTS.get(i), s, 2);
		}
	}

	// ----- Racing -----

	private static void join(ServerLevel level, ServerPlayer p) {
		if (racing || countdown > 0) {
			p.sendOverlayMessage(Component.literal("A race is on! The next one starts soon.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		for (Kart k : KARTS) {
			if (k.driver.equals(p.getUUID())) {
				return;
			}
		}
		if (KARTS.size() >= COLORS.length) {
			p.sendOverlayMessage(Component.literal("The grid's full!").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (lobby <= 0) {
			lobby = 15 * 20;
			HubHosts.callOut(level, "karts", "A go-kart race starts in 15 seconds! Right-click me at the Speedway to join.", q -> false);
			for (ServerPlayer q : level.players()) {
				if (q.position().distanceTo(PIT) > 28) {
					q.sendSystemMessage(Component.literal("[Rex] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
							.append(Component.literal(p.getName().getString() + " is starting a go-kart race! Fly to the Speedway (Park Map) to join.")
									.withStyle(ChatFormatting.WHITE)));
				}
			}
		}
		Kart k = new Kart(p.getUUID());
		int slot = KARTS.size();
		Vec3 base = line.get(line.size() - 4 - (slot / 2) * 6);
		Vec3 ahead = line.get(line.size() - 2 - (slot / 2) * 6).subtract(base).normalize();
		Vec3 side = new Vec3(-ahead.z, 0, ahead.x);
		k.pos = base.add(side.scale(slot % 2 == 0 ? -1.2 : 1.2));
		k.yaw = (float) Math.toDegrees(Math.atan2(-ahead.x, ahead.z));
		k.index = line.size() - 4 - (slot / 2) * 6;
		k.furthest = k.index;
		k.lap = -1;
		Display.ItemDisplay m = HubPark.tempModel(level, k.pos, k.yaw, "kart_" + COLORS[slot], 1.5F, 0.75F);
		Display.BlockDisplay seat = HubPark.seat(level, k.pos.add(0, 0.2, 0));
		if (m == null || seat == null) {
			return;
		}
		k.model = m.getUUID();
		k.seat = seat.getUUID();
		if (p.isPassenger()) {
			p.stopRiding();
		}
		p.startRiding(seat, true, true);
		KARTS.add(k);
		p.sendOverlayMessage(Component.literal("You're on the grid! Wait for the green light...").withStyle(ChatFormatting.RED));
		place(level, k);
	}

	static void tick(ServerLevel level, int tick) {
		if (line.isEmpty()) {
			return;
		}
		// Drivers who hop out (or leave) are out of the race.
		for (Iterator<Kart> it = KARTS.iterator(); it.hasNext();) {
			Kart k = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			Entity seat = level.getEntity(k.seat);
			if (p == null || seat == null || p.getVehicle() != seat) {
				HubPark.discard(level, k.model);
				HubPark.discard(level, k.seat);
				it.remove();
				if (p != null && p.level() == level) {
					FunPark.letOff(p, PIT.add(0, 0, -2), "Out of the race! Talk to Rex to race again.");
				}
			}
		}
		if (lobby > 0) {
			if (KARTS.isEmpty()) {
				lobby = 0;
				return;
			}
			if (lobby % 100 == 0) {
				tellAll(level, "Race starts in " + lobby / 20 + " seconds... (" + KARTS.size() + " on the grid)", ChatFormatting.GOLD);
			}
			if (--lobby == 0) {
				countdown = 80;
			}
		}
		if (countdown > 0) {
			if (KARTS.isEmpty()) {
				countdown = 0;
				lights(level, 0);
				return;
			}
			if (countdown % 20 == 0 && countdown <= 60) {
				lights(level, 4 - countdown / 20);
				level.playSound(null, BlockPos.containing(line.get(0)), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.5F, 0.8F);
				tellTitle(level, String.valueOf(countdown / 20), ChatFormatting.RED);
			}
			if (--countdown == 0) {
				lights(level, -1);
				racing = true;
				raceTick = 0;
				firstFinish = -1;
				finishers = 0;
				level.playSound(null, BlockPos.containing(line.get(0)), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 2.0F, 1.6F);
				tellTitle(level, "GO!", ChatFormatting.GREEN);
			} else {
				for (Kart k : KARTS) {
					place(level, k);
				}
				return;
			}
		}
		if (racing) {
			raceTick++;
			drive(level);
			standings(level);
			boolean allDone = KARTS.stream().allMatch(k -> k.finished >= 0);
			if (KARTS.isEmpty() || allDone || firstFinish >= 0 && raceTick - firstFinish > 45 * 20) {
				finish(level);
			}
		}
		for (Kart k : KARTS) {
			place(level, k);
		}
	}

	private static void drive(ServerLevel level) {
		for (Kart k : KARTS) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			if (p == null) {
				continue;
			}
			Input in = k.finished >= 0 ? Input.EMPTY : p.getLastClientInput();
			double speed = k.vel.length();
			float turn = (float) (3.5 + Math.min(1, speed / 0.3) * 2.5);
			if (in.left()) {
				k.yaw -= turn;
			}
			if (in.right()) {
				k.yaw += turn;
			}
			Vec3 ahead = new Vec3(-Math.sin(Math.toRadians(k.yaw)), 0, Math.cos(Math.toRadians(k.yaw)));
			// Grip: the kart mostly goes the way it points.
			k.vel = ahead.scale(k.vel.dot(ahead)).add(k.vel.subtract(ahead.scale(k.vel.dot(ahead))).scale(0.6));
			if (in.forward()) {
				k.vel = k.vel.add(ahead.scale(0.028));
			} else if (in.backward()) {
				k.vel = k.vel.add(ahead.scale(-0.03));
			}
			BlockPos under = BlockPos.containing(k.pos.x, FLOOR, k.pos.z);
			boolean tarmac = level.getBlockState(under).is(Blocks.CONCRETE.pick(DyeColor.GRAY)) || level.getBlockState(under).is(Blocks.CONCRETE.pick(DyeColor.BLACK))
					|| level.getBlockState(under).is(Blocks.CONCRETE.pick(DyeColor.WHITE)) || level.getBlockState(under).is(Blocks.CONCRETE.pick(DyeColor.RED));
			double max = tarmac ? 0.48 : 0.2;
			k.vel = k.vel.scale(0.965);
			if (k.vel.length() > max) {
				k.vel = k.vel.normalize().scale(Math.max(max, k.vel.length() * 0.92));
			}
			Vec3 next = k.pos.add(k.vel);
			if (wall(level, next)) {
				if (k.bonk <= 0 && k.vel.length() > 0.12) {
					level.playSound(null, BlockPos.containing(k.pos), SoundEvents.WOOL_HIT, SoundSource.PLAYERS, 1.0F, 0.7F);
					level.playSound(null, BlockPos.containing(k.pos), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 0.6F, 0.8F);
					k.bonk = 8;
				}
				k.vel = k.vel.scale(-0.35);
			} else {
				k.pos = next;
			}
			if (k.bonk > 0) {
				k.bonk--;
			}
			if (k.vel.length() > 0.3 && raceTick % 4 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, k.pos.x - ahead.x * 0.8, k.pos.y + 0.2, k.pos.z - ahead.z * 0.8, 1, 0.05, 0.02, 0.05, 0.01);
			}
		}
		// Karts bump each other.
		for (int i = 0; i < KARTS.size(); i++) {
			for (int j = i + 1; j < KARTS.size(); j++) {
				Kart a = KARTS.get(i);
				Kart b = KARTS.get(j);
				Vec3 d = b.pos.subtract(a.pos);
				double dist = d.horizontalDistance();
				if (dist < 1.3 && dist > 1e-4) {
					Vec3 n = new Vec3(d.x / dist, 0, d.z / dist);
					double push = (1.3 - dist) / 2;
					a.pos = a.pos.subtract(n.scale(push));
					b.pos = b.pos.add(n.scale(push));
					double va = a.vel.dot(n);
					double vb = b.vel.dot(n);
					if (va - vb > 0) {
						a.vel = a.vel.add(n.scale(vb - va));
						b.vel = b.vel.add(n.scale(va - vb));
					}
				}
			}
		}
	}

	private static boolean wall(ServerLevel level, Vec3 at) {
		for (double[] o : new double[][] {{0.55, 0}, {-0.55, 0}, {0, 0.55}, {0, -0.55}}) {
			BlockPos b = BlockPos.containing(at.x + o[0], FLOOR + 1.2, at.z + o[1]);
			if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Where everyone is on the lap, laps done, and the order. */
	private static void standings(ServerLevel level) {
		int n = line.size();
		for (Kart k : KARTS) {
			if (k.finished >= 0) {
				continue;
			}
			int best = k.index;
			double bd = Double.MAX_VALUE;
			for (int o = -30; o <= 30; o++) {
				int i = ((k.index + o) % n + n) % n;
				double d = line.get(i).distanceToSqr(k.pos.x, line.get(i).y, k.pos.z);
				if (d < bd) {
					bd = d;
					best = i;
				}
			}
			// Over the line (from the end of the lap to the start of the next), having been most of the way round.
			if (k.index > n - 40 && best < 40) {
				if (k.lap < 0 || k.furthest > n * 0.8) {
					lapDone(level, k);
				}
				k.furthest = best;
			} else if (best > k.furthest && best - k.furthest < 40) {
				k.furthest = best;
			}
			k.index = best;
		}
		List<Kart> order = new ArrayList<>(KARTS);
		order.sort(Comparator.comparingInt((Kart k) -> k.finished >= 0 ? -100000 + k.place : 0).thenComparingInt(k -> -(k.lap * n + k.index)));
		for (int i = 0; i < order.size(); i++) {
			Kart k = order.get(i);
			if (k.finished < 0) {
				k.place = i + 1;
			}
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			if (p != null && raceTick % 10 == 0 && k.finished < 0) {
				p.sendOverlayMessage(Component.literal("Lap " + Math.max(1, Math.min(LAPS, k.lap + 1)) + "/" + LAPS + "   " + ordinal(k.place) + " of " + KARTS.size()
						+ "   " + time(raceTick)).withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	private static void lapDone(ServerLevel level, Kart k) {
		ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
		int lapTime = raceTick - k.lapStart;
		if (k.lap >= 0) {
			if (lapTime < k.best) {
				k.best = lapTime;
			}
			if (p != null) {
				Records.submit(level, "Fastest go-kart lap", p.getName().getString(), lapTime, true, Speedway::time);
			}
		}
		k.lap++;
		k.lapStart = raceTick;
		if (p == null) {
			return;
		}
		if (k.lap >= LAPS) {
			k.finished = raceTick;
			finishers++;
			k.place = finishers;
			if (firstFinish < 0) {
				firstFinish = raceTick;
			}
			Alerts.title(p, ordinal(k.place) + " place!", "Finished in " + time(raceTick) + "  (best lap " + time(k.best) + ")", ChatFormatting.GOLD);
			level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.2F);
			HubPark.burst(level, p.position().add(0, 3, 0));
		} else if (k.lap > 0) {
			p.sendSystemMessage(Component.literal("Lap " + k.lap + ": " + time(lapTime)).withStyle(ChatFormatting.GRAY));
			if (k.lap == LAPS - 1) {
				Alerts.title(p, "", "FINAL LAP!", ChatFormatting.YELLOW);
			}
		}
	}

	private static void finish(ServerLevel level) {
		racing = false;
		lights(level, 0);
		List<Kart> order = new ArrayList<>(KARTS);
		order.sort(Comparator.comparingInt((Kart k) -> k.finished >= 0 ? k.place : 100 + k.place));
		StringBuilder line = new StringBuilder();
		for (Kart k : order) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			if (p != null) {
				line.append(line.isEmpty() ? "" : ",  ").append(ordinal(k.place)).append(" ").append(p.getName().getString());
			}
		}
		for (Kart k : new ArrayList<>(KARTS)) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			HubPark.discard(level, k.model);
			HubPark.discard(level, k.seat);
			if (p != null) {
				p.sendSystemMessage(Component.literal("[Rex] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
						.append(Component.literal("Race results: " + line).withStyle(ChatFormatting.WHITE)));
				FunPark.letOff(p, PIT.add(0, 0, -2), "Talk to Rex to race again!");
			}
		}
		KARTS.clear();
	}

	private static void tellAll(ServerLevel level, String text, ChatFormatting color) {
		for (Kart k : KARTS) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			if (p != null) {
				p.sendOverlayMessage(Component.literal(text).withStyle(color));
			}
		}
	}

	private static void tellTitle(ServerLevel level, String text, ChatFormatting color) {
		for (Kart k : KARTS) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			if (p != null) {
				Alerts.title(p, text, "", color);
			}
		}
	}

	private static void place(ServerLevel level, Kart k) {
		Entity m = level.getEntity(k.model);
		if (m != null) {
			m.teleportTo(k.pos.x, k.pos.y, k.pos.z);
			m.setYRot(k.yaw);
		}
		Entity s = level.getEntity(k.seat);
		if (s != null) {
			s.teleportTo(k.pos.x, k.pos.y + 0.15, k.pos.z);
			s.setYRot(k.yaw);
		}
	}

	static String ordinal(int n) {
		return n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th");
	}

	static String time(int ticks) {
		int tenths = ticks / 2;
		return (tenths / 600) + ":" + String.format("%02d", (tenths / 10) % 60) + "." + (tenths % 10);
	}

	static void reset() {
		KARTS.clear();
		lobby = 0;
		countdown = 0;
		racing = false;
	}
}
