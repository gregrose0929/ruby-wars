package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.rubymod.block.MineBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** A Landmine or AT Mine: drawn only for those who can see it (its base's side); everyone else sees bare ground. */
public class MineRenderer implements BlockEntityRenderer<MineBlockEntity, MineRenderer.State> {
	public static class State extends BlockEntityRenderState {
		public MovingBlockRenderState look;
	}

	public MineRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(MineBlockEntity be, State state, float partial, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(be, state, crumbling);
		state.look = null;
		if (!(be.getLevel() instanceof ClientLevel level) || !be.visibleTo(Minecraft.getInstance().getCameraEntity())) {
			return;
		}
		MovingBlockRenderState look = new MovingBlockRenderState();
		look.randomSeedPos = be.getBlockPos();
		look.blockPos = be.getBlockPos();
		look.blockState = be.getBlockState();
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
		return 96;
	}
}
