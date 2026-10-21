package dev.rubymod.client;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType.Weapon;
import dev.rubymod.entity.RocketEntity;
import dev.rubymod.entity.VehicleEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The pilot's fire-control computer, run on the client each tick:
 * - missile lock: the target nearest the center of view inside the seeker cone,
 *   locked after it stays there for a moment;
 * - guided-missile aim point: where the view ray meets the ground (missiles fired
 *   without a lock fly there);
 * - bomb CCIP: where a bomb released now would land (simulated fall);
 * - incoming missile warning.
 */
public final class Targeting {
	private static final double SEEKER_CONE = 12.0;
	private static final int AAM_LOCK_TICKS = 15;
	private static final int AGM_LOCK_TICKS = 20;

	public static Entity candidate;
	public static int lockTicks;
	public static Vec3 aimPoint;
	public static Vec3 bombImpact;
	public static boolean missileIncoming;

	private Targeting() {
	}

	public static AircraftEntity piloting(Minecraft mc) {
		return mc.player != null && mc.player.getVehicle() instanceof AircraftEntity aircraft && aircraft.getControllingPassenger() == mc.player
				? aircraft : null;
	}

	public static int lockTime(Weapon weapon) {
		return weapon == Weapon.AAM ? AAM_LOCK_TICKS : AGM_LOCK_TICKS;
	}

	public static boolean locked(Weapon weapon) {
		return candidate != null && lockTicks >= lockTime(weapon);
	}

	public static void tick(Minecraft mc) {
		AircraftEntity aircraft = piloting(mc);
		if (aircraft == null) {
			candidate = null;
			lockTicks = 0;
			aimPoint = null;
			bombImpact = null;
			missileIncoming = false;
			return;
		}
		Weapon weapon = aircraft.selectedWeapon();
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getLookAngle();

		// Seeker.
		Entity best = null;
		if (weapon == Weapon.AAM || weapon == Weapon.AGM) {
			double range = weapon == Weapon.AAM ? 320 : 240;
			double bestAngle = SEEKER_CONE;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (!seekable(e, aircraft, mc.player, weapon) || e.distanceTo(aircraft) > range) {
					continue;
				}
				Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
				double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
				if (angle < bestAngle) {
					bestAngle = angle;
					best = e;
				}
			}
		}
		if (best != null && best == candidate) {
			lockTicks++;
		} else {
			candidate = best;
			lockTicks = 0;
		}

		// Laser point where the pilot is looking (guided missiles fly here without a lock).
		Vec3 end = eye.add(look.scale(400));
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
		aimPoint = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : null;

		bombImpact = weapon == Weapon.BOMBS ? simulateBomb(mc, aircraft) : null;

		missileIncoming = false;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof RocketEntity rocket && (rocket.targetId() == aircraft.getId() || rocket.targetId() == mc.player.getId())) {
				missileIncoming = true;
				break;
			}
		}
	}

	private static boolean seekable(Entity e, AircraftEntity own, Player self, Weapon weapon) {
		if (e == own || e == self || !e.isAlive() || e.isSpectator() || own.hasPassenger(e)) {
			return false;
		}
		if (e.getVehicle() != null) {
			return false; // Lock the vehicle, not the person in it.
		}
		if (weapon == Weapon.AAM) {
			// Heat seekers: aircraft and anything else in the air.
			return e instanceof AircraftEntity a ? !a.onGround() : e instanceof LivingEntity living && !living.onGround() && !(e instanceof Player);
		}
		return e instanceof VehicleEntity || (e instanceof AircraftEntity a && a.onGround()) || (e instanceof LivingEntity living && living.onGround());
	}

	/** Where a bomb dropped right now would hit (same physics as the bomb: gravity 0.08, drag 0.99). */
	private static Vec3 simulateBomb(Minecraft mc, AircraftEntity aircraft) {
		Vec3 pos = aircraft.position().add(0, -1.2, 0);
		Vec3 velocity = aircraft.getDeltaMovement().add(0, -0.1, 0);
		for (int i = 0; i < 400; i++) {
			velocity = velocity.add(0, -0.08, 0);
			Vec3 next = pos.add(velocity);
			BlockHitResult hit = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
			if (hit.getType() == HitResult.Type.BLOCK) {
				return hit.getLocation();
			}
			pos = next;
			velocity = velocity.scale(0.99);
			if (pos.y < mc.level.getMinY()) {
				return null;
			}
		}
		return null;
	}
}
