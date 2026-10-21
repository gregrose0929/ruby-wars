package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.gun.GunItem;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Aiming a scoped gun (the sniper) counts as "scoping", like a spyglass. That
 * gives it the vanilla scope overlay, hides the hand, zooms way in, and slows
 * the mouse for precise aim.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "isScoping", at = @At("RETURN"), cancellable = true)
	private void rubymod$sniperScope(CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (!cir.getReturnValueZ() && self.isUsingItem()) {
			ItemStack used = self.getUseItem();
			if (used.getItem() instanceof GunItem gun && gun.stats(used).zoom() <= 0.3F) {
				cir.setReturnValue(true);
			}
		}
	}
}
