package dev.rubymod.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Draws nothing, but (unlike the game's own "draw nothing" renderer) still counts as on screen, so LambDynamicLights
 * lights it up: muzzle flashes, illumination flares, burning wrecks.
 */
public class InvisibleRenderer<T extends Entity> extends EntityRenderer<T, EntityRenderState> {
	public InvisibleRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public EntityRenderState createRenderState() {
		return new EntityRenderState();
	}
}
