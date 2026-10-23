package dev.rubymod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** After you die, the camera looks through your killer's eyes for two seconds. */
public final class Killcam {
	private static final int TICKS = 40;
	private static int left;

	private Killcam() {
	}

	public static void start(Minecraft mc, int killerId) {
		if (mc.level == null || mc.player == null || killerId < 0) {
			return;
		}
		Entity killer = mc.level.getEntity(killerId);
		if (killer == null || killer == mc.player || !killer.isAlive()) {
			return;
		}
		mc.setCameraEntity(killer);
		left = TICKS;
	}

	public static void tick(Minecraft mc) {
		if (left <= 0) {
			return;
		}
		Entity camera = mc.getCameraEntity();
		if (--left == 0 || mc.player == null || camera == null || !camera.isAlive() || camera == mc.player) {
			left = 0;
			if (mc.player != null) {
				mc.setCameraEntity(mc.player);
			}
		}
	}

	public static boolean active() {
		return left > 0;
	}
}
