package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.entity.LightVehicleEntity;

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

/** The Dirt Bike and the Quad, out of block models: a coloured frame, a seat, handlebars, and wheels that turn. */
public class LightVehicleRenderer extends EntityRenderer<LightVehicleEntity, LightVehicleRenderer.State> {
	public static class State extends EntityRenderState {
		final MovingBlockRenderState frame = new MovingBlockRenderState();
		final MovingBlockRenderState seat = new MovingBlockRenderState();
		final MovingBlockRenderState wheel = new MovingBlockRenderState();
		final MovingBlockRenderState hub = new MovingBlockRenderState();
		final MovingBlockRenderState bars = new MovingBlockRenderState();
		boolean bike;
		float yaw;
		float roll;
	}

	public LightVehicleRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.6F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	private static void fill(MovingBlockRenderState s, BlockState block, LightVehicleEntity e) {
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
	public void extractRenderState(LightVehicleEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.bike = entity.isBike();
		state.yaw = entity.getYRot(partialTicks);
		// Wheels turn with the distance travelled.
		state.roll = (float) ((entity.walkAnimation.position(partialTicks)) * 70.0);
		DyeColor paint = state.bike ? DyeColor.ORANGE : DyeColor.LIME;
		fill(state.frame, Blocks.CONCRETE.pick(paint).defaultBlockState(), entity);
		fill(state.seat, Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), entity);
		fill(state.wheel, Blocks.BLACKSTONE.defaultBlockState(), entity);
		fill(state.hub, Blocks.IRON_BLOCK.defaultBlockState(), entity);
		fill(state.bars, Blocks.POLISHED_BASALT.defaultBlockState(), entity);
	}

	private static void box(PoseStack pose, SubmitNodeCollector c, MovingBlockRenderState s, double x, double y, double z, float w, float h, float l) {
		pose.pushPose();
		pose.translate(x - w / 2, y, z - l / 2);
		pose.scale(w, h, l);
		c.submitMovingBlock(pose, s, 0);
		pose.popPose();
	}

	/** A wheel: a fat black disc (a square turned as it rolls) with a silver middle. */
	private static void wheel(PoseStack pose, SubmitNodeCollector c, State s, double x, double y, double z, float size, float width) {
		pose.pushPose();
		pose.translate(x, y, z);
		pose.rotateDegrees(Axis.XP, s.roll);
		for (int k = 0; k < 2; k++) {
			pose.pushPose();
			pose.rotateDegrees(Axis.XP, k * 45.0F);
			box(pose, c, s.wheel, 0, -size / 2, 0, width, size, size);
			pose.popPose();
		}
		box(pose, c, s.hub, 0, -size * 0.2, 0, width + 0.04F, size * 0.4F, size * 0.4F);
		pose.popPose();
	}

	@Override
	public void submit(State s, PoseStack pose, SubmitNodeCollector c, CameraRenderState camera) {
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, -s.yaw);
		if (s.bike) {
			wheel(pose, c, s, 0, 0.35, 0.65, 0.7F, 0.18F);
			wheel(pose, c, s, 0, 0.35, -0.6, 0.7F, 0.2F);
			box(pose, c, s.frame, 0, 0.45, 0.0, 0.25F, 0.3F, 1.2F);
			box(pose, c, s.frame, 0, 0.7, 0.4, 0.3F, 0.25F, 0.35F);
			box(pose, c, s.seat, 0, 0.7, -0.2, 0.3F, 0.12F, 0.6F);
			box(pose, c, s.bars, 0, 0.9, 0.55, 0.08F, 0.35F, 0.08F);
			box(pose, c, s.bars, 0, 1.2, 0.55, 0.7F, 0.06F, 0.06F);
		} else {
			for (int side = -1; side <= 1; side += 2) {
				wheel(pose, c, s, side * 0.55, 0.3, 0.55, 0.6F, 0.3F);
				wheel(pose, c, s, side * 0.55, 0.3, -0.55, 0.6F, 0.3F);
			}
			box(pose, c, s.frame, 0, 0.35, 0, 0.8F, 0.35F, 1.4F);
			box(pose, c, s.frame, 0, 0.55, 0.55, 1.1F, 0.12F, 0.4F);
			box(pose, c, s.frame, 0, 0.55, -0.6, 1.1F, 0.12F, 0.35F);
			box(pose, c, s.seat, 0, 0.7, -0.25, 0.45F, 0.15F, 0.8F);
			box(pose, c, s.bars, 0, 0.75, 0.5, 0.08F, 0.35F, 0.08F);
			box(pose, c, s.bars, 0, 1.05, 0.5, 0.8F, 0.06F, 0.06F);
		}
		pose.popPose();
		super.submit(s, pose, c, camera);
	}
}
