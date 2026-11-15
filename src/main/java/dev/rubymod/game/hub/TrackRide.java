package dev.rubymod.game.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A ride where cars (one of the park's models, two seats each) run along a fixed path from a station and back: the
 * Ghost Train and the log flume. A car waits a few seconds at the station for friends, then sets off; things can
 * happen as it passes points along the way (a ghost pops out, a big splash). Riders get off at the exit.
 */
final class TrackRide {
	static final double STEP = 0.25;

	final String name;
	final List<Vec3> path;
	final double[] speed;
	final String model;
	final float size;
	final Vec3 exit;
	final String hostName;
	/** Seats in a car (2, or 4 for the balloon), and how high the seats are above the path. */
	int seatsPer = 2;
	double seatHeight = Double.NaN;
	/** How far up the model sits from the path (by default, half its size: its bottom on the path). */
	double modelUp = Double.NaN;
	/** The most the car tips nose-up or nose-down (degrees). */
	double maxPitch = 90;
	/** Stays level (the balloon) instead of tilting with the path. */
	boolean upright;
	/** Two seats side by side (the doom buggy) instead of one behind the other. */
	boolean sideBySide;
	/** Riders sway from side to side as they go (hanging from the Sky Zip). */
	boolean sway;
	/** Called for each rider as they get off (the Toy Blaster tots up your score). */
	java.util.function.Consumer<ServerPlayer> onLeave;
	/** Called for each rider as they get on. */
	java.util.function.Consumer<ServerPlayer> onBoard;
	/** How long a car waits at the station for friends. */
	int waitTicks = 80;
	/** Things that happen as a car passes a point on the path (keyed by the point's index). */
	final Map<Integer, BiConsumer<ServerLevel, Car>> events = new HashMap<>();

	static final class Car {
		UUID model;
		UUID[] seats = new UUID[2];
		double d;
		int wait = 80;
		final Set<UUID> riders = new LinkedHashSet<>();
		Vec3 pos = Vec3.ZERO;
		/** Something the car turns to face for a while (a scene in the Haunted Mansion). */
		Vec3 face;
		int faceUntil;
	}

	final List<Car> cars = new ArrayList<>();

	/** Every ride built this time (for the headroom check). */
	static final List<TrackRide> ALL = new ArrayList<>();

	TrackRide(String name, List<Vec3> path, double[] speed, String model, float size, Vec3 exit, String hostName) {
		ALL.add(this);
		this.name = name;
		this.path = path;
		this.speed = speed;
		this.model = model;
		this.size = size;
		this.exit = exit;
		this.hostName = hostName;
	}

	double length() {
		return (path.size() - 1) * STEP;
	}

	Vec3 at(double d) {
		double f = Math.max(0, Math.min(path.size() - 1.001, d / STEP));
		int i = (int) Math.floor(f);
		return path.get(i).lerp(path.get(i + 1), f - i);
	}

	/** Get a group on: into the car waiting at the station (a new one if there isn't one, or it's full). */
	void board(ServerPlayer p, List<ServerPlayer> group) {
		ServerLevel level = (ServerLevel) p.level();
		for (ServerPlayer q : group) {
			if (riding(q)) {
				continue;
			}
			Car car = null;
			for (Car c : cars) {
				if (c.wait > 0 && c.riders.size() < seatsPer) {
					car = c;
				}
			}
			if (car == null) {
				for (Car c : cars) {
					if (c.d < 4) {
						q.sendOverlayMessage(Component.literal("The car's full! The next one comes along in a moment.").withStyle(ChatFormatting.YELLOW));
						car = null;
						return;
					}
				}
				car = spawn(level);
				if (car == null) {
					return;
				}
			}
			if (q.isPassenger()) {
				q.stopRiding();
			}
			Entity seat = level.getEntity(car.seats[car.riders.size()]);
			if (seat == null) {
				return;
			}
			q.startRiding(seat, true, true);
			HubRides.faceSeat(q, seat);
			car.riders.add(q.getUUID());
			if (onBoard != null) {
				onBoard.accept(q);
			}
			car.wait = Math.max(car.wait, Math.min(50, waitTicks));
			q.sendOverlayMessage(Component.literal("All aboard! (crouch to get off)").withStyle(ChatFormatting.LIGHT_PURPLE));
			level.playSound(null, q.blockPosition(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 0.6F, 1.3F);
		}
	}

	boolean riding(ServerPlayer p) {
		for (Car c : cars) {
			if (c.riders.contains(p.getUUID())) {
				return true;
			}
		}
		return false;
	}

	private Car spawn(ServerLevel level) {
		Car c = new Car();
		c.seats = new UUID[seatsPer];
		c.wait = waitTicks;
		Vec3 p = at(0);
		Display.ItemDisplay m = HubPark.tempModel(level, p, 0, model, size, (float) (Double.isNaN(modelUp) ? size / 2 : modelUp));
		if (m == null) {
			return null;
		}
		c.model = m.getUUID();
		for (int s = 0; s < seatsPer; s++) {
			Display.BlockDisplay seat = HubPark.seat(level, p);
			c.seats[s] = seat == null ? null : seat.getUUID();
		}
		// The car rides along with the first seat, so your game moves them together: the car never slides into you.
		Entity first = c.seats[0] == null ? null : level.getEntity(c.seats[0]);
		if (first != null) {
			m.startRiding(first, true, true);
		}
		cars.add(c);
		place(level, c);
		return c;
	}

	void tick(ServerLevel level) {
		for (Iterator<Car> it = cars.iterator(); it.hasNext();) {
			Car c = it.next();
			// Anyone who hops off goes to the exit.
			for (Iterator<UUID> r = c.riders.iterator(); r.hasNext();) {
				ServerPlayer p = level.getServer().getPlayerList().getPlayer(r.next());
				if (p == null || p.level() != level) {
					r.remove();
					continue;
				}
				if (!onCar(p, c)) {
					r.remove();
					letOff(p, "You hopped off! Talk to " + hostName + " to ride again.");
				}
			}
			if (c.wait > 0) {
				if (c.riders.isEmpty()) {
					remove(level, c);
					it.remove();
					continue;
				}
				if (c.wait % 20 == 0) {
					tell(level, c, "Leaving in " + c.wait / 20 + "...");
				}
				c.wait--;
				place(level, c);
				continue;
			}
			int before = (int) (c.d / STEP);
			c.d += speed[Math.min(speed.length - 1, before)];
			int after = (int) (c.d / STEP);
			for (int i = before + 1; i <= after; i++) {
				BiConsumer<ServerLevel, Car> e = events.get(i);
				if (e != null) {
					e.accept(level, c);
				}
			}
			if (c.d >= length()) {
				for (UUID id : new ArrayList<>(c.riders)) {
					ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
					if (p != null) {
						letOff(p, "Talk to " + hostName + " to ride again!");
					}
				}
				remove(level, c);
				it.remove();
				continue;
			}
			place(level, c);
		}
	}

	/** Turn the car (and its riders' heads) towards {@code target} until the path gets {@code ahead} points further on. */
	void face(ServerLevel level, Car c, Vec3 target, int ahead) {
		c.face = target;
		c.faceUntil = (int) (c.d / STEP) + ahead;
		for (ServerPlayer p : riders(level, c)) {
			// Never look steeply down (you'd just see the inside of the car).
			Vec3 eye = p.getEyePosition();
			double h = Math.hypot(target.x - eye.x, target.z - eye.z);
			double low = eye.y - h * 0.36;
			Vec3 look = target.y < low ? new Vec3(target.x, low, target.z) : target;
			p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, look);
		}
	}

	void tell(ServerLevel level, Car c, String text) {
		for (UUID id : c.riders) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
			}
		}
	}

	List<ServerPlayer> riders(ServerLevel level, Car c) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : c.riders) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				out.add(p);
			}
		}
		return out;
	}

	private boolean onCar(ServerPlayer p, Car c) {
		Entity v = p.getVehicle();
		if (v == null) {
			return false;
		}
		for (UUID id : c.seats) {
			if (v.getUUID().equals(id)) {
				return true;
			}
		}
		return false;
	}

	private void letOff(ServerPlayer p, String message) {
		if (onLeave != null) {
			onLeave.accept(p);
		}
		FunPark.letOff(p, exit, message);
	}

	private void remove(ServerLevel level, Car c) {
		HubPark.discard(level, c.model);
		for (UUID id : c.seats) {
			HubPark.discard(level, id);
		}
	}

	private void place(ServerLevel level, Car c) {
		Vec3 p = at(c.d);
		c.pos = p;
		Vec3 dir = at(c.d + 0.5).subtract(at(Math.max(0, c.d - 0.5)));
		if (dir.lengthSqr() < 1e-6) {
			dir = at(1).subtract(at(0));
		}
		dir = dir.normalize();
		float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
		if (c.face != null && c.d / STEP < c.faceUntil) {
			Vec3 look = c.face.subtract(p);
			yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
		}
		float pitch = upright ? 0 : (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.y))));
		pitch = (float) Math.max(-maxPitch, Math.min(maxPitch, pitch));
		Vec3 seat0 = null;
		Vec3 flat = new Vec3(dir.x, 0, dir.z).normalize();
		Vec3 side = new Vec3(-flat.z, 0, flat.x);
		for (int s = 0; s < seatsPer; s++) {
			Entity seat = c.seats[s] == null ? null : level.getEntity(c.seats[s]);
			if (seat != null) {
				double along = sideBySide ? 0 : (s / (seatsPer > 2 ? 2 : 1)) % 2 == 0 ? 0.35 : -0.45;
				double across = seatsPer > 2 || sideBySide ? (s % 2 == 0 ? -0.45 : 0.45) : 0;
				if (sway) {
					across += Math.sin(c.d * 0.35) * 0.35;
				}
				double up = Double.isNaN(seatHeight) ? 0.55 * size / 2 : seatHeight;
				Vec3 sp = p.add(flat.scale(along * size / 2)).add(side.scale(across * size / 2)).add(0, up + dir.y * along, 0);
				if (c.face != null && c.d / STEP < c.faceUntil) {
					Vec3 fwd = new Vec3(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
					Vec3 sd = new Vec3(-fwd.z, 0, fwd.x);
					sp = p.add(sd.scale(across * size / 2)).add(0, up, 0);
				}
				seat.teleportTo(sp.x, sp.y, sp.z);
				seat.setYRot(yaw);
				if (s == 0) {
					seat0 = sp;
				}
				for (Entity rider : seat.getPassengers()) {
					rider.resetFallDistance();
				}
			}
		}
		Entity m = level.getEntity(c.model);
		if (m instanceof Display d) {
			Entity first = c.seats.length == 0 || c.seats[0] == null ? null : level.getEntity(c.seats[0]);
			if (first != null && m.getVehicle() != first) {
				m.startRiding(first, true, true);
			}
			if (m.getVehicle() == null || seat0 == null) {
				m.teleportTo(p.x, p.y, p.z);
				seat0 = p;
			}
			// The car's turn and tilt, and where its middle is from the seat it rides with.
			org.joml.Quaternionf q = new org.joml.Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch));
			float up = (float) (Double.isNaN(modelUp) ? size / 2 : modelUp);
			org.joml.Vector3f off = q.transform(new org.joml.Vector3f(0, up, 0)).add((float) (p.x - seat0.x), (float) (p.y - seat0.y), (float) (p.z - seat0.z));
			((dev.rubymod.mixin.DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
			((dev.rubymod.mixin.DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
			((dev.rubymod.mixin.DisplayAccessor) d).rubymod$setTransformation(new com.mojang.math.Transformation(off,
					new org.joml.Quaternionf(q).rotateY((float) Math.PI), new org.joml.Vector3f(size, size, size), new org.joml.Quaternionf()));
		}
	}

	/**
	 * Nothing solid near a rider's head anywhere along the way: blocks in the space from just above the seat to well over
	 * your head (and a little to each side) are cleared. Returns how many went.
	 */
	int clearHeadroom(ServerLevel level) {
		double up = Double.isNaN(seatHeight) ? 0.55 * size / 2 : seatHeight;
		double side = seatsPer > 1 || sideBySide ? 0.45 * size / 2 + 0.9 : 0.9;
		int n = 0;
		for (int i = 0; i < path.size(); i += 2) {
			Vec3 p = path.get(i);
			n += HubRides.clearAround(level, p.add(0, up, 0), side, 1.0, 3.0);
		}
		return n;
	}

	void reset() {
		cars.clear();
	}
}
