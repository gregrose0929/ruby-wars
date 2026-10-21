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
		if (firstPerson && GunItem.isAiming(self) && !self.isScoping() && self.getUseItem().getItem() instanceof GunItem gun) {
			cir.setReturnValue(cir.getReturnValueF() * gun.stats(self.getUseItem()).zoom());
		}
	}
}
