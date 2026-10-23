package dev.rubymod.block;

import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Holo Camouflage: projects a fake block. Right-click it with any block to copy that
 * block's look (grass, stone, logs...); sneak-right-click with an empty hand to switch
 * it off. To everyone else it looks and feels like the real thing, a solid block; you,
 * your squad and the players you trust walk (and drive) straight through it, so it
 * makes secret doors and hidden bunker entrances.
 */
public class HoloBlock extends BaseEntityBlock {
	public static final BooleanProperty DISGUISED = BooleanProperty.create("disguised");

	public HoloBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(DISGUISED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(DISGUISED);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		// Disguised: the block entity renderer draws the copied block instead.
		return state.getValue(DISGUISED) ? RenderShape.INVISIBLE : RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new HoloBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.HOLO, HoloBlockEntity::serverTick);
	}

	/** Solid for everyone except its owner's side (and what they're driving). */
	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (context instanceof EntityCollisionContext ec && ec.getEntity() != null && level.getBlockEntity(pos) instanceof HoloBlockEntity holo
				&& holo.lets(rider(ec.getEntity()))) {
			return Shapes.empty();
		}
		return Shapes.block();
	}

	private static Entity rider(Entity entity) {
		return entity.getControllingPassenger() instanceof Player driver ? driver : entity;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
		if (!level.isClientSide() && by instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof HoloBlockEntity holo) {
			holo.setOwner(player.getUUID());
			player.sendSystemMessage(Component.literal("Holo Camouflage placed. Right-click it with any block to copy its look; you and your squad can walk through it.")
					.withStyle(ChatFormatting.AQUA), true);
		}
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		if (!(stack.getItem() instanceof BlockItem item) || item.getBlock() instanceof HoloBlock || item.getBlock() instanceof EntityBlock) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (!(level.getBlockEntity(pos) instanceof HoloBlockEntity holo)) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide()) {
			if (!holo.lets(player)) {
				player.sendOverlayMessage(Component.literal("That's someone else's Holo Camouflage.").withStyle(ChatFormatting.RED));
				return InteractionResult.FAIL;
			}
			BlockState look = item.getBlock().defaultBlockState();
			holo.setDisguise(look);
			level.setBlock(pos, state.setValue(DISGUISED, true), Block.UPDATE_ALL);
			level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 0.4F, 1.8F);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!player.isSecondaryUseActive() || !state.getValue(DISGUISED) || !(level.getBlockEntity(pos) instanceof HoloBlockEntity holo)) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide() && holo.lets(player)) {
			holo.setDisguise(null);
			level.setBlock(pos, state.setValue(DISGUISED, false), Block.UPDATE_ALL);
			level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.4F, 1.6F);
		}
		return InteractionResult.SUCCESS;
	}
}
