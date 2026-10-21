package dev.rubymod.client;

import dev.rubymod.item.LockOnLauncherItem;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The seeker of a shoulder-fired lock-on launcher (Skyhammer, Lancer): while
 * aiming (right-click held), the target nearest the crosshair inside the seeker
 * circle builds a lock, with a rising tone, then a steady tone once locked.
 * Also draws the launcher's sight.
 */
public class LauncherSeeker implements HudElement {
	private static final double CONE = 8.0;
	private static final int LOCK_TICKS = 25;

	public static Entity candidate;
	public static int lockTicks;

	public static LockOnLauncherItem.Kind held(Minecraft mc) {
		if (mc.player == null || mc.player.getVehicle() != null) {
			return null;
		}
		return mc.player.getMainHandItem().getItem() instanceof LockOnLauncherItem launcher ? launcher.kind() : null;
	}

	public static boolean locked() {
		return candidate != null && lockTicks >= LOCK_TICKS;
	}

	public static void tick(Minecraft mc) {
		LockOnLauncherItem.Kind kind = held(mc);
		boolean aiming = kind != null && mc.player.isUsingItem();
		if (!aiming) {
			candidate = null;
			lockTicks = 0;
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getLookAngle();
		Entity best = null;
		double bestAngle = CONE;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!LockOnLauncherItem.canLock(kind, e, mc.player) || e.distanceTo(mc.player) > LockOnLauncherItem.range(kind)) {
				continue;
			}
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
			if (angle < bestAngle) {
				bestAngle = angle;
				best = e;
			}
		}
		if (best != null && best == candidate) {
			lockTicks++;
		} else {
			candidate = best;
			lockTicks = 0;
		}
		// Lock tone: beeps that speed up, then a steady high tone.
		if (candidate != null) {
			int period = locked() ? 2 : Math.max(3, 10 - lockTicks / 3);
			if (mc.player.tickCount % period == 0) {
				mc.player.playSound(SoundEvents.NOTE_BLOCK_BIT.value(), 0.35F, locked() ? 2.0F : 1.0F + lockTicks / 50.0F);
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LockOnLauncherItem.Kind kind = held(mc);
		if (kind == null || !mc.player.isUsingItem()) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		Camera camera = mc.gameRenderer.mainCamera();
		int green = 0xFF50FF70;
		double scale = (h / 2.0) / Math.tan(Math.toRadians(camera.getFov() / 2.0));
		int r = (int) (scale * Math.tan(Math.toRadians(CONE)));
		for (int i = 0; i < 48; i++) {
			double a = i * Math.PI * 2 / 48;
			int x = w / 2 + (int) Math.round(Math.cos(a) * r);
			int y = h / 2 + (int) Math.round(Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, 0xA050FF70);
		}
		String title = kind == LockOnLauncherItem.Kind.ANTI_AIR ? "SKYHAMMER  AA" : "LANCER  AT - TOP ATTACK";
		g.text(font, title, w / 2 - font.width(title) / 2, h / 2 - r - 14, green, false);
		if (candidate == null) {
			String s = kind == LockOnLauncherItem.Kind.ANTI_AIR ? "Put an aircraft in the circle" : "Put a vehicle or target in the circle";
			g.text(font, s, w / 2 - font.width(s) / 2, h / 2 + r + 6, 0xA050FF70, false);
			return;
		}
		Vec3 rel = candidate.getBoundingBox().getCenter().subtract(camera.position());
		var f = camera.forwardVector();
		var u = camera.upVector();
		var l = camera.leftVector();
		double z = rel.x * f.x() + rel.y * f.y() + rel.z * f.z();
		if (z < 0.1) {
			return;
		}
		int x = (int) (w / 2.0 - (rel.x * l.x() + rel.y * l.y() + rel.z * l.z()) / z * scale);
		int y = (int) (h / 2.0 - (rel.x * u.x() + rel.y * u.y() + rel.z * u.z()) / z * scale);
		float progress = Math.min(1.0F, lockTicks / (float) LOCK_TICKS);
		int size = locked() ? 8 : (int) (20 - 12 * progress);
		int col = locked() ? 0xFFFF4040 : 0xFFFFC040;
		g.outline(x - size, y - size, size * 2, size * 2, col);
		String d = Math.round(candidate.distanceTo(mc.player)) + "m";
		g.text(font, (locked() ? "LOCKED " : "LOCKING ") + d, x + size + 3, y - 4, col, false);
	}
}
