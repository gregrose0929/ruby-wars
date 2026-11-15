package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * River Rapids, on Splash Island: big round rafts for four that spin and bob down a rocky river, under waterfalls and
 * past geysers. You WILL get splashed.
 */
final class Rapids {
	private Rapids() {
	}

	static final double CX = 40;
	static final double CZ = -141;
	private static final double A = 14;
	private static final double B = 8.5;

	private static TrackRide ride;

	private static Vec3 oval(double deg) {
		double t = Math.toRadians(deg);
		return new Vec3(CX + 0.5 + Math.cos(t) * A, FLOOR + 0.05, CZ + 0.5 + Math.sin(t) * B);
	}

	static void build(ServerLevel level) {
		List<Vec3> ctl = new ArrayList<>();
		for (int d = 90; d <= 450; d += 15) {
			ctl.add(oval(d));
		}
		List<Vec3> path = HubRides.spline(ctl, TrackRide.STEP);
		double[] speed = new double[path.size()];
		for (int i = 0; i < path.size(); i++) {
			// Faster through the narrow bits, slow at the start and end.
			double left = Math.min(i, path.size() - 1 - i) * TrackRide.STEP;
			speed[i] = left < 4 ? 0.1 : 0.17 + 0.07 * Math.sin(i * 0.05);
		}
		ride = new TrackRide("rapids", path, speed, "raft", 2.6F, oval(90).add(-4, 1, 3), "Rocky");
		ride.seatsPer = 4;
		ride.seatHeight = 0.3;
		ride.upright = true;
		ride.spin = 2.2;
		ride.spinFrom = 30;
		ride.waitTicks = 60;
		channel(level, path);
		// Geysers and waterfalls along the way.
		for (double deg : new double[] {170, 260, 350}) {
			int i = nearest(path, oval(deg));
			ride.events.put(i, (lvl, car) -> {
				lvl.sendParticles(ParticleTypes.SPLASH, car.pos.x, car.pos.y + 1, car.pos.z, 150, 0.8, 2.5, 0.8, 0.5);
				lvl.sendParticles(ParticleTypes.CLOUD, car.pos.x, car.pos.y + 3, car.pos.z, 20, 0.5, 1.5, 0.5, 0.05);
				lvl.playSound(null, BlockPos.containing(car.pos), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.8F, 0.7F);
				for (ServerPlayer p : ride.riders(lvl, car)) {
					Alerts.title(p, "", "GEYSER! You're soaked!", ChatFormatting.AQUA);
				}
			});
		}
		for (double deg : new double[] {215, 305, 400}) {
			Vec3 at = oval(deg);
			arch(level, at);
			int i = nearest(path, at);
			ride.events.put(i - 6, (lvl, car) -> {
				lvl.sendParticles(ParticleTypes.FALLING_WATER, car.pos.x, car.pos.y + 4.5, car.pos.z, 120, 1.5, 0.5, 1.5, 0.1);
				lvl.playSound(null, BlockPos.containing(car.pos), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.5F, 0.6F);
				ride.tell(lvl, car, "Under the waterfall!");
			});
		}
		HubPark.label(level, CX + 0.5, FLOOR + 9, CZ + 0.5, "RIVER RAPIDS", ChatFormatting.AQUA, 2.6F);
		Vec3 host = oval(90).add(-3, 1, 2);
		HubHosts.add(level, host, 180, VillagerProfession.FISHERMAN, VillagerType.JUNGLE,
				new HubHosts.Host("rapids", "Rocky", "River Rapids", 0x40B0FF, "minecraft:oak_boat",
						"Big round rafts for four! They spin and bump their way down the river, under waterfalls and past the geysers. "
								+ "Everyone gets wet. Everyone.",
						"River Rapids: right-click Rocky for a raft!", HubHosts.rideButtons("Ride the rapids!"), (p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	private static int nearest(List<Vec3> path, Vec3 at) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = Math.hypot(path.get(i).x - at.x, path.get(i).z - at.z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	/** A river dug into the ground: water three blocks wide, rocky banks, boulders here and there. */
	private static void channel(ServerLevel level, List<Vec3> path) {
		Set<Long> water = new HashSet<>();
		for (Vec3 p : path) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					int x = (int) Math.floor(p.x) + dx;
					int z = (int) Math.floor(p.z) + dz;
					if (Math.hypot(x + 0.5 - p.x, z + 0.5 - p.z) <= 2.6) {
						water.add(BlockPos.asLong(x, 0, z));
					}
				}
			}
		}
		BlockState[] rock = {Blocks.STONE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(),
			Blocks.COBBLESTONE.defaultBlockState()};
		for (long k : water) {
			int x = BlockPos.getX(k);
			int z = BlockPos.getZ(k);
			HubPark.set(level, x, FLOOR - 2, z, Blocks.GRAVEL.defaultBlockState());
			HubPark.set(level, x, FLOOR - 1, z, Blocks.WATER.defaultBlockState());
			HubPark.set(level, x, FLOOR, z, Blocks.WATER.defaultBlockState());
			for (int y = 1; y <= 4; y++) {
				HubPark.set(level, x, FLOOR + y, z, Blocks.AIR.defaultBlockState());
			}
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				long n = BlockPos.asLong(x + d[0], 0, z + d[1]);
				if (!water.contains(n)) {
					BlockState s = rock[Math.floorMod(x * 7 + z * 3, rock.length)];
					HubPark.set(level, x + d[0], FLOOR, z + d[1], s);
					HubPark.set(level, x + d[0], FLOOR - 1, z + d[1], s);
					HubPark.set(level, x + d[0], FLOOR - 2, z + d[1], s);
					if (Math.floorMod(x * 5 + z * 11, 7) == 0) {
						HubPark.set(level, x + d[0], FLOOR + 1, z + d[1], s);
					}
				}
			}
		}
	}

	/** A rocky arch over the river with a waterfall pouring off it (high above the rafts). */
	private static void arch(ServerLevel level, Vec3 at) {
		int x = (int) Math.floor(at.x);
		int z = (int) Math.floor(at.z);
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				boolean leg = Math.abs(dx) >= 4;
				for (int y = leg ? 1 : 6; y <= 7; y++) {
					if (!leg || !level.getBlockState(new BlockPos(x + dx, FLOOR, z + dz)).is(Blocks.WATER)) {
						HubPark.ifAir(level, x + dx, FLOOR + y, z + dz, (dx + y) % 3 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
					}
				}
			}
		}
	}

	static void tick(ServerLevel level, int tick) {
		if (ride == null) {
			return;
		}
		ride.tick(level);
		if (tick % 3 == 0) {
			for (TrackRide.Car c : ride.cars) {
				if (c.wait <= 0) {
					level.sendParticles(ParticleTypes.SPLASH, c.pos.x, c.pos.y + 0.3, c.pos.z, 6, 0.8, 0.1, 0.8, 0.1);
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
