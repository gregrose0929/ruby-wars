package dev.rubymod.game;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import dev.rubymod.RubyMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Lifetime stats for every player, across every mode (they last across seasons):
 * party game wins and kills, Horde records, Sky Race times, duels and seasons won.
 * They feed the profile screen (I), the hub leaderboards and cosmetic unlocks. Also
 * what each player has unlocked and is wearing.
 * Saved to world/rubywars/stats.json.
 */
public final class Stats {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Record {
		public String name = "";
		public Map<String, Integer> wins = new HashMap<>();
		public Map<String, Integer> played = new HashMap<>();
		public int partyKills;
		public int hordeGames;
		public int hordeBestWave;
		public int hordeKills;
		public int hordeRevives;
		/** Best Sky Race time in ticks (0: never finished). */
		public int skyBest;
		public int duelsWon;
		public int riftRuns;
		public int riftBestFloor;
		public int riftWins;
		/** Ruby Convoy: jobs run, rubies delivered, the furthest stage reached (1-5), kills. */
		public int convoyRuns;
		public int convoyWins;
		public int convoyBest;
		public int convoyKills;
		public int riftShards;
		public Set<String> riftUnlocks = new HashSet<>();
		/** The Ruby Rift Codex: everything found ("relic:id", "duo:id", "legend:name", "boss:ID", "charm:ID"). */
		public Set<String> riftCodex = new HashSet<>();
		public String riftLoadout = "soldier";
		/** The Rift ability picked at the Altar of Echoes (F). */
		public String riftAbility = "dash";
		public int riftHeatUnlocked;
		/** The Rift companion picked at the Altar ("none", "hound", "fox", "pebble", "buzz"). */
		public String riftCompanion = "none";
		/** The deepest the Endless Descent has gone (0: never). */
		public int riftDeepest;
		/** Codex milestones already paid out (one per 10 entries found). */
		public int riftCodexClaimed;
		public int seasonsWon;
		public Set<String> unlocked = new HashSet<>();
		public String hat = "";
		public String trail = "";
		public String killEffect = "";
		/** The armor set worn (a look for whatever armor you have on; "" for none), and your convoy truck's paint. */
		public String armor = "";
		public String paint = "";
		/** Experience from every game mode (see level()), and whether what was played before XP existed has been counted. */
		public int xp;
		public boolean xpCounted;
		public int convoyHardWins;
		/** Fastest delivery, in seconds (0: none yet). */
		public int convoyBestTime;

		public int totalWins() {
			return wins.values().stream().mapToInt(Integer::intValue).sum();
		}

		public int totalPlayed() {
			return played.values().stream().mapToInt(Integer::intValue).sum();
		}

		public int wins(String mode) {
			return wins.getOrDefault(mode, 0);
		}
	}

	private static Map<String, Record> players = new HashMap<>();
	private static Path file;
	private static boolean dirty;
	private static MinecraftServer server;

	private Stats() {
	}

