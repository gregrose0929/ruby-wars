package dev.rubymod.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.RubyMod;
import dev.rubymod.client.model.PartsModel;

import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * Draws a vehicle with its generated model (tools/more_vehicles.py), the way the tank is drawn: textured, lit, with
 * its lights glowing, wheels that turn with the ground covered and whatever else moves on it (see PartsModel).
 */
public abstract class ModelVehicleRenderer<E extends Entity> extends EntityRenderer<E, PartsModel.State> {
	private static final Map<Entity, float[]> TRAVEL = new WeakHashMap<>();

	protected ModelVehicleRenderer(EntityRendererProvider.Context context, float shadow) {
		super(context);
		this.shadowRadius = shadow;
	}

	public static ModelLayerLocation layer(String name) {
		return new ModelLayerLocation(RubyMod.id(name), "main");
	}

	public static void registerLayers() {
		ModelLayerRegistry.registerModelLayer(layer("tunnel_borer"), dev.rubymod.client.model.VehicleModels::tunnel_borer);
		ModelLayerRegistry.registerModelLayer(layer("dirt_bike"), dev.rubymod.client.model.VehicleModels::dirt_bike);
		ModelLayerRegistry.registerModelLayer(layer("quad"), dev.rubymod.client.model.VehicleModels::quad);
		ModelLayerRegistry.registerModelLayer(layer("patrol_boat"), dev.rubymod.client.model.VehicleModels::patrol_boat);
		ModelLayerRegistry.registerModelLayer(layer("gunboat"), dev.rubymod.client.model.VehicleModels::gunboat);
		ModelLayerRegistry.registerModelLayer(layer("landing_craft"), dev.rubymod.client.model.VehicleModels::landing_craft);
		// Gear, drawn only as items (see items/flamethrower.json and friends).
		ModelLayerRegistry.registerModelLayer(layer("flamethrower"), dev.rubymod.client.model.VehicleModels::flamethrower);
		ModelLayerRegistry.registerModelLayer(layer("grappling_hook"), dev.rubymod.client.model.VehicleModels::grappling_hook);
		ModelLayerRegistry.registerModelLayer(layer("night_goggles"), dev.rubymod.client.model.VehicleModels::night_goggles);
		ModelLayerRegistry.registerModelLayer(layer("keycard"), dev.rubymod.client.model.VehicleModels::keycard);
		ModelLayerRegistry.registerModelLayer(layer("fire_bomb"), dev.rubymod.client.model.VehicleModels::fire_bomb);
	}

	@Override
	public PartsModel.State createRenderState() {
		return new PartsModel.State();
	}

	/** Which model, and its texture name (textures/entity/vehicle/NAME.png, and NAME_glow.png for the lights). */
	protected abstract PartsModel model(PartsModel.State state);

	protected abstract String texture(PartsModel.State state);

	@Override
	public void extractRenderState(E entity, PartsModel.State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.yaw = entity.getYRot(partialTicks);
		// Distance covered along the heading (negative backing up), and how fast it's turning.
		float[] t = TRAVEL.computeIfAbsent(entity, e -> new float[] {(float) e.getX(), (float) e.getZ(), 0.0F, 0.0F});
		double x = Mth.lerp(partialTicks, entity.xo, entity.getX());
		double z = Mth.lerp(partialTicks, entity.zo, entity.getZ());
		double dx = x - t[0];
		double dz = z - t[1];
		if (dx * dx + dz * dz < 4.0) {
			double yawRad = Math.toRadians(state.yaw);
			t[2] += (float) (-dx * Math.sin(yawRad) + dz * Math.cos(yawRad)) * 16.0F;
		}
		t[0] = (float) x;
		t[1] = (float) z;
		state.distance = t[2];
		float yawRate = Mth.wrapDegrees(entity.getYRot() - entity.yRotO);
		t[3] = Mth.lerp(0.2F, t[3], Mth.clamp(yawRate * 0.12F, -0.5F, 0.5F));
		state.steer = t[3];
	}

	@Override
	public void submit(PartsModel.State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		PartsModel model = model(state);
		String name = texture(state);
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, -state.yaw);
		if (state.shakeX != 0 || state.shakeY != 0 || state.shakeZ != 0) {
			pose.translate(state.shakeX, state.shakeY, state.shakeZ);
		}
		if (state.pitch != 0) {
			pose.translate(0, 0, -state.pitchPivot);
			pose.rotateDegrees(Axis.XP, -state.pitch);
			pose.translate(0, 0, state.pitchPivot);
		}
		if (state.roll != 0) {
			pose.rotateDegrees(Axis.ZP, state.roll);
		}
		pose.scale(-1.0F, -1.0F, 1.0F);
		collector.submitModel(model, state, pose, tex(name), state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		collector.submitModel(model, state, pose, RenderTypes.eyes(tex(name + "_glow")), 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}

	private static Identifier tex(String name) {
		return RubyMod.id("textures/entity/vehicle/" + name + ".png");
	}
}
