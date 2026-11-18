package dev.rubymod.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Light that moves (LambDynamicLights lights these up for whoever has it): a muzzle flash, gone in a blink, or an
 * illumination flare that hangs in the air, drifting down slowly and fizzing, lighting the field below.
 */
public class LightEntity extends Entity {
	public LightEntity(EntityType<? extends LightEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
	}

	public boolean flare() {
		return getType() == dev.rubymod.registry.ModEntities.ILLUMINATION_FLARE;
	}

	/** A shot's flash at this spot (three ticks of light). */
	public static void flash(ServerLevel level, Vec3 at) {
		LightEntity e = dev.rubymod.registry.ModEntities.MUZZLE_FLASH.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
		if (e != null) {
			e.setPos(at);
			level.addFreshEntity(e);
		}
	}

	/** An illumination flare, high over this spot. */
	public static void flare(ServerLevel level, Vec3 at) {
		LightEntity e = dev.rubymod.registry.ModEntities.ILLUMINATION_FLARE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
		if (e != null) {
			e.setPos(at);
			level.addFreshEntity(e);
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public void tick() {
		super.tick();
		if (!flare()) {
			if (!level().isClientSide() && tickCount > 2) {
				discard();
			}
			return;
		}
		// A flare under its little parachute: down slowly, swaying, fizzing.
		double sway = Math.sin(tickCount * 0.05) * 0.03;
		setPos(getX() + sway, getY() - 0.015, getZ() + Math.cos(tickCount * 0.04) * 0.03);
		if (level() instanceof ServerLevel level) {
			if (tickCount % 2 == 0) {
				level.sendParticles(ParticleTypes.FIREWORK, getX(), getY(), getZ(), 1, 0.05, 0.05, 0.05, 0.01);
			}
			if (tickCount % 5 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 0.2, getZ(), 1, 0.05, 0.05, 0.05, 0.01);
			}
			boolean landed = !level.getBlockState(blockPosition()).getCollisionShape(level, blockPosition()).isEmpty();
			if (tickCount > 1200 || landed) {
				discard();
			}
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		// Lights don't outlast a restart.
		discard();
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
