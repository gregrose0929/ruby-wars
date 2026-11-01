package dev.rubymod.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.rubymod.game.Duels;
import dev.rubymod.game.party.AirBattle;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * The edge of the Air Battle: see-through red walls and a ceiling with a faint grid, fading
 * in as you get close. They don't stop anything (the game warns you, then you're out); they
 * just show where the edge is. Drawn right here on the client, so they can't pop in and out.
 */
public final class AirWalls {
	/** How close you get before an edge starts to show. */
	private static final double SHOW = 220;
	private static final double GRID = 32;
	private static final int RED = 0xFF3048;

	private AirWalls() {
	}

	public static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.level.dimension() != Duels.ARENA) {
			return;
		}
		double dx = mc.player.getX() - AirBattle.CENTER_X;
		double dz = mc.player.getZ();
		int area = AirBattle.AREA;
		// Only out over the airfield (nobody's near it unless they're in the battle).
		if (Math.abs(dx) > area + 300 || Math.abs(dz) > area + 300) {
			return;
		}
		double y0 = Duels.GROUND;
		double y1 = Duels.GROUND + 1 + AirBattle.CEILING;
		double x0 = AirBattle.CENTER_X - area;
		double x1 = AirBattle.CENTER_X + area;
		double z0 = -area;
		double z1 = area;
		double py = mc.player.getY();
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		var pose = context.poseStack();
		pose.pushPose();
		pose.translate(-cam.x, -cam.y, -cam.z);
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, vc) -> {
			Matrix4f m = p.pose();
			wallZ(vc, m, z0, x0, x1, y0, y1, px, py, Math.abs(pz - z0));
			wallZ(vc, m, z1, x0, x1, y0, y1, px, py, Math.abs(pz - z1));
			wallX(vc, m, x0, z0, z1, y0, y1, pz, py, Math.abs(px - x0));
			wallX(vc, m, x1, z0, z1, y0, y1, pz, py, Math.abs(px - x1));
			ceiling(vc, m, y1, x0, x1, z0, z1, px, pz, Math.abs(py - y1));
		});
		pose.popPose();
	}

	private static float strength(double distance) {
		return distance >= SHOW ? 0 : (float) (1 - distance / SHOW);
	}

	private static int color(float a) {
		return Math.round(a * 255) << 24 | RED;
	}

	/** A wall along x, at this z. */
	private static void wallZ(VertexConsumer vc, Matrix4f m, double z, double x0, double x1, double y0, double y1, double px, double py, double distance) {
		float s = strength(distance);
		if (s <= 0) {
			return;
		}
		int fill = color(0.04F + 0.16F * s);
		quad(vc, m, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, fill);
		int line = color(0.25F + 0.5F * s);
		for (double x = Math.ceil(Math.max(x0, px - SHOW) / GRID) * GRID; x <= Math.min(x1, px + SHOW); x += GRID) {
			quad(vc, m, x - 0.3, y0, z, x + 0.3, y0, z, x + 0.3, y1, z, x - 0.3, y1, z, line);
		}
		for (double y = Math.ceil(y0 / GRID) * GRID; y <= y1; y += GRID) {
			double a = Math.max(x0, px - SHOW);
			double b = Math.min(x1, px + SHOW);
			quad(vc, m, a, y - 0.3, z, b, y - 0.3, z, b, y + 0.3, z, a, y + 0.3, z, line);
		}
	}

	/** A wall along z, at this x. */
	private static void wallX(VertexConsumer vc, Matrix4f m, double x, double z0, double z1, double y0, double y1, double pz, double py, double distance) {
		float s = strength(distance);
		if (s <= 0) {
			return;
		}
		int fill = color(0.04F + 0.16F * s);
		quad(vc, m, x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0, fill);
		int line = color(0.25F + 0.5F * s);
		for (double z = Math.ceil(Math.max(z0, pz - SHOW) / GRID) * GRID; z <= Math.min(z1, pz + SHOW); z += GRID) {
			quad(vc, m, x, y0, z - 0.3, x, y0, z + 0.3, x, y1, z + 0.3, x, y1, z - 0.3, line);
		}
		for (double y = Math.ceil(y0 / GRID) * GRID; y <= y1; y += GRID) {
			double a = Math.max(z0, pz - SHOW);
			double b = Math.min(z1, pz + SHOW);
			quad(vc, m, x, y - 0.3, a, x, y - 0.3, b, x, y + 0.3, b, x, y + 0.3, a, line);
		}
	}

	private static void ceiling(VertexConsumer vc, Matrix4f m, double y, double x0, double x1, double z0, double z1, double px, double pz, double distance) {
		float s = strength(distance);
		if (s <= 0) {
			return;
		}
		quad(vc, m, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, color(0.04F + 0.14F * s));
		int line = color(0.25F + 0.5F * s);
		double a = Math.max(z0, pz - SHOW);
		double b = Math.min(z1, pz + SHOW);
		for (double x = Math.ceil(Math.max(x0, px - SHOW) / GRID) * GRID; x <= Math.min(x1, px + SHOW); x += GRID) {
			quad(vc, m, x - 0.3, y, a, x + 0.3, y, a, x + 0.3, y, b, x - 0.3, y, b, line);
		}
		double c = Math.max(x0, px - SHOW);
		double d = Math.min(x1, px + SHOW);
		for (double z = Math.ceil(Math.max(z0, pz - SHOW) / GRID) * GRID; z <= Math.min(z1, pz + SHOW); z += GRID) {
			quad(vc, m, c, y, z - 0.3, d, y, z - 0.3, d, y, z + 0.3, c, y, z + 0.3, line);
		}
	}

	private static void quad(VertexConsumer vc, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
			double dx, double dy, double dz, int color) {
		vc.addVertex(m, (float) ax, (float) ay, (float) az).setColor(color);
		vc.addVertex(m, (float) bx, (float) by, (float) bz).setColor(color);
		vc.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(color);
		vc.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(color);
	}
}
