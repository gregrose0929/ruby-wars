package dev.rubymod.client.map;

import dev.rubymod.client.ClientState;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * What you're looking at (like Jade): owner, tier, status and charge for
 * machines, ammo for defenses, integrity for Hearts, armor and crew for
 * vehicles, the lock on a War Grave. Asked from the server a few times a second.
 */
public class LookPanel implements HudElement {
	private static BlockPos lastPos = BlockPos.ZERO;
	private static int lastEntity = -2;
	private static long lastAsked;

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		HitResult hit = mc.hitResult;
		BlockPos pos = BlockPos.ZERO;
		int entity = -1;
		if (hit instanceof EntityHitResult e && e.getEntity() == mc.player.getVehicle()) {
			lastEntity = -2;
			return;
		} else if (hit instanceof EntityHitResult e) {
			entity = e.getEntity().getId();
		} else if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
			pos = b.getBlockPos();
		} else {
			lastEntity = -2;
			return;
		}
		long now = System.currentTimeMillis();
		boolean changed = entity != lastEntity || !pos.equals(lastPos);
		if (changed || now - lastAsked > 400) {
			lastPos = pos;
			lastEntity = entity;
			lastAsked = now;
			ClientPlayNetworking.send(new MorePayloads.LookQuery(pos, entity));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		dev.rubymod.client.HudScale.push(g);
		try {
			draw(g, delta);
		} finally {
			dev.rubymod.client.HudScale.pop(g);
		}
	}

	/** Drawn at the HUD's size (see HudScale). */
	private void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		MorePayloads.LookInfo info = ClientState.look;
		if (info == null || !dev.rubymod.client.ClientSettings.lookInfo || System.currentTimeMillis() - ClientState.lookAt > 900 || lastEntity == -2
				|| dev.rubymod.client.GameHud.menuOpen()) {
			return;
		}
		boolean match = info.entityId() >= 0 ? info.entityId() == lastEntity : lastEntity < 0 && info.pos().equals(lastPos);
		if (!match || info.lines().isEmpty()) {
			return;
		}
		Font font = mc.font;
		int width = 0;
		for (String line : info.lines()) {
			width = Math.max(width, font.width(line));
		}
		width = Math.min(width + 16, dev.rubymod.client.HudScale.width(g) - 8);
		int lines = info.lines().size();
		int height = lines * 10 + 6 + (info.bar() >= 0 ? 5 : 0);
		int x = dev.rubymod.client.HudScale.width(g) / 2 - width / 2;
		// Under whatever is at the top centre (the phase banner, respawns, a raid).
		int y = Math.max(20, dev.rubymod.client.GameHud.topCentreBottom() + 3);
		g.fill(x, y, x + width, y + height, 0xD0101418);
		g.fill(x, y, x + 2, y + height, 0xFF000000 | info.color());
		g.outline(x, y, width, height, 0xFF2C333B);
		int ty = y + 4;
		for (int i = 0; i < lines; i++) {
			g.text(font, dev.rubymod.client.ui.Ui.fit(info.lines().get(i), width - 14), x + 8, ty, i == 0 ? 0xFF000000 | info.color() : 0xFFC8D0D8, true);
			ty += 10;
		}
		if (info.bar() >= 0) {
			int bw = width - 16;
			g.fill(x + 8, ty, x + 8 + bw, ty + 3, 0xFF2A2F35);
			g.fill(x + 8, ty, x + 8 + Math.round(bw * Math.max(0, Math.min(1, info.bar()))), ty + 3, 0xFF000000 | info.color());
		}
	}
}
