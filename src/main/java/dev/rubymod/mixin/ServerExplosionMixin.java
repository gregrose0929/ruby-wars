package dev.rubymod.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.rubymod.game.Explosions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;

/** Runs every explosion's block list through the claim and armor rules. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Shadow
	public abstract float radius();

	/** Every blast near a Ruby Heart wears it down a little (Open War, enemies only: see HeartDamage). */
	@Inject(method = "explode", at = @At("HEAD"))
	private void rubymod$hurtHearts(CallbackInfoReturnable<Integer> cir) {
		net.minecraft.world.level.Explosion self = (net.minecraft.world.level.Explosion) (Object) this;
		dev.rubymod.game.HeartDamage.blast(level, self.center(), self.radius(), self.getDirectSourceEntity(), self.getIndirectSourceEntity());
	}

	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
	private void rubymod$filterBlocks(CallbackInfoReturnable<List<BlockPos>> cir) {
		cir.setReturnValue(Explosions.filter(level, cir.getReturnValue(), radius()));
	}
}
