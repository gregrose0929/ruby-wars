package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.client.gun.ScopeOverlay;
import dev.rubymod.gun.GunItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;

/** Scoped guns get a real rifle scope instead of the spyglass view; aiming down sights hides the crosshair. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractSpyglassOverlay", at = @At("HEAD"), cancellable = true)
	private void rubymod$scope(GuiGraphicsExtractor graphics, float scale, CallbackInfo ci) {
		var player = Minecraft.getInstance().player;
		if (player != null && player.isUsingItem() && player.getUseItem().getItem() instanceof GunItem) {
			ScopeOverlay.draw(graphics, scale);
			ci.cancel();
		}
	}

	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void rubymod$adsCrosshair(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
		if (ScopeOverlay.hideCrosshair()) {
			ci.cancel();
			var player = Minecraft.getInstance().player;
			if (player != null && dev.rubymod.client.gun.GunView.ironSights(player)) {
				// Iron sights sit just under the aim point; this dot is where the bullet goes.
				int cx = graphics.guiWidth() / 2;
				int cy = graphics.guiHeight() / 2;
				graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xB0000000);
				graphics.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFF3030);
			}
		}
	}
}
