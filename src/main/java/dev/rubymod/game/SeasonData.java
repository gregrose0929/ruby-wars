package dev.rubymod.game;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.rubymod.RubyMod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Everything about the current season that must survive a restart:
 * the phase and its timer, every claim, and every player's record.
 * Saved as world/rubywars/season.json.
 */
public class SeasonData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static SeasonData current = new SeasonData();
	private static Path file;
	private static boolean dirty;

	// --- Phase and timers ---
	public Phase phase = Phase.LOBBY;
	public int season = 1;
	/** Wall-clock time (ms) when the current phase ends; 0 = no timer. */
	public long phaseEndsAt;
	/** Time left (ms) when the timer was paused; -1 = not paused. */
	public long pausedRemaining = -1;
	public long buildStartedAt;
	public long nextSupplyDropAt;
	public long lastBackupAt;
	public String winner = "";

	public Map<String, Claim> claims = new LinkedHashMap<>();
	public Map<String, PlayerRecord> players = new LinkedHashMap<>();
	public List<String> pastChampions = new ArrayList<>();
	public Map<String, Squads.Squad> squads = new LinkedHashMap<>();
	public Map<String, List<Waypoints.Waypoint>> waypoints = new LinkedHashMap<>();

	public static class Claim {
		public String owner;
		public String ownerName;
		public String dimension;
		public int x;
		public int y;
		public int z;
		public int tier = 1;
		public int hp;
		public boolean destroyed;
		public int lastMovedDay = -1;
		public Set<String> trusted = new HashSet<>();

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}

		public boolean canUse(UUID player) {
			String id = player.toString();
			return id.equals(owner) || trusted.contains(id) || Squads.same(UUID.fromString(owner), player);
		}

		public int radius() {
			return GameConfig.get().heartRadius(tier);
		}

		public int maxHp() {
			return GameConfig.get().heartHp(tier);
		}

		/** Claims cover a square column (all heights), centered on the Heart. */
		public boolean contains(String dim, int bx, int bz) {
			if (destroyed || !dimension.equals(dim)) {
				return false;
			}
			int r = radius();
			return Math.abs(bx - x) <= r && Math.abs(bz - z) <= r;
		}
	}

	public static class PlayerRecord {
		public String name = "";
		public boolean eliminated;
		public int kills;
		public int deaths;
		public int heartsDestroyed;
		public int killstreak;
		/** The streak this player had when they last died (for "ended X's streak"). */
		public transient int killstreakBefore;
		public int missilesUsed;
		public boolean heartGiven;
		public int bestKillstreak;
		public int lastHeartMoveDay = -1;
		public int bountyDay = -1;
		public List<Bounties.Bounty> bounties = new ArrayList<>();
		public int researchPoints;
		public int researchDay = -1;
		public Set<String> research = new HashSet<>();
	}

	public static SeasonData get() {
		return current;
	}

	public static void markDirty() {
		dirty = true;
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("season.json");
		current = new SeasonData();
		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					SeasonData loaded = GSON.fromJson(reader, SeasonData.class);
					if (loaded != null) {
						current = loaded;
					}
				}
			}
		} catch (Exception e) {
			RubyMod.LOGGER.error("Couldn't read season data from {}", file, e);
		}
		dirty = false;
	}

	public static void saveIfDirty() {
		if (dirty) {
			save();
		}
	}

	public static void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("season.json.tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(current, writer);
			}
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
		} catch (Exception e) {
			RubyMod.LOGGER.error("Couldn't save season data to {}", file, e);
		}
	}

	/** A copy of the whole season, for tests that need to put everything back afterwards. */
	public static String snapshot() {
		return GSON.toJson(current);
	}

	public static void restore(String json) {
		current = GSON.fromJson(json, SeasonData.class);
		markDirty();
	}

	public PlayerRecord player(UUID id, String name) {
		PlayerRecord record = players.computeIfAbsent(id.toString(), k -> new PlayerRecord());
		if (name != null && !name.equals(record.name)) {
			record.name = name;
			markDirty();
		}
		return record;
	}

	public PlayerRecord player(UUID id) {
		return players.get(id.toString());
	}

	public Claim claimOf(UUID owner) {
		return claims.get(owner.toString());
	}

	public Claim claimAt(String dimension, int x, int z) {
		for (Claim claim : claims.values()) {
			if (claim.contains(dimension, x, z)) {
				return claim;
			}
		}
		return null;
	}

	public Claim heartAt(String dimension, BlockPos pos) {
		for (Claim claim : claims.values()) {
			if (!claim.destroyed && claim.dimension.equals(dimension) && claim.x == pos.getX() && claim.y == pos.getY() && claim.z == pos.getZ()) {
				return claim;
			}
		}
		return null;
	}
}
