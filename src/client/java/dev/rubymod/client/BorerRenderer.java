package dev.rubymod.client;

import dev.rubymod.client.model.PartsModel;
import dev.rubymod.entity.TunnelBorerEntity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

/** The Tunnel Borer: a yellow digger on tracks with a cab at the back and a big drill on the front that spins while it works. */
public class BorerRenderer extends ModelVehicleRenderer<TunnelBorerEntity> {
	private final PartsModel model;

	public BorerRenderer(EntityRendererProvider.Context context) {
		super(context, 1.1F);
		String[] wheels = new String[12];
		int i = 0;
		for (String side : new String[] {"l", "r"}) {
			for (int k = 0; k < 4; k++) {
				wheels[i++] = "wheel_" + side + k + "=3.6";
			}
			wheels[i++] = "wheel_" + side + "s=4.2";
			wheels[i++] = "wheel_" + side + "i=4.0";
		}
		this.model = new PartsModel(context.bakeLayer(layer("tunnel_borer")), wheels);
	}

	@Override
	public void extractRenderState(TunnelBorerEntity entity, PartsModel.State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		boolean working = entity.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4 || entity.isVehicle();
		state.spin = working ? (entity.tickCount + partialTicks) * 18.0F : 0;
	}

	@Override
	protected PartsModel model(PartsModel.State state) {
		return model;
	}

	@Override
	protected String texture(PartsModel.State state) {
		return "tunnel_borer";
	}
}
