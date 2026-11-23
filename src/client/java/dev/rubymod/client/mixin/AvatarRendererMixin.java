package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.gun.GunItem;
import dev.rubymod.item.RocketLauncherItem;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;

/** Players hold guns up in an aiming pose (two hands, like a loaded crossbow) instead of letting them dangle. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	/** Remember who wears a jetpack (the chest item only reaches the render state if it has an armor texture). */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void rubymod$jetpack(net.minecraft.world.entity.Avatar entity, net.minecraft.client.renderer.entity.state.AvatarRenderState state,
			float partialTicks, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		state.setData(dev.rubymod.client.Jetpack.WEARING,
				entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(dev.rubymod.registry.ModItems.JETPACK));
		// On a leaning Dirt Bike, the rider leans with it.
		state.setData(BIKE_LEAN, entity.getVehicle() instanceof dev.rubymod.entity.LightVehicleEntity bike && bike.isBike()
				? new float[] {bike.lean(partialTicks), bike.getYRot(partialTicks)} : null);
	}

	private static final net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey<float[]> BIKE_LEAN =
			net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey.create(() -> "rubymod:bike_lean");

	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("HEAD"))
	private void rubymod$bikeLean(net.minecraft.client.renderer.entity.state.AvatarRenderState state, com.mojang.blaze3d.vertex.PoseStack pose, float bodyRot,
			float scale, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		float[] lean = state.getData(BIKE_LEAN);
		if (lean != null && lean[0] != 0) {
			pose.rotateDegrees(com.mojang.math.Axis.YP, -lean[1]);
			pose.rotateDegrees(com.mojang.math.Axis.ZP, lean[0]);
			pose.rotateDegrees(com.mojang.math.Axis.YP, lean[1]);
		}
	}

	@Inject(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
			at = @At("HEAD"), cancellable = true)
	private static void rubymod$aimGuns(Avatar avatar, ItemStack item, InteractionHand hand, CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
		if (!avatar.isSwinging() && (item.getItem() instanceof GunItem || item.getItem() instanceof RocketLauncherItem
				|| item.getItem() instanceof dev.rubymod.item.LockOnLauncherItem)) {
			cir.setReturnValue(HumanoidModel.ArmPose.CROSSBOW_HOLD);
		}
	}
}
