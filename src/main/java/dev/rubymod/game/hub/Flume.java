package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Flume Falls, on Splash Island: a log flume in a channel of real water. Up the lift, round the top of Thunder Mountain
 * and through the crystal cave inside it, down the first drop, a float along the hillside, then the BIG drop into the
 * splash pool under the bridge (where anyone watching gets soaked too).
 */
final class Flume {
	private Flume() {
	}

	/** The loop's middle and size (an oval). */
	static final double CX = 74;
	static final double CZ = -100;
	private static final double A = 22;
	private static final double B = 17;
	/** Thunder Mountain. */
	private static final double MX = 90.3;
	private static final double MZ = -115;
	private static final Vec3 EXIT = new Vec3(48.5, FLOOR + 1, -103.5);
	private static final Vec3 SPLASH = new Vec3(74.5, FLOOR, -82.5);
	private static final AABB BRIDGE = new AABB(71, FLOOR + 4, -90, 78, FLOOR + 8, -76);

	private static TrackRide ride;

	/** The track: degrees round the oval, and height. */
	private static final double[][] PLAN = {{180, 0}, {186, 0}, {192, 0.4}, {205, 3}, {225, 8.5}, {245, 14}, {262, 18.5}, {272, 20}, {282, 20}, {300, 20},
		{320, 20}, {338, 20}, {346, 19.6}, {352, 15.5}, {357, 11.5}, {362, 10}, {372, 10}, {390, 10}, {410, 10}, {426, 10}, {432, 9.5}, {438, 5.5},
		{443, 1.5}, {448, 0}, {460, 0}, {480, 0}, {500, 0}, {520, 0}, {534, 0}, {540, 0}};

	private static Vec3 oval(double deg, double h) {
		double t = Math.toRadians(deg);
		return new Vec3(CX + 0.5 + Math.cos(t) * A, FLOOR + h, CZ + 0.5 + Math.sin(t) * B);
	}

