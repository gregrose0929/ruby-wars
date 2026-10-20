package dev.rubymod.entity;

import dev.rubymod.game.Claims;
import dev.rubymod.game.GameConfig;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * A charge stuck to a block. After a 5 second fuse it blows. On a Ruby Heart it
 * deals heavy damage to the Heart; anywhere else it's a block-breaking blast.
 */
public class BreachingChargeEntity extends ModProjectile {
	private static final int FUSE = 100;
	private BlockPos attachedTo = BlockPos.ZERO;

	public BreachingChargeEntity(EntityType<? extends BreachingChargeEntity> type, Level level) {
		super(type, level);
	}

	public static BreachingChargeEntity plant(Level level, LivingEntity owner, BlockPos block, Vec3 at) {
		BreachingChargeEntity charge = new BreachingChargeEntity(ModEntities.BREACHING_CHARGE, level);
		charge.setPos(at);
		charge.setOwner(owner);
		charge.setItem(ModItems.BREACHING_CHARGE.getDefaultInstance());
		charge.setNoGravity(true);
		charge.attachedTo = block.immutable();
		return charge;
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.BREACHING_CHARGE;
	}

	@Override
	public void tick() {
		baseTick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		int left = FUSE - tickCount;
		int beepEvery = left > 40 ? 20 : left > 15 ? 8 : 3;
		if (left % beepEvery == 0) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.HOSTILE, 1.5F, 2.0F);
			level.sendParticles(ParticleTypes.SMALL_FLAME, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0.0);
		}
		if (left <= 0) {
			detonate(level);
		}
	}

	private void detonate(ServerLevel level) {
		SeasonData.Claim heart = SeasonData.get().heartAt(Rules.dim(level), attachedTo);
		if (heart != null) {
			dev.rubymod.game.Explosions.explode(level, null, getX(), getY(), getZ(), 3.0F, false);
			Claims.damageHeart(level, heart, GameConfig.get().breachingChargeDamage, getOwner() instanceof ServerPlayer p ? p : null);
			discard();
		} else {
			explode(level, 3.5F, true);
		}
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putLong("attached_to", attachedTo.asLong());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		attachedTo = BlockPos.of(input.getLongOr("attached_to", 0L));
	}
}
