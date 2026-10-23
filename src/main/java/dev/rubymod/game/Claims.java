package dev.rubymod.game;

import java.util.UUID;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Placing, moving, upgrading, damaging, and destroying Ruby Hearts. */
public final class Claims {
	private Claims() {
	}

	public static ItemStack heartItem() {
		return new ItemStack(ModBlocks.RUBY_HEART);
	}

	/** Returns why the player can't place a Heart here, or null if they can. */
	public static String placementProblem(ServerLevel level, ServerPlayer player, BlockPos pos) {
		SeasonData data = SeasonData.get();
		GameConfig config = GameConfig.get();
		if (Rules.isEliminated(player.getUUID())) {
			return "You've been eliminated this season.";
		}
		if (data.phase.isBattle()) {
			return "Hearts can't be placed or moved on Battle Day.";
		}
		if (level.dimension() != Level.OVERWORLD) {
			return "Ruby Hearts only work in the Overworld.";
		}
		SeasonData.Claim existing = data.claimOf(player.getUUID());
		if (existing != null && !existing.destroyed) {
			return "You already have a Ruby Heart at " + existing.x + ", " + existing.y + ", " + existing.z + ".";
		}
		BlockPos spawn = level.getRespawnData().pos();
		if (Math.max(Math.abs(pos.getX() - spawn.getX()), Math.abs(pos.getZ() - spawn.getZ())) < config.minDistanceFromSpawn) {
			return "Too close to spawn. Hearts must be at least " + config.minDistanceFromSpawn + " blocks away.";
		}
		int myRadius = config.heartRadius(1);
		for (SeasonData.Claim other : data.claims.values()) {
			if (other.destroyed || other.owner.equals(player.getUUID().toString()) || !other.dimension.equals(Rules.dim(level))) {
				continue;
			}
			int distance = Math.max(Math.abs(pos.getX() - other.x), Math.abs(pos.getZ() - other.z));
			int needed = Math.max(config.minDistanceBetweenHearts, other.radius() + myRadius + 1);
			if (distance < needed) {
				return "Too close to " + other.ownerName + "'s base. Move " + (needed - distance) + " more blocks away.";
			}
		}
		if (!level.getWorldBorder().isWithinBounds(pos)) {
			return "That's outside the world border.";
		}
		return null;
	}

