package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.game.Rules;
import dev.rubymod.industry.MachineBlock;
import dev.rubymod.industry.MachineBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ruby Wrench: right-click a machine, belt, Belt Loader or Sorter to turn it; crouch and right-click to pick it up
 * (a machine keeps its tier, and what's in it goes into your inventory). Only in your own base.
 */
public class WrenchItem extends Item {
	public WrenchItem(Properties properties) {
		super(properties);
	}

	private static boolean ours(Block block) {
		return block instanceof MachineBlock || block instanceof dev.rubymod.industry.ConveyorBlock || block instanceof dev.rubymod.industry.BeltLoaderBlock;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		if (!ours(state.getBlock()) || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide() || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		if (!Rules.canBuild(player, level, pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity be && !be.canUse(player)) {
			player.sendOverlayMessage(Component.literal("That's not yours to change.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		if (player.isSecondaryUseActive()) {
			pickUp(level, pos, state, player);
			return InteractionResult.SUCCESS;
		}
		Direction now = state.getValue(HorizontalDirectionalBlock.FACING);
		level.setBlock(pos, state.setValue(HorizontalDirectionalBlock.FACING, now.getClockWise()), Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.7F, 1.0F);
		player.sendOverlayMessage(Component.literal("Now facing " + now.getClockWise().getName()).withStyle(ChatFormatting.AQUA));
		return InteractionResult.SUCCESS;
	}

	private static void pickUp(Level level, BlockPos pos, BlockState state, ServerPlayer player) {
		ItemStack item = new ItemStack(state.getBlock());
		if (level.getBlockEntity(pos) instanceof MachineBlockEntity be) {
			MachineBlock.released(be);
			for (ItemStack inside : be.items()) {
				if (!inside.isEmpty()) {
					give(player, inside.copy());
				}
			}
			be.items().clear();
			if (be.tier() > 1) {
				item.set(dev.rubymod.registry.ModComponents.MACHINE_TIER, be.tier());
			}
		}
		level.removeBlockEntity(pos);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		give(player, item);
		level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.7F, 0.8F);
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click a machine or belt to turn it").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Crouch + right-click to pick it up (it keeps its tier)").withStyle(ChatFormatting.GRAY));
	}
}
