package dev.rubymod.client.gun;

import dev.rubymod.RubyMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** The sniper's scope: a lens with a mil-dot reticle and a lit center dot, drifting slightly as you breathe. */
public final class ScopeOverlay {
	private static final Identifier SCOPE = RubyMod.id("textures/misc/scope.png");

	private ScopeOverlay() {
	}

	public static void draw(GuiGraphicsExtractor g, float scale) {
		int w = g.guiWidth();
		int h = g.guiHeight();
		int size = Mth.floor(Math.min(w, h) * Math.min(1.0F, scale) * 1.02F);
		long ms = System.currentTimeMillis();
		int dx = Math.round(Mth.sin(ms / 1400.0F) * 1.5F);
		int dy = Math.round(Mth.sin(ms / 1100.0F + 1.0F) * 1.2F);
		int x = (w - size) / 2 + dx;
		int y = (h - size) / 2 + dy;
		g.blit(RenderPipelines.GUI_TEXTURED, SCOPE, x, y, 0.0F, 0.0F, size, size, size, size);
		g.fill(RenderPipelines.GUI, 0, 0, w, y, 0xFF000000);
		g.fill(RenderPipelines.GUI, 0, y + size, w, h, 0xFF000000);
		g.fill(RenderPipelines.GUI, 0, y, x, y + size, 0xFF000000);
		g.fill(RenderPipelines.GUI, x + size, y, w, y + size, 0xFF000000);
	}

	/** Aiming down a red dot or iron sights: the sight is the crosshair. */
	public static boolean hideCrosshair() {
		var player = Minecraft.getInstance().player;
		return player != null && Minecraft.getInstance().options.getCameraType().isFirstPerson() && GunView.handles(player.getMainHandItem())
				&& GunView.adsProgress() > 0.5F;
	}
}
