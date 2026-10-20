package dev.rubymod.entity;

import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown flare. Ten seconds after it lands, a plane drops five bombs in a
 * line through the flare, along the direction it was thrown.
 */
public class AirStrikeFlareEntity extends ModProjectile {
	private static final int DELAY = 200;
	private int landedAt = -1;
	private float lineYaw;

	public AirStrikeFlareEntity(EntityType<? extends AirStrikeFlareEntity> type, Level level) {
		super(type, level);
	}

	public static AirStrikeFlareEntity throwFrom(Level level, LivingEntity thrower) {
		AirStrikeFlareEntity flare = new AirStrikeFlareEntity(ModEntities.AIR_STRIKE_FLARE, level);
		flare.setPos(thrower.getX(), thrower.getEyeY() - 0.1, thrower.getZ());
		flare.setOwner(thrower);
		flare.setItem(ModItems.AIR_STRIKE_FLARE.getDefaultInstance());
		flare.lineYaw = thrower.getYRot();
		return flare;
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.AIR_STRIKE_FLARE;
	}

	@Override
	public void tick() {
		if (landedAt >= 0) {
			baseTick();
			if (level() instanceof ServerLevel level) {
				level.sendParticles(new DustParticleOptions(0xFF3020, 2.0F), getX(), getY() + 0.3, getZ(), 3, 0.1, 0.4, 0.1, 0.0);
				level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, getX(), getY() + 0.3, getZ(), 1, 0.05, 0.1, 0.05, 0.01);
				if (tickCount - landedAt >= DELAY) {
					callStrike(level);
				}
			}
			return;
		}
		super.tick();
	}

	private void callStrike(ServerLevel level) {
		Vec3 along = Vec3.directionFromRotation(0, lineYaw);
		for (int i = -2; i <= 2; i++) {
			Vec3 drop = position().add(along.scale(i * 5)).add(0, 40, 0);
			level.addFreshEntity(BombEntity.drop(level, getOwner() != null ? getOwner() : this, drop));
		}
		level.playSound(null, getX(), getY() + 30, getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 8.0F, 0.5F);
		discard();
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		if (landedAt < 0) {
			landedAt = tickCount;
			setDeltaMovement(Vec3.ZERO);
			setNoGravity(true);
			setPos(hitResult.getLocation());
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0F, 0.6F);
			if (getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
				player.sendSystemMessage(Component.literal("Air strike inbound in 10 seconds!"), true);
			}
		}
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putFloat("line_yaw", lineYaw);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		lineYaw = input.getFloatOr("line_yaw", 0);
	}
}
