package dev.rubymod.block;

import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Slows down anyone pushing through who isn't trusted in this claim, and cuts them on Battle Day. */
public class BarbedWireBlock extends Block {
	public BarbedWireBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ());
		if (entity instanceof Player player && claim != null && claim.canUse(player.getUUID())) {
			return;
		}
		entity.makeStuckInBlock(state, new Vec3(0.3, 0.6, 0.3));
		if (level instanceof ServerLevel serverLevel && Rules.phase().pvp() && entity instanceof Player && level.getGameTime() % 20 == 0) {
			entity.hurtServer(serverLevel, level.damageSources().sweetBerryBush(), 1.0F);
		}
	}
}
