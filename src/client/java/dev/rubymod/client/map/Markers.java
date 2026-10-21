package dev.rubymod.client.map;

import org.joml.Vector3fc;

import dev.rubymod.client.ClientState;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Markers in the world: waypoints, your grave, pings, supply drops and Hearts as
 * diamonds with a name and distance (pinned to the screen edge when off to the
 * side), and squadmates' names with health bars, visible through walls.
 */
public class Markers implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		MorePayloads.MapSync map = ClientState.map;
		if (mc.player == null || map == null) {
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		Vec3 eye = camera.position();
		long now = System.currentTimeMillis();
		for (MorePayloads.Marker m : map.markers()) {
			Vec3 at = new Vec3(m.x() + 0.5, m.y() + 1.2, m.z() + 0.5);
			double dist = at.distanceTo(eye);
			if (m.kind() == 4 && dist < 24 || m.kind() == 1 && dist < 3) {
				continue; // standing at it
			}
			int color = color(m.kind());
			if (m.kind() == 2 && (now / 250) % 2 == 0) {
				color = 0xFFFFE070;
			}
			float[] s = project(camera, at, w, h);
			boolean edge = s == null || s[0] < 8 || s[0] > w - 8 || s[1] < 8 || s[1] > h - 8;
			if (s == null) {
				s = behind(camera, at, w, h);
			}
			int x = Mth.clamp(Math.round(s[0]), 10, w - 10);
			int y = Mth.clamp(Math.round(s[1]), 18, h - 30);
			diamond(g, x, y, color);
			if (!edge || m.kind() == 2) {
				String label = m.name() + "  " + Math.round(dist) + "m";
				int tw = font.width(label);
				g.fill(x - tw / 2 - 3, y - 17, x + tw / 2 + 3, y - 7, 0x90000000);
				g.centeredText(font, label, x, y - 16, color);
			}
		}
		for (MorePayloads.Mate mate : map.mates()) {
			Vec3 at = new Vec3(mate.x(), mate.y() + 2.3, mate.z());
			float[] s = project(camera, at, w, h);
			if (s == null) {
				continue;
			}
			int x = Math.round(s[0]);
			int y = Math.round(s[1]);
			String label = mate.name() + "  " + Math.round(at.distanceTo(eye)) + "m";
			int tw = font.width(label);
			g.fill(x - tw / 2 - 3, y - 12, x + tw / 2 + 3, y + 2, 0x90000000);
			g.centeredText(font, label, x, y - 11, 0xFF7CF59A);
			int bar = Math.max(1, Math.round((tw + 4) * Mth.clamp(mate.health(), 0, 1)));
			g.fill(x - tw / 2 - 2, y - 1, x - tw / 2 - 2 + bar, y + 1, mate.health() > 0.5F ? 0xFF5BE37D : mate.health() > 0.25F ? 0xFFFFB040 : 0xFFFF4040);
		}
	}

	public static int color(byte kind) {
		return switch (kind) {
			case 1 -> 0xFFFFD040;    // grave
			case 2 -> 0xFFFFFFFF;    // ping
			case 3 -> 0xFF40E0FF;    // supply drop
			case 4 -> 0xFFFF3D6E;    // your Heart
			case 5 -> 0xFF5BE37D;    // squad Heart
			case 6 -> 0xFFFF4040;    // raid
			case 7 -> 0xFFFF2A2A;    // spotted enemy
			default -> 0xFF6FB6FF;   // waypoint
		};
	}

	public static String compass(float yaw) {
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		return names[Math.floorMod(Math.round(yaw / 45.0F), 8)];
	}

	private static void diamond(GuiGraphicsExtractor g, int x, int y, int color) {
		for (int i = 0; i <= 4; i++) {
			g.fill(x - i, y - 5 + i, x + i + 1, y - 4 + i, 0xFF101010);
			g.fill(x - i, y + 5 - i, x + i + 1, y + 6 - i, 0xFF101010);
		}
		for (int i = 0; i <= 3; i++) {
			g.fill(x - i, y - 3 + i, x + i + 1, y - 2 + i, color);
			g.fill(x - i, y + 3 - i, x + i + 1, y + 4 - i, color);
		}
	}

	/** Screen position of a world point, or null if it's behind you. */
	public static float[] project(Camera camera, Vec3 world, int w, int h) {
		Vec3 rel = world.subtract(camera.position());
		Vector3fc f = camera.forwardVector();
		Vector3fc u = camera.upVector();
		Vector3fc l = camera.leftVector();
		double z = rel.x * f.x() + rel.y * f.y() + rel.z * f.z();
		if (z < 0.1) {
			return null;
		}
		double x = rel.x * l.x() + rel.y * l.y() + rel.z * l.z();
		double y = rel.x * u.x() + rel.y * u.y() + rel.z * u.z();
		double scale = (h / 2.0) / Math.tan(Math.toRadians(camera.getFov() / 2.0));
		return new float[] {(float) (w / 2.0 - x / z * scale), (float) (h / 2.0 - y / z * scale)};
	}

	/** For points behind you: the screen edge in their direction. */
	private static float[] behind(Camera camera, Vec3 world, int w, int h) {
		Vec3 rel = world.subtract(camera.position());
		Vector3fc l = camera.leftVector();
		double x = rel.x * l.x() + rel.y * l.y() + rel.z * l.z();
		return new float[] {x > 0 ? 0 : w, h * 0.5F};
	}
}
