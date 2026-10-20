package dev.rubymod.item;

import java.util.function.Supplier;

import dev.rubymod.entity.VehicleEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Right-click the ground to deploy a Jeep or Tank. Right-click the vehicle to get in. */
public class VehicleItem extends Item {
	private final Supplier<EntityType<VehicleEntity>> type;

	public VehicleItem(Supplier<EntityType<VehicleEntity>> type, Properties properties) {
		super(properties);
		this.type = type;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		VehicleEntity vehicle = type.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (vehicle == null) {
			return InteractionResult.FAIL;
		}
		vehicle.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0);
		vehicle.yBodyRot = player.getYRot();
		vehicle.yHeadRot = player.getYRot();
		if (!level.noCollision(vehicle, vehicle.getBoundingBox())) {
			player.sendOverlayMessage(Component.literal("Not enough room here.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		level.addFreshEntity(vehicle);
		level.playSound(null, pos, SoundEvents.IRON_GOLEM_REPAIR, SoundSource.PLAYERS, 1.0F, 0.8F);
		context.getItemInHand().consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