	public static void load(MinecraftServer s) {
		server = s;
		file = s.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("stats.json");
		players = new HashMap<>();
		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					Map<String, Record> loaded = GSON.fromJson(reader, new TypeToken<Map<String, Record>>() { }.getType());
					if (loaded != null) {
						players = loaded;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			RubyMod.LOGGER.error("Couldn't read player stats", e);
		}
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
			Path tmp = file.resolveSibling("stats.json.tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(players, writer);
			}
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
		} catch (IOException e) {
			RubyMod.LOGGER.error("Couldn't save player stats", e);
		}
	}

	public static Record get(UUID id, String name) {
		Record r = players.computeIfAbsent(id.toString(), k -> new Record());
		if (name != null && !name.isEmpty()) {
			r.name = name;
		}
		if (r.unlocked == null) {
			r.unlocked = new HashSet<>();
		}
		if (r.wins == null) {
			r.wins = new HashMap<>();
		}
		if (r.played == null) {
			r.played = new HashMap<>();
		}
		if (r.armor == null) {
			r.armor = "";
		}
		if (r.paint == null) {
			r.paint = "";
		}
		if (!r.xpCounted) {
			// Everything played before levels existed counts toward them.
			r.xpCounted = true;
			r.xp += r.totalPlayed() * 15 + r.totalWins() * 35 + r.partyKills * 2 + r.hordeGames * 40 + r.riftRuns * 60 + r.riftWins * 120
					+ r.convoyRuns * 80 + r.convoyWins * 150 + r.duelsWon * 30 + r.seasonsWon * 500;
			dirty = true;
		}
		return r;
	}

	public static Record get(ServerPlayer player) {
		return get(player.getUUID(), player.getName().getString());
	}

	private static void changed(UUID id) {
		dirty = true;
		if (server != null) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				Cosmetics.checkUnlocks(p);
			}
		}
	}

	// ----- Levels -----

	/** XP to get from this level to the next. */
	public static int xpFor(int level) {
		return 100 + 40 * (level - 1);
	}

	/** {level, XP into it, XP it takes}. */
	public static int[] level(int xp) {
		int level = 1;
		while (xp >= xpFor(level)) {
			xp -= xpFor(level);
			level++;
		}
		return new int[] {level, xp, xpFor(level)};
	}

	/** XP for something done in any game mode; says so when it's a new level. */
	public static void addXp(UUID id, String name, int amount) {
		if (amount <= 0) {
			return;
		}
		Record r = get(id, name);
		int before = level(r.xp)[0];
		r.xp += amount;
		int after = level(r.xp)[0];
		dirty = true;
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
		if (p != null) {
			if (after > before) {
				Alerts.title(p, "LEVEL " + after + "!", "Press I for your profile", net.minecraft.ChatFormatting.AQUA);
				Alerts.jingle(p, dev.rubymod.registry.ModSounds.LEVEL_UP, 0.9F);
			}
		}
	}

	// ----- What happened -----

	public static void played(UUID id, String name, String mode) {
		get(id, name).played.merge(mode, 1, Integer::sum);
		addXp(id, name, 15);
		changed(id);
	}

	public static void won(UUID id, String name, String mode) {
		get(id, name).wins.merge(mode, 1, Integer::sum);
		addXp(id, name, 35);
		changed(id);
	}

	public static void partyKills(UUID id, String name, int kills) {
		if (kills > 0) {
			get(id, name).partyKills += kills;
			addXp(id, name, kills * 2);
			changed(id);
		}
	}

	public static void horde(UUID id, String name, int wavesSurvived, int kills, int revives) {
		Record r = get(id, name);
		r.hordeGames++;
		r.hordeBestWave = Math.max(r.hordeBestWave, wavesSurvived);
		r.hordeKills += kills;
		r.hordeRevives += revives;
		addXp(id, name, 10 + wavesSurvived * 8);
		changed(id);
	}

	public static void rift(UUID id, String name, int floor, boolean won) {
		Record r = get(id, name);
		r.riftRuns++;
		r.riftBestFloor = Math.max(r.riftBestFloor, floor);
		if (won) {
			r.riftWins++;
		}
		addXp(id, name, floor * 10 + (won ? 120 : 0));
		changed(id);
	}

	/** A convoy job: how far it got, whether the rubies arrived, on what difficulty (0 easy, 1 normal, 2 hard) and in how long. */
	public static void convoy(UUID id, String name, int stage, boolean delivered, int kills, int difficulty, int seconds) {
		Record r = get(id, name);
		r.convoyRuns++;
		r.convoyBest = Math.max(r.convoyBest, stage);
		r.convoyKills += kills;
		if (delivered) {
			r.convoyWins++;
			if (difficulty >= 2) {
				r.convoyHardWins++;
			}
			if (r.convoyBestTime == 0 || seconds < r.convoyBestTime) {
				r.convoyBestTime = seconds;
			}
		}
		addXp(id, name, Math.round((stage * 40 + (delivered ? 150 : 0)) * (difficulty == 0 ? 0.7F : difficulty == 2 ? 1.5F : 1.0F)));
		changed(id);
	}

	public static void skyRace(UUID id, String name, int ticks) {
		Record r = get(id, name);
		if (r.skyBest == 0 || ticks < r.skyBest) {
			r.skyBest = ticks;
		}
		changed(id);
	}

	public static void duelWon(UUID id, String name) {
		get(id, name).duelsWon++;
		addXp(id, name, 30);
		changed(id);
	}

	public static void seasonWon(UUID id, String name) {
		get(id, name).seasonsWon++;
		addXp(id, name, 500);
		changed(id);
	}

	public static void markDirty() {
		dirty = true;
	}

	// ----- For the leaderboards -----

	public static List<Record> top(Comparator<Record> by, int count, java.util.function.Predicate<Record> include) {
		List<Record> list = new ArrayList<>();
		for (Record r : players.values()) {
			if (include.test(r)) {
				list.add(r);
			}
		}
		list.sort(by);
		return list.subList(0, Math.min(count, list.size()));
	}

	public static Collection<Record> all() {
		return players.values();
	}

	/** Lines for the profile screen. */
	public static List<String> profileLines(ServerPlayer player) {
		Record r = get(player);
		List<String> lines = new ArrayList<>();
		int[] lv = level(r.xp);
		lines.add("@level|" + lv[0] + "|" + lv[1] + "|" + lv[2]);
		lines.add("Party games won|" + r.totalWins() + " of " + r.totalPlayed());
		for (dev.rubymod.game.party.PartyGame game : dev.rubymod.game.party.PartyGames.ALL) {
			int played = r.played.getOrDefault(game.id, 0);
			if (played > 0) {
				lines.add("  " + game.title + "|" + r.wins(game.id) + " wins, " + played + " played");
			}
		}
		lines.add("Party game kills|" + r.partyKills);
		lines.add("Horde best|" + (r.hordeBestWave > 0 ? "wave " + r.hordeBestWave : "-") + " (" + r.hordeGames + " games, " + r.hordeKills + " kills, "
				+ r.hordeRevives + " revives)");
		lines.add("Ruby Rift|" + (r.riftRuns == 0 ? "-" : r.riftWins + " wins, best floor " + r.riftBestFloor + " (" + r.riftRuns + " runs)"));
		lines.add("Ruby Convoy|" + (r.convoyRuns == 0 ? "-" : r.convoyWins + " delivered" + (r.convoyHardWins > 0 ? " (" + r.convoyHardWins + " on Hard)" : "")
				+ ", best stage " + r.convoyBest + "/5" + (r.convoyBestTime > 0 ? ", fastest " + r.convoyBestTime / 60 + ":" + String.format("%02d", r.convoyBestTime % 60) : "")));
		lines.add("Sky Race best|" + (r.skyBest > 0 ? raceTime(r.skyBest) : "-"));
		lines.add("Duels won|" + r.duelsWon);
		lines.add("Ruby Wars seasons won|" + r.seasonsWon);
		SeasonData.PlayerRecord season = SeasonData.get().player(player.getUUID());
		if (season != null) {
			lines.add("This season|" + season.kills + " kills, " + season.deaths + " deaths");
		}
		return lines;
	}

	public static String raceTime(int ticks) {
		int tenths = ticks / 2;
		return tenths / 600 + ":" + String.format("%02d", (tenths / 10) % 60) + "." + tenths % 10;
	}

	public static void forEachWinner(Collection<UUID> ids, java.util.function.Consumer<UUID> action) {
		Set<UUID> seen = new HashSet<>();
		for (UUID id : ids) {
			if (seen.add(id)) {
				action.accept(id);
			}
		}
	}
}
