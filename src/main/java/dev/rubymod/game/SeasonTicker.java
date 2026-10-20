package dev.rubymod.game;

import dev.rubymod.RubyMod;
import dev.rubymod.net.Payloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The season clock. Phase timers use real (wall-clock) time, so the 14-day
 * build phase keeps counting while the server is off or restarting.
 */
public final class SeasonTicker {
	private static final long MINUTE = 60_000L;
	private static final long DAY = 24 * 60 * MINUTE;
	private static int lastAnnouncedDay = -1;
	private static boolean waitingNotified;

	private SeasonTicker() {
	}

	public static long now() {
		return System.currentTimeMillis();
	}

	public static long durationOf(Phase phase) {
		GameConfig c = GameConfig.get();
		return switch (phase) {
			case BUILD -> c.buildPhaseMinutes * MINUTE;
			case LOCKDOWN -> c.lockdownMinutes * MINUTE;
			case WAR -> c.warMinutes * MINUTE;
			case SUDDEN_DEATH -> c.suddenDeathMinutes * MINUTE;
			default -> 0;
		};
	}

	/** Day of the build phase, starting at 1. Scaled so a shortened test season still counts 14 days. */
	public static int currentDay() {
		SeasonData data = SeasonData.get();
		if (data.phase != Phase.BUILD || data.buildStartedAt == 0) {
			return 0;
		}
		long length = durationOf(Phase.BUILD);
		long dayLength = length >= 14 * DAY ? DAY : Math.max(1, length / 14);
		return (int) ((now() - data.buildStartedAt) / dayLength) + 1;
	}

	public static long remainingMillis() {
		SeasonData data = SeasonData.get();
		if (data.pausedRemaining >= 0) {
			return data.pausedRemaining;
		}
		if (data.phaseEndsAt == 0) {
			return -1;
		}
		return Math.max(0, data.phaseEndsAt - now());
	}

	/** Called once per second. */
	public static void tickSecond(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (data.pausedRemaining < 0 && data.phaseEndsAt != 0 && now() >= data.phaseEndsAt) {
			if (data.phase == Phase.BUILD && !GameConfig.get().autoStartBattle) {
				if (!waitingNotified) {
					waitingNotified = true;
					Alerts.broadcast(server, Component.literal("The build phase is over! Battle Day starts when an admin runs /rubywars phase lockdown.")
							.withStyle(ChatFormatting.GOLD));
				}
			} else {
				BattleDirector.enterPhase(server, data.phase.next());
			}
		}

		int day = currentDay();
		if (day != lastAnnouncedDay) {
			if (day > 0 && lastAnnouncedDay > 0) {
				Alerts.broadcastTitle(server, "Day " + day, "of the build phase", ChatFormatting.GOLD);
			}
			lastAnnouncedDay = day;
		}

		BattleDirector.tickSecond(server);
		Backups.tick(server);
		syncHud(server);
	}

	public static void syncHud(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		long remaining = remainingMillis();
		int seconds = remaining < 0 ? -1 : (int) (remaining / 1000);
		int alive = BattleDirector.aliveCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SeasonData.Claim claim = data.claimOf(player.getUUID());
			int hp = claim == null || claim.destroyed ? -1 : claim.hp;
			int max = claim == null ? 0 : claim.maxHp();
			if (!ServerPlayNetworking.canSend(player, Payloads.HudSync.TYPE)) {
				continue;
			}
			ServerPlayNetworking.send(player, new Payloads.HudSync(data.phase.ordinal(), seconds, data.pausedRemaining >= 0, currentDay(), hp, max,
					alive, Rules.isEliminated(player.getUUID())));
		}
	}

	public static void setRemaining(long millis) {
		SeasonData data = SeasonData.get();
		if (data.pausedRemaining >= 0) {
			data.pausedRemaining = millis;
		} else {
			data.phaseEndsAt = now() + millis;
		}
		SeasonData.markDirty();
		RubyMod.LOGGER.info("Phase timer set to {} seconds", millis / 1000);
	}

	public static void pause() {
		SeasonData data = SeasonData.get();
		if (data.pausedRemaining < 0 && data.phaseEndsAt != 0) {
			data.pausedRemaining = Math.max(0, data.phaseEndsAt - now());
			SeasonData.markDirty();
		}
	}

	public static void resume() {
		SeasonData data = SeasonData.get();
		if (data.pausedRemaining >= 0) {
			data.phaseEndsAt = now() + data.pausedRemaining;
			data.pausedRemaining = -1;
			SeasonData.markDirty();
		}
	}

	static void resetNotices() {
		waitingNotified = false;
		lastAnnouncedDay = -1;
	}
}
