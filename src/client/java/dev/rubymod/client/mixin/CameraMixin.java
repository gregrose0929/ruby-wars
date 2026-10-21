package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.rubymod.client.Vehicles;

import net.minecraft.client.Camera;

/** Free-look: the camera turns by the free-look offset while the pilot's (steering) view stays put. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0), index = 0)
	private float rubymod$freeYaw0(float yaw) {
		return yaw + Vehicles.freeYaw;
	}

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0), index = 1)
	private float rubymod$freePitch0(float pitch) {
		return pitch + Vehicles.freePitch;
	}

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1), index = 0)
	private float rubymod$freeYaw1(float yaw) {
		return yaw + Vehicles.freeYaw;
	}

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1), index = 1)
	private float rubymod$freePitch1(float pitch) {
		return pitch + Vehicles.freePitch;
	}
}
