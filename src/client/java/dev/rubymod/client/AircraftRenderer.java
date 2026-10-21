package dev.rubymod.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.RubyMod;
import dev.rubymod.client.model.AircraftModel;
import dev.rubymod.client.model.AircraftModels;
import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType;

import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Draws aircraft with their textured, animated models (see tools/aircraft_models.py),
 * pitched and banked as they fly, plus a full-bright pass for nav lights and
 * engine glow (strobes blink).
 */
public class AircraftRenderer extends EntityRenderer<AircraftEntity, AircraftRenderer.State> {
	/** Height of the pitch/roll pivot above the wheels, in blocks. */
	private static final float PIVOT = 1.0F;
	private static final Map<AircraftType, ModelLayerLocation> LAYERS = new HashMap<>();

	public static class State extends EntityRenderState {
		public AircraftType kind;
		public float yaw;
		public float pitch;
		public float roll;
		public float pitchRate;
		public float yawRate;
		public float rotorAngle;
		public boolean gearDown = true;
		public boolean hidden;
		public int paint;
	}

	private final Map<AircraftType, AircraftModel> models = new HashMap<>();

	public AircraftRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 2.0F;
		for (var entry : LAYERS.entrySet()) {
			models.put(entry.getKey(), new AircraftModel(context.bakeLayer(entry.getValue())));
		}
	}

	/** Called once at client start. */
	public static void registerLayers() {
		layer(AircraftType.FIGHTER, AircraftModels::fighterJet);
		layer(AircraftType.ATTACK, AircraftModels::attackJet);
		layer(AircraftType.BOMBER, AircraftModels::bomber);
		layer(AircraftType.HELICOPTER, AircraftModels::attackHeli);
	}

	private static void layer(AircraftType kind, ModelLayerRegistry.TexturedLayerDefinitionProvider provider) {
		ModelLayerLocation location = new ModelLayerLocation(RubyMod.id(kind.id()), "main");
		LAYERS.put(kind, location);
		ModelLayerRegistry.registerModelLayer(location, provider);
	}

	private static Identifier texture(AircraftType kind, boolean glow) {
		return RubyMod.id("textures/entity/aircraft/" + kind.id() + (glow ? "_glow" : "") + ".png");
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(AircraftEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.kind = entity.kind();
		state.paint = entity.paint();
		state.yaw = entity.getYRot(partialTicks);
		state.pitch = entity.getXRot(partialTicks);
		state.roll = entity.roll();
		state.pitchRate = entity.getXRot() - entity.xRotO;
		state.yawRate = net.minecraft.util.Mth.wrapDegrees(entity.getYRot() - entity.yRotO);
		state.gearDown = entity.gearDown() || entity.onGround();
		boolean running = entity.isVehicle() || !entity.onGround();
		state.rotorAngle = running ? (entity.tickCount + partialTicks) * 1.3F : 0.0F;
		// Truly first person: the pilot doesn't see their own airframe, just the HUD.
		Minecraft mc = Minecraft.getInstance();
		state.hidden = entity.hasPassenger(mc.player) && mc.options.getCameraType().isFirstPerson();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		AircraftModel model = models.get(state.kind);
		if (state.hidden || model == null) {
			super.submit(state, poseStack, collector, camera);
			return;
		}
		poseStack.pushPose();
		poseStack.translate(0, PIVOT, 0);
		poseStack.rotateDegrees(Axis.YP, -state.yaw);
		poseStack.rotateDegrees(Axis.XP, state.pitch);
		poseStack.rotateDegrees(Axis.ZP, -state.roll);
		poseStack.translate(0, -PIVOT, 0);
		float s = state.kind.scale();
		poseStack.scale(s, s, s);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		String scheme = dev.rubymod.item.PaintKitItem.scheme(state.paint);
		Identifier skin = scheme.isEmpty() ? texture(state.kind, false) : RubyMod.id("textures/entity/aircraft/" + state.kind.id() + "_" + scheme + ".png");
		collector.submitModel(model, state, poseStack, skin, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		// Lights and engine glow, full-bright; the strobes blink.
		if ((state.ageInTicks % 30) < 22) {
			collector.submitModel(model, state, poseStack, RenderTypes.eyes(texture(state.kind, true)), 0xF000F0, OverlayTexture.NO_OVERLAY,
					state.outlineColor);
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
