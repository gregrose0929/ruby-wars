package dev.rubymod.client.gun;

import java.util.List;
import java.util.function.Consumer;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.rubymod.client.model.GunModel;
import dev.rubymod.gun.GunItem;
import dev.rubymod.registry.ModComponents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a gun item with its 3D model everywhere it isn't held in first person:
 * inventory slots, other players' hands, item frames, the ground. Attachments
 * fitted to the gun show on the model. (First person is {@link GunView}.)
 *
 * Item space: the gun points along +X with its grip at (6, 5) pixels.
 */
public final class GunItemRenderer implements SpecialModelRenderer<GunModel.Pose> {
	public static final float ORIGIN_X = 6.0F;
	public static final float ORIGIN_Y = 5.0F;
	private final String gun;

	private GunItemRenderer(String gun) {
		this.gun = gun;
	}

	@Override
	public GunModel.Pose extractArgument(ItemStack stack) {
		GunModel.Pose pose = new GunModel.Pose();
		pose.attachments = stack.getOrDefault(ModComponents.ATTACHMENTS, List.<String>of());
		pose.rift = GunModel.isRift(stack);
		pose.riftColor = GunModel.riftColor(stack);
		var player = Minecraft.getInstance().player;
		if (player != null && !(stack.getItem() instanceof GunItem)) {
			// Launchers show the rocket in the tube unless they're reloading.
			pose.loaded = player.getCooldowns().getCooldownPercent(stack, 0.0F) <= 0.0F;
		}
		return pose;
	}

	@Override
	public void submit(GunModel.Pose pose, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
		GunModel model = GunModel.get(gun);
		if (model == null || pose == null) {
			return;
		}
		poseStack.pushPose();
		poseStack.translate(ORIGIN_X / 16.0F, ORIGIN_Y / 16.0F, 0.5F);
		poseStack.rotateDegrees(Axis.YP, 90.0F);
		model.submit(pose, poseStack, collector, light, overlay, outline);
		poseStack.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		GunModel model = GunModel.get(gun);
		if (model == null) {
			return;
		}
		float[] lo = model.anchor("bounds_min");
		float[] hi = model.anchor("bounds_max");
		for (int i = 0; i < 8; i++) {
			float x = (i & 1) == 0 ? lo[0] : hi[0];
			float y = (i & 2) == 0 ? lo[1] : hi[1];
			float z = (i & 4) == 0 ? lo[2] : hi[2];
			// Gun (x left, y up, z forward) -> item (z + ox, y + oy, 8 - x), in blocks.
			output.accept(new Vector3f((z + ORIGIN_X) / 16.0F, (y + ORIGIN_Y) / 16.0F, (8.0F - x) / 16.0F));
		}
	}

	public record Unbaked(String gun) implements SpecialModelRenderer.Unbaked<GunModel.Pose> {
		public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.STRING.fieldOf("gun").forGetter(Unbaked::gun)).apply(i, Unbaked::new));

		@Override
		public SpecialModelRenderer<GunModel.Pose> bake(SpecialModelRenderer.BakingContext context) {
			GunModel.bake(gun, context.entityModelSet());
			return new GunItemRenderer(gun);
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
