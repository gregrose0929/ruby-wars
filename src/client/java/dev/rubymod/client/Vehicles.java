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
	/** Aircraft autopilot: holds heading and altitude so the pilot can look around and aim. */
	public static boolean autopilot;
	public static double autopilotAltitude;
	public static float autopilotHeading;

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
		return (freeLookHeld || autopilot) && inVehicle(mc);
	}

	/** Toggle the autopilot (aircraft only): lock the current heading and altitude. */
	public static void toggleAutopilot(Minecraft mc) {
		AircraftEntity aircraft = Targeting.piloting(mc);
		if (aircraft == null || aircraft.onGround()) {
			autopilot = false;
			return;
		}
		autopilot = !autopilot;
		if (autopilot) {
			autopilotAltitude = aircraft.getY();
			autopilotHeading = aircraft.getYRot();
		} else {
			// Hand the stick back pointing where the plane is going, so it doesn't lurch.
			mc.player.setYRot(aircraft.getYRot());
			mc.player.setXRot(aircraft.getXRot());
			freeYaw = 0;
			freePitch = 0;
		}
		aircraft.setAutopilot(mc.player, autopilot, autopilotHeading, autopilotAltitude);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new dev.rubymod.net.MorePayloads.Autopilot(autopilot, autopilotHeading, (float) autopilotAltitude));
		mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 0.5F, autopilot ? 1.6F : 1.0F);
	}

	/** Where the pilot's camera is actually pointing (their steering view plus any free-look offset). */
	public static net.minecraft.world.phys.Vec3 viewLook(Minecraft mc) {
		float yaw = mc.player.getYRot() + freeYaw;
		float pitch = net.minecraft.util.Mth.clamp(mc.player.getXRot() + freePitch, -90.0F, 90.0F);
		return net.minecraft.world.phys.Vec3.directionFromRotation(pitch, yaw);
	}

	/** Mouse movement while free-looking turns the camera only. */
	public static void turn(double dx, double dy) {
		freeYaw = net.minecraft.util.Mth.clamp(freeYaw + (float) dx * 0.15F, -170.0F, 170.0F);
		// On autopilot you can look straight down at what you're bombing.
		freePitch = net.minecraft.util.Mth.clamp(freePitch + (float) dy * 0.15F, -80.0F, autopilot ? 89.0F : 80.0F);
	}

	/** Called every client tick: ease the view back forward once Alt is released. */
	public static void tick(Minecraft mc, boolean held) {
		freeLookHeld = held;
		AircraftEntity aircraft = Targeting.piloting(mc);
		if (autopilot && (aircraft == null || aircraft.onGround())) {
			autopilot = false;
		}
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
