package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.decoration.ItemFrame;

/** The photo wall's frames are fixed: nobody can knock the photos out. */
@Mixin(ItemFrame.class)
public interface ItemFrameAccessor {
	@Accessor("fixed")
	void rubymod$setFixed(boolean fixed);
}
