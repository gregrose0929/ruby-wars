package dev.rubymod.industry;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Belt Ramp: a belt that climbs one block the way it faces. Things come on at the bottom and go off at the top, onto
 * the next belt (or into a container) a level up. Right-click it with an empty hand to make it a down ramp: things
 * come on at the top (off a belt a level up) and ride down to the belt below.
 */
public class BeltRampBlock extends ConveyorBlock {
	/** Carries things down (on at the high end behind it, off at the low end in front). */
	public static final BooleanProperty DOWN = BooleanProperty.create("down");
	private static final VoxelShape LOW = Block.box(0, 0, 0, 16, 4, 16);

	public BeltRampBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(DOWN, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(DOWN);
	}

	/** A slab, with a step up at the high end (the front of an up ramp, the back of a down ramp). */
	private static VoxelShape shape(Direction high) {
		VoxelShape step = switch (high) {
			case NORTH -> Block.box(0, 4, 0, 16, 16, 8);
			case SOUTH -> Block.box(0, 4, 8, 16, 16, 16);
			case EAST -> Block.box(8, 4, 0, 16, 16, 16);
			default -> Block.box(0, 4, 0, 8, 16, 16);
		};
		return Shapes.or(LOW, step);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		Direction facing = state.getValue(FACING);
		return shape(state.getValue(DOWN) ? facing.getOpposite() : facing);
	}

	@Override
	protected double lift(BlockPos pos, Entity entity) {
		// Up over the step, until it's at the top. (Going down, things just roll off the step.)
		return entity.level().getBlockState(pos).getValue(DOWN) || entity.getY() >= pos.getY() + 1.05 ? Double.NEGATIVE_INFINITY : 0.3;
	}

	/** Holding something (another belt to place, say): use it as normal. Only an empty hand flips the ramp. */
	@Override
	protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
		return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide()) {
			BlockState flipped = state.cycle(DOWN);
			level.setBlock(pos, flipped, Block.UPDATE_ALL);
			level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.4F, 1.2F);
			player.sendOverlayMessage(Component.literal(flipped.getValue(DOWN) ? "Belt Ramp: carries things down (on at the top, off at the bottom)"
					: "Belt Ramp: carries things up (on at the bottom, off at the top)").withStyle(ChatFormatting.AQUA));
		}
		return InteractionResult.SUCCESS;
	}
}
