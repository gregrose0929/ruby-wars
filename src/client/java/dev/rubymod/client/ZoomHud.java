package dev.rubymod.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** "ZOOM 4x" under the crosshair for tank / AA gun sights and lock-on launchers (the pilot HUD shows its own). */
public class ZoomHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		// Only while zoomed in (the hint that you can is on the vehicle's own display).
		if (!Vehicles.zoomable(mc) || Targeting.piloting(mc) != null || Vehicles.zoom <= 1) {
			return;
		}
		String text = "ZOOM " + Vehicles.zoom + "x";
		int color = 0xFF50FF70;
		g.text(mc.font, text, g.guiWidth() / 2 - mc.font.width(text) / 2, g.guiHeight() / 2 + 40, color, false);
	}
}
