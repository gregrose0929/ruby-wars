package dev.rubymod.client;

import dev.rubymod.item.RangefinderItem;
import dev.rubymod.item.ThrowableWeaponItem;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.HitResult;

/**
 * Two little helpers under the crosshair: looking through the Rangefinder, a reticle and how far away the spot in the
 * middle is (let go to mark it); winding up a grenade, a bar that fills as the throw gets harder.
 */
public class AimHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.player.isUsingItem() || mc.gui.screen() != null) {
			return;
		}
		int cx = g.guiWidth() / 2;
		int cy = g.guiHeight() / 2;
		var item = mc.player.getUseItem().getItem();
		if (item instanceof RangefinderItem) {
			// A ring and ticks round the middle, darker outside.
			int r = Math.min(g.guiWidth(), g.guiHeight()) / 3;
			g.fill(0, 0, g.guiWidth(), cy - r, 0x90000000);
			g.fill(0, cy + r, g.guiWidth(), g.guiHeight(), 0x90000000);
			g.fill(0, cy - r, cx - r, cy + r, 0x90000000);
			g.fill(cx + r, cy - r, g.guiWidth(), cy + r, 0x90000000);
			g.fill(cx - r, cy, cx - 6, cy + 1, 0xC0FF7A30);
			g.fill(cx + 6, cy, cx + r, cy + 1, 0xC0FF7A30);
			g.fill(cx, cy - r, cx + 1, cy - 6, 0xC0FF7A30);
			g.fill(cx, cy + 6, cx + 1, cy + r, 0xC0FF7A30);
			HitResult hit = mc.player.pick(RangefinderItem.RANGE, deltaTracker.getGameTimeDeltaPartialTick(true), true);
			String text = hit.getType() == HitResult.Type.MISS ? "Out of range"
					: Math.round(mc.player.getEyePosition().distanceTo(hit.getLocation())) + " blocks   (let go to mark)";
			g.centeredText(mc.font, text, cx, cy + 12, 0xFFFFD0A0);
		} else if (item instanceof ThrowableWeaponItem) {
			int held = mc.player.getTicksUsingItem();
			float power = ThrowableWeaponItem.power(held);
			int w = 40;
			int x = cx - w / 2;
			int y = cy + 12;
			g.fill(x - 1, y - 1, x + w + 1, y + 4, 0xA0000000);
			g.fill(x, y, x + Math.round(w * power), y + 3, power >= 1 ? 0xFFFF6040 : 0xFFFFC040);
		}
	}
}
