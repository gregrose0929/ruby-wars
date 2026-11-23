package dev.rubymod.client;

import dev.rubymod.client.model.PartsModel;
import dev.rubymod.entity.TunnelBorerEntity;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;

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

	/** Each borer's drill: {angle, last frame's time}. */
	private static final Map<TunnelBorerEntity, float[]> DRILL = new WeakHashMap<>();

	@Override
	public void extractRenderState(TunnelBorerEntity entity, PartsModel.State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		// The drill turns as fast as it's spun up (up to two turns a second), and the machine shudders while it bites.
		float[] d = DRILL.computeIfAbsent(entity, e -> new float[] {0, entity.tickCount + partialTicks});
		float now = entity.tickCount + partialTicks;
		d[0] = (d[0] + entity.spin() * 36.0F * Mth.clamp(now - d[1], 0.0F, 3.0F)) % 360.0F;
		d[1] = now;
		state.spin = d[0];
		if (entity.grinding()) {
			float t = now * 2.7F;
			state.shakeX = Mth.sin(t * 3.1F) * 0.025F;
			state.shakeY = Mth.sin(t * 4.3F) * 0.02F;
			state.shakeZ = Mth.sin(t * 2.3F) * 0.02F;
		}
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
