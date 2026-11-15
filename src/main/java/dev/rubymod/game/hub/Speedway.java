package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.game.Props;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Speedway: kart racing on ice. Each kart is really a raft on an ice track (so your own game drives it, with no
 * lag: it steers the instant you press), wearing a go-kart. A wide, twisty circuit with walls all round and blue boost
 * strips. Rex starts the races, just you lot or with three computer karts. Three red lights, the gate drops, GO: three
 * laps. W to go, A and D to steer, S to slow down. The top three go up on the podium.
 */
public final class Speedway {
	private Speedway() {
	}

	static final int CX = -110;
	static final int CZ = 0;
	static final int RADIUS = 47;
	static final int Y = FLOOR + 1;
	static final Vec3 LAND = new Vec3(-108.5, FLOOR + 1, -39.5);
	private static final Vec3 PIT = new Vec3(-106.5, FLOOR + 1, -35.5);
	/** The island: circles of x, z, radius. */
	static final int[][] CIRCLES = {{CX, CZ, 43}, {CX + 6, CZ - 32, 14}};
	/** The track's middle line (x, z from the middle of the island), round from the start/finish line. */
	private static final double[][] TRACK = {{0, -28}, {12, -28}, {22, -25}, {30, -16}, {32, -2}, {27, 10}, {16, 14}, {7, 9}, {-2, 13}, {-6, 25},
		{-18, 30}, {-29, 23}, {-32, 8}, {-26, -4}, {-30, -16}, {-22, -27}, {-11, -28.5}};
	private static final int LAPS = 3;
	private static final String[] COLORS = {"red", "blue", "green", "yellow", "pink", "purple", "orange", "white"};
	private static final String[] BOT_NAMES = {"Turbo Tom", "Zippy Zoe", "Speedy Sam"};
	private static final double STEP = 0.5;
	private static final double HALF = 4.6;

	private static List<Vec3> line = List.of();
	private static final List<BlockPos> LIGHTS = new ArrayList<>();
	private static final List<BlockPos> GATE = new ArrayList<>();
	private static final List<BlockPos> PODIUM = new ArrayList<>();

	private static final class Kart {
		final UUID driver;
		final String bot;
		double skill = 1;
		double lane;
		UUID boat;
		UUID model;
		UUID nameTag;
		Vec3 pos = Vec3.ZERO;
		double d;
		int index;
		int lap;
		int furthest;
		int lapStart;
		int best = Integer.MAX_VALUE;
		int finished = -1;
		int place;

		Kart(UUID driver, String bot) {
			this.driver = driver;
			this.bot = bot;
		}

		String name(ServerLevel level) {
			if (bot != null) {
				return bot;
			}
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(driver);
			return p == null ? "?" : p.getName().getString();
		}
	}

	private static final List<Kart> KARTS = new ArrayList<>();
	private static int lobby;
	private static boolean bots;
	private static int countdown;
	private static boolean racing;
	private static int raceTick;
	private static int firstFinish = -1;
	private static int finishers;
	private static int tick;

	// ----- Building -----

