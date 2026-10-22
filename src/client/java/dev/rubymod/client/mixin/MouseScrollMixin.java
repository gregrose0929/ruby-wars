package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.client.Vehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

/** While piloting an aircraft (no hotbar there), the scroll wheel zooms the view instead of changing slots. */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void rubymod$aircraftZoom(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || mc.gui.screen() != null || mc.gui.overlay() != null) {
			return;
		}
		double amount = yoffset != 0 ? yoffset : -xoffset;
		if (Vehicles.scrollZoom(mc, amount)) {
			ci.cancel();
		}
	}
}
