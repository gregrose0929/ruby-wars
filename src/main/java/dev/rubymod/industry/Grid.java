package dev.rubymod.industry;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.rubymod.RubyMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Every player's power grid and ruby bank.
 *
 * Power ("charge") is deliberately simple: all of a player's machines share one
 * pool, wherever they are. Generators add to it, machines draw from it, batteries
 * raise how much it can hold. No cables to lay; the planning is in balancing
 * production against demand.
 *
 * The bank holds rubies earned by the Trade Depot and deposited into a Vault, in
 * thousandths of a ruby so small sales add up. Vaults pay daily interest.
 * Saved to world/rubywars/grids.json.
 */
public final class Grid {
	public static final long BASE_CAPACITY = 10_000;

	/** What gets saved per player. */
	public static final class Account {
		public long charge;
		/** Bank balance in thousandths of a ruby. */
		public long milliRubies;
		public long lastInterestDay = -1;
		public long lifetimeEarned;
		/** Ruby Drills this player owns (limited by their Heart tier). */
		public int drills;
	}

	/** Per-second bookkeeping (not saved). */
	private static final class Rates {
		long produced;
		long consumed;
		long lastProduced;
		long lastConsumed;
		long batteryCapacity;
		long lastBatteryCapacity;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Map<String, Account> accounts = new HashMap<>();
	private static final Map<UUID, Rates> RATES = new HashMap<>();
	private static Path file;
	private static boolean dirty;

	private Grid() {
	}

	public static Account account(UUID owner) {
		return accounts.computeIfAbsent(owner.toString(), k -> new Account());
	}

	private static Rates rates(UUID owner) {
		return RATES.computeIfAbsent(owner, k -> new Rates());
	}

	// ----- Power -----

	public static long capacity(UUID owner) {
		return BASE_CAPACITY + rates(owner).lastBatteryCapacity;
	}

	public static long charge(UUID owner) {
		return account(owner).charge;
	}

	/** A generator adds charge (excess beyond capacity is lost). */
	public static void produce(UUID owner, long amount) {
		Account a = account(owner);
		a.charge = Math.min(capacity(owner), a.charge + amount);
		rates(owner).produced += amount;
		dirty = true;
	}

	/** A machine draws charge; false (and nothing taken) if there isn't enough. */
	public static boolean consume(UUID owner, long amount) {
		Account a = account(owner);
		if (a.charge < amount) {
			return false;
		}
		a.charge -= amount;
		rates(owner).consumed += amount;
		dirty = true;
		return true;
	}

	/** A loaded battery reports its capacity every tick. */
	public static void addBattery(UUID owner, long capacity) {
		rates(owner).batteryCapacity += capacity;
	}

	/** Charge produced and used per second, as of the last full second. */
	public static long producedPerSecond(UUID owner) {
		return rates(owner).lastProduced;
	}

	public static long consumedPerSecond(UUID owner) {
		return rates(owner).lastConsumed;
	}

	// ----- Bank -----

	public static long rubies(UUID owner) {
		return account(owner).milliRubies / 1000;
	}

	public static long milliRubies(UUID owner) {
		return account(owner).milliRubies;
	}

	public static void deposit(UUID owner, long milliRubies) {
		Account a = account(owner);
		a.milliRubies += milliRubies;
		if (milliRubies > 0) {
			a.lifetimeEarned += milliRubies;
		}
		dirty = true;
	}

	/** Take whole rubies from the bank; false if the balance is short. */
	public static boolean withdraw(UUID owner, long rubies) {
		Account a = account(owner);
		if (a.milliRubies < rubies * 1000) {
			return false;
		}
		a.milliRubies -= rubies * 1000;
		dirty = true;
		return true;
	}

	// ----- Ticking and saving -----

	/** Called every server tick, after machines tick: batteries re-report each tick. */
	public static void endTick(long gameTime) {
		if (gameTime % 20 == 0) {
			for (Rates r : RATES.values()) {
				r.lastProduced = r.produced;
				r.lastConsumed = r.consumed;
				r.produced = 0;
				r.consumed = 0;
			}
		}
		for (Rates r : RATES.values()) {
			r.lastBatteryCapacity = r.batteryCapacity;
			r.batteryCapacity = 0;
		}
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("grids.json");
		accounts = new HashMap<>();
		RATES.clear();
		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					Map<String, Account> loaded = GSON.fromJson(reader, new com.google.gson.reflect.TypeToken<Map<String, Account>>() { }.getType());
					if (loaded != null) {
						accounts = loaded;
					}
				}
			}
		} catch (Exception e) {
			RubyMod.LOGGER.error("Couldn't read power grids from {}", file, e);
		}
		dirty = false;
	}

	public static void saveIfDirty() {
		if (!dirty || file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("grids.json.tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(accounts, writer);
			}
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
		} catch (Exception e) {
			RubyMod.LOGGER.error("Couldn't save power grids to {}", file, e);
		}
	}

	/** Players by rubies earned through their economy (Trade Depot sales, deposits, interest), best first. */
	public static java.util.List<Map.Entry<String, Account>> leaderboard() {
		return accounts.entrySet().stream().filter(e -> e.getValue().lifetimeEarned > 0)
				.sorted((a, b) -> Long.compare(b.getValue().lifetimeEarned, a.getValue().lifetimeEarned)).limit(10).toList();
	}

	/** Wipe everything (new season). */
	public static void reset() {
		accounts.clear();
		RATES.clear();
		dirty = true;
	}
}
