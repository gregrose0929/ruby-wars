package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.game.hub.ParkBoats;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * The park's ice rides (the Speedway's karts, the bumper cars) are boats on ice: a top speed and a steadier turn there, so
 * they're fast but drivable. Runs where the boat's driven (your own game).
 */
@Mixin(AbstractBoat.class)
public abstract class BoatLimitMixin {
	@Shadow
	private float deltaRotation;

	@Inject(method = "controlBoat", at = @At("TAIL"))
	private void rubymod$parkLimit(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		double max = ParkBoats.maxSpeed(self.level(), self.getX(), self.getZ());
		if (max <= 0) {
			return;
		}
		Vec3 v = self.getDeltaMovement();
		double h = v.horizontalDistance();
		if (h > max) {
			self.setDeltaMovement(v.x * max / h, v.y, v.z * max / h);
		}
		float turn = ParkBoats.maxTurn(self.level(), self.getX(), self.getZ());
		deltaRotation = Math.max(-turn, Math.min(turn, deltaRotation));
	}
}
