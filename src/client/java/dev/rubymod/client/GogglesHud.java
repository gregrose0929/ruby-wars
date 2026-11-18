package dev.rubymod.client;

import dev.rubymod.registry.ModItems;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;

/** Night-vision goggles on: the world through green glass, darker at the edges. */
public class GogglesHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson() || mc.getCameraEntity() != mc.player
				|| !mc.player.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.NIGHT_GOGGLES)) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		g.fill(0, 0, w, h, 0x3830FF50);
		// A soft dark rim, like looking down a pair of tubes.
		int rim = Math.max(8, Math.min(w, h) / 10);
		for (int i = 0; i < rim; i += 2) {
			int a = (int) (0x70 * (1 - i / (float) rim));
			int c = a << 24 | 0x001000;
			g.fill(0, i, w, i + 2, c);
			g.fill(0, h - i - 2, w, h - i, c);
			g.fill(i, 0, i + 2, h, c);
			g.fill(w - i - 2, 0, w - i, h, c);
		}
	}
}
