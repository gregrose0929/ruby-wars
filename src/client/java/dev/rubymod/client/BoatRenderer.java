package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.entity.BoatEntity;

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

/** The war boats, out of block models: a grey-green hull, a cabin, and the Gunboat's turret or the Landing Craft's ramp. */
public class BoatRenderer extends EntityRenderer<BoatEntity, BoatRenderer.State> {
	public static class State extends EntityRenderState {
		final MovingBlockRenderState hull = new MovingBlockRenderState();
		final MovingBlockRenderState deck = new MovingBlockRenderState();
		final MovingBlockRenderState trim = new MovingBlockRenderState();
		final MovingBlockRenderState glass = new MovingBlockRenderState();
		final MovingBlockRenderState gun = new MovingBlockRenderState();
		BoatEntity.Kind kind;
		float yaw;
		float gunYaw;
		float gunPitch;
		float ramp;
	}

	public BoatRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 1.2F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	private static void fill(MovingBlockRenderState s, BlockState block, BoatEntity e) {
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
	public void extractRenderState(BoatEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.kind = entity.kind();
		state.yaw = entity.getYRot(partialTicks);
		state.gunYaw = entity.turretYaw();
		state.gunPitch = entity.barrelPitch();
		// The ramp eases down and up.
		float want = entity.rampDown() ? 1 : 0;
		state.ramp = want;
		fill(state.hull, Blocks.CONCRETE.pick(state.kind == BoatEntity.Kind.PATROL ? DyeColor.GRAY : DyeColor.GREEN).defaultBlockState(), entity);
		fill(state.deck, Blocks.SPRUCE_PLANKS.defaultBlockState(), entity);
		fill(state.trim, Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState(), entity);
		fill(state.glass, Blocks.TINTED_GLASS.defaultBlockState(), entity);
		fill(state.gun, Blocks.IRON_BLOCK.defaultBlockState(), entity);
	}

	private static void box(PoseStack pose, SubmitNodeCollector c, MovingBlockRenderState s, double x, double y, double z, float w, float h, float l) {
		pose.pushPose();
		pose.translate(x - w / 2, y, z - l / 2);
		pose.scale(w, h, l);
		c.submitMovingBlock(pose, s, 0);
		pose.popPose();
	}

	@Override
	public void submit(State s, PoseStack pose, SubmitNodeCollector c, CameraRenderState camera) {
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, -s.yaw);
		switch (s.kind) {
			case PATROL -> {
				box(pose, c, s.hull, 0, -0.1, 0, 1.8F, 0.55F, 3.4F);
				box(pose, c, s.hull, 0, -0.1, 1.85, 1.1F, 0.55F, 0.4F);
				box(pose, c, s.deck, 0, 0.4, -0.2, 1.6F, 0.08F, 3.0F);
				box(pose, c, s.trim, 0, 0.45, 0.6, 1.2F, 0.5F, 0.15F);
				box(pose, c, s.glass, 0, 0.95, 0.6, 1.2F, 0.35F, 0.1F);
			}
			case GUNBOAT -> {
				box(pose, c, s.hull, 0, -0.15, 0, 2.2F, 0.7F, 4.4F);
				box(pose, c, s.hull, 0, -0.15, 2.35, 1.4F, 0.7F, 0.5F);
				box(pose, c, s.deck, 0, 0.5, 0, 2.0F, 0.08F, 4.0F);
				box(pose, c, s.trim, 0, 0.55, -0.6, 1.3F, 0.9F, 1.2F);
				box(pose, c, s.glass, 0, 1.15, -0.05, 1.1F, 0.3F, 0.08F);
				// The deck gun, on its own turn and tilt.
				pose.pushPose();
				pose.translate(0, 0.6, 1.4);
				pose.rotateDegrees(Axis.YP, -(s.gunYaw - s.yaw));
				box(pose, c, s.trim, 0, 0, 0, 0.8F, 0.4F, 0.8F);
				pose.translate(0, 0.45, 0);
				pose.rotateDegrees(Axis.XP, s.gunPitch);
				box(pose, c, s.gun, 0, -0.1, 0.8, 0.18F, 0.18F, 1.6F);
				pose.popPose();
			}
			case LANDING -> {
				box(pose, c, s.hull, 0, -0.15, -0.3, 2.8F, 0.75F, 5.2F);
				box(pose, c, s.trim, -1.35, 0.55, -0.3, 0.12F, 0.7F, 5.2F);
				box(pose, c, s.trim, 1.35, 0.55, -0.3, 0.12F, 0.7F, 5.2F);
				box(pose, c, s.deck, 0, 0.55, -0.3, 2.6F, 0.06F, 5.0F);
				box(pose, c, s.trim, 0, 0.55, -2.6, 1.6F, 1.1F, 0.6F);
				// The bow ramp: up (closed), or swung down onto the beach.
				pose.pushPose();
				pose.translate(0, 0.55, 2.3);
				pose.rotateDegrees(Axis.XP, s.ramp * 100.0F);
				box(pose, c, s.hull, 0, 0, 0.05, 2.6F, 1.3F, 0.12F);
				pose.popPose();
			}
		}
		pose.popPose();
		super.submit(s, pose, c, camera);
	}
}