	static void build(ServerLevel level) {
		List<Vec3> ctl = new ArrayList<>();
		for (double[] c : PLAN) {
			ctl.add(oval(c[0], c[1]));
		}
		List<Vec3> path = HubRides.spline(ctl, TrackRide.STEP);
		// Speeds: slow up the lift, a gentle float, gravity down the drops (easing off after each), a slow float home.
		double[] speed = new double[path.size()];
		double v = 0.2;
		double top = path.get(0).y;
		for (int i = 0; i < path.size(); i++) {
			Vec3 p = path.get(i);
			Vec3 q = path.get(Math.min(path.size() - 1, i + 1));
			double dy = (q.y - p.y) / TrackRide.STEP;
			if (dy > 0.05) {
				v = 0.16;
				top = q.y;
			} else if (dy < -0.05) {
				v = Math.max(v, Math.min(1.05, Math.sqrt(0.04 + 2 * 0.045 * Math.max(0, top - p.y))));
			} else {
				v = Math.max(0.2, v - 0.012);
				top = p.y;
			}
			double left = (path.size() - 1 - i) * TrackRide.STEP;
			speed[i] = left < 4 ? Math.min(v, 0.12 + left * 0.03) : v;
		}
		ride = new TrackRide("flume", path, speed, "log_boat", 1.8F, EXIT, "Flo");
		// Sit up high in the log (it rides low in the water), so you see over its sides.
		ride.seatHeight = 0.45;
		ride.modelUp = 0.6;
		ride.maxPitch = 20;
		ride.waitTicks = 60;
		mountain(level);
		channel(level, path);
		cave(level, path);
		station(level);
		bridge(level);
		int drop1 = nearestDeg(path, 352);
		int drop2 = nearestDeg(path, 438);
		int splash1 = nearestDeg(path, 364);
		int splash2 = nearestDeg(path, 449);
		ride.events.put(nearestDeg(path, 200), (lvl, car) -> ride.tell(lvl, car, "Clank... clank... up the lift we go..."));
		ride.events.put(nearestDeg(path, 296), (lvl, car) -> ride.tell(lvl, car, "Into Thunder Mountain... ooh, crystals!"));
		ride.events.put(nearestDeg(path, 318), (lvl, car) -> ride.tell(lvl, car, "Pirate treasure! Don't touch..."));
		ride.events.put(drop1 - 8, (lvl, car) -> ride.tell(lvl, car, "Hold on... WHEEEE!"));
		ride.events.put(splash1, (lvl, car) -> splash(lvl, car, car.pos, false));
		ride.events.put(drop2 - 10, (lvl, car) -> ride.tell(lvl, car, "Uh oh... this one's BIG..."));
		ride.events.put(splash2, (lvl, car) -> splash(lvl, car, SPLASH, true));
		HubHosts.add(level, new Vec3(48.5, FLOOR + 1, -97.5), 270, VillagerProfession.FARMER, VillagerType.JUNGLE,
				new HubHosts.Host("flume", "Flo", "Flume Falls", 0x40E0A0, "minecraft:oak_log",
						"Hop in a log! Up the lift, round Thunder Mountain and through the crystal cave inside, down one drop... then the "
								+ "BIG one, into the splash pool under the bridge. Two in a log. You WILL get wet.",
						"Flume Falls: right-click Flo for a ride!", HubHosts.rideButtons("Ride Flume Falls!"), (p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	private static void splash(ServerLevel lvl, TrackRide.Car car, Vec3 at, boolean big) {
		lvl.playSound(null, BlockPos.containing(at), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, big ? 2.5F : 1.5F, 0.8F);
		lvl.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 2.0F, 0.7F);
		lvl.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.8, at.z, big ? 300 : 120, big ? 2.5 : 1.2, big ? 2.0 : 0.8, big ? 2.5 : 1.2, 0.6);
		lvl.sendParticles(ParticleTypes.FALLING_WATER, at.x, at.y + 4, at.z, big ? 160 : 40, 2.5, 1.5, 2.5, 0.1);
		for (ServerPlayer p : ride.riders(lvl, car)) {
			if (big) {
				Alerts.title(p, "SPLASH!", "You're soaked!", ChatFormatting.AQUA);
			} else {
				p.sendOverlayMessage(Component.literal("Splash!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			}
		}
		if (big) {
			for (ServerPlayer p : lvl.players()) {
				if (!ride.riding(p) && (BRIDGE.contains(p.position()) || p.position().distanceTo(SPLASH) < 7)) {
					p.sendOverlayMessage(Component.literal("SPLOOSH! You got splashed!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
					lvl.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 1.5, p.getZ(), 40, 0.4, 0.4, 0.4, 0.3);
				}
			}
		}
	}

	private static int nearestDeg(List<Vec3> path, double deg) {
		Vec3 at = oval(deg, 0);
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = Math.hypot(path.get(i).x - at.x, path.get(i).z - at.z);
			// The start and end of the loop are the same spot: prefer the right one by angle.
			if (deg < 200 && i > path.size() / 2 || deg > 500 && i < path.size() / 2) {
				continue;
			}
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	/** How high Thunder Mountain is at x, z (a flat top, steep sides). */
	private static double height(double x, double z) {
		double d = Math.hypot(x - MX, z - MZ) + Math.sin(x * 0.6 + z * 0.4) * 0.9;
		if (d <= 8) {
			return 26;
		}
		return Math.max(0, 26 - (d - 8) * 2.8);
	}

	private static void mountain(ServerLevel level) {
		BlockState[] rock = {Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.STONE.defaultBlockState(),
			Blocks.GRANITE.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
		for (int x = (int) MX - 19; x <= (int) MX + 19; x++) {
			for (int z = (int) MZ - 19; z <= (int) MZ + 19; z++) {
				double h = height(x + 0.5, z + 0.5);
				if (h < 0.5 || !level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.GRASS_BLOCK) && !level.getBlockState(new BlockPos(x, FLOOR, z)).isAir()
						&& !level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.DIRT)) {
					continue;
				}
				int top = FLOOR + (int) Math.round(h);
				boolean plateau = h >= 25.5 || h < 2;
				for (int y = FLOOR; y <= top; y++) {
					BlockState s = (x * 7 + y * 3 + z * 11) % 41 == 0 ? ModBlocks.RUBY_BLOCK.defaultBlockState() : rock[Math.floorMod(x * 3 + y * 5 + z * 7, rock.length)];
					if (plateau && y >= top - 2) {
						s = y == top ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
					} else if (y == top && Math.floorMod(x * 5 + z * 3, 4) == 0) {
						// Steep sides are rock, with a bit of moss and grass clinging on.
						s = Math.floorMod(x + z, 2) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
					}
					HubPark.set(level, x, y, z, s);
				}
			}
		}
		// A few pines on top.
		for (int[] t : new int[][] {{(int) MX - 3, (int) MZ - 4}, {(int) MX + 4, (int) MZ + 2}, {(int) MX - 1, (int) MZ + 5}, {(int) MX + 5, (int) MZ - 5}}) {
			int y = FLOOR + 27;
			for (int k = 0; k < 5; k++) {
				HubPark.set(level, t[0], y + k, t[1], Blocks.SPRUCE_LOG.defaultBlockState());
			}
			for (int k = 1; k <= 3; k++) {
				int r = 3 - k + 1;
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.abs(dx) + Math.abs(dz) <= r + 1 && (dx != 0 || dz != 0)) {
							HubPark.ifAir(level, t[0] + dx, y + k + 1, t[1] + dz, Blocks.SPRUCE_LEAVES.defaultBlockState()
									.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
						}
					}
				}
			}
			HubPark.ifAir(level, t[0], y + 5, t[1], Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
		}
		HubPark.label(level, MX, FLOOR + 36, MZ, "FLUME FALLS", ChatFormatting.AQUA, 4.0F);
		HubPark.label(level, MX, FLOOR + 33.5, MZ, "Thunder Mountain", ChatFormatting.WHITE, 1.6F);
	}

	/**
	 * The channel: real water along the flat parts (in a wooden trough on posts, or dug into the ground), a chain floor up
	 * the lift, smooth chutes down the drops (the water from above runs down them). Every cell of water is walled in.
	 */
	private static void channel(ServerLevel level, List<Vec3> path) {
		Map<Long, Integer> floor = new HashMap<>();
		Map<Long, Boolean> flat = new HashMap<>();
		Map<Long, Boolean> lift = new HashMap<>();
		Map<Long, Integer> splashPool = new HashMap<>();
		int splash2 = nearestDeg(path, 449);
		for (int i = 0; i < path.size(); i++) {
			Vec3 p = path.get(i);
			Vec3 q = path.get(Math.min(path.size() - 1, i + 1));
			Vec3 a = path.get(Math.max(0, i - 1));
			double dy = (q.y - a.y) / (2 * TrackRide.STEP);
			int fy = (int) Math.floor(p.y + 0.01) - 1;
			double half = Math.abs(i - splash2) < 50 ? 2.6 : 1.1;
			for (double dx = -half; dx <= half; dx += 0.5) {
				for (double dz = -half; dz <= half; dz += 0.5) {
					if (dx * dx + dz * dz > half * half + 0.01) {
						continue;
					}
					int x = (int) Math.floor(p.x + dx);
					int z = (int) Math.floor(p.z + dz);
					long k = BlockPos.asLong(x, 0, z);
					floor.merge(k, fy, Math::min);
					boolean f = Math.abs(dy) < 0.04;
					flat.merge(k, f, Boolean::logicalAnd);
					lift.merge(k, dy > 0.04, Boolean::logicalOr);
					if (half > 2) {
						splashPool.put(k, fy);
					}
				}
			}
		}
		BlockState wood = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState trim = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
		BlockState chain = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		BlockState chute = Blocks.PRISMARINE_BRICKS.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		// Clear the way, then the floor and the water.
		for (var e : floor.entrySet()) {
			BlockPos c = BlockPos.of(e.getKey());
			int fy = e.getValue();
			for (int y = fy + 1; y <= fy + 4; y++) {
				HubPark.set(level, c.getX(), y, c.getZ(), air);
			}
			boolean f = flat.get(e.getKey());
			HubPark.set(level, c.getX(), fy - 1, c.getZ(), f ? (fy < FLOOR ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : wood) : chute);
			HubPark.set(level, c.getX(), fy, c.getZ(), f ? water : lift.get(e.getKey()) ? chain : chute);
		}
		// Walls round every cell (as high as the water beside them could reach), and posts under the high parts.
		Set<Long> walled = new HashSet<>();
		for (var e : floor.entrySet()) {
			BlockPos c = BlockPos.of(e.getKey());
			int fy = e.getValue();
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
				long n = BlockPos.asLong(c.getX() + d[0], 0, c.getZ() + d[1]);
				if (floor.containsKey(n)) {
					continue;
				}
				int bottom = fy - 1;
				// Beside still water the wall only needs to reach the water; beside a slope, a block higher (the water runs down it).
				int top = flat.get(e.getKey()) ? fy : fy + 1;
				for (int[] d2 : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					Integer f2 = floor.get(BlockPos.asLong(c.getX() + d[0] + d2[0], 0, c.getZ() + d[1] + d2[1]));
					if (f2 != null) {
						top = Math.max(top, flat.get(BlockPos.asLong(c.getX() + d[0] + d2[0], 0, c.getZ() + d[1] + d2[1])) ? f2 : f2 + 1);
						bottom = Math.min(bottom, f2 - 1);
					}
				}
				int x = c.getX() + d[0];
				int z = c.getZ() + d[1];
				for (int y = bottom; y <= top; y++) {
					BlockState cur = level.getBlockState(new BlockPos(x, y, z));
					if (cur.isAir() || cur.is(Blocks.GRASS_BLOCK) || cur.is(Blocks.DIRT) || cur.is(Blocks.WATER) || y < FLOOR + 1) {
						HubPark.set(level, x, y, z, y == top && y > FLOOR ? trim : y <= FLOOR && fy < FLOOR + 1 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : wood);
					}
				}
				walled.add(n);
			}
		}
		// Posts down to the ground under the raised parts.
		int n = 0;
		for (var e : floor.entrySet()) {
			BlockPos c = BlockPos.of(e.getKey());
			int fy = e.getValue();
			if (fy - 2 <= FLOOR || (c.getX() + c.getZ()) % 4 != 0 || n++ % 2 == 1) {
				continue;
			}
			for (int y = fy - 2; y > FLOOR; y--) {
				BlockPos b = new BlockPos(c.getX(), y, c.getZ());
				if (!level.getBlockState(b).isAir()) {
					break;
				}
				HubPark.set(level, c.getX(), y, c.getZ(), Blocks.SPRUCE_FENCE.defaultBlockState());
			}
		}
	}

	/** The crystal cave inside the mountain: a roomy tunnel, lit, with grottoes to either side. */
	private static void cave(ServerLevel level, List<Vec3> path) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int i = 0; i < path.size(); i += 2) {
			Vec3 p = path.get(i);
			if (height(p.x, p.z) < p.y - FLOOR + 3) {
				continue;
			}
			int fy = (int) Math.floor(p.y + 0.01) - 1;
			// Room above the channel: 2.5 to each side, 4 up.
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (dx * dx + dz * dz > 7) {
						continue;
					}
					int x = (int) Math.floor(p.x) + dx;
					int z = (int) Math.floor(p.z) + dz;
					for (int y = fy + 2; y <= fy + 4; y++) {
						BlockState cur = level.getBlockState(new BlockPos(x, y, z));
						if (!cur.is(Blocks.WATER) && !cur.is(Blocks.SPRUCE_PLANKS) && !cur.is(Blocks.STRIPPED_SPRUCE_LOG)) {
							HubPark.set(level, x, y, z, air);
						}
					}
				}
			}
			if (i % 24 == 0) {
				HubPark.set(level, (int) Math.floor(p.x), fy + 5, (int) Math.floor(p.z), Blocks.SEA_LANTERN.defaultBlockState());
			}
		}
		// Grottoes: crystals, pirate treasure, glowing mushrooms.
		grotto(level, path, 296, 1, new BlockState[] {Blocks.AMETHYST_BLOCK.defaultBlockState(), Blocks.BUDDING_AMETHYST.defaultBlockState(),
			Blocks.STAINED_GLASS.pick(DyeColor.PURPLE).defaultBlockState()}, Blocks.AMETHYST_CLUSTER.defaultBlockState());
		grotto(level, path, 308, -1, new BlockState[] {Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState(), Blocks.CALCITE.defaultBlockState(),
			Blocks.AMETHYST_BLOCK.defaultBlockState()}, Blocks.LARGE_AMETHYST_BUD.defaultBlockState());
		grotto(level, path, 318, 1, new BlockState[] {Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.RAW_GOLD_BLOCK.defaultBlockState(),
			Blocks.STONE.defaultBlockState()}, Blocks.CHEST.defaultBlockState());
		grotto(level, path, 328, -1, new BlockState[] {Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.MYCELIUM.defaultBlockState(),
			Blocks.STONE.defaultBlockState()}, Blocks.RED_MUSHROOM.defaultBlockState());
	}

