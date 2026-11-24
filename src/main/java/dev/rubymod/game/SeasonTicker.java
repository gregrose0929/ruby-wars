package dev.rubymod.game;

import dev.rubymod.RubyMod;
import dev.rubymod.net.Payloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The season clock. Phase timers use real (wall-clock) time, so a scheduled build phase keeps counting while the server is
 * off. Battle Day is different: it's a family appointment. When its date comes it's "ready" and waits until everyone
 * with a Heart is on (or the admin calls it), and once it's going its clock pauses whenever nobody's on (and over a
 * restart), carrying on where it left off.
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
			case BUILD -> (SeasonData.get().buildMinutes >= 0 ? SeasonData.get().buildMinutes : c.buildLimitMinutes) * MINUTE;
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
		// No limit, or a week or more: real days. A limit shorter than a day (a test season) still counts fourteen "days".
		long dayLength = length == 0 || length >= DAY ? DAY : Math.max(1, length / 14);
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

	/** When the battle an admin called begins (ms; 0: none called). */
	private static long battleAt;

	/** /rubywars battle: Battle Day begins after a countdown everyone sees. Returns an error, or null. */
	public static String callBattle(MinecraftServer server, int seconds) {
		SeasonData data = SeasonData.get();
		if (data.phase == Phase.LOBBY) {
			return "The season hasn't started. Run /rubywars start first.";
		}
		if (data.phase != Phase.BUILD) {
			return "The battle has already begun (" + data.phase.title() + ").";
		}
		battleAt = now() + seconds * 1000L;
		lastBattleCall = -1;
		Alerts.broadcastTitle(server, "BATTLE DAY IS COMING", "It begins in " + seconds + " seconds. Get to your base!", ChatFormatting.RED);
		Alerts.broadcast(server, Component.literal("Battle Day has been called! It begins in " + seconds + " seconds. Finish what you're doing and get to your base.")
				.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		Alerts.news(server, "Battle Day begins in " + seconds + " seconds.");
		return null;
	}

	/** Call the battle off again (before it starts). */
	public static boolean cancelBattle(MinecraftServer server) {
		if (battleAt == 0) {
			return false;
		}
		battleAt = 0;
		Alerts.broadcast(server, Component.literal("Battle Day has been called off. Keep building!").withStyle(ChatFormatting.GOLD));
		Alerts.news(server, "Battle Day has been called off.");
		return true;
	}

	private static long lastBattleCall = -1;

	/**
	 * When Battle Day comes: "until I say" (0), or a number of minutes from now (a week, two weeks). Only in the build phase
	 * (or before the season starts, for when it does). Returns an error, or null.
	 */
	public static String schedule(MinecraftServer server, long minutes) {
		SeasonData data = SeasonData.get();
		if (data.phase != Phase.BUILD && data.phase != Phase.LOBBY) {
			return "The battle has already begun (" + data.phase.title() + ").";
		}
		data.buildMinutes = minutes;
		if (data.phase == Phase.BUILD) {
			data.pausedRemaining = -1;
			data.phaseEndsAt = minutes > 0 ? now() + minutes * MINUTE : 0;
			warned = 0;
			Alerts.broadcast(server, Component.literal(minutes > 0 ? "Battle Day is set: it begins in " + describe(minutes * MINUTE) + "."
					: "No date for Battle Day: keep building, it begins when the admin calls it.").withStyle(ChatFormatting.GOLD));
		}
		SeasonData.markDirty();
		return null;
	}

	/** "7 days", "2 hours", "1 day 3 hours". */
	public static String describe(long millis) {
		long h = (millis + MINUTE * 59) / (60 * MINUTE);
		long d = h / 24;
		h %= 24;
		if (d == 0) {
			return h <= 1 ? "less than an hour" : h + " hours";
		}
		return d + (d == 1 ? " day" : " days") + (h > 0 ? " " + h + (h == 1 ? " hour" : " hours") : "");
	}

	/** For the self-test: is a Battle Day countdown running? */
	public static boolean battleCalled() {
		return battleAt != 0;
	}

	/** Heads-ups before a scheduled Battle Day: 1 = a day before, 2 = an hour before. */
	private static int warned;

	/** Everyone taking part in Battle Day: whoever has a standing Heart. */
	public static java.util.List<java.util.UUID> battlers() {
		java.util.List<java.util.UUID> out = new java.util.ArrayList<>();
		for (SeasonData.Claim claim : SeasonData.get().claims.values()) {
			if (!claim.destroyed) {
				out.add(java.util.UUID.fromString(claim.owner));
			}
		}
		return out;
	}

	/** How many of {@link #battlers()} are on the server. */
	public static int battlersHere(MinecraftServer server) {
		int n = 0;
		for (java.util.UUID id : battlers()) {
			if (server.getPlayerList().getPlayer(id) != null) {
				n++;
			}
		}
		return n;
	}

	/** Anyone still in the battle on the server? (Nobody: Battle Day's clock waits.) */
	private static boolean anyoneFighting(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			SeasonData.PlayerRecord r = SeasonData.get().player(p.getUUID());
			if (r != null && !r.eliminated) {
				return true;
			}
		}
		return false;
	}

	/** "Battle Day is ready" (to one player, or everyone). */
	public static Component readyMessage(MinecraftServer server) {
		int here = battlersHere(server);
		int of = battlers().size();
		return Component.literal("Battle Day is ready! It starts when everyone with a Heart is on (" + here + " of " + of + " here now), "
				+ "or when the admin calls it.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	/** Time the clock didn't tick (the server off, or asleep with nobody on) is added to anything waiting on a paused clock. */
	private static void catchUp(SeasonData data) {
		long t = now();
		long gap = data.lastSecondAt == 0 ? 1000 : t - data.lastSecondAt;
		data.lastSecondAt = t;
		if (gap <= 2500 || data.pausedRemaining < 0) {
			return;
		}
		long extra = gap - 1000;
		if (data.closeMs > 0) {
			data.closeStart += extra;
		}
		if (data.nextSupplyDropAt != 0) {
			data.nextSupplyDropAt += extra;
		}
		if (data.nextMeteorAt != 0) {
			data.nextMeteorAt += extra;
		}
		SupplyDrops.shift(extra);
		WorldEvents.shift(extra);
		SeasonData.markDirty();
		RubyMod.LOGGER.info("Season clock: {} s away while paused, added to what's waiting", extra / 1000);
	}

	/** Battle Day's clock waits while nobody's on, and carries on when someone's back. */
	private static void battlePause(MinecraftServer server, SeasonData data) {
		if (!data.phase.isBattle()) {
			data.autoPaused = false;
			return;
		}
		boolean anyone = anyoneFighting(server);
		if (!anyone && data.pausedRemaining < 0) {
			pause();
			data.autoPaused = true;
			RubyMod.LOGGER.info("Battle Day paused: nobody's on");
		} else if (anyone && data.autoPaused) {
			data.autoPaused = false;
			if (data.pausedRemaining >= 0) {
				resume();
				Alerts.broadcast(server, Component.literal("Battle Day carries on where it left off.").withStyle(ChatFormatting.GOLD));
			}
		}
	}

	/** The server's shutting down: Battle Day's clock waits until it's back (and someone's on). */
	public static void onStopping() {
		SeasonData data = SeasonData.get();
		if (data.phase.isBattle() && data.pausedRemaining < 0) {
			pause();
			data.autoPaused = true;
		}
		data.lastSecondAt = now();
		SeasonData.markDirty();
	}

	/** Called once per second. */
	public static void tickSecond(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		catchUp(data);
		battlePause(server, data);
		// A season begun under the old two-week limit carries on without one.
		if (data.phase == Phase.BUILD && durationOf(Phase.BUILD) == 0 && data.phaseEndsAt != 0 && data.pausedRemaining < 0) {
			data.phaseEndsAt = 0;
			SeasonData.markDirty();
		}
		if (battleAt != 0 && data.phase != Phase.BUILD) {
			battleAt = 0;
		}
		if (battleAt != 0) {
			long left = Math.max(0, (battleAt - now() + 999) / 1000);
			if (left <= 0) {
				battleAt = 0;
				BattleDirector.enterPhase(server, Phase.LOCKDOWN);
			} else if (left != lastBattleCall && (left == 30 || left == 10 || left <= 5)) {
				lastBattleCall = left;
				Alerts.broadcastTitle(server, String.valueOf(left), "Battle Day begins", ChatFormatting.RED);
			}
		}
		if (data.pausedRemaining < 0 && data.phaseEndsAt != 0 && now() >= data.phaseEndsAt) {
			if (data.phase == Phase.BUILD && !GameConfig.get().autoStartBattle) {
				if (!waitingNotified) {
					waitingNotified = true;
					Alerts.broadcast(server, Component.literal("The build phase is over! Battle Day starts when an admin runs /rubywars phase lockdown.")
							.withStyle(ChatFormatting.GOLD));
				}
			} else if (data.phase == Phase.BUILD) {
				// The date's come: Battle Day waits for the family (it never starts with people missing).
				data.battleReady = true;
				data.phaseEndsAt = 0;
				SeasonData.markDirty();
				Alerts.broadcast(server, readyMessage(server));
				Alerts.news(server, "Battle Day is ready: it starts when everyone's on.");
			} else {
				BattleDirector.enterPhase(server, data.phase.next());
			}
		}
		// Ready, and everyone with a Heart is here: a minute's countdown, then Battle Day.
		if (data.phase == Phase.BUILD && data.battleReady && battleAt == 0 && !battlers().isEmpty() && battlersHere(server) == battlers().size()) {
			callBattle(server, 60);
		}

		// A scheduled Battle Day: a heads-up the day before, and an hour before.
		if (data.phase == Phase.BUILD && data.phaseEndsAt != 0 && data.pausedRemaining < 0) {
			long left = data.phaseEndsAt - now();
			if (left < 60 * MINUTE && left > 0 && warned < 2) {
				warned = 2;
				Alerts.broadcastTitle(server, "BATTLE DAY", "begins in less than an hour", ChatFormatting.RED);
			} else if (left < DAY && left >= 60 * MINUTE && warned < 1) {
				warned = 1;
				Alerts.broadcast(server, Component.literal("Battle Day is tomorrow (in " + describe(left) + "). Get your base ready!")
						.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
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
			boolean out = Rules.isEliminated(player.getUUID());
			int respawn = Payloads.HudSync.RESPAWN_NONE;
			if (data.phase.isBattle() && !out) {
				SeasonData.Claim at = BattleDirector.respawnHeart(player.getUUID());
				respawn = !data.phase.heartsGiveRespawns() ? Payloads.HudSync.NO_RESPAWNS
						: at == null ? Payloads.HudSync.LAST_LIFE
						: at == claim ? Payloads.HudSync.RESPAWN_HEART : Payloads.HudSync.RESPAWN_MATE;
			}
			int flags = respawn | (Squads.enabled() ? 8 : 0) | (Hub.where(player) == Hub.Where.RUBY_WARS ? 16 : 0)
					| (data.phase == Phase.BUILD && data.battleReady ? 32 | Math.min(15, battlersHere(server)) << 8 | Math.min(15, battlers().size()) << 12 : 0);
			ServerPlayNetworking.send(player, new Payloads.HudSync(data.phase.ordinal(), seconds, data.pausedRemaining >= 0, currentDay(), hp, max,
					alive, out, flags));
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
		warned = 0;
		lastAnnouncedDay = -1;
	}
}
