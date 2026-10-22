package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.client.Vehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** While free-looking, mouse movement turns the camera, not the player (who steers the vehicle). */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
	private static boolean rubymod$scaling;

	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void rubymod$freeLook(double xo, double yo, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this == mc.player && dev.rubymod.client.Designation.weaponCam) {
			ci.cancel(); // The aircraft holds its course while you watch the weapon.
			return;
		}
		double scale = (Object) this == mc.player ? 1.0 / Vehicles.zoom : 1.0;
		if ((Object) this == mc.player && Vehicles.freeLooking(mc)) {
			Vehicles.turn(xo * scale, yo * scale);
			ci.cancel();
		} else if (scale < 1.0 && !rubymod$scaling) {
			// Zoomed in an aircraft: the same mouse movement turns less.
			rubymod$scaling = true;
			try {
				((Entity) (Object) this).turn(xo * scale, yo * scale);
			} finally {
				rubymod$scaling = false;
			}
			ci.cancel();
		}
	}
}
