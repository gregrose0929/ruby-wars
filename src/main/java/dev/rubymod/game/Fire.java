package dev.rubymod.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Setting things alight (the flamethrower and fire bombs), only where a weapon may break blocks: anywhere outside a
 * claim, and inside one only on Battle Day. Never in the hub. (Spreading is held back the same way: FireBlockMixin.)
 */
public final class Fire {
	private Fire() {
	}

	/** Light a fire at pos (an empty space with something to burn on or beside it). True if it did. */
	public static boolean light(ServerLevel level, BlockPos pos) {
		if (level.dimension() == Hub.HUB || !level.getBlockState(pos).isAir()) {
			return false;
		}
		BlockState below = level.getBlockState(pos.below());
		if (!Rules.canWeaponBreak(level, pos.below(), below) || !Rules.canWeaponBreak(level, pos, level.getBlockState(pos))) {
			return false;
		}
		BlockState fire = BaseFireBlock.getState(level, pos);
		if (!fire.canSurvive(level, pos)) {
			return false;
		}
		level.setBlockAndUpdate(pos, fire);
		return true;
	}

	/** Light the face of the block at hit, on the side it was hit from. */
	public static boolean lightAgainst(ServerLevel level, BlockPos hit, Direction side) {
		return light(level, hit.relative(side));
	}
}
