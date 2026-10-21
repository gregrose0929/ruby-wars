package dev.rubymod.client.gun;

import java.util.ArrayDeque;
import java.util.Iterator;

import org.joml.Matrix4f;

import dev.rubymod.RubyMod;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Bullet holes on walls: small scorched decals that fade out after 30 seconds (the newest 256 are kept). */
public final class BulletHoles {
	private static final Identifier TEXTURE = RubyMod.id("textures/misc/bullet_hole.png");
	private static final int MAX = 256;
	private static final long LIFE = 30_000L;
	private static final ArrayDeque<Hole> HOLES = new ArrayDeque<>();

	private record Hole(Vec3 pos, Direction face, float size, float spin, long at) {
	}

	private BulletHoles() {
	}

	public static void add(MorePayloads.BulletHole packet) {
		float size = packet.size() == 0 ? 0.07F : packet.size() == 2 ? 0.16F : 0.11F;
		HOLES.addLast(new Hole(new Vec3(packet.x(), packet.y(), packet.z()), Direction.from3DDataValue(packet.face()), size,
				(float) (Math.random() * Math.PI * 2), System.currentTimeMillis()));
		while (HOLES.size() > MAX) {
			HOLES.removeFirst();
		}
	}

	public static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (HOLES.isEmpty() || mc.level == null) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Iterator<Hole> it = HOLES.iterator(); it.hasNext();) {
			Hole h = it.next();
			if (now - h.at > LIFE || mc.level.getBlockState(BlockPos.containing(h.pos.subtract(Vec3.atLowerCornerOf(h.face.getUnitVec3i()).scale(0.01)))).isAir()) {
				it.remove();
			}
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		var pose = context.poseStack();
		pose.pushPose();
		pose.translate(-cam.x, -cam.y, -cam.z);
		context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.entityTranslucent(TEXTURE), (p, vc) -> {
			Matrix4f m = p.pose();
			for (Hole h : HOLES) {
				float alpha = Math.min(1.0F, (LIFE - (now - h.at)) / 5000.0F);
				Vec3 n = Vec3.atLowerCornerOf(h.face.getUnitVec3i());
				Vec3 c = h.pos.add(n.scale(0.004));
				// Two axes across the face, rotated by the hole's random spin.
				Vec3 u = h.face.getAxis() == Direction.Axis.Y ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
				Vec3 v = n.cross(u);
				double cs = Math.cos(h.spin), sn = Math.sin(h.spin);
				Vec3 a = u.scale(cs).add(v.scale(sn)).scale(h.size);
				Vec3 b = v.scale(cs).subtract(u.scale(sn)).scale(h.size);
				int light = LightCoordsUtil.pack(mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, BlockPos.containing(c.add(n.scale(0.3)))),
						mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, BlockPos.containing(c.add(n.scale(0.3)))));
				int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
				vertex(vc, m, c.subtract(a).subtract(b), 0, 0, color, light, n);
				vertex(vc, m, c.subtract(a).add(b), 0, 1, color, light, n);
				vertex(vc, m, c.add(a).add(b), 1, 1, color, light, n);
				vertex(vc, m, c.add(a).subtract(b), 1, 0, color, light, n);
			}
		});
		pose.popPose();
	}

	private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc, Matrix4f m, Vec3 p, float u, float v, int color, int light, Vec3 n) {
		vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal((float) n.x, (float) n.y, (float) n.z);
	}
}
