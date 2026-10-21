package dev.rubymod.client.gun;

import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Vehicle and aircraft items show a miniature of the real entity model (the
 * same textured geometry you see in the world), scaled to fill the slot.
 */
public final class ModelIconRenderer implements NoDataSpecialModelRenderer {
	private final Model<Object> model;
	private final Identifier texture;
	private final Identifier glow;
	private final Vector3f center;
	private final float scale;

	private ModelIconRenderer(ModelPart root, Identifier texture) {
		this.model = new Model<>(root, RenderTypes::entityCutout) {
		};
		this.texture = texture;
		this.glow = Identifier.fromNamespaceAndPath(texture.getNamespace(), texture.getPath().replace(".png", "_glow.png"));
		// Bounds of every cube, in blocks, in model space.
		Vector3f lo = new Vector3f(Float.MAX_VALUE);
		Vector3f hi = new Vector3f(-Float.MAX_VALUE);
		root.visit(new PoseStack(), (pose, path, index, cube) -> {
			Matrix4f m = pose.pose();
			for (int i = 0; i < 8; i++) {
				Vector3f p = m.transformPosition(new Vector3f(
						((i & 1) == 0 ? cube.minX : cube.maxX) / 16.0F,
						((i & 2) == 0 ? cube.minY : cube.maxY) / 16.0F,
						((i & 4) == 0 ? cube.minZ : cube.maxZ) / 16.0F));
				lo.min(p);
				hi.max(p);
			}
		});
		this.center = new Vector3f(lo).add(hi).mul(0.5F);
		Vector3f size = new Vector3f(hi).sub(lo);
		this.scale = 1.0F / Math.max(0.01F, Math.max(size.x, Math.max(size.y, size.z)));
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
		pose.pushPose();
		pose.translate(0.5F, 0.5F, 0.5F);
		pose.scale(scale, scale, scale);
		pose.scale(-1.0F, -1.0F, 1.0F);
		pose.translate(-center.x, -center.y, -center.z);
		collector.submitModel(model, Boolean.TRUE, pose, RenderTypes.entityCutout(texture), light, overlay, outline);
		collector.submitModel(model, Boolean.TRUE, pose, RenderTypes.eyes(glow), 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		for (int i = 0; i < 8; i++) {
			output.accept(new Vector3f((i & 1) * 1.0F, ((i >> 1) & 1) * 1.0F, ((i >> 2) & 1) * 1.0F));
		}
	}

	public record Unbaked(String layer, String texture) implements NoDataSpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.STRING.fieldOf("layer").forGetter(Unbaked::layer),
				Codec.STRING.fieldOf("texture").forGetter(Unbaked::texture)).apply(i, Unbaked::new));

		@Override
		public ModelIconRenderer bake(SpecialModelRenderer.BakingContext context) {
			ModelPart root = context.entityModelSet().bakeLayer(new ModelLayerLocation(Identifier.parse(layer), "main"));
			return new ModelIconRenderer(root, Identifier.parse(texture));
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
