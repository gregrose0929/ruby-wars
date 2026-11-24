package dev.rubymod.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Anything an attacker has can wear a Ruby Heart down in Open War: hitting it, shooting it, and every blast near it
 * (rockets, grenades, mortars, shells, missiles, TNT). Breaching Charges still hit hardest (see BreachingChargeEntity).
 * Only enemies count: your own (and your team's) shots and blasts never hurt your Heart, and nor do monsters.
 * Small hits add up (a pistol round is a tenth of a point), so a Heart under fire goes down steadily, not all at once.
 */
public final class HeartDamage {
	private HeartDamage() {
	}

	/** Bullet damage to Heart damage: a sniper shot (30) takes 3, a pistol round less than 1. */
	public static final float BULLET = 0.1F;
	/** A blast takes up to this much per block of blast size, less the further its middle is from the Heart. */
	public static final float BLAST = 4.0F;
	/** Hitting it with your hand, or with anything you're holding. */
	public static final int SWING_HAND = 1;
	public static final int SWING_TOOL = 2;
	/** Ticks between swings that count. */
	private static final int SWING_EVERY = 10;

	/** Not-yet-whole damage per Heart (its owner's id), so small hits add up. */
	private static final Map<String, Float> PART = new HashMap<>();
	private static final Map<UUID, Long> NEXT_SWING = new HashMap<>();

	/** Can this player hurt this Heart right now? */
	public static boolean canHurt(SeasonData.Claim claim, ServerPlayer attacker) {
		return claim != null && !claim.destroyed && attacker != null && Rules.phase().raidWeaponsActive() && !Rules.sittingOut(claim)
				&& Rules.isEnemyOf(claim, attacker);
	}

	private static void hurt(ServerLevel level, SeasonData.Claim claim, float amount, ServerPlayer attacker) {
		if (!canHurt(claim, attacker) || amount <= 0) {
			return;
		}
		float total = PART.getOrDefault(claim.owner, 0.0F) + amount;
		int whole = (int) total;
		PART.put(claim.owner, total - whole);
		if (whole > 0) {
			Claims.damageHeart(level, claim, whole, attacker);
		}
	}

	/** A bullet hit the block at pos. Returns true if it was a Heart (so it isn't chipped like a block). */
	public static boolean bullet(ServerLevel level, BlockPos pos, float gunDamage, ServerPlayer shooter) {
		SeasonData.Claim claim = SeasonData.get().heartAt(Rules.dim(level), pos);
		if (claim == null || claim.destroyed) {
			return false;
		}
		hurt(level, claim, gunDamage * BULLET, shooter);
		return true;
	}

	/** A player hit the block at pos (left-click). Returns true if they were hitting an enemy Heart they can hurt. */
	public static boolean swing(ServerPlayer player, Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server)) {
			return false;
		}
		SeasonData.Claim claim = SeasonData.get().heartAt(Rules.dim(level), pos);
		if (!canHurt(claim, player)) {
			return false;
		}
		long now = server.getGameTime();
		if (now < NEXT_SWING.getOrDefault(player.getUUID(), 0L)) {
			return true;
		}
		NEXT_SWING.put(player.getUUID(), now + SWING_EVERY);
		hurt(server, claim, player.getMainHandItem().isEmpty() ? SWING_HAND : SWING_TOOL, player);
		return true;
	}

	/** An explosion is going off: Hearts near it take some, by how close they are. */
	public static void blast(ServerLevel level, Vec3 centre, float radius, Entity direct, LivingEntity indirect) {
		if (!Rules.phase().raidWeaponsActive() || radius <= 0 || direct instanceof dev.rubymod.entity.BreachingChargeEntity) {
			return;
		}
		ServerPlayer attacker = indirect instanceof ServerPlayer p ? p
				: direct instanceof ServerPlayer p ? p
				: direct instanceof Projectile proj && proj.getOwner() instanceof ServerPlayer p ? p : null;
		if (attacker == null) {
			return;
		}
		String dim = Rules.dim(level);
		double reach = radius + 1.5;
		for (SeasonData.Claim claim : SeasonData.get().claims.values()) {
			if (claim.destroyed || !dim.equals(claim.dimension)) {
				continue;
			}
			double d = Vec3.atCenterOf(claim.pos()).distanceTo(centre);
			if (d <= reach) {
				hurt(level, claim, (float) (radius * BLAST * (1.0 - d / reach * 0.75)), attacker);
			}
		}
	}

	public static void forget() {
		PART.clear();
		NEXT_SWING.clear();
	}
}
