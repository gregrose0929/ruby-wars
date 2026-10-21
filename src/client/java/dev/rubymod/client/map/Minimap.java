package dev.rubymod.client.map;

import com.mojang.blaze3d.platform.NativeImage;

import dev.rubymod.RubyMod;
import dev.rubymod.client.ClientState;
import dev.rubymod.client.Vehicles;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The minimap (top right): terrain you've seen, claim borders (yours green,
 * your squad's blue, others red), waypoints, your grave, pings, supply drops,
 * squadmates, and an arrow for you. North is up. Coordinates and heading below.
 */
public class Minimap implements HudElement {
	public static final int SIZE = 96;
	/** Blocks across the map. */
	private static final int SPAN = 128;
	private static final Identifier TEXTURE = RubyMod.id("minimap");
	private static DynamicTexture texture;
	private static long lastUpload;
	public static boolean enabled = true;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled || mc.player == null || mc.level == null || Vehicles.inVehicle(mc) && dev.rubymod.client.Targeting.piloting(mc) != null) {
			return;
		}
		int w = g.guiWidth();
		int x0 = w - SIZE - 4;
		int y0 = 4;
		int px = mc.player.getBlockX();
		int pz = mc.player.getBlockZ();
		upload(px, pz);

		g.fill(x0 - 2, y0 - 2, x0 + SIZE + 2, y0 + SIZE + 2, 0xC0101418);
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x0, y0, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
		g.outline(x0 - 1, y0 - 1, SIZE + 2, SIZE + 2, 0xFF3A4048);
		float scale = SIZE / (float) SPAN;
		g.enableScissor(x0, y0, x0 + SIZE, y0 + SIZE);
		MorePayloads.MapSync map = ClientState.map;
		if (map != null) {
			for (MorePayloads.ClaimInfo c : map.claims()) {
				int color = c.relation() == 0 ? 0xFF5BE37D : c.relation() == 1 ? 0xFF55AAFF : 0xFFFF5050;
				int cx = x0 + Math.round((c.x() - c.radius() - px) * scale + SIZE / 2.0F);
				int cz = y0 + Math.round((c.z() - c.radius() - pz) * scale + SIZE / 2.0F);
				int size = Math.round((c.radius() * 2 + 1) * scale);
				g.outline(cx, cz, size, size, color);
			}
			for (MorePayloads.Marker m : map.markers()) {
				int mx = x0 + Math.round((m.x() - px) * scale + SIZE / 2.0F);
				int mz = y0 + Math.round((m.z() - pz) * scale + SIZE / 2.0F);
				mx = Mth.clamp(mx, x0 + 2, x0 + SIZE - 3);
				mz = Mth.clamp(mz, y0 + 2, y0 + SIZE - 3);
				dot(g, mx, mz, Markers.color(m.kind()));
			}
			for (MorePayloads.Mate mate : map.mates()) {
				int mx = Mth.clamp(x0 + Math.round((mate.x() - px) * scale + SIZE / 2.0F), x0 + 2, x0 + SIZE - 3);
				int mz = Mth.clamp(y0 + Math.round((mate.z() - pz) * scale + SIZE / 2.0F), y0 + 2, y0 + SIZE - 3);
				g.fill(mx - 2, mz - 2, mx + 2, mz + 2, 0xFF101010);
				g.fill(mx - 1, mz - 1, mx + 1, mz + 1, 0xFF5BE37D);
			}
		}
		g.disableScissor();
		arrow(g, x0 + SIZE / 2, y0 + SIZE / 2, mc.player.getYRot());

		Font font = mc.font;
		String heading = Markers.compass(mc.player.getYRot());
		String coords = px + " " + mc.player.getBlockY() + " " + pz + "  " + heading;
		g.fill(x0 - 2, y0 + SIZE + 2, x0 + SIZE + 2, y0 + SIZE + 13, 0xC0101418);
		g.centeredText(font, coords, x0 + SIZE / 2, y0 + SIZE + 4, 0xFFDDE6EE);
		g.text(font, "N", x0 + SIZE / 2 - 2, y0 + 1, 0xFFFF6060, true);
	}

	private static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF101010);
		g.fill(x - 1, y - 1, x + 2, y + 2, color);
	}

	/** A small arrowhead pointing the way you face (yaw 0 = south = down on a north-up map). */
	private static void arrow(GuiGraphicsExtractor g, int cx, int cy, float yaw) {
		double a = Math.toRadians(yaw);
		double fx = -Math.sin(a);
		double fz = Math.cos(a);
		for (int i = -3; i <= 5; i++) {
			int width = i < 0 ? 1 : Math.max(0, 3 - i / 2);
			for (int s = -width; s <= width; s++) {
				int x = (int) Math.round(cx + fx * i - fz * s);
				int y = (int) Math.round(cy + fz * i + fx * s);
				g.fill(x, y, x + 1, y + 1, i > 3 ? 0xFFFF5060 : 0xFFFFFFFF);
			}
		}
		g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFF101010);
	}

	/** Rebuilds the minimap texture from the map cache (5 times a second). */
	private static void upload(int px, int pz) {
		long now = System.currentTimeMillis();
		if (texture != null && now - lastUpload < 200) {
			return;
		}
		lastUpload = now;
		if (texture == null) {
			texture = new DynamicTexture(() -> "rubymod minimap", SPAN, SPAN, false);
			Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
		}
		NativeImage img = texture.getPixels();
		for (int z = 0; z < SPAN; z++) {
			for (int x = 0; x < SPAN; x++) {
				int c = MapCache.color(px - SPAN / 2 + x, pz - SPAN / 2 + z);
				img.setPixel(x, z, c == 0 ? 0xFF1A1E22 : c);
			}
		}
		texture.upload();
	}
}
