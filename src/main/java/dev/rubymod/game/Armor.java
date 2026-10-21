package dev.rubymod.game;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModDamage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Damage against vehicles and aircraft. Ordinary explosions and bullets barely
 * scratch armor; weapons made for it (rockets, shells, missiles, bombs, mines,
 * cannon rounds) deal a fixed "anti-armor" amount to vehicles they hit or burst
 * next to. Tanks take 1.5x from behind and from above.
 *
 * Reference amounts (tank = 160 HP): rocket 48, tank shell 56, mortar 40,
 * anti-tank missile 64 (x1.5 top attack), guided missile 75, bomb 90,
 * landmine 80, anti-tank mine 130, cruise missile 250.
 */
public final class Armor {
	/** How far from a vehicle's hull a burst still hurts it. */
	private static final double REACH = 2.0;

	private Armor() {
	}

	public static boolean isArmored(Entity e) {
		return e instanceof VehicleEntity || e instanceof AircraftEntity;
	}

	/** An anti-armor burst at {@code at} (travelling along {@code travel}, may be null). */
	public static void blast(ServerLevel level, Entity direct, Entity owner, Vec3 at, float damage, Vec3 travel) {
		if (damage <= 0) {
			return;
		}
		for (Entity e : level.getEntities((Entity) null, new AABB(at, at).inflate(REACH + 4), Armor::isArmored)) {
			double dist = distance(e.getBoundingBox(), at);
			if (dist > REACH) {
				continue;
			}
			float falloff = dist <= 0.75 ? 1.0F : (float) (1.0 - (dist - 0.75) / (REACH - 0.75) * 0.6);
			hit(level, e, direct, owner, damage * falloff * weakSpot(e, at, travel));
		}
	}

	/** Deal anti-armor damage to one vehicle, and tell the shooter how it went. */
	public static void hit(ServerLevel level, Entity target, Entity direct, Entity owner, float amount) {
		if (!(target instanceof LivingEntity living) || !living.isAlive()) {
			return;
		}
		living.setInvulnerableTime(0);
		boolean hurt = living.hurtServer(level, ModDamage.source(level, ModDamage.ANTI_ARMOR, direct, owner), amount);
		if (hurt && owner instanceof ServerPlayer shooter && !target.hasPassenger(shooter)) {
			int left = Math.max(0, Math.round(living.getHealth() / living.getMaxHealth() * 100));
			boolean destroyed = !living.isAlive();
			Net.send(shooter, new Payloads.HitConfirm(target.getName().getString(), left, destroyed));
			Net.send(shooter, new Payloads.HitMarker(false, destroyed));
		}
	}

	private static float weakSpot(Entity e, Vec3 at, Vec3 travel) {
		if (!(e instanceof VehicleEntity vehicle) || !vehicle.isTank()) {
			return 1.0F;
		}
		AABB box = e.getBoundingBox();
		boolean top = at.y >= box.maxY - 0.3 || (travel != null && travel.lengthSqr() > 1.0E-4 && travel.normalize().y < -0.6);
		Vec3 fromCenter = at.subtract(box.getCenter()).multiply(1, 0, 1);
		Vec3 forward = Vec3.directionFromRotation(0, vehicle.getYRot());
		boolean rear = fromCenter.lengthSqr() > 0.01 && fromCenter.normalize().dot(forward) < -0.5;
		return top || rear ? 1.5F : 1.0F;
	}

	private static double distance(AABB box, Vec3 p) {
		double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
