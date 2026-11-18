package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.client.StormClient;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

/** Battle Day storms: thick fog inside one. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void rubymod$stormFog(Camera camera, int renderDistance, DeltaTracker delta, float darken, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
		dev.rubymod.client.BattleSky.fog(cir.getReturnValue());
		StormClient.fog(cir.getReturnValue());
	}
}
