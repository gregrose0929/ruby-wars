package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** All the party games, and the hooks the rest of the mod calls into. */
public final class PartyGames {
	public static final List<PartyGame> ALL = List.of(
			new FreeRoam(),
			new GunGame(),
			new HideAndSeek(),
			new Infection(),
			new CaptureTheFlag(),
			new KingOfTheHill(),
			new TankBattle(),
			new AirBattle(),
			new SkyRace(),
			new BuildBattle(),
			new RubyRush());

	/** For autotests: stand-in players (fake players) that aren't on the player list. */
	public static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();

	private PartyGames() {
	}

	public static PartyGame byId(String id) {
		for (PartyGame game : ALL) {
			if (game.id.equals(id)) {
				return game;
			}
		}
		return null;
	}

	public static PartyGame of(Player player) {
		for (PartyGame game : ALL) {
			if (game.contains(player)) {
				return game;
			}
		}
		return null;
	}

	static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null ? p : TEST_PLAYERS.get(id);
	}

	/** Which games have bots switched on: world/rubywars/party_bots.txt. */
	private static java.nio.file.Path botsFile;

	/**
	 * Every map in the arena world has its own stretch of it. Two on the same ground wreck each other (Air Battle once
	 * shared Build Battle's, and jets flew into its invisible walls), so say so loudly at startup if it ever happens again.
	 * Returns what overlaps (empty: all clear).
	 */
	public static List<String> overlaps() {
		record Zone(String name, int x, int reach) {
		}
		List<Zone> zones = new ArrayList<>();
		for (PartyGame game : ALL) {
			if (!(game instanceof RubyRush)) {
				zones.add(new Zone(game.title, game.center().getX(), game.reach()));
			}
		}
		zones.add(new Zone("Co-op Horde", dev.rubymod.game.Horde.CX, 120));
		zones.add(new Zone("Ruby Rift", -90000, 200));
		zones.add(new Zone("Ruby Convoy", dev.rubymod.game.convoy.ConvoyRoute.X0, 220));
		List<String> found = new ArrayList<>();
		for (int i = 0; i < zones.size(); i++) {
			for (int j = i + 1; j < zones.size(); j++) {
				Zone a = zones.get(i);
				Zone b = zones.get(j);
				// Games that share a map on purpose (the two town games) are the same class of thing: same middle, same reach.
				boolean shared = a.x() == b.x() && a.reach() == b.reach() && a.reach() < 100;
				if (!shared && Math.abs(a.x() - b.x()) < a.reach() + b.reach() + 64) {
					found.add(a.name() + " and " + b.name());
				}
			}
		}
		return found;
	}

	public static void reset(MinecraftServer server) {
		for (String clash : overlaps()) {
			dev.rubymod.RubyMod.LOGGER.error("MAPS OVERLAP: {} are built on the same ground. Move one of them.", clash);
		}
		TEST_PLAYERS.clear();
		botsFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("rubywars").resolve("party_bots.txt");
		List<String> on = new ArrayList<>();
		try {
			if (java.nio.file.Files.exists(botsFile)) {
				on = java.nio.file.Files.readAllLines(botsFile);
			}
		} catch (java.io.IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the party bot settings", e);
		}
		for (PartyGame game : ALL) {
			game.botsOn = on.contains(game.id);
		}
		pickupsFile = botsFile.resolveSibling("party_pickups_on.txt");
		List<String> pickups = new ArrayList<>();
		try {
			if (java.nio.file.Files.exists(pickupsFile)) {
				pickups = java.nio.file.Files.readAllLines(pickupsFile);
			}
		} catch (java.io.IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the party pick-up settings", e);
		}
		for (PartyGame game : ALL) {
			game.pickupsOn = pickups.contains(game.id);
		}
		for (PartyGame game : ALL) {
			game.attach(server);
			game.reset();
		}
	}

	public static void tick(MinecraftServer server) {
		// All the games share ~10 ms a tick for building arenas.
		// Arenas someone is waiting on come first; the rest get a few milliseconds each.
		PartyGame.budgetEnd = System.nanoTime() + 10_000_000L;
		for (PartyGame game : ALL) {
			if (!game.players.isEmpty()) {
				buildSome(game, 10_000_000L);
			}
		}
		for (PartyGame game : ALL) {
			if (game.players.isEmpty()) {
				buildSome(game, 3_000_000L);
			}
		}
		for (PartyGame game : ALL) {
			game.attach(server);
			try {
				game.tick();
			} catch (RuntimeException e) {
				crashed(game, e);
			}
		}
	}

	private static void buildSome(PartyGame game, long sliceNanos) {
		try {
			game.runWork(sliceNanos);
		} catch (RuntimeException e) {
			crashed(game, e);
		}
	}

	/** A game hit a bug: log it, and send its players back to the hub instead of taking the server down. */
	public static void crashed(PartyGame game, Throwable e) {
		dev.rubymod.RubyMod.LOGGER.error("{} hit an error; sending its players to the hub", game.title, e);
		try {
			for (ServerPlayer p : game.online()) {
				p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Oops, something went wrong in " + game.title
						+ ". Sorry! Back to the hub (the round was stopped).").withStyle(net.minecraft.ChatFormatting.RED));
			}
			game.sendAllToHub();
		} catch (RuntimeException again) {
			dev.rubymod.RubyMod.LOGGER.error("Couldn't clean up after {}", game.title, again);
			game.reset();
		}
	}

	/** Build every arena in the background when the server starts, so first rounds start right away. */
	public static void prebuild(MinecraftServer server) {
		for (PartyGame game : ALL) {
			game.attach(server);
			if (dev.rubymod.game.Arenas.built(game.id)) {
				continue; // built on an earlier run
			}
			try {
				game.queueBuild();
			} catch (RuntimeException e) {
				dev.rubymod.RubyMod.LOGGER.error("Couldn't build the {} arena", game.title, e);
			}
		}
	}

	public static boolean onDeath(ServerPlayer victim, DamageSource source) {
		PartyGame game = of(victim);
		if (game == null) {
			return false;
		}
		try {
			return game.onDeath(victim, source);
		} catch (RuntimeException e) {
			crashed(game, e);
			victim.setHealth(victim.getMaxHealth());
			return true;
		}
	}

	public static void onEntityDeath(LivingEntity entity, DamageSource source) {
		for (PartyGame game : ALL) {
			try {
				game.entityDied(entity, source);
			} catch (RuntimeException e) {
				crashed(game, e);
			}
		}
	}

	/** The party arenas' outer walls: no blasting your way out. */
	public static boolean protects(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
			return false;
		}
		for (PartyGame game : ALL) {
			if (game.server != null && game.protectsWall(server, pos)) {
				return true;
			}
		}
		return false;
	}

	public static Boolean canHurt(Player attacker, Player victim) {
		PartyGame a = of(attacker);
		PartyGame v = of(victim);
		if (a == null && v == null) {
			return null;
		}
		if (a != v) {
			return false;
		}
		try {
			return a.canHurt(attacker, victim);
		} catch (RuntimeException e) {
			crashed(a, e);
			return false;
		}
	}

	/** The player's team in a team party game, or -1. */
	public static int teamOf(Player player) {
		PartyGame game = of(player);
		if (game == null || !game.teams) {
			return -1;
		}
		PartyGame.Part part = game.players.get(player.getUUID());
		return part == null || part.watching ? -1 : part.team;
	}

	public static void onDisconnect(ServerPlayer player) {
		PartyGame game = of(player);
		if (game != null) {
			game.onDisconnect(player);
		}
	}

	public static boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		PartyGame game = of(player);
		try {
			return game == null || game.allowsItemUse(player, stack);
		} catch (RuntimeException e) {
			crashed(game, e);
			return false;
		}
	}

	public static boolean allowsGuns(ServerPlayer player) {
		PartyGame game = of(player);
		return game == null || game.allowsGuns(player);
	}

	public static boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		PartyGame game = of(player);
		try {
			return game != null && game.onUseBlock(player, pos, held, placeAt);
		} catch (RuntimeException e) {
			crashed(game, e);
			return true;
		}
	}

	public static boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		PartyGame game = of(player);
		return game == null || game.allowsBreak(player, pos);
	}

	public static long playerCount() {
		long n = 0;
		for (PartyGame game : ALL) {
			n += game.players.size();
		}
		return n;
	}

	/** For the game picker: "game:id|title|status|icon|about" per game. */
	public static List<String> lines() {
		List<String> lines = new ArrayList<>();
		for (PartyGame game : ALL) {
			lines.add("game:" + game.id + "|" + game.title + "|" + game.status() + "|" + game.icon + "|" + game.about + "|"
					+ (game.hasBots() ? game.botsOn ? "on" : "off" : "-") + "|" + (game.hasPickups() ? game.pickupsOn ? "on" : "off" : "-"));
		}
		return lines;
	}

	private static java.nio.file.Path pickupsFile;

	/** The game picker's Pick-ups button: switch a game's pick-ups on or off (from its next round). */
	public static void togglePickups(ServerPlayer player, String id) {
		PartyGame game = byId(id);
		if (game == null || !game.hasPickups()) {
			return;
		}
		game.pickupsOn = !game.pickupsOn;
		List<String> on = new ArrayList<>();
		for (PartyGame g : ALL) {
			if (g.hasPickups() && g.pickupsOn) {
				on.add(g.id);
			}
		}
		try {
			java.nio.file.Files.createDirectories(pickupsFile.getParent());
			java.nio.file.Files.write(pickupsFile, on);
		} catch (java.io.IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the party pick-up settings", e);
		}
		var message = net.minecraft.network.chat.Component.literal(player.getName().getString() + " turned pick-ups " + (game.pickupsOn ? "ON" : "OFF")
				+ " for " + game.title + ". It starts next round.").withStyle(net.minecraft.ChatFormatting.YELLOW);
		player.sendSystemMessage(message);
		for (ServerPlayer p : game.online()) {
			if (p != player) {
				p.sendSystemMessage(message);
			}
		}
	}

	/** The game picker's Bots button: switch bots on or off for a game (from its next round). */
	public static void toggleBots(ServerPlayer player, String id) {
		PartyGame game = byId(id);
		if (game == null || !game.hasBots()) {
			return;
		}
		game.botsOn = !game.botsOn;
		List<String> on = new ArrayList<>();
		for (PartyGame g : ALL) {
			if (g.botsOn) {
				on.add(g.id);
			}
		}
		try {
			java.nio.file.Files.createDirectories(botsFile.getParent());
			java.nio.file.Files.write(botsFile, on);
		} catch (java.io.IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the party bot settings", e);
		}
		var message = net.minecraft.network.chat.Component.literal(player.getName().getString() + " turned bots " + (game.botsOn ? "ON" : "OFF") + " for "
				+ game.title + (game.botsOn ? "." : " (a player on their own still gets bots).") + " It starts next round.")
				.withStyle(net.minecraft.ChatFormatting.YELLOW);
		player.sendSystemMessage(message);
		for (ServerPlayer p : game.online()) {
			if (p != player) {
				p.sendSystemMessage(message);
			}
		}
	}

	/** For autotests: a fake player joins a game. */
	public static void addTestPlayer(PartyGame game, ServerPlayer fake) {
		TEST_PLAYERS.put(fake.getUUID(), fake);
		game.join(fake);
	}
}
