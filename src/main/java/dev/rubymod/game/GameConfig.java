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
