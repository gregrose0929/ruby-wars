package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.RubyMod;
import dev.rubymod.client.model.AircraftModel;
import dev.rubymod.client.model.AircraftModels;
import dev.rubymod.entity.ReconDroneEntity;

import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** The Recon Drone: a small rotor UAV with nav lights and a camera gimbal, rotor spinning. */
public class DroneRenderer extends EntityRenderer<ReconDroneEntity, AircraftRenderer.State> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(RubyMod.id("recon_drone"), "main");
	private static final Identifier TEXTURE = RubyMod.id("textures/entity/aircraft/recon_drone.png");
	private static final Identifier GLOW = RubyMod.id("textures/entity/aircraft/recon_drone_glow.png");
	private final AircraftModel model;

	public DroneRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.5F;
		this.model = new AircraftModel(context.bakeLayer(LAYER));
	}

	public static void registerLayer() {
		ModelLayerRegistry.registerModelLayer(LAYER, AircraftModels::reconDrone);
	}

	@Override
	public AircraftRenderer.State createRenderState() {
		return new AircraftRenderer.State();
	}

	@Override
	public void extractRenderState(ReconDroneEntity entity, AircraftRenderer.State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yaw = entity.getYRot(partialTicks);
		state.rotorAngle = entity.rotorAngle(partialTicks);
		state.gearDown = true;
	}

	@Override
	public void submit(AircraftRenderer.State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, -state.yaw);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		collector.submitModel(model, state, poseStack, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		if ((state.ageInTicks % 20) < 14) {
			collector.submitModel(model, state, poseStack, RenderTypes.eyes(GLOW), 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
