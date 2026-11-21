package dev.rubymod.industry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Rules;
import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Explosion;
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

/**
 * Ore Silo: holds a huge amount of one thing. Right-click with an item to put that stack in (twice quickly: all of it
 * from your inventory); with an empty hand to take a stack out (crouch: just one). Belts and hoppers fill and empty
 * it like a chest. Look at it to see what it holds.
 */
public class OreSiloBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	/** When each player last put something in (a second click soon after puts in all of it). */
	private static final Map<UUID, Long> LAST_PUT = new HashMap<>();

	public OreSiloBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SiloBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.SILO, SiloBlockEntity::serverTick);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
		super.setPlacedBy(level, pos, state, by, stack);
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SiloBlockEntity be && by instanceof Player player) {
			be.setOwner(player.getUUID());
		}
	}

	/** Its owner, or someone trusted in its owner's base (or anyone, for one nobody owns). */
	private static boolean mayUse(SiloBlockEntity be, Player player) {
		if (be.owner() == null || be.owner().equals(player.getUUID()) || Rules.isAdminBypass(player)) {
			return true;
		}
		var claim = dev.rubymod.game.SeasonData.get().claimOf(be.owner());
		return claim != null && claim.canUse(player.getUUID());
	}

	public static String describe(SiloBlockEntity be) {
		ItemStack type = be.type();
		if (type.isEmpty()) {
			return "Ore Silo: empty (holds 128 stacks of one thing)";
		}
		return String.format(java.util.Locale.ROOT, "Ore Silo: %s %,d / %,d", type.getHoverName().getString(), be.total(), be.capacity());
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		if (stack.isEmpty() || !(level.getBlockEntity(pos) instanceof SiloBlockEntity be)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (stack.getItem() instanceof dev.rubymod.item.WrenchItem) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!mayUse(be, player)) {
			player.sendOverlayMessage(Component.literal("This Ore Silo belongs to someone else.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		if (!be.type().isEmpty() && !ItemStack.isSameItemSameComponents(be.type(), stack)) {
			player.sendOverlayMessage(Component.literal("This silo holds " + be.type().getHoverName().getString() + ": empty it to store something else.")
					.withStyle(ChatFormatting.GOLD));
			return InteractionResult.FAIL;
		}
		long now = level.getGameTime();
		Long last = LAST_PUT.get(player.getUUID());
		int moved = be.put(stack);
		if (last != null && now - last <= 10 && now >= last) {
			// A quick second click: everything like it from your inventory.
			var inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, be.type())) {
					moved += be.put(s);
				}
			}
		}
		LAST_PUT.put(player.getUUID(), now);
		if (moved > 0) {
			level.playSound(null, pos, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.4F, 1.3F);
		}
		player.sendOverlayMessage(Component.literal((moved > 0 ? "+" + moved + "  " : "") + describe(be)
				+ (moved == 0 ? " (full)" : last == null || now - last > 10 ? "   Click again quickly to put in all of it" : ""))
				.withStyle(ChatFormatting.AQUA));
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof SiloBlockEntity be)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!mayUse(be, player)) {
			player.sendOverlayMessage(Component.literal("This Ore Silo belongs to someone else.").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		ItemStack type = be.type();
		if (type.isEmpty()) {
			player.sendOverlayMessage(Component.literal("Ore Silo: empty. Right-click it with something to store it, or run a belt into it.")
					.withStyle(ChatFormatting.AQUA));
			return InteractionResult.SUCCESS;
		}
		ItemStack taken = be.take(player.isSecondaryUseActive() ? 1 : type.getMaxStackSize());
		int n = taken.getCount();
		if (!player.getInventory().add(taken)) {
			be.put(taken);
		}
		if (n - taken.getCount() > 0) {
			level.playSound(null, pos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.4F, 1.3F);
		}
		player.sendOverlayMessage(Component.literal(describe(be)).withStyle(ChatFormatting.AQUA));
		return InteractionResult.SUCCESS;
	}

	/** The silo item, carrying what it holds. */
	private ItemStack drop(SiloBlockEntity be) {
		ItemStack drop = new ItemStack(this);
		var stacks = be.asStacks();
		if (!stacks.isEmpty()) {
			drop.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
		}
		be.clearContent();
		return drop;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SiloBlockEntity be && (!player.isCreative() || !be.isEmpty())) {
			Block.popResource(level, pos, drop(be));
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected void onExplosionHit(BlockState state, ServerLevel level, BlockPos pos, Explosion explosion,
			java.util.function.BiConsumer<ItemStack, BlockPos> onHit) {
		if (level.getBlockEntity(pos) instanceof SiloBlockEntity be && Rules.canWeaponBreak(level, pos, state)) {
			Block.popResource(level, pos, drop(be));
		}
		super.onExplosionHit(state, level, pos, explosion, onHit);
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		if (level.getBlockEntity(pos) instanceof SiloBlockEntity be && be.total() > 0) {
			return 1 + (int) Math.floor(14.0 * be.total() / be.capacity());
		}
		return 0;
	}
}
