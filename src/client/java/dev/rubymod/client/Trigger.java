package dev.rubymod.client;

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

	private static boolean drivingArmedVehicle(Minecraft mc) {
		return mc.player != null && mc.player.getVehicle() instanceof VehicleEntity vehicle && vehicle.getControllingPassenger() == mc.player
				&& vehicle.hasWeapon();
	}

	/** Should left-click be taken away from vanilla (no punching, no mining)? */
	public static boolean capturesAttack(Minecraft mc) {
		if (mc.player == null) {
			return false;
		}
		ItemStack held = mc.player.getMainHandItem();
		return held.getItem() instanceof GunItem || held.getItem() instanceof RocketLauncherItem || drivingArmedVehicle(mc);
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		boolean down = mc.player != null && mc.gui.screen() == null && mc.options.keyAttack.isDown();
		boolean pressed = down && !wasDown;
		wasDown = down;
		if (!down || mc.player == null) {
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
		}
	}
}
