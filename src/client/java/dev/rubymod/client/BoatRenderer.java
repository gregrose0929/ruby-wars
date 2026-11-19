package dev.rubymod.client;

import dev.rubymod.client.model.PartsModel;
import dev.rubymod.entity.BoatEntity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;

/** The war boats: the Patrol Boat, the Gunboat with its deck gun that turns and tilts, and the Landing Craft with its bow ramp. */
public class BoatRenderer extends ModelVehicleRenderer<BoatEntity> {
	private final PartsModel patrol;
	private final PartsModel gunboat;
	private final PartsModel landing;

	public BoatRenderer(EntityRendererProvider.Context context) {
		super(context, 1.2F);
		this.patrol = new PartsModel(context.bakeLayer(layer("patrol_boat")));
		this.gunboat = new PartsModel(context.bakeLayer(layer("gunboat")));
		this.landing = new PartsModel(context.bakeLayer(layer("landing_craft")));
	}

	@Override
	public void extractRenderState(BoatEntity entity, PartsModel.State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		// Which boat, kept in spin (no part spins on a boat).
		state.spin = entity.kind().ordinal();
		state.turretYaw = Mth.wrapDegrees(entity.turretYaw() - state.yaw);
		state.gunPitch = entity.barrelPitch();
		state.ramp = entity.rampDown() ? 1 : 0;
	}

	@Override
	protected PartsModel model(PartsModel.State state) {
		return switch (BoatEntity.Kind.values()[(int) state.spin]) {
			case PATROL -> patrol;
			case GUNBOAT -> gunboat;
			case LANDING -> landing;
		};
	}

	@Override
	protected String texture(PartsModel.State state) {
		return switch (BoatEntity.Kind.values()[(int) state.spin]) {
			case PATROL -> "patrol_boat";
			case GUNBOAT -> "gunboat";
			case LANDING -> "landing_craft";
		};
	}
}
