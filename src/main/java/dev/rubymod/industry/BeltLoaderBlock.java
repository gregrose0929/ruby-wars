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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Belt Loader: takes things out of the chest, furnace or machine behind it and puts them on the belt in front (or
 * straight into a container in front). Right-click it with an item to load only that (gold ore, say); crouch and
 * right-click with an empty hand to take anything again. A few Loaders round one chest, each with its own belt,
 * sort it.
 */
public class BeltLoaderBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 10, 15);

	public BeltLoaderBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** Facing away from the container you put it against (or the way you're looking, if there isn't one). */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Direction clicked = context.getClickedFace();
		if (clicked.getAxis().isHorizontal() && Belts.containerAt(level, pos.relative(clicked.getOpposite())) != null) {
			return defaultBlockState().setValue(FACING, clicked);
		}
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (Belts.containerAt(level, pos.relative(d.getOpposite())) != null) {
				return defaultBlockState().setValue(FACING, d);
			}
		}
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FilterBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.FILTER, FilterBlockEntity::loaderTick);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		return setFilter(level, pos, player, stack, "Belt Loader");
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
			}
			player.sendOverlayMessage(Component.literal("Belt Loader: takes " + be.filterName()
					+ (be.filter().isEmpty() ? ". Right-click it with an item to take only that." : ". Crouch and right-click to take anything."))
					.withStyle(ChatFormatting.AQUA));
		}
		return InteractionResult.SUCCESS;
	}

	/** Right-click with an item: that's all it handles now. (Shared with the Belt Sorter.) */
	static InteractionResult setFilter(Level level, BlockPos pos, Player player, ItemStack stack, String what) {
		if (stack.isEmpty() || !(level.getBlockEntity(pos) instanceof FilterBlockEntity be)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (!level.isClientSide()) {
			be.setFilter(stack);
			level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1.2F);
			player.sendOverlayMessage(Component.literal(what + ": only " + be.filterName() + " now. Crouch and right-click with an empty hand for anything.")
					.withStyle(ChatFormatting.AQUA));
		}
		return InteractionResult.SUCCESS;
	}
}
