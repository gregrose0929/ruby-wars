package dev.rubymod.item;

import dev.rubymod.entity.AirStrikeFlareEntity;
import dev.rubymod.entity.GrenadeEntity;
import dev.rubymod.entity.ModProjectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Frag grenades, smoke grenades, fire bombs and air strike flares: hold right-click to wind up, let go to throw. */
public class ThrowableWeaponItem extends Item {
	public enum Kind {
		FRAG,
		SMOKE,
		FIRE,
		AIR_STRIKE
	}

	private final Kind kind;

	public ThrowableWeaponItem(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
	}

	/** Ticks of winding up for the longest throw. */
	public static final int WIND_UP = 20;

	/** Hold right-click to wind up; let go to throw (a quick tap lobs it, a full wind-up throws it well over twice as far). */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity user) {
		return 72000;
	}

	@Override
	public net.minecraft.world.item.ItemUseAnimation getUseAnimation(ItemStack stack) {
		return net.minecraft.world.item.ItemUseAnimation.SPEAR;
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, net.minecraft.world.entity.LivingEntity entity, int remaining) {
		if (entity instanceof Player player) {
			throwIt(level, player, stack, power(getUseDuration(stack, entity) - remaining));
		}
		return true;
	}

	/** How hard, 0 to 1, after winding up this many ticks. */
	public static float power(int ticks) {
		return Math.min(1.0F, Math.max(0, ticks) / (float) WIND_UP);
	}

	/** Throwing speed for that much wind-up (the old fixed throw was 1.1). */
	public static float speed(float power) {
		return 0.6F + 0.95F * power;
	}

	/** Throw one, this hard. */
	public void throwIt(Level level, Player player, ItemStack stack, float power) {
		if (level instanceof ServerLevel serverLevel) {
			ModProjectile thrown = switch (kind) {
				case FRAG -> GrenadeEntity.throwFrom(level, player, false);
				case SMOKE -> GrenadeEntity.throwFrom(level, player, true);
				case FIRE -> GrenadeEntity.fireBomb(level, player);
				case AIR_STRIKE -> AirStrikeFlareEntity.throwFrom(level, player);
			};
			thrown.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, speed(power), 1.0F);
			serverLevel.addFreshEntity(thrown);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6F, 0.8F - power * 0.3F);
		}
		player.getCooldowns().addCooldown(stack, 20);
		stack.consume(1, player);
	}
}
