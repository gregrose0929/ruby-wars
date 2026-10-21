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
import net.minecraft.world.level.block.Block;

/**
 * Draws the Jeep and Tank from block models (models/block/*_model.json),
 * scaled up. The Tank is three parts: the hull follows the body, the turret
 * turns on its own, and the barrel tilts inside the turret.
 */
public class VehicleRenderer extends EntityRenderer<VehicleEntity, VehicleRenderer.State> {
	public static class State extends EntityRenderState {
		public final MovingBlockRenderState hull = new MovingBlockRenderState();
		public final MovingBlockRenderState turret = new MovingBlockRenderState();
		public final MovingBlockRenderState barrel = new MovingBlockRenderState();
		public boolean tank;
		public float yaw;
		public float turretYaw;
		public float barrelPitch;
		public float scale;
	}

	public VehicleRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 1.6F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	private static void fill(MovingBlockRenderState block, Block model, VehicleEntity entity, BlockPos pos) {
		block.randomSeedPos = BlockPos.ZERO;
		block.blockPos = pos;
		block.blockState = model.defaultBlockState();
		if (entity.level() instanceof ClientLevel level) {
			block.biome = level.getBiome(pos);
			block.cardinalLighting = level.cardinalLighting();
			block.lightEngine = level.getLightEngine();
		}
	}

	@Override
	public void extractRenderState(VehicleEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.tank = entity.isTank();
		state.yaw = entity.getYRot(partialTicks);
		state.turretYaw = entity.turretYaw();
		state.barrelPitch = entity.barrelPitch();
		state.scale = state.tank ? 3.0F : 2.4F;
		BlockPos pos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
		fill(state.hull, state.tank ? ModBlocks.TANK_MODEL : ModBlocks.JEEP_MODEL, entity, pos);
		if (state.tank) {
			fill(state.turret, ModBlocks.TANK_TURRET_MODEL, entity, pos);
			fill(state.barrel, ModBlocks.TANK_BARREL_MODEL, entity, pos);
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		float s = state.scale;
		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, 180.0F - state.yaw);
		poseStack.scale(s, s, s);
		poseStack.translate(-0.5, 0.0, -0.5);
		collector.submitMovingBlock(poseStack, state.hull, state.outlineColor);
		poseStack.popPose();

		if (state.tank) {
			// Turret: sits on the hull deck (y = 7/16 of a model block) and turns around its center.
			poseStack.pushPose();
			poseStack.rotateDegrees(Axis.YP, 180.0F - state.turretYaw);
			poseStack.scale(s, s, s);
			poseStack.translate(-0.5, 7.0 / 16.0, -0.5);
			collector.submitMovingBlock(poseStack, state.turret, state.outlineColor);
			// Barrel: tilts around its mount at (8, 1.6, 3.5).
			poseStack.translate(0.5, 1.6 / 16.0, 3.5 / 16.0);
			poseStack.rotateDegrees(Axis.XP, -state.barrelPitch);
			poseStack.translate(-0.5, -1.6 / 16.0, -3.5 / 16.0);
			collector.submitMovingBlock(poseStack, state.barrel, state.outlineColor);
			poseStack.popPose();
		}
		super.submit(state, poseStack, collector, camera);
	}
}
