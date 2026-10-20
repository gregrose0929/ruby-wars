package dev.rubymod.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.rubymod.gun.Attachment;
import dev.rubymod.gun.GunItem;
import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/** Hold a gun in one hand and this in the other, then right-click to fit it. */
public class AttachmentItem extends Item {
	private final Attachment attachment;

	public AttachmentItem(Attachment attachment, Properties properties) {
		super(properties);
		this.attachment = attachment;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack self = player.getItemInHand(hand);
		ItemStack gun = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		if (!(gun.getItem() instanceof GunItem)) {
			if (player instanceof ServerPlayer sp) {
				sp.sendOverlayMessage(Component.literal("Hold a gun in your other hand to fit the " + attachment.title + "."));
			}
			return InteractionResult.FAIL;
		}
		List<String> fitted = new ArrayList<>(gun.getOrDefault(ModComponents.ATTACHMENTS, List.of()));
		if (fitted.contains(attachment.name())) {
			if (player instanceof ServerPlayer sp) {
				sp.sendOverlayMessage(Component.literal("That gun already has a " + attachment.title + "."));
			}
			return InteractionResult.FAIL;
		}
		if (!level.isClientSide()) {
			fitted.add(attachment.name());
			gun.set(ModComponents.ATTACHMENTS, List.copyOf(fitted));
			self.consume(1, player);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 1.0F, 1.4F);
			((ServerPlayer) player).sendOverlayMessage(Component.literal(attachment.title + " fitted: " + attachment.effect).withStyle(ChatFormatting.GREEN));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal(attachment.effect).withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Hold a gun in your other hand and right-click").withStyle(ChatFormatting.DARK_GRAY));
	}
}
