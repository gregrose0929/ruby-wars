package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.gun.GunItem;

import net.minecraft.client.player.AbstractClientPlayer;

/** Zoom in a little while aiming a gun. (The sniper uses the full scope view instead, see PlayerMixin.) */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void rubymod$aimZoom(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		if (firstPerson && self == net.minecraft.client.Minecraft.getInstance().player
				&& dev.rubymod.client.VehicleHud.zoomed(net.minecraft.client.Minecraft.getInstance())) {
			cir.setReturnValue(cir.getReturnValueF() * 0.3F); // Tank gunner sight.
			return;
		}
		if (firstPerson && self.isUsingItem() && self.getUseItem().getItem() instanceof dev.rubymod.item.LockOnLauncherItem) {
			cir.setReturnValue(cir.getReturnValueF() * 0.7F);
			return;
		}
		if (!firstPerson || !GunItem.isAiming(self) || !(self.getUseItem().getItem() instanceof GunItem gun)) {
			return;
		}
		float zoom = gun.stats(self.getUseItem()).zoom();
		if (!self.isScoping()) {
			cir.setReturnValue(cir.getReturnValueF() * zoom);
		} else if (zoom > 0.25F) {
			// Vanilla scoping zooms to 0.1 (spyglass). Lighter scopes like the rifle's zoom less.
			cir.setReturnValue(zoom);
		}
	}
}
