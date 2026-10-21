package dev.rubymod.block;

import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.registry.ModBlockEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Base-defense blocks that think every tick: Sentry Turret, Missile Interceptor,
 * Radar Station, Alarm Block, and Claymore. They only work inside a claim, and
 * they defend whoever owns that claim. The logic lives in {@link DefenseBlockEntity}.
 */
public class DefenseBlock extends BaseEntityBlock {
	public enum Kind {
		SENTRY_TURRET,
		INTERCEPTOR,
		RADAR,
		ALARM,
		CLAYMORE,
		FLAK,
		SAM,
		AIRFIELD_PAD
	}

	private final Kind kind;
	private final VoxelShape shape;

	public DefenseBlock(Kind kind, VoxelShape shape, Properties properties) {
		super(properties);
		this.kind = kind;
		this.shape = shape;
	}

	public Kind kind() {
		return kind;
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
		return new DefenseBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.DEFENSE, DefenseBlockEntity::serverTick);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack itemStack) {
		if (!level.isClientSide() && by instanceof ServerPlayer player
				&& SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ()) == null) {
			player.sendSystemMessage(Component.literal("Defenses only work inside a claim. Place your Ruby Heart first!")
					.withStyle(ChatFormatting.YELLOW), true);
		}
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof DefenseBlockEntity be)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if ((kind == Kind.SENTRY_TURRET || kind == Kind.FLAK) && stack.is(ModItems.HEAVY_AMMO)) {
			if (!level.isClientSide()) {
				int space = DefenseBlockEntity.MAX_TURRET_AMMO - be.ammo;
				int add = Math.min(space, stack.getCount());
				be.ammo += add;
				be.setChanged();
				if (!player.isCreative()) {
					stack.shrink(add);
				}
				level.playSound(null, pos, SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
				player.sendOverlayMessage(Component.literal((kind == Kind.FLAK ? "Flak shells: " : "Turret ammo: ") + be.ammo + " / " + DefenseBlockEntity.MAX_TURRET_AMMO));
			}
			return InteractionResult.SUCCESS;
		}
		if ((kind == Kind.INTERCEPTOR || kind == Kind.SAM) && stack.is(ModItems.RUBY)) {
			if (be.charges >= DefenseBlockEntity.MAX_CHARGES) {
				return InteractionResult.FAIL;
			}
			if (!level.isClientSide()) {
				be.charges++;
				be.setChanged();
				if (!player.isCreative()) {
					stack.shrink(1);
				}
				level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 1.5F);
				player.sendOverlayMessage(Component.literal((kind == Kind.SAM ? "SAM missiles: " : "Interceptor charges: ") + be.charges + " / " + DefenseBlockEntity.MAX_CHARGES));
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof DefenseBlockEntity be) {
			String status = switch (kind) {
				case SENTRY_TURRET -> "Sentry Turret: " + be.ammo + " rounds. Load it with Heavy Ammo.";
				case INTERCEPTOR -> "Missile Interceptor: " + be.charges + "/" + DefenseBlockEntity.MAX_CHARGES + " charges. Recharge with rubies.";
				case RADAR -> "Radar Station: online. Contacts within 96 blocks show on your HUD.";
				case ALARM -> "Alarm Block: armed. Alerts you when enemies come within 12 blocks.";
				case CLAYMORE -> "Claymore: armed on Battle Day. Fires at enemies in front of it.";
				case FLAK -> "Flak Cannon: " + be.ammo + " shells. Fires bursting shells at enemy aircraft within 80 blocks. Load with Heavy Ammo.";
				case SAM -> "SAM Site: " + be.charges + "/" + DefenseBlockEntity.MAX_CHARGES + " missiles. Shoots down enemy aircraft within 180 blocks. Reload with rubies.";
				case AIRFIELD_PAD -> "Airfield Pad: park an aircraft within 12 blocks and stop to rearm and repair it.";
			};
			player.sendOverlayMessage(Component.literal(status));
		}
		return InteractionResult.SUCCESS;
	}
}
