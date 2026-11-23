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

/**
 * The C4 Detonator: what's left in your hand when you throw your last Sticky C4. Right-click it and every charge you've
 * put out goes off; then it's used up. (With nothing out to set off, it just goes.)
 */
public class DetonatorItem extends Item {
	public DetonatorItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel server) {
			boolean any = StickyChargeEntity.hasCharges(player);
			if (any) {
				StickyChargeEntity.detonateAll(server, player);
			}
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 1.0F, 1.5F);
			player.sendOverlayMessage(Component.literal(any ? "Boom!" : "No charges out to set off.").withStyle(any ? ChatFormatting.GOLD : ChatFormatting.GRAY));
			player.getItemInHand(hand).shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	/** Swap a hand's (now empty) slot for a detonator, or put one in the inventory. */
	public static void give(Player player, InteractionHand hand) {
		if (hasOne(player)) {
			return;
		}
		ItemStack detonator = new ItemStack(dev.rubymod.registry.ModItems.C4_DETONATOR);
		if (player.getItemInHand(hand).isEmpty()) {
			player.setItemInHand(hand, detonator);
		} else if (!player.getInventory().add(detonator)) {
			player.drop(detonator, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
	}

	public static boolean hasOne(Player player) {
		return player.getInventory().contains(s -> s.is(dev.rubymod.registry.ModItems.C4_DETONATOR));
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click: set off every Sticky C4 you've thrown").withStyle(ChatFormatting.GRAY));
	}
}
