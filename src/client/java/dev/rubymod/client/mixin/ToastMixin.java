package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** No "New Recipes Unlocked" or "Advancement Made" pop-ups: they mean nothing in these games, and cover the corner of the screen. */
@Mixin(ToastManager.class)
public abstract class ToastMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void rubymod$quiet(Toast toast, CallbackInfo ci) {
		if (toast instanceof AdvancementToast || toast instanceof RecipeToast) {
			ci.cancel();
		}
	}
}
