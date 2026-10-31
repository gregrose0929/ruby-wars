package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.math.Transformation;

import net.minecraft.world.entity.Display;

/** Lets the hub set up its floating signs (text displays) without commands. */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setBillboardConstraints")
	void rubymod$setBillboard(Display.BillboardConstraints constraints);

	@Invoker("setTransformation")
	void rubymod$setTransformation(Transformation transformation);

	@Invoker("setViewRange")
	void rubymod$setViewRange(float range);

	@Invoker("setTransformationInterpolationDuration")
	void rubymod$setTransformationInterpolationDuration(int ticks);

	@Invoker("setTransformationInterpolationDelay")
	void rubymod$setTransformationInterpolationDelay(int ticks);

	@Invoker("setPosRotInterpolationDuration")
	void rubymod$setPosRotInterpolationDuration(int ticks);

	@Invoker("setGlowColorOverride")
	void rubymod$setGlowColorOverride(int rgb);
}
