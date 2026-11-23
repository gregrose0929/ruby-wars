package dev.rubymod.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hold right-click to look through it (zoomed right in), let go to mark the block in the middle (up to 400 blocks) as
 * your Mortar and Missile Silo target. The target stays on your map and screen until you mark another.
 */
public class RangefinderItem extends Item {
	public static final double RANGE = 400;
	private static final Map<UUID, BlockPos> TARGETS = new HashMap<>();

	public RangefinderItem(Properties properties) {
		super(properties);
	}

	/** Mark a target directly (used by tests). */
	public static void mark(Player player, BlockPos pos) {
		TARGETS.put(player.getUUID(), pos.immutable());
	}

	public static BlockPos target(Player player) {
		return TARGETS.get(player.getUUID());
	}

	/** Hold right-click to look through it (zoomed in), let go to mark what's in the middle. */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(net.minecraft.world.item.ItemStack stack, net.minecraft.world.entity.LivingEntity user) {
		return 72000;
	}

	@Override
	public net.minecraft.world.item.ItemUseAnimation getUseAnimation(net.minecraft.world.item.ItemStack stack) {
		return net.minecraft.world.item.ItemUseAnimation.SPYGLASS;
	}

	@Override
	public boolean releaseUsing(net.minecraft.world.item.ItemStack stack, Level level, net.minecraft.world.entity.LivingEntity entity, int remaining) {
		if (entity instanceof Player player) {
			markLooked(level, player);
		}
		return true;
	}

	/** Mark the block in the middle of their view (and say how far it is). */
	public static void markLooked(Level level, Player player) {
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			Vec3 eye = player.getEyePosition();
			Vec3 end = eye.add(player.getLookAngle().scale(RANGE));
			BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
			if (hit.getType() == HitResult.Type.MISS) {
				serverPlayer.sendSystemMessage(Component.literal("Nothing in range."), true);
			} else {
				BlockPos pos = hit.getBlockPos();
				TARGETS.put(player.getUUID(), pos.immutable());
				int distance = (int) eye.distanceTo(hit.getLocation());
				serverPlayer.sendSystemMessage(Component.literal("Target marked: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
						+ "  (" + distance + " blocks)").withStyle(ChatFormatting.GOLD), true);
				Vec3 at = hit.getLocation();
				serverLevel.sendParticles(serverPlayer, new DustParticleOptions(0xFF0000, 3.0F), true, true, at.x, at.y + 0.2, at.z, 20, 0.3, 0.3, 0.3, 0);
			}
		}
	}
}
