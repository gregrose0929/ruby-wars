package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.industry.OreSiloBlock;
import dev.rubymod.industry.SiloBlockEntity;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;

/** The Ore Silo: what it holds, seen through the window on its front. */
public class SiloRenderer implements BlockEntityRenderer<SiloBlockEntity, SiloRenderer.State> {
	public static class State extends BlockEntityRenderState {
		public final ItemStackRenderState item = new ItemStackRenderState();
		public Direction facing = Direction.NORTH;
		public int frontLight;
	}

	private final ItemModelResolver items;

	public SiloRenderer(BlockEntityRendererProvider.Context context) {
		this.items = context.itemModelResolver();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(SiloBlockEntity be, State state, float partial, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(be, state, crumbling);
		state.facing = be.getBlockState().hasProperty(OreSiloBlock.FACING) ? be.getBlockState().getValue(OreSiloBlock.FACING) : Direction.NORTH;
		state.frontLight = be.getLevel() == null ? state.lightCoords : LightCoordsUtil.getLightCoords(be.getLevel(), be.getBlockPos().relative(state.facing));
		items.updateForTopItem(state.item, be.shownType(), ItemDisplayContext.FIXED, be.getLevel(), null, (int) be.getBlockPos().asLong());
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.item.isEmpty()) {
			return;
		}
		pose.pushPose();
		pose.translate(0.5F, 0.5F, 0.5F);
		pose.rotateDegrees(Axis.YP, 180.0F - state.facing.toYRot());
		// Just in front of the window (6-10 across, 3.5-8 up, on the front face).
		pose.translate(0.0F, 5.75F / 16 - 0.5F, -(0.5F - 1.3F / 16));
		pose.rotateDegrees(Axis.YP, 180.0F);
		pose.scale(0.27F, 0.27F, 0.27F);
		state.item.submit(pose, collector, state.frontLight, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}
}
