package dev.rubymod.entity;

import dev.rubymod.game.Explosions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/** Shared base for the mod's projectiles. They render as their item, like a snowball. */
public abstract class ModProjectile extends ThrowableItemProjectile {
	protected ModProjectile(EntityType<? extends ModProjectile> type, Level level) {
		super(type, level);
	}

	/** Downward acceleration per tick. */
	protected double gravity() {
		return 0.03;
	}

	/** Velocity kept each tick (1.0 = no air drag). */
	protected float drag() {
		return 0.99F;
	}

	@Override
	protected double getDefaultGravity() {
		return gravity();
	}

	@Override
	protected float getAirDrag() {
		return drag();
	}

	@Override
	protected void onHit(HitResult hitResult) {
		super.onHit(hitResult);
		if (level() instanceof ServerLevel serverLevel && isAlive()) {
			onImpact(serverLevel, hitResult);
		}
	}

	protected abstract void onImpact(ServerLevel level, HitResult hitResult);

	protected void explode(ServerLevel level, float power, boolean breakBlocks) {
		Explosions.explode(level, this, getX(), getY(), getZ(), power, breakBlocks);
		discard();
	}
}
