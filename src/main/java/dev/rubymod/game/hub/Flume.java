package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.world.phys.Vec3;

/**
 * Flume Falls: a log flume. You sit in a hollowed-out log, clank up the lift, float round the island in a wooden
 * channel, then plunge down the big drop into the splash pool (and anyone watching from the side gets wet too).
 */
final class Flume {
	private Flume() {
	}

	static final int CX = 6;
	static final int CZ = -63;
	static final Vec3 LAND = new Vec3(-4.5, FLOOR + 1, -60.5);
	private static final Vec3 SPLASH = new Vec3(2.5, FLOOR + 1, -54.5);
	/** x, z, height. */
	private static final double[][] TRACK = {{-1, -53, 1}, {-1, -55, 1.2}, {-1, -58, 3}, {-1, -66, 10.5}, {0, -70, 12}, {3, -73, 12}, {9, -74, 11.8},
		{14, -72, 11.5}, {17, -67, 11.2}, {17, -61, 10.9}, {15, -57, 10.6}, {12, -55, 10.3}, {10, -55, 10}, {3, -55, 1.3}, {1, -55, 1}};

	private static TrackRide ride;

	static void build(ServerLevel level) {
		HubPark.island(level, CX, CZ, 14, FLOOR);
		HubPark.clear(level, CX - 14, FLOOR + 13, CZ - 14, CX + 14, FLOOR + 16, CZ + 14);
		List<Vec3> pts = new ArrayList<>();
		for (double[] c : TRACK) {
			pts.add(new Vec3(c[0] + 0.5, FLOOR + c[2], c[1] + 0.5));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		// Speeds: slow up the lift, a gentle float, then gravity down the drop, and a slow coast after the splash.
		double[] speed = new double[path.size()];
		int top = 0;
		for (int i = 0; i < path.size() / 3; i++) {
			if (path.get(i).y > path.get(top).y) {
				top = i;
			}
		}
		int drop = nearest(path, 10.5, -54.5);
		int splash = nearest(path, 3.5, -54.5);
		for (int i = 0; i < path.size(); i++) {
			double v;
			if (i <= top) {
				v = 0.15;
			} else if (i < drop) {
				v = 0.2;
			} else if (i < splash) {
				v = Math.min(0.95, Math.sqrt(0.04 + 2 * 0.045 * Math.max(0, path.get(drop).y - path.get(i).y)));
			} else {
				v = 0.12;
			}
			speed[i] = v;
		}
		ride = new TrackRide("flume", path, speed, "log_boat", 1.7F, new Vec3(-4.5, FLOOR + 1, -58.5), "Flo");
		channel(level, path, top);
		// The splash pool.
		for (int x = -1; x <= 6; x++) {
			for (int z = -57; z <= -52; z++) {
				boolean rim = x == -1 || x == 6 || z == -57 || z == -52;
				boolean gap = rim && (x == 6 && z >= -56 && z <= -54 || x == -1 && z >= -56 && z <= -54);
				HubPark.box(level, x, FLOOR - 2, z, x, FLOOR, z, rim && !gap ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : Blocks.WATER.defaultBlockState());
				HubPark.set(level, x, FLOOR - 3, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
			}
		}
		// The station by the bottom of the lift.
		HubPark.box(level, -4, FLOOR, -57, -3, FLOOR, -52, Blocks.SPRUCE_PLANKS.defaultBlockState());
		for (int z : new int[] {-57, -52}) {
			HubPark.box(level, -4, FLOOR + 1, z, -4, FLOOR + 3, z, Blocks.SPRUCE_FENCE.defaultBlockState());
		}
		for (int z = -58; z <= -51; z++) {
			for (int x = -5; x <= 0; x++) {
				HubPark.set(level, x, FLOOR + 4, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.GREEN : DyeColor.WHITE).defaultBlockState());
			}
		}
		HubPark.label(level, -2, FLOOR + 5.8, -54.5, "LOG FLUME", ChatFormatting.GREEN, 1.8F);
		HubPark.label(level, CX + 0.5, FLOOR + 16, CZ + 0.5, "FLUME FALLS", ChatFormatting.AQUA, 2.6F);
		HubPark.label(level, SPLASH.x + 3, FLOOR + 2.8, SPLASH.z + 3.5, "SPLASH ZONE\nyou WILL get wet!", ChatFormatting.AQUA, 0.8F);
		HubPark.travelPad(level, 8, FLOOR, -64, "flume");
		ride.events.put(splash, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(SPLASH), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 2.0F, 0.8F);
			lvl.playSound(null, BlockPos.containing(SPLASH), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 2.0F, 0.7F);
			lvl.sendParticles(ParticleTypes.SPLASH, SPLASH.x, SPLASH.y + 0.5, SPLASH.z, 200, 2.0, 1.2, 2.0, 0.6);
			lvl.sendParticles(ParticleTypes.FALLING_WATER, SPLASH.x, SPLASH.y + 3, SPLASH.z, 80, 2.5, 1.0, 2.5, 0.1);
			for (ServerPlayer p : ride.riders(lvl, car)) {
				Alerts.title(p, "SPLASH!", "You're soaked!", ChatFormatting.AQUA);
			}
			for (ServerPlayer p : lvl.players()) {
				if (!ride.riding(p) && p.position().distanceTo(SPLASH) < 6) {
					p.sendOverlayMessage(Component.literal("SPLOOSH! You got splashed!").withStyle(ChatFormatting.AQUA));
				}
			}
		});
		ride.events.put(drop, (lvl, car) -> ride.tell(lvl, car, "Hold on... WHEEEEE!"));
		HubHosts.add(level, new Vec3(-3.5, FLOOR + 1, -56.5), 90, VillagerProfession.FARMER, VillagerType.JUNGLE,
				new HubHosts.Host("flume", "Flo", "Log Flume", 0x40E0A0, "minecraft:oak_log",
						"Hop in a log for a float round Flume Falls, then down the BIG drop into the splash pool! Two people fit in a log.",
						"Log Flume: right-click Flo for a ride!", HubHosts.rideButtons("Ride the Log Flume!"), (p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	/** A wooden channel of "water" along the path, on posts, with a chain floor up the lift. */
	private static void channel(ServerLevel level, List<Vec3> path, int lift) {
		BlockState water = Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
		BlockState chain = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		BlockState side = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState post = Blocks.SPRUCE_FENCE.defaultBlockState();
		int splash = nearest(path, 3.5, -54.5);
		for (int i = 0; i < splash; i++) {
			Vec3 p = path.get(i);
			Vec3 q = path.get(Math.min(path.size() - 1, i + 1));
			Vec3 sideDir = new Vec3(q.z - p.z, 0, -(q.x - p.x));
			if (sideDir.lengthSqr() < 1e-6) {
				continue;
			}
			sideDir = sideDir.normalize();
			int fy = (int) Math.floor(p.y) - 1;
			int bx = (int) Math.floor(p.x);
			int bz = (int) Math.floor(p.z);
			HubPark.set(level, bx, fy, bz, i <= lift ? chain : water);
			for (int s : new int[] {-1, 1}) {
				Vec3 r = p.add(sideDir.scale(s * 1.05));
				HubPark.ifAir(level, (int) Math.floor(r.x), fy + 1, (int) Math.floor(r.z), side);
				HubPark.ifAir(level, (int) Math.floor(r.x), fy, (int) Math.floor(r.z), side);
			}
			if (i % 10 == 0 && fy > FLOOR + 1) {
				for (int y = fy - 1; y > FLOOR; y--) {
					HubPark.ifAir(level, bx, y, bz, post);
				}
			}
		}
	}

	private static int nearest(List<Vec3> path, double x, double z) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = Math.hypot(path.get(i).x - x, path.get(i).z - z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
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

	static void reset() {
		if (ride != null) {
			ride.reset();
		}
	}
}
