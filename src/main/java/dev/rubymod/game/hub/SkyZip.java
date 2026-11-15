package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
 * The Sky Zip: a long, fast zip line high over the park. From a tall tower on the plaza, across to the top of the Splash
 * Tower, over the Fun Park to a mast on the Thrill Zone, and all the way back: wind in your ears, swinging under the
 * cable. About forty seconds. Ziggy at the bottom of the plaza tower clips you on.
 */
final class SkyZip {
	private SkyZip() {
	}

	private static final Vec3 PLAZA = new Vec3(19.5, FLOOR, 12.5);
	private static final Vec3 WATER = new Vec3(30.5, FLOOR, -99.5);
	private static final Vec3 THRILL = new Vec3(100.5, FLOOR, 26.5);
	private static final double HANG = 2.3;
	private static TrackRide zip;

	static void build(ServerLevel level) {
		// The plaza tower: a lattice of dark wood with a platform up top.
		int tx = (int) Math.floor(PLAZA.x);
		int tz = (int) Math.floor(PLAZA.z);
		for (int y = FLOOR + 1; y <= FLOOR + 31; y++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					boolean corner = dx != 0 && dz != 0;
					boolean brace = (y + dx + dz) % 4 == 0 && !(dx == 0 && dz == 0);
					HubPark.set(level, tx + dx, y, tz + dz, corner ? Blocks.DARK_OAK_LOG.defaultBlockState() : brace ? Blocks.DARK_OAK_FENCE.defaultBlockState()
							: Blocks.AIR.defaultBlockState());
				}
			}
		}
		HubPark.box(level, tx - 2, FLOOR + 32, tz - 2, tx + 2, FLOOR + 32, tz + 2, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		HubPark.set(level, tx, FLOOR + 33, tz, Blocks.LANTERN.defaultBlockState());
		HubPark.label(level, PLAZA.x, FLOOR + 35, PLAZA.z, "SKY ZIP", ChatFormatting.GOLD, 2.2F);
		// Masts on top of the Splash Tower and on the Thrill Zone.
		HubPark.box(level, (int) Math.floor(WATER.x), WaterPark.TOP + 6, (int) Math.floor(WATER.z), (int) Math.floor(WATER.x), FLOOR + 38,
				(int) Math.floor(WATER.z), Blocks.IRON_BARS.defaultBlockState());
		int mx = (int) Math.floor(THRILL.x);
		int mz = (int) Math.floor(THRILL.z);
		HubPark.box(level, mx, FLOOR + 1, mz, mx, FLOOR + 36, mz, Blocks.IRON_BARS.defaultBlockState());
		HubPark.box(level, mx - 1, FLOOR + 1, mz - 1, mx + 1, FLOOR + 1, mz + 1, Blocks.STONE_BRICKS.defaultBlockState());
		HubPark.set(level, mx, FLOOR + 37, mz, Blocks.SEA_LANTERN.defaultBlockState());
		// The route: up the plaza tower, three legs of cable (each sagging a little), down again.
		List<Vec3> pts = new ArrayList<>();
		Vec3 base = PLAZA.add(0, 1, 0);
		lift(pts, base, FLOOR + 32 - HANG + 0.5);
		Vec3 a = new Vec3(PLAZA.x, FLOOR + 32, PLAZA.z);
		Vec3 b = new Vec3(WATER.x, FLOOR + 30, WATER.z);
		Vec3 b2 = new Vec3(WATER.x, FLOOR + 37, WATER.z);
		Vec3 c = new Vec3(THRILL.x, FLOOR + 32, THRILL.z);
		Vec3 c2 = new Vec3(THRILL.x, FLOOR + 36, THRILL.z);
		Vec3 d = new Vec3(PLAZA.x, FLOOR + 29, PLAZA.z);
		List<int[]> legs = new ArrayList<>();
		legs.add(new int[] {pts.size()});
		leg(level, pts, a, b);
		lift(pts, b.add(0, -HANG, 0), b2.y - HANG);
		legs.add(new int[] {pts.size()});
		leg(level, pts, b2, c);
		lift(pts, c.add(0, -HANG, 0), c2.y - HANG);
		legs.add(new int[] {pts.size()});
		leg(level, pts, c2, d);
		lift(pts, d.add(0, -HANG, 0), FLOOR + 1);
		List<Vec3> path = HubRides.even(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		for (int i = 0; i < path.size(); i++) {
			Vec3 p = path.get(i);
			Vec3 q = path.get(Math.min(path.size() - 1, i + 1));
			boolean vertical = Math.abs(q.y - p.y) > 0.4 * TrackRide.STEP;
			speed[i] = vertical ? 0.2 : 0.7;
		}
		zip = new TrackRide("skyzip", path, speed, "zip_handle", 1.0F, new Vec3(17.5, FLOOR + 1, 15.5), "Ziggy");
		zip.seatsPer = 1;
		zip.seatHeight = 0;
		zip.modelUp = HANG - 0.3;
		zip.upright = true;
		zip.sway = true;
		zip.waitTicks = 30;
		for (int[] l : legs) {
			zip.events.put(Math.min(path.size() - 1, (int) (l[0] * 1.0)), (lvl, car) -> {
				lvl.playSound(null, BlockPos.containing(car.pos), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 1.0F, 1.3F);
				zip.tell(lvl, car, "WHEEEEE!");
			});
		}
		HubHosts.add(level, new Vec3(16.5, FLOOR + 1, 15.5), 0, VillagerProfession.CARTOGRAPHER, VillagerType.SAVANNA,
				new HubHosts.Host("skyzip", "Ziggy", "Sky Zip", 0xFFAA40, "minecraft:lead",
						"Clip on and zip high over the park: out to the top of the Splash Tower on Splash Island, across to the Fun Park, and all the way "
								+ "back here. Fast and windy! About forty seconds.",
						"Sky Zip: right-click Ziggy to fly!", HubHosts.rideButtons("Ride the Sky Zip!"), (p, key) -> zip.board(p, HubHosts.group(p, key))));
	}

	/** Straight up (or down) a mast to {@code toY}. */
	private static void lift(List<Vec3> pts, Vec3 from, double toY) {
		int n = (int) Math.ceil(Math.abs(toY - from.y) / 0.5);
		for (int i = 0; i <= n; i++) {
			pts.add(new Vec3(from.x, from.y + (toY - from.y) * i / Math.max(1, n), from.z));
		}
	}

	/** A cable from a to b, sagging in the middle; the rider hangs HANG below it. */
	private static void leg(ServerLevel level, List<Vec3> pts, Vec3 a, Vec3 b) {
		int n = (int) Math.ceil(a.distanceTo(b) / 0.5);
		BlockState cable = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		Vec3 prev = null;
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			Vec3 c = a.lerp(b, t).add(0, -2.5 * Math.sin(Math.PI * t), 0);
			pts.add(c.add(0, -HANG, 0));
			if (i % 6 == 0) {
				if (prev != null) {
					HubPark.beam(level, prev, c, 0.07F, 0.07F, cable);
				}
				prev = c;
			}
		}
		if (prev != null) {
			HubPark.beam(level, prev, b, 0.07F, 0.07F, cable);
		}
	}

	static void tick(ServerLevel level, int tick) {
		if (zip == null) {
			return;
		}
		zip.tick(level);
		if (tick % 4 == 0) {
			for (TrackRide.Car c : zip.cars) {
				if (c.wait <= 0) {
					level.sendParticles(ParticleTypes.CLOUD, c.pos.x, c.pos.y + 0.5, c.pos.z, 1, 0.1, 0.1, 0.1, 0.01);
				}
			}
		}
	}

	static boolean busy() {
		return zip != null && !zip.cars.isEmpty();
	}

	static void reset() {
		if (zip != null) {
			zip.reset();
		}
	}
}
