package dev.rubymod.client;

import org.joml.Vector3fc;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.HeavyAAGunEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Heavy AA Gun sight: a ring reticle, magazine and reload bar, and for every
 * aircraft in range a box on it plus a lead marker: put the reticle on the
 * marker and the shells meet the aircraft. The marker turns red when you're on.
 */
public final class AAGunHud {
	private static final int GREEN = 0xFF50FF70;
	private static final int DIM = 0xA050FF70;
	private static final int AMBER = 0xFFFFC040;
	private static final int RED = 0xFFFF4040;
	private static final int RANGE = 320;

	private AAGunHud() {
	}

	public static void draw(GuiGraphicsExtractor g, Minecraft mc, HeavyAAGunEntity gun) {
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		int cx = w / 2;
		int cy = h / 2;
		boolean zoomed = VehicleHud.zoomed(mc);
		Camera camera = mc.gameRenderer.mainCamera();

		// Reticle: ring with range ticks; bigger when zoomed.
		int r = zoomed ? 46 : 22;
		ring(g, cx, cy, r, DIM);
		ring(g, cx, cy, 2, GREEN);
		g.horizontalLine(cx - r - 8, cx - r - 2, cy, GREEN);
		g.horizontalLine(cx + r + 2, cx + r + 8, cy, GREEN);
		g.verticalLine(cx, cy + r + 2, cy + r + 8, GREEN);

		// Where the barrels actually point (they lag the mouse a little).
		float[] bore = project(camera, camera.position().add(gun.aim().scale(400)), w, h);
		if (bore != null) {
			g.outline((int) bore[0] - 3, (int) bore[1] - 3, 7, 7, AMBER);
		}

		// Aircraft: box + lead marker.
		Vec3 muzzle = gun.position().add(0, 1.5, 0);
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof AircraftEntity a) || a.onGround() || a.hasPassenger(mc.player) || e.distanceTo(gun) > RANGE) {
				continue;
			}
			Vec3 pos = a.getBoundingBox().getCenter();
			Vec3 vel = new Vec3(a.getX() - a.xo, a.getY() - a.yo, a.getZ() - a.zo);
			double t = pos.distanceTo(muzzle) / HeavyAAGunEntity.SHELL_SPEED;
			for (int i = 0; i < 3; i++) {
				t = pos.add(vel.scale(t)).distanceTo(muzzle) / HeavyAAGunEntity.SHELL_SPEED;
			}
			Vec3 lead = pos.add(vel.scale(t)).add(0, 0.5 * 0.03 * t * t, 0);
			float[] p = project(camera, pos, w, h);
			float[] l = project(camera, lead, w, h);
			if (p != null) {
				g.outline((int) p[0] - 7, (int) p[1] - 7, 14, 14, AMBER);
				g.text(font, Math.round(e.distanceTo(gun)) + "m", (int) p[0] + 9, (int) p[1] - 10, AMBER, false);
			}
			if (l != null) {
				boolean on = Math.hypot(l[0] - cx, l[1] - cy) < 6;
				int col = on ? RED : GREEN;
				diamond(g, (int) l[0], (int) l[1], 4, col);
				if (p != null) {
					dotted(g, p[0], p[1], l[0], l[1], DIM);
				}
				if (on) {
					g.text(font, "FIRE", (int) l[0] + 7, (int) l[1] + 2, RED, false);
				}
			}
		}

		// Magazine / reload.
		int ammo = gun.ammo();
		int reload = gun.reloadLeft();
		int bx = cx - 60;
		int by = h - 30;
		g.fill(bx - 2, by - 12, bx + 122, by + 8, 0x70000000);
		if (reload > 0) {
			float done = 1.0F - reload / (float) HeavyAAGunEntity.RELOAD_TICKS;
			g.fill(bx, by, bx + Math.round(120 * done), by + 6, AMBER);
			g.text(font, "RELOADING (4 Heavy Ammo)", bx, by - 10, AMBER, false);
		} else {
			for (int i = 0; i < HeavyAAGunEntity.MAGAZINE; i++) {
				g.fill(bx + i * 3, by, bx + i * 3 + 2, by + 6, i < ammo ? GREEN : 0xFF30383A);
			}
			g.text(font, "HEAVY AA  " + ammo + "/" + HeavyAAGunEntity.MAGAZINE, bx, by - 10, GREEN, false);
		}
		int hp = Math.round(gun.getHealth() / gun.getMaxHealth() * 100);
		g.text(font, "MOUNT " + hp + "%", bx + 80, by - 10, hp < 30 ? RED : DIM, false);
		if (!zoomed) {
			g.text(font, "Hold right-click: sight", 6, h - 12, DIM, false);
		}
	}

	private static float[] project(Camera camera, Vec3 world, int w, int h) {
		Vec3 rel = world.subtract(camera.position());
		Vector3fc f = camera.forwardVector();
		Vector3fc u = camera.upVector();
		Vector3fc l = camera.leftVector();
		double z = rel.x * f.x() + rel.y * f.y() + rel.z * f.z();
		if (z < 0.1) {
			return null;
		}
		double x = rel.x * l.x() + rel.y * l.y() + rel.z * l.z();
		double y = rel.x * u.x() + rel.y * u.y() + rel.z * u.z();
		double scale = (h / 2.0) / Math.tan(Math.toRadians(camera.getFov() / 2.0));
		return new float[] {(float) (w / 2.0 - x / z * scale), (float) (h / 2.0 - y / z * scale)};
	}

	private static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int col) {
		int points = Math.max(12, r * 3);
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			int x = cx + (int) Math.round(Math.cos(a) * r);
			int y = cy + (int) Math.round(Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, col);
		}
	}

	private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int col) {
		for (int i = 0; i <= r; i++) {
			g.fill(cx - r + i, cy - i, cx - r + i + 1, cy - i + 1, col);
			g.fill(cx - r + i, cy + i, cx - r + i + 1, cy + i + 1, col);
			g.fill(cx + r - i, cy - i, cx + r - i + 1, cy - i + 1, col);
			g.fill(cx + r - i, cy + i, cx + r - i + 1, cy + i + 1, col);
		}
	}

	private static void dotted(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int col) {
		int steps = (int) (Math.hypot(x1 - x0, y1 - y0) / 4);
		for (int i = 1; i < steps; i++) {
			int x = (int) (x0 + (x1 - x0) * i / steps);
			int y = (int) (y0 + (y1 - y0) * i / steps);
			g.fill(x, y, x + 1, y + 1, col);
		}
	}
}
