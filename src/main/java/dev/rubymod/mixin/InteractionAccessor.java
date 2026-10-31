package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Interaction;

/** Lets the Ruby Convoy size the seats on its truck. */
@Mixin(Interaction.class)
public interface InteractionAccessor {
	@Invoker("setWidth")
	void rubymod$setWidth(float width);

	@Invoker("setHeight")
	void rubymod$setHeight(float height);
}
