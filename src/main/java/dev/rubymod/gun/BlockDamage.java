package dev.rubymod.gun;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dev.rubymod.game.Rules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bullets chip away at blocks. Each block has hit points from its hardness
 * (dirt ~14, stone ~34, planks ~44); hits add up and show cracks, and the block
 * breaks when they run out. Damage heals if the block isn't shot for 10 seconds.
 * Very hard blocks (obsidian, reinforced blocks) shrug bullets off.
 */
public final class BlockDamage {
	private static final float HP_PER_HARDNESS = 20.0F;
	private static final float BASE_HP = 4.0F;
	private static final float MAX_HARDNESS = 10.0F;
	private static final int HEAL_AFTER = 200;

	private record Damage(float amount, long lastHit) {
	}

	private static final Map<GlobalPos, Damage> DAMAGE = new HashMap<>();

	private BlockDamage() {
	}

	public static float hitPoints(BlockState state) {
		return BASE_HP + state.getBlock().defaultDestroyTime() * HP_PER_HARDNESS;
	}

	/** Apply {@code amount} bullet damage to the block at {@code pos}. Returns true if it broke. */
	public static boolean hit(ServerLevel level, BlockPos pos, float amount, Entity shooter) {
		BlockState state = level.getBlockState(pos);
		float hardness = state.getBlock().defaultDestroyTime();
		if (state.isAir() || hardness < 0 || hardness > MAX_HARDNESS || state.getBlock().getExplosionResistance() >= 100.0F
				|| !Rules.canWeaponBreak(level, pos, state)) {
			return false;
		}
		GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
		long now = level.getGameTime();
		Damage previous = DAMAGE.get(key);
		float total = amount + (previous != null && now - previous.lastHit() < HEAL_AFTER ? previous.amount() : 0.0F);
		float hp = hitPoints(state);
		if (total >= hp) {
			DAMAGE.remove(key);
			level.destroyBlockProgress(crackId(pos), pos, -1);
			level.destroyBlock(pos, true, shooter);
			return true;
		}
		DAMAGE.put(key, new Damage(total, now));
		level.destroyBlockProgress(crackId(pos), pos, Math.min(9, (int) (total / hp * 10.0F)));
		return false;
	}

	/** Forget damage on blocks nobody has shot for a while (called every second). */
	public static void heal(ServerLevel level) {
		long now = level.getGameTime();
		Iterator<Map.Entry<GlobalPos, Damage>> it = DAMAGE.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<GlobalPos, Damage> entry = it.next();
			if (entry.getKey().dimension() == level.dimension() && now - entry.getValue().lastHit() >= HEAL_AFTER) {
				level.destroyBlockProgress(crackId(entry.getKey().pos()), entry.getKey().pos(), -1);
				it.remove();
			}
		}
	}

	/** A stable fake "breaker" id per block so crack overlays don't collide with players mining. */
	private static int crackId(BlockPos pos) {
		return 0x52570000 ^ pos.hashCode();
	}
}
