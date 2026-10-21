package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.block.DefenseBlockEntity;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** The Sentry Turret's gun head: it slews toward whatever it last fired at, and slowly scans when idle. */
public class SentryRenderer implements BlockEntityRenderer<DefenseBlockEntity, SentryRenderer.State> {
	public static class State extends BlockEntityRenderState {
		public boolean sentry;
		public float yaw;
		public final MovingBlockRenderState head = new MovingBlockRenderState();
	}

	public SentryRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(DefenseBlockEntity be, State state, float partial, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(be, state, crumbling);
		state.sentry = be.getBlockState().is(ModBlocks.SENTRY_TURRET);
		if (!state.sentry || be.getLevel() == null) {
			return;
		}
		float time = be.getLevel().getGameTime() + partial;
		float since = time - be.lastShot;
		// Idle: a slow scan. After firing: hold on the target, then ease back into the scan.
		float scan = (time * 0.8F + (be.getBlockPos().asLong() % 360)) % 360.0F;
		float hold = Mth.clamp((since - 40.0F) / 30.0F, 0.0F, 1.0F);
		state.yaw = since < 40.0F ? be.aimYaw : be.aimYaw + Mth.wrapDegrees(scan - be.aimYaw) * hold;
		state.head.randomSeedPos = be.getBlockPos();
		state.head.blockPos = be.getBlockPos();
		state.head.blockState = ModBlocks.SENTRY_HEAD.defaultBlockState();
		if (be.getLevel() instanceof ClientLevel level) {
			state.head.biome = level.getBiome(be.getBlockPos());
			state.head.cardinalLighting = level.cardinalLighting();
			state.head.lightEngine = level.getLightEngine();
		}
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!state.sentry) {
			return;
		}
		pose.pushPose();
		pose.translate(0.5, 0.0, 0.5);
		// The head model faces north (yaw 180); turn it to face the aim yaw.
		pose.rotateDegrees(Axis.YP, 180.0F - state.yaw);
		pose.translate(-0.5, 0.0, -0.5);
		collector.submitMovingBlock(pose, state.head, 0);
		pose.popPose();
	}
}
