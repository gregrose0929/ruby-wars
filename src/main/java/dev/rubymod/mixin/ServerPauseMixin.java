package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.MinecraftServer;

/**
 * The server naps when nobody's on (pause-when-empty), but not while it's still building an arena in the
 * background: otherwise a big one (the Coaster Workshop's ground) never gets finished between restarts.
 */
// The dedicated server has its own pauseWhenEmptySeconds (from server.properties), so both.
@Mixin({MinecraftServer.class, net.minecraft.server.dedicated.DedicatedServer.class})
public abstract class ServerPauseMixin {
	@Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
	private void rubymod$stayAwakeWhileBuilding(CallbackInfoReturnable<Integer> cir) {
		if (dev.rubymod.game.party.PartyGames.stillBuilding()) {
			cir.setReturnValue(0);
		}
	}
}
