package dev.rubymod.industry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Belt Ramp: a belt that climbs one block the way it faces. Things come on at the bottom and go off at the top, onto
 * the next belt (or into a container) a level up.
 */
public class BeltRampBlock extends ConveyorBlock {
	private static final VoxelShape LOW = Block.box(0, 0, 0, 16, 4, 16);

	public BeltRampBlock(Properties properties) {
		super(properties);
	}

	/** A slab, with a step up at the front (the high end). */
	private static VoxelShape shape(Direction facing) {
		VoxelShape step = switch (facing) {
			case NORTH -> Block.box(0, 4, 0, 16, 16, 8);
			case SOUTH -> Block.box(0, 4, 8, 16, 16, 16);
			case EAST -> Block.box(8, 4, 0, 16, 16, 16);
			default -> Block.box(0, 4, 0, 8, 16, 16);
		};
		return Shapes.or(LOW, step);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape(state.getValue(FACING));
	}

	@Override
	protected double lift(BlockPos pos, Entity entity) {
		// Up over the step, until it's at the top.
		return entity.getY() < pos.getY() + 1.05 ? 0.3 : Double.NEGATIVE_INFINITY;
	}
}
