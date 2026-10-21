package dev.rubymod.client;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType.Weapon;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.gun.GunItem;
import dev.rubymod.item.RocketLauncherItem;
import dev.rubymod.net.Payloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Turns the attack button into a trigger for guns, launchers, and vehicle weapons. */
public final class Trigger {
	private static boolean wasDown;

	private Trigger() {
	}

	private static boolean wasJumping;

	private static boolean drivingArmedVehicle(Minecraft mc) {
		if (mc.player == null) {
			return false;
		}
		if (Targeting.piloting(mc) != null) {
			return true;
		}
		return mc.player.getVehicle() instanceof VehicleEntity vehicle && vehicle.getControllingPassenger() == mc.player && vehicle.hasWeapon();
	}

	/** Should left-click be taken away from vanilla (no punching, no mining)? */
	public static boolean capturesAttack(Minecraft mc) {
		if (mc.player == null) {
			return false;
		}
		ItemStack held = mc.player.getMainHandItem();
		return held.getItem() instanceof GunItem || held.getItem() instanceof RocketLauncherItem
				|| held.getItem() instanceof dev.rubymod.item.LockOnLauncherItem || drivingArmedVehicle(mc);
	}

	/** Send the fire request with the seeker's lock (if locked) or the marked ground point. */
	public static void fireAircraft(Weapon weapon) {
		int target = weapon != null && Targeting.locked(weapon) ? Targeting.candidate.id() : -1;
		var point = Designation.point != null ? Designation.point : Targeting.aimPoint;
		ClientPlayNetworking.send(new Payloads.AircraftFire(target, point != null, point != null ? point.x : 0, point != null ? point.y : 0,
				point != null ? point.z : 0));
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		boolean jumping = mc.player != null && mc.gui.screen() == null && mc.options.keyJump.isDown();
		if (jumping && !wasJumping && Targeting.piloting(mc) != null) {
			ClientPlayNetworking.send(new Payloads.AircraftFlares());
		} else if (jumping && !wasJumping && mc.player.getVehicle() instanceof VehicleEntity tank && tank.isTank()
				&& tank.getControllingPassenger() == mc.player) {
			ClientPlayNetworking.send(new Payloads.VehicleSmoke());
		}
		wasJumping = jumping;
		boolean down = mc.player != null && mc.gui.screen() == null && mc.options.keyAttack.isDown();
		boolean pressed = down && !wasDown;
		wasDown = down;
		if (!down || mc.player == null) {
			return;
		}
		AircraftEntity aircraft = Targeting.piloting(mc);
		if (aircraft != null) {
			// The cannon fires while held (the server sets the rate); missiles and bombs once per click.
			Weapon weapon = aircraft.selectedWeapon();
			if (weapon == Weapon.CANNON || pressed) {
				fireAircraft(weapon);
			}
			return;
		}
		if (drivingArmedVehicle(mc)) {
			ClientPlayNetworking.send(new Payloads.VehicleFire());
			return;
		}
		ItemStack held = mc.player.getMainHandItem();
		if (held.getItem() instanceof GunItem gun) {
			// Automatic guns fire while held (the server enforces the fire rate); others fire once per click.
			if (gun.stats(held).automatic() || pressed) {
				ClientPlayNetworking.send(new Payloads.Fire());
			}
		} else if (held.getItem() instanceof RocketLauncherItem && pressed) {
			ClientPlayNetworking.send(new Payloads.Fire());
		} else if (held.getItem() instanceof dev.rubymod.item.LockOnLauncherItem && pressed) {
			ClientPlayNetworking.send(new Payloads.LauncherFire(LauncherSeeker.locked() ? LauncherSeeker.candidate.getId() : -1));
		}
	}
}
