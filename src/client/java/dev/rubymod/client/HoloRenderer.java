package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.rubymod.block.HoloBlockEntity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** Holo Camouflage: draws the block it's pretending to be. */
public class HoloRenderer implements BlockEntityRenderer<HoloBlockEntity, HoloRenderer.State> {
	public static class State extends BlockEntityRenderState {
		public MovingBlockRenderState look;
	}

	public HoloRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(HoloBlockEntity be, State state, float partial, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(be, state, crumbling);
		state.look = null;
		if (be.disguise() == null || !(be.getLevel() instanceof ClientLevel level)) {
			return;
		}
		MovingBlockRenderState look = new MovingBlockRenderState();
		look.randomSeedPos = be.getBlockPos();
		look.blockPos = be.getBlockPos();
		look.blockState = be.disguise();
		look.biome = level.getBiome(be.getBlockPos());
		look.cardinalLighting = level.cardinalLighting();
		look.lightEngine = level.getLightEngine();
		state.look = look;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.look != null) {
			collector.submitMovingBlock(pose, state.look, 0);
		}
	}

	@Override
	public int getViewDistance() {
		return 256;
	}
}
