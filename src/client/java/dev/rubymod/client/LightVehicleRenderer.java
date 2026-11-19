package dev.rubymod.client;

import dev.rubymod.client.model.PartsModel;
import dev.rubymod.entity.LightVehicleEntity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

/** The Dirt Bike (orange, with a number plate and knobbly tyres) and the Quad (green, four fat wheels), handlebars that steer. */
public class LightVehicleRenderer extends ModelVehicleRenderer<LightVehicleEntity> {
	private final PartsModel bike;
	private final PartsModel quad;


	public LightVehicleRenderer(EntityRendererProvider.Context context) {
		super(context, 0.6F);
		this.bike = new PartsModel(context.bakeLayer(layer("dirt_bike")), "wheel_f=5.6", "wheel_r=5.6");
		this.quad = new PartsModel(context.bakeLayer(layer("quad")), "wheel_fl=4.8", "wheel_fr=4.8", "wheel_rl=4.8", "wheel_rr=4.8");
	}


	@Override
	public void extractRenderState(LightVehicleEntity entity, PartsModel.State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.ramp = entity.isBike() ? 1 : 0;
		state.steer *= 2.0F;
	}

	@Override
	protected PartsModel model(PartsModel.State state) {
		return state.ramp > 0 ? bike : quad;
	}

	@Override
	protected String texture(PartsModel.State state) {
		return state.ramp > 0 ? "dirt_bike" : "quad";
	}
}
