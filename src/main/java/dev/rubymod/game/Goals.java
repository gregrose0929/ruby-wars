package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.industry.Grid;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Goals: a checklist of things to do in Ruby Wars, each worth stars (research points).
 * Upgrades come from doing things, never from how long or how often you play:
 * build up your base, make rubies, go to world events, fight on Battle Day, get around.
 * Each goal counts once per season. There are fewer stars than the whole tree
 * costs, so you still pick what to research.
 */
public final class Goals {
	/** One goal: reach {@code target} (a count, or a value like your bank) for {@code stars}. */
	public record Goal(String id, String title, int target, int stars) {
	}

	public static final List<Goal> ALL = List.of(
			// Your base
			new Goal("heart", "Place your Ruby Heart", 1, 1),
			new Goal("heart2", "Heart to tier 2", 2, 2),
			new Goal("heart3", "Heart to tier 3", 3, 3),
			new Goal("armory", "Buy from the Armory", 1, 1),
			new Goal("defense", "Place a base defense", 1, 1),
			new Goal("defense5", "Place 5 base defenses", 5, 2),
			new Goal("vault", "Build a Vault", 1, 1),
			// Making rubies
			new Goal("ruby10", "Mine 10 Ruby Ore", 10, 1),
			new Goal("ruby50", "Mine 25 Ruby Ore", 25, 2),
			new Goal("power", "Build a generator", 1, 1),
			new Goal("drill", "Build a Ruby Drill", 1, 1),
			new Goal("depot", "Build a Trade Depot", 1, 1),
			new Goal("sell100", "Sell at a Trade Depot", 100, 1),
			new Goal("loader100", "Belt Loaders move items", 100, 1),
			new Goal("busy3", "3 machines busy at once", 3, 2),
			new Goal("mk3", "Machine to Mk III", 3, 2),
			new Goal("bank500", "Bank 100 rubies", 100, 1),
			new Goal("bank2000", "Bank 200 rubies", 200, 2),
			// World events and Battle Day
			new Goal("cache", "Get to a Supply Cache", 1, 2),
			new Goal("shrine", "Hold a Ruby Shrine longest", 1, 2),
			new Goal("knockout", "Battle Day knockout", 1, 1),
			new Goal("heartkill", "Damage an enemy Heart", 1, 2),
			new Goal("sudden", "Make it to Sudden Death", 1, 2),
			// Getting around
			new Goal("drive", "Drive 300 blocks", 300, 1),
			new Goal("fly", "Fly 500 blocks", 500, 2),
			new Goal("sail", "Sail 500 blocks", 500, 1),
			new Goal("dig", "Dig with a Tunnel Borer", 50, 1),
			// Chapter two, "Ready for Battle Day": opens after OPEN_AT goals above. No stars (you still pick your research
			// with the ones above); finishing it all earns the Fortress Sparks fireworks.
			new Goal("coverage", "See what your defenses cover (hold one)", 1, 0),
			new Goal("airdef", "Air defense near your Heart", 1, 0),
			new Goal("shield", "Put up a Shield Generator", 1, 0),
			new Goal("holo", "Hide something with Holo Camo", 1, 0),
			new Goal("sensor", "Place a Seismic Sensor or Radar", 1, 0),
			new Goal("dummy", "Your defenses hit a Practice Dummy", 1, 0));

	/** The first goal of chapter two ("Ready for Battle Day"). */
	public static final String CHAPTER_TWO = "coverage";
	/** Goals done in chapter one to open chapter two. */
	public static final int OPEN_AT = 20;

	private static boolean chapterTwo(String id) {
		boolean two = false;
		for (Goal g : ALL) {
			two |= g.id().equals(CHAPTER_TWO);
			if (g.id().equals(id)) {
				return two;
			}
		}
		return false;
	}

	/** How many chapter-one goals this player has done. */
	private static int chapterOneDone(SeasonData.PlayerRecord r) {
		int n = 0;
		for (String id : r.goalsDone) {
			if (byId(id) != null && !chapterTwo(id)) {
				n++;
			}
		}
		return n;
	}

	/** Is "Ready for Battle Day" open for them? */
	public static boolean chapterTwoOpen(SeasonData.PlayerRecord r) {
		return r != null && r.goalsDone != null && chapterOneDone(r) >= OPEN_AT;
	}

