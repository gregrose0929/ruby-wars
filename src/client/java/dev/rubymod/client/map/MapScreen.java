package dev.rubymod.client.map;

import com.mojang.blaze3d.platform.NativeImage;

import dev.rubymod.RubyMod;
import dev.rubymod.client.ClientState;
import dev.rubymod.net.MorePayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The full map (M): everything you've explored, claims, Hearts, waypoints,
 * your grave, pings, supply drops and squadmates. Scroll to zoom, drag to pan.
 */
public class MapScreen extends Screen {
	private static final Identifier TEXTURE = RubyMod.id("world_map");
	private static final int RES = 256;
	private static DynamicTexture texture;
	private double centerX;
	private double centerZ;
	/** Blocks per screen pixel. */
	private double zoom = 2.0;
	private boolean dragging;
	private double lastMouseX;
	private double lastMouseY;
	private long lastUpload;
	private double uploadedX = Double.NaN;
	private double uploadedZ;
	private double uploadedZoom;

	public MapScreen() {
		super(Component.literal("Map"));
		var player = Minecraft.getInstance().player;
		if (player != null) {
			centerX = player.getX();
			centerZ = player.getZ();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		int w = width;
		int h = height;
		int size = Math.min(w, h) - 40;
		int x0 = (w - size) / 2;
		int y0 = 24;
		double span = size * zoom;
		upload(span);
		g.fill(x0 - 3, y0 - 3, x0 + size + 3, y0 + size + 3, 0xF0101418);
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x0, y0, 0.0F, 0.0F, size, size, size, size);
		g.outline(x0 - 1, y0 - 1, size + 2, size + 2, 0xFF5A6470);
		g.enableScissor(x0, y0, x0 + size, y0 + size);
		MorePayloads.MapSync map = ClientState.map;
		if (map != null) {
			for (MorePayloads.ClaimInfo c : map.claims()) {
				int color = c.relation() == 0 ? 0xFF5BE37D : c.relation() == 1 ? 0xFF55AAFF : 0xFFFF5050;
				int[] a = screen(c.x() - c.radius(), c.z() - c.radius(), x0, y0, size);
				int s = (int) Math.round((c.radius() * 2 + 1) / zoom);
				g.outline(a[0], a[1], Math.max(2, s), Math.max(2, s), color);
				g.centeredText(font, c.name(), a[0] + s / 2, a[1] + 2, color);
			}
			for (MorePayloads.Marker m : map.markers()) {
				int[] p = screen(m.x(), m.z(), x0, y0, size);
				int color = Markers.color(m.kind());
				g.fill(p[0] - 3, p[1] - 3, p[0] + 4, p[1] + 4, 0xFF101010);
				g.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, color);
				g.text(font, m.name(), p[0] + 6, p[1] - 4, color, true);
			}
			for (MorePayloads.Mate mate : map.mates()) {
				int[] p = screen((int) mate.x(), (int) mate.z(), x0, y0, size);
				g.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, 0xFF5BE37D);
				g.text(font, mate.name(), p[0] + 5, p[1] - 4, 0xFF7CF59A, true);
			}
		}
		var player = Minecraft.getInstance().player;
		if (player != null) {
			int[] p = screen(player.getBlockX(), player.getBlockZ(), x0, y0, size);
			g.fill(p[0] - 3, p[1] - 3, p[0] + 4, p[1] + 4, 0xFF101010);
			g.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, 0xFFFFFFFF);
		}
		g.disableScissor();
		g.centeredText(font, "MAP  -  scroll: zoom, drag: pan, right-click: add/remove a waypoint  (" + (int) Math.round(span) + " blocks)", w / 2, 8, 0xFFDDE6EE);
		// Legend
		String[] names = {"Waypoint", "Grave", "Ping", "Supply drop", "Your Heart", "Squad Heart"};
		int lx = x0 + size + 10;
		if (lx + 80 < w) {
			for (int i = 0; i < names.length; i++) {
				g.fill(lx, y0 + i * 12 + 2, lx + 5, y0 + i * 12 + 7, Markers.color((byte) i));
				g.text(font, names[i], lx + 9, y0 + i * 12, 0xFFCCCCCC, true);
			}
			g.text(font, "Claims: yours / squad / other", lx, y0 + 80, 0xFFAAAAAA, true);
		}
	}

	private int[] screen(int bx, int bz, int x0, int y0, int size) {
		return new int[] {(int) Math.round(x0 + size / 2.0 + (bx - centerX) / zoom), (int) Math.round(y0 + size / 2.0 + (bz - centerZ) / zoom)};
	}

	private void upload(double span) {
		long now = System.currentTimeMillis();
		boolean moved = uploadedX != centerX || uploadedZ != centerZ || uploadedZoom != zoom;
		if (texture != null && !moved && now - lastUpload < 1000) {
			return;
		}
		lastUpload = now;
		uploadedX = centerX;
		uploadedZ = centerZ;
		uploadedZoom = zoom;
		if (texture == null) {
			texture = new DynamicTexture(() -> "rubymod world map", RES, RES, false);
			Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
		}
		NativeImage img = texture.getPixels();
		double step = span / RES;
		for (int z = 0; z < RES; z++) {
			for (int x = 0; x < RES; x++) {
				int c = MapCache.color((int) Math.floor(centerX - span / 2 + x * step), (int) Math.floor(centerZ - span / 2 + z * step));
				img.setPixel(x, z, c == 0 ? 0xFF15191D : c);
			}
		}
		texture.upload();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		zoom = Math.max(0.5, Math.min(16.0, zoom * (scrollY > 0 ? 0.8 : 1.25)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() == 1) {
			// Right-click: drop a waypoint there (or remove the one you clicked).
			int size = Math.min(width, height) - 40;
			int x0 = (width - size) / 2;
			int y0 = 24;
			int bx = (int) Math.round(centerX + (event.x() - x0 - size / 2.0) * zoom);
			int bz = (int) Math.round(centerZ + (event.y() - y0 - size / 2.0) * zoom);
			var player = Minecraft.getInstance().player;
			if (player != null) {
				var map = ClientState.map;
				if (map != null) {
					for (MorePayloads.Marker m : map.markers()) {
						if (m.kind() == 0 && Math.abs(m.x() - bx) <= 4 * zoom && Math.abs(m.z() - bz) <= 4 * zoom) {
							player.connection.sendCommand("waypoint remove " + m.name());
							return true;
						}
					}
				}
				long count = map == null ? 0 : map.markers().stream().filter(m -> m.kind() == 0).count();
				player.connection.sendCommand("waypoint at " + bx + " " + bz + " Marker " + (count + 1));
			}
			return true;
		}
		dragging = true;
		lastMouseX = event.x();
		lastMouseY = event.y();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return true;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (dragging) {
			centerX -= (mouseX - lastMouseX) * zoom;
			centerZ -= (mouseY - lastMouseY) * zoom;
		}
		lastMouseX = mouseX;
		lastMouseY = mouseY;
	}
}
