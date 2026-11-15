package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.game.Props;
import dev.rubymod.mixin.DisplayAccessor;

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
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Speedway: a big figure-eight go-kart track that crosses over itself on a bridge, with a jump, glowing boost pads,
 * curbs and tyre walls, a grandstand and a podium. Rex starts the races: just players, or with three computer karts
 * (your choice). Three red lights, GO, three laps. W to drive, S to brake, A and D to steer; turn hard at speed to
 * drift. The top three go up on the podium.
 */
public final class Speedway {
	private Speedway() {
	}

	static final int CX = -96;
	static final int CZ = 6;
	static final Vec3 LAND = new Vec3(-95.5, FLOOR + 1, 22.5);
	private static final Vec3 PIT = new Vec3(-98.5, FLOOR + 1, 20.5);
	/** The racing line: x, z (from the middle), height. Starts at the start/finish line and crosses over itself at 0, 0. */
	private static final double[][] TRACK = {{-10, -9, 0}, {-16, -10.5, 0}, {-22, -8, 0}, {-25, 0, 0}, {-22, 8, 0.5}, {-16, 11, 1.5}, {-10, 9.5, 3},
		{-5, 5, 4.4}, {0, 0, 5}, {5, -5, 4.2}, {10, -9.5, 2.5}, {16, -11, 1}, {22, -8, 0}, {25, 0, 0}, {22, 8, 0}, {16, 11, 0}, {10, 9.5, 0},
		{5, 5, 0}, {0, 0, 0}, {-5, -5, 0}};
	private static final int LAPS = 3;
	private static final String[] COLORS = {"red", "blue", "green", "yellow", "pink", "purple", "orange", "white"};
	private static final String[] BOT_NAMES = {"Turbo Tom", "Zippy Zoe", "Speedy Sam"};
	private static final double STEP = 0.5;
	private static final double HALF = 3.5;

	private static List<Vec3> line = List.of();
	private static final Set<Long> BOOSTS = new HashSet<>();
	private static final List<BlockPos> LIGHTS = new ArrayList<>();
	private static final List<BlockPos> PODIUM = new ArrayList<>();

	private static final class Kart {
		final UUID driver;
		final String bot;
		double skill = 1;
		UUID model;
		UUID seat;
		UUID nameTag;
		Vec3 pos;
		Vec3 vel = Vec3.ZERO;
		double vy;
		float yaw;
		int index;
		int lap;
		int furthest;
		int lapStart;
		int best = Integer.MAX_VALUE;
		int finished = -1;
		int place;
		int bonk;
		int boost;
		boolean drifting;

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

	// ----- Building -----