	/** The J screen's headings: each starts just before its first goal. */
	private static final Map<String, String> SECTIONS = Map.of("heart", "Your base", "ruby10", "Making rubies", "cache", "World events and Battle Day",
			"drive", "Getting around", CHAPTER_TWO, "Ready for Battle Day");

	/** Machines that count as "working" for busy3 (makers, not power or defenses). */
	private static final Map<UUID, Map<Long, Long>> WORKING = new HashMap<>();

	/** A machine of this owner's worked just now (MachineBlockEntity). */
	public static void working(UUID owner, long pos, long gameTime) {
		WORKING.computeIfAbsent(owner, k -> new HashMap<>()).put(pos, gameTime);
	}

	private static final Map<UUID, Vec3> LAST_RIDE_POS = new HashMap<>();

	private Goals() {
	}

	public static Goal byId(String id) {
		for (Goal g : ALL) {
			if (g.id().equals(id)) {
				return g;
			}
		}
		return null;
	}

	/** The player's record, or null if they aren't a player this season (never creates one). */
	private static SeasonData.PlayerRecord record(UUID player) {
		SeasonData.PlayerRecord r = SeasonData.get().players.get(player.toString());
		if (r == null) {
			return null;
		}
		if (r.goalProgress == null) {
			r.goalProgress = new HashMap<>();
		}
		if (r.goalsDone == null) {
			r.goalsDone = new HashSet<>();
		}
		return r;
	}

	/** Add to a counting goal. */
	public static void add(MinecraftServer server, UUID player, String id, int amount) {
		SeasonData.PlayerRecord r = record(player);
		if (r == null || r.goalsDone.contains(id) || amount <= 0) {
			return;
		}
		r.goalProgress.merge(id, amount, Integer::sum);
		SeasonData.markDirty();
		check(server, player, id);
	}

	/** Raise a "reach this value" goal (bank size, machine tier). */
	public static void reach(MinecraftServer server, UUID player, String id, int value) {
		SeasonData.PlayerRecord r = record(player);
		if (r == null || r.goalsDone.contains(id) || value <= r.goalProgress.getOrDefault(id, 0)) {
			return;
		}
		r.goalProgress.put(id, value);
		SeasonData.markDirty();
		check(server, player, id);
	}

