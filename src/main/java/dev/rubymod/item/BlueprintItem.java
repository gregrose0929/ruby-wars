package dev.rubymod.item;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Consumer;

import dev.rubymod.industry.Blueprints;
import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Right-click two opposite corners to copy a structure (up to 24x24x24). Put the
 * Blueprint in a Constructor to build copies. Sneak + right-click in the air to
 * wipe it.
 */
public class BlueprintItem extends Item {
	public BlueprintItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isShiftKeyDown() && (stack.has(ModComponents.BLUEPRINT) || stack.has(ModComponents.CORNER))) {
			stack.remove(ModComponents.BLUEPRINT);
			stack.remove(ModComponents.CORNER);
			if (player instanceof ServerPlayer sp) {
				sp.sendOverlayMessage(Component.literal("Blueprint wiped."));
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		ItemStack stack = context.getItemInHand();
		if (player == null || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		if (stack.has(ModComponents.BLUEPRINT)) {
			sp.sendOverlayMessage(Component.literal("This Blueprint is full. Put it in a Constructor, or sneak + right-click the air to wipe it."));
			return InteractionResult.FAIL;
		}
		BlockPos pos = context.getClickedPos();
		BlockPos corner = stack.get(ModComponents.CORNER);
		if (corner == null) {
			stack.set(ModComponents.CORNER, pos);
			sp.sendOverlayMessage(Component.literal("First corner set. Right-click the opposite corner.").withStyle(ChatFormatting.AQUA));
			return InteractionResult.SUCCESS;
		}
		Blueprints.Data data = Blueprints.capture(context.getLevel(), corner, pos);
		if (data == null) {
			sp.sendOverlayMessage(Component.literal("Too big: a Blueprint holds up to " + Blueprints.MAX_SIZE + " blocks each way.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		stack.remove(ModComponents.CORNER);
		stack.set(ModComponents.BLUEPRINT, data);
		context.getLevel().playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.0F);
		sp.sendOverlayMessage(Component.literal("Copied " + data.sx() + "x" + data.sy() + "x" + data.sz() + " (" + data.blocks() + " blocks).")
				.withStyle(ChatFormatting.AQUA));
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		Blueprints.Data data = stack.get(ModComponents.BLUEPRINT);
		if (data == null) {
			builder.accept(Component.literal(stack.has(ModComponents.CORNER) ? "Right-click the opposite corner" : "Right-click two opposite corners to copy")
					.withStyle(ChatFormatting.GRAY));
			builder.accept(Component.literal("Up to " + Blueprints.MAX_SIZE + "x" + Blueprints.MAX_SIZE + "x" + Blueprints.MAX_SIZE)
					.withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		builder.accept(Component.literal(data.sx() + " x " + data.sy() + " x " + data.sz() + ", " + data.blocks() + " blocks").withStyle(ChatFormatting.AQUA));
		data.bill().entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder())).limit(6)
				.forEach(e -> builder.accept(Component.literal("  " + e.getValue() + " x ").append(e.getKey().getName(e.getKey().getDefaultInstance()))
						.withStyle(ChatFormatting.GRAY)));
		builder.accept(Component.literal("Put it in a Constructor to build copies").withStyle(ChatFormatting.DARK_GRAY));
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return stack.has(ModComponents.BLUEPRINT);
	}
}
