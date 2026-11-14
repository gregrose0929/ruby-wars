package dev.rubymod.game.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.InteractionAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Everything in the hub that carries you along: the rollercoaster, the water slide, the zipline, the cannon, the launch
 * pads between the islands, the swings and the benches. A ride is a path of points; you sit on an invisible seat that
 * is moved along it a little every tick (with a cart or a raft drawn under you), and get off at the end. Step off
 * (crouch) and you just drop; nothing in the hub can hurt you.
 */
public final class HubRides {
	/** Seats, carts and the like: they only exist while someone's using them (any left in the saved world are cleared). */
	static final String TEMP = "rubymod_park_temp";
	static final Set<UUID> LIVE = new HashSet<>();

	enum Kind {
		COASTER(0.55, 1.0), SLIDE(0.5, 1.0), ZIP(0.5, 1.0), FLIGHT(0, 1.0);

		final double speed;
		final double scale;

		Kind(double speed, double scale) {
			this.speed = speed;
			this.scale = scale;
		}
	}

	private static final class Ride {
		final UUID player;
		final UUID seat;
		final UUID car;
		final List<Vec3> path;
		final Kind kind;
		final Vec3 off;
		double at;
		/** For flights: a fixed number of ticks from start to end. */
		final int ticks;
		int t;

		Ride(UUID player, UUID seat, UUID car, List<Vec3> path, Kind kind, Vec3 off, int ticks) {
			this.player = player;
			this.seat = seat;
			this.car = car;
			this.path = path;
			this.kind = kind;
			this.off = off;
			this.ticks = ticks;
		}
	}

	private static final Map<UUID, Ride> RIDES = new HashMap<>();

	private HubRides() {
	}

	static boolean riding(ServerPlayer p) {
		return RIDES.containsKey(p.getUUID());
	}

	static boolean stale(Entity e) {
		return e.entityTags().contains(TEMP) && !LIVE.contains(e.getUUID());
	}

	// ----- Paths -----

	/** A smooth curve through the points, sampled every {@code step} blocks. */
	static List<Vec3> spline(List<Vec3> pts, double step) {
		List<Vec3> out = new ArrayList<>();
		for (int i = 0; i < pts.size() - 1; i++) {
			Vec3 p0 = pts.get(Math.max(0, i - 1));
			Vec3 p1 = pts.get(i);
			Vec3 p2 = pts.get(i + 1);
			Vec3 p3 = pts.get(Math.min(pts.size() - 1, i + 2));
			int n = Math.max(2, (int) Math.ceil(p1.distanceTo(p2) / step * 3));
			for (int k = 0; k < n; k++) {
				double t = k / (double) n;
				double t2 = t * t;
				double t3 = t2 * t;
				out.add(p0.scale(-0.5 * t3 + t2 - 0.5 * t).add(p1.scale(1.5 * t3 - 2.5 * t2 + 1)).add(p2.scale(-1.5 * t3 + 2 * t2 + 0.5 * t))
						.add(p3.scale(0.5 * t3 - 0.5 * t2)));
			}
		}
		out.add(pts.get(pts.size() - 1));
		return even(out, step);
	}

	/** The same path, re-spaced so the points are {@code step} apart (so speed is just points per tick). */
	static List<Vec3> even(List<Vec3> pts, double step) {
		List<Vec3> out = new ArrayList<>();
		out.add(pts.get(0));
		double carry = 0;
		for (int i = 1; i < pts.size(); i++) {
			Vec3 a = pts.get(i - 1);
			Vec3 b = pts.get(i);
			double len = a.distanceTo(b);
			double d = step - carry;
			while (d <= len) {
				out.add(a.lerp(b, d / len));
				d += step;
			}
			carry = len - (d - step);
		}
		out.add(pts.get(pts.size() - 1));
		return out;
	}

