package dev.rubymod.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

/** Puts up a Practice Dummy in your base for your defenses to fire on (see {@link dev.rubymod.game.Practice}). Kept: use it again any time. */
public class PracticeDummyItem extends Item {
	public PracticeDummyItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		String error = dev.rubymod.game.Practice.place(player, level, context.getClickedPos().relative(context.getClickedFace()));
		if (error != null) {
			player.sendOverlayMessage(Component.literal(error).withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click the ground in your base: a dummy stands there").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("for a minute, and your turrets and Tesla Coils fire on it").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Nothing is used up or lost; keep it and use it again").withStyle(ChatFormatting.DARK_GRAY));
	}
}
