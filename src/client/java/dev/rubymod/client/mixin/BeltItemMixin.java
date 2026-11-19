package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;

/** Things riding a conveyor belt sit still on it (no bobbing and spinning), so a belt looks like a belt. */
@Mixin(ItemEntityRenderer.class)
public abstract class BeltItemMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"))
	private void rubymod$still(ItemEntity entity, ItemEntityRenderState state, float partialTicks, CallbackInfo ci) {
		var level = entity.level();
		var pos = entity.blockPosition();
		if (level.getBlockState(pos).getBlock() instanceof dev.rubymod.industry.ConveyorBlock
				|| level.getBlockState(pos.below()).getBlock() instanceof dev.rubymod.industry.ConveyorBlock && entity.getY() - pos.getY() < 0.3) {
			state.ageInTicks = 0;
			state.bobOffset = 0;
		}
	}
}
