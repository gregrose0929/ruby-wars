package dev.rubymod.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import dev.rubymod.RubyMod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A new season: the season's data starts over (claims, banks, players, waypoints), and everyone's Ruby Wars
 * gear is cleared once, the first time they're back (now if they're in Ruby Wars, otherwise when they join or
 * go in). The hub, the party games, the Coaster Park, stats and past champions are kept. The island itself is
 * remade on the server by "rubywars newseason" (which runs this too).
 */
public final class FreshStart {
	private FreshStart() {
	}

	/** Starts the next season in the lobby. Returns its number. */
	public static int resetSeason(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		int nextSeason = data.season + 1;
		var champions = new java.util.ArrayList<>(data.pastChampions);
		data.claims.clear();
		dev.rubymod.industry.Grid.reset();
		data.players.clear();
		data.squads.clear();
		data.waypoints.clear();
		data.phase = Phase.LOBBY;
		data.phaseEndsAt = 0;
		data.pausedRemaining = -1;
		data.buildStartedAt = 0;
		data.buildMinutes = -1;
		data.winner = "";
		data.season = nextSeason;
		data.pastChampions = champions;
		data.freshSeason = nextSeason;
		data.freshStart.clear();
		SeasonData.save();
		GameConfig config = GameConfig.get();
		var border = server.overworld().getWorldBorder();
		border.setCenter(config.borderCenterX, config.borderCenterZ);
		border.setSize(BattleDirector.startBorder(server));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() && Hub.where(player) == Hub.Where.RUBY_WARS) {
				player.setGameMode(GameType.SURVIVAL);
			}
			check(player);
		}
		return nextSeason;
	}

	/** The server found "rubywars newseason"'s note (it was stopped when that ran): reset now. */
	public static void onServerStarted(MinecraftServer server) {
		Path note = server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("reset-season");
		if (Files.exists(note)) {
			int season = resetSeason(server);
			RubyMod.LOGGER.info("New season: reset to the lobby for Season {}", season);
			try {
				Files.deleteIfExists(note);
			} catch (IOException e) {
				RubyMod.LOGGER.warn("Couldn't delete {}", note, e);
			}
		}
	}

	/** Clear this player's Ruby Wars gear if they haven't had this season's fresh start yet. */
	public static void check(ServerPlayer player) {
		SeasonData data = SeasonData.get();
		if (data.freshSeason == 0 || data.freshSeason != data.season || data.freshStart == null) {
			return;
		}
		if (!data.freshStart.add(player.getUUID().toString())) {
			return;
		}
		SeasonData.markDirty();
		MinecraftServer server = player.level().getServer();
		// Saved away while they're elsewhere: gone, so going in starts fresh at the island's spawn.
		Path saved = server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("hub").resolve(player.getUUID() + ".dat");
		try {
			Files.deleteIfExists(saved);
		} catch (IOException e) {
			RubyMod.LOGGER.warn("Couldn't clear {}'s old Ruby Wars things", player.getName().getString(), e);
		}
		if (Hub.where(player) != Hub.Where.RUBY_WARS) {
			return;
		}
		player.stopRiding();
		player.getInventory().clearContent();
		player.getEnderChestInventory().clearContent();
		player.setExperienceLevels(0);
		player.setExperiencePoints(0);
		player.removeAllEffects();
		player.setHealth(player.getMaxHealth());
		if (player.isSpectator()) {
			player.setGameMode(GameType.SURVIVAL);
		}
		ServerLevel overworld = server.overworld();
		BlockPos spawn = overworld.getRespawnData().pos();
		overworld.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4);
		int top = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
		player.teleportTo(overworld, spawn.getX() + 0.5, Math.max(spawn.getY(), top) + 0.1, spawn.getZ() + 0.5, Set.<Relative>of(), 0, 0, true);
		player.inventoryMenu.broadcastChanges();
		player.sendSystemMessage(Component.literal("Season " + data.season + ": a fresh start! Everyone begins again with nothing, on a fresh island.")
				.withStyle(ChatFormatting.GOLD));
	}
}
