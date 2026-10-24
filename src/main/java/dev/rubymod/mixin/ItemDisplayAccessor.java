package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;

/** Lets cosmetics set what a floating hat shows. */
@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayAccessor {
	@Invoker("setItemStack")
	void rubymod$setItemStack(ItemStack stack);
}
