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
		if (mc.player == null || !mc.options.keyUse.isDown() || mc.gui.screen() != null) {
			return false;
		}
		if (mc.player.getVehicle() instanceof dev.rubymod.entity.HeavyAAGunEntity gun) {
			return gun.getControllingPassenger() == mc.player;
		}
		return mc.player.getVehicle() instanceof VehicleEntity v && v.isTank() && v.getControllingPassenger() == mc.player;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getVehicle() instanceof dev.rubymod.entity.HeavyAAGunEntity gun) {
			AAGunHud.draw(g, mc, gun);
			return;
		}
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
		dev.rubymod.client.ui.Ui.panel(g, x0 - 6, y0 - 16, barW + 12, 28);
		int col = health < 0.25F ? dev.rubymod.client.ui.Ui.RED : health < 0.5F ? dev.rubymod.client.ui.Ui.AMBER : dev.rubymod.client.ui.Ui.GREEN;
		dev.rubymod.client.ui.Ui.text(g, (vehicle.isTank() ? "Tank" : "Jeep") + " armor", x0, y0 - 11, dev.rubymod.client.ui.Ui.TEXT);
		dev.rubymod.client.ui.Ui.bar(g, x0, y0, barW, 7, health, col);

		if (!vehicle.isTank() || !driver) {
			return;
		}
		// Reload bar.
		int reload = vehicle.reloadLeft();
		int rx = w / 2 + barW / 2 + 10;
		dev.rubymod.client.ui.Ui.panel(g, rx - 2, y0 - 16, 84, 28);
		if (reload > 0) {
			float done = 1.0F - reload / (float) VehicleEntity.RELOAD_TICKS;
			dev.rubymod.client.ui.Ui.text(g, "Reloading", rx + 4, y0 - 11, dev.rubymod.client.ui.Ui.AMBER);
			dev.rubymod.client.ui.Ui.bar(g, rx + 4, y0, 72, 7, done, dev.rubymod.client.ui.Ui.AMBER);
		} else {
			dev.rubymod.client.ui.Ui.text(g, "Gun ready", rx + 4, y0 - 11, dev.rubymod.client.ui.Ui.GREEN);
			dev.rubymod.client.ui.Ui.bar(g, rx + 4, y0, 72, 7, 1, dev.rubymod.client.ui.Ui.GREEN);
		}

		// Turret indicator: the hull (fixed, pointing up) and where the turret points relative to it.
		int cx = w / 2 - barW / 2 - 32;
		int cy = y0 - 2;
		dev.rubymod.client.ui.Ui.panel(g, cx - 17, cy - 17, 34, 34);
		g.outline(cx - 6, cy - 9, 12, 18, DIM);
		float rel = (float) Math.toRadians(Mth.wrapDegrees(vehicle.turretYaw() - vehicle.getYRot()));
		for (int i = 0; i < 13; i++) {
			int px = cx - Math.round((float) Math.sin(rel) * i);
			int py = cy - Math.round((float) Math.cos(rel) * i);
			g.fill(px, py, px + 1, py + 1, GREEN);
		}
		g.fill(cx - 2, cy - 2, cx + 2, cy + 2, GREEN);

		int smoke = vehicle.smokeCharges();
		String hint = "Click to fire. Right-click: sight. Space: smoke (" + smoke + ")";
		int hw = font.width(hint) + 14;
		dev.rubymod.client.ui.Ui.panel(g, w / 2 - hw / 2, y0 - 38, hw, 18);
		dev.rubymod.client.ui.Ui.text(g, hint, w / 2 - hw / 2 + 7, y0 - 33, dev.rubymod.client.ui.Ui.MUTED);
		if (zoomed(mc)) {
			drawSight(g, font, w, h, reload == 0);
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
