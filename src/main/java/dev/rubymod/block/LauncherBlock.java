package dev.rubymod.block;

import dev.rubymod.game.HeavyWeapons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Mortar and the Missile Silo. Mark a target with a Rangefinder, then
 * right-click the launcher holding its ammo (Mortar Shells or a Cruise Missile).
 */
public class LauncherBlock extends Block {
	public enum Kind {
		MORTAR,
		MISSILE_SILO
	}

	private final Kind kind;
	private final VoxelShape shape;

	public LauncherBlock(Kind kind, VoxelShape shape, Properties properties) {
		super(properties);
		this.kind = kind;
		this.shape = shape;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape;
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hitResult) {
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			if (kind == Kind.MORTAR) {
				HeavyWeapons.fireMortar(serverLevel, serverPlayer, pos, stack);
			} else {
				HeavyWeapons.launchMissile(serverLevel, serverPlayer, pos, stack);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (player instanceof ServerPlayer serverPlayer) {
			HeavyWeapons.describe(serverPlayer, pos, kind == Kind.MORTAR);
		}
		return InteractionResult.SUCCESS;
	}
}
