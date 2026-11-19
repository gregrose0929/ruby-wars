package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.game.Stats;
import dev.rubymod.game.Veterans;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Veteran stars after a player's name: one gold ★ per Ruby Wars season played (see Veterans). */
@Mixin(Player.class)
public abstract class PlayerNameMixin {
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void rubymod$veteranStars(CallbackInfoReturnable<Component> cir) {
		Player self = (Player) (Object) this;
		int seasons = self.level().isClientSide() ? Veterans.CLIENT.getOrDefault(self.getUUID(), 0) : Stats.seasonsPlayed(self.getUUID());
		if (seasons > 0) {
			cir.setReturnValue(Veterans.decorate(cir.getReturnValue(), seasons));
		}
	}
}
