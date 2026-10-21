package dev.rubymod.industry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Conveyor Belt: carries items (and gently, people) the way it faces, and feeds
 * them into any container or machine at its end. Machines drop their output
 * onto a belt leading away from them. Chain belts to route goods around a base.
 */
public class ConveyorBlock extends Block {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);
	private static final double SPEED = 0.1;

	public ConveyorBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** Belts run away from you as you place them. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		carry(level, pos, state, entity);
		super.stepOn(level, pos, state, entity);
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
		carry(level, pos, state, entity);
	}

	private void carry(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (entity.isShiftKeyDown()) {
			return;
		}
		Direction facing = state.getValue(FACING);
		Vec3 dir = Vec3.atLowerCornerOf(facing.getUnitVec3i());
		Vec3 v = entity.getDeltaMovement();
		boolean item = entity instanceof ItemEntity;
		double speed = item ? SPEED : SPEED * 0.6;
		// Along the belt at belt speed; across it, drift to the middle so items line up.
		double across = facing.getAxis() == Direction.Axis.Z ? pos.getX() + 0.5 - entity.getX() : pos.getZ() + 0.5 - entity.getZ();
		double ax = facing.getAxis() == Direction.Axis.Z ? across * 0.2 : 0;
		double az = facing.getAxis() == Direction.Axis.X ? across * 0.2 : 0;
		entity.setDeltaMovement(dir.x * speed + ax, v.y, dir.z * speed + az);
		if (!(entity instanceof ItemEntity itemEntity) || level.isClientSide()) {
			return;
		}
		// At the end of the belt: into a container or machine, if there's one.
		Vec3 center = Vec3.atBottomCenterOf(pos);
		double along = entity.position().subtract(center).dot(dir);
		if (along < 0.3) {
			return;
		}
		BlockEntity front = level.getBlockEntity(pos.relative(facing));
		if (front instanceof Container container) {
			ItemStack stack = itemEntity.getItem();
			ItemStack rest = HopperBlockEntity.addItem(null, container, stack.copy(), facing.getOpposite());
			if (rest.isEmpty()) {
				itemEntity.discard();
			} else {
				itemEntity.setItem(rest);
			}
		}
	}

	/** Machines put output on a belt next to them if it leads away. */
	public static boolean leadsAway(BlockState belt, Direction fromMachine) {
		return belt.getBlock() instanceof ConveyorBlock && belt.getValue(FACING) != fromMachine.getOpposite();
	}
}
