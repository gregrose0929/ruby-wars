package dev.rubymod.client;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.VehicleEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Client-side vehicle view state: whether the local player is riding one of
 * our vehicles (no hands, no hotbar, vehicle HUD instead), and free-look
 * (hold Alt to look around without steering).
 */
public final class Vehicles {
	public static float freeYaw;
	public static float freePitch;
	public static boolean freeLookHeld;

	private Vehicles() {
	}

	/** The Ruby Wars vehicle the local player is in, or null. */
	public static Entity vehicle(Minecraft mc) {
		if (mc.player == null) {
			return null;
		}
		Entity v = mc.player.getVehicle();
		return v instanceof VehicleEntity || v instanceof AircraftEntity || v instanceof dev.rubymod.entity.HeavyAAGunEntity ? v : null;
	}

	public static boolean inVehicle(Minecraft mc) {
		return vehicle(mc) != null;
	}

	/** Is free-look taking the mouse right now? */
	public static boolean freeLooking(Minecraft mc) {
		return freeLookHeld && inVehicle(mc);
	}

	/** Mouse movement while free-looking turns the camera only. */
	public static void turn(double dx, double dy) {
		freeYaw = net.minecraft.util.Mth.clamp(freeYaw + (float) dx * 0.15F, -170.0F, 170.0F);
		freePitch = net.minecraft.util.Mth.clamp(freePitch + (float) dy * 0.15F, -80.0F, 80.0F);
	}

	/** Called every client tick: ease the view back forward once Alt is released. */
	public static void tick(Minecraft mc, boolean held) {
		freeLookHeld = held;
		if (!freeLooking(mc)) {
			freeYaw *= 0.5F;
			freePitch *= 0.5F;
			if (Math.abs(freeYaw) < 0.1F && Math.abs(freePitch) < 0.1F) {
				freeYaw = 0;
				freePitch = 0;
			}
		}
	}
}
