package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.RubyMod;
import dev.rubymod.client.model.AircraftModels;
import dev.rubymod.entity.HeavyAAGunEntity;

import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** The Heavy AA Gun: the mount turns with the gunner, the barrels elevate and recoil when firing. */
public class HeavyAAGunRenderer extends EntityRenderer<HeavyAAGunEntity, HeavyAAGunRenderer.State> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(RubyMod.id("heavy_aa_gun"), "main");
	public static final ModelLayerLocation RAILGUN_LAYER = new ModelLayerLocation(RubyMod.id("railgun"), "main");
	private final Identifier texture;
	private final Identifier glow;
	private final float scale;

	public static class State extends EntityRenderState {
		public float baseYaw;
		public float turretYaw;
		public float gunPitch;
		public float recoil;
		public boolean hidden;
	}

	static class Model extends EntityModel<State> {
		private final ModelPart turret;
		private final ModelPart guns;

		Model(ModelPart root) {
			super(root, RenderTypes::entityCutout);
			this.turret = root.getChild("turret");
			this.guns = turret.getChild("guns");
		}

		@Override
		public void setupAnim(State state) {
			super.setupAnim(state);
			turret.yRot = (float) Math.toRadians(-(state.turretYaw - state.baseYaw));
			guns.xRot = (float) Math.toRadians(state.gunPitch);
			guns.z -= state.recoil * 2.5F;
		}
	}

	private final Model model;

	public HeavyAAGunRenderer(EntityRendererProvider.Context context) {
		this(context, LAYER, "heavy_aa_gun", 1.25F);
	}

	public HeavyAAGunRenderer(EntityRendererProvider.Context context, ModelLayerLocation layer, String name, float scale) {
		super(context);
		this.shadowRadius = 1.6F;
		this.model = new Model(context.bakeLayer(layer));
		this.texture = RubyMod.id("textures/entity/aircraft/" + name + ".png");
		this.glow = RubyMod.id("textures/entity/aircraft/" + name + "_glow.png");
		this.scale = scale;
	}

	public static HeavyAAGunRenderer railgun(EntityRendererProvider.Context context) {
		return new HeavyAAGunRenderer(context, RAILGUN_LAYER, "railgun", 1.2F);
	}

	public static void registerLayer() {
		ModelLayerRegistry.registerModelLayer(LAYER, AircraftModels::heavyAaGun);
		ModelLayerRegistry.registerModelLayer(RAILGUN_LAYER, AircraftModels::railgun);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(HeavyAAGunEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.baseYaw = entity.getYRot();
		state.turretYaw = entity.turretYaw();
		state.gunPitch = entity.gunPitch();
		float t = entity.recoil + partialTicks;
		state.recoil = t < 4 ? Mth.sin(t / 4.0F * (float) Math.PI) : 0.0F;
		// The gunner sees through the sight, not the inside of the gun shield.
		Minecraft mc = Minecraft.getInstance();
		state.hidden = entity.hasPassenger(mc.player) && mc.options.getCameraType().isFirstPerson();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.hidden) {
			return;
		}
		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, -state.baseYaw);
		poseStack.scale(scale, scale, scale);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		collector.submitModel(model, state, poseStack, texture, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		collector.submitModel(model, state, poseStack, RenderTypes.eyes(glow), 0xF000F0, OverlayTexture.NO_OVERLAY, state.outlineColor);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
