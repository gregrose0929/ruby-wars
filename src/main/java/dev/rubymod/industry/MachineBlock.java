package dev.rubymod.industry;

import dev.rubymod.game.Rules;
import dev.rubymod.registry.ModBlockEntities;
import dev.rubymod.registry.ModComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A machine block (one class for every {@link MachineKind}). Right-click opens its
 * screen. Breaking it keeps its tier on the item, so upgrades aren't lost when you
 * move it; a Vault blown open on Battle Day spills a quarter of its owner's bank.
 */
public class MachineBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

	private final MachineKind kind;
	private final VoxelShape shape;

	public MachineBlock(MachineKind kind, VoxelShape shape, Properties properties) {
		super(properties);
		this.kind = kind;
		this.shape = shape;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	public MachineKind kind() {
		return kind;
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
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MachineBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.MACHINE, MachineBlockEntity::serverTick);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MachineBlockEntity be && by instanceof Player player) {
			be.setOwner(player.getUUID());
			String goal = switch (kind) {
				case RUBY_DRILL -> "drill";
				case TRADE_DEPOT -> "depot";
				case COMBUSTION_GENERATOR, SOLAR_PANEL -> "power";
				default -> null;
			};
			if (goal != null) {
				dev.rubymod.game.Goals.add(level.getServer(), player.getUUID(), goal, 1);
			}
			if (kind == MachineKind.RUBY_DRILL) {
				Grid.account(player.getUUID()).drills++;
			}
			Integer tier = stack.get(ModComponents.MACHINE_TIER);
			if (tier != null) {
				be.setTier(tier);
			}
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MachineBlockEntity be && player instanceof ServerPlayer sp) {
			if (!be.canUse(sp)) {
				sp.sendOverlayMessage(Component.literal("This " + kind.title + " belongs to someone else."));
				return InteractionResult.FAIL;
			}
			sp.openMenu(be);
		}
		return InteractionResult.SUCCESS;
	}

	/** A drill leaving the world frees one of its owner's drill slots. */
	private void released(MachineBlockEntity be) {
		if (kind == MachineKind.RUBY_DRILL) {
			Grid.Account account = Grid.account(be.owner());
			account.drills = Math.max(0, account.drills - 1);
		}
	}

	/** Breaking it by hand: drop the contents and the machine, with its tier kept on the item. */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MachineBlockEntity be) {
			released(be);
			Containers.dropContents(level, pos, be.items());
			if (!player.isCreative()) {
				ItemStack drop = new ItemStack(this);
				if (be.tier() > 1) {
					drop.set(ModComponents.MACHINE_TIER, be.tier());
				}
				Block.popResource(level, pos, drop);
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	/** Blown up (the block entity is still readable here): spill contents; a Vault spills part of the bank. */
	@Override
	protected void onExplosionHit(BlockState state, ServerLevel level, BlockPos pos, Explosion explosion,
			java.util.function.BiConsumer<ItemStack, BlockPos> onHit) {
		if (level.getBlockEntity(pos) instanceof MachineBlockEntity be && Rules.canWeaponBreak(level, pos, state)) {
			released(be);
			Containers.dropContents(level, pos, be.items());
			if (kind == MachineKind.VAULT) {
				be.raided(level);
			}
		}
		super.onExplosionHit(state, level, pos, explosion, onHit);
	}
}
