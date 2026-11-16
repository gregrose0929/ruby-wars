package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Matrix3f;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Ruby Rocket: a launched coaster. It rolls out of the station, waits... 3, 2, 1... and BLASTS off, straight up the
 * giant top hat, over the top, down, over a row of airtime hills round the edge of the Fun Park, dives under the island,
 * climbs a helix and brakes home. Four one-seat cars; each car rides with its seat, so it never jiggles against you, and
 * there's nothing over your head.
 */
public final class Coaster {
	private Coaster() {
	}

	/** The whole track (x, z, height above the floor), from the station round and back to it. */
	private static final double[][] TRACK = {
		// Station, then the launch track.
		{44, -20, 1}, {49, -20, 1}, {54, -20, 1}, {60, -20, 1}, {66, -20, 1}, {72, -20, 1.2}, {78, -20, 1.8}, {84, -20, 3.5},
		// The top hat: straight up, over, straight down.
		{88, -20, 8}, {90, -20, 15}, {91, -20, 24}, {91.6, -20, 33}, {93.5, -20, 40}, {97.5, -20, 42.5}, {101.5, -20, 40}, {103.4, -20, 33},
		{104, -20, 24}, {105, -20, 15}, {107.5, -20, 8}, {112, -18, 5},
		// Airtime hills round the east edge.
		{118, -14, 7}, {124, -7, 3.5}, {129, 1, 6}, {130, 8, 11}, {129, 14, 6}, {128, 20, 3}, {125, 26, 6}, {122, 32, 10}, {118, 38, 6},
		{114, 44, 4}, {109, 49, 6}, {104, 54, 9}, {98, 58, 6}, {92, 62, 4},
		// The dive under the island.
		{86, 65, 2}, {80, 66, 0}, {74, 63, -3}, {70, 60, -6}, {66, 55, -9}, {64, 50, -11}, {62, 44, -12}, {60, 38, -11}, {58, 32, -9},
		{56, 27, -6.5}, {50, 22, -4}, {42, 16, -2.5},
		// (the helix is added here)
		// Round to the station and brake.
		{25.5, 0, 8}, {27, -6, 7}, {31, -12, 5.5}, {37, -17, 3.5}, {41, -19.5, 2}, {44, -20, 1}};
	/** The helix: centre, radius, turns, and the climb. */
	private static final double HX = 33;
	private static final double HZ = 6;
	private static final double HR = 7.5;
	private static final double HELIX_FROM = -1.5;
	private static final double HELIX_TO = 8.5;
	private static final int HELIX_AFTER = 45;

	/** The old coaster (round the plaza): its rock tunnel is cleared away. */
	private static final double[][] OLD_PART_3 = {{-28, -9, 9}, {-29, 0, 7}, {-27, 8, 5}, {-23, 16, 3.5}, {-17, 24, 3}, {-8, 29.5, 3}, {0, 31, 3}, {8, 30.5, 6}};

	private static final double STEP = 0.25;
	private static final int CARS_N = 4;
	private static final double CAR_GAP = 2.1;
	private static final double CAR = 1.8F;
	/** Where the front car stops in the station. */
	private static final double HOME = 8.5;
	private static final Vec3 EXIT = new Vec3(49.5, FLOOR + 1, -15.5);
	private static final double MAX = 1.6;

	private static List<Vec3> path = List.of();
	private static List<Vec3> ups = List.of();
	private static float[] yaws = new float[0];
	private static double[] speed = new double[0];
	private static double length;
	private static int launchFrom;
	private static int launchTo;

	private enum State {
		IDLE, COUNTDOWN, RUNNING
	}

	private static State state = State.IDLE;
	private static int timer;
	private static int hold;
	private static boolean launched;
	private static double front = HOME;
	private static double travelled;
	private static double v;
	private static final UUID[] CARS = new UUID[CARS_N];
	private static final UUID[] SEATS = new UUID[CARS_N];
	private static final Set<UUID> RIDERS = new HashSet<>();

