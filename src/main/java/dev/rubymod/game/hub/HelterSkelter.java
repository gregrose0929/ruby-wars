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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Helter Skelter, in Toyland: a tall striped tower. Climb the ladder inside, step onto the yellow mat at the top,
 * and whizz round and round the slide down the outside.
 */
final class HelterSkelter {
	private HelterSkelter() {
	}

	static final int[][] CIRCLES = {{-9, 87, 11}};
	private static final int CX = -9;
	private static final int CZ = 87;
	private static final int TOP = FLOOR + 16;
	private static final double R = 5.0;
	private static TrackRide ride;
	private static BlockPos pad;
	private static final Set<UUID> ON_PAD = new HashSet<>();

	static void build(ServerLevel level) {
		ON_PAD.clear();
		// The tower: red and white stripes, a door, a ladder up the inside, a pointy roof.
		for (int y = FLOOR + 1; y <= TOP + 3; y++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					boolean door = dz == 2 && dx == 0 && y <= FLOOR + 2;
					boolean window = wall && y == TOP + 1 && dx == 2 && dz == 0;
					BlockState s = !wall || door || window ? Blocks.AIR.defaultBlockState()
							: Blocks.CONCRETE.pick(((y - FLOOR) / 2) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState();
					HubPark.set(level, CX + dx, y, CZ + dz, s);
				}
			}
			if (y < TOP + 1) {
				HubPark.set(level, CX, y, CZ - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
			}
		}
		HubPark.box(level, CX - 1, TOP, CZ - 1, CX + 1, TOP, CZ + 1, Blocks.SPRUCE_PLANKS.defaultBlockState());
		HubPark.set(level, CX, TOP, CZ - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
		for (int i = 0; i < 4; i++) {
			HubPark.box(level, CX - 2 + i / 2, TOP + 4 + i, CZ - 2 + i / 2, CX + 2 - i / 2, TOP + 4 + i, CZ + 2 - i / 2,
					Blocks.WOOL.pick(i % 2 == 0 ? DyeColor.YELLOW : DyeColor.RED).defaultBlockState());
		}
		// The way out at the top, onto the mat.
		HubPark.set(level, CX + 2, TOP, CZ, Blocks.SPRUCE_PLANKS.defaultBlockState());
		pad = new BlockPos(CX + 3 + HubPark.OX, TOP, CZ + HubPark.OZ);
		HubPark.set(level, CX + 3, TOP, CZ, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState());
		HubPark.label(level, CX + 0.5, TOP + 10, CZ + 0.5, "HELTER SKELTER", ChatFormatting.RED, 2.2F);
		HubPark.label(level, CX + 0.5, FLOOR + 3.6, CZ + 3.0, "Climb the ladder inside,\nthen step on the yellow mat!", ChatFormatting.WHITE, 0.6F);
		// The slide: two and a half turns round the outside, down to a soft landing.
		List<Vec3> pts = new ArrayList<>();
		double turns = 2.5;
		int n = 60;
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			double a = t * turns * Math.PI * 2;
			pts.add(new Vec3(CX + 0.5 + Math.cos(a) * R, TOP - 0.2 - t * (TOP - 0.2 - FLOOR - 1), CZ + 0.5 + Math.sin(a) * R));
		}
		Vec3 last = pts.get(pts.size() - 1);
		pts.add(last.add(0, 0, 3));
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		for (int i = 0; i < path.size(); i++) {
			double left = (path.size() - 1 - i) * TrackRide.STEP;
			speed[i] = left < 3 ? 0.12 : Math.min(0.42, 0.2 + i * 0.004);
		}
		ride = new TrackRide("helter", path, speed, "tube", 1.3F, new Vec3(CX + 7.5, FLOOR + 1, CZ + 5.5), "the tower");
		ride.seatsPer = 1;
		ride.seatHeight = 0.15;
		ride.upright = true;
		ride.waitTicks = 10;
		ride.lookAhead = true;
		ride.again = "Wheee! Climb the tower to go again!";
		// The slide's surface (wooden, striped), and a low rim on the outside.
		for (int i = 0; i < path.size() - 8; i++) {
			Vec3 p = path.get(i);
			Vec3 out = new Vec3(p.x - CX - 0.5, 0, p.z - CZ - 0.5).normalize();
			int fy = (int) Math.floor(p.y - 0.4);
			for (double w = -1.2; w <= 1.2; w += 0.5) {
				Vec3 f = p.add(out.scale(w));
				HubPark.ifAir(level, (int) Math.floor(f.x), fy, (int) Math.floor(f.z),
						(i / 12) % 2 == 0 ? Blocks.STRIPPED_OAK_WOOD.defaultBlockState() : Blocks.STRIPPED_BIRCH_WOOD.defaultBlockState());
			}
			Vec3 rim = p.add(out.scale(2.1));
			HubPark.ifAir(level, (int) Math.floor(rim.x), fy + 1, (int) Math.floor(rim.z), Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
		}
		// A soft landing.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = 0; dz <= 4; dz++) {
				HubPark.set(level, (int) Math.floor(last.x) + dx, FLOOR, (int) Math.floor(last.z) + dz, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState());
			}
		}
	}

	static void tick(ServerLevel level) {
		if (ride == null) {
			return;
		}
		for (ServerPlayer p : level.players()) {
			boolean on = p.onGround() && !p.isPassenger() && p.blockPosition().below().equals(pad);
			if (!on) {
				ON_PAD.remove(p.getUUID());
			} else if (ON_PAD.add(p.getUUID())) {
				ride.board(p, List.of(p));
				level.playSound(null, p.blockPosition(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1.0F, 1.2F);
			}
		}
		ride.tick(level);
	}

	static boolean busy() {
		return ride != null && !ride.cars.isEmpty();
	}

	static void reset() {
		if (ride != null) {
			ride.reset();
		}
		ON_PAD.clear();
	}
}
