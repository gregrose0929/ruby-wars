package dev.rubymod.block;

import dev.rubymod.game.Claims;
import dev.rubymod.game.SeasonData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The center of a player's base. Placing it claims the land around it, and on
 * Battle Day its owner respawns here until it is destroyed.
 */
public class RubyHeartBlock extends Block {
	/** How it looks (a cosmetic, picked on the profile screen): it's the same Heart whatever the look. */
	public enum Look implements net.minecraft.util.StringRepresentable {
		RUBY("ruby", 0xFF2040), FROST("frost", 0x9AD8FF), EMERALD("emerald", 0x3CE07A), LAVA("lava", 0xFF8A20), CRYSTAL("crystal", 0xC07CFF),
		GOLD("gold", 0xFFD040), SHADOW("shadow", 0xB040FF);

		private final String name;
		public final int particle;

		Look(String name, int particle) {
			this.name = name;
			this.particle = particle;
		}

		@Override
		public String getSerializedName() {
			return name;
		}

		public static Look byName(String name) {
			for (Look l : values()) {
				if (l.name.equals(name)) {
					return l;
				}
			}
			return RUBY;
		}
	}

	public static final net.minecraft.world.level.block.state.properties.EnumProperty<Look> LOOK =
			net.minecraft.world.level.block.state.properties.EnumProperty.create("look", Look.class);

	public RubyHeartBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(LOOK, Look.RUBY));
	}

	@Override
	protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LOOK);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack itemStack) {
		if (level instanceof ServerLevel serverLevel && by instanceof ServerPlayer player) {
			Claims.onHeartPlaced(serverLevel, player, pos);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide()) {
			SeasonData.Claim claim = SeasonData.get().heartAt(level.dimension().identifier().toString(), pos);
			if (claim != null) {
				player.sendSystemMessage(Component.literal(claim.ownerName + "'s Ruby Heart")
						.withStyle(ChatFormatting.RED)
						.append(Component.literal("  HP " + claim.hp + "/" + claim.maxHp() + "  Tier " + claim.tier + "  Radius " + claim.radius())
								.withStyle(ChatFormatting.GRAY)));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (random.nextInt(3) == 0) {
			level.addParticle(new DustParticleOptions(state.getValue(LOOK).particle, 1.0F),
					pos.getX() + random.nextDouble(), pos.getY() + 1.1, pos.getZ() + random.nextDouble(), 0, 0.02, 0);
		}
	}
}
