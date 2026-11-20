package dev.rubymod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Ruby Wars HUD's own size, inside Minecraft's GUI scale (Settings, "HUD size"). "Small" (the default) draws the
 * HUD one GUI-scale step smaller (scale 3 draws like 2, scale 4 like 3...), never below 2; "Normal" draws it at the
 * GUI scale. Because the GUI scale times this is always a whole number, text stays sharp.
 *
 * <p>Use: {@code HudScale.push(g)}, draw using {@link #width}/{@link #height} instead of the GUI's, then {@code pop}.
 */
public final class HudScale {
	private HudScale() {
	}

	public static float factor() {
		if (!"small".equals(ClientSettings.hudSize)) {
			return 1.0F;
		}
		int gui = (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale());
		int target = Math.max(2, gui - 1);
		return target >= gui ? 1.0F : target / (float) gui;
	}

	public static void push(GuiGraphicsExtractor g) {
		float k = factor();
		g.pose().pushMatrix();
		g.pose().scale(k, k);
	}

	public static void pop(GuiGraphicsExtractor g) {
		g.pose().popMatrix();
	}

	/** The screen's width in HUD units. */
	public static int width(GuiGraphicsExtractor g) {
		return Math.round(g.guiWidth() / factor());
	}

	public static int height(GuiGraphicsExtractor g) {
		return Math.round(g.guiHeight() / factor());
	}

	/** HUD units to GUI units (for things drawn outside the HUD that line up with it, like toasts). */
	public static int toGui(int hudUnits) {
		return Math.round(hudUnits * factor());
	}
}
