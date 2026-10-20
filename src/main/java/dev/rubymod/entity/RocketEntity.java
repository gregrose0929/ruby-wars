package dev.rubymod.entity;

import dev.rubymod.item.RocketLauncherItem;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A laser-guided rocket. While its shooter keeps holding the launcher, the
 * rocket steers toward wherever they're looking.
 */
public class RocketEntity extends ModProjectile {
	private static final double SPEED = 1.6;
	private static final int LIFETIME = 140;

	public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
		super(type, level);
	}

	public static RocketEntity fire(Level level, LivingEntity shooter) {
		RocketEntity rocket = new RocketEntity(ModEntities.ROCKET, level);
		Vec3 look = shooter.getLookAngle();
		rocket.setPos(shooter.getX() + look.x, shooter.getEyeY() - 0.1 + look.y, shooter.getZ() + look.z);
		rocket.setOwner(shooter);
		rocket.setItem(ModItems.ROCKET.getDefaultInstance());
		rocket.setDeltaMovement(look.scale(SPEED));
		return rocket;
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.ROCKET;
	}

	@Override
	protected double gravity() {
		return 0.0;
	}

	@Override
	protected float drag() {
		return 1.0F;
	}

	@Override
	public void tick() {
		if (level() instanceof ServerLevel level) {
			steer();
			if (tickCount > LIFETIME) {
				explode(level, 3.0F, true);
				return;
			}
			if (tickCount % 2 == 0) {
				level.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 1, 0.02, 0.02, 0.02, 0.0);
			}
			level.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 1, 0.03, 0.03, 0.03, 0.0);
		}
		super.tick();
	}

	private void steer() {
		Entity owner = getOwner();
		if (!(owner instanceof Player player) || !(player.getMainHandItem().getItem() instanceof RocketLauncherItem)) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 aimEnd = eye.add(player.getLookAngle().scale(160));
		HitResult hit = level().clip(new ClipContext(eye, aimEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 target = hit.getType() == HitResult.Type.MISS ? aimEnd : hit.getLocation();
		Vec3 desired = target.subtract(position()).normalize().scale(SPEED);
		// Turn at most ~15% toward the laser point per tick, so rockets curve rather than snap.
		setDeltaMovement(getDeltaMovement().lerp(desired, 0.15).normalize().scale(SPEED));
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		explode(level, 3.0F, true);
	}
}
