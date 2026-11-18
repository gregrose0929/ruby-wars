package dev.rubymod.item;

import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Right-click water (or a beach) to put a boat in: the Patrol Boat, the Gunboat, the Landing Craft. */
public class BoatItem extends Item {
	private final Supplier<? extends EntityType<? extends Mob>> type;

	public BoatItem(Supplier<? extends EntityType<? extends Mob>> type, Properties properties) {
		super(properties);
		this.type = type;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		boolean water = level.getFluidState(pos).is(FluidTags.WATER);
		double y = water ? pos.getY() + 0.6 : pos.relative(hit.getDirection()).getY();
		BlockPos at = water ? pos : pos.relative(hit.getDirection());
		Mob boat = type.get().create(server, EntitySpawnReason.SPAWN_ITEM_USE);
		if (boat == null) {
			return InteractionResult.FAIL;
		}
		boat.snapTo(at.getX() + 0.5, y, at.getZ() + 0.5, player.getYRot(), 0);
		boat.yBodyRot = player.getYRot();
		boat.yHeadRot = player.getYRot();
		if (!server.noCollision(boat, boat.getBoundingBox().deflate(0.1))) {
			sp.sendOverlayMessage(Component.literal("Not enough room here: put it in open water.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		server.addFreshEntity(boat);
		server.playSound(null, at, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.0F, 0.8F);
		player.getItemInHand(hand).consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
