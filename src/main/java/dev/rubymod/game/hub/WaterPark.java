package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Splash Tower, a water park: a 25-block tower with three slides off the top (the Twister, a fast tube that loops out
 * over the sky; Free Fall, a near-vertical plunge; the Family Raft, a bumpy ride for up to four), a wave pool, and a
 * lazy river all the way round the island. Lulu lifts you to the top or sets you afloat on the river.
 */
final class WaterPark {
	private WaterPark() {
	}

	/** Laid out round (44, -44); built shifted by DX, DZ, on Splash Island. */
	static final int DX = -14;
	static final int DZ = -56;
	static final int CX = 44;
	static final int CZ = -44;
	static final int TOP = FLOOR + 26;
	private static final Vec3 BEACH = new Vec3(44.5 + DX, FLOOR + 1, -48.5 + DZ);
	private static final AABB POOL = new AABB(37 + DX, FLOOR - 3, -59 + DZ, 52 + DX, FLOOR + 2, -49.5 + DZ);

	private static TrackRide twister;
	private static TrackRide freeFall;
	private static TrackRide raft;
	private static TrackRide river;
	private static final BlockPos TWISTER_PAD = new BlockPos(48 + DX, TOP, -44 + DZ);
	private static final BlockPos FALL_PAD = new BlockPos(40 + DX, TOP, -44 + DZ);
	private static final BlockPos RAFT_PAD = new BlockPos(44 + DX, TOP, -40 + DZ);
	private static final Set<UUID> ON_PAD = new HashSet<>();
	/** The drop capsule: stand on its floor, a countdown, and the floor drops away (laid out at 49, -54). */
	private static final BlockPos CAPSULE = new BlockPos(49 + DX, TOP, -54 + DZ);
	private static final java.util.Map<UUID, Integer> IN_CAPSULE = new java.util.HashMap<>();
	private static int capsuleOpenUntil;
	private static int tick;

	static void build(ServerLevel level) {
		HubPark.at(DX, DZ, () -> layout(level));
	}

	private static void layout(ServerLevel level) {
		ON_PAD.clear();
		HubPark.clear(level, CX - 19, FLOOR + 1, CZ - 19, CX + 19, FLOOR + 40, CZ + 19);
		for (int x = -16; x <= 16; x++) {
			for (int z = -16; z <= 16; z++) {
				BlockPos p = new BlockPos(CX + x + DX, FLOOR, CZ + z + DZ);
				if (level.getBlockState(p).is(Blocks.GRASS_BLOCK)) {
					level.setBlock(p, Blocks.SAND.defaultBlockState(), 2);
				}
			}
		}
		pool(level);
		lazyRiver(level);
		tower(level);
		slides(level);
		dress(level);
		HubPark.label(level, CX + 0.5, TOP + 9, CZ + 0.5, "SPLASH TOWER", ChatFormatting.AQUA, 3.0F);
		HubHosts.add(level, new Vec3(CX + 0.5, FLOOR + 1, CZ + 6.5), 0, VillagerProfession.NITWIT, VillagerType.DESERT,
				new HubHosts.Host("tower", "Lulu", "Splash Tower", 0x40C8FF, "minecraft:water_bucket",
						"Up at the top of the tower there are three slides: step on BLUE for the Twister (fast, loops out over the sky), RED for "
								+ "Free Fall (straight down!), YELLOW for the Family Raft (up to four of you). Or float round the lazy river!",
						"Splash Tower: right-click Lulu!", p -> {
							List<String> b = new ArrayList<>();
							b.add("top|Take me to the top!");
							b.add("river|Float the lazy river");
							if (!HubHosts.friends(p).isEmpty()) {
								b.add("riverfriends|Float the river with friends");
							}
							return b;
						}, (p, key) -> {
							if ("top".equals(key)) {
								p.teleportTo(CX + DX + 0.5, TOP + 1, CZ + DZ + 3.5);
								p.setYRot(180);
								p.resetFallDistance();
								p.sendOverlayMessage(Component.literal("Blue: Twister.  Red: Free Fall.  Yellow: Family Raft.").withStyle(ChatFormatting.AQUA));
							} else {
								river.board(p, HubHosts.group(p, "riverfriends".equals(key) ? "friends" : "play"));
							}
						}));
	}

