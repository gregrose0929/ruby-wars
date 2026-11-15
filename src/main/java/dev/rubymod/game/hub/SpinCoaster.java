package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Twister Mouse: a spinning coaster. Up the lift, then a zig-zag of tight hairpin turns and little drops, the car
 * spinning round as it goes, so you never know which way you'll be facing next.
 */
final class SpinCoaster {
	private SpinCoaster() {
	}

	static final int[][] CIRCLES = {{50, 75, 14}};
	private static TrackRide ride;

	static void build(ServerLevel level) {
		double[][] c = {{40, 70, 1}, {44, 70, 1}, {48, 70, 4}, {52, 70, 8}, {56, 70, 9}, {59, 71.5, 9}, {59, 74.5, 9}, {56, 76, 8.6}, {46, 76, 8.2},
			{43, 77.5, 8}, {43, 80.5, 7.6}, {46, 82, 7.2}, {54, 82, 5.5}, {59, 82, 4}, {63, 80, 3}, {64, 74, 2}, {62, 67, 1.5}, {54, 65.5, 1}, {44, 65.5, 1},
			{39, 67, 1}};
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : c) {
			pts.add(new Vec3(p[0] + 0.5, FLOOR + p[2], p[1] + 0.5));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		int top = 0;
		for (int i = 0; i < path.size() / 2; i++) {
			if (path.get(i).y > path.get(top).y) {
				top = i;
			}
		}
		for (int i = 0; i < path.size(); i++) {
			double v = i <= top ? 0.14 : Math.min(0.62, Math.sqrt(0.04 + 2 * 0.035 * Math.max(0, path.get(top).y - path.get(i).y)) + 0.12);
			double left = (path.size() - 1 - i) * TrackRide.STEP;
			speed[i] = left < 6 ? Math.min(v, 0.1 + left * 0.05) : v;
		}
		ride = new TrackRide("spinner", path, speed, "teacup_purple", 2.2F, new Vec3(38.5, FLOOR + 1, 72.5), "Minnie");
		ride.seatsPer = 2;
		ride.sideBySide = true;
		ride.upright = true;
		ride.spin = 3.0;
		ride.spinFrom = top;
		ride.modelUp = 0.5;
		ride.seatHeight = 0.5;
		ride.waitTicks = 60;
		ride.events.put(top + 2, (lvl, car) -> {
			ride.tell(lvl, car, "Here we go... round and round!");
			lvl.playSound(null, BlockPos.containing(car.pos), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.2F, 1.4F);
		});
		track(level, path);
		// The station.
		HubPark.box(level, 37, FLOOR, 68, 45, FLOOR, 73, Blocks.SMOOTH_STONE.defaultBlockState());
		for (int x = 36; x <= 46; x++) {
			for (int z = 67; z <= 74; z++) {
				HubPark.set(level, x, FLOOR + 5, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.PURPLE : DyeColor.WHITE).defaultBlockState());
			}
		}
		for (int[] post : new int[][] {{36, 67}, {46, 67}, {36, 74}, {46, 74}}) {
			HubPark.box(level, post[0], FLOOR + 1, post[1], post[0], FLOOR + 4, post[1], Blocks.QUARTZ_PILLAR.defaultBlockState());
		}
		HubPark.label(level, 51, FLOOR + 13, 75, "TWISTER MOUSE", ChatFormatting.LIGHT_PURPLE, 2.6F);
		HubHosts.add(level, new Vec3(38.5, FLOOR + 1, 74.5), 180, VillagerProfession.LIBRARIAN, VillagerType.SNOW,
				new HubHosts.Host("spinner", "Minnie", "Twister Mouse", 0xC060FF, "minecraft:purple_dye",
						"A spinning coaster! Tight turns, little drops, and your car spins round as it goes: you never know which way you'll "
								+ "be facing next. Two to a car.",
						"Twister Mouse: right-click Minnie to ride!", HubHosts.rideButtons("Ride the Twister Mouse!"), (p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	/** Rails on a steel spine, posts down to the ground. */
	private static void track(ServerLevel level, List<Vec3> path) {
		BlockState rail = Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState();
		BlockState spine = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		BlockState post = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
		int seg = 6;
		for (int i = 0; i + seg < path.size(); i += seg) {
			Vec3 a = path.get(i);
			Vec3 b = path.get(i + seg);
			Vec3 d = b.subtract(a);
			Vec3 side = new Vec3(-d.z, 0, d.x).normalize().scale(0.5);
			for (int s : new int[] {-1, 1}) {
				NightShow.glow(HubPark.beam(level, a.add(side.scale(s)), b.add(side.scale(s)), 0.14F, 0.14F, rail));
			}
			HubPark.beam(level, a.add(0, -0.35, 0), b.add(0, -0.35, 0), 0.3F, 0.3F, spine);
			if (i % (seg * 4) == 0 && a.y > FLOOR + 1.5) {
				HubPark.beam(level, a.add(0, -0.5, 0), new Vec3(a.x, FLOOR + 1, a.z), 0.3F, 0.3F, post);
			}
		}
	}

	static void tick(ServerLevel level) {
		if (ride != null) {
			ride.tick(level);
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
