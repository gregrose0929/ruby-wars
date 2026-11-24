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

	/** Is this base sitting Battle Day out (its owner wasn't on when it started, or had to go)? It stays protected. */
	public static boolean sittingOut(SeasonData.Claim claim) {
		if (claim == null || !phase().isBattle()) {
			return false;
		}
		SeasonData.PlayerRecord owner = SeasonData.get().players.get(claim.owner);
		return owner != null && owner.satOut;
	}

	/** Is this base protected right now: before Battle Day, or all through it for a base that's sitting it out? */
	public static boolean protectedNow(SeasonData.Claim claim) {
		return claim != null && (phase().claimsProtected() || sittingOut(claim));
	}

	/** Can this player break or place blocks at pos? */
	public static boolean canBuild(Player player, Level level, BlockPos pos) {
		if (isEliminated(player.getUUID())) {
			return false;
		}
		if (isAdminBypass(player)) {
			return true;
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(dim(level), pos);
		if (!protectedNow(claim)) {
			return true;
		}
		return claim.canUse(player.getUUID());
	}

	/** Ruby Wars blocks that are plain building material (anyone allowed to build somewhere may use them there). */
	private static final java.util.Set<String> MATERIALS = java.util.Set.of("ruby_block", "ruby_ore", "deepslate_ruby_ore", "sandbags",
			"concrete_wall", "reinforced_concrete", "reinforced_glass", "camo_net", "rift_stone", "rift_magma", "rift_frost", "rift_verdant");

	/** Ruby Wars equipment: machines, defenses, belts, mines, security and the rest (not building material). */
	public static boolean isEquipment(net.minecraft.world.level.block.Block block) {
		var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
		return id.getNamespace().equals(dev.rubymod.RubyMod.MOD_ID) && !MATERIALS.contains(id.getPath());
	}

	/** Whose base this is (the whole column, top to bottom), or null outside every base. */
	public static SeasonData.Claim baseAt(Level level, BlockPos pos) {
		return SeasonData.get().claimAt(dim(level), pos.getX(), pos.getZ());
	}

	/**
	 * Can this player put {@code block} at pos? As {@link #canBuild}, and equipment only ever goes in your own base:
	 * nobody (trusted, or an attacker on Battle Day) puts their machines, defenses, belts or mines in someone else's.
	 */
	public static boolean canPlace(Player player, Level level, BlockPos pos, net.minecraft.world.level.block.Block block) {
		if (!canBuild(player, level, pos)) {
			return false;
		}
		if (!isEquipment(block) || isAdminBypass(player)) {
			return true;
		}
		SeasonData.Claim claim = baseAt(level, pos);
		return claim == null || claim.owner.equals(player.getUUID().toString());
	}

	/** Can this player open doors, chests, and machines at pos? */
	public static boolean canInteract(Player player, Level level, BlockPos pos, BlockState state) {
		if (isEliminated(player.getUUID())) {
			return false;
		}
		if (isAdminBypass(player)) {
			return true;
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(dim(level), pos);
		if (claim == null || claim.canUse(player.getUUID())) {
			return true;
		}
		// Anyone can swipe at a Keycard Reader (it decides for itself).
		if (state.is(ModBlocks.KEYCARD_READER)) {
			return true;
		}
		// Keycard Doors open for whoever carries that base's Keycard (any day), and nobody else outside it.
		if (state.is(ModBlocks.KEYCARD_DOOR)) {
			return BaseSecurity.hasCard(player, claim);
		}
		// Blast Doors, turrets and Monitors stay locked to outsiders even on Battle Day.
		if (state.is(ModBlocks.BLAST_DOOR) || state.is(ModBlocks.MONITOR) || state.is(ModBlocks.SENTRY_TURRET) || state.is(ModBlocks.MISSILE_SILO)
				|| state.is(ModBlocks.MORTAR) || state.is(ModBlocks.RADAR_STATION) || state.is(ModBlocks.MISSILE_INTERCEPTOR)) {
			return false;
		}
		return !protectedNow(claim);
	}

	/** Can a weapon or explosion break the block at pos right now? */
	public static boolean canWeaponBreak(Level level, BlockPos pos, BlockState state) {
		if (dev.rubymod.game.rift.Rift.protects(level, pos) || dev.rubymod.game.party.FreeRoam.protects(level, pos) || dev.rubymod.game.convoy.Convoy.protects(level, pos)
				|| dev.rubymod.game.party.PartyGames.protects(level, pos) || WorldEvents.protects(level, pos) || SupplyDrops.protects(level, pos)) {
			return false;
		}
		if (state.is(ModBlocks.RUBY_HEART) || state.getBlock().defaultDestroyTime() < 0) {
			return false;
		}
		return !protectedNow(SeasonData.get().claimAt(dim(level), pos));
	}

	/** Is pos inside a claim that's currently protected from fire and explosions? */
	public static boolean isProtectedFromGriefing(Level level, BlockPos pos) {
		return !level.isClientSide() && protectedNow(SeasonData.get().claimAt(dim(level), pos));
	}

	/** Should fluid be stopped from flowing from one block into the next (crossing into a claim)? */
	public static boolean blocksFlow(Level level, BlockPos from, BlockPos to) {
		if (level.isClientSide()) {
			return false;
		}
		SeasonData.Claim target = SeasonData.get().claimAt(dim(level), to);
		return protectedNow(target) && target != SeasonData.get().claimAt(dim(level), from);
	}

	/** Can attacker hurt victim? Only matters between two players. */
	public static boolean canHurt(Entity attacker, Entity victim) {
		if (!(attacker instanceof Player a) || !(victim instanceof Player v) || a == v) {
			return true;
		}
		if (v instanceof net.minecraft.server.level.ServerPlayer sv && SpawnShield.isShielded(sv)) {
			return false;
		}
		Boolean party = dev.rubymod.game.party.PartyGames.canHurt(a, v);
		if (party != null) {
			return party;
		}
		if (isEliminated(a.getUUID()) && Hub.inRubyWarsWorld(a)) {
			return false;
		}
		Boolean rift = dev.rubymod.game.rift.Rift.canHurt(a, v);
		if (rift != null) {
			return rift;
		}
		Boolean convoy = dev.rubymod.game.convoy.Convoy.canHurt(a, v);
		if (convoy != null) {
			return convoy;
		}
		Boolean horde = Horde.canHurt(a, v);
		if (horde != null) {
			return horde;
		}
		Boolean duel = Duels.canHurt(a, v);
		if (duel != null) {
			return duel;
		}
		if (!GameConfig.get().friendlyFire && Squads.same(a.getUUID(), v.getUUID())) {
			return false;
		}
		if (phase().pvp()) {
			return true;
		}
		// Build phase: fight anywhere outside the bases, but nobody can be hurt inside one (or shoot out of one).
		if ((phase() == Phase.BUILD || phase() == Phase.LOBBY) && Hub.inRubyWarsWorld(a) && Hub.inRubyWarsWorld(v)) {
			return !inBase(a) && !inBase(v);
		}
		return false;
	}

	/** Standing on anyone's claimed land. */
	public static boolean inBase(Player player) {
		return SeasonData.get().claimAt(dim(player.level()), player.blockPosition()) != null;
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
	/**
	 * Should a base's defenses (turrets, traps, Tesla Coils) go for this? The same rule for all of them: raiders (and an
	 * enemy's guards) whenever they're attacking, monsters any time, and enemy players once PvP is on.
	 */
	public static boolean defenseTarget(SeasonData.Claim claim, Entity entity) {
		if (claim == null || entity == null || !entity.isAlive()) {
			return false;
		}
		if (entity instanceof dev.rubymod.entity.SoldierEntity soldier) {
			return soldier.isEnemyOfClaim(claim);
		}
		if (entity instanceof net.minecraft.world.entity.monster.Enemy) {
			return true;
		}
		// A base sitting Battle Day out is out of the fight: its defenses leave players alone (it can't be attacked either).
		return phase().pvp() && !sittingOut(claim) && isEnemyOf(claim, entity);
	}

	public static boolean isEnemyOf(SeasonData.Claim claim, Entity entity) {
		if (!(entity instanceof Player p) || p.isSpectator() || p.isCreative()) {
			return false;
		}
		return !claim.canUse(p.getUUID()) && !isEliminated(p.getUUID());
	}
}
