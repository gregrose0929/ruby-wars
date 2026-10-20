package dev.rubymod.block;

import dev.rubymod.game.Claims;
import dev.rubymod.game.SeasonData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The center of a player's base. Placing it claims the land around it, and on
 * Battle Day its owner respawns here until it is destroyed.
 */
public class RubyHeartBlock extends Block {
	public RubyHeartBlock(Properties properties) {
		super(properties);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack itemStack) {
		if (level instanceof ServerLevel serverLevel && by instanceof ServerPlayer player) {
			Claims.onHeartPlaced(serverLevel, player, pos);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide()) {
			SeasonData.Claim claim = SeasonData.get().heartAt(level.dimension().identifier().toString(), pos);
			if (claim != null) {
				player.sendSystemMessage(Component.literal(claim.ownerName + "'s Ruby Heart")
						.withStyle(ChatFormatting.RED)
						.append(Component.literal("  HP " + claim.hp + "/" + claim.maxHp() + "  Tier " + claim.tier + "  Radius " + claim.radius())
								.withStyle(ChatFormatting.GRAY)));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (random.nextInt(3) == 0) {
			level.addParticle(new DustParticleOptions(0xFF2040, 1.0F),
					pos.getX() + random.nextDouble(), pos.getY() + 1.1, pos.getZ() + random.nextDouble(), 0, 0.02, 0);
		}
	}
}
