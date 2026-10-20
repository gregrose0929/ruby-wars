package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.game.Rules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/** Outside Battle Day, lava and water can't flow from outside a claim into it. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void rubymod$noFlowIntoClaims(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
		if (level instanceof Level l && Rules.blocksFlow(l, pos.relative(direction.getOpposite()), pos)) {
			ci.cancel();
		}
	}
}
