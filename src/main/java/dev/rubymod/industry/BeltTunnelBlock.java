package dev.rubymod.industry;

import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Belt Tunnel: two of them, facing the same way up to {@value #REACH} blocks apart, carry things under whatever is
 * between (a wall, a path, a floor). Belts (and machines, and Belt Loaders) feed the entrance from behind; things
 * come out of the exit onto the belt (or into the container) in front of it. The first one you place is an
 * entrance; one placed after it, further along, facing the same way, is its exit. Right-click with an empty hand to
 * swap. Items only: nobody can ride through.
 */
public class BeltTunnelBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	/** The exit (things come out of it), not the entrance. */
	public static final BooleanProperty EXIT = BooleanProperty.create("exit");
	/** How far apart a pair can be. */
	public static final int REACH = 7;
	private static final VoxelShape BASE = Block.box(0, 0, 0, 16, 4, 16);

	public BeltTunnelBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(EXIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, EXIT);
	}

	/** An exit if there's an entrance without one behind it (facing the same way); otherwise an entrance. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction facing = context.getHorizontalDirection();
		BlockPos pos = context.getClickedPos();
		Level level = context.getLevel();
		boolean exit = false;
		for (int i = 1; i <= REACH; i++) {
			BlockState s = level.getBlockState(pos.relative(facing.getOpposite(), i));
			if (s.getBlock() instanceof BeltTunnelBlock && s.getValue(FACING) == facing) {
				exit = !s.getValue(EXIT);
				break;
			}
		}
		return defaultBlockState().setValue(FACING, facing).setValue(EXIT, exit);
	}

	/** The hood is over the end that goes underground: the front of an entrance, the back of an exit. */
	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		Direction hood = state.getValue(EXIT) ? state.getValue(FACING).getOpposite() : state.getValue(FACING);
		VoxelShape top = switch (hood) {
			case NORTH -> Block.box(0, 4, 0, 16, 12, 9);
			case SOUTH -> Block.box(0, 4, 7, 16, 12, 16);
			case EAST -> Block.box(7, 4, 0, 16, 12, 16);
			default -> Block.box(0, 4, 0, 9, 12, 16);
		};
		return Shapes.or(BASE, top);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new BeltTunnelBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.BELT_TUNNEL, BeltTunnelBlockEntity::tick);
	}

	/** Holding something: use it as normal (place the next belt). Only an empty hand swaps entrance and exit. */
	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide()) {
			BlockState flipped = state.cycle(EXIT);
			level.setBlock(pos, flipped, Block.UPDATE_ALL);
			level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.4F, 1.2F);
			player.sendOverlayMessage(Component.literal(flipped.getValue(EXIT) ? "Belt Tunnel exit: things come out the front"
					: "Belt Tunnel entrance: a belt behind it feeds things in").withStyle(ChatFormatting.AQUA));
		}
		return InteractionResult.SUCCESS;
	}

	/** The exit this entrance sends things to (the first tunnel ahead facing the same way, if it's an exit, within reach), or null. */
	public static BlockPos exitFor(Level level, BlockPos pos, BlockState state) {
		if (state.getValue(EXIT)) {
			return null;
		}
		Direction facing = state.getValue(FACING);
		for (int i = 1; i <= REACH; i++) {
			BlockPos at = pos.relative(facing, i);
			if (!level.isLoaded(at)) {
				return null;
			}
			BlockState s = level.getBlockState(at);
			if (s.getBlock() instanceof BeltTunnelBlock && s.getValue(FACING) == facing) {
				return s.getValue(EXIT) ? at : null;
			}
		}
		return null;
	}

	/** The entrance sending things to this exit, or null. */
	public static BlockPos entranceFor(Level level, BlockPos pos, BlockState state) {
		if (!state.getValue(EXIT)) {
			return null;
		}
		Direction facing = state.getValue(FACING);
		for (int i = 1; i <= REACH; i++) {
			BlockPos at = pos.relative(facing.getOpposite(), i);
			BlockState s = level.getBlockState(at);
			if (s.getBlock() instanceof BeltTunnelBlock && s.getValue(FACING) == facing) {
				return s.getValue(EXIT) ? null : at;
			}
		}
		return null;
	}
}
