package dev.rubymod.block;

import dev.rubymod.game.BaseSecurity;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Keycard Reader: right-click it, and if you belong to its base (or carry that base's Keycard) it gives out redstone
 * power for five seconds. Next to a Ruby Piston or Ruby Bearing: a gate that opens for friends and shuts behind them.
 */
public class KeycardReaderBlock extends Block {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
	private static final int OPEN_TICKS = 100;

	public KeycardReaderBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(POWERED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level instanceof ServerLevel server) {
			swipe(server, pos, player);
		}
		return InteractionResult.SUCCESS;
	}

	/** Someone swipes: power if they're allowed; a buzz if not. True if it opened. */
	public static boolean swipe(ServerLevel level, BlockPos pos, Player player) {
		BlockState state = level.getBlockState(pos);
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ());
		boolean allowed = claim == null || claim.canUse(player.getUUID()) || BaseSecurity.hasCard(player, claim);
		if (!allowed) {
			level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 1.0F, 0.5F);
			player.sendOverlayMessage(Component.literal("Access denied: you need this base's Keycard.").withStyle(ChatFormatting.RED));
			return false;
		}
		level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1.0F, 1.6F);
		player.sendOverlayMessage(Component.literal("Access granted.").withStyle(ChatFormatting.GREEN));
		if (!state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, true), 3);
			level.updateNeighborsAt(pos, state.getBlock(), null);
		}
		level.scheduleTick(pos, state.getBlock(), OPEN_TICKS);
		return true;
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, false), 3);
			level.updateNeighborsAt(pos, state.getBlock(), null);
			level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.8F, 1.0F);
		}
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(POWERED) ? 15 : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(POWERED) ? 15 : 0;
	}
}
