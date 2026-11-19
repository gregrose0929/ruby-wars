package dev.rubymod.industry;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Belt Sorter: a belt piece that turns one kind of item off to the side (its left, or its right) and lets everything
 * else carry straight on. Right-click it with an item to choose what it turns; right-click with an empty hand to
 * swap sides; crouch and right-click to clear it (then it turns nothing).
 */
public class BeltSorterBlock extends ConveyorBlock implements EntityBlock {
	public static final BooleanProperty RIGHT = BooleanProperty.create("right");

	public BeltSorterBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(RIGHT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(RIGHT);
	}

	/** Which way things it turns go. */
	public static Direction side(BlockState state) {
		Direction facing = state.getValue(FACING);
		return state.getValue(RIGHT) ? facing.getClockWise() : facing.getCounterClockWise();
	}

	@Override
	protected Direction route(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (entity instanceof ItemEntity item && level.getBlockEntity(pos) instanceof FilterBlockEntity be && !be.filter().isEmpty()
				&& Belts.matches(be.filter(), item.getItem())) {
			return side(state);
		}
		return state.getValue(FACING);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FilterBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		return BeltLoaderBlock.setFilter(level, pos, player, stack, "Belt Sorter");
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof FilterBlockEntity be)) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide()) {
			if (player.isSecondaryUseActive()) {
				be.setFilter(ItemStack.EMPTY);
				level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.2F);
			} else {
				state = state.cycle(RIGHT);
				level.setBlock(pos, state, Block.UPDATE_ALL);
				level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.6F, 1.2F);
			}
			String where = state.getValue(RIGHT) ? "right" : "left";
			player.sendOverlayMessage(Component.literal(be.filter().isEmpty() ? "Belt Sorter: turns nothing yet. Right-click it with an item to choose."
					: "Belt Sorter: " + be.filterName() + " goes off to the " + where + ", everything else straight on.").withStyle(ChatFormatting.AQUA));
		}
		return InteractionResult.SUCCESS;
	}
}
