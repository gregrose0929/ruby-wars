package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Happy Ghast Sky Tours: a big friendly happy ghast floats you (and up to three friends, on its back) in a slow loop
 * high over the whole park, and back to the plaza. Captain Cloud at the plaza takes you up.
 */
final class SkyTours {
	private SkyTours() {
	}

	private static final DyeColor[] HARNESS = {DyeColor.LIGHT_BLUE, DyeColor.PINK, DyeColor.YELLOW, DyeColor.LIME};
	private static TrackRide tour;
	/** Each tour car's ghast. */
	private static final Map<TrackRide.Car, UUID> GHASTS = new HashMap<>();
	private static int made;

	static void reset() {
		if (tour != null) {
			tour.reset();
		}
		GHASTS.clear();
	}

	static void build(ServerLevel level) {
		GHASTS.clear();
		double cx = 5;
		double cz = -15;
		double r = 82;
		double y = FLOOR + 70;
		List<Vec3> pts = new ArrayList<>();
		// Up from over the plaza, once round the park (gently up and down), and back down over the plaza.
		pts.add(new Vec3(-4, FLOOR + 30, 14));
		pts.add(new Vec3(-4, FLOOR + 50, 40));
		int n = 28;
		for (int i = 0; i <= n; i++) {
			double a = Math.PI / 2 + i * 2 * Math.PI / n;
			pts.add(new Vec3(cx + Math.cos(a) * r, y + Math.sin(i * 0.9) * 4, cz + Math.sin(a) * r));
		}
		pts.add(new Vec3(-4, FLOOR + 50, 40));
		pts.add(new Vec3(-4, FLOOR + 30, 14));
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		for (int i = 0; i < speed.length; i++) {
			// Easing off at either end.
			double ends = Math.min(i, speed.length - 1 - i);
			speed[i] = Math.min(0.28, 0.06 + ends * 0.002);
		}
		tour = new TrackRide("skytour", path, speed, "empty", 4.0F, new Vec3(-2.5, FLOOR + 1, 12.5), "Captain Cloud");
		tour.seatsPer = 4;
		tour.upright = true;
		tour.seatHeight = 4.35;
		tour.waitTicks = 80;
		tour.again = "What a view! Talk to Captain Cloud to fly again.";
		tour.events.put(path.size() / 6, (lv, car) -> tour.tell(lv, car, "Look down: there's the Fun Park and the big coaster!"));
		tour.events.put(path.size() * 2 / 6, (lv, car) -> tour.tell(lv, car, "Splash Island and the Waterfall Plunge, down there!"));
		tour.events.put(path.size() * 3 / 6, (lv, car) -> tour.tell(lv, car, "The Speedway: can you see the karts racing?"));
		tour.events.put(path.size() * 4 / 6, (lv, car) -> tour.tell(lv, car, "The Speedway, and Mini Golf over there!"));
		tour.events.put(path.size() * 5 / 6, (lv, car) -> tour.tell(lv, car, "The plaza's just ahead! Nearly home now."));
		HubPark.label(level, -2.5, FLOOR + 3.4, 15.5, "SKY TOURS\nfly on a happy ghast!", ChatFormatting.AQUA, 0.9F);
		HubHosts.add(level, new Vec3(-2.5, FLOOR + 1, 15.5), 180, VillagerProfession.CARTOGRAPHER, VillagerType.SNOW,
				new HubHosts.Host("skytour", "Captain Cloud", "Sky Tours", 0x80D0FF, "minecraft:ghast_tear",
						"Climb aboard a happy ghast for a slow, gentle flight high over the whole park and back! "
								+ "Up to four on its back: bring your friends. (Crouch to hop off: you'll float safely back down to the plaza.)",
						"Sky Tours: right-click Captain Cloud to fly on a happy ghast!", HubHosts.rideButtons("Fly!"), (p, key) -> tour.board(p, HubHosts.group(p, key))));
	}

	static void tick(ServerLevel level) {
		if (tour == null) {
			return;
		}
		tour.tick(level);
		// Every tour car has its ghast underneath, facing the way it's going.
		for (TrackRide.Car c : tour.cars) {
			UUID id = GHASTS.get(c);
			Entity g = id == null ? null : level.getEntity(id);
			if (g == null) {
				Mob m = EntityTypes.HAPPY_GHAST.create(level, EntitySpawnReason.EVENT);
				if (m == null) {
					continue;
				}
				m.setNoAi(true);
				m.setSilent(true);
				m.setPermanentlyInvulnerable(true);
				m.setNoGravity(true);
				m.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.HARNESS.pick(HARNESS[made++ % HARNESS.length])));
				m.snapTo(c.pos.x, c.pos.y, c.pos.z, 0, 0);
				m.addTag(HubRides.TEMP);
				HubPark.live(m);
				level.addFreshEntity(m);
				GHASTS.put(c, m.getUUID());
				g = m;
			}
			Vec3 ahead = tour.at(c.d + 1.0);
			float yaw = (float) Math.toDegrees(Math.atan2(-(ahead.x - c.pos.x), ahead.z - c.pos.z));
			g.teleportTo(c.pos.x, c.pos.y, c.pos.z);
			g.setYRot(yaw);
			g.setYHeadRot(yaw);
			if (g instanceof Mob m) {
				m.setYBodyRot(yaw);
			}
		}
		for (Iterator<Map.Entry<TrackRide.Car, UUID>> it = GHASTS.entrySet().iterator(); it.hasNext();) {
			Map.Entry<TrackRide.Car, UUID> e = it.next();
			if (!tour.cars.contains(e.getKey())) {
				HubPark.discard(level, e.getValue());
				it.remove();
			}
		}
	}

	static boolean busy() {
		return tour != null && !tour.cars.isEmpty();
	}
}
