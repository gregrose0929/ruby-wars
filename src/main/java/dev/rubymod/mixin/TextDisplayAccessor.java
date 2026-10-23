package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

/** Lets the hub write and restyle its floating signs. */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
	@Invoker("setText")
	void rubymod$setText(Component text);

	@Invoker("getText")
	Component rubymod$getText();

	@Invoker("setBackgroundColor")
	void rubymod$setBackground(int argb);
}