	// ----- Water -----

	private static void pool(ServerLevel level) {
		for (int x = 36; x <= 52; x++) {
			for (int z = -60; z <= -49; z++) {
				boolean rim = x == 36 || x == 52 || z == -60;
				boolean beach = z == -49;
				if (Math.hypot(x - CX, z - CZ) > 16.3) {
					continue;
				}
				HubPark.set(level, x, FLOOR - 3, z, Blocks.PRISMARINE_BRICKS.defaultBlockState());
				for (int y = FLOOR - 2; y <= FLOOR; y++) {
					HubPark.set(level, x, y, z, rim ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : beach ? Blocks.SMOOTH_SANDSTONE.defaultBlockState()
							: Blocks.WATER.defaultBlockState());
				}
			}
		}
		HubPark.label(level, 44.5, FLOOR + 3, -59, "WAVE POOL", ChatFormatting.AQUA, 1.2F);
	}

	private static void lazyRiver(ServerLevel level) {
		for (int x = -16; x <= 16; x++) {
			for (int z = -16; z <= 16; z++) {
				double r = Math.hypot(x, z);
				int bx = CX + x;
				int bz = CZ + z;
				if (POOL.contains(bx + DX + 0.5, FLOOR - 1, bz + DZ + 0.5)) {
					continue;
				}
				if (r >= 11 && r <= 14) {
					HubPark.set(level, bx, FLOOR - 2, bz, Blocks.SMOOTH_QUARTZ.defaultBlockState());
					HubPark.set(level, bx, FLOOR - 1, bz, Blocks.WATER.defaultBlockState());
					HubPark.set(level, bx, FLOOR, bz, Blocks.WATER.defaultBlockState());
					HubPark.set(level, bx, FLOOR + 1, bz, Blocks.AIR.defaultBlockState());
				} else if (r > 10.3 && r < 11 || r > 14 && r < 14.8) {
					HubPark.set(level, bx, FLOOR, bz, Blocks.SMOOTH_QUARTZ.defaultBlockState());
					HubPark.set(level, bx, FLOOR - 1, bz, Blocks.SMOOTH_QUARTZ.defaultBlockState());
				}
			}
		}
		// The river's dock, by the south side.
		HubPark.box(level, 42, FLOOR, -34, 46, FLOOR, -34, Blocks.SPRUCE_PLANKS.defaultBlockState());
		List<Vec3> ring = new ArrayList<>();
		for (int i = 0; i <= 64; i++) {
			double a = Math.PI / 2 + i * Math.PI * 2 / 64;
			ring.add(new Vec3(CX + DX + 0.5 + Math.cos(a) * 12.5, FLOOR + 0.75, CZ + DZ + 0.5 + Math.sin(a) * 12.5));
		}
		List<Vec3> path = HubRides.even(ring, TrackRide.STEP);
		double[] speed = new double[path.size()];
		java.util.Arrays.fill(speed, 0.11);
		river = new TrackRide("river", path, speed, "tube", 1.5F, new Vec3(44.5 + DX, FLOOR + 1, -35.5 + DZ), "Lulu");
		river.seatsPer = 1;
		river.seatHeight = 0.15;
		river.upright = true;
		river.waitTicks = 30;
		river.events.put(path.size() / 4, (lvl, car) -> river.tell(lvl, car, "Ahh, lovely and relaxing..."));
		river.events.put(path.size() / 2, (lvl, car) -> {
			river.tell(lvl, car, "Under the waterfall!");
			lvl.sendParticles(ParticleTypes.FALLING_WATER, car.pos.x, car.pos.y + 3, car.pos.z, 60, 0.6, 0.6, 0.6, 0.1);
		});
	}

	// ----- The tower -----

