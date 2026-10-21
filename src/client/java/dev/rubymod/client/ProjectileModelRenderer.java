package dev.rubymod.client;

import java.util.function.Function;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a missile, shell, or bomb as a small 3D model (models/block/*_model.json)
 * pointed along its flight path, so it always faces the way it's flying.
 */
public class ProjectileModelRenderer<T extends Entity> extends EntityRenderer<T, ProjectileModelRenderer.State> {
	private final Function<T, Block> model;
	private final float scale;

	public static class State extends EntityRenderState {
		public final MovingBlockRenderState block = new MovingBlockRenderState();
		public float yaw;
		public float pitch;
	}

	public ProjectileModelRenderer(EntityRendererProvider.Context context, Function<T, Block> model, float scale) {
		super(context);
		this.model = model;
		this.scale = scale;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(T entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		Vec3 v = entity.getDeltaMovement();
		double horizontal = Math.sqrt(v.x * v.x + v.z * v.z);
		if (v.lengthSqr() > 1.0E-4) {
			// Same conventions as an entity's look: yaw 0 = south, pitch positive = down.
			state.yaw = (float) Math.toDegrees(Mth.atan2(-v.x, v.z));
			state.pitch = (float) -Math.toDegrees(Mth.atan2(v.y, horizontal));
		} else {
			state.yaw = entity.getYRot();
			state.pitch = 0;
		}
		BlockPos pos = entity.blockPosition();
		state.block.randomSeedPos = BlockPos.ZERO;
		state.block.blockPos = pos;
		state.block.blockState = model.apply(entity).defaultBlockState();
		if (entity.level() instanceof ClientLevel level) {
			state.block.biome = level.getBiome(pos);
			state.block.cardinalLighting = level.cardinalLighting();
			state.block.lightEngine = level.getLightEngine();
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.translate(0, 0.15, 0);
		poseStack.rotateDegrees(Axis.YP, 180.0F - state.yaw);
		poseStack.rotateDegrees(Axis.XP, -state.pitch);
		poseStack.scale(scale, scale, scale);
		poseStack.translate(-0.5, -0.5, -0.5);
		collector.submitMovingBlock(poseStack, state.block, state.outlineColor);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
