package dev.rubymod.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.RubyMod;
import dev.rubymod.client.model.VehicleModel;
import dev.rubymod.client.model.VehicleModels;
import dev.rubymod.entity.VehicleEntity;

import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Draws the Jeep and Tank with their textured, animated models (see
 * tools/vehicle_models.py): wheels that turn with the ground covered, sliding
 * track links, a traversing turret, a recoiling gun with a muzzle flash, and
 * the jeep's steering. Headlights and tail lights glow.
 */
public class VehicleRenderer extends EntityRenderer<VehicleEntity, VehicleRenderer.State> {
	public static final ModelLayerLocation TANK = new ModelLayerLocation(RubyMod.id("tank"), "main");
	public static final ModelLayerLocation JEEP = new ModelLayerLocation(RubyMod.id("jeep"), "main");
	private static final Map<VehicleEntity, float[]> TRAVEL = new WeakHashMap<>();

	public static class State extends EntityRenderState {
		public boolean tank;
		public float yaw;
		/** Turret angle relative to the hull, degrees. */
		public float turretYaw;
		public float barrelPitch;
		/** Pixels driven (signed), for the wheels and tracks. */
		public float distance;
		public float steer;
		public float recoil;
		public boolean flash;
		public float flashRoll;
	}

	private final VehicleModel tank;
	private final VehicleModel jeep;

	public VehicleRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 2.0F;
		this.tank = new VehicleModel(context.bakeLayer(TANK));
		this.jeep = new VehicleModel(context.bakeLayer(JEEP));
	}

	public static void registerLayers() {
		ModelLayerRegistry.registerModelLayer(TANK, VehicleModels::tank);
		ModelLayerRegistry.registerModelLayer(JEEP, VehicleModels::jeep);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(VehicleEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.tank = entity.isTank();
		state.yaw = entity.getYRot(partialTicks);
		state.turretYaw = state.tank ? Mth.wrapDegrees(entity.turretYaw() - state.yaw) : 0.0F;
		state.barrelPitch = entity.barrelPitch();
		// Distance driven: accumulate movement along the hull's heading (negative when reversing).
		float[] t = TRAVEL.computeIfAbsent(entity, e -> new float[] {(float) e.getX(), (float) e.getZ(), 0.0F, 0.0F});
		double x = Mth.lerp(partialTicks, entity.xo, entity.getX());
		double z = Mth.lerp(partialTicks, entity.zo, entity.getZ());
		double dx = x - t[0];
		double dz = z - t[1];
		if (dx * dx + dz * dz < 4.0) {
			double yawRad = Math.toRadians(state.yaw);
			double forward = -dx * Math.sin(yawRad) + dz * Math.cos(yawRad);
			t[2] += (float) forward * 16.0F;
		}
		t[0] = (float) x;
		t[1] = (float) z;
		state.distance = t[2];
		float yawRate = Mth.wrapDegrees(entity.getYRot() - entity.yRotO);
		t[3] = Mth.lerp(0.2F, t[3], Mth.clamp(yawRate * 0.12F, -0.5F, 0.5F));
		state.steer = t[3];
		int reload = entity.reloadLeft();
		int since = VehicleEntity.RELOAD_TICKS - reload;
		state.recoil = reload > 0 && since < 8 ? 1.0F - since / 8.0F : 0.0F;
		state.flash = reload > 0 && since < 2;
		state.flashRoll = (entity.tickCount * 0.7F) % 6.28F;
	}

	private static Identifier texture(boolean tank, boolean glow) {
		return RubyMod.id("textures/entity/vehicle/" + (tank ? "tank" : "jeep") + (glow ? "_glow" : "") + ".png");
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		VehicleModel model = state.tank ? tank : jeep;
		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, -state.yaw);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		collector.submitModel(model, state, poseStack, texture(state.tank, false), state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		collector.submitModel(model, state, poseStack, RenderTypes.eyes(texture(state.tank, true)), 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
