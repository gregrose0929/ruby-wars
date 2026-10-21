package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public interface FirstPersonHandsInvoker {
	@Invoker("renderPlayerHand")
	void rubymod$renderPlayerHand(PoseStack pose, SubmitNodeCollector collector, int light, HumanoidArm arm, PlayerRenderState state);
}
