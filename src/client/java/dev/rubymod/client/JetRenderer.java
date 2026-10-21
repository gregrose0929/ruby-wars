package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.entity.JetEntity;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;

/** Draws the jet from models/block/jet_model.json, pitched and banked as it flies. */
public class JetRenderer extends EntityRenderer<JetEntity, JetRenderer.State> {
	private static final float SCALE = 2.6F;

	public static class State extends EntityRenderState {
		public final MovingBlockRenderState model = new MovingBlockRenderState();
		public float yaw;
		public float pitch;
		public float roll;
	}

	public JetRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 1.5F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(JetEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yaw = entity.getYRot(partialTicks);
		state.pitch = entity.getXRot(partialTicks);
		state.roll = entity.roll();
		BlockPos pos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
		state.model.randomSeedPos = BlockPos.ZERO;
		state.model.blockPos = pos;
		state.model.blockState = ModBlocks.JET_MODEL.defaultBlockState();
		if (entity.level() instanceof ClientLevel level) {
			state.model.biome = level.getBiome(pos);
			state.model.cardinalLighting = level.cardinalLighting();
			state.model.lightEngine = level.getLightEngine();
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.translate(0, 0.4, 0);
		poseStack.rotateDegrees(Axis.YP, 180.0F - state.yaw);
		poseStack.rotateDegrees(Axis.XP, -state.pitch);
		poseStack.rotateDegrees(Axis.ZP, state.roll);
		poseStack.scale(SCALE, SCALE, SCALE);
		poseStack.translate(-0.5, -4.5 / 16.0, -0.5);
		collector.submitMovingBlock(poseStack, state.model, state.outlineColor);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
