package dev.rubymod.game;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.rubymod.RubyMod;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server settings, stored in config/rubywars.json. Edit the file and run
 * /rubywars reload to apply changes without restarting.
 */
public class GameConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("rubywars.json");

	private static GameConfig current = new GameConfig();

	// Season timing (minutes). 20160 minutes = 14 days.
	public int buildPhaseMinutes = 20160;
	/** Hostile mobs (zombies, creepers...) spawn at all. Off: Ruby Wars is about building and each other. */
	public boolean hostileMobs = false;
	/** Hunger drains. Off: the food bar stays full (health still regenerates slowly). */
	public boolean hunger = false;
	public int lockdownMinutes = 15;
	public int warMinutes = 60;
	public int suddenDeathMinutes = 20;
	/** Start Battle Day automatically when the build timer runs out. */
	public boolean autoStartBattle = true;

	// Map
	public int borderCenterX = 0;
	public int borderCenterZ = 0;
	public int borderStartSize = 3000;
	public int borderEndSize = 200;

	// Hearts and claims
	public int[] heartRadiusByTier = {24, 40, 56};
	public int[] heartHpByTier = {100, 150, 200};
	public int[] heartUpgradeCost = {20, 60};
	public int minDistanceBetweenHearts = 64;
	public int minDistanceFromSpawn = 64;

	// Battle Day
	public int supplyDropIntervalMinutes = 15;
	public int maxMissilesPerPlayer = 3;
	public int killstreakForAirStrike = 5;
	public int breachingChargeDamage = 50;

	// Server
	public int backupIntervalHours = 24;
	public int backupsToKeep = 7;

	/** War Graves: your gear waits where you died; only you can open it for this long, then anyone can. */
	public boolean graves = true;
	public int graveLockSeconds = 300;
	/** An unclaimed grave breaks open after this many minutes, spilling what's left. */
	public int graveDecayMinutes = 60;
	/** Research: advanced Armory gear starts locked (see Research). Squadmates share what they research. */
	public boolean research = true;
	public boolean squadsShareResearch = true;
	/** Practice raids in the build phase: on these days, raiders by Heart tier, minutes to repel them, loot cap, rewards. */
	public int[] raidDays = {3, 6, 9, 12};
	public int[] raidSize = {4, 6, 8};
	public int raidMinutes = 6;
	public int raidMaxLootPercent = 20;
	public int raidRewardPerKill = 3;
	public int raidVictoryBonus = 15;
	/** Hired guards: damage per rifle round, and how many each player may field. */
	public float guardDamage = 3.0F;
	/** Raiders hit a little softer than guards, so a defended base wins a fair fight. */
	public float raiderDamage = 2.0F;
	public float guardHealth = 40.0F;
	public int maxGuards = 4;
	/** Squads: players per squad, and whether squadmates can hurt each other. */
	public int squadMaxSize = 4;
	public boolean friendlyFire = false;
	/** Seconds a PvP hit keeps you "in combat"; logging out then kills you. */
	public int combatTagSeconds = 15;

	/** Economy: Trade Depot prices are multiplied by this (1.0 = the full price list). */
	public double tradeDepotPriceMultiplier = 0.35;
	/** Ruby Drills a player may own: without a Heart, then per Heart tier 1/2/3. */
	public int drillsWithoutHeart = 1;
	public int[] drillsPerHeartTier = {2, 4, 6};

	/** Armory price overrides: item id (like "rubymod:sniper_rifle") to price in rubies. */
	public Map<String, Integer> prices = new LinkedHashMap<>();

	public static GameConfig get() {
		return current;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader reader = Files.newBufferedReader(PATH)) {
					GameConfig loaded = GSON.fromJson(reader, GameConfig.class);
					if (loaded != null) {
						current = loaded;
					}
				}
			}
			save();
		} catch (Exception e) {
			RubyMod.LOGGER.error("Couldn't read {}, using defaults", PATH, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(current, writer);
			}
		} catch (IOException e) {
			RubyMod.LOGGER.error("Couldn't write {}", PATH, e);
		}
	}

	/** How many Ruby Drills this player may own right now. */
	public int drillLimit(java.util.UUID player) {
		SeasonData.Claim claim = SeasonData.get().claimOf(player);
		if (claim == null) {
			return drillsWithoutHeart;
		}
		return drillsPerHeartTier[Math.max(0, Math.min(drillsPerHeartTier.length, claim.tier) - 1)];
	}

	public int heartRadius(int tier) {
		return heartRadiusByTier[Math.min(tier, heartRadiusByTier.length) - 1];
	}

	public int heartHp(int tier) {
		return heartHpByTier[Math.min(tier, heartHpByTier.length) - 1];
	}

	public int price(String itemId, int defaultPrice) {
		return prices.getOrDefault(itemId, defaultPrice);
	}
}
