package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;

/** Lets Hide & Seek set the block a disguised hider looks like. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {
	@Invoker("setBlockState")
	void rubymod$setBlockState(BlockState state);
}
