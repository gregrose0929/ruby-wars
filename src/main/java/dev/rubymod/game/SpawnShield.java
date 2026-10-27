package dev.rubymod.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A few seconds of protection from other players after respawning at your
 * Heart, so raiders can't farm you at your spawn point. It ends early the
 * moment you fire a weapon.
 */
public final class SpawnShield {
	public static final int TICKS = 100;
	private static final Map<UUID, Long> SHIELDED_UNTIL = new HashMap<>();

	private SpawnShield() {
	}

	public static void grant(ServerPlayer player) {
		SHIELDED_UNTIL.put(player.getUUID(), player.level().getGameTime() + TICKS);
	}

	public static void clear(ServerPlayer player) {
		SHIELDED_UNTIL.remove(player.getUUID());
	}

	public static boolean isShielded(ServerPlayer player) {
		Long until = SHIELDED_UNTIL.get(player.getUUID());
		if (until == null) {
			return false;
		}
		if (player.level().getGameTime() >= until) {
			SHIELDED_UNTIL.remove(player.getUUID());
			return false;
		}
		return true;
	}

	/** Shimmer around shielded players so attackers can see why their shots aren't landing. */
	public static void tick(ServerPlayer player) {
		if (player.tickCount % 5 == 0 && isShielded(player)) {
			((ServerLevel) player.level()).sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 2, 0.4, 0.6, 0.4, 0.0);
		}
	}
}
