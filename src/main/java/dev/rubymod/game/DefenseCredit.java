package dev.rubymod.game;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/**
 * "How far did they get?": credit for defending, on the wrap-up's "Your base" page. In Open War, each enemy who comes
 * into your base is an attack; how far they got (inside, at the Heart, hurting it) and what stopped them (your
 * defenses and traps, your guards, your side, or they turned back) is counted for you. An attack that breaks your Heart
 * isn't one you stopped. Just passing through a corner of your land doesn't count.
 */
public final class DefenseCredit {
	private DefenseCredit() {
	}

	private static final int INSIDE = 1;
	private static final int AT_HEART = 2;
	private static final int HURT_IT = 3;
	/** Near the Heart. */
	private static final double HEART_NEAR = 8.0;
	/** Out of the base this long (seconds), and they've turned back. */
	private static final int GONE_FOR = 10;
	/** In the base at least this long (seconds) to count as an attack, unless they got to the Heart. */
	private static final int COUNTS_AFTER = 10;

	private static final class Visit {
		final String claim;
		int stage = INSIDE;
		int seconds;
		int away;

		Visit(String claim) {
			this.claim = claim;
		}
	}

	private static final Map<UUID, Visit> VISITS = new HashMap<>();

	/** Every second: who's inside whose base. */
	public static void tickSecond(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (data.phase != Phase.WAR) {
			VISITS.clear();
			return;
		}
		java.util.Set<UUID> inside = new java.util.HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator() || Rules.isEliminated(p.getUUID()) || !Hub.inRubyWarsWorld(p)) {
				continue;
			}
			SeasonData.Claim claim = Rules.baseAt(p.level(), p.blockPosition());
			if (claim == null || claim.destroyed || Rules.sittingOut(claim) || !Rules.isEnemyOf(claim, p)) {
				continue;
			}
			Visit v = VISITS.get(p.getUUID());
			if (v != null && !v.claim.equals(claim.owner)) {
				end(server, v, "turned back");
				v = null;
			}
			if (v == null) {
				v = new Visit(claim.owner);
				VISITS.put(p.getUUID(), v);
			}
			v.seconds++;
			v.away = 0;
			inside.add(p.getUUID());
			if (p.position().distanceTo(Vec3.atCenterOf(claim.pos())) <= HEART_NEAR) {
				v.stage = Math.max(v.stage, AT_HEART);
			}
		}
		Iterator<Map.Entry<UUID, Visit>> it = VISITS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Visit> e = it.next();
			if (!inside.contains(e.getKey()) && ++e.getValue().away >= GONE_FOR) {
				it.remove();
				end(server, e.getValue(), "turned back");
			}
		}
	}

	/** An enemy hurt this Heart (from in the base or out of it): their attack got that far. */
	public static void heartHurt(SeasonData.Claim claim, ServerPlayer attacker) {
		if (attacker == null || SeasonData.get().phase != Phase.WAR) {
			return;
		}
		Visit v = VISITS.get(attacker.getUUID());
		if (v == null || !v.claim.equals(claim.owner)) {
			v = new Visit(claim.owner);
			VISITS.put(attacker.getUUID(), v);
		}
		v.stage = HURT_IT;
	}

	/** This Heart fell: whoever was attacking it wasn't stopped. */
	public static void heartBroken(SeasonData.Claim claim) {
		VISITS.values().removeIf(v -> v.claim.equals(claim.owner));
	}

	/** An attacker was knocked down: stopped, by whatever got them. */
	public static void died(MinecraftServer server, ServerPlayer victim, DamageSource source) {
		Visit v = VISITS.remove(victim.getUUID());
		if (v == null) {
			return;
		}
		SeasonData.Claim claim = SeasonData.get().claimOf(UUID.fromString(v.claim));
		String by = "defenses";
		if (source.getEntity() instanceof dev.rubymod.entity.SoldierEntity) {
			by = "guards";
		} else if (source.getEntity() instanceof ServerPlayer killer && claim != null && claim.canUse(killer.getUUID())) {
			by = "your side";
		} else if (source.getEntity() instanceof ServerPlayer) {
			by = "someone else";
		}
		v.seconds = Math.max(v.seconds, COUNTS_AFTER); // knocked down on the way in: that counts
		end(server, v, by);
	}

	private static void end(MinecraftServer server, Visit v, String by) {
		if (v.seconds < COUNTS_AFTER && v.stage < AT_HEART) {
			return; // just passing through
		}
		SeasonData.PlayerRecord r = SeasonData.get().players.get(v.claim);
		if (r == null) {
			return;
		}
		r.attacksStopped++;
		if (v.stage >= AT_HEART) {
			r.stoppedAtHeart++;
		}
		if (r.stoppedBy == null) {
			r.stoppedBy = new HashMap<>();
		}
		r.stoppedBy.merge(by, 1, Integer::sum);
		SeasonData.markDirty();
	}

	/** "3 (2 before the Heart, 1 at the Heart)". */
	public static String stopped(SeasonData.PlayerRecord r) {
		if (r.attacksStopped == 0) {
			return "0";
		}
		int before = r.attacksStopped - r.stoppedAtHeart;
		StringBuilder b = new StringBuilder().append(r.attacksStopped).append(" (");
		if (before > 0) {
			b.append(before).append(" before the Heart");
		}
		if (r.stoppedAtHeart > 0) {
			b.append(before > 0 ? ", " : "").append(r.stoppedAtHeart).append(" at the Heart");
		}
		return b.append(")").toString();
	}

	/** "defenses 2, guards 1, turned back 1" (most first), or "" if nothing was stopped. */
	public static String stoppedBy(SeasonData.PlayerRecord r) {
		if (r.stoppedBy == null || r.stoppedBy.isEmpty()) {
			return "";
		}
		return r.stoppedBy.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
				.map(e -> e.getKey() + " " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("");
	}

	public static void forget() {
		VISITS.clear();
	}
}
