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

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack launcher = player.getItemInHand(hand);
		if (!player.isCreative() && player.getInventory().countItem(ModItems.ROCKET) == 0) {
			if (player instanceof ServerPlayer serverPlayer) {
				serverPlayer.sendSystemMessage(Component.literal("No rockets! Buy some at the Armory."), true);
			}
			return InteractionResult.FAIL;
		}
		if (level instanceof ServerLevel serverLevel) {
			serverLevel.addFreshEntity(RocketEntity.fire(level, player));
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.0F, 0.6F);
			if (!player.isCreative()) {
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					ItemStack s = player.getInventory().getItem(i);
					if (s.is(ModItems.ROCKET)) {
						s.shrink(1);
						break;
					}
				}
			}
		}
		player.getCooldowns().addCooldown(launcher, RELOAD);
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click to fire a Rocket").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Keep aiming: the rocket follows your crosshair").withStyle(ChatFormatting.DARK_GRAY));
	}
}
