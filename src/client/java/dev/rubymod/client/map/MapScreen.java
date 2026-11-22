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
		dev.rubymod.client.ui.Ui.panel(g, x0 - 6, y0 - 6, size + 12, size + 12);
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x0, y0, 0.0F, 0.0F, size, size, size, size);
		g.enableScissor(x0, y0, x0 + size, y0 + size);
		MorePayloads.MapSync map = ClientState.map;
		labels.clear();
		if (map != null) {
			for (MorePayloads.ClaimInfo c : map.claims()) {
				int color = c.relation() == 0 ? 0xFF5BE37D : c.relation() == 1 ? 0xFF55AAFF : 0xFFFF5050;
				int[] a = screen(c.x() - c.radius(), c.z() - c.radius(), x0, y0, size);
				int s = (int) Math.round((c.radius() * 2 + 1) / zoom);
				g.outline(a[0], a[1], Math.max(2, s), Math.max(2, s), color);
				label(c.name(), a[0] + s / 2 - font.width(c.name()) / 2, a[1] + 2, color, 3);
			}
			var plan = dev.rubymod.client.ClientState.borderPlan;
			if (plan != null && plan.show()) {
				int half = plan.size() / 2;
				int[] a = screen(plan.x() - half, plan.z() - half, x0, y0, size);
				int s = Math.max(3, (int) Math.round(plan.size() / zoom));
				g.outline(a[0], a[1], s, s, 0xFFFFB040);
				g.outline(a[0] + 1, a[1] + 1, s - 2, s - 2, 0xFFFFB040);
				label("Final circle", a[0] + 3, a[1] + 3, 0xFFFFB040, 0);
				// Off the edge of the map as it's showing: point to it from the edge.
				int[] mid = screen(plan.x(), plan.z(), x0, y0, size);
				boolean off = a[0] + s < x0 || a[1] + s < y0 || a[0] > x0 + size || a[1] > y0 + size;
				if (off) {
					int ex = Math.max(x0 + 4, Math.min(x0 + size - 70, mid[0]));
					int ey = Math.max(y0 + 4, Math.min(y0 + size - 12, mid[1]));
					String arrow = (mid[1] < y0 ? "↑" : mid[1] > y0 + size ? "↓" : "") + (mid[0] < x0 ? "←" : mid[0] > x0 + size ? "→" : "");
					g.fill(ex - 2, ey - 2, ex + font.width(arrow + " Final circle") + 2, ey + 9, 0xB0000000);
					g.text(font, arrow + " Final circle", ex, ey, 0xFFFFB040, false);
				}
			}
			for (MorePayloads.Marker m : map.markers()) {
				int[] p = screen(m.x(), m.z(), x0, y0, size);
				int color = Markers.color(m.kind());
				var icon = Markers.icon(m.kind());
				if (icon.isEmpty()) {
					g.fill(p[0] - 3, p[1] - 3, p[0] + 4, p[1] + 4, 0xFF101010);
					g.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, color);
					label(m.name(), p[0] + 6, p[1] - 4, color, 1);
				} else {
					g.item(icon, p[0] - 8, p[1] - 8);
					label(m.name(), p[0] + 10, p[1] - 4, color, 1);
				}
			}
			for (MorePayloads.Mate mate : map.mates()) {
				int[] p = screen((int) mate.x(), (int) mate.z(), x0, y0, size);
				g.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, 0xFF5BE37D);
				label(mate.name(), p[0] + 5, p[1] - 4, 0xFF7CF59A, 2);
			}
			drawLabels(g);
		}
		var player = Minecraft.getInstance().player;
		if (player != null) {
			int[] p = screen(player.getBlockX(), player.getBlockZ(), x0, y0, size);
			g.fill(p[0] - 3, p[1] - 3, p[0] + 4, p[1] + 4, 0xFF101010);
			g.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, 0xFFFFFFFF);
		}
		g.disableScissor();
		dev.rubymod.client.ui.Ui.heading(g, "Map", x0 - 6, 6, dev.rubymod.client.ui.Ui.TEXT, 1.0F, false);
		String help = "Scroll: zoom, drag: move, right-click: waypoint";
		// All of it (smaller, or on two small lines) rather than cut short: "right-click for a waypoint" is the useful part.
		dev.rubymod.client.ui.Ui.shrinkText(g, help, x0 + 30, 6, size - 30, dev.rubymod.client.ui.Ui.DIM);
		// Legend
		// Squad things only when squads are on.
		boolean squads = dev.rubymod.client.ClientState.hud != null && dev.rubymod.client.ClientState.hud.squads();
		String[] names = squads ? new String[] {"Waypoint", "Grave", "Ping", "Supply drop", "Your Heart", "Squad Heart"}
				: new String[] {"Waypoint", "Grave", "Ping", "Supply drop", "Your Heart"};
		int lx = x0 + size + 10;
		if (lx + 92 < w) {
			dev.rubymod.client.ui.Ui.panel(g, lx - 4, y0 - 6, 96, names.length * 12 + 76);
			for (int i = 0; i < names.length; i++) {
				var icon = Markers.icon((byte) i);
				if (icon.isEmpty()) {
					g.fill(lx + 2, y0 + i * 12 + 2, lx + 7, y0 + i * 12 + 7, Markers.color((byte) i));
				} else {
					g.pose().pushMatrix();
					g.pose().translate(lx + 1, y0 + i * 12);
					g.pose().scale(0.5F, 0.5F);
					g.item(icon, 0, 0);
					g.pose().popMatrix();
				}
				g.text(font, names[i], lx + 11, y0 + i * 12, dev.rubymod.client.ui.Ui.DIM, false);
			}
			// World events: a chest, a crystal or ruby ore.
			int ey = y0 + names.length * 12;
			g.pose().pushMatrix();
			g.pose().translate(lx + 1, ey);
			g.pose().scale(0.5F, 0.5F);
			g.item(Markers.icon((byte) 8), 0, 0);
			g.pose().popMatrix();
			g.text(font, "World event", lx + 11, ey, dev.rubymod.client.ui.Ui.DIM, false);
			g.outline(lx + 2, ey + 14, 5, 5, 0xFFFFB040);
			g.text(font, "Final circle", lx + 11, ey + 12, dev.rubymod.client.ui.Ui.DIM, false);
			int cy = ey + 24 + 6;
			g.text(font, "Claims", lx + 2, cy, dev.rubymod.client.ui.Ui.GOLD, false);
			int[] cc = squads ? new int[] {0xFF5BE37D, 0xFF55AAFF, 0xFFFF5050} : new int[] {0xFF5BE37D, 0xFFFF5050};
			String[] cn = squads ? new String[] {"Yours", "Squad", "Other"} : new String[] {"Yours", "Others"};
			int cx = lx + 2;
			for (int i = 0; i < cc.length; i++) {
				g.outline(cx, cy + 13, 5, 5, cc[i]);
				g.text(font, cn[i], cx + 7, cy + 12, dev.rubymod.client.ui.Ui.DIM, false);
				cx += font.width(cn[i]) + 11;
			}
			dev.rubymod.client.ui.Ui.shrinkText(g, (int) Math.round(span) + " blocks across", lx + 2, cy + 26, 86, dev.rubymod.client.ui.Ui.MUTED);
		}
	}

	/** A name on the map, put down once everything's drawn so none lands on another. Lower {@code rank} goes first. */
	private record Label(String text, int x, int y, int color, int rank) {
	}

	private final java.util.List<Label> labels = new java.util.ArrayList<>();

	private void label(String text, int x, int y, int color, int rank) {
		if (text != null && !text.isEmpty()) {
			labels.add(new Label(text, x, y, color, rank));
		}
	}

	/** Each where it was meant to go, or nudged up or down a line if that's taken; left off if there's nowhere near. */
	private void drawLabels(GuiGraphicsExtractor g) {
		java.util.List<Label> order = new java.util.ArrayList<>(labels);
		order.sort(java.util.Comparator.comparingInt(Label::rank));
		java.util.List<int[]> taken = new java.util.ArrayList<>();
		int[] nudges = {0, -10, 10, -20, 20};
		for (Label l : order) {
			int lw = font.width(l.text());
			for (int dy : nudges) {
				int[] r = {l.x() - 1, l.y() + dy - 1, l.x() + lw + 1, l.y() + dy + 9};
				boolean clear = true;
				for (int[] t : taken) {
					if (r[0] < t[2] && t[0] < r[2] && r[1] < t[3] && t[1] < r[3]) {
						clear = false;
						break;
					}
				}
				if (clear) {
					taken.add(r);
					g.text(font, l.text(), l.x(), l.y() + dy, l.color(), true);
					break;
				}
			}
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
