package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.game.GameConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

/** Right-click the ground to post a hired guard there (Rifleman or AT Gunner). */
public class GuardContractItem extends Item {
	private final int role;

	public GuardContractItem(int role, Properties properties) {
		super(properties);
		this.role = role;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		int fielded = level.getEntitiesOfClass(SoldierEntity.class, player.getBoundingBox().inflate(4096),
				s -> !s.isRaider() && s.owner().equals(player.getUUID())).size();
		if (fielded >= GameConfig.get().maxGuards) {
			player.sendOverlayMessage(Component.literal("You already have " + fielded + " guards (the limit).").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		SoldierEntity guard = SoldierEntity.guard(level, player, pos, role);
		if (guard == null || !level.noCollision(guard, guard.getBoundingBox())) {
			player.sendOverlayMessage(Component.literal("Not enough room here.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		level.addFreshEntity(guard);
		level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
		player.sendOverlayMessage(Component.literal("Guard posted. Right-click them: follow me / hold here. Sneak + right-click: pack up.")
				.withStyle(ChatFormatting.GREEN));
		context.getItemInHand().consume(1, player);
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal(role == SoldierEntity.AT_GUNNER
				? "An anti-tank gunner: rockets at vehicles, aircraft and raiders" : "A rifleman: guards your base or follows you")
				.withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Fights monsters and raiders any time, enemy players when PvP is on").withStyle(ChatFormatting.DARK_GRAY));
		builder.accept(Component.literal("Right-click the ground to post them").withStyle(ChatFormatting.DARK_GRAY));
	}
}
