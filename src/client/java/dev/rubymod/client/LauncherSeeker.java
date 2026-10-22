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
 * circle builds a lock, with a rising tone, then a fast tone once locked, the same
 * way the aircraft missiles lock. Also draws the launcher's sight.
 */
public class LauncherSeeker implements HudElement {
	private static final double CONE = 12.0;
	/** Ticks a target may sit just outside the circle before the lock starts over. */
	private static final int GRACE = 6;

	public static Contacts.Target candidate;
	public static int lockTicks;
	private static int lostFor;

	public static LockOnLauncherItem.Kind held(Minecraft mc) {
		if (mc.player == null || mc.player.getVehicle() != null) {
			return null;
		}
		return mc.player.getMainHandItem().getItem() instanceof LockOnLauncherItem launcher ? launcher.kind() : null;
	}

	public static boolean locked() {
		return candidate != null && lockTicks >= lockTime(held(Minecraft.getInstance()));
	}

	/** Same lock times as the aircraft seekers: quick for heat-seekers, a little longer for ground targets. */
	public static int lockTime(LockOnLauncherItem.Kind kind) {
		return kind == LockOnLauncherItem.Kind.ANTI_TANK ? 20 : 15;
	}

	/**
	 * Works like the jets' fire-control computer: the target nearest the middle of a
	 * 12-degree circle, including radar contacts beyond render distance, locks after it
	 * stays there briefly. A target that slips just outside the circle for a moment
	 * keeps its lock progress instead of starting over.
	 */
	public static void tick(Minecraft mc) {
		LockOnLauncherItem.Kind kind = held(mc);
		boolean aiming = kind != null && mc.player.isUsingItem();
		if (!aiming) {
			candidate = null;
			lockTicks = 0;
			lostFor = 0;
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getLookAngle();
		java.util.List<Contacts.Target> options = new java.util.ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (LockOnLauncherItem.canLock(kind, e, mc.player)) {
				options.add(Contacts.of(e));
			}
		}
		for (Contacts.Target t : Contacts.remote(mc)) {
			if (t.airborne() == (kind == LockOnLauncherItem.Kind.ANTI_AIR)) {
				options.add(t);
			}
		}
		Contacts.Target best = null;
		double bestAngle = CONE;
		double range = LockOnLauncherItem.range(kind);
		for (Contacts.Target t : options) {
			Vec3 to = t.pos().subtract(eye);
			if (to.length() > range) {
				continue;
			}
			double angle = angle(to, look);
			// Stick with the current target unless another is clearly nearer the middle.
			if (candidate != null && t.id() == candidate.id()) {
				angle -= 2.0;
			}
			if (angle < bestAngle) {
				bestAngle = angle;
				best = t;
			}
		}
		if (best != null && candidate != null && best.id() == candidate.id()) {
			candidate = best;
			lockTicks++;
			lostFor = 0;
		} else if (best == null && candidate != null && ++lostFor <= GRACE) {
			// Briefly outside the circle: hold on to it (moving with the latest contact position).
			candidate = Contacts.refresh(mc, candidate);
		} else {
			candidate = best;
			lockTicks = 0;
			lostFor = 0;
		}
		lockTone(mc, candidate != null, lockTicks, lockTime(kind));
	}

	private static double angle(Vec3 to, Vec3 look) {
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
	}

	/** The lock tone, shared with the aircraft seekers: beeps that speed up as the lock builds, then a fast high tone. */
	public static void lockTone(Minecraft mc, boolean tracking, int ticks, int lockTime) {
		if (!tracking) {
			return;
		}
		boolean locked = ticks >= lockTime;
		int period = locked ? 2 : Math.max(3, 10 - ticks * 7 / Math.max(1, lockTime));
		if (mc.player.tickCount % period == 0) {
			mc.player.playSound(SoundEvents.NOTE_BLOCK_BIT.value(), 0.35F, locked ? 2.0F : 1.0F + ticks / (float) lockTime * 0.5F);
		}
	}

	/** The seeker's optic: a clear round view with soft dark edges, range ticks and a fine crosshair. */
	private static void drawSightFrame(GuiGraphicsExtractor g, int w, int h, int r, int green) {
		int radius = Math.min(w, h) * 9 / 20;
		int cx = w / 2;
		int cy = h / 2;
		// Darken everything outside the lens, row by row (a circle cut from black bars).
		for (int y = 0; y < h; y++) {
			int dy = y - cy;
			if (Math.abs(dy) >= radius) {
				g.fill(0, y, w, y + 1, 0xE0000000);
				continue;
			}
			int half = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
			g.fill(0, y, cx - half, y + 1, 0xE0000000);
			g.fill(cx + half, y, w, y + 1, 0xE0000000);
			// A thin soft rim inside the lens.
			g.fill(cx - half, y, cx - half + 3, y + 1, 0x60000000);
			g.fill(cx + half - 3, y, cx + half, y + 1, 0x60000000);
		}
		int fine = 0x9050FF70;
		g.fill(cx - radius + 6, cy, cx - r - 4, cy + 1, fine);
		g.fill(cx + r + 4, cy, cx + radius - 6, cy + 1, fine);
		g.fill(cx, cy + r + 4, cx + 1, cy + radius - 6, fine);
		for (int i = 1; i <= 4; i++) {
			int ty = cy + r + 4 + i * (radius - r - 10) / 5;
			g.fill(cx - 3, ty, cx + 4, ty + 1, fine);
		}
		g.fill(cx - 1, cy, cx + 2, cy + 1, green);
		g.fill(cx, cy - 1, cx + 1, cy + 2, green);
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
		if (dev.rubymod.client.gun.GunView.adsProgress() > 0.55F) {
			drawSightFrame(g, w, h, r, green);
		}
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
		Contacts.Target target = Contacts.refresh(mc, candidate);
		if (target == null) {
			return;
		}
		Vec3 rel = target.pos().subtract(camera.position());
		var f = camera.forwardVector();
		var u = camera.upVector();
		var l = camera.leftVector();
		double z = rel.x * f.x() + rel.y * f.y() + rel.z * f.z();
		if (z < 0.1) {
			return;
		}
		int x = (int) (w / 2.0 - (rel.x * l.x() + rel.y * l.y() + rel.z * l.z()) / z * scale);
		int y = (int) (h / 2.0 - (rel.x * u.x() + rel.y * u.y() + rel.z * u.z()) / z * scale);
		float progress = Math.min(1.0F, lockTicks / (float) lockTime(kind));
		int size = locked() ? 8 : (int) (20 - 12 * progress);
		int col = locked() ? 0xFFFF4040 : 0xFFFFC040;
		g.outline(x - size, y - size, size * 2, size * 2, col);
		String d = Math.round(target.pos().distanceTo(mc.player.getEyePosition())) + "m" + (target.visible() ? "" : "  RADAR");
		g.text(font, (locked() ? "LOCKED " : "LOCKING ") + d, x + size + 3, y - 4, col, false);
	}
}
