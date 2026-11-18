package dev.rubymod.block;

import dev.rubymod.game.Movers;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Ruby Bearing and the Ruby Piston: power one (a lever, a button held, any redstone) and the blocks stuck to its
 * front move: a bearing swings them a quarter turn round its axis (a drawbridge, a roof that opens), a piston slides
 * them out (a sliding gate, a lift). Power off, and they go back. Right-click with an empty hand to set it up: a
 * piston's push, a bearing's direction (crouch: a quarter or a half turn).
 */
public class RubyMoverBlock extends Block {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
	/** Where its blocks are now: swung (or pushed) out, or home. */
	public static final BooleanProperty OUT = BooleanProperty.create("out");
	/** A bearing that swings the other way. */
	public static final BooleanProperty REVERSED = BooleanProperty.create("reversed");
	/** A bearing that turns half way round (not a quarter). */
	public static final BooleanProperty HALF = BooleanProperty.create("half");
	/** How far a piston pushes. */
	public static final net.minecraft.world.level.block.state.properties.IntegerProperty DISTANCE =
			net.minecraft.world.level.block.state.properties.IntegerProperty.create("distance", 1, 8);

	private final boolean bearing;

	public RubyMoverBlock(boolean bearing, Properties properties) {
		super(properties);
		this.bearing = bearing;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false).setValue(OUT, false).setValue(REVERSED, false)
				.setValue(HALF, false).setValue(DISTANCE, 3));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED, OUT, REVERSED, HALF, DISTANCE);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// Its front points out of the face you put it on (build what it moves on that side).
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	/**
	 * Settings (empty hand, while its blocks are home): a piston's push (1 to 8 blocks, round and round); a bearing's
	 * direction, or (crouching) a quarter or a half turn.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (state.getValue(OUT) || Movers.busy(pos)) {
			player.sendOverlayMessage(Component.literal("Change it while its blocks are home (power it off first).").withStyle(ChatFormatting.YELLOW));
			return InteractionResult.SUCCESS;
		}
		if (!bearing) {
			int d = state.getValue(DISTANCE) % 8 + 1;
			level.setBlock(pos, state.setValue(DISTANCE, d), 3);
			player.sendOverlayMessage(Component.literal("Ruby Piston: pushes " + d + (d == 1 ? " block" : " blocks") + " now.").withStyle(ChatFormatting.LIGHT_PURPLE));
		} else if (player.isShiftKeyDown()) {
			boolean half = !state.getValue(HALF);
			level.setBlock(pos, state.setValue(HALF, half), 3);
			player.sendOverlayMessage(Component.literal("Ruby Bearing: turns " + (half ? "half way round" : "a quarter turn") + " now.")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		} else {
			boolean reversed = !state.getValue(REVERSED);
			level.setBlock(pos, state.setValue(REVERSED, reversed), 3);
			player.sendOverlayMessage(Component.literal("Ruby Bearing: it'll swing " + (reversed ? "the other way" : "the first way") + " now.")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston) {
		if (level instanceof ServerLevel server) {
			boolean powered = server.hasNeighborSignal(pos);
			if (powered != state.getValue(POWERED)) {
				server.setBlock(pos, state.setValue(POWERED, powered), 2);
				go(server, pos, null);
			}
		}
	}

	/** Move to match the power (out when powered, home when not), unless it's already there or moving. */
	public static void go(ServerLevel level, BlockPos pos, ServerPlayer tell) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof RubyMoverBlock mover) || Movers.busy(pos)) {
			return;
		}
		boolean want = state.getValue(POWERED);
		if (want == state.getValue(OUT)) {
			return;
		}
		Direction front = state.getValue(FACING);
		Runnable done = () -> {
			BlockState now = level.getBlockState(pos);
			if (now.getBlock() instanceof RubyMoverBlock) {
				level.setBlock(pos, now.setValue(OUT, want), 2);
				// The power changed while it was moving: catch up.
				go(level, pos, null);
			}
		};
		String why;
		if (mover.bearing) {
			float degrees = (state.getValue(REVERSED) ? -90 : 90) * (state.getValue(HALF) ? 2 : 1) * (want ? 1 : -1);
			why = Movers.rotate(level, pos, front, degrees, done);
		} else {
			why = Movers.slide(level, pos, front, want ? front : front.getOpposite(), state.getValue(DISTANCE), done);
		}
		if (why != null) {
			for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, new net.minecraft.world.phys.AABB(pos).inflate(16))) {
				p.sendOverlayMessage(Component.literal((mover.bearing ? "Ruby Bearing: " : "Ruby Piston: ") + why).withStyle(ChatFormatting.YELLOW));
			}
		}
	}
}
