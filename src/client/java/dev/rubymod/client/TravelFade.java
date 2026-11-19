package dev.rubymod.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Going somewhere else (the hub, Ruby Wars, a game): the screen goes dark for a moment and fades back in, so the
 * jump and the world loading in around you aren't a sudden pop.
 */
public class TravelFade implements HudElement {
	private static final long HOLD_MS = 450;
	private static final long FADE_MS = 650;
	private static long startedAt = -10_000;

	public static void start() {
		startedAt = System.currentTimeMillis();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		long age = System.currentTimeMillis() - startedAt;
		if (age > HOLD_MS + FADE_MS) {
			return;
		}
		float a = age < HOLD_MS ? 1.0F : 1.0F - (age - HOLD_MS) / (float) FADE_MS;
		int alpha = Math.round(Math.max(0, Math.min(1, a)) * 255);
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), alpha << 24 | 0x0B0C10);
	}
}
