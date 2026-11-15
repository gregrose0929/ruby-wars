package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.client.RideView;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

/** On a ride, in first person, the car (or log, kart, chair...) you're sitting in isn't drawn: it never blocks your view. */
@Mixin(EntityRenderDispatcher.class)
public abstract class RideViewMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void rubymod$hideOwnRide(E entity, Frustum frustum, double x, double y, double z, float partial,
			CallbackInfoReturnable<Boolean> cir) {
		if (RideView.hide(entity)) {
			cir.setReturnValue(false);
		}
	}
}
