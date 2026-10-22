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
		if (!Vehicles.zoomable(mc) || Targeting.piloting(mc) != null) {
			return;
		}
		String text = Vehicles.zoom > 1 ? "ZOOM " + Vehicles.zoom + "x  [scroll]" : "scroll: zoom";
		int color = Vehicles.zoom > 1 ? 0xFF50FF70 : 0x9050FF70;
		g.text(mc.font, text, g.guiWidth() / 2 - mc.font.width(text) / 2, g.guiHeight() / 2 + 40, color, false);
	}
}
