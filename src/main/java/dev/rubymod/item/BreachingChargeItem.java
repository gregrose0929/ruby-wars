package dev.rubymod.item;

import dev.rubymod.entity.BreachingChargeEntity;
import dev.rubymod.game.Rules;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** Right-click a block to plant a charge with a 5 second fuse. The only thing that can damage a Ruby Heart. */
public class BreachingChargeItem extends Item {
	public BreachingChargeItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		if (!Rules.phase().raidWeaponsActive()) {
			player.sendSystemMessage(Component.literal("Breaching Charges only work during Open War on Battle Day.").withStyle(ChatFormatting.YELLOW), true);
			return InteractionResult.FAIL;
		}
		Vec3 at = context.getClickLocation().add(Vec3.atLowerCornerOf(context.getClickedFace().getUnitVec3i()).scale(0.1));
		level.addFreshEntity(BreachingChargeEntity.plant(level, player, context.getClickedPos(), at));
		level.playSound(null, at.x, at.y, at.z, SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1.0F, 1.0F);
		context.getItemInHand().consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
