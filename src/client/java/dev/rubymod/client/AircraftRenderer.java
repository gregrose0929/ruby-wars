package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType;
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

/** Draws an aircraft from its block model (fighter, attack jet, or bomber), pitched and banked as it flies, wheels on the ground. */
public class AircraftRenderer extends EntityRenderer<AircraftEntity, AircraftRenderer.State> {
	/** Height of the pitch/roll pivot above the wheels, in blocks. */
	private static final float PIVOT = 0.4F;

	public static class State extends EntityRenderState {
		public final MovingBlockRenderState model = new MovingBlockRenderState();
		public float yaw;
		public float pitch;
		public float roll;
		public float scale;
		public float gear;
	}

	public AircraftRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 1.5F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	private static Block model(AircraftType kind) {
		if (kind == AircraftType.ATTACK) {
			return ModBlocks.ATTACK_JET_MODEL;
		}
		if (kind == AircraftType.BOMBER) {
			return ModBlocks.BOMBER_MODEL;
		}
		return ModBlocks.JET_MODEL;
	}

	@Override
	public void extractRenderState(AircraftEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yaw = entity.getYRot(partialTicks);
		state.pitch = entity.getXRot(partialTicks);
		state.roll = entity.roll();
		state.scale = entity.kind().scale();
		state.gear = (float) entity.kind().gearHeight();
		BlockPos pos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
		state.model.randomSeedPos = BlockPos.ZERO;
		state.model.blockPos = pos;
		state.model.blockState = model(entity.kind()).defaultBlockState();
		if (entity.level() instanceof ClientLevel level) {
			state.model.biome = level.getBiome(pos);
			state.model.cardinalLighting = level.cardinalLighting();
			state.model.lightEngine = level.getLightEngine();
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.translate(0, PIVOT, 0);
		poseStack.rotateDegrees(Axis.YP, 180.0F - state.yaw);
		poseStack.rotateDegrees(Axis.XP, -state.pitch);
		poseStack.rotateDegrees(Axis.ZP, state.roll);
		poseStack.scale(state.scale, state.scale, state.scale);
		// Put the bottom of the landing gear on the ground, under the pivot.
		poseStack.translate(-0.5, -state.gear / 16.0 - PIVOT / state.scale, -0.5);
		collector.submitMovingBlock(poseStack, state.model, state.outlineColor);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
