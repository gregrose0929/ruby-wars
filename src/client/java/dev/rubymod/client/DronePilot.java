package dev.rubymod.client;

import java.util.HashMap;
import java.util.Map;

import dev.rubymod.entity.ScoutDroneEntity;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;

/**
 * Flying a Scout Drone: the camera is the drone's, W A S D / Space / Shift fly it (your own legs stay still), the mouse
 * turns it, right-click brings it home. Also the glow on whatever a squad drone spotted.
 */
public final class DronePilot {
	private DronePilot() {
	}

	/** The drone being flown (its entity id), or -1. */
	private static int droneId = -1;
	/** Ticks we've been waiting for the drone to turn up on this side. */
	private static int waiting;
	/** The movement keys this tick (taken from the player, who doesn't move while flying). */
	static Input keys = Input.EMPTY;
	private static boolean recall;
	private static CameraType cameraBefore;
	/** Watching a base's security cameras (not flying a drone). */
	private static boolean camera;
	private static boolean exitSent;
	/** Entity id -> when its glow ends (client ms). */
	private static final Map<Integer, Long> GLOWING = new HashMap<>();

	public static void takeKeys(Input in) {
		keys = in;
	}

	public static boolean flying() {
		return droneId >= 0;
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.DroneView.TYPE, (payload, context) -> view(context.client(), payload.entityId(), payload.camera()));
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.DroneGlow.TYPE, (payload, context) -> {
			long until = System.currentTimeMillis() + payload.seconds() * 1000L;
			for (int id : payload.ids()) {
				GLOWING.put(id, until);
			}
		});
	}

	private static void view(Minecraft mc, int id, boolean cam) {
		if (id < 0) {
			stop(mc);
			return;
		}
		camera = cam;
		exitSent = false;
		droneId = id;
		waiting = 0;
		recall = false;
	}

	private static void stop(Minecraft mc) {
		if (droneId >= 0 && mc.player != null) {
			mc.setCameraEntity(mc.player);
			if (cameraBefore != null) {
				mc.options.setCameraType(cameraBefore);
			}
		}
		droneId = -1;
		cameraBefore = null;
		keys = Input.EMPTY;
	}

	/** Right-click while flying: bring it home (instead of using whatever's in your hand). */
	public static boolean useKey() {
		if (!flying()) {
			return false;
		}
		recall = true;
		return true;
	}

	public static void tick(Minecraft mc) {
		if (!flying()) {
			return;
		}
		if (mc.player == null || mc.level == null) {
			droneId = -1;
			return;
		}
		Entity drone = mc.level.getEntity(droneId);
		if (drone == null || !camera && !(drone instanceof ScoutDroneEntity)) {
			// Not here yet (just launched), or gone without a word: give up after a couple of seconds.
			if (++waiting > 40) {
				stop(mc);
			}
			return;
		}
		if (mc.getCameraEntity() != drone) {
			cameraBefore = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			mc.setCameraEntity(drone);
		}
		if (camera) {
			// Watching cameras: right-click for the next one, crouch to step away.
			if (keys.shift() && !exitSent) {
				exitSent = true;
				ClientPlayNetworking.send(new MorePayloads.CameraInput(false, true));
			} else if (recall) {
				ClientPlayNetworking.send(new MorePayloads.CameraInput(true, false));
			}
			recall = false;
			return;
		}
		float forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		float strafe = (keys.right() ? 1 : 0) - (keys.left() ? 1 : 0);
		float up = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
		ClientPlayNetworking.send(new MorePayloads.DroneInput(forward, strafe, up, mc.player.getYRot(), mc.player.getXRot(), recall));
		recall = false;
	}

	/** Should this entity glow for us (a drone spotted it)? */
	public static boolean glows(Entity e) {
		if (GLOWING.isEmpty()) {
			return false;
		}
		Long until = GLOWING.get(e.getId());
		if (until == null) {
			return false;
		}
		if (until < System.currentTimeMillis()) {
			GLOWING.remove(e.getId());
			return false;
		}
		return true;
	}
}
