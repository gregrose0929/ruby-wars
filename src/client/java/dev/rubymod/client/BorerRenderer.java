package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.entity.TunnelBorerEntity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The Tunnel Borer, out of block models: a yellow body, a dark cab on the back, and a big spinning drill on the front. */
public class BorerRenderer extends EntityRenderer<TunnelBorerEntity, BorerRenderer.State> {
	public static class State extends EntityRenderState {
		final MovingBlockRenderState body = new MovingBlockRenderState();
		final MovingBlockRenderState cab = new MovingBlockRenderState();
		final MovingBlockRenderState drill = new MovingBlockRenderState();
		final MovingBlockRenderState tip = new MovingBlockRenderState();
		final MovingBlockRenderState track = new MovingBlockRenderState();
		float yaw;
		float spin;
	}

	public BorerRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 1.0F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	private static void fill(MovingBlockRenderState s, BlockState block, TunnelBorerEntity e) {
		BlockPos pos = e.blockPosition();
		s.randomSeedPos = BlockPos.ZERO;
		s.blockPos = pos;
		s.blockState = block;
		if (e.level() instanceof ClientLevel level) {
			s.biome = level.getBiome(pos);
			s.cardinalLighting = level.cardinalLighting();
			s.lightEngine = level.getLightEngine();
		}
	}

	@Override
	public void extractRenderState(TunnelBorerEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yaw = entity.getYRot(partialTicks);
		boolean moving = entity.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4 || entity.isVehicle();
		state.spin = moving ? (entity.tickCount + partialTicks) * 18.0F : 0;
		fill(state.body, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState(), entity);
		fill(state.cab, Blocks.TINTED_GLASS.defaultBlockState(), entity);
		fill(state.drill, Blocks.IRON_BLOCK.defaultBlockState(), entity);
		fill(state.tip, Blocks.POLISHED_DEEPSLATE.defaultBlockState(), entity);
		fill(state.track, Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), entity);
	}

	/** A block model stretched to this box (x, y, z from the middle-bottom of the machine, facing +z). */
	private static void box(PoseStack pose, SubmitNodeCollector c, MovingBlockRenderState s, double x, double y, double z, float w, float h, float l) {
		pose.pushPose();
		pose.translate(x - w / 2, y, z - l / 2);
		pose.scale(w, h, l);
		c.submitMovingBlock(pose, s, 0);
		pose.popPose();
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, -state.yaw);
		// Tracks either side, the body, the cab at the back.
		box(pose, collector, state.track, -0.75, 0, -0.1, 0.4F, 0.5F, 2.1F);
		box(pose, collector, state.track, 0.75, 0, -0.1, 0.4F, 0.5F, 2.1F);
		box(pose, collector, state.body, 0, 0.35, -0.2, 1.5F, 0.75F, 1.9F);
		box(pose, collector, state.cab, 0, 1.1, -0.65, 0.9F, 0.5F, 0.8F);
		// The drill: three rings, smaller and smaller, spinning, then the point.
		pose.pushPose();
		pose.translate(0, 0.75, 0.9);
		pose.rotateDegrees(Axis.ZP, state.spin);
		float[] sizes = {1.3F, 0.95F, 0.6F};
		for (int i = 0; i < sizes.length; i++) {
			pose.pushPose();
			pose.rotateDegrees(Axis.ZP, i * 30.0F);
			box(pose, collector, state.drill, 0, -sizes[i] / 2, 0.2 + i * 0.3, sizes[i], sizes[i], 0.3F);
			pose.popPose();
		}
		box(pose, collector, state.tip, 0, -0.15, 1.15, 0.3F, 0.3F, 0.35F);
		pose.popPose();
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
