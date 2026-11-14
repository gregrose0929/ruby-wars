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
	/** Stays level (the balloon) instead of tilting with the path. */
	boolean upright;
	/** Things that happen as a car passes a point on the path (keyed by the point's index). */
	final Map<Integer, BiConsumer<ServerLevel, Car>> events = new HashMap<>();

	static final class Car {
		UUID model;
		UUID[] seats = new UUID[2];
		double d;
		int wait = 80;
		final Set<UUID> riders = new LinkedHashSet<>();
		Vec3 pos = Vec3.ZERO;
	}

	final List<Car> cars = new ArrayList<>();

	TrackRide(String name, List<Vec3> path, double[] speed, String model, float size, Vec3 exit, String hostName) {
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
			car.riders.add(q.getUUID());
			car.wait = Math.max(car.wait, 50);
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
		float pitch = upright ? 0 : (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.y))));
		Entity m = level.getEntity(c.model);
		if (m != null) {
			m.teleportTo(p.x, p.y, p.z);
			m.setYRot(yaw);
			m.setXRot(pitch);
		}
		Vec3 flat = new Vec3(dir.x, 0, dir.z).normalize();
		Vec3 side = new Vec3(-flat.z, 0, flat.x);
		for (int s = 0; s < seatsPer; s++) {
			Entity seat = c.seats[s] == null ? null : level.getEntity(c.seats[s]);
			if (seat != null) {
				double along = (s / (seatsPer > 2 ? 2 : 1)) % 2 == 0 ? 0.35 : -0.45;
				double across = seatsPer > 2 ? (s % 2 == 0 ? -0.45 : 0.45) : 0;
				double up = Double.isNaN(seatHeight) ? 0.55 * size / 2 : seatHeight;
				Vec3 sp = p.add(flat.scale(along * size / 2)).add(side.scale(across * size / 2)).add(0, up + dir.y * along, 0);
				seat.teleportTo(sp.x, sp.y, sp.z);
				seat.setYRot(yaw);
				for (Entity rider : seat.getPassengers()) {
					rider.resetFallDistance();
				}
			}
		}
	}

	void reset() {
		cars.clear();
	}
}
