package dev.rubymod.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.Shapes;

/** Laser Tripwire: a defense block that shines its beam out of the face you put it on (see DefenseBlockEntity). */
public class LaserTripwireBlock extends DefenseBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

	public LaserTripwireBlock(Properties properties) {
		super(Kind.LASER, Shapes.block(), properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}
}
