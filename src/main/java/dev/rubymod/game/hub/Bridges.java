package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Getting about on foot: wobbly-looking rope bridges wherever two islands are close, and a cable car from the plaza up to
 * Home Street (and back down).
 */
final class Bridges {
	private Bridges() {
	}

	private static final int R = 175;
	private static TrackRide up;
	private static TrackRide down;

	static void reset() {
		if (up != null) {
			up.reset();
		}
		if (down != null) {
			down.reset();
		}
	}

	static void build(ServerLevel level) {
		ropeBridges(level);
		cableCar(level);
	}

	// ----- Rope bridges -----

	private static java.nio.file.Path file(ServerLevel level) {
		return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("rubywars").resolve("bridges.txt");
	}

	private static void ropeBridges(ServerLevel level) {
		// Bridges made before are rebuilt (their ends now join the islands, so they wouldn't be found again).
		try {
			java.nio.file.Path f = file(level);
			if (java.nio.file.Files.exists(f)) {
				int n = 0;
				for (String line : java.nio.file.Files.readAllLines(f)) {
					String[] q = line.trim().split(" ");
					if (q.length == 4) {
						bridge(level, Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]), Integer.parseInt(q[3]));
						n++;
					}
				}
				dev.rubymod.RubyMod.LOGGER.info("Bridges: {} rope bridges (as before)", n);
				return;
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the bridges", e);
		}
		StringBuilder saved = new StringBuilder();
		int n = 2 * R + 1;
		int[] comp = new int[n * n];
		java.util.Arrays.fill(comp, -1);
		List<List<int[]>> rims = new ArrayList<>();
		int count = 0;
		for (int i = 0; i < n * n; i++) {
			if (comp[i] != -1 || !land(level, i % n - R, i / n - R)) {
				continue;
			}
			// Flood-fill one island.
			List<int[]> cells = new ArrayList<>();
			java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
			q.add(i);
			comp[i] = count;
			while (!q.isEmpty()) {
				int c = q.poll();
				int x = c % n - R;
				int z = c / n - R;
				cells.add(new int[] {x, z});
				int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
				for (int[] d : nb) {
					int nx = x + d[0];
					int nz = z + d[1];
					if (nx < -R || nx > R || nz < -R || nz > R) {
						continue;
					}
					int k = (nz + R) * n + nx + R;
					if (comp[k] == -1 && land(level, nx, nz)) {
						comp[k] = count;
						q.add(k);
					}
				}
			}
			List<int[]> rim = new ArrayList<>();
			if (cells.size() >= 150) {
				for (int[] c : cells) {
					if (!land(level, c[0] + 1, c[1]) || !land(level, c[0] - 1, c[1]) || !land(level, c[0], c[1] + 1) || !land(level, c[0], c[1] - 1)) {
						rim.add(c);
					}
				}
			}
			rims.add(rim);
			count++;
		}
		// The closest edges of every pair of islands.
		List<int[]> pairs = new ArrayList<>();
		for (int a = 0; a < rims.size(); a++) {
			for (int b = a + 1; b < rims.size(); b++) {
				List<int[]> cands = new ArrayList<>();
				for (int[] p : rims.get(a)) {
					for (int[] q : rims.get(b)) {
						double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
						if (d < 20 && d > 3) {
							cands.add(new int[] {p[0], p[1], q[0], q[1], (int) (d * 100), a * 1000 + b});
						}
					}
				}
				cands.sort((p, q) -> Integer.compare(p[4], q[4]));
				pairs.addAll(cands.subList(0, Math.min(60, cands.size())));
			}
		}
		pairs.sort((p, q) -> Integer.compare(p[4], q[4]));
		int made = 0;
		java.util.Set<Integer> joined = new java.util.HashSet<>();
		for (int[] p : pairs) {
			if (made >= 8 || joined.contains(p[5])) {
				continue;
			}
			if (bridge(level, p[0], p[1], p[2], p[3])) {
				joined.add(p[5]);
				made++;
				saved.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append(' ').append(p[3]).append('\n');
			}
		}
		try {
			java.nio.file.Files.createDirectories(file(level).getParent());
			java.nio.file.Files.writeString(file(level), saved.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the bridges", e);
		}
		dev.rubymod.RubyMod.LOGGER.info("Bridges: {} rope bridges ({} islands, {} close pairs)", made, count, pairs.size());
	}

