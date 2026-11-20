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
 * (a machine keeps its tier, and what's in it goes into your inventory). Crouch and right-click a belt again right
 * after picking one up, and the rest of that belt line comes too. Only in your own base.
 */
public class WrenchItem extends Item {
	public WrenchItem(Properties properties) {
		super(properties);
	}

	private static boolean ours(Block block) {
		return block instanceof MachineBlock || block instanceof dev.rubymod.industry.ConveyorBlock || block instanceof dev.rubymod.industry.BeltLoaderBlock
				|| block instanceof dev.rubymod.industry.BeltTunnelBlock;
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
			boolean belt = state.getBlock() instanceof dev.rubymod.industry.ConveyorBlock;
			long[] last = LAST_BELT.get(player.getUUID());
			if (belt && last != null && level.getGameTime() - last[0] < 40) {
				int n = pickUpLine(level, pos, BlockPos.of(last[1]), player);
				LAST_BELT.remove(player.getUUID());
				player.sendOverlayMessage(Component.literal("Picked up the whole line: " + n + (n == 1 ? " piece" : " pieces")).withStyle(ChatFormatting.AQUA));
				return InteractionResult.SUCCESS;
			}
			pickUp(level, pos, state, player);
			if (belt) {
				LAST_BELT.put(player.getUUID(), new long[] {level.getGameTime(), pos.asLong()});
				player.sendOverlayMessage(Component.literal("Crouch + right-click the next belt to take the whole line").withStyle(ChatFormatting.GRAY));
			}
			return InteractionResult.SUCCESS;
		}
		Direction now = state.getValue(HorizontalDirectionalBlock.FACING);
		level.setBlock(pos, state.setValue(HorizontalDirectionalBlock.FACING, now.getClockWise()), Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.7F, 1.0F);
		player.sendOverlayMessage(Component.literal("Now facing " + now.getClockWise().getName()).withStyle(ChatFormatting.AQUA));
		return InteractionResult.SUCCESS;
	}

	/** When each player last picked up a single belt piece (game time), for "the whole line" on the next click. */
	private static final java.util.Map<java.util.UUID, long[]> LAST_BELT = new java.util.HashMap<>();
	private static final int MAX_LINE = 256;

	/**
	 * Every belt piece joined to this one (side by side, or a step up or down for ramps) that the player may build
	 * at, picked up together. Things riding on them drop where they are. Returns how many pieces came.
	 */
	private static int pickUpLine(Level level, BlockPos start, BlockPos gap, ServerPlayer player) {
		java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		java.util.List<BlockPos> line = new java.util.ArrayList<>();
		todo.add(start);
		seen.add(start);
		// The line ran through the piece just picked up: carry on from the belts on its other side too.
		seen.add(gap);
		for (Direction d : Direction.Plane.HORIZONTAL) {
			for (int dy = -1; dy <= 1; dy++) {
				BlockPos next = gap.relative(d).above(dy);
				BlockState there = level.getBlockState(next);
				// It fed the gap, or carries on from it (a belt running alongside points another way).
				boolean part = there.getBlock() instanceof dev.rubymod.industry.ConveyorBlock
						&& (dev.rubymod.industry.ConveyorBlock.feeds(level, next, d.getOpposite()) || there.getValue(dev.rubymod.industry.ConveyorBlock.FACING) == d);
				if (part && start.distManhattan(gap) <= 3 && seen.add(next)) {
					todo.add(next);
				}
			}
		}
		while (!todo.isEmpty() && line.size() < MAX_LINE) {
			BlockPos at = todo.poll();
			if (!(level.getBlockState(at).getBlock() instanceof dev.rubymod.industry.ConveyorBlock) || !Rules.canBuild(player, level, at)) {
				continue;
			}
			line.add(at);
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos next = at.relative(d).above(dy);
					if (!seen.contains(next) && joined(level, at, next, d, dy)) {
						seen.add(next);
						todo.add(next);
					}
				}
			}
		}
		java.util.Map<net.minecraft.world.item.Item, Integer> counts = new java.util.LinkedHashMap<>();
		for (BlockPos at : line) {
			counts.merge(level.getBlockState(at).getBlock().asItem(), 1, Integer::sum);
			level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		counts.forEach((item, n) -> {
			int left = n;
			while (left > 0) {
				int take = Math.min(left, item.getDefaultMaxStackSize());
				give(player, new ItemStack(item, take));
				left -= take;
			}
		});
		if (!line.isEmpty()) {
			level.playSound(null, start, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.8F, 0.7F);
		}
		return line.size();
	}

	/** Do these two belt pieces carry things between them (so a belt running alongside isn't part of this line)? */
	private static boolean joined(Level level, BlockPos at, BlockPos next, Direction d, int dy) {
		BlockState a = level.getBlockState(at);
		BlockState b = level.getBlockState(next);
		if (!(a.getBlock() instanceof dev.rubymod.industry.ConveyorBlock) || !(b.getBlock() instanceof dev.rubymod.industry.ConveyorBlock)) {
			return false;
		}
		if (dy == 0) {
			return dev.rubymod.industry.ConveyorBlock.feeds(level, at, d) || dev.rubymod.industry.ConveyorBlock.feeds(level, next, d.getOpposite());
		}
		// A step up or down: one of them is a ramp, and one runs toward the other.
		boolean ramp = a.getBlock() instanceof dev.rubymod.industry.BeltRampBlock || b.getBlock() instanceof dev.rubymod.industry.BeltRampBlock;
		return ramp && (a.getValue(dev.rubymod.industry.ConveyorBlock.FACING) == d
				|| b.getValue(dev.rubymod.industry.ConveyorBlock.FACING) == d.getOpposite());
	}

	private static void pickUp(Level level, BlockPos pos, BlockState state, ServerPlayer player) {
		ItemStack item = new ItemStack(state.getBlock());
		if (level.getBlockEntity(pos) instanceof MachineBlockEntity be) {
			MachineBlock.released(be);
			if (level instanceof net.minecraft.server.level.ServerLevel server) {
				dev.rubymod.industry.BaseMachines.removed(server, pos);
			}
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
		if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container box && !(level.getBlockEntity(pos) instanceof MachineBlockEntity)) {
			// A Belt Tunnel (or anything else with things inside): those come with it.
			for (int i = 0; i < box.getContainerSize(); i++) {
				if (!box.getItem(i).isEmpty()) {
					give(player, box.removeItemNoUpdate(i));
				}
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
		builder.accept(Component.literal("Do it again on the next belt to take the whole line").withStyle(ChatFormatting.GRAY));
	}
}
