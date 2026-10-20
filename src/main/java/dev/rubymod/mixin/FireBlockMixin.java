package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.game.Rules;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;

/** Outside Battle Day, fire can't spread into claims or burn blocks inside them. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
	@Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
	private void rubymod$noSpreadIntoClaims(LevelReader level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		if (level instanceof Level l && Rules.isProtectedFromGriefing(l, pos)) {
			cir.setReturnValue(0);
		}
	}

	@Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
	private void rubymod$noBurningInClaims(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
		if (Rules.isProtectedFromGriefing(level, pos)) {
			ci.cancel();
		}
	}
}
