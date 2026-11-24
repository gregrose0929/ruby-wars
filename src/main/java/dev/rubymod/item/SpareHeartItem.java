package dev.rubymod.item;

import dev.rubymod.game.SpareHearts;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** A Spare Heart (see SpareHearts): put it down on open ground in Open War, and you come back there while it stands. */
public class SpareHeartItem extends BlockItem {
	public SpareHeartItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
			String problem = SpareHearts.placeProblem(level, player, context.getClickedPos());
			if (problem != null) {
				player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED), true);
				return InteractionResult.FAIL;
			}
			InteractionResult result = super.place(context);
			if (result.consumesAction()) {
				SpareHearts.placed(player, context.getClickedPos());
			}
			return result;
		}
		return super.place(context);
	}
}
