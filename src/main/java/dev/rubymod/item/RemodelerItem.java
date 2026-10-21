package dev.rubymod.item;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import dev.rubymod.game.Rules;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Remodeler: right-click a block to swap it, and the connected blocks of the
 * same kind around it (a 5x5x5 patch, sneak for just the one), for the block in
 * your off hand. The old blocks go into your inventory. Turns a cobblestone wall
 * into reinforced concrete in a few clicks.
 */
public class RemodelerItem extends Item {
	private static final int RADIUS = 2;

	public RemodelerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		ItemStack material = player.getOffhandItem();
		if (!(material.getItem() instanceof BlockItem newItem) || newItem.getBlock().defaultBlockState().hasBlockEntity()) {
			player.sendOverlayMessage(Component.literal("Hold the new block in your off hand.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		BlockPos origin = context.getClickedPos();
		BlockState old = level.getBlockState(origin);
		BlockState replacement = newItem.getBlock().defaultBlockState();
		if (old.hasBlockEntity() || old.is(replacement.getBlock()) || old.getDestroySpeed(level, origin) < 0) {
			return InteractionResult.FAIL;
		}
		Set<BlockPos> patch = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(origin);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			if (!patch.add(p) || player.isShiftKeyDown()) {
				continue;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (!patch.contains(n) && level.getBlockState(n).is(old.getBlock()) && Math.abs(n.getX() - origin.getX()) <= RADIUS
						&& Math.abs(n.getY() - origin.getY()) <= RADIUS && Math.abs(n.getZ() - origin.getZ()) <= RADIUS) {
					queue.add(n);
				}
			}
		}
		int swapped = 0;
		Item oldItem = old.getBlock().asItem();
		for (BlockPos p : patch) {
			if (!Rules.canBuild(player, level, p)) {
				continue;
			}
			if (!player.isCreative()) {
				if (!take(player, newItem)) {
					break;
				}
				ArchitectWandItem.giveOrDrop(player, new ItemStack(oldItem));
			}
			level.setBlock(p, replacement, 3);
			swapped++;
		}
		if (swapped > 0) {
			level.playSound(null, origin, replacement.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
			level.playSound(null, origin, SoundEvents.GRINDSTONE_USE, SoundSource.PLAYERS, 0.4F, 1.6F);
			player.sendOverlayMessage(Component.literal("Remodeled " + swapped + " blocks.").withStyle(ChatFormatting.AQUA));
		}
		return InteractionResult.SUCCESS;
	}

	private static boolean take(Player player, Item item) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				s.shrink(1);
				return true;
			}
		}
		return false;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click: swap a 5x5x5 patch of one block for your off-hand block").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Sneak + right-click: swap just one block").withStyle(ChatFormatting.GRAY));
	}
}
