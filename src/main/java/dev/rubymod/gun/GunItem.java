package dev.rubymod.gun;

import java.util.List;
import java.util.function.Consumer;

import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * A gun. Right-click fires (hold it for automatic guns), sneak to aim,
 * press R to reload. All the shooting happens on the server in {@link Gunfire}.
 */
public class GunItem extends Item {
	private final GunStats stats;

	public GunItem(GunStats stats, Properties properties) {
		super(properties);
		this.stats = stats;
	}

	/** Base stats, without attachments. */
	public GunStats stats() {
		return stats;
	}

	/** Stats for this particular gun, including its attachments. */
	public GunStats stats(ItemStack stack) {
		return Attachment.apply(stats, stack.getOrDefault(ModComponents.ATTACHMENTS, List.<String>of()));
	}

	public static int ammo(ItemStack stack) {
		return stack.getOrDefault(ModComponents.AMMO, 0);
	}

	/** Right-click (hold) aims down the sights. Firing is left-click, sent as a Fire packet. */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	/** Is this player aiming down the sights of a gun right now? */
	public static boolean isAiming(LivingEntity entity) {
		return entity.isUsingItem() && entity.getUseItem().getItem() instanceof GunItem;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.NONE;
	}

	/** Don't replay the "pull out item" animation every time the ammo count changes. */
	@Override
	public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
		return false;
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return true;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return Math.min(13, Math.round(13.0F * ammo(stack) / stats(stack).magazine()));
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return ammo(stack) == 0 ? 0xFF4040 : 0xFFD040;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		GunStats stats = stats(stack);
		builder.accept(Component.literal("Ammo: " + ammo(stack) + " / " + stats.magazine()).withStyle(ChatFormatting.GOLD));
		for (String name : stack.getOrDefault(ModComponents.ATTACHMENTS, List.<String>of())) {
			Attachment attachment = Attachment.valueOf(name);
			builder.accept(Component.literal("+ " + attachment.title).withStyle(ChatFormatting.AQUA));
		}
		String dmg = stats.pellets() > 1 ? stats.damage() + " x" + stats.pellets() : String.valueOf(stats.damage());
		builder.accept(Component.literal("Damage " + dmg + "  |  Range " + (int) stats.range()).withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Uses " + Component.translatable(stats.ammo().item().getDescriptionId()).getString())
				.withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal(stats.automatic() ? "Hold left-click to fire" : "Left-click to fire").withStyle(ChatFormatting.DARK_GRAY));
		builder.accept(Component.literal("Hold right-click to aim, R to reload").withStyle(ChatFormatting.DARK_GRAY));
	}
}