	/** A thrown arc from one spot to another, peaking {@code height} above the higher end. */
	static List<Vec3> arc(Vec3 from, Vec3 to, double height, int ticks) {
		List<Vec3> out = new ArrayList<>();
		double top = Math.max(from.y, to.y) + height;
		for (int i = 0; i <= ticks; i++) {
			double t = i / (double) ticks;
			// A parabola through both ends with its top at {@code top}.
			double y = from.y + (to.y - from.y) * t + 4 * (top - Math.max(from.y, to.y) + Math.abs(to.y - from.y) * 0.5) * t * (1 - t);
			out.add(new Vec3(from.x + (to.x - from.x) * t, y, from.z + (to.z - from.z) * t));
		}
		return out;
	}

	// ----- Starting and running rides -----

	/** How far above the path the rider sits (the old seat was this tall). */
	private static final double SEAT = 0.3;

	/** Ride along {@code path} (points {@code kind.speed} apart... or, for flights, one per tick). */
	static void start(ServerPlayer p, List<Vec3> path, Kind kind, BlockState car, float carW, float carH, float carL) {
		if (riding(p) || p.isPassenger()) {
			return;
		}
		ServerLevel level = (ServerLevel) p.level();
		Vec3 first = path.get(0);
		// An invisible display entity as the seat: displays send their position every tick and glide between updates,
		// so the ride is smooth (an interaction entity only sends every few ticks, which made rides judder).
		Display.BlockDisplay seat = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (seat == null) {
			return;
		}
		((BlockDisplayAccessor) seat).rubymod$setBlockState(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		((DisplayAccessor) seat).rubymod$setPosRotInterpolationDuration(2);
		seat.snapTo(first.x, first.y + SEAT, first.z, 0, 0);
		level.addFreshEntity(seat);
		seat.addTag(TEMP);
		LIVE.add(seat.getUUID());
		UUID carId = null;
		if (car != null) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d != null) {
				d.snapTo(first.x, first.y, first.z, 0, 0);
				((BlockDisplayAccessor) d).rubymod$setBlockState(car);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-carW / 2, -0.05F, -carL / 2), new Quaternionf(),
						new Vector3f(carW, carH, carL), new Quaternionf()));
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
				level.addFreshEntity(d);
				d.addTag(TEMP);
				LIVE.add(d.getUUID());
				carId = d.getUUID();
			}
		}
		p.startRiding(seat, true, true);
		int ticks = kind == Kind.FLIGHT ? path.size() - 1 : 0;
		RIDES.put(p.getUUID(), new Ride(p.getUUID(), seat.getUUID(), carId, path, kind, Vec3.ZERO, ticks));
	}

	static void tick(ServerLevel level) {
		for (Iterator<Ride> it = RIDES.values().iterator(); it.hasNext();) {
			Ride r = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(r.player);
			Entity seat = level.getEntity(r.seat);
			boolean still = p != null && p.level() == level && seat != null && p.getVehicle() == seat;
			if (!still) {
				finish(level, r, p, false);
				it.remove();
				continue;
			}
			int i;
			if (r.kind == Kind.FLIGHT) {
				i = Math.min(r.path.size() - 1, ++r.t);
			} else {
				// Faster going downhill, slower climbing: roughly how a coaster feels.
				int here = Math.min(r.path.size() - 1, (int) r.at);
				int ahead = Math.min(r.path.size() - 1, here + 4);
				double slope = r.path.get(here).y - r.path.get(ahead).y;
				r.at += Math.max(0.6, Math.min(2.4, 1.0 + slope * 0.6));
				i = Math.min(r.path.size() - 1, (int) r.at);
			}
			Vec3 pos = r.path.get(i);
			Vec3 next = r.path.get(Math.min(r.path.size() - 1, i + 2));
			float yaw = (float) Math.toDegrees(Math.atan2(-(next.x - pos.x), next.z - pos.z));
			seat.teleportTo(pos.x, pos.y + SEAT, pos.z);
			Entity car = r.car == null ? null : level.getEntity(r.car);
			if (car != null) {
				car.teleportTo(pos.x, pos.y, pos.z);
				car.setYRot(yaw);
			}
			p.resetFallDistance();
			if (r.kind == Kind.SLIDE && level.getGameTime() % 2 == 0) {
				level.sendParticles(ParticleTypes.SPLASH, pos.x, pos.y + 0.2, pos.z, 6, 0.3, 0.1, 0.3, 0.1);
			}
			if (r.kind == Kind.FLIGHT && level.getGameTime() % 2 == 0) {
				level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 1, 0.1, 0.1, 0.1, 0.01);
			}
			if (i >= r.path.size() - 1) {
				finish(level, r, p, true);
				it.remove();
			}
		}
		tickSwings(level);
		tickBenches(level);
	}

	private static void finish(ServerLevel level, Ride r, ServerPlayer p, boolean arrived) {
		for (UUID id : new UUID[] {r.seat, r.car}) {
			Entity e = id == null ? null : level.getEntity(id);
			if (e != null) {
				e.discard();
			}
			LIVE.remove(id);
		}
		if (p != null && p.level() == level) {
			p.stopRiding();
			if (arrived) {
				Vec3 end = r.path.get(r.path.size() - 1);
				p.teleportTo(end.x, end.y + 0.1, end.z);
				p.resetFallDistance();
				if (r.kind == Kind.SLIDE || HubPark.water(level, BlockPos.containing(end))) {
					level.playSound(null, BlockPos.containing(end), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.2F, 1.0F);
					level.sendParticles(ParticleTypes.SPLASH, end.x, end.y + 0.5, end.z, 60, 1, 0.4, 1, 0.3);
				}
			}
			HubPark.landed(p);
		}
	}

	// ----- Swings -----

	private static final class Swing {
		final Vec3 pivot;
		final double length;
		UUID seat;
		UUID handle;
		final List<UUID> ropes = new ArrayList<>();
		UUID board;
		double phase;
		double swing;

		Swing(Vec3 pivot, double length) {
			this.pivot = pivot;
			this.length = length;
		}
	}

	private static final List<Swing> SWINGS = new ArrayList<>();

	/** A swing hanging from {@code pivot}: two ropes and a board you right-click to sit on. */
	static void swing(ServerLevel level, Vec3 pivot, double length) {
		Swing s = new Swing(pivot, length);
		// What you right-click (an interaction box at the board), and what you sit on (an invisible display, which
		// moves smoothly).
		Interaction handle = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
		Display.BlockDisplay seat = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (handle == null || seat == null) {
			return;
		}
		((InteractionAccessor) handle).rubymod$setWidth(1.0F);
		((InteractionAccessor) handle).rubymod$setHeight(0.6F);
		handle.snapTo(pivot.x, pivot.y - length - 0.2, pivot.z, 0, 0);
		level.addFreshEntity(handle);
		handle.addTag(HubPark.TAG);
		HubPark.keep(handle);
		s.handle = handle.getUUID();
		((BlockDisplayAccessor) seat).rubymod$setBlockState(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		((DisplayAccessor) seat).rubymod$setPosRotInterpolationDuration(2);
		seat.snapTo(pivot.x, pivot.y - length, pivot.z, 0, 0);
		level.addFreshEntity(seat);
		seat.addTag(HubPark.TAG);
		HubPark.keep(seat);
		s.seat = seat.getUUID();
		for (double dx : new double[] {-0.45, 0.45}) {
			Display.BlockDisplay rope = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (rope != null) {
				rope.snapTo(pivot.x + dx, pivot.y, pivot.z, 0, 0);
				((BlockDisplayAccessor) rope).rubymod$setBlockState(net.minecraft.world.level.block.Blocks.IRON_CHAIN.defaultBlockState());
				level.addFreshEntity(rope);
				rope.addTag(HubPark.TAG);
				HubPark.keep(rope);
				s.ropes.add(rope.getUUID());
			}
		}
		Display.BlockDisplay board = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (board != null) {
			board.snapTo(pivot.x, pivot.y, pivot.z, 0, 0);
			((BlockDisplayAccessor) board).rubymod$setBlockState(net.minecraft.world.level.block.Blocks.DARK_OAK_PLANKS.defaultBlockState());
			level.addFreshEntity(board);
			board.addTag(HubPark.TAG);
			HubPark.keep(board);
			s.board = board.getUUID();
		}
		SWINGS.add(s);
		place(level, s, 0);
	}

	static void clearSwings() {
		SWINGS.clear();
	}

	/** Right-clicking a swing's board: sit on it (if it's free). */
	static boolean mountSwing(ServerPlayer p, Entity clicked) {
		for (Swing s : SWINGS) {
			if (clicked.getUUID().equals(s.handle) || clicked.getUUID().equals(s.seat)) {
				Entity seat = ((ServerLevel) p.level()).getEntity(s.seat);
				if (seat == null || seat.isVehicle() || p.isPassenger()) {
					return false;
				}
				return p.startRiding(seat, true, true);
			}
		}
		return false;
	}

	private static void tickSwings(ServerLevel level) {
		for (Swing s : SWINGS) {
			Entity seat = level.getEntity(s.seat);
			if (seat == null) {
				continue;
			}
			boolean used = seat.isVehicle();
			// Someone on it: it swings higher and higher (up to 50 degrees); nobody: it slows to a stop.
			s.swing = used ? Math.min(50, s.swing + 0.35) : Math.max(0, s.swing - 0.8);
			if (s.swing <= 0 && !used) {
				continue;
			}
			s.phase += 0.075;
			place(level, s, Math.toRadians(s.swing * Math.sin(s.phase)));
			for (Entity rider : seat.getPassengers()) {
				rider.resetFallDistance();
			}
		}
	}

	private static void place(ServerLevel level, Swing s, double angle) {
		Entity seat = level.getEntity(s.seat);
		double y = -s.length * Math.cos(angle);
		double z = -s.length * Math.sin(angle);
		if (seat != null) {
			seat.teleportTo(s.pivot.x, s.pivot.y + y + 0.1, s.pivot.z + z);
		}
		Entity handle = level.getEntity(s.handle);
		if (handle != null) {
			handle.teleportTo(s.pivot.x, s.pivot.y + y - 0.2, s.pivot.z + z);
		}
		Quaternionf tilt = new Quaternionf().rotateX((float) angle);
		Quaternionf down = new Quaternionf().rotateX((float) Math.PI);
		for (UUID id : s.ropes) {
			if (level.getEntity(id) instanceof Display d) {
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), tilt, new Vector3f(0.12F, (float) s.length, 0.12F), down));
			}
		}
		if (level.getEntity(s.board) instanceof Display d) {
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
			// The board hangs at the ropes' ends, tilting with them (centred there: the box's middle is moved onto the end).
			Vector3f half = tilt.transform(new Vector3f(0.6F, 0.06F, 0.25F));
			Vector3f at = new Vector3f(0, (float) y, (float) z).sub(half);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(at, tilt, new Vector3f(1.2F, 0.12F, 0.5F), new Quaternionf()));
		}
	}

	// ----- Benches: right-click any stairs in the hub to sit down -----

	private static final List<UUID> BENCH_SEATS = new ArrayList<>();

	static void sit(ServerPlayer p, BlockPos stairs) {
		if (riding(p) || p.isPassenger()) {
			return;
		}
		ServerLevel level = (ServerLevel) p.level();
		Interaction seat = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
		if (seat == null) {
			return;
		}
		((InteractionAccessor) seat).rubymod$setWidth(0.1F);
		((InteractionAccessor) seat).rubymod$setHeight(0.0F);
		seat.snapTo(stairs.getX() + 0.5, stairs.getY() + 0.35, stairs.getZ() + 0.5, 0, 0);
		level.addFreshEntity(seat);
		seat.addTag(TEMP);
		LIVE.add(seat.getUUID());
		BENCH_SEATS.add(seat.getUUID());
		p.startRiding(seat, true, true);
	}

	private static void tickBenches(ServerLevel level) {
		for (Iterator<UUID> it = BENCH_SEATS.iterator(); it.hasNext();) {
			UUID id = it.next();
			Entity seat = level.getEntity(id);
			if (seat == null || !seat.isVehicle()) {
				if (seat != null) {
					seat.discard();
				}
				LIVE.remove(id);
				it.remove();
			}
		}
	}

	/** Everything temporary goes (the server's stopping, or the hub's being rebuilt). */
	static void reset() {
		RIDES.clear();
		BENCH_SEATS.clear();
		LIVE.clear();
		SWINGS.clear();
	}
}