	public static void onHeartPlaced(ServerLevel level, ServerPlayer player, BlockPos pos) {
		Goals.add(level.getServer(), player.getUUID(), "heart", 1);
		SeasonData data = SeasonData.get();
		SeasonData.Claim claim = new SeasonData.Claim();
		claim.owner = player.getUUID().toString();
		claim.ownerName = player.getName().getString();
		claim.dimension = Rules.dim(level);
		claim.x = pos.getX();
		claim.y = pos.getY();
		claim.z = pos.getZ();
		SeasonData.Claim old = data.claimOf(player.getUUID());
		if (old != null) {
			claim.tier = old.tier;
			claim.trusted = old.trusted;
		}
		claim.hp = claim.maxHp();
		data.claims.put(claim.owner, claim);
		SeasonData.markDirty();
		level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.2F);
		player.sendSystemMessage(Component.literal("Ruby Heart placed! You've claimed " + (claim.radius() * 2 + 1) + "x" + (claim.radius() * 2 + 1)
				+ " blocks around it. Only you (and players you /rubywars trust) can build here.").withStyle(ChatFormatting.GREEN));
	}

	/** The owner picked their Heart back up (build phase only). */
	public static void onHeartRemoved(ServerPlayer owner, SeasonData.Claim claim) {
		SeasonData data = SeasonData.get();
		data.claims.remove(claim.owner);
		data.player(owner.getUUID(), owner.getName().getString()).lastHeartMoveDay = SeasonTicker.currentDay();
		SeasonData.markDirty();
		// Keep tier and trust list for when it's placed again.
		SeasonData.Claim keep = new SeasonData.Claim();
		keep.owner = claim.owner;
		keep.ownerName = claim.ownerName;
		keep.dimension = claim.dimension;
		keep.tier = claim.tier;
		keep.trusted = claim.trusted;
		keep.destroyed = true;
		data.claims.put(claim.owner, keep);
		owner.sendSystemMessage(Component.literal("You picked up your Ruby Heart. Your land is unclaimed until you place it again.")
				.withStyle(ChatFormatting.YELLOW));
	}

	public static void damageHeart(ServerLevel level, SeasonData.Claim claim, int damage, ServerPlayer attacker) {
		if (claim.destroyed || !Rules.phase().raidWeaponsActive()) {
			return;
		}
		claim.hp = Math.max(0, claim.hp - damage);
		SeasonData.markDirty();
		// The owner and their squad (who respawn at this Heart too) all get the alert.
		java.util.List<UUID> side = new java.util.ArrayList<>(Squads.mates(UUID.fromString(claim.owner)));
		side.add(UUID.fromString(claim.owner));
		for (UUID id : side) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				String whose = id.toString().equals(claim.owner) ? "HEART UNDER ATTACK" : claim.ownerName.toUpperCase() + "'S HEART UNDER ATTACK";
				Alerts.title(p, whose, "HP " + claim.hp + " / " + claim.maxHp(), ChatFormatting.RED);
				Alerts.ping(p, 0.5F);
			}
		}
		if (claim.hp <= 0) {
			destroyHeart(level, claim, attacker);
		}
	}

	public static void destroyHeart(ServerLevel level, SeasonData.Claim claim, ServerPlayer attacker) {
		if (claim.destroyed) {
			return;
		}
		claim.destroyed = true;
		claim.hp = 0;
		SeasonData.markDirty();
		BlockPos pos = claim.pos();
		if (level.getBlockState(pos).is(ModBlocks.RUBY_HEART)) {
			level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		}
		Explosions.explode(level, null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2.0F, false);
		level.playSound(null, pos, SoundEvents.WITHER_DEATH, SoundSource.BLOCKS, 3.0F, 1.2F);
		String by = attacker != null ? " by " + attacker.getName().getString() : "";
		Alerts.broadcast(level.getServer(), Component.literal(claim.ownerName + "'s Ruby Heart was destroyed" + by + "!")
				.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		if (attacker != null) {
			SeasonData.get().player(attacker.getUUID(), attacker.getName().getString()).heartsDestroyed++;
			// Raiding reward.
			ItemEntity loot = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(ModItems.RUBY, 10));
			level.addFreshEntity(loot);
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(UUID.fromString(claim.owner));
		if (owner != null) {
			Alerts.title(owner, "HEART DESTROYED", "Your next death eliminates you", ChatFormatting.DARK_RED);
		}
	}

	/** Spend rubies to raise the Heart's tier. Returns a message for the player. */
	public static String upgrade(ServerPlayer player) {
		SeasonData.Claim claim = SeasonData.get().claimOf(player.getUUID());
		if (claim == null || claim.destroyed) {
			return "Place your Ruby Heart first.";
		}
		int[] costs = GameConfig.get().heartUpgradeCost;
		if (claim.tier > costs.length) {
			return "Your Heart is already max tier.";
		}
		if (Rules.phase().isBattle()) {
			return "Hearts can't be upgraded on Battle Day.";
		}
		int cost = costs[claim.tier - 1];
		if (!Shop.takeRubies(player, cost)) {
			return "You need " + cost + " rubies.";
		}
		claim.tier++;
		claim.hp = claim.maxHp();
		SeasonData.markDirty();
		Goals.add(player.level().getServer(), player.getUUID(), "heart2", 1);
		return "Heart upgraded to tier " + claim.tier + "! Claim radius is now " + claim.radius() + ", HP " + claim.maxHp() + ".";
	}
}