	private static void check(MinecraftServer server, UUID player, String id) {
		Goal goal = byId(id);
		SeasonData.PlayerRecord r = record(player);
		if (goal == null || r == null || r.goalsDone.contains(id) || r.goalProgress.getOrDefault(id, 0) < goal.target()) {
			sync(server, player);
			return;
		}
		boolean wasOpen = chapterTwoOpen(r);
		r.goalsDone.add(id);
		r.researchPoints += goal.stars();
		SeasonData.markDirty();
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		boolean two = chapterTwo(id);
		if (p != null) {
			advancement(p, id);
			if (!two || wasOpen) {
				String stars = goal.stars() > 0 ? " +" + "★".repeat(goal.stars()) : "";
				Alerts.toast(p, two ? "rubymod:sentry_turret" : "minecraft:nether_star", (two ? "Ready for Battle Day!" : "Goal done!") + stars + " (J)",
						goal.title(), Alerts.GOLD);
				p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.2F);
			}
			if (!two && !wasOpen && chapterTwoOpen(r)) {
				Alerts.toast(p, "rubymod:sentry_turret", "New goals: Ready for Battle Day (J)", "Get your base set for the big day", Alerts.GOLD);
			}
		}
		if (two && chapterTwoOpen(r) || !two && !wasOpen && chapterTwoOpen(r)) {
			chapterTwoFinished(server, player, r);
		}
		sync(server, player);
	}

	/** The goal's advancement (the advancements screen's Ruby Wars tab: see tools/goal_advancements.py). */
	private static void advancement(ServerPlayer p, String id) {
		var holder = p.level().getServer().getAdvancements().get(dev.rubymod.RubyMod.id("goals/" + id));
		if (holder != null) {
			p.getAdvancements().award(holder, "done");
		}
	}

	/** Joining: every goal done this season shows as an advancement too (and any already past its target counts now). */
	public static void syncAdvancements(ServerPlayer p) {
		SeasonData.PlayerRecord r = record(p.getUUID());
		if (r != null) {
			for (Goal g : ALL) {
				if (!r.goalsDone.contains(g.id()) && r.goalProgress.getOrDefault(g.id(), 0) >= g.target()) {
					check(p.level().getServer(), p.getUUID(), g.id());
				}
			}
		}
		if (r != null && r.goalsDone != null) {
			for (String id : r.goalsDone) {
				advancement(p, id);
			}
		}
	}

	private static void sync(MinecraftServer server, UUID player) {
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		if (p != null) {
			Research.sync(p);
		}
	}

	/** All of "Ready for Battle Day" done (and open): the Fortress Sparks, once a season. */
	private static void chapterTwoFinished(MinecraftServer server, UUID player, SeasonData.PlayerRecord r) {
		boolean two = false;
		for (Goal g : ALL) {
			two |= g.id().equals(CHAPTER_TWO);
			if (two && !r.goalsDone.contains(g.id())) {
				return;
			}
		}
		if (r.goalsDone.contains("ready_all")) {
			return;
		}
		r.goalsDone.add("ready_all");
		SeasonData.markDirty();
		Stats.battleReady(player, r.name);
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		if (p != null) {
			Alerts.toast(p, "minecraft:firework_rocket", "Your base is ready for Battle Day!", "New fireworks: Fortress Sparks (I to pick them)", Alerts.GOLD);
		}
	}

	/** For the research screen: "id|title|progress|target|stars|done" per goal, and "#Heading" before each group. */
	public static List<String> describe(UUID player) {
		SeasonData.PlayerRecord r = record(player);
		List<String> out = new ArrayList<>();
		for (Goal g : ALL) {
			if (g.id().equals(CHAPTER_TWO) && !chapterTwoOpen(r)) {
				// Not open yet: a line saying how to open it.
				out.add("#Ready for Battle Day (opens later)");
				int done = r == null ? 0 : Math.min(OPEN_AT, chapterOneDone(r));
				out.add("ready_lock|Do " + OPEN_AT + " goals above to open these|" + done + "|" + OPEN_AT + "|0|false");
				break;
			}
			if (SECTIONS.containsKey(g.id())) {
				out.add("#" + SECTIONS.get(g.id()));
			}
			boolean done = r != null && r.goalsDone.contains(g.id());
			int progress = done ? g.target() : r == null ? 0 : Math.min(g.target(), r.goalProgress.getOrDefault(g.id(), 0));
			out.add(g.id() + "|" + g.title() + "|" + progress + "|" + g.target() + "|" + g.stars() + "|" + done);
		}
		return out;
	}

	/** Every second: bank goals, machines working at once, and distance driven, flown or sailed. */
	public static void tickSecond(MinecraftServer server) {
		long time = server.overworld().getGameTime();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			UUID id = p.getUUID();
			reach(server, id, "bank500", (int) Math.min(Integer.MAX_VALUE, Grid.rubies(id)));
			reach(server, id, "bank2000", (int) Math.min(Integer.MAX_VALUE, Grid.rubies(id)));
			Map<Long, Long> busy = WORKING.get(id);
			if (busy != null) {
				busy.values().removeIf(t -> time - t > 40);
				reach(server, id, "busy3", busy.size());
			}
			var vehicle = p.getVehicle();
			boolean boat = vehicle instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat b && b.getControllingPassenger() == p;
			boolean driving = vehicle instanceof VehicleEntity v && v.getControllingPassenger() == p || boat;
			boolean jetpack = vehicle == null && Jetpacks.wearing(p) && !p.onGround() && !p.isInWater() && !p.getAbilities().flying;
			boolean flying = vehicle instanceof AircraftEntity a && a.getControllingPassenger() == p && !a.onGround() || jetpack;
			if (!driving && !flying || !Hub.inRubyWarsWorld(p)) {
				LAST_RIDE_POS.remove(id);
				continue;
			}
			Vec3 now = vehicle == null ? p.position() : vehicle.position();
			Vec3 last = LAST_RIDE_POS.put(id, now);
			if (last != null) {
				double moved = Math.hypot(now.x - last.x, now.z - last.z);
				if (moved < 200) { // not a teleport
					add(server, id, flying ? "fly" : boat || vehicle instanceof dev.rubymod.entity.BoatEntity ? "sail" : "drive", (int) Math.round(moved));
				}
			}
		}
	}
}
