package dev.rubymod.entity;

import dev.rubymod.game.Explosions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel serverLevel && isAlive()) {
			dev.rubymod.game.Range.keepLoaded(serverLevel, this);
		}
		Vec3 v = getDeltaMovement();
		if (v.lengthSqr() > 1.0E-4) {
			setYRot((float) Math.toDegrees(Math.atan2(-v.x, v.z)));
			setXRot((float) -Math.toDegrees(Math.atan2(v.y, v.horizontalDistance())));
		}
	}

	/** Shells and bombs in flight aren't knocked off course by other blasts. */
	@Override
	public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
		return true;
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
		dev.rubymod.game.Armor.blast(level, this, getOwner(), position(), antiArmor(power), getDeltaMovement());
		Explosions.explode(level, this, getX(), getY(), getZ(), power, breakBlocks);
		discard();
	}

	/** Damage dealt to vehicles and aircraft this blast reaches (see {@link dev.rubymod.game.Armor}). */
	protected float antiArmor(float power) {
		return power * 4.0F;
	}
}
