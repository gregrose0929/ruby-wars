package dev.rubymod.item;

import dev.rubymod.entity.ReconDroneEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Right-click to launch a Recon Drone that marks enemies around you for your squad for 90 seconds. */
public class ReconDroneItem extends Item {
	public ReconDroneItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel server && player instanceof ServerPlayer sp) {
			ReconDroneEntity drone = ReconDroneEntity.launch(server, sp);
			if (drone != null) {
				server.addFreshEntity(drone);
				server.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEEHIVE_EXIT, SoundSource.PLAYERS, 1.0F, 1.4F);
				sp.sendOverlayMessage(Component.literal("Recon drone up: enemies within 56 blocks show on your squad's HUD for 90 s.")
						.withStyle(ChatFormatting.AQUA));
				player.getItemInHand(hand).consume(1, player);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
