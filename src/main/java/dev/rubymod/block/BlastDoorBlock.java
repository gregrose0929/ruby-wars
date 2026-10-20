package dev.rubymod.block;

import dev.rubymod.game.Rules;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A steel door that opens by hand, but only for its claim's owner and trusted
 * players (checked in ClaimProtection). Redstone can't open it, so raiders
 * can't just press a button.
 */
public class BlastDoorBlock extends DoorBlock {
	public BlastDoorBlock(Properties properties) {
		super(BlockSetType.IRON, properties);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!Rules.canInteract(player, level, pos, state)) {
			// Locked. Let the held item (like a Breaching Charge) be used on the door instead.
			return InteractionResult.PASS;
		}
		setOpen(player, level, state, pos, !isOpen(state));
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston) {
		// Ignore redstone.
	}
}
