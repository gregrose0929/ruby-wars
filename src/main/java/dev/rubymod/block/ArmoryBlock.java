package dev.rubymod.block;

import dev.rubymod.game.Shop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The shop. Right-click to buy guns, ammo, defenses, and upgrades with rubies. */
public class ArmoryBlock extends Block {
	public ArmoryBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (player instanceof ServerPlayer serverPlayer) {
			Shop.open(serverPlayer, 0);
		}
		return InteractionResult.SUCCESS;
	}
}