	static void build(ServerLevel level) {
		KARTS.clear();
		LIGHTS.clear();
		PODIUM.clear();
		BOOSTS.clear();
		lobby = 0;
		racing = false;
		countdown = 0;
		HubPark.island(level, CX, CZ, 30, FLOOR);
		HubPark.clear(level, CX - 30, FLOOR + 1, CZ - 30, CX + 30, FLOOR + 10, CZ + 30);
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : TRACK) {
			pts.add(new Vec3(CX + p[0] + 0.5, FLOOR + 1 + p[2], CZ + p[1] + 0.5));
		}
		line = loop(pts);
		// The jump: a kicker on the far bend.
		int kick = nearest(CX + 24.5, CZ + 3.5);
		double[] bump = new double[line.size()];
		for (int k = 0; k < 9; k++) {
			bump[(kick + k) % line.size()] = k * 0.19;
		}
		surface(level, bump);
		// Boost pads.
		for (double[] b : new double[][] {{-24, -3}, {2.5, 2.5}, {18, -10.5}, {13, 10.5}}) {
			int i = nearest(CX + b[0] + 0.5, CZ + b[1] + 0.5);
			Vec3 c = line.get(i);
			Vec3 d = line.get((i + 2) % line.size()).subtract(c).normalize();
			Vec3 side = new Vec3(-d.z, 0, d.x);
			for (int a = -1; a <= 1; a++) {
				for (int s = -2; s <= 2; s++) {
					Vec3 q = c.add(d.scale(a)).add(side.scale(s * 0.6));
					BlockPos bp = BlockPos.containing(q.x, c.y - 0.6, q.z);
					level.setBlock(bp, Blocks.GLAZED_TERRACOTTA.pick(DyeColor.MAGENTA).defaultBlockState(), 2);
					BOOSTS.add(BlockPos.asLong(bp.getX(), 0, bp.getZ()));
				}
			}
		}
		gantry(level);
		grandstand(level);
		podium(level);
		for (int[] f : new int[][] {{CX - 27, CZ - 12}, {CX + 27, CZ - 12}, {CX - 27, CZ + 12}, {CX + 27, CZ + 12}, {CX, CZ - 26}}) {
			floodlight(level, f[0], f[1]);
		}
		HubPark.label(level, CX + 0.5, FLOOR + 16, CZ + 0.5, "SPEEDWAY", ChatFormatting.RED, 3.0F);
		HubPark.travelPad(level, CX + 7, FLOOR, CZ + 15, "speedway");
		HubHosts.add(level, PIT, 0, VillagerProfession.TOOLSMITH, VillagerType.SNOW,
				new HubHosts.Host("karts", "Rex", "Speedway", 0xFF5050, "minecraft:minecart",
						"Go-kart racing round the big figure-eight: over the bridge, under the bridge, over the jump. Hit the pink pads for a boost! "
								+ "Three laps. W to drive, S to brake, A and D to steer (turn hard at speed to drift). Race just your family, "
								+ "or add three computer karts.",
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

	private static int nearest(double x, double z) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < line.size(); i++) {
			double d = Math.hypot(line.get(i).x - x, line.get(i).z - z) + line.get(i).y * 0.001;
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	/** Tarmac along the line (decks on the bridge and its ramps, in half-block steps), curbs, and tyre walls or barriers. */
	private static void surface(ServerLevel level, double[] bump) {
		// For each column, the layers of track over it: the height of the road and how close the middle of the road is.
		Map<Long, List<double[]>> columns = new HashMap<>();
		for (int i = 0; i < line.size(); i++) {
			Vec3 p = line.get(i);
			double h = p.y + bump[i];
			for (int x = (int) Math.floor(p.x - 5); x <= p.x + 5; x++) {
				for (int z = (int) Math.floor(p.z - 5); z <= p.z + 5; z++) {
					double d = Math.hypot(x + 0.5 - p.x, z + 0.5 - p.z);
					if (d > 5) {
						continue;
					}
					List<double[]> layers = columns.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> new ArrayList<>());
					double[] same = null;
					for (double[] l : layers) {
						if (Math.abs(l[0] - h) < 2) {
							same = l;
						}
					}
					if (same == null) {
						layers.add(new double[] {h, d});
					} else if (d < same[1]) {
						same[0] = h;
						same[1] = d;
					}
				}
			}
		}
		BlockState tarmac = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		BlockState tarmacSlab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
		for (var e : columns.entrySet()) {
			int x = BlockPos.getX(e.getKey());
			int z = BlockPos.getZ(e.getKey());
			for (double[] l : e.getValue()) {
				double top = Math.floor(l[0] * 2) / 2;
				boolean ground = top < FLOOR + 1.3;
				boolean half = top != Math.floor(top);
				int y = half ? (int) Math.floor(top) : (int) top - 1;
				if (l[1] <= HALF + 0.7) {
					BlockState s;
					if (l[1] > HALF) {
						s = Blocks.CONCRETE.pick(((x + z) & 1) == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState();
					} else {
						s = tarmac;
					}
					if (half) {
						s = l[1] > HALF ? Blocks.CUT_RED_SANDSTONE_SLAB.defaultBlockState() : tarmacSlab;
					}
					level.setBlock(new BlockPos(x, y, z), s, 2);
					if (!ground && !half) {
						level.setBlock(new BlockPos(x, y - 1, z), Blocks.AIR.defaultBlockState(), 2);
					}
				} else if (l[1] <= HALF + 1.5) {
					boolean onTrack = false;
					for (double[] o : e.getValue()) {
						if (o != l && o[1] <= HALF + 0.7 && Math.abs(o[0] - (y + 1)) < 1.6) {
							onTrack = true;
						}
					}
					if (!onTrack) {
						if (!ground) {
							level.setBlock(new BlockPos(x, y, z), half ? Blocks.SMOOTH_STONE_SLAB.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY)
									.defaultBlockState(), 2);
						}
						BlockState wall = ground ? Blocks.WOOL.pick(((x * 3 + z) % 4 == 0) ? DyeColor.WHITE : DyeColor.BLACK).defaultBlockState()
								: Blocks.CONCRETE.pick(((x + z) % 3 == 0) ? DyeColor.RED : DyeColor.WHITE).defaultBlockState();
						level.setBlock(new BlockPos(x, (int) Math.floor(top), z), wall, 2);
						boolean roadBelow = false;
						for (double[] o : e.getValue()) {
							if (o != l && o[0] < l[0] - 1 && o[1] <= HALF + 1.6) {
								roadBelow = true;
							}
						}
						if (!ground && !roadBelow && (x + z) % 7 == 0) {
							// Bridge supports, down to the ground.
							for (int sy = (int) Math.floor(top) - 1; sy > FLOOR; sy--) {
								BlockPos sp = new BlockPos(x, sy, z);
								if (level.getBlockState(sp).isAir()) {
									level.setBlock(sp, Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(), 2);
								}
							}
						}
					}
				}
			}
		}
	}

	private static void gantry(ServerLevel level) {
		Vec3 s0 = line.get(0);
		Vec3 dir = line.get(2).subtract(s0).normalize();
		Vec3 side = new Vec3(-dir.z, 0, dir.x);
		for (int k = -7; k <= 7; k++) {
			for (int r = 0; r <= 1; r++) {
				Vec3 c = s0.add(side.scale(k * 0.5)).add(dir.scale(r * 0.5));
				level.setBlock(BlockPos.containing(c.x, FLOOR, c.z), Blocks.CONCRETE.pick(((k + r) & 1) == 0 ? DyeColor.BLACK : DyeColor.WHITE).defaultBlockState(), 2);
			}
		}
		BlockState steel = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		Vec3 left = s0.add(side.scale(-5.6));
		Vec3 right = s0.add(side.scale(5.6));
		HubPark.beam(level, left.add(0, -1, 0), left.add(0, 5.5, 0), 0.5F, 0.5F, steel);
		HubPark.beam(level, right.add(0, -1, 0), right.add(0, 5.5, 0), 0.5F, 0.5F, steel);
		HubPark.beam(level, left.add(0, 5.5, 0), right.add(0, 5.5, 0), 0.7F, 0.9F, Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
		HubPark.label(level, s0.x, FLOOR + 8, s0.z, "START / FINISH", ChatFormatting.WHITE, 1.4F);
		for (int i = -2; i <= 2; i++) {
			Vec3 l = s0.add(side.scale(i * 1.1));
			BlockPos lp = BlockPos.containing(l.x, FLOOR + 5, l.z);
			LIGHTS.add(lp);
		}
		lights(level, 0);
	}

	private static void lights(ServerLevel level, int lit) {
		for (int i = 0; i < LIGHTS.size(); i++) {
			BlockState s = lit < 0 ? Blocks.EMERALD_BLOCK.defaultBlockState() : i < lit ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.COAL_BLOCK.defaultBlockState();
			level.setBlock(LIGHTS.get(i), s, 2);
		}
	}

	private static void grandstand(ServerLevel level) {
		// Facing north over the track, with a striped roof; the records board on top.
		for (int row = 0; row < 4; row++) {
			for (int x = CX - 12; x <= CX + 12; x++) {
				DyeColor c = row % 3 == 0 ? DyeColor.RED : row % 3 == 1 ? DyeColor.WHITE : DyeColor.BLUE;
				HubPark.set(level, x, FLOOR + 1 + row, CZ + 17 + row, Blocks.WOOL.pick(c).defaultBlockState());
				HubPark.box(level, x, FLOOR + 1, CZ + 17 + row, x, FLOOR + row, CZ + 17 + row, Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState());
			}
		}
		for (int x : new int[] {CX - 13, CX, CX + 13}) {
			HubPark.box(level, x, FLOOR + 1, CZ + 21, x, FLOOR + 7, CZ + 21, Blocks.IRON_BARS.defaultBlockState());
		}
		for (int x = CX - 13; x <= CX + 13; x++) {
			for (int z = CZ + 16; z <= CZ + 22; z++) {
				HubPark.set(level, x, FLOOR + 8, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
		}
	}

	private static void podium(ServerLevel level) {
		// North of the track: gold in the middle, silver and bronze either side.
		int px = CX;
		int pz = CZ - 20;
		int[][] steps = {{0, 3}, {-2, 2}, {2, 1}};
		BlockState[] looks = {Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.ORANGE).defaultBlockState()};
		for (int i = 0; i < 3; i++) {
			HubPark.box(level, px + steps[i][0] - 0, FLOOR + 1, pz, px + steps[i][0] + 1, FLOOR + steps[i][1], pz + 1, looks[i]);
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
			for (ServerPlayer q : level.players()) {
				if (q != p) {
					q.sendSystemMessage(Component.literal("[Rex] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
							.append(Component.literal(p.getName().getString() + " is starting a go-kart race" + (bots ? " (with computer karts)" : "")
									+ "! Fly to the Speedway (Park Map) and talk to Rex in the next 15 seconds to join.").withStyle(ChatFormatting.WHITE)));
				}
			}
		}
		Kart k = new Kart(p.getUUID(), null);
		if (!grid(level, k)) {
			return;
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		p.startRiding(level.getEntity(k.seat), true, true);
		p.sendOverlayMessage(Component.literal("You're on the grid! Wait for the green lights...").withStyle(ChatFormatting.RED));
	}

	/** Put a kart on its grid slot (behind the line, two by two). */
	private static boolean grid(ServerLevel level, Kart k) {
		int slot = KARTS.size();
		int back = line.size() - 6 - (slot / 2) * 7;
		Vec3 base = line.get(back);
		Vec3 ahead = line.get(back + 2).subtract(base).normalize();
		Vec3 side = new Vec3(-ahead.z, 0, ahead.x);
		k.pos = base.add(side.scale(slot % 2 == 0 ? -1.4 : 1.4));
		k.yaw = (float) Math.toDegrees(Math.atan2(-ahead.x, ahead.z));
		k.index = back;
		k.furthest = back;
		k.lap = -1;
		Display.ItemDisplay m = HubPark.tempModel(level, k.pos, k.yaw, "kart_" + COLORS[slot % COLORS.length], 1.5F, 0.75F);
		Display.BlockDisplay seat = HubPark.seat(level, k.pos.add(0, 0.15, 0));
		if (m == null || seat == null) {
			return false;
		}
		k.model = m.getUUID();
		k.seat = seat.getUUID();
		if (k.bot != null) {
			Display.TextDisplay tag = Props.text(level, k.pos.add(0, 1.6, 0), Component.literal(k.bot).withStyle(ChatFormatting.AQUA), 0.6F, HubRides.TEMP);
			if (tag != null) {
				HubPark.live(tag);
				((DisplayAccessor) tag).rubymod$setPosRotInterpolationDuration(2);
				k.nameTag = tag.getUUID();
			}
		}
		KARTS.add(k);
		place(level, k);
		return true;
	}

	static void tick(ServerLevel level, int tick) {
		if (line.isEmpty()) {
			return;
		}
		// Drivers who hop out (or leave) are out of the race.
		for (Iterator<Kart> it = KARTS.iterator(); it.hasNext();) {
			Kart k = it.next();
			if (k.bot != null) {
				continue;
			}
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(k.driver);
			Entity seat = level.getEntity(k.seat);
			if (p == null || seat == null || p.getVehicle() != seat) {
				remove(level, k);
				it.remove();
				if (p != null && p.level() == level) {
					FunPark.letOff(p, PIT.add(0, 0, 2), "Out of the race! Talk to Rex to race again.");
				}
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
						b.skill = 0.86 + i * 0.05;
						grid(level, b);
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
				int lit = 5 - countdown / 20;
				lights(level, lit);
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
				level.playSound(null, BlockPos.containing(line.get(0)), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.0F, 1.0F);
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
			boolean peopleDone = KARTS.stream().filter(k -> k.bot == null).allMatch(k -> k.finished >= 0);
			if (!people || peopleDone || firstFinish >= 0 && raceTick - firstFinish > 40 * 20) {
				finish(level);
			}
		}
		for (Kart k : KARTS) {
			place(level, k);
		}
	}

	private static void drive(ServerLevel level) {
		for (Kart k : KARTS) {
			ServerPlayer p = k.bot == null ? level.getServer().getPlayerList().getPlayer(k.driver) : null;
			boolean fwd;
			boolean back;
			boolean left;
			boolean right;
			if (k.bot != null) {
				// The computer karts aim a little way ahead along the racing line.
				Vec3 target = line.get((k.index + 9) % line.size());
				double want = Math.toDegrees(Math.atan2(-(target.x - k.pos.x), target.z - k.pos.z));
				double diff = ((want - k.yaw) % 360 + 540) % 360 - 180;
				left = diff < -4;
				right = diff > 4;
				fwd = k.finished < 0 && k.vel.length() < 0.47 * k.skill;
				back = false;
			} else {
				Input in = k.finished >= 0 || p == null ? Input.EMPTY : p.getLastClientInput();
				fwd = in.forward();
				back = in.backward();
				left = in.left();
				right = in.right();
			}
			double speed = k.vel.length();
			float turn = (float) (3.6 + Math.min(1, speed / 0.3) * 2.6);
			k.drifting = (left || right) && speed > 0.36 && k.bot == null;
			if (k.drifting) {
				turn *= 1.25F;
			}
			if (left) {
				k.yaw -= turn;
			}
			if (right) {
				k.yaw += turn;
			}
			Vec3 ahead = new Vec3(-Math.sin(Math.toRadians(k.yaw)), 0, Math.cos(Math.toRadians(k.yaw)));
			double grip = k.drifting ? 0.82 : 0.6;
			Vec3 along = ahead.scale(k.vel.dot(ahead));
			k.vel = along.add(k.vel.subtract(along).scale(grip));
			if (fwd) {
				k.vel = k.vel.add(ahead.scale(0.03));
			} else if (back) {
				k.vel = k.vel.add(ahead.scale(-0.03));
			}
			BlockPos under = BlockPos.containing(k.pos.x, k.pos.y - 0.6, k.pos.z);
			if (level.getBlockState(under).is(Blocks.GLAZED_TERRACOTTA.pick(DyeColor.MAGENTA)) && k.boost <= 0) {
				k.boost = 30;
				level.playSound(null, BlockPos.containing(k.pos), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.2F, 1.6F);
				if (p != null) {
					p.sendOverlayMessage(Component.literal("BOOST!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
				}
			}
			boolean grass = level.getBlockState(under).is(Blocks.GRASS_BLOCK) || level.getBlockState(under).is(Blocks.DIRT);
			double max = k.boost > 0 ? 0.8 : grass ? 0.22 : 0.5;
			if (k.boost > 0) {
				k.boost--;
				k.vel = k.vel.add(ahead.scale(0.05));
				level.sendParticles(ParticleTypes.FLAME, k.pos.x - ahead.x * 0.9, k.pos.y + 0.3, k.pos.z - ahead.z * 0.9, 2, 0.05, 0.05, 0.05, 0.01);
			}
			k.vel = k.vel.scale(0.965);
			if (k.vel.length() > max) {
				k.vel = k.vel.normalize().scale(Math.max(max, k.vel.length() * 0.93));
			}
			Vec3 next = k.pos.add(k.vel.x, 0, k.vel.z);
			if (wall(level, next)) {
				if (k.bonk <= 0 && k.vel.length() > 0.12) {
					level.playSound(null, BlockPos.containing(k.pos), SoundEvents.WOOL_HIT, SoundSource.PLAYERS, 1.0F, 0.7F);
					level.playSound(null, BlockPos.containing(k.pos), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 0.6F, 0.8F);
					k.bonk = 8;
				}
				k.vel = k.vel.scale(-0.35);
				next = k.pos;
			}
			// Up and down: follow the road (half-block steps), fly off the jump, fall back down.
			double ground = groundAt(level, next, k.pos.y);
			double y = k.pos.y;
			if (ground >= y - 0.05) {
				double climb = ground - y;
				y = ground;
				k.vy = climb > 0 ? Math.min(0.4, climb * 0.9) : 0;
			} else {
				k.vy -= 0.06;
				y = Math.max(ground, y + k.vy);
				if (y <= ground + 0.01 && k.vy < -0.25) {
					level.playSound(null, BlockPos.containing(k.pos), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 0.8F, 0.6F);
					if (p != null) {
						p.sendOverlayMessage(Component.literal("Big air!").withStyle(ChatFormatting.AQUA));
					}
				}
				if (y <= ground + 0.01) {
					k.vy = 0;
				}
			}
			k.pos = new Vec3(next.x, y, next.z);
			if (k.bonk > 0) {
				k.bonk--;
			}
			if (k.drifting && raceTick % 2 == 0) {
				Vec3 sd = new Vec3(-ahead.z, 0, ahead.x);
				for (int s : new int[] {-1, 1}) {
					level.sendParticles(ParticleTypes.CLOUD, k.pos.x - ahead.x * 0.7 + sd.x * 0.5 * s, k.pos.y + 0.1, k.pos.z - ahead.z * 0.7 + sd.z * 0.5 * s, 1,
							0.05, 0.02, 0.05, 0.01);
				}
				if (raceTick % 8 == 0) {
					level.playSound(null, BlockPos.containing(k.pos), SoundEvents.SAND_STEP, SoundSource.PLAYERS, 0.7F, 1.6F);
				}
			}
			// The engine: a buzz that rises with speed (just for the driver).
			if (p != null && raceTick % 4 == 0 && k.vel.length() > 0.05) {
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_DIDGERIDOO, SoundSource.PLAYERS,
						k.pos.x, k.pos.y, k.pos.z, 0.18F, (float) (0.5 + k.vel.length() * 1.6), 0));
			}
		}
		// Karts bump each other.
		for (int i = 0; i < KARTS.size(); i++) {
			for (int j = i + 1; j < KARTS.size(); j++) {
				Kart a = KARTS.get(i);
				Kart b = KARTS.get(j);
				if (Math.abs(a.pos.y - b.pos.y) > 1.5) {
					continue;
				}
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

	/** The top of the road under {@code at}: the highest solid surface no more than a step above {@code y}. */
	private static double groundAt(ServerLevel level, Vec3 at, double y) {
		int x = (int) Math.floor(at.x);
		int z = (int) Math.floor(at.z);
		for (int by = (int) Math.floor(y + 0.6); by >= FLOOR - 3; by--) {
			BlockPos b = new BlockPos(x, by, z);
			VoxelShape shape = level.getBlockState(b).getCollisionShape(level, b);
			if (!shape.isEmpty()) {
				double top = by + shape.max(net.minecraft.core.Direction.Axis.Y);
				if (top <= y + 0.6) {
					return top;
				}
			}
		}
		return FLOOR - 3;
	}

	private static boolean wall(ServerLevel level, Vec3 at) {
		for (double[] o : new double[][] {{0.6, 0}, {-0.6, 0}, {0, 0.6}, {0, -0.6}}) {
			BlockPos b = BlockPos.containing(at.x + o[0], at.y + 0.75, at.z + o[1]);
			VoxelShape shape = level.getBlockState(b).getCollisionShape(level, b);
			if (!shape.isEmpty() && b.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) > at.y + 0.6) {
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
			for (int o = -24; o <= 24; o++) {
				int i = ((k.index + o) % n + n) % n;
				double d = line.get(i).distanceToSqr(k.pos.x, k.pos.y, k.pos.z);
				if (d < bd) {
					bd = d;
					best = i;
				}
			}
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
		HubPark.discard(level, k.seat);
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
		Entity t = k.nameTag == null ? null : level.getEntity(k.nameTag);
		if (t != null) {
			t.teleportTo(k.pos.x, k.pos.y + 1.7, k.pos.z);
		}
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
