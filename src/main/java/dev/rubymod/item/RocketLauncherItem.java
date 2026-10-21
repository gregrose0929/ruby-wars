package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.entity.RocketEntity;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/** Fires a laser-guided Rocket. Keep looking at the target and the rocket follows your aim. */
public class RocketLauncherItem extends Item {
	private static final int RELOAD = 50;

	public RocketLauncherItem(Properties properties) {
		super(properties);
	}

	/** Right-click (hold) aims; left-click fires (see the Fire packet). */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity user) {
		return 72000;
	}

	public static void fire(ServerPlayer player, ItemStack launcher) {
		if (player.getCooldowns().isOnCooldown(launcher)) {
			return;
		}
		if (!player.isCreative() && player.getInventory().countItem(ModItems.ROCKET) == 0) {
			player.sendSystemMessage(Component.literal("No rockets! Buy some at the Armory."), true);
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		level.addFreshEntity(RocketEntity.fire(level, player));
		level.playSound(null, player.getX(), player.getY(), player.getZ(), dev.rubymod.registry.ModSounds.ROCKET_LAUNCH, SoundSource.PLAYERS, 1.5F, 1.0F);
		if (!player.isCreative()) {
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				ItemStack s = player.getInventory().getItem(i);
				if (s.is(ModItems.ROCKET)) {
					s.shrink(1);
					break;
				}
			}
		}
		player.getCooldowns().addCooldown(launcher, RELOAD);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Left-click to fire a Rocket, hold right-click to aim").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Keep aiming: the rocket follows your crosshair").withStyle(ChatFormatting.DARK_GRAY));
	}
}
