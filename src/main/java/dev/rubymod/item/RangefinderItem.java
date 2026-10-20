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

/** Right-click to mark the block you're looking at (up to 400 blocks) as your Mortar and Missile Silo target. */
public class RangefinderItem extends Item {
	private static final double RANGE = 400;
	private static final Map<UUID, BlockPos> TARGETS = new HashMap<>();

	public RangefinderItem(Properties properties) {
		super(properties);
	}

	public static BlockPos target(Player player) {
		return TARGETS.get(player.getUUID());
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			Vec3 eye = player.getEyePosition();
			Vec3 end = eye.add(player.getLookAngle().scale(RANGE));
			BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
			if (hit.getType() == HitResult.Type.MISS) {
				serverPlayer.sendSystemMessage(Component.literal("Nothing in range."), true);
			} else {
				BlockPos pos = hit.getBlockPos();
				TARGETS.put(player.getUUID(), pos);
				int distance = (int) eye.distanceTo(hit.getLocation());
				serverPlayer.sendSystemMessage(Component.literal("Target marked: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
						+ "  (" + distance + " blocks)").withStyle(ChatFormatting.GOLD), true);
				Vec3 at = hit.getLocation();
				serverLevel.sendParticles(serverPlayer, new DustParticleOptions(0xFF0000, 3.0F), true, true, at.x, at.y + 0.2, at.z, 20, 0.3, 0.3, 0.3, 0);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