	private static void grotto(ServerLevel level, List<Vec3> path, double deg, int side, BlockState[] walls, BlockState thing) {
		int i = nearestDeg(path, deg);
		Vec3 p = path.get(i);
		Vec3 q = path.get(Math.min(path.size() - 1, i + 2));
		Vec3 dir = new Vec3(q.x - p.x, 0, q.z - p.z).normalize();
		Vec3 out = new Vec3(-dir.z, 0, dir.x).scale(side);
		Vec3 c = p.add(out.scale(4.5));
		int fy = (int) Math.floor(p.y + 0.01) - 1;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = 0; dy <= 4; dy++) {
					int x = (int) Math.floor(c.x) + dx;
					int z = (int) Math.floor(c.z) + dz;
					int y = fy + 1 + dy;
					double r = Math.sqrt(dx * dx + dz * dz + (dy - 2) * (dy - 2) * 0.8);
					BlockState cur = level.getBlockState(new BlockPos(x, y, z));
					if (cur.is(Blocks.WATER) || cur.is(Blocks.SPRUCE_PLANKS) || cur.is(Blocks.STRIPPED_SPRUCE_LOG)) {
						continue;
					}
					if (r < 2.4) {
						HubPark.set(level, x, y, z, dy == 0 ? walls[Math.floorMod(x + z, walls.length)] : Blocks.AIR.defaultBlockState());
					} else if (r < 3.4 && !cur.isAir()) {
						HubPark.set(level, x, y, z, walls[Math.floorMod(x * 3 + y + z * 5, walls.length)]);
					}
				}
			}
		}
		for (int k = 0; k < 4; k++) {
			int x = (int) Math.floor(c.x) + (k % 2 == 0 ? -1 : 1);
			int z = (int) Math.floor(c.z) + (k < 2 ? -1 : 1);
			HubPark.ifAir(level, x, fy + 2, z, thing);
		}
		HubPark.set(level, (int) Math.floor(c.x), fy + 5, (int) Math.floor(c.z), Blocks.GLOWSTONE.defaultBlockState());
	}

	private static void station(ServerLevel level) {
		HubPark.box(level, 46, FLOOR, -105, 50, FLOOR, -95, Blocks.SPRUCE_PLANKS.defaultBlockState());
		for (int[] post : new int[][] {{46, -105}, {46, -95}, {50, -105}, {50, -95}}) {
			HubPark.box(level, post[0], FLOOR + 1, post[1], post[0], FLOOR + 5, post[1], Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
		}
		for (int x = 45; x <= 53; x++) {
			for (int z = -106; z <= -94; z++) {
				HubPark.set(level, x, FLOOR + 6, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.GREEN : DyeColor.WHITE).defaultBlockState());
			}
		}
		HubPark.label(level, 49.5, FLOOR + 8, -99.5, "LOG FLUME", ChatFormatting.GREEN, 2.0F);
		HubPark.label(level, 47.5, FLOOR + 2.6, -103.5, "EXIT", ChatFormatting.GREEN, 0.8F);
	}

	/** The splash bridge over the pool at the bottom of the big drop. */
	private static void bridge(ServerLevel level) {
		int x0 = 73;
		int x1 = 76;
		int y = FLOOR + 5;
		for (int z = -91; z <= -75; z++) {
			int h = Math.min(Math.min(z + 91, -75 - z), 5);
			int yy = FLOOR + h;
			for (int x = x0; x <= x1; x++) {
				if (h <= 0) {
					continue;
				}
				HubPark.set(level, x, yy, z, h == 5 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.SPRUCE_STAIRS.defaultBlockState()
						.setValue(net.minecraft.world.level.block.StairBlock.FACING, z < -83 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH));
				if (h == 5 && (x == x0 || x == x1)) {
					HubPark.set(level, x, yy + 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
				}
			}
			if (h > 1) {
				for (int x : new int[] {x0, x1}) {
					for (int yy2 = FLOOR + 1; yy2 < yy; yy2++) {
						if ((z + 91) % 4 == 0) {
							HubPark.ifAir(level, x, yy2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
						}
					}
				}
			}
		}
		HubPark.label(level, (x0 + x1) / 2.0 + 0.5, y + 3.4, -82.5, "SPLASH BRIDGE\nyou WILL get wet!", ChatFormatting.AQUA, 0.9F);
	}

	static void tick(ServerLevel level, int tick) {
		if (ride == null) {
			return;
		}
		ride.tick(level);
		if (tick % 3 == 0) {
			for (TrackRide.Car c : ride.cars) {
				if (c.wait <= 0) {
					level.sendParticles(ParticleTypes.SPLASH, c.pos.x, c.pos.y + 0.3, c.pos.z, 3, 0.4, 0.1, 0.4, 0.05);
				}
			}
		}
	}

	static boolean busy() {
		return ride != null && !ride.cars.isEmpty();
	}

	static void reset() {
		if (ride != null) {
			ride.reset();
		}
	}
}
