package dev.rubymod.client;

import dev.rubymod.entity.VehicleEntity;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * Ground vehicle HUD (replaces the hotbar and hearts while driving): armor bar,
 * and for the tank a reload bar, a turret-direction indicator, and the gunner's
 * sight while zoomed (hold right-click).
 */
public class VehicleHud implements HudElement {
	private static final int GREEN = 0xFF50FF70;
	private static final int DIM = 0xA050FF70;
	private static final int AMBER = 0xFFFFC040;
	private static final int RED = 0xFFFF4040;
	private static final int PANEL = 0x70000000;

	public static boolean zoomed(Minecraft mc) {
		return mc.player != null && mc.player.getVehicle() instanceof VehicleEntity v && v.isTank() && v.getControllingPassenger() == mc.player
				&& mc.options.keyUse.isDown() && mc.gui.screen() == null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !(mc.player.getVehicle() instanceof VehicleEntity vehicle)) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		boolean driver = vehicle.getControllingPassenger() == mc.player;

		// Armor bar, bottom center (where the hotbar was).
		float health = vehicle.getHealth() / vehicle.getMaxHealth();
		int barW = 120;
		int x0 = w / 2 - barW / 2;
		int y0 = h - 22;
		g.fill(x0 - 2, y0 - 12, x0 + barW + 2, y0 + 8, PANEL);
		int col = health < 0.25F ? RED : health < 0.5F ? AMBER : GREEN;
		g.fill(x0, y0, x0 + Math.round(barW * health), y0 + 6, col);
		g.outline(x0 - 1, y0 - 1, barW + 2, 8, DIM);
		String name = vehicle.isTank() ? "TANK" : "JEEP";
		g.text(font, name + " ARMOR " + Math.round(health * 100) + "%", x0, y0 - 10, col, false);

		if (!vehicle.isTank() || !driver) {
			return;
		}
		// Reload bar.
		int reload = vehicle.reloadLeft();
		int rx = w / 2 + barW / 2 + 10;
		g.fill(rx - 2, y0 - 12, rx + 72, y0 + 8, PANEL);
		if (reload > 0) {
			float done = 1.0F - reload / (float) VehicleEntity.RELOAD_TICKS;
			g.fill(rx, y0, rx + Math.round(70 * done), y0 + 6, AMBER);
			g.text(font, "RELOADING", rx, y0 - 10, AMBER, false);
		} else {
			g.fill(rx, y0, rx + 70, y0 + 6, GREEN);
			g.text(font, "GUN READY", rx, y0 - 10, GREEN, false);
		}

		// Turret indicator: the hull (fixed, pointing up) and where the turret points relative to it.
		int cx = w / 2 - barW / 2 - 30;
		int cy = y0 - 6;
		g.fill(cx - 16, cy - 16, cx + 16, cy + 16, PANEL);
		g.outline(cx - 6, cy - 9, 12, 18, DIM);
		float rel = (float) Math.toRadians(Mth.wrapDegrees(vehicle.turretYaw() - vehicle.getYRot()));
		for (int i = 0; i < 13; i++) {
			int px = cx - Math.round((float) Math.sin(rel) * i);
			int py = cy - Math.round((float) Math.cos(rel) * i);
			g.fill(px, py, px + 1, py + 1, GREEN);
		}
		g.fill(cx - 2, cy - 2, cx + 2, cy + 2, GREEN);

		int smoke = vehicle.smokeCharges();
		g.text(font, "SMOKE x" + smoke + " [Space]", rx, y0 - 22, smoke > 0 ? GREEN : RED, false);
		if (zoomed(mc)) {
			drawSight(g, font, w, h, reload == 0);
		} else {
			g.text(font, "Hold right-click: gunner sight", 6, h - 12, DIM, false);
		}
	}

	/** Gunner's sight: a chevron with range stadia below it. */
	private void drawSight(GuiGraphicsExtractor g, Font font, int w, int h, boolean ready) {
		int cx = w / 2;
		int cy = h / 2;
		int col = ready ? GREEN : AMBER;
		g.horizontalLine(cx - 60, cx - 8, cy, col);
		g.horizontalLine(cx + 8, cx + 60, cy, col);
		for (int i = 0; i < 6; i++) {
			g.fill(cx - i, cy + i, cx - i + 1, cy + i + 1, col);
			g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, col);
		}
		for (int r = 1; r <= 4; r++) {
			int y = cy + 10 + r * 9;
			g.horizontalLine(cx - 4 - r, cx + 4 + r, y, DIM);
			g.text(font, Integer.toString(r * 100), cx + 8 + r, y - 4, DIM, false);
		}
		g.outline(cx - 90, cy - 60, 180, 120, DIM);
	}
}