	static void build(ServerLevel level) {
		state = State.IDLE;
		front = HOME;
		RIDERS.clear();
		clearOldTunnel(level);
		clearFootings(level);
		layout();
		length = (path.size() - 1) * STEP;
		speeds();
		bank();
		station(level);
		dig(level);
		track(level);
		train(level);
		HubHosts.add(level, new Vec3(54.5, FLOOR + 1, -15.5), 180, VillagerProfession.TOOLSMITH, VillagerType.SAVANNA,
				new HubHosts.Host("coaster", "Rita", "Ruby Rocket", 0xFF4050, "minecraft:minecart",
						"The biggest ride in the park! 3, 2, 1... LAUNCH! Straight up the giant top hat, over the airtime hills (you'll "
								+ "float out of your seat!), a dive right under the island and up the helix. Four cars, one seat each.",
						"Ruby Rocket: right-click Rita to ride!", HubHosts.rideButtons("Ride the Ruby Rocket!"), (p, key) -> {
							for (ServerPlayer q : HubHosts.group(p, key)) {
								board(q);
							}
						}));
	}

	/** The old coaster's rock tube, out past the plaza, goes. */
	private static void clearOldTunnel(ServerLevel level) {
		List<Vec3> ctl = new ArrayList<>();
		for (double[] c : OLD_PART_3) {
			ctl.add(new Vec3(c[0] + 0.5, FLOOR + c[2], c[1] + 0.5));
		}
		for (Vec3 p : HubRides.spline(ctl, 1.0)) {
			if (p.x < -20 || p.x > 1 || p.z < 20 || p.y > FLOOR + 4.5) {
				continue;
			}
			for (int dx = -4; dx <= 4; dx++) {
				for (int dy = -4; dy <= 4; dy++) {
					for (int dz = -4; dz <= 4; dz++) {
						BlockPos b = BlockPos.containing(p.x + dx, p.y + dy, p.z + dz);
						if (Math.hypot(b.getX(), b.getZ()) < 24.5) {
							continue;
						}
						BlockState s = level.getBlockState(b);
						if (s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.TUFF) || s.is(Blocks.SHROOMLIGHT)) {
							level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
						}
					}
				}
			}
		}
	}

	/** Earlier footings (little islands hanging in the sky under the track) are tidied away, and put back where needed. */
	private static void clearFootings(ServerLevel level) {
		for (int x = -40; x <= 140; x++) {
			for (int z = -40; z <= 80; z++) {
				BlockPos g = new BlockPos(x, FLOOR - 5, z);
				for (int dy = 0; dy >= -16; dy -= 1) {
					BlockPos at = g.offset(0, dy, 0);
					if (level.getBlockState(at).is(Blocks.GRASS_BLOCK) && level.getBlockState(at.below()).is(Blocks.DIRT) && level.getBlockState(at.below(2)).is(Blocks.STONE)
							&& level.getBlockState(at.below(3)).isAir() && level.getBlockState(at.above()).isAir()) {
						boolean footing = true;
						for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
							BlockPos q = at.offset(d[0], 0, d[1]);
							footing &= level.getBlockState(q).is(Blocks.GRASS_BLOCK) && level.getBlockState(q.below()).isAir();
						}
						if (!footing) {
							continue;
						}
						for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
							level.setBlock(at.offset(d[0], 0, d[1]), Blocks.AIR.defaultBlockState(), 2);
						}
						level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
						level.setBlock(at.below(), Blocks.AIR.defaultBlockState(), 2);
						level.setBlock(at.below(2), Blocks.AIR.defaultBlockState(), 2);
					}
				}
			}
		}
	}

	private static void layout() {
		List<Vec3> ctl = new ArrayList<>();
		for (int i = 0; i < TRACK.length; i++) {
			double[] c = TRACK[i];
			ctl.add(new Vec3(c[0] + 0.5, FLOOR + c[2], c[1] + 0.5));
			if (i == HELIX_AFTER) {
				// One and a quarter turns, climbing (anticlockwise seen from above, entering on the north side heading west).
				double from = Math.PI / 2;
				double to = from + Math.PI * 2.5;
				int n = 15;
				for (int k = 0; k <= n; k++) {
					double t = from + (to - from) * k / n;
					double h = HELIX_FROM + (HELIX_TO - HELIX_FROM) * k / n;
					ctl.add(new Vec3(HX + 0.5 + Math.cos(t) * HR, FLOOR + h, HZ + 0.5 + Math.sin(t) * HR));
				}
			}
		}
		path = HubRides.spline(ctl, STEP);
	}

	/**
	 * Speeds: rolling out of the station, the launch, then the track's ups and downs (a little lost to friction), and the
	 * brakes home.
	 */
	private static void speeds() {
		speed = new double[path.size()];
		launchFrom = -1;
		launchTo = -1;
		for (int i = 0; i < path.size(); i++) {
			Vec3 p = path.get(i);
			if (launchFrom < 0 && p.x >= 57.5) {
				launchFrom = i;
			}
			if (launchTo < 0 && p.x >= 85) {
				launchTo = i;
			}
		}
		double accel = (MAX * MAX - 0.04) / (2 * (launchTo - launchFrom) * STEP);
		double g = 0.03;
		double friction = 0.004;
		double vL = MAX;
		double yL = path.get(launchTo).y;
		for (int i = 0; i < path.size(); i++) {
			double s;
			if (i < launchFrom) {
				s = 0.22;
			} else if (i <= launchTo) {
				s = Math.sqrt(0.04 + 2 * accel * (i - launchFrom) * STEP);
			} else {
				double v2 = vL * vL - 2 * g * (path.get(i).y - yL) - friction * (i - launchTo) * STEP;
				s = Math.max(0.35, Math.min(MAX, Math.sqrt(Math.max(0, v2))));
			}
			double left = (path.size() - 1 - i) * STEP;
			if (left < 22) {
				s = Math.min(s, 0.2 + left / 22 * 0.9);
			}
			speed[i] = s;
		}
	}

	/** Which way is up at each point (the track leans into the bends), and which way the cars face. */
	private static void bank() {
		int n = path.size();
		Vec3[] flat = new Vec3[n];
		double[] lean = new double[n];
		Vec3 last = new Vec3(0, 1, 0);
		for (int i = 0; i < n; i++) {
			Vec3 fwd = dir(i);
			Vec3 up = new Vec3(0, 1, 0).subtract(fwd.scale(fwd.y));
			up = up.lengthSqr() < 1e-3 ? last : up.normalize();
			flat[i] = up;
			last = up;
			// How sharply it's turning (left or right), times how fast: the lean.
			Vec3 a = path.get(Math.max(0, i - 8));
			Vec3 b = path.get(Math.min(n - 1, i + 8));
			Vec3 h1 = new Vec3(path.get(i).x - a.x, 0, path.get(i).z - a.z);
			Vec3 h2 = new Vec3(b.x - path.get(i).x, 0, b.z - path.get(i).z);
			double turn = 0;
			if (h1.length() > 0.5 && h2.length() > 0.5) {
				double cross = h1.x * h2.z - h1.z * h2.x;
				double ang = Math.asin(Math.max(-1, Math.min(1, cross / (h1.length() * h2.length()))));
				turn = ang / (16 * STEP);
			}
			double sp = speed[i];
			lean[i] = Math.max(-1.0, Math.min(1.0, Math.atan(sp * sp * turn / 0.08)));
		}
		// Smooth the lean so it rolls in and out.
		double[] smooth = new double[n];
		for (int i = 0; i < n; i++) {
			double sum = 0;
			int c = 0;
			for (int k = -20; k <= 20; k++) {
				int j = i + k;
				if (j >= 0 && j < n) {
					sum += lean[j];
					c++;
				}
			}
			smooth[i] = sum / c;
		}
		List<Vec3> up = new ArrayList<>();
		yaws = new float[n];
		for (int i = 0; i < n; i++) {
			Vec3 fwd = dir(i);
			// Positive turn is towards +x cross +z... lean the top of the car into the bend.
			up.add(rotate(flat[i], fwd, -smooth[i]));
			Vec3 heading = new Vec3(fwd.x, 0, fwd.z).subtract(new Vec3(flat[i].x, 0, flat[i].z).scale(fwd.y));
			yaws[i] = heading.lengthSqr() < 1e-6 ? (i > 0 ? yaws[i - 1] : 0) : (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
		}
		ups = up;
	}

	private static Vec3 dir(int i) {
		Vec3 a = path.get(Math.max(0, i - 1));
		Vec3 b = path.get(Math.min(path.size() - 1, i + 1));
		Vec3 d = b.subtract(a);
		return d.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : d.normalize();
	}

	private static Vec3 rotate(Vec3 v, Vec3 k, double angle) {
		double c = Math.cos(angle);
		double sn = Math.sin(angle);
		return v.scale(c).add(k.cross(v).scale(sn)).add(k.scale(k.dot(v) * (1 - c)));
	}

	private static int index(double d) {
		double m = Math.max(0, Math.min(length, d));
		return (int) Math.min(path.size() - 1, Math.floor(m / STEP));
	}

	private static Vec3 at(double d) {
		double m = Math.max(0, Math.min(length, d));
		double f = m / STEP;
		int i = (int) Math.floor(f);
		Vec3 a = path.get(Math.min(path.size() - 1, i));
		Vec3 b = path.get(Math.min(path.size() - 1, i + 1));
		return a.lerp(b, f - i);
	}

	private static Vec3 upAt(double d) {
		double f = Math.max(0, Math.min(length, d)) / STEP;
		int i = (int) Math.floor(f);
		Vec3 a = ups.get(Math.min(ups.size() - 1, i));
		Vec3 b = ups.get(Math.min(ups.size() - 1, i + 1));
		return a.lerp(b, f - i).normalize();
	}

	/** Forward, up (square to it) and right at d. */
	private static Vec3[] frame(double d) {
		Vec3 fwd = at(d + 0.3).subtract(at(d - 0.3));
		fwd = fwd.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : fwd.normalize();
		Vec3 up = upAt(d);
		up = up.subtract(fwd.scale(fwd.dot(up)));
		up = up.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : up.normalize();
		return new Vec3[] {fwd, up, up.cross(fwd).normalize()};
	}

	// ----- Building -----

	private static void station(ServerLevel level) {
		HubPark.clear(level, 41, FLOOR + 1, -24, 58, FLOOR + 8, -15);
		HubPark.box(level, 41, FLOOR, -24, 58, FLOOR, -15, Blocks.SMOOTH_STONE.defaultBlockState());
		HubPark.box(level, 41, FLOOR, -21, 58, FLOOR, -19, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
		HubPark.box(level, 41, FLOOR, -18, 58, FLOOR, -18, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		HubPark.box(level, 41, FLOOR, -22, 58, FLOOR, -22, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		// Tall posts and a high striped roof (well above the riders' heads).
		for (int x : new int[] {41, 47, 53, 58}) {
			for (int z : new int[] {-24, -15}) {
				HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 6, z, Blocks.QUARTZ_PILLAR.defaultBlockState());
			}
		}
		for (int x = 40; x <= 59; x++) {
			for (int z = -25; z <= -14; z++) {
				boolean edge = z == -25 || z == -14;
				HubPark.set(level, x, FLOOR + 7, z, edge ? Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState()
						: Blocks.WOOL.pick(x % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
			HubPark.set(level, x, FLOOR + 8, -20, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
		}
		for (int x = 42; x <= 57; x++) {
			if (x < 52 || x > 55) {
				HubPark.set(level, x, FLOOR + 1, -16, Blocks.IRON_BARS.defaultBlockState());
			}
		}
		HubPark.label(level, 49.5, FLOOR + 10, -19.5, "RUBY ROCKET", ChatFormatting.RED, 3.0F);
		HubPark.label(level, 49.5, FLOOR + 3, -15.6, "3... 2... 1... LAUNCH!", ChatFormatting.GOLD, 0.9F);
		// The launch track's lights.
		for (int x = 58; x <= 84; x += 3) {
			HubPark.set(level, x, FLOOR - 1, -23, Blocks.SEA_LANTERN.defaultBlockState());
		}
	}

	/** Where the track runs through the island's rock (the dive), a tunnel is dug out, roomy and lit. */
	private static void dig(ServerLevel level) {
		for (int i = 0; i < path.size(); i += 2) {
			Vec3 p = path.get(i);
			if (p.y > FLOOR - 1) {
				continue;
			}
			Vec3 c = p.add(0, 1.2, 0);
			boolean rock = false;
			for (int dx = -4; dx <= 4; dx++) {
				for (int dy = -4; dy <= 4; dy++) {
					for (int dz = -4; dz <= 4; dz++) {
						BlockPos b = BlockPos.containing(c.x + dx, c.y + dy, c.z + dz);
						if (Vec3.atCenterOf(b).distanceTo(c) <= 3.3 && !level.getBlockState(b).isAir()) {
							level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
							rock = true;
						}
					}
				}
			}
			if (rock && i % 32 == 0) {
				BlockPos top = BlockPos.containing(c.x, c.y + 3.6, c.z);
				if (!level.getBlockState(top.above()).isAir()) {
					level.setBlock(top, Blocks.SHROOMLIGHT.defaultBlockState(), 2);
				}
			}
		}
	}

	private static void track(ServerLevel level) {
		BlockState rail = Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();
		BlockState spine = Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState();
		BlockState tie = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
		BlockState steel = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
		int seg = (int) Math.round(1.5 / STEP);
		for (int i = 0; i + seg < path.size(); i += seg) {
			Vec3 a = path.get(i);
			Vec3 b = path.get(i + seg);
			Vec3[] fa = frame(i * STEP);
			Vec3[] fb = frame((i + seg) * STEP);
			for (int s : new int[] {-1, 1}) {
				NightShow.glow(HubPark.beam(level, a.add(fa[2].scale(0.45 * s)).add(fa[1].scale(-0.06)), b.add(fb[2].scale(0.45 * s)).add(fb[1].scale(-0.06)), 0.15F,
						0.15F, rail));
			}
			NightShow.glow(HubPark.beam(level, a.add(fa[1].scale(-0.4)), b.add(fb[1].scale(-0.4)), 0.34F, 0.34F, spine));
			Vec3 mid = a.lerp(b, 0.5).add(fa[1].scale(-0.18));
			HubPark.beam(level, mid.add(fa[2].scale(-0.5)), mid.add(fa[2].scale(0.5)), 0.08F, 0.14F, tie);
		}
		// Steel supports down to the ground, or to a little rock footing over the sky.
		int every = (int) Math.round(4.0 / STEP);
		for (int i = 0; i < path.size(); i += every) {
			Vec3 p = path.get(i);
			if (p.x < 58 && p.z < -14 && p.z > -26) {
				continue;
			}
			BlockPos col = BlockPos.containing(p.x, p.y, p.z);
			if (p.y < FLOOR - 1) {
				// Under the island: a frame hanging from the rock above (its legs well out to the sides).
				Vec3[] f = frame(i * STEP);
				Vec3 side = new Vec3(f[2].x, 0, f[2].z).normalize().scale(2.2);
				int top = Integer.MIN_VALUE;
				for (int y = (int) Math.floor(p.y + 3); y <= (int) p.y + 16 && top == Integer.MIN_VALUE; y++) {
					for (int sgn : new int[] {-1, 1}) {
						BlockPos q = BlockPos.containing(p.x + side.x * sgn, y, p.z + side.z * sgn);
						if (!level.getBlockState(q).isAir()) {
							top = y;
						}
					}
				}
				if (top != Integer.MIN_VALUE) {
					Vec3 under = p.add(0, -0.6, 0);
					HubPark.beam(level, under.add(side), under.subtract(side), 0.25F, 0.25F, steel);
					for (int sgn : new int[] {-1, 1}) {
						Vec3 leg = under.add(side.scale(sgn));
						HubPark.beam(level, leg, new Vec3(leg.x, top + 0.5, leg.z), 0.25F, 0.25F, steel);
					}
				}
				continue;
			}
			int ground = Integer.MIN_VALUE;
			for (int y = (int) Math.floor(p.y - 0.8); y >= (int) p.y - 14; y--) {
				if (!level.getBlockState(new BlockPos(col.getX(), y, col.getZ())).isAir()) {
					ground = y + 1;
					break;
				}
			}
			boolean sky = ground == Integer.MIN_VALUE;
			double bottom = sky ? Math.min(FLOOR - 4, Math.floor(p.y) - 4) : ground;
			if (p.y - 0.5 - bottom < 0.8) {
				continue;
			}
			HubPark.beam(level, new Vec3(p.x, p.y - 0.5, p.z), new Vec3(p.x, bottom, p.z), 0.36F, 0.36F, steel);
			if (sky) {
				int x = col.getX();
				int z = col.getZ();
				int y = (int) bottom - 1;
				for (int[] d : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					HubPark.ifAir(level, x + d[0], y, z + d[1], Blocks.GRASS_BLOCK.defaultBlockState());
				}
				HubPark.ifAir(level, x, y - 1, z, Blocks.DIRT.defaultBlockState());
				HubPark.ifAir(level, x, y - 2, z, Blocks.STONE.defaultBlockState());
			}
		}
	}

	private static void train(ServerLevel level) {
		String[] colors = {"red", "yellow", "blue", "green"};
		for (int k = 0; k < CARS_N; k++) {
			Vec3 p = at(HOME - k * CAR_GAP);
			Display.BlockDisplay seat = HubPark.seat(level, p.add(0, 0.55, 0));
			SEATS[k] = seat == null ? null : seat.getUUID();
			Display.ItemDisplay car = HubPark.tempModel(level, p, 0, "coaster_solo_" + colors[k], (float) CAR, (float) (CAR / 2));
			CARS[k] = car == null ? null : car.getUUID();
			if (car != null && seat != null) {
				// The car rides along with its seat: your game moves the two of you together, so it never slides into you.
				car.startRiding(seat, true, true);
			}
		}
		place(level);
	}

	// ----- Riding -----

	private static boolean taken(Entity seat) {
		for (Entity e : seat.getPassengers()) {
			if (e instanceof ServerPlayer) {
				return true;
			}
		}
		return false;
	}

	private static void board(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (state == State.RUNNING) {
			p.sendOverlayMessage(Component.literal("The train's out on the track. It'll be back in a few seconds!").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		for (UUID id : SEATS) {
			Entity seat = id == null ? null : level.getEntity(id);
			if (seat != null && !taken(seat)) {
				p.startRiding(seat, true, true);
				HubRides.faceSeat(p, seat);
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

	static boolean busy() {
		return state != State.IDLE;
	}

	private static void tell(ServerLevel level, String text, ChatFormatting color) {
		for (UUID id : RIDERS) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				p.sendOverlayMessage(Component.literal(text).withStyle(color, ChatFormatting.BOLD));
			}
		}
	}

	static void tick(ServerLevel level, int tick) {
		if (path.isEmpty()) {
			return;
		}
		for (var it = RIDERS.iterator(); it.hasNext();) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(it.next());
			if (p == null || p.level() != level) {
				it.remove();
			} else if (!onTrain(p)) {
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
					tell(level, "Leaving in " + timer / 20 + "...", ChatFormatting.GOLD);
					level.playSound(null, BlockPos.containing(at(HOME)), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.8F, 1.6F);
				}
				if (--timer <= 0) {
					state = State.RUNNING;
					travelled = 0;
					launched = false;
					hold = 0;
					v = 0;
					for (UUID id : RIDERS) {
						ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
						if (p != null) {
							Alerts.title(p, "", "Rolling out...", ChatFormatting.RED);
						}
					}
				}
			}
			case RUNNING -> {
				int i = index(front);
				double want = speed[i];
				// At the start of the launch track: stop, count down, then go.
				if (!launched && i >= launchFrom) {
					if (hold == 0) {
						tell(level, "3...", ChatFormatting.GOLD);
						level.playSound(null, BlockPos.containing(at(front)), SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.2F, 0.6F);
					}
					hold++;
					if (hold == 20) {
						tell(level, "2...", ChatFormatting.GOLD);
					}
					if (hold == 40) {
						tell(level, "1...", ChatFormatting.GOLD);
					}
					if (hold < 60) {
						if (hold % 20 == 0) {
							level.playSound(null, BlockPos.containing(at(front)), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 1.5F, 0.8F + hold / 60F);
						}
						v = 0;
						place(level);
						return;
					}
					launched = true;
					tell(level, "LAUNCH!", ChatFormatting.RED);
					Vec3 here = at(front);
					level.playSound(null, BlockPos.containing(here), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 3.0F, 0.5F);
					level.playSound(null, BlockPos.containing(here), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6F, 1.6F);
					level.sendParticles(ParticleTypes.CLOUD, here.x, here.y, here.z, 30, 1.0, 0.3, 1.0, 0.15);
				}
				// Ease towards the speed for this bit of track (a smooth push, not a jolt).
				v += Math.max(-0.08, Math.min(0.09, want - v));
				front += v;
				travelled += v;
				Vec3 here = at(front);
				if (v > 1.0 && tick % 10 == 0) {
					level.playSound(null, BlockPos.containing(here), SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 0.9F, 1.5F);
				}
				if (v > 1.0 && tick % 5 == 0) {
					for (UUID id : RIDERS) {
						ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
						if (p != null) {
							// Speed widens your view: it feels as fast as it is.
							p.addEffect(new MobEffectInstance(MobEffects.SPEED, 12, v > 1.4 ? 5 : 3, false, false));
						}
					}
				}
				if (i > launchTo && i < launchTo + 4 * (int) (1 / STEP)) {
					if (tick % 4 == 0) {
						level.playSound(null, BlockPos.containing(here), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.6F, 1.4F);
					}
				}
				if (front >= length - 0.01 || travelled > length + 20) {
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

	private static boolean onTrain(ServerPlayer p) {
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
		p.removeEffect(MobEffects.SPEED);
		p.teleportTo(EXIT.x, EXIT.y, EXIT.z);
		p.setYRot(0);
		p.resetFallDistance();
		HubPark.landed(p);
		p.sendOverlayMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	/** Put the seats where the train is, and turn each car to the track (gently: it never tips far round its rider). */
	private static void place(ServerLevel level) {
		for (int k = 0; k < CARS_N; k++) {
			double d = front - k * CAR_GAP;
			if (d < 0) {
				d += length;
			}
			if (state != State.RUNNING) {
				d = HOME - k * CAR_GAP;
			}
			Vec3 p = at(d);
			Vec3[] f = frame(d);
			Entity seat = SEATS[k] == null ? null : level.getEntity(SEATS[k]);
			if (seat == null) {
				continue;
			}
			Vec3 sp = p.add(f[1].scale(0.55));
			seat.teleportTo(sp.x, sp.y, sp.z);
			float yaw = yaws[index(d)];
			seat.setYRot(yaw);
			for (Entity rider : seat.getPassengers()) {
				rider.resetFallDistance();
			}
			Entity car = CARS[k] == null ? null : level.getEntity(CARS[k]);
			if (car == null) {
				continue;
			}
			if (car.getVehicle() != seat) {
				car.startRiding(seat, true, true);
			}
			if (car instanceof Display disp) {
				// The car's own lean: the track's pitch and roll, but only a little of each.
				double pitch = Math.asin(Math.max(-1, Math.min(1, f[0].y)));
				pitch = Math.max(-0.35, Math.min(0.35, pitch));
				double roll = Math.atan2(f[2].y, f[1].y);
				roll = Math.max(-0.26, Math.min(0.26, roll));
				Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) -pitch).rotateZ((float) roll);
				Vector3f carUp = q.transform(new Vector3f(0, 1, 0));
				// From the seat to the car's middle.
				Vector3f off = new Vector3f(carUp).mul((float) (CAR / 2)).sub(new Vector3f((float) f[1].x, (float) f[1].y, (float) f[1].z).mul(0.55F));
				((DisplayAccessor) disp).rubymod$setTransformationInterpolationDelay(0);
				((DisplayAccessor) disp).rubymod$setTransformationInterpolationDuration(2);
				((DisplayAccessor) disp).rubymod$setTransformation(new Transformation(off, new Quaternionf(q).rotateY((float) Math.PI),
						new Vector3f((float) CAR, (float) CAR, (float) CAR), new Quaternionf()));
			}
		}
	}

	/** Nothing solid near a rider's head anywhere round the track. */
	static void clearHeadroom(ServerLevel level) {
		int n = 0;
		for (int i = 0; i < path.size(); i += 2) {
			Vec3[] f = frame(i * STEP);
			n += HubRides.clearAround(level, path.get(i).add(f[1].scale(0.55)), 1.0, 0.9, 3.0);
		}
		// Bushes and hedges the cars would pass through (at the launch, mostly): gone.
		for (int i = 0; i < path.size(); i += 2) {
			Vec3 p = path.get(i);
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int dy = -2; dy <= 3; dy++) {
						net.minecraft.core.BlockPos b = net.minecraft.core.BlockPos.containing(p.x + dx, p.y + dy, p.z + dz);
						if (Math.hypot(b.getX() + 0.5 - p.x, b.getZ() + 0.5 - p.z) > 2.2) {
							continue;
						}
						var s = level.getBlockState(b);
						if (s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || s.is(net.minecraft.world.level.block.Blocks.AZALEA)
								|| s.is(net.minecraft.world.level.block.Blocks.FLOWERING_AZALEA) || s.is(net.minecraft.world.level.block.Blocks.SHROOMLIGHT)) {
							level.setBlock(b, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
							n++;
						}
					}
				}
			}
		}
		if (n > 0) {
			dev.rubymod.RubyMod.LOGGER.info("Headroom: cleared {} blocks over the coaster", n);
		}
	}

	static List<Vec3> pathPoints() {
		return path;
	}

	static void reset() {
		state = State.IDLE;
		RIDERS.clear();
		path = List.of();
	}

	/** For the autotest: where the front car is, and how fast. */
	public static String debug() {
		return state + " front " + String.format("%.1f/%.1f v %.2f", front, length, v);
	}
}