	static void build(ServerLevel level) {
		KARTS.clear();
		LIGHTS.clear();
		GATE.clear();
		PODIUM.clear();
		lobby = 0;
		racing = false;
		countdown = 0;
		HubPark.blob(level, CIRCLES, FLOOR);
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : TRACK) {
			pts.add(new Vec3(CX + p[0] + 0.5, Y, CZ + p[1] + 0.5));
		}
		line = loop(pts);
		surface(level);
		gantry(level);
		grandstand(level);
		podium(level);
		for (int[] f : new int[][] {{CX - 36, CZ - 4}, {CX + 36, CZ - 4}, {CX - 14, CZ + 36}, {CX + 20, CZ + 26}, {CX + 18, CZ - 38}, {CX - 20, CZ - 36}}) {
			floodlight(level, f[0], f[1]);
		}
		HubPark.label(level, CX + 0.5, FLOOR + 18, CZ + 0.5, "SPEEDWAY", ChatFormatting.RED, 4.0F);
		HubPark.travelPad(level, CX - 2, FLOOR, CZ - 40, "speedway");
		HubHosts.add(level, PIT, 0, VillagerProfession.TOOLSMITH, VillagerType.SNOW,
				new HubHosts.Host("karts", "Rex", "Speedway", 0xFF5050, "minecraft:minecart",
						"Kart racing on ice! W to go, A and D to steer, S to slow down. Slide round the corners, hit the BLUE strips for a "
								+ "boost. Three laps. Race just your family, or add three computer karts.",
						"Speedway: right-click Rex to race!", p -> {
							if (racing || countdown > 0) {
								return List.of("busy|A race is on: the next one starts soon!");
							}
							if (lobby > 0) {
								return List.of("join|Join the race!");
							}
							return List.of("race|Race (just us)", "bots|Race against 3 computer karts");
						}, (p, key) -> {
							if ("bots".equals(key)) {
								bots = true;
							} else if ("race".equals(key) && lobby <= 0) {
								bots = false;
							}
							if (!"busy".equals(key)) {
								join((ServerLevel) p.level(), p);
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

	private static Vec3 dir(int i) {
		int n = line.size();
		return line.get((i + 2) % n).subtract(line.get((i - 2 + n) % n)).normalize();
	}

	/** Ice where the track is, a padded wall each side, blue boost strips on the straights. */
	private static void surface(ServerLevel level) {
		Map<Long, Double> dist = new HashMap<>();
		Map<Long, Integer> nearest = new HashMap<>();
		for (int i = 0; i < line.size(); i++) {
			Vec3 p = line.get(i);
			for (int x = (int) Math.floor(p.x - HALF - 2); x <= p.x + HALF + 2; x++) {
				for (int z = (int) Math.floor(p.z - HALF - 2); z <= p.z + HALF + 2; z++) {
					double d = Math.hypot(x + 0.5 - p.x, z + 0.5 - p.z);
					long k = BlockPos.asLong(x, 0, z);
					if (d < dist.getOrDefault(k, Double.MAX_VALUE)) {
						dist.put(k, d);
						nearest.put(k, i);
					}
				}
			}
		}
		// Blue strips: on the long fast bits.
		java.util.Set<Integer> boost = new java.util.HashSet<>();
		for (double[] b : new double[][] {{6, -28}, {31, -9}, {-31, 15}}) {
			int i = nearestIndex(CX + b[0] + 0.5, CZ + b[1] + 0.5);
			for (int k = -6; k <= 6; k++) {
				boost.add(((i + k) % line.size() + line.size()) % line.size());
			}
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		for (var e : dist.entrySet()) {
			int x = BlockPos.getX(e.getKey());
			int z = BlockPos.getZ(e.getKey());
			double d = e.getValue();
			if (d <= HALF) {
				boolean blue = boost.contains(nearest.get(e.getKey())) && d < 2.2;
				HubPark.set(level, x, FLOOR, z, blue ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState());
				HubPark.set(level, x, FLOOR - 1, z, Blocks.STONE.defaultBlockState());
				for (int y = 1; y <= 4; y++) {
					HubPark.set(level, x, FLOOR + y, z, air);
				}
			} else if (d <= HALF + 1.2) {
				// The wall: two high, red and white (black and white tyres on the outside of the bends).
				Vec3 p = line.get(nearest.get(e.getKey()));
				Vec3 dv = dir(nearest.get(e.getKey()));
				Vec3 out = new Vec3(x + 0.5 - p.x, 0, z + 0.5 - p.z);
				boolean tyre = Math.abs(dv.x * out.z - dv.z * out.x) > 0 && (x * 3 + z) % 5 == 0;
				HubPark.set(level, x, FLOOR, z, Blocks.STONE.defaultBlockState());
				BlockState w = tyre ? Blocks.WOOL.pick(DyeColor.BLACK).defaultBlockState() : Blocks.WOOL.pick(((x + z) & 2) == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState();
				HubPark.set(level, x, FLOOR + 1, z, w);
				HubPark.set(level, x, FLOOR + 2, z, tyre ? Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState() : w);
			}
		}
	}

	private static int nearestIndex(double x, double z) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < line.size(); i++) {
			double d = Math.hypot(line.get(i).x - x, line.get(i).z - z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	private static void gantry(ServerLevel level) {
		Vec3 s0 = line.get(0);
		Vec3 dv = dir(0);
		Vec3 side = new Vec3(-dv.z, 0, dv.x);
		// The chequered line.
		for (double k = -HALF; k <= HALF; k += 0.5) {
			for (int r = 0; r <= 1; r++) {
				Vec3 c = s0.add(side.scale(k)).add(dv.scale(r * 0.5));
				int cell = (int) Math.floor(k * 2) + r;
				level.setBlock(BlockPos.containing(c.x, FLOOR, c.z), (cell & 1) == 0 ? Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState()
						: Blocks.SNOW_BLOCK.defaultBlockState(), 2);
			}
		}
		BlockState steel = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		Vec3 left = s0.add(side.scale(-HALF - 1.6));
		Vec3 right = s0.add(side.scale(HALF + 1.6));
		HubPark.beam(level, left.add(0, -1, 0), left.add(0, 7.5, 0), 0.5F, 0.5F, steel);
		HubPark.beam(level, right.add(0, -1, 0), right.add(0, 7.5, 0), 0.5F, 0.5F, steel);
		HubPark.beam(level, left.add(0, 7.5, 0), right.add(0, 7.5, 0), 0.7F, 1.1F, Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
		HubPark.label(level, s0.x, FLOOR + 10.5, s0.z, "START / FINISH", ChatFormatting.WHITE, 1.6F);
		for (int i = -2; i <= 2; i++) {
			Vec3 l = s0.add(side.scale(i * 1.3));
			LIGHTS.add(BlockPos.containing(l.x, FLOOR + 7, l.z));
		}
		lights(level, 0);
		// The start gate: a row of glass across the track, dropped at GO.
		for (double k = -HALF; k <= HALF; k += 0.4) {
			BlockPos g = BlockPos.containing(s0.x + side.x * k + dv.x * 1.2, FLOOR + 1, s0.z + side.z * k + dv.z * 1.2);
			if (!GATE.contains(g)) {
				GATE.add(g);
			}
		}
		gate(level, true);
	}

	private static void gate(ServerLevel level, boolean up) {
		for (BlockPos g : GATE) {
			level.setBlock(g, up ? Blocks.STAINED_GLASS.pick(DyeColor.RED).defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		}
	}

	private static void lights(ServerLevel level, int lit) {
		for (int i = 0; i < LIGHTS.size(); i++) {
			BlockState s = lit < 0 ? Blocks.EMERALD_BLOCK.defaultBlockState() : i < lit ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.COAL_BLOCK.defaultBlockState();
			level.setBlock(LIGHTS.get(i), s, 2);
		}
	}

	private static void grandstand(ServerLevel level) {
		// North of the start straight, facing it.
		int z0 = CZ - 37;
		for (int row = 0; row < 4; row++) {
			for (int x = CX - 20; x <= CX - 6; x++) {
				DyeColor c = row % 3 == 0 ? DyeColor.RED : row % 3 == 1 ? DyeColor.WHITE : DyeColor.BLUE;
				HubPark.set(level, x, FLOOR + 1 + row, z0 + 3 - row, Blocks.WOOL.pick(c).defaultBlockState());
				HubPark.box(level, x, FLOOR + 1, z0 + 3 - row, x, FLOOR + row, z0 + 3 - row, Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState());
			}
		}
		for (int x = CX - 21; x <= CX - 5; x++) {
			for (int z = z0 - 1; z <= z0 + 4; z++) {
				HubPark.set(level, x, FLOOR + 8, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
		}
		for (int x : new int[] {CX - 21, CX - 13, CX - 5}) {
			HubPark.box(level, x, FLOOR + 1, z0 - 1, x, FLOOR + 7, z0 - 1, Blocks.IRON_BARS.defaultBlockState());
		}
	}

	private static void podium(ServerLevel level) {
		int px = CX + 13;
		int pz = CZ - 38;
		int[][] steps = {{0, 3}, {-2, 2}, {2, 1}};
		BlockState[] looks = {Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.ORANGE).defaultBlockState()};
		for (int i = 0; i < 3; i++) {
			HubPark.box(level, px + steps[i][0], FLOOR + 1, pz, px + steps[i][0] + 1, FLOOR + steps[i][1], pz + 1, looks[i]);
			PODIUM.add(new BlockPos(px + steps[i][0], FLOOR + steps[i][1] + 1, pz));
		}
		HubPark.label(level, px + 1, FLOOR + 7, pz + 1, "WINNERS' PODIUM", ChatFormatting.GOLD, 1.0F);
	}

	private static void floodlight(ServerLevel level, int x, int z) {
		if (!level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.GRASS_BLOCK)) {
			return;
		}
		HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 10, z, Blocks.IRON_BARS.defaultBlockState());
		HubPark.box(level, x - 1, FLOOR + 11, z, x + 1, FLOOR + 11, z, Blocks.SEA_LANTERN.defaultBlockState());
		HubPark.box(level, x, FLOOR + 11, z - 1, x, FLOOR + 11, z + 1, Blocks.SEA_LANTERN.defaultBlockState());
	}

	// ----- Racing -----

	private static void join(ServerLevel level, ServerPlayer p) {
		if (racing || countdown > 0) {
			return;
		}
		for (Kart k : KARTS) {
			if (p.getUUID().equals(k.driver)) {
				return;
			}
		}
		if (KARTS.size() >= COLORS.length - 3) {
			p.sendOverlayMessage(Component.literal("The grid's full!").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (lobby <= 0) {
			lobby = 15 * 20;
			gate(level, true);
			lights(level, 0);
			for (ServerPlayer q : level.players()) {
				if (q != p) {
					q.sendSystemMessage(Component.literal("[Rex] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
							.append(Component.literal(p.getName().getString() + " is starting a kart race" + (bots ? " (with computer karts)" : "")
									+ "! Fly to the Speedway (Park Map) and talk to Rex in the next 15 seconds to join.").withStyle(ChatFormatting.WHITE)));
				}
			}
		}
		Kart k = new Kart(p.getUUID(), null);
		if (p.isPassenger()) {
			p.stopRiding();
		}
		if (!grid(level, k, p)) {
			return;
		}
		p.sendOverlayMessage(Component.literal("You're on the grid! Wait for the green lights... (W to go, A/D to steer)").withStyle(ChatFormatting.RED));
	}

	/** Put a kart on its grid slot (behind the line, two by two). */
	private static boolean grid(ServerLevel level, Kart k, ServerPlayer driver) {
		int slot = KARTS.size();
		int n = line.size();
		int back = n - 5 - (slot / 2) * 8;
		Vec3 base = line.get(back);
		Vec3 ahead = dir(back);
		Vec3 side = new Vec3(-ahead.z, 0, ahead.x);
		k.lane = slot % 2 == 0 ? -2.0 : 2.0;
		k.pos = base.add(side.scale(k.lane));
		k.d = back * STEP;
		k.index = back;
		k.furthest = back;
		k.lap = -1;
		float yaw = (float) Math.toDegrees(Math.atan2(-ahead.x, ahead.z));
		AbstractBoat boat = EntityTypes.BAMBOO_RAFT.create(level, EntitySpawnReason.EVENT);
		if (boat == null) {
			return false;
		}
		boat.snapTo(k.pos.x, Y, k.pos.z, yaw, 0);
		boat.setPermanentlyInvulnerable(true);
		boat.addTag(HubRides.TEMP);
		HubPark.live(boat);
		level.addFreshEntity(boat);
		k.boat = boat.getUUID();
		Display.ItemDisplay m = HubPark.tempModel(level, k.pos, yaw, "kart_" + COLORS[slot % COLORS.length], 1.9F, 0);
		if (m == null) {
			boat.discard();
			return false;
		}
		k.model = m.getUUID();
		((DisplayAccessor) m).rubymod$setPosRotInterpolationDuration(1);
		if (driver != null) {
			driver.teleportTo(k.pos.x, Y, k.pos.z);
			driver.startRiding(boat, true, true);
			// The kart rides behind the driver in the raft, so it's nudged forward to sit round them.
			((DisplayAccessor) m).rubymod$setTransformation(new Transformation(new Vector3f(0, 0.1F, 0.6F), new Quaternionf().rotateY((float) Math.PI),
					new Vector3f(1.9F, 1.9F, 1.9F), new Quaternionf()));
		} else {
			((DisplayAccessor) m).rubymod$setTransformation(new Transformation(new Vector3f(0, 0.1F, 0), new Quaternionf().rotateY((float) Math.PI),
					new Vector3f(1.9F, 1.9F, 1.9F), new Quaternionf()));
			boat.setNoGravity(true);
			Display.TextDisplay tag = Props.text(level, k.pos.add(0, 1.8, 0), Component.literal(k.bot).withStyle(ChatFormatting.AQUA), 0.6F, HubRides.TEMP);
			if (tag != null) {
				HubPark.live(tag);
				((DisplayAccessor) tag).rubymod$setPosRotInterpolationDuration(2);
				k.nameTag = tag.getUUID();
			}
		}
		m.startRiding(boat, true, true);
		KARTS.add(k);
		return true;
	}

	static void tick(ServerLevel level, int tickNow) {
		tick++;
		if (line.isEmpty()) {
			return;
		}
		// Drivers who hop out (or leave) are out of the race.
		for (Iterator<Kart> it = KARTS.iterator(); it.hasNext();) {
			Kart k = it.next();
			Entity boat = level.getEntity(k.boat);
			if (k.bot != null) {
				continue;
			}
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			if (p == null || boat == null || p.getVehicle() != boat) {
				remove(level, k);
				it.remove();
				if (p != null && p.level() == level) {
					FunPark.letOff(p, PIT.add(0, 0, 2), "Out of the race! Talk to Rex to race again.");
				}
			} else {
				k.pos = boat.position();
			}
		}
		for (Kart k : KARTS) {
			Entity boat = level.getEntity(k.boat);
			Entity m = level.getEntity(k.model);
			if (boat != null && m != null) {
				m.setYRot(boat.getYRot());
			}
		}
		boolean people = KARTS.stream().anyMatch(k -> k.bot == null);
		if (lobby > 0) {
			if (!people) {
				lobby = 0;
				clearBots(level);
				return;
			}
			if (lobby % 100 == 0) {
				tellAll(level, "Race starts in " + lobby / 20 + " seconds... (" + KARTS.size() + " on the grid)", ChatFormatting.GOLD);
			}
			if (--lobby == 0) {
				if (bots) {
					for (int i = 0; i < 3 && KARTS.size() < COLORS.length; i++) {
						Kart b = new Kart(null, BOT_NAMES[i]);
						b.skill = 0.82 + i * 0.06;
						grid(level, b, null);
					}
				}
				countdown = 100;
			}
		}
		if (countdown > 0) {
			if (!people) {
				countdown = 0;
				lights(level, 0);
				clearBots(level);
				return;
			}
			if (countdown % 20 == 0 && countdown <= 80) {
				lights(level, 5 - countdown / 20);
				level.playSound(null, BlockPos.containing(line.get(0)), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.5F, 0.8F);
				tellTitle(level, String.valueOf(countdown / 20), ChatFormatting.RED);
			}
			if (--countdown == 0) {
				lights(level, -1);
				gate(level, false);
				racing = true;
				raceTick = 0;
				firstFinish = -1;
				finishers = 0;
				level.playSound(null, BlockPos.containing(line.get(0)), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 2.0F, 1.6F);
				level.playSound(null, BlockPos.containing(line.get(0)), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.0F, 1.0F);
				tellTitle(level, "GO!", ChatFormatting.GREEN);
			}
		}
		if (racing) {
			raceTick++;
			bots(level);
			standings(level);
			boolean peopleDone = KARTS.stream().filter(k -> k.bot == null).allMatch(k -> k.finished >= 0);
			if (!people || peopleDone || firstFinish >= 0 && raceTick - firstFinish > 40 * 20) {
				finish(level);
			}
		}
		// The engine: a buzz that rises with speed (just for the driver).
		if (tick % 4 == 0) {
			for (Kart k : KARTS) {
				ServerPlayer p = k.bot == null ? level.getServer().getPlayerList().getPlayer(k.driver) : null;
				Entity boat = level.getEntity(k.boat);
				if (p != null && boat != null) {
					double v = boat.getDeltaMovement().horizontalDistance();
					if (v > 0.05) {
						p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_DIDGERIDOO, SoundSource.PLAYERS,
								boat.getX(), boat.getY(), boat.getZ(), 0.15F, (float) (0.5 + v * 1.4), 0));
					}
				}
			}
		}
	}

	/** The computer karts: round the line, slowing for the bends. */
	private static void bots(ServerLevel level) {
		int n = line.size();
		for (Kart k : KARTS) {
			if (k.bot == null || k.finished >= 0) {
				continue;
			}
			int i = (int) Math.floor(k.d / STEP) % n;
			Vec3 a = dir(i);
			Vec3 b = dir((i + 14) % n);
			double bend = Math.acos(Math.max(-1, Math.min(1, a.dot(b))));
			double v = Math.min(raceTick / 30.0, 1) * 0.7 * k.skill * (1 - 0.45 * Math.min(1, bend / 1.0));
			k.d = (k.d + v) % (n * STEP);
			int j = (int) Math.floor(k.d / STEP) % n;
			Vec3 at = line.get(j).lerp(line.get((j + 1) % n), k.d / STEP - Math.floor(k.d / STEP));
			Vec3 dv = dir(j);
			Vec3 side = new Vec3(-dv.z, 0, dv.x);
			// Drift back towards the middle lane as the race goes on.
			k.lane *= 0.995;
			k.pos = at.add(side.scale(k.lane));
			Entity boat = level.getEntity(k.boat);
			float yaw = (float) Math.toDegrees(Math.atan2(-dv.x, dv.z));
			if (boat != null) {
				boat.teleportTo(k.pos.x, Y, k.pos.z);
				boat.setYRot(yaw);
				boat.setDeltaMovement(Vec3.ZERO);
			}
			Entity t = k.nameTag == null ? null : level.getEntity(k.nameTag);
			if (t != null) {
				t.teleportTo(k.pos.x, Y + 1.8, k.pos.z);
			}
		}
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
				double d = line.get(i).distanceToSqr(k.pos.x, Y, k.pos.z);
				if (d < bd) {
					bd = d;
					best = i;
				}
			}
			if (k.index > n - 50 && best < 50) {
				if (k.lap < 0 || k.furthest > n * 0.8) {
					lapDone(level, k);
				}
				k.furthest = best;
			} else if (best > k.furthest && best - k.furthest < 50) {
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
			ServerPlayer p = k.bot == null ? level.getServer().getPlayerList().getPlayer(k.driver) : null;
			if (p != null && raceTick % 10 == 0 && k.finished < 0) {
				p.sendOverlayMessage(Component.literal("Lap " + Math.max(1, Math.min(LAPS, k.lap + 1)) + "/" + LAPS + "   " + ordinal(k.place) + " of " + KARTS.size()
						+ "   " + time(raceTick)).withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	private static void lapDone(ServerLevel level, Kart k) {
		ServerPlayer p = k.bot == null ? level.getServer().getPlayerList().getPlayer(k.driver) : null;
		int lapTime = raceTick - k.lapStart;
		if (k.lap >= 0) {
			k.best = Math.min(k.best, lapTime);
			if (p != null) {
				Records.submit(level, "Fastest go-kart lap", p.getName().getString(), lapTime, true, Speedway::time);
			}
		}
		k.lap++;
		k.lapStart = raceTick;
		if (k.lap >= LAPS) {
			k.finished = raceTick;
			finishers++;
			k.place = finishers;
			if (firstFinish < 0) {
				firstFinish = raceTick;
			}
			if (p != null) {
				Alerts.title(p, ordinal(k.place) + " place!", "Finished in " + time(raceTick) + "  (best lap " + time(k.best) + ")", ChatFormatting.GOLD);
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.2F);
			}
		} else if (p != null && k.lap > 0) {
			p.sendSystemMessage(Component.literal("Lap " + k.lap + ": " + time(lapTime)).withStyle(ChatFormatting.GRAY));
			if (k.lap == LAPS - 1) {
				Alerts.title(p, "", "FINAL LAP!", ChatFormatting.YELLOW);
			}
		}
	}

	private static void finish(ServerLevel level) {
		racing = false;
		lights(level, 0);
		gate(level, true);
		List<Kart> order = new ArrayList<>(KARTS);
		order.sort(Comparator.comparingInt((Kart k) -> k.finished >= 0 ? k.place : 100 + k.place));
		StringBuilder results = new StringBuilder();
		for (int i = 0; i < order.size(); i++) {
			results.append(results.isEmpty() ? "" : ",  ").append(ordinal(i + 1)).append(" ").append(order.get(i).name(level));
		}
		for (int i = 0; i < order.size(); i++) {
			Kart k = order.get(i);
			ServerPlayer p = k.bot == null ? level.getServer().getPlayerList().getPlayer(k.driver) : null;
			remove(level, k);
			if (p == null) {
				continue;
			}
			p.sendSystemMessage(Component.literal("[Rex] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
					.append(Component.literal("Race results: " + results).withStyle(ChatFormatting.WHITE)));
			if (i < 3) {
				BlockPos spot = PODIUM.get(i);
				FunPark.letOff(p, Vec3.atBottomCenterOf(spot), i == 0 ? "You won! Up on the top step!" : "On the podium: " + ordinal(i + 1) + "!");
				p.setYRot(180);
				HubPark.burst(level, Vec3.atCenterOf(spot).add(0, 4, 0));
			} else {
				FunPark.letOff(p, PIT.add(0, 0, 2), "Talk to Rex to race again!");
			}
		}
		KARTS.clear();
		bots = false;
	}

	private static void clearBots(ServerLevel level) {
		for (Iterator<Kart> it = KARTS.iterator(); it.hasNext();) {
			Kart k = it.next();
			if (k.bot != null) {
				remove(level, k);
				it.remove();
			}
		}
	}

	private static void remove(ServerLevel level, Kart k) {
		HubPark.discard(level, k.model);
		HubPark.discard(level, k.boat);
		HubPark.discard(level, k.nameTag);
	}

	private static void tellAll(ServerLevel level, String text, ChatFormatting color) {
		for (Kart k : KARTS) {
			ServerPlayer p = k.bot == null ? level.getServer().getPlayerList().getPlayer(k.driver) : null;
			if (p != null) {
				p.sendOverlayMessage(Component.literal(text).withStyle(color));
			}
		}
	}

	private static void tellTitle(ServerLevel level, String text, ChatFormatting color) {
		for (Kart k : KARTS) {
			ServerPlayer p = k.bot == null ? level.getServer().getPlayerList().getPlayer(k.driver) : null;
			if (p != null) {
				Alerts.title(p, text, "", color);
			}
		}
	}

	static boolean busy() {
		return !KARTS.isEmpty();
	}

	static String ordinal(int n) {
		return n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th");
	}

	static String time(int ticks) {
		int tenths = ticks / 2;
		return (tenths / 600) + ":" + String.format("%02d", (tenths / 10) % 60) + "." + (tenths % 10);
	}

	/** For tests: where each kart is (name, position, lap, place). */
	public static String debug(ServerLevel level) {
		StringBuilder b = new StringBuilder();
		for (Kart k : KARTS) {
			b.append(String.format(java.util.Locale.ROOT, "%s@(%.1f,%.1f,%.1f) lap %d #%d; ", k.name(level), k.pos.x, k.pos.y, k.pos.z, k.lap, k.place));
		}
		return b.toString();
	}

	static void reset() {
		KARTS.clear();
		lobby = 0;
		countdown = 0;
		racing = false;
		bots = false;
	}
}
