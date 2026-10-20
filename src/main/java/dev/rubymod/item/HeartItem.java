package dev.rubymod.item;

import dev.rubymod.game.Claims;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** The Ruby Heart's item. Checks the claim rules before letting it be placed. */
public class HeartItem extends BlockItem {
	public HeartItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
			String problem = Claims.placementProblem(level, player, context.getClickedPos());
			if (problem != null) {
				player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED), true);
				return InteractionResult.FAIL;
			}
		}
		return super.place(context);
	}
}
