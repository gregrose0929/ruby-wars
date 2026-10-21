package dev.rubymod.block;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.game.GameConfig;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A War Grave: an ammo crate with a helmet and dog tags, holding a fallen player's gear. */
public class GraveBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	private static final VoxelShape SHAPE = Block.box(1, 0, 3, 15, 10, 13);

	public GraveBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
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
		return new GraveBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level, BlockState state,
			net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, dev.rubymod.registry.ModBlockEntities.GRAVE, GraveBlockEntity::serverTick);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof GraveBlockEntity grave && player instanceof ServerPlayer sp) {
			if (!grave.canOpen(sp)) {
				int s = grave.lockedSeconds();
				sp.sendOverlayMessage(Component.literal(grave.ownerName() + "'s grave unlocks in " + s / 60 + ":" + String.format("%02d", s % 60))
						.withStyle(ChatFormatting.RED));
				return InteractionResult.FAIL;
			}
			sp.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.sixRows(id, inv, grave), grave.title()));
			level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.BLOCKS, 1.0F, 0.8F);
		}
		return InteractionResult.SUCCESS;
	}

	/** Broken (by its owner, or by anyone once unlocked): the gear spills out. */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof GraveBlockEntity grave) {
			Containers.dropContents(level, pos, grave);
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	/**
	 * Called just before a player dies: moves their whole inventory into a new grave
	 * where they fell. Returns false (vanilla drops as usual) if there's nowhere to put one.
	 */
	public static boolean bury(ServerPlayer player) {
		if (!GameConfig.get().graves || player.isCreative() || player.isSpectator()) {
			return false;
		}
		ServerLevel level = (ServerLevel) player.level();
		if (level.getGameRules().get(GameRules.KEEP_INVENTORY)) {
			return false;
		}
		if (player.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
			return false;
		}
		Inventory inv = player.getInventory();
		List<ItemStack> stacks = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!inv.getItem(i).isEmpty()) {
				stacks.add(inv.getItem(i));
			}
		}
		if (stacks.isEmpty()) {
			return false;
		}
		BlockPos spot = findSpot(level, player.blockPosition());
		if (spot == null) {
			return false;
		}
		level.setBlockAndUpdate(spot, ModBlocks.WAR_GRAVE.defaultBlockState().setValue(FACING, player.getDirection().getOpposite()));
		if (!(level.getBlockEntity(spot) instanceof GraveBlockEntity grave)) {
			return false;
		}
		grave.fill(player, stacks);
		for (int i = 0; i < inv.getContainerSize(); i++) {
			inv.setItem(i, ItemStack.EMPTY);
		}
		int lock = GameConfig.get().graveLockSeconds;
		player.sendSystemMessage(Component.literal("Your gear is in a War Grave at " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
				+ " (only you can open it for " + lock / 60 + " min).").withStyle(ChatFormatting.GOLD));
		dev.rubymod.game.Waypoints.setGrave(player, spot);
		return true;
	}

	private static BlockPos findSpot(ServerLevel level, BlockPos start) {
		int minY = level.getMinY() + 1;
		BlockPos base = start.getY() < minY ? new BlockPos(start.getX(), minY, start.getZ()) : start;
		for (int dy = 0; dy < 12; dy++) {
			BlockPos p = base.above(dy);
			BlockState here = level.getBlockState(p);
			if ((here.isAir() || here.canBeReplaced()) && here.getFluidState().isEmpty()) {
				return p;
			}
		}
		for (int dy = 0; dy < 12; dy++) {
			BlockPos p = base.above(dy);
			if (level.getBlockState(p).canBeReplaced()) {
				return p;
			}
		}
		return null;
	}
}
