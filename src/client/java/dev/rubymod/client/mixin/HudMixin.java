package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.rubymod.gun.GunItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;

/** Shrinks the scope overlay for lighter scopes (the rifle's), so you keep more of the screen. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@ModifyVariable(method = "extractSpyglassOverlay", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private float rubymod$scopeSize(float scale) {
		var player = Minecraft.getInstance().player;
		if (player != null && player.isUsingItem() && player.getUseItem().getItem() instanceof GunItem gun) {
			return scale * gun.stats(player.getUseItem()).scopeSize();
		}
		return scale;
	}
}
