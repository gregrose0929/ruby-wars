package dev.rubymod.block;

import dev.rubymod.game.Explosions;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A pressure mine. On Battle Day (once PvP is on), an enemy stepping on it
 * inside its claim sets it off.
 */
public class LandmineBlock extends Block implements net.minecraft.world.level.block.EntityBlock {
	private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 2, 14);

	public LandmineBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		// Buried out of sight (and out of reach) for anyone it's hidden from: see MineBlockEntity.
		return MineBlockEntity.shape(SHAPE, level, pos, context);
	}

	@Override
	protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
		// Drawn by its block entity's renderer, only for those who can see it.
		return net.minecraft.world.level.block.RenderShape.INVISIBLE;
	}

	@Override
	public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MineBlockEntity(pos, state);
	}

	@Override
	public <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
			Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
		return MineBlockEntity.ticker(level, type);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (dev.rubymod.game.Horde.inOutpost(serverLevel, pos)) {
			// Co-op Horde: only the horde sets them off.
			if (dev.rubymod.game.Horde.isEnemy(entity)) {
				serverLevel.removeBlock(pos, false);
				dev.rubymod.game.Armor.blast(serverLevel, null, null, net.minecraft.world.phys.Vec3.atCenterOf(pos), 80.0F, null);
				Explosions.explode(serverLevel, null, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 3.0F, false);
			}
			return;
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ());
		if (claim != null && Rules.defenseTarget(claim, entity.getControllingPassenger() != null ? entity.getControllingPassenger() : entity)) {
			serverLevel.removeBlock(pos, false);
			dev.rubymod.game.Armor.blast(serverLevel, null, null, net.minecraft.world.phys.Vec3.atCenterOf(pos), 80.0F, null);
			Explosions.explode(serverLevel, null, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 3.0F, false);
		}
	}
}
