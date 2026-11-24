package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.game.Rules;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Right-click a block to extend it up to 16 blocks: a straight line in the
 * direction you're facing, or straight up while sneaking. Uses matching blocks
 * from your inventory. R undoes the last stretch (the blocks come back).
 */
public class BuildersWandItem extends Item {
	private static final int MAX = 16;

	public BuildersWandItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		if (context.getItemInHand().getDamageValue() >= context.getItemInHand().getMaxDamage() - 1) {
			player.sendSystemMessage(Component.literal("The wand's worn out: it mends itself in a little while.").withStyle(ChatFormatting.GRAY), true);
			return InteractionResult.FAIL;
		}
		BlockPos origin = context.getClickedPos();
		BlockState state = level.getBlockState(origin);
		Item blockItem = state.getBlock().asItem();
		if (blockItem == net.minecraft.world.item.Items.AIR || level.getBlockEntity(origin) != null) {
			player.sendSystemMessage(Component.literal("The wand can't copy that block."), true);
			return InteractionResult.FAIL;
		}
		Direction direction = player.isShiftKeyDown() ? Direction.UP : player.getDirection();
		int placed = 0;
		java.util.List<ArchitectWandItem.Placed> done = new java.util.ArrayList<>();
		BlockPos pos = origin;
		for (int i = 0; i < MAX; i++) {
			pos = pos.relative(direction);
			if (!level.getBlockState(pos).canBeReplaced() || !Rules.canPlace(player, level, pos, state.getBlock()) || !level.getWorldBorder().isWithinBounds(pos)) {
				break;
			}
			if (!player.isCreative() && !take(player.getInventory(), blockItem)) {
				break;
			}
			level.setBlockAndUpdate(pos, state);
			done.add(new ArchitectWandItem.Placed(pos.immutable(), blockItem));
			placed++;
		}
		if (placed > 0) {
			ArchitectWandItem.remember(player, done);
			level.playSound(null, origin, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
			// It wears with use, but never breaks: it mends itself (see inventoryTick).
			ItemStack wand = context.getItemInHand();
			wand.setDamageValue(Math.min(wand.getMaxDamage() - 1, wand.getDamageValue() + 1));
		} else {
			player.sendSystemMessage(Component.literal("Nothing placed. You need more " + blockItem.getName(blockItem.getDefaultInstance()).getString()
					+ " or there's no room."), true);
		}
		return InteractionResult.SUCCESS;
	}

	private static boolean take(Inventory inventory, Item item) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (s.is(item)) {
				s.shrink(1);
				return true;
			}
		}
		return false;
	}

	/** It mends itself: a use back every half second, whenever it's not being used. */
	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, net.minecraft.world.entity.Entity owner, net.minecraft.world.entity.EquipmentSlot slot) {
		if (level.getGameTime() % 10 == 0) {
			mend(stack);
		}
	}

	public static boolean mend(ItemStack stack) {
		if (stack.getDamageValue() <= 0) {
			return false;
		}
		stack.setDamageValue(stack.getDamageValue() - 1);
		return true;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click a block: extend it 16 blocks forward").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Sneak + right-click: build straight up").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("R: undo the last stretch (the blocks come back)").withStyle(ChatFormatting.GRAY));
	}
}
