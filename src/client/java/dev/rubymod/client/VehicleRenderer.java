package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;

/**
 * Draws a vehicle as a scaled-up block model (models/block/jeep_model.json and
 * tank_model.json). A placeholder until proper animated models exist.
 */
public class VehicleRenderer extends EntityRenderer<VehicleEntity, VehicleRenderer.State> {
	public static class State extends EntityRenderState {
		public final MovingBlockRenderState block = new MovingBlockRenderState();
		public float yaw;
		public float scale;
	}

	public VehicleRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 1.4F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(VehicleEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yaw = entity.getYRot(partialTicks);
		state.scale = entity.isTank() ? 3.0F : 2.4F;
		BlockPos pos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
		state.block.randomSeedPos = BlockPos.ZERO;
		state.block.blockPos = pos;
		state.block.blockState = (entity.isTank() ? ModBlocks.TANK_MODEL : ModBlocks.JEEP_MODEL).defaultBlockState();
		if (entity.level() instanceof ClientLevel level) {
			state.block.biome = level.getBiome(pos);
			state.block.cardinalLighting = level.cardinalLighting();
			state.block.lightEngine = level.getLightEngine();
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.yaw));
		poseStack.scale(state.scale, state.scale, state.scale);
		poseStack.translate(-0.5, 0.0, -0.5);
		collector.submitMovingBlock(poseStack, state.block, state.outlineColor);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
