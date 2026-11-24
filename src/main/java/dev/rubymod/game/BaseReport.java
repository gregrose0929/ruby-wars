package dev.rubymod.game;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * "How your base held up" on Battle Day, for the season's wrap-up: how long your Heart stood, how many hits your Sentry
 * Turrets landed, and how many of your base's blocks were blown up. Counted as it happens, only on Battle Day.
 */
public final class BaseReport {
	private BaseReport() {
	}

	private static SeasonData.PlayerRecord ownerOf(SeasonData.Claim claim) {
		return claim == null ? null : SeasonData.get().players.get(claim.owner);
	}

	/** One of this base's turrets hit someone. */
	public static void defenseHit(SeasonData.Claim claim) {
		SeasonData.PlayerRecord r = ownerOf(claim);
		if (r != null && SeasonData.get().phase.isBattle()) {
			r.defenseHits++;
			SeasonData.markDirty();
		}
	}

	/** An explosion is about to break these blocks: count the ones inside a base. */
	public static void blocksBroken(ServerLevel level, List<BlockPos> positions) {
		SeasonData data = SeasonData.get();
		if (!data.phase.isBattle() || positions.isEmpty()) {
			return;
		}
		String dim = Rules.dim(level);
		for (BlockPos pos : positions) {
			if (level.getBlockState(pos).isAir()) {
				continue;
			}
			SeasonData.PlayerRecord r = ownerOf(data.claimAt(dim, pos));
			if (r != null) {
				r.blocksLost++;
				SeasonData.markDirty();
			}
		}
	}

	/** This base's Heart has gone (on Battle Day): broken by someone, or taken by Sudden Death after it held all of Open War. */
	public static void heartFell(SeasonData.Claim claim, net.minecraft.server.level.ServerPlayer attacker) {
		SeasonData.PlayerRecord r = ownerOf(claim);
		SeasonData data = SeasonData.get();
		if (r == null || !data.phase.isBattle() || r.heartFellAt != 0 || r.heartHeld) {
			return;
		}
		if (attacker == null && data.phase != Phase.WAR && data.phase != Phase.LOCKDOWN) {
			r.heartHeld = true;
		} else {
			r.heartFellAt = SeasonTicker.now();
			r.heartBrokenBy = attacker == null ? "" : attacker.getName().getString();
		}
		SeasonData.markDirty();
	}

	/** "1 hour 10 minutes", "12 minutes", "under a minute". */
	public static String length(long ms) {
		long minutes = ms / 60_000L;
		if (minutes < 1) {
			return "under a minute";
		}
		long h = minutes / 60;
		long m = minutes % 60;
		String hours = h == 0 ? "" : h + (h == 1 ? " hour" : " hours");
		String mins = m == 0 ? "" : m + (m == 1 ? " minute" : " minutes");
		return hours.isEmpty() ? mins : mins.isEmpty() ? hours : hours + " " + mins;
	}

	/**
	 * What happened to this player's Heart on Battle Day: "Never broken: held to Sudden Death", "Never broken", or
	 * "Broken by Sam, 23 minutes in". Empty if Battle Day never came.
	 */
	public static String heartStood(UUID id) {
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord r = data.players.get(id.toString());
		SeasonData.Claim claim = data.claimOf(id);
		if (r == null || claim == null || data.battleStartedAt == 0) {
			return "";
		}
		if (r.heartHeld) {
			return "Never broken: held to Sudden Death";
		}
		if (r.heartFellAt == 0) {
			return "Never broken";
		}
		String when = length(Math.max(0, r.heartFellAt - data.battleStartedAt)) + " in";
		return r.heartBrokenBy == null || r.heartBrokenBy.isEmpty() ? "Broken, " + when : "Broken by " + r.heartBrokenBy + ", " + when;
	}

	/** Everything this player's machines made this season (see Production). */
	public static long made(SeasonData.PlayerRecord r) {
		long n = 0;
		for (long v : r.made.values()) {
			n += v;
		}
		return n;
	}
}
