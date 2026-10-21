package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.client.Vehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

/** In a vehicle the view is truly first person: no hands, no held item. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void rubymod$noHandsInVehicles(CallbackInfo ci) {
		if (Vehicles.inVehicle(Minecraft.getInstance())) {
			ci.cancel();
		}
	}
}
