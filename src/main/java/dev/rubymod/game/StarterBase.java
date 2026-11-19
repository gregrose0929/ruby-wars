package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.industry.Grid;
import dev.rubymod.industry.MachineBlock;
import dev.rubymod.industry.MachineBlockEntity;
import dev.rubymod.industry.MachineKind;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The first time each season someone places their Heart, a working base appears around
 * it: an Armory on one side, and on the other two Solar Panels powering a Ruby Drill
 * that feeds a Trade Depot (ore dug is sold straight into the bank), plus a Vault, and
 * some rubies in the bank. Rubies keep coming in, with nothing to figure out first.
 *
 * Each spot is cleared of plants, earth and stone first and given firm ground, so it fits on
 * bumpy ground too (anything with contents, or built by a player, is left alone); whatever
 * still can't fit lands in the player's inventory instead.
 */
public final class StarterBase {
	/** Rubies in the bank to start with, so the Armory has something to sell you right away. */
	private static final int STARTING_RUBIES = 30;

	private StarterBase() {
	}

	/** Build it if this player hasn't had one this season. */
	public static void maybeBuild(ServerLevel level, ServerPlayer player, BlockPos heart) {
		SeasonData.PlayerRecord record = SeasonData.get().player(player.getUUID(), player.getName().getString());
		if (record.starterBase) {
			return;
		}
		record.starterBase = true;
		SeasonData.markDirty();
		List<String> missed = new ArrayList<>();
		// Make room first: each spot gets firm ground at the Heart's level and space above it, so the whole base fits on bumpy ground too.
		for (int[] d : new int[][] {{-2, 3}, {-1, 3}, {0, 3}, {1, 3}, {2, 3}, {3, 5}, {5, 3}, {0, -3}}) {
			pad(level, heart.offset(d[0], 0, d[1]));
		}
		// The power line: panels, then the drill; a conveyor belt carries what it digs into a chest; the depot and vault beside it.
		place(level, player, heart.offset(-2, 0, 3), ModBlocks.SOLAR_PANEL, missed);
		place(level, player, heart.offset(-1, 0, 3), ModBlocks.SOLAR_PANEL, missed);
		BlockPos drill = place(level, player, heart.offset(0, 0, 3), ModBlocks.RUBY_DRILL, missed);
		BlockPos belt = drill == null ? null : placeAt(level, drill.east(),
				ModBlocks.CONVEYOR.defaultBlockState().setValue(dev.rubymod.industry.ConveyorBlock.FACING, Direction.EAST));
		if (belt != null) {
			placeAt(level, belt.east(), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.NORTH));
		}
		place(level, player, heart.offset(3, 0, 5), ModBlocks.TRADE_DEPOT, missed);
		place(level, player, heart.offset(5, 0, 3), ModBlocks.VAULT, missed);
		place(level, player, heart.offset(0, 0, -3), ModBlocks.ARMORY, missed);
		Grid.deposit(player.getUUID(), STARTING_RUBIES * 1000L);
		level.playSound(null, heart, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8F, 1.2F);
		player.sendSystemMessage(Component.literal("Your starter base is ready, with " + STARTING_RUBIES + " rubies in the bank! The Solar Panels power "
				+ "a Ruby Drill; a conveyor belt carries what it digs into the chest next to it. Put things in the Trade Depot to sell them "
				+ "into your bank, and spend at the Armory (right-click it). Upgrade your Heart there too (the Your Heart tab) for a bigger base.")
				.withStyle(ChatFormatting.GREEN));
		if (!missed.isEmpty()) {
			player.sendSystemMessage(Component.literal("No room for your " + String.join(", ", missed) + ": it's in your inventory, place it yourself.")
					.withStyle(ChatFormatting.YELLOW));
		}
	}

	/** Clear a spot (plants, earth, stone; never anything with contents, or someone's build) and give it firm ground. */
	private static void pad(ServerLevel level, BlockPos at) {
		for (int dy = 0; dy < 3; dy++) {
			BlockPos p = at.above(dy);
			BlockState state = level.getBlockState(p);
			if (!state.isAir() && soft(level, p, state)) {
				level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		BlockPos below = at.below();
		BlockState ground = level.getBlockState(below);
		if (!ground.isFaceSturdy(level, below, Direction.UP) && (ground.canBeReplaced() || soft(level, below, ground))) {
			level.setBlock(below, net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	/** Natural stuff that can go to make room: no block entity, breakable, and not a player-made block like planks or glass. */
	private static boolean soft(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0) {
			return false;
		}
		return state.canBeReplaced() || state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(net.minecraft.tags.BlockTags.SAND)
				|| state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.tags.BlockTags.LEAVES)
				|| state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)
				|| state.is(net.minecraft.tags.BlockTags.FLOWERS) || !state.getFluidState().isEmpty();
	}

	/** A plain block at {@code at}, if there's room and ground under it. */
	private static BlockPos placeAt(ServerLevel level, BlockPos at, BlockState state) {
		if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) {
			return null;
		}
		level.setBlock(at, state, Block.UPDATE_ALL);
		return at;
	}

	/** Put {@code block} at the nearest free ground around {@code at} (or give it to the player). */
	private static BlockPos place(ServerLevel level, ServerPlayer player, BlockPos at, Block block, List<String> missed) {
		for (int dy : new int[] {0, 1, -1, 2, -2, 3}) {
			BlockPos pos = at.above(dy);
			BlockState here = level.getBlockState(pos);
			BlockState below = level.getBlockState(pos.below());
			if (!here.canBeReplaced() || !here.getFluidState().isEmpty() || !below.isFaceSturdy(level, pos.below(), Direction.UP)) {
				continue;
			}
			BlockState state = block.defaultBlockState();
			if (block instanceof MachineBlock) {
				state = state.setValue(MachineBlock.FACING, Direction.NORTH);
			}
			level.setBlock(pos, state, Block.UPDATE_ALL);
			if (block instanceof MachineBlock machine && level.getBlockEntity(pos) instanceof MachineBlockEntity be) {
				be.setOwner(player.getUUID());
				String goal = switch (machine.kind()) {
					case RUBY_DRILL -> "drill";
					case TRADE_DEPOT -> "depot";
					case SOLAR_PANEL -> "power";
					default -> null;
				};
				if (goal != null) {
					Goals.add(level.getServer(), player.getUUID(), goal, 1);
				}
				if (machine.kind() == MachineKind.RUBY_DRILL) {
					Grid.account(player.getUUID()).drills++;
				}
			}
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 8, 0.4, 0.4, 0.4, 0);
			return pos;
		}
		ItemStack item = new ItemStack(block);
		if (!player.getInventory().add(item)) {
			player.drop(item, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		missed.add(block.getName().getString());
		return null;
	}
}
