package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.client.Vehicles;
import dev.rubymod.gun.GunItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Zoom: a little while aiming a gun (the sniper uses the full scope view instead, see
 * PlayerMixin), the tank / AA gun sights while right-click is held, lock-on launchers
 * while aiming, and on top of all that the scroll-wheel zoom (1x to 8x).
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void rubymod$aimZoom(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		Minecraft mc = Minecraft.getInstance();
		if (self != mc.player) {
			return;
		}
		float value = cir.getReturnValueF();
		if (dev.rubymod.client.VehicleHud.zoomed(mc)) {
			value *= 0.3F; // Tank / AA gun sight.
		} else if (self.isUsingItem() && self.getUseItem().getItem() instanceof dev.rubymod.item.LockOnLauncherItem) {
			value *= 0.7F;
		} else if (firstPerson && GunItem.isAiming(self) && self.getUseItem().getItem() instanceof GunItem gun) {
			float zoom = gun.stats(self.getUseItem()).zoom();
			if (!self.isScoping()) {
				value *= zoom;
			} else if (zoom > 0.25F) {
				// Vanilla scoping zooms to 0.1 (spyglass). Lighter scopes like the rifle's zoom less.
				value = zoom;
			}
		}
		if (Vehicles.zoom > 1) {
			value /= Vehicles.zoom; // Scroll-wheel zoom.
		}
		cir.setReturnValue(value);
	}
}
