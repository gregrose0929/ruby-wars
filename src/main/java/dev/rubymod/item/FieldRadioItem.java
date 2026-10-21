package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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

/**
 * Field Radio (needs Simple Voice Chat): hold it and talk, and everyone carrying
 * a radio on the same channel hears you anywhere on the map. Right-click to
 * change channel (1-8). Anyone who picks up a radio can listen in, so guard yours.
 */
public class FieldRadioItem extends Item {
	public static final int CHANNELS = 8;

	public FieldRadioItem(Properties properties) {
		super(properties);
	}

	public static int channel(ItemStack stack) {
		return stack.getOrDefault(ModComponents.RADIO_CHANNEL, 1);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		int next = channel(stack) % CHANNELS + 1;
		stack.set(ModComponents.RADIO_CHANNEL, next);
		if (player instanceof ServerPlayer sp) {
			sp.sendOverlayMessage(Component.literal("Radio: channel " + next).withStyle(ChatFormatting.GREEN));
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 1.6F);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Channel " + channel(stack)).withStyle(ChatFormatting.GREEN));
		builder.accept(Component.literal("Hold it and talk: radios on the same channel hear you anywhere").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Right-click: next channel. Needs Simple Voice Chat.").withStyle(ChatFormatting.DARK_GRAY));
	}
}
