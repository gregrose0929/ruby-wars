package dev.rubymod.item;

import dev.rubymod.game.Rules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** A pickaxe that mines a 3x3 area facing you. */
public class ExcavatorItem extends Item {
	private static boolean breaking;

	public ExcavatorItem(Properties properties) {
		super(properties);
	}

	/** Called after the player breaks a block: mine the 8 blocks around it too. */
	public static void afterBreak(ServerLevel level, ServerPlayer player, BlockPos center, BlockState centerState) {
		ItemStack tool = player.getMainHandItem();
		if (breaking || !(tool.getItem() instanceof ExcavatorItem) || player.isShiftKeyDown()) {
			return;
		}
		Direction face = player.getXRot() > 45 || player.getXRot() < -45 ? Direction.UP : player.getDirection();
		breaking = true;
		try {
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					if (a == 0 && b == 0) {
						continue;
					}
					BlockPos pos = switch (face.getAxis()) {
						case Y -> center.offset(a, 0, b);
						case X -> center.offset(0, a, b);
						case Z -> center.offset(a, b, 0);
					};
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || state.getDestroySpeed(level, pos) < 0 || state.getDestroySpeed(level, pos) > centerState.getDestroySpeed(level, center) + 1.5F
							|| !tool.isCorrectToolForDrops(state) || !Rules.canBuild(player, level, pos) || level.getBlockEntity(pos) != null) {
						continue;
					}
					player.gameMode.destroyBlock(pos);
					if (tool.isEmpty()) {
						return;
					}
				}
			}
		} finally {
			breaking = false;
		}
	}
}