	private static boolean land(ServerLevel level, int x, int z) {
		return !level.getBlockState(new BlockPos(x, FLOOR, z)).isAir();
	}

	/** A rope bridge from one island's edge (x1, z1) to another's (x2, z2), if the way between is open. */
	private static boolean bridge(ServerLevel level, int x1, int z1, int x2, int z2) {
		double dx = x2 - x1;
		double dz = z2 - z1;
		double len = Math.hypot(dx, dz);
		double ux = dx / len;
		double uz = dz / len;
		// Open sky all the way across (and nothing standing at either end where you'd step off).
		for (double t = 1; t < len - 1; t += 0.5) {
			for (double w = -1.2; w <= 1.2; w += 0.6) {
				int x = (int) Math.floor(x1 + 0.5 + ux * t - uz * w);
				int z = (int) Math.floor(z1 + 0.5 + uz * t + ux * w);
				if (land(level, x, z)) {
					continue;
				}
				for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
					BlockState s = level.getBlockState(new BlockPos(x, y, z));
					if (!s.isAir() && !s.canBeReplaced() && !(s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) && !s.is(Blocks.BARRIER)) {
												return false;
					}
				}
			}
		}
		for (int[] e : new int[][] {{x1, z1}, {x2, z2}}) {
			for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
				BlockState s = level.getBlockState(new BlockPos(e[0], y, e[1]));
				if (!s.isAir() && !s.canBeReplaced() && !(s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
						&& !s.is(Blocks.SHROOMLIGHT) && !s.is(Blocks.BARRIER)) {
										return false;
				}
			}
		}
		// The deck: planks three wide, flush with the islands; and invisible walls either side so nobody falls off.
		BlockState deck = Blocks.SPRUCE_PLANKS.defaultBlockState();
		java.util.Set<BlockPos> deckCells = new java.util.HashSet<>();
		for (double t = -0.5; t <= len + 0.5; t += 0.25) {
			for (double w = -1.2; w <= 1.2; w += 0.4) {
				int x = (int) Math.floor(x1 + 0.5 + ux * t - uz * w);
				int z = (int) Math.floor(z1 + 0.5 + uz * t + ux * w);
				BlockPos p = new BlockPos(x, FLOOR, z);
				if (level.getBlockState(p).isAir()) {
					level.setBlock(p, deck, 2);
					deckCells.add(p);
				}
				// Clear the hedge where you step on and off.
				for (int y = 1; y <= 2; y++) {
					BlockPos up = p.above(y);
					BlockState s = level.getBlockState(up);
					if (s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || s.is(Blocks.SHROOMLIGHT) || s.canBeReplaced() && !s.isAir()) {
						level.setBlock(up, Blocks.AIR.defaultBlockState(), 2);
					}
				}
			}
		}
		for (BlockPos p : deckCells) {
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
				BlockPos side = p.offset(d[0], 0, d[1]);
				if (deckCells.contains(side) || !level.getBlockState(side).isAir()) {
					continue;
				}
				for (int y = 1; y <= 2; y++) {
					if (level.getBlockState(side.above(y)).isAir()) {
						level.setBlock(side.above(y), Blocks.BARRIER.defaultBlockState(), 2);
					}
				}
			}
		}
		// Posts at each end with lanterns, and rope rails sagging between them, with hangers down to the deck.
		BlockState rope = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
		for (int side = -1; side <= 1; side += 2) {
			double ox = -uz * 1.7 * side;
			double oz = ux * 1.7 * side;
			Vec3 a = new Vec3(x1 + 0.5 - ux * 0.5 + ox, FLOOR + 1, z1 + 0.5 - uz * 0.5 + oz);
			Vec3 b = new Vec3(x2 + 0.5 + ux * 0.5 + ox, FLOOR + 1, z2 + 0.5 + uz * 0.5 + oz);
			for (Vec3 post : new Vec3[] {a, b}) {
				HubPark.beam(level, post, post.add(0, 1.9, 0), 0.26F, 0.26F, Blocks.SPRUCE_LOG.defaultBlockState());
				HubPark.piece(level, post.add(0, 2.15, 0), Blocks.LANTERN.defaultBlockState(), new org.joml.Vector3f(0.45F, 0.45F, 0.45F), new org.joml.Quaternionf());
			}
			Vec3 last = a.add(0, 1.6, 0);
			int steps = Math.max(4, (int) (len / 1.5));
			for (int i = 1; i <= steps; i++) {
				double t = i / (double) steps;
				Vec3 p = a.lerp(b, t).add(0, 1.6 - Math.sin(t * Math.PI) * 0.5, 0);
				HubPark.beam(level, last, p, 0.06F, 0.06F, rope);
				if (i < steps) {
					HubPark.beam(level, p, new Vec3(p.x - ox * 0.25, FLOOR + 1.0, p.z - oz * 0.25), 0.04F, 0.04F, rope);
				}
				last = p;
			}
		}
		HubPark.label(level, x1 + 0.5 - ux * 1.5, FLOOR + 3.2, z1 + 0.5 - uz * 1.5, "ROPE BRIDGE", ChatFormatting.GOLD, 0.6F);
		dev.rubymod.RubyMod.LOGGER.info("Bridges: rope bridge {} {} -> {} {}", x1, z1, x2, z2);
		return true;
	}

	// ----- The cable car -----

	/** A cable car from the plaza's north-west corner up over the park to Home Street, and one back. */
	private static void cableCar(ServerLevel level) {
		Vec3 a = towerSpot(level, new Vec3(-14.5, FLOOR + 1, -14.5));
		Vec3 b = towerSpot(level, new Vec3(-49.5, FLOOR + 1, -30.5));
		int top = FLOOR + 12;
		for (double t = 0; t <= 1; t += 0.02) {
			Vec3 p = a.lerp(b, t);
			for (int y = FLOOR + 30; y > top - 6; y--) {
				if (!level.getBlockState(BlockPos.containing(p.x, y, p.z)).isAir() && Math.min(p.distanceTo(a), p.distanceTo(b)) > 3) {
					top = Math.max(top, y + 6);
					break;
				}
			}
		}
		top = Math.min(top, FLOOR + 30);
		tower(level, a, top);
		tower(level, b, top);
		// Two cables between the towers, sagging a little.
		Vec3 ca = new Vec3(a.x, top + 0.6, a.z);
		Vec3 cb = new Vec3(b.x, top + 0.6, b.z);
		double len = ca.distanceTo(cb);
		List<Vec3> line = new ArrayList<>();
		int steps = (int) (len / 2);
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			line.add(ca.lerp(cb, t).add(0, -Math.sin(t * Math.PI) * 1.8, 0));
		}
		for (int i = 1; i < line.size(); i++) {
			HubPark.beam(level, line.get(i - 1), line.get(i), 0.07F, 0.07F, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
		}
		// The rides: the cabin hangs under the cable.
		up = cabin("cable_up", line, b, "Gondolier Gil", false);
		down = cabin("cable_down", line, a, "Gondolier Gil", true);
		Vec3 ha = a.add(2.5, 0, 0);
		dev.rubymod.RubyMod.LOGGER.info("Bridges: cable car from {} to {}, cable at FLOOR + {}", a, b, top - FLOOR);
		Vec3 hb = b.add(-2.5, 0, 0);
		HubHosts.add(level, ha, 90, VillagerProfession.CARTOGRAPHER, VillagerType.SAVANNA,
				new HubHosts.Host("cable_up", "Gondolier Gil", "Cable Car", 0x60A0E0, "minecraft:chain",
						"Up, up and away! The cable car floats you over the park to Home Street. Two to a cabin: bring a friend.",
						"Cable car to Home Street: right-click Gil!", HubHosts.rideButtons("Up to Home Street!"), (p, key) -> up.board(p, HubHosts.group(p, key))));
		HubHosts.add(level, hb, -90, VillagerProfession.CARTOGRAPHER, VillagerType.SAVANNA,
				new HubHosts.Host("cable_down", "Gondolier Gwen", "Cable Car", 0x60A0E0, "minecraft:chain",
						"All aboard for the plaza! The cable car floats you back over the park. Two to a cabin.",
						"Cable car to the plaza: right-click Gwen!", HubHosts.rideButtons("Down to the plaza!"), (p, key) -> down.board(p, HubHosts.group(p, key))));
	}

	private static TrackRide cabin(String name, List<Vec3> cable, Vec3 exit, String host, boolean reverse) {
		List<Vec3> pts = new ArrayList<>();
		for (Vec3 p : cable) {
			pts.add(p.add(0, -3.0, 0));
		}
		if (reverse) {
			java.util.Collections.reverse(pts);
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		for (int i = 0; i < speed.length; i++) {
			double ends = Math.min(i, speed.length - 1 - i);
			speed[i] = Math.min(0.16, 0.04 + ends * 0.004);
		}
		TrackRide r = new TrackRide(name, path, speed, "gondola_blue", 2.5F, exit, host);
		r.upright = true;
		r.sideBySide = true;
		r.seatHeight = 0.35;
		r.waitTicks = 60;
		r.again = "What a view! Talk to the gondolier to ride again.";
		return r;
	}

	private static Vec3 towerSpot(ServerLevel level, Vec3 near) {
		for (int r = 0; r <= 8; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					int x = (int) Math.floor(near.x) + dx;
					int z = (int) Math.floor(near.z) + dz;
					boolean ok = true;
					for (int ox = -1; ox <= 1 && ok; ox++) {
						for (int oz = -1; oz <= 1 && ok; oz++) {
							ok = !level.getBlockState(new BlockPos(x + ox, FLOOR, z + oz)).isAir();
							for (int y = 1; y <= 3 && ok; y++) {
								BlockState s = level.getBlockState(new BlockPos(x + ox, FLOOR + y, z + oz));
								ok = s.isAir() || s.canBeReplaced();
							}
						}
					}
					if (ok) {
						return new Vec3(x + 0.5, FLOOR + 1, z + 0.5);
					}
				}
			}
		}
		return near;
	}

	/** A timber tower with a little roof and a lantern, the cable running from the top. */
	private static void tower(ServerLevel level, Vec3 at, int top) {
		int x = (int) Math.floor(at.x);
		int z = (int) Math.floor(at.z);
		for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
			HubPark.box(level, x + c[0], FLOOR + 1, z + c[1], x + c[0], top, z + c[1], Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
		}
		for (int y = FLOOR + 4; y < top; y += 4) {
			for (int k = -1; k <= 1; k++) {
				HubPark.set(level, x + k, y, z - 1, Blocks.SPRUCE_FENCE.defaultBlockState());
				HubPark.set(level, x + k, y, z + 1, Blocks.SPRUCE_FENCE.defaultBlockState());
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				HubPark.set(level, x + dx, top + 1, z + dz, Math.max(Math.abs(dx), Math.abs(dz)) == 2 ? Blocks.SPRUCE_SLAB.defaultBlockState()
						: Blocks.SPRUCE_PLANKS.defaultBlockState());
			}
		}
		HubPark.set(level, x, top + 2, z, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
		HubPark.set(level, x, top, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		HubPark.label(level, x + 0.5, FLOOR + 4.2, z + 0.5, "CABLE CAR", ChatFormatting.AQUA, 1.0F);
	}

	static void tick(ServerLevel level) {
		if (up != null) {
			up.tick(level);
		}
		if (down != null) {
			down.tick(level);
		}
	}
}
