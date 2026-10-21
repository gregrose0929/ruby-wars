package dev.rubymod.entity;

import dev.rubymod.registry.ModEntities;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The burning remains of a destroyed vehicle: fire and black smoke for 15 seconds. */
public class WreckEntity extends Entity {
	private static final int BURN_TICKS = 300;
	private float size = 1.0F;

	public WreckEntity(EntityType<? extends WreckEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
	}

	public static WreckEntity of(ServerLevel level, Entity destroyed) {
		WreckEntity wreck = new WreckEntity(ModEntities.WRECK, level);
		wreck.setPos(destroyed.position());
		wreck.size = destroyed.getBbWidth() / 2.0F;
		return wreck;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		float fade = 1.0F - tickCount / (float) BURN_TICKS;
		if (tickCount % 2 == 0) {
			level.sendParticles(ParticleTypes.FLAME, getX(), getY() + 0.5, getZ(), Math.max(1, (int) (4 * fade)), size * 0.6, 0.3, size * 0.6, 0.02);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.0, getZ(), 2, size * 0.5, 0.4, size * 0.5, 0.03);
		}
		if (tickCount % 6 == 0) {
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 1.5, getZ(), 1, size * 0.4, 0.2, size * 0.4, 0.02);
		}
		if (tickCount > BURN_TICKS) {
			discard();
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		discard();
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
