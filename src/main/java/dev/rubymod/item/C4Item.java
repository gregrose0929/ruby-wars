package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.entity.StickyChargeEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

/** Right-click throws a sticky charge; with charges out, right-click (sneaking) sets them all off. */
public class C4Item extends Item {
	public C4Item(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level instanceof ServerLevel serverLevel) {
			if (StickyChargeEntity.hasCharges(player) && (player.isShiftKeyDown() || stack.getCount() <= 1 && !player.isCreative())) {
				StickyChargeEntity.detonateAll(serverLevel, player);
				level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 1.0F, 1.5F);
				return InteractionResult.SUCCESS;
			}
			level.addFreshEntity(StickyChargeEntity.throwFrom(level, player));
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, 0.5F);
			if (!player.isCreative()) {
				stack.shrink(1);
			}
			player.sendOverlayMessage(Component.literal("C4 placed. Sneak + right-click with C4 to detonate."));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click: throw a sticky charge (sticks to vehicles)").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Sneak + right-click: detonate all your charges").withStyle(ChatFormatting.GRAY));
	}
}
