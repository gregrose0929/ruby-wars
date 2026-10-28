package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.client.Trigger;

import net.minecraft.client.Minecraft;

/** While you hold a gun (or drive a vehicle), left-click pulls the trigger instead of punching or mining. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void rubymod$noAttackWithGun(CallbackInfoReturnable<Boolean> cir) {
		if (Trigger.capturesAttack((Minecraft) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	/** In the cockpit the designate key wins over chat when they share a key (both default to T). */
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void rubymod$designateNotChat(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		var designate = dev.rubymod.client.RubyModClient.designateKey;
		if (designate != null && dev.rubymod.client.Targeting.piloting(mc) != null && mc.options.keyChat.same(designate)) {
			while (mc.options.keyChat.consumeClick()) {
				dev.rubymod.client.Designation.designate(mc);
			}
		}
	}

	/** In a vehicle right-click is the vehicle's (tank gunner sight), not the held item's. */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void rubymod$noItemsInVehicles(CallbackInfo ci) {
		if (dev.rubymod.client.Vehicles.inVehicle((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}

	/** In the Ruby Rift: the Rift's music (each theme and boss has its own). */
	@Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true)
	private void rubymod$riftMusic(CallbackInfoReturnable<net.minecraft.sounds.Music> cir) {
		net.minecraft.sounds.Music music = dev.rubymod.client.rift.RiftMusic.current();
		if (music != null) {
			cir.setReturnValue(music);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void rubymod$noMiningWithGun(boolean down, CallbackInfo ci) {
		if (Trigger.capturesAttack((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
