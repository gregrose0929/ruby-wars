package dev.rubymod.block;

import dev.rubymod.game.Armor;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Anti-tank mine. People can walk over it; a driven vehicle sets it off and takes
 * heavy damage (130, most of a tank). Inside a claim it only fires at enemies on
 * Battle Day; outside claims it fires at any driven vehicle.
 */
public class AtMineBlock extends Block {
	private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 3, 15);

	public AtMineBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
		if (!(level instanceof ServerLevel serverLevel) || !Armor.isArmored(entity) || entity.getControllingPassenger() == null) {
			return;
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ());
		if (claim != null && (!Rules.phase().pvp() || !Rules.isEnemyOf(claim, entity.getControllingPassenger()))) {
			return;
		}
		serverLevel.removeBlock(pos, false);
		Armor.blast(serverLevel, null, null, Vec3.atCenterOf(pos), 130.0F, null);
		Explosions.explode(serverLevel, null, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 3.5F, false);
	}
}
