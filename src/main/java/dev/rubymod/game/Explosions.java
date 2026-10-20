package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every explosion in the world passes its list of blocks through {@link #filter}
 * (see ServerExplosionMixin). That's where claims are protected and armored
 * blocks soak up hits instead of breaking.
 */
public final class Explosions {
	/** Explosions this big or bigger (cruise missiles) break armored blocks outright. */
	private static final float HEAVY_RADIUS = 6.0F;
	/** Reinforced Concrete ignores explosions smaller than this (grenades). */
	private static final float REINFORCED_MIN_RADIUS = 2.5F;

	private static final Map<String, Map<Long, Integer>> HITS = new HashMap<>();

	private Explosions() {
	}

	public static void explode(ServerLevel level, Entity source, double x, double y, double z, float power, boolean breakBlocks) {
		level.explode(source, x, y, z, power, breakBlocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
	}

	public static List<BlockPos> filter(ServerLevel level, List<BlockPos> positions, float radius) {
		List<BlockPos> result = new ArrayList<>(positions.size());
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (!Rules.canWeaponBreak(level, pos, state)) {
				continue;
			}
			int hp = armor(state);
			if (hp > 0 && !absorb(level, pos, state, hp, radius)) {
				continue;
			}
			result.add(pos);
		}
		return result;
	}

	/** How many explosion hits an armored block takes; 0 = not armored. */
	private static int armor(BlockState state) {
		if (state.is(ModBlocks.CONCRETE_WALL)) {
			return 4;
		}
		if (state.is(ModBlocks.REINFORCED_CONCRETE)) {
			return 3;
		}
		return 0;
	}

	/** Records one hit. Returns true once the block has taken enough to break. */
	private static boolean absorb(ServerLevel level, BlockPos pos, BlockState state, int hp, float radius) {
		if (radius >= HEAVY_RADIUS) {
			return true;
		}
		if (state.is(ModBlocks.REINFORCED_CONCRETE) && radius < REINFORCED_MIN_RADIUS) {
			return false;
		}
		Map<Long, Integer> hits = HITS.computeIfAbsent(Rules.dim(level), k -> new HashMap<>());
		int taken = hits.merge(pos.asLong(), 1, Integer::sum);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				12, 0.4, 0.4, 0.4, 0.1);
		if (taken >= hp) {
			hits.remove(pos.asLong());
			return true;
		}
		return false;
	}

	public static void clearHits() {
		HITS.clear();
	}
}
