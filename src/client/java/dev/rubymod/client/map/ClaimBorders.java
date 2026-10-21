package dev.rubymod.client.map;

import dev.rubymod.client.ClientState;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Claim borders in the world: when you're within 16 blocks of a claim's edge, a
 * band outline shows where it runs (green yours, blue your squad's, red someone
 * else's), around your height so you can see it at a glance.
 */
public final class ClaimBorders {
	private static final double NEAR = 16.0;

	private ClaimBorders() {
	}

	public static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		MorePayloads.MapSync map = ClientState.map;
		if (mc.player == null || map == null) {
			return;
		}
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		double py = Math.floor(mc.player.getY());
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		for (MorePayloads.ClaimInfo c : map.claims()) {
			double x0 = c.x() - c.radius();
			double x1 = c.x() + c.radius() + 1;
			double z0 = c.z() - c.radius();
			double z1 = c.z() + c.radius() + 1;
			// Distance to the nearest edge (inside or outside).
			double dx = Math.max(x0 - px, Math.max(0, px - x1));
			double dz = Math.max(z0 - pz, Math.max(0, pz - z1));
			boolean inside = dx == 0 && dz == 0;
			double edge = inside ? Math.min(Math.min(px - x0, x1 - px), Math.min(pz - z0, z1 - pz)) : Math.hypot(dx, dz);
			if (edge > NEAR) {
				continue;
			}
			int alpha = (int) (200 * (1.0 - edge / NEAR)) + 40;
			int rgb = c.relation() == 0 ? 0x5BE37D : c.relation() == 1 ? 0x55AAFF : 0xFF5050;
			var pose = context.poseStack();
			pose.pushPose();
			pose.translate(x0 - cam.x, py - 1 - cam.y, z0 - cam.z);
			for (int band = 0; band < 3; band++) {
				var shape = Shapes.box(0, band * 2.0, 0, x1 - x0, band * 2.0 + 0.02, z1 - z0);
				context.submitNodeCollector().submitShapeOutline(pose, shape, RenderTypes.linesTranslucent(), (alpha << 24) | rgb, 2.0F, false);
			}
			pose.popPose();
		}
	}
}
