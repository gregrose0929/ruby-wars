package dev.rubymod.gun;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side rewind for hitscan guns. Every tick we remember where each player
 * was. When someone shoots, targets are checked where the shooter actually saw
 * them (about one ping ago), not where they are now. Without this, players on
 * a home-hosted server with 100+ ms of ping would miss shots that looked clean
 * on their screen. Rewind is capped at 300 ms so laggy shooters can't reach far
 * into the past.
 */
public final class LagCompensation {
	private static final int HISTORY = 20;
	private static final int MAX_REWIND_TICKS = 6;

	private static final Map<UUID, Vec3[]> HISTORY_BY_PLAYER = new HashMap<>();
	private static long currentTick;

	private LagCompensation() {
	}

	/** Called at the end of every server tick. */
	public static void record(MinecraftServer server) {
		currentTick = server.getTickCount();
		int slot = (int) (currentTick % HISTORY);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			HISTORY_BY_PLAYER.computeIfAbsent(player.getUUID(), id -> new Vec3[HISTORY])[slot] = player.position();
		}
	}

	public static void forget(UUID player) {
		HISTORY_BY_PLAYER.remove(player);
	}

	/** How many ticks to rewind for this shooter's ping. */
	public static int rewindTicks(ServerPlayer shooter) {
		int pingMs = shooter.connection == null ? 0 : shooter.connection.latency();
		return Math.clamp(Math.round(pingMs / 50.0F), 0, MAX_REWIND_TICKS);
	}

	/** Where this entity's feet were {@code ticksAgo} ticks ago (players only; others use their current position). */
	public static Vec3 positionAt(Entity entity, int ticksAgo) {
		if (ticksAgo <= 0 || !(entity instanceof ServerPlayer)) {
			return entity.position();
		}
		Vec3[] history = HISTORY_BY_PLAYER.get(entity.getUUID());
		if (history == null) {
			return entity.position();
		}
		Vec3 past = history[(int) Math.floorMod(currentTick - ticksAgo, (long) HISTORY)];
		return past == null ? entity.position() : past;
	}

	/** The entity's hitbox as the shooter saw it. */
	public static AABB boxAt(Entity entity, int ticksAgo) {
		Vec3 offset = positionAt(entity, ticksAgo).subtract(entity.position());
		return entity.getBoundingBox().move(offset);
	}
}
