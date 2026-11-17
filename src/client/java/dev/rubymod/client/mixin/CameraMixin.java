package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.client.Vehicles;

import net.minecraft.client.Camera;

/** Free-look: the camera turns by the free-look offset while the pilot's (steering) view stays put. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	/** Flying a Scout Drone: the view turns with your mouse, every frame (the drone itself catches up each tick). */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void rubymod$droneLook(float partialTicks, CallbackInfo ci) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (dev.rubymod.client.DronePilot.flying() && mc.player != null && mc.getCameraEntity() instanceof dev.rubymod.entity.ScoutDroneEntity) {
			setRotation(mc.player.getViewYRot(partialTicks), mc.player.getViewXRot(partialTicks));
		}
	}

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
