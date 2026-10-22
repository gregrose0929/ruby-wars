package dev.rubymod.game;

import java.util.UUID;

import dev.rubymod.registry.ModBlocks;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The one place that answers "is this allowed right now?". */
public final class Rules {
	private Rules() {
	}

	public static Phase phase() {
		return SeasonData.get().phase;
	}

	public static String dim(Level level) {
		return level.dimension().identifier().toString();
	}

	/** Admins in creative mode can build anywhere. */
	public static boolean isAdminBypass(Player player) {
		return player instanceof ServerPlayer sp && sp.isCreative()
				&& Commands.LEVEL_GAMEMASTERS.check(sp.createCommandSourceStack().permissions());
	}

	/** Can this player break or place blocks at pos? */
	public static boolean canBuild(Player player, Level level, BlockPos pos) {
		if (isEliminated(player.getUUID())) {
			return false;
		}
		if (isAdminBypass(player)) {
			return true;
		}
		if (!phase().claimsProtected()) {
			return true;
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(dim(level), pos.getX(), pos.getZ());
		return claim == null || claim.canUse(player.getUUID());
	}

	/** Can this player open doors, chests, and machines at pos? */
	public static boolean canInteract(Player player, Level level, BlockPos pos, BlockState state) {
		if (isEliminated(player.getUUID())) {
			return false;
		}
		if (isAdminBypass(player)) {
			return true;
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(dim(level), pos.getX(), pos.getZ());
		if (claim == null || claim.canUse(player.getUUID())) {
			return true;
		}
		// Blast Doors and turrets stay locked to outsiders even on Battle Day.
		if (state.is(ModBlocks.BLAST_DOOR) || state.is(ModBlocks.SENTRY_TURRET) || state.is(ModBlocks.MISSILE_SILO)
				|| state.is(ModBlocks.MORTAR) || state.is(ModBlocks.RADAR_STATION) || state.is(ModBlocks.MISSILE_INTERCEPTOR)) {
			return false;
		}
		return !phase().claimsProtected();
	}

	/** Can a weapon or explosion break the block at pos right now? */
	public static boolean canWeaponBreak(Level level, BlockPos pos, BlockState state) {
		if (state.is(ModBlocks.RUBY_HEART) || state.getBlock().defaultDestroyTime() < 0) {
			return false;
		}
		if (!phase().claimsProtected()) {
			return true;
		}
		return SeasonData.get().claimAt(dim(level), pos.getX(), pos.getZ()) == null;
	}

	/** Is pos inside a claim that's currently protected from fire and explosions? */
	public static boolean isProtectedFromGriefing(Level level, BlockPos pos) {
		return !level.isClientSide() && phase().claimsProtected() && SeasonData.get().claimAt(dim(level), pos.getX(), pos.getZ()) != null;
	}

	/** Should fluid be stopped from flowing from one block into the next (crossing into a claim)? */
	public static boolean blocksFlow(Level level, BlockPos from, BlockPos to) {
		if (level.isClientSide() || !phase().claimsProtected()) {
			return false;
		}
		SeasonData.Claim target = SeasonData.get().claimAt(dim(level), to.getX(), to.getZ());
		return target != null && target != SeasonData.get().claimAt(dim(level), from.getX(), from.getZ());
	}

	/** Can attacker hurt victim? Only matters between two players. */
	public static boolean canHurt(Entity attacker, Entity victim) {
		if (!(attacker instanceof Player a) || !(victim instanceof Player v) || a == v) {
			return true;
		}
		if (isEliminated(a.getUUID())) {
			return false;
		}
		if (v instanceof net.minecraft.server.level.ServerPlayer sv && SpawnShield.isShielded(sv)) {
			return false;
		}
		Boolean duel = Duels.canHurt(a, v);
		if (duel != null) {
			return duel;
		}
		if (!GameConfig.get().friendlyFire && Squads.same(a.getUUID(), v.getUUID())) {
			return false;
		}
		return phase().pvp();
	}

	public static boolean isEliminated(UUID player) {
		SeasonData.PlayerRecord record = SeasonData.get().player(player);
		return record != null && record.eliminated;
	}

	/** True if the two players are the same, or trust each other's claims. */
	public static boolean isFriendly(UUID a, UUID b) {
		if (a.equals(b) || Squads.same(a, b)) {
			return true;
		}
		SeasonData.Claim claimA = SeasonData.get().claimOf(a);
		SeasonData.Claim claimB = SeasonData.get().claimOf(b);
		return (claimA != null && claimA.trusted.contains(b.toString())) || (claimB != null && claimB.trusted.contains(a.toString()));
	}

	/** Is this entity an enemy of the claim's owner (used by turrets, mines, alarms)? */
	public static boolean isEnemyOf(SeasonData.Claim claim, Entity entity) {
		if (!(entity instanceof Player p) || p.isSpectator() || p.isCreative()) {
			return false;
		}
		return !claim.canUse(p.getUUID()) && !isEliminated(p.getUUID());
	}
}
