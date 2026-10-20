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

/** Frag grenades, smoke grenades, and air strike flares: right-click to throw. */
public class ThrowableWeaponItem extends Item {
	public enum Kind {
		FRAG,
		SMOKE,
		AIR_STRIKE
	}

	private final Kind kind;

	public ThrowableWeaponItem(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level instanceof ServerLevel serverLevel) {
			ModProjectile thrown = switch (kind) {
				case FRAG -> GrenadeEntity.throwFrom(level, player, false);
				case SMOKE -> GrenadeEntity.throwFrom(level, player, true);
				case AIR_STRIKE -> AirStrikeFlareEntity.throwFrom(level, player);
			};
			thrown.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.1F, 1.0F);
			serverLevel.addFreshEntity(thrown);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6F, 0.6F);
		}
		player.getCooldowns().addCooldown(stack, 20);
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
