package dev.rubymod.item;

import dev.rubymod.game.BaseSecurity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Keycard: right-click it in your own base to make it that base's card; whoever carries it can open its Keycard Doors. */
public class KeycardItem extends Item {
	public KeycardItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer sp) {
			BaseSecurity.bind(sp, sp.getItemInHand(hand));
		}
		return InteractionResult.SUCCESS;
	}
}
