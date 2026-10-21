package dev.rubymod.entity;

import dev.rubymod.registry.ModDamage;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A shooting-range target. It can't be destroyed; it shows the last hit and
 * the running total over its head (reset after 3 seconds without hits).
 * Sneak-punch it in creative to remove it.
 */
public class TargetDummyEntity extends ArmorStand {
	private float total;
	private int hits;
	private int idleTicks;

	public TargetDummyEntity(EntityType<? extends TargetDummyEntity> type, Level level) {
		super(type, level);
		setShowArms(true);
		setCustomNameVisible(true);
		setCustomName(Component.literal("Target").withStyle(ChatFormatting.GRAY));
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (isRemoved()) {
			return false;
		}
		if (source.getEntity() instanceof Player player && player.isCreative() && player.isShiftKeyDown() && source.getDirectEntity() == player) {
			discard();
			return true;
		}
		boolean headshot = source.is(ModDamage.HEADSHOT);
		total += damage;
		hits++;
		idleTicks = 0;
		Component name = Component.literal(String.format("%.1f", damage)).withStyle(headshot ? ChatFormatting.GOLD : ChatFormatting.WHITE)
				.append(Component.literal(headshot ? " HEADSHOT" : "").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
				.append(Component.literal(String.format("   total %.1f (%d hits)", total, hits)).withStyle(ChatFormatting.GRAY));
		setCustomName(name);
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, getX(), getY(1.0), getZ(), 2, 0.2, 0.1, 0.2, 0.0);
		level.playSound(null, getX(), getY(), getZ(), headshot ? SoundEvents.ARROW_HIT_PLAYER : SoundEvents.ARMOR_STAND_HIT, SoundSource.NEUTRAL, 0.6F, 1.0F);
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide() && hits > 0 && ++idleTicks > 60) {
			total = 0;
			hits = 0;
			setCustomName(Component.literal("Target").withStyle(ChatFormatting.GRAY));
		}
	}

	@Override
	public void kill(ServerLevel level, net.minecraft.world.entity.Entity attributedTo) {
		// Only /kill-style removal (void, commands) gets rid of it.
		discard();
	}
}
