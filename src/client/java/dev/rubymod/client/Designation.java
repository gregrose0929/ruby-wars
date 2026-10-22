package dev.rubymod.client;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.BombEntity;
import dev.rubymod.entity.RocketEntity;
import dev.rubymod.game.Armor;
import dev.rubymod.game.CannonRounds;
import dev.rubymod.net.Payloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pilot targeting beyond the missile seeker:
 * - T designates what's under the crosshair (a vehicle, aircraft, player or a
 *   ground point, up to 1500 blocks); missiles and bombs go for it;
 * - the cannon's lead-computing sight;
 * - the weapon camera (C): ride along with your last missile or bomb.
 */
public final class Designation {
	private static final double RANGE = 1500;

	public static Contacts.Target entity;
	public static Vec3 point;
	/** Where to put the cannon pipper to hit the lead target (a world direction from the camera), or null. */
	public static Vec3 leadDirection;
	public static Entity leadTarget;

	/** The newest missile or bomb this aircraft launched, for the weapon camera. */
	public static Entity lastMunition;
	public static boolean weaponCam;
	private static int camLinger;
	private static CameraType camTypeBefore;

	private Designation() {
	}

	/** The designated spot as it is now. */
	public static Vec3 target() {
		return entity != null ? entity.pos() : point;
	}

	/** T pressed. */
	public static void designate(Minecraft mc) {
		AircraftEntity aircraft = Targeting.piloting(mc);
		if (aircraft == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getLookAngle();
		Contacts.Target best = null;
		double bestAngle = 2.0;
		java.util.List<Contacts.Target> options = new java.util.ArrayList<>(Contacts.remote(mc));
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == aircraft || e == mc.player || !e.isAlive() || e.getVehicle() != null || aircraft.hasPassenger(e)
					|| !(Armor.isArmored(e) || e instanceof LivingEntity)) {
				continue;
			}
			options.add(Contacts.of(e));
		}
		for (Contacts.Target t : options) {
			if (t.pos().distanceTo(aircraft.position()) > RANGE) {
				continue;
			}
			Vec3 to = t.pos().subtract(eye);
			double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
			if (angle < bestAngle) {
				bestAngle = angle;
				best = t;
			}
		}
		Vec3 ground = null;
		if (best == null) {
			BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(RANGE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
			ground = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : null;
		}
		entity = best;
		point = ground;
		ClientPlayNetworking.send(new Payloads.AircraftDesignate(best != null ? best.id() : -1, ground != null,
				ground != null ? ground.x : 0, ground != null ? ground.y : 0, ground != null ? ground.z : 0));
		mc.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, best != null || ground != null ? 1.8F : 0.8F);
	}

	/** V pressed: ride along with the last munition (or come back). */
	public static void toggleCam(Minecraft mc) {
		if (weaponCam) {
			endCam(mc);
		} else if (Targeting.piloting(mc) != null && lastMunition != null && lastMunition.isAlive()) {
			weaponCam = true;
			camLinger = 0;
			camTypeBefore = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			mc.setCameraEntity(lastMunition);
		}
	}

	private static void endCam(Minecraft mc) {
		weaponCam = false;
		if (mc.player != null) {
			mc.setCameraEntity(mc.player);
		}
		if (camTypeBefore != null) {
			mc.options.setCameraType(camTypeBefore);
		}
	}

	public static void tick(Minecraft mc) {
		AircraftEntity aircraft = Targeting.piloting(mc);
		if (aircraft == null) {
			entity = null;
			point = null;
			leadDirection = null;
			lastMunition = null;
			if (weaponCam) {
				endCam(mc);
			}
			return;
		}
		if (entity != null) {
			Contacts.Target now = Contacts.refresh(mc, entity);
			if (now == null) {
				point = entity.pos();
			}
			entity = now;
		}
		// Remember what we just launched.
		for (Entity e : mc.level.entitiesForRendering()) {
			if ((e instanceof RocketEntity || e instanceof BombEntity) && e.tickCount < 6 && e.distanceTo(aircraft) < 16) {
				lastMunition = e;
			}
		}
		// Weapon camera follows the munition, then lingers a second on the impact.
		if (weaponCam) {
			if (lastMunition == null || !lastMunition.isAlive()) {
				if (++camLinger > 25) {
					endCam(mc);
				}
			}
		}
		computeLead(mc, aircraft);
	}

	/** Lead for the cannon: aim where target and shells meet (shells inherit our velocity). */
	private static void computeLead(Minecraft mc, AircraftEntity aircraft) {
		leadDirection = null;
		leadTarget = null;
		Entity target = entity != null && entity.visible() && entity.pos().distanceTo(aircraft.position()) < 400 ? mc.level.getEntity(entity.id()) : null;
		if (target == null) {
			Vec3 look = aircraft.forward();
			double bestAngle = 10.0;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e == aircraft || e == mc.player || !Armor.isArmored(e) && !(e instanceof LivingEntity) || e.getVehicle() != null
						|| aircraft.hasPassenger(e) || e.distanceTo(aircraft) > 350) {
					continue;
				}
				Vec3 to = e.getBoundingBox().getCenter().subtract(aircraft.gunPosition());
				double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
				if (angle < bestAngle) {
					bestAngle = angle;
					target = e;
				}
			}
		}
		if (target == null) {
			return;
		}
		Vec3 rel = target.getBoundingBox().getCenter().subtract(aircraft.gunPosition());
		Vec3 targetVel = target.position().subtract(target.xo, target.yo, target.zo);
		Vec3 relVel = targetVel.subtract(aircraft.getDeltaMovement());
		double t = rel.length() / CannonRounds.SPEED;
		for (int i = 0; i < 3; i++) {
			t = rel.add(relVel.scale(t)).length() / CannonRounds.SPEED;
		}
		// Shells also drop: aim a little higher.
		Vec3 aim = rel.add(relVel.scale(t)).add(0, 0.5 * CannonRounds.GRAVITY * t * t, 0);
		leadDirection = aim.normalize();
		leadTarget = target;
	}
}
