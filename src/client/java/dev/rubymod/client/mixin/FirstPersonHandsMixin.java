package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.rubymod.client.gun.GunView;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Guns in first person are drawn by GunView (both hands on the gun); the off hand stays out of the way. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void rubymod$guns(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState hands, float partialTick, float pitch,
			InteractionHand hand, float swing, ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (hand == InteractionHand.MAIN_HAND && GunView.handles(stack)) {
			FirstPersonHandsInvoker invoker = (FirstPersonHandsInvoker) (Object) this;
			if (GunView.render(playerState, stack, pose, collector, light, invoker::rubymod$renderPlayerHand)) {
				ci.cancel();
			}
		} else if (hand == InteractionHand.OFF_HAND && GunView.handles(hands.mainHandItem)) {
			ci.cancel();
		}
	}
}