	private static void tower(ServerLevel level) {
		for (int y = FLOOR + 1; y < TOP; y++) {
			for (int x = CX - 3; x <= CX + 3; x++) {
				for (int z = CZ - 3; z <= CZ + 3; z++) {
					boolean wall = x == CX - 3 || x == CX + 3 || z == CZ - 3 || z == CZ + 3;
					boolean corner = (x == CX - 3 || x == CX + 3) && (z == CZ - 3 || z == CZ + 3);
					boolean window = wall && !corner && (y - FLOOR) % 5 == 3 && (x + z) % 2 == 0;
					HubPark.set(level, x, y, z, !wall ? Blocks.AIR.defaultBlockState() : corner ? Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState()
							: window ? Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState()
									: Blocks.DYED_TERRACOTTA.pick(((y - FLOOR) / 3) % 2 == 0 ? DyeColor.WHITE : DyeColor.LIGHT_BLUE).defaultBlockState());
				}
			}
		}
		// The top: a deck with railings, three gaps (one for each slide), coloured pads, and a big striped parasol.
		for (int x = CX - 5; x <= CX + 5; x++) {
			for (int z = CZ - 5; z <= CZ + 5; z++) {
				HubPark.set(level, x, TOP, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
				boolean rim = x == CX - 5 || x == CX + 5 || z == CZ - 5 || z == CZ + 5;
				boolean gap = Math.abs(z - CZ) <= 1 && (x == CX - 5 || x == CX + 5) || Math.abs(x - CX) <= 1 && z == CZ + 5;
				HubPark.set(level, x, TOP + 1, z, rim && !gap ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
		}
		HubPark.set(level, (TWISTER_PAD.getX() - DX), TWISTER_PAD.getY(), (TWISTER_PAD.getZ() - DZ), Blocks.GLAZED_TERRACOTTA.pick(DyeColor.BLUE).defaultBlockState());
		HubPark.set(level, (FALL_PAD.getX() - DX), FALL_PAD.getY(), (FALL_PAD.getZ() - DZ), Blocks.GLAZED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState());
		HubPark.set(level, (RAFT_PAD.getX() - DX), RAFT_PAD.getY(), (RAFT_PAD.getZ() - DZ), Blocks.GLAZED_TERRACOTTA.pick(DyeColor.YELLOW).defaultBlockState());
		HubPark.label(level, (TWISTER_PAD.getX() - DX) + 0.5, TOP + 3, (TWISTER_PAD.getZ() - DZ) + 0.5, "THE TWISTER\nstep on the blue", ChatFormatting.AQUA, 0.8F);
		HubPark.label(level, (FALL_PAD.getX() - DX) + 0.5, TOP + 3, (FALL_PAD.getZ() - DZ) + 0.5, "FREE FALL\nstep on the red", ChatFormatting.RED, 0.8F);
		HubPark.label(level, (RAFT_PAD.getX() - DX) + 0.5, TOP + 3, (RAFT_PAD.getZ() - DZ) + 0.5, "FAMILY RAFT\nstep on the yellow", ChatFormatting.YELLOW, 0.8F);
		HubPark.box(level, CX, TOP + 1, CZ, CX, TOP + 4, CZ, Blocks.BIRCH_FENCE.defaultBlockState());
		// The drop capsule: a walkway off the deck to a glass capsule standing out over the pool.
		for (int z = -50; z >= -53; z--) {
			HubPark.set(level, 49, TOP, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
			HubPark.set(level, 48, TOP + 1, z, Blocks.GLASS_PANE.defaultBlockState());
			HubPark.set(level, 50, TOP + 1, z, Blocks.GLASS_PANE.defaultBlockState());
		}
		HubPark.set(level, 49, TOP + 1, -49, Blocks.AIR.defaultBlockState());
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				boolean door = dx == 0 && dz == 2;
				for (int y = TOP + 1; y <= TOP + 4; y++) {
					HubPark.set(level, 49 + dx, y, -54 + dz, edge && !door && y <= TOP + 3 ? Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState()
							: y == TOP + 4 ? Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState() : Blocks.AIR.defaultBlockState());
				}
				if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && !(dx == 0 && dz == 0)) {
					HubPark.set(level, 49 + dx, TOP, -54 + dz, Blocks.SMOOTH_QUARTZ.defaultBlockState());
				}
			}
		}
		HubPark.set(level, 49, TOP, -54, Blocks.GLAZED_TERRACOTTA.pick(DyeColor.MAGENTA).defaultBlockState());
		HubPark.label(level, 49.5, TOP + 5.5, -53.5, "DROP CAPSULE\nstand on the pink... if you dare!", ChatFormatting.LIGHT_PURPLE, 0.8F);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				int ring = Math.max(Math.abs(dx), Math.abs(dz));
				HubPark.set(level, CX + dx, TOP + 5 + (3 - ring) / 2, CZ + dz, Blocks.WOOL.pick((dx + dz) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
		}
	}

	/** A slide: a floor along the path, low walls, and (for a tube) glass sides and a roof. */
	private static void chute(ServerLevel level, List<Vec3> path, int from, int to, BlockState floor, BlockState side, boolean tube, double width) {
		for (int i = from; i < to; i++) {
			Vec3 p = path.get(i);
			Vec3 q = path.get(Math.min(path.size() - 1, i + 1));
			Vec3 sd = new Vec3(q.z - p.z, 0, -(q.x - p.x));
			if (sd.lengthSqr() < 1e-6) {
				continue;
			}
			sd = sd.normalize();
			int fy = (int) Math.floor(p.y - 0.4);
			if (p.y - q.y > TrackRide.STEP * 0.8) {
				Vec3 back = new Vec3(p.x - q.x, 0, p.z - q.z);
				back = back.lengthSqr() < 1e-6 ? sd : back.normalize();
				Vec3 w = p.add(back.scale(1.1));
				HubPark.ifAir(level, (int) Math.floor(w.x) - HubPark.OX, (int) Math.floor(p.y), (int) Math.floor(w.z) - HubPark.OZ, side);
				continue;
			}
			for (double w = -width; w <= width; w += 0.5) {
				Vec3 f = p.add(sd.scale(w));
				HubPark.ifAir(level, (int) Math.floor(f.x) - HubPark.OX, fy, (int) Math.floor(f.z) - HubPark.OZ, floor);
			}
			for (int s : new int[] {-1, 1}) {
				Vec3 r = p.add(sd.scale(s * (width + 1.05)));
				HubPark.ifAir(level, (int) Math.floor(r.x) - HubPark.OX, fy + 1, (int) Math.floor(r.z) - HubPark.OZ, side);
				if (tube) {
					HubPark.ifAir(level, (int) Math.floor(r.x) - HubPark.OX, fy + 2, (int) Math.floor(r.z) - HubPark.OZ, side);
				}
			}
			if (tube && i % 2 == 0) {
				HubPark.ifAir(level, (int) Math.floor(p.x) - HubPark.OX, fy + 3, (int) Math.floor(p.z) - HubPark.OZ, Blocks.STAINED_GLASS.pick(DyeColor.WHITE).defaultBlockState());
			}
		}
	}

	private static List<Vec3> path(double[][] c) {
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : c) {
			pts.add(new Vec3(p[0] + DX + 0.5, FLOOR + 1 + p[2], p[1] + DZ + 0.5));
		}
		return HubRides.spline(pts, TrackRide.STEP);
	}

	private static double[] gravity(List<Vec3> path, double start, double max) {
		double[] v = new double[path.size()];
		double top = path.get(0).y;
		for (int i = 0; i < path.size(); i++) {
			double s = Math.sqrt(start * start + 2 * 0.04 * Math.max(0, top - path.get(i).y));
			double left = (path.size() - 1 - i) * TrackRide.STEP;
			v[i] = Math.min(max, left < 4 ? Math.min(s, 0.2 + left * 0.1) : s);
		}
		return v;
	}

	private static void slides(ServerLevel level) {
		// The Twister: out from the top, round in great loops over the sky, back down into the pool.
		List<Vec3> tw = path(new double[][] {{48, -44, 26.2}, {51, -44, 25.6}, {57, -41, 24}, {62, -33, 22}, {57, -24, 20}, {47, -21, 18}, {36, -25, 16},
			{28, -34, 14}, {26, -46, 12}, {30, -57, 10}, {38, -63, 7}, {46, -61, 4}, {48, -57, 2}, {46, -54, -0.25}});
		twister = new TrackRide("twister", tw, gravity(tw, 0.35, 0.9), "tube", 1.5F, BEACH, "Lulu");
		twister.seatsPer = 1;
		twister.seatHeight = 0.15;
		twister.waitTicks = 20;
		chute(level, tw, 2, tw.size() - 4, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState(), Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState(),
				false, 0.5);
		splashAtEnd(twister);
		// Free Fall: off the west edge, nearly straight down, then out into the pool.
		List<Vec3> ff = path(new double[][] {{40, -44, 26.2}, {37.5, -44, 25.5}, {36, -44, 22}, {34.6, -44, 8}, {34.5, -46, 3}, {36, -51, 1}, {39, -54, -0.25}});
		freeFall = new TrackRide("freefall", ff, gravity(ff, 0.25, 1.2), "tube", 1.5F, BEACH, "Lulu");
		freeFall.seatsPer = 1;
		freeFall.seatHeight = 0.15;
		freeFall.waitTicks = 20;
		freeFall.events.put(4, (lvl, car) -> freeFall.tell(lvl, car, "Don't look down..."));
		chute(level, ff, 1, ff.size() - 4, Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState(), false,
				0.5);
		splashAtEnd(freeFall);
		// The Family Raft: a wide, bumpy, winding run down the south and east sides.
		List<Vec3> rf = path(new double[][] {{44, -40, 26.2}, {44, -37, 24.5}, {47, -33, 22.5}, {51, -31, 21.2}, {54, -34, 19}, {55.5, -38, 17.8}, {55, -42, 15},
			{54, -46, 13.8}, {51, -49, 10.5}, {47, -50, 7}, {44, -52, 3.5}, {42, -54, -0.25}});
		raft = new TrackRide("raft", rf, gravity(rf, 0.25, 0.6), "raft", 2.4F, BEACH, "Lulu");
		raft.seatsPer = 4;
		raft.seatHeight = 0.25;
		raft.waitTicks = 60;
		raft.upright = true;
		chute(level, rf, 2, rf.size() - 4, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState(), false,
				1.2);
		splashAtEnd(raft);
	}

	private static void splashAtEnd(TrackRide ride) {
		ride.events.put(ride.path.size() - 6, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(car.pos), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 1.6F, 0.9F);
			lvl.sendParticles(ParticleTypes.SPLASH, car.pos.x, car.pos.y + 0.6, car.pos.z, 120, 1.2, 0.6, 1.2, 0.4);
			ride.tell(lvl, car, "SPLASH!");
		});
	}

	private static void dress(ServerLevel level) {
		umbrella(level, CX - 7, CZ + 5, DyeColor.RED);
		umbrella(level, CX + 7, CZ + 5, DyeColor.ORANGE);
		umbrella(level, CX + 8, CZ - 3, DyeColor.LIME);
		lounger(level, CX - 8, CZ + 7);
		lounger(level, CX - 6, CZ + 7);
		lounger(level, CX + 6, CZ + 7);
		lounger(level, CX + 8, CZ + 7);
		palm(level, CX - 8, CZ - 2);
		palm(level, CX + 5, CZ + 8);
		palm(level, CX - 4, CZ + 9);
	}

	private static void umbrella(ServerLevel level, int x, int z, DyeColor color) {
		HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 3, z, Blocks.BIRCH_FENCE.defaultBlockState());
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				HubPark.set(level, x + dx, FLOOR + 4, z + dz, Blocks.WOOL.pick((dx + dz) % 2 == 0 ? color : DyeColor.WHITE).defaultBlockState());
			}
		}
		HubPark.set(level, x, FLOOR + 5, z, Blocks.WOOL.pick(color).defaultBlockState());
	}

	private static void lounger(ServerLevel level, int x, int z) {
		HubPark.set(level, x, FLOOR + 1, z, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState());
		HubPark.set(level, x, FLOOR + 1, z - 1, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
	}

	private static void palm(ServerLevel level, int x, int z) {
		BlockState trunk = Blocks.JUNGLE_LOG.defaultBlockState();
		for (int y = 1; y <= 6; y++) {
			HubPark.set(level, x + (y > 4 ? 1 : 0), FLOOR + y, z, trunk);
		}
		BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		int tx = x + 1;
		int ty = FLOOR + 7;
		HubPark.set(level, tx, ty, z, leaves);
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			for (int i = 1; i <= 2; i++) {
				HubPark.set(level, tx + d[0] * i, ty - (i == 2 ? 1 : 0), z + d[1] * i, leaves);
			}
		}
	}

	// ----- Every tick -----

	static void tick(ServerLevel level) {
		tick++;
		// The pads on top of the tower.
		for (ServerPlayer p : level.players()) {
			BlockPos under = p.blockPosition().below();
			TrackRide r = under.equals(TWISTER_PAD) ? twister : under.equals(FALL_PAD) ? freeFall : under.equals(RAFT_PAD) ? raft : null;
			if (r == null || !p.onGround() || p.isPassenger()) {
				if (r == null) {
					ON_PAD.remove(p.getUUID());
				}
				continue;
			}
			if (ON_PAD.add(p.getUUID())) {
				r.board(p, List.of(p));
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.8F, 1.3F);
			}
		}
		for (TrackRide r : new TrackRide[] {twister, freeFall, raft, river}) {
			if (r != null) {
				r.tick(level);
				if (tick % 3 == 0) {
					for (TrackRide.Car c : r.cars) {
						if (c.wait <= 0) {
							level.sendParticles(ParticleTypes.SPLASH, c.pos.x, c.pos.y + 0.3, c.pos.z, 3, 0.3, 0.1, 0.3, 0.05);
						}
					}
				}
			}
		}
		// The drop capsule.
		for (ServerPlayer p : level.players()) {
			boolean in = p.onGround() && p.blockPosition().below().equals(CAPSULE);
			if (!in) {
				IN_CAPSULE.remove(p.getUUID());
				continue;
			}
			int t = IN_CAPSULE.merge(p.getUUID(), 1, Integer::sum);
			if (t == 1 || t == 21 || t == 41) {
				dev.rubymod.game.Alerts.title(p, String.valueOf(3 - t / 20), "The floor's going to drop...", ChatFormatting.LIGHT_PURPLE);
				level.playSound(null, CAPSULE, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.5F, 0.8F + t / 60F);
			}
			if (t == 60) {
				level.setBlock(CAPSULE, Blocks.AIR.defaultBlockState(), 2);
				capsuleOpenUntil = tick + 40;
				level.playSound(null, CAPSULE, SoundEvents.PISTON_CONTRACT, SoundSource.PLAYERS, 1.5F, 0.6F);
				dev.rubymod.game.Alerts.title(p, "AAAAAH!", "", ChatFormatting.RED);
			}
		}
		if (capsuleOpenUntil > 0 && tick >= capsuleOpenUntil) {
			capsuleOpenUntil = 0;
			level.setBlock(CAPSULE, Blocks.GLAZED_TERRACOTTA.pick(DyeColor.MAGENTA).defaultBlockState(), 2);
		}
		// The wave pool: every ten seconds a wave rolls through.
		int phase = tick % 200;
		if (phase == 0) {
			for (ServerPlayer p : level.players()) {
				if (POOL.contains(p.position())) {
					p.sendOverlayMessage(Component.literal("WAVE INCOMING!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
				}
			}
			level.playSound(null, BlockPos.containing(44.5 + DX, FLOOR, -55 + DZ), dev.rubymod.registry.ModSounds.PARK_WAVES, SoundSource.AMBIENT, 1.5F, 1.0F);
		}
		if (phase < 30 && phase % 2 == 0) {
			double z = -59.5 + DZ + phase * 0.32;
			for (double x = 37.5 + DX; x < 52 + DX; x += 1.0) {
				level.sendParticles(ParticleTypes.SPLASH, x, FLOOR + 1, z, 3, 0.3, 0.1, 0.1, 0.1);
			}
			for (ServerPlayer p : level.players()) {
				if (POOL.contains(p.position()) && Math.abs(p.getZ() - z) < 1.5 && !p.isPassenger()) {
					p.setDeltaMovement(p.getDeltaMovement().add(0, 0.35, 0.25));
					p.syncVelocity = true;
				}
			}
		}
	}

	static void reset() {
		for (TrackRide r : new TrackRide[] {twister, freeFall, raft, river}) {
			if (r != null) {
				r.reset();
			}
		}
		ON_PAD.clear();
	}
}
