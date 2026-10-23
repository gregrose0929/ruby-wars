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
 * Goals: a short checklist of things to do, each worth stars (research points).
 * Upgrades come from doing things, never from how long or how often you play:
 * place your Heart, mine ruby, build machines, repel a raid, win a duel...
 * Each goal counts once per season. There are fewer stars than the whole tree
 * costs, so squads still split the research.
 */
public final class Goals {
	/** One goal: reach {@code target} (a count, or a value like your bank) for {@code stars}. */
	public record Goal(String id, String title, int target, int stars) {
	}

	public static final List<Goal> ALL = List.of(
			new Goal("heart", "Place your Ruby Heart", 1, 1),
			new Goal("ruby10", "Mine 10 Ruby Ore", 10, 1),
			new Goal("ruby50", "Mine 50 Ruby Ore", 50, 2),
			new Goal("power", "Build a generator", 1, 1),
			new Goal("drill", "Build a Ruby Drill", 1, 1),
			new Goal("depot", "Build a Trade Depot", 1, 1),
			new Goal("mk3", "Machine to Mk III", 3, 2),
			new Goal("bank500", "Bank 500 rubies", 500, 1),
			new Goal("bank2000", "Bank 2,000 rubies", 2000, 2),
			new Goal("heart2", "Upgrade your Heart", 1, 2),
			new Goal("armory", "Buy from the Armory", 1, 1),
			new Goal("defense", "Place a base defense", 1, 1),
			new Goal("squad", "Join a squad", 1, 1),
			new Goal("bounties", "Finish 3 bounties", 3, 1),
			new Goal("raid", "Repel a raid", 1, 2),
			new Goal("duel", "Win a duel (bot counts)", 1, 1),
			new Goal("drive", "Drive 300 blocks", 300, 1),
			new Goal("fly", "Fly 1,000 blocks", 1000, 2));

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
		r.goalsDone.add(id);
		r.researchPoints += goal.stars();
		SeasonData.markDirty();
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		if (p != null) {
			String stars = "★".repeat(goal.stars());
			Alerts.title(p, "GOAL!", goal.title() + "  +" + stars, ChatFormatting.GOLD);
			p.sendSystemMessage(Component.literal("Goal complete: " + goal.title() + "! +" + goal.stars() + " star" + (goal.stars() == 1 ? "" : "s")
					+ " to spend on upgrades (press J).").withStyle(ChatFormatting.GOLD));
			p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.2F);
		}
		sync(server, player);
	}

	private static void sync(MinecraftServer server, UUID player) {
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		if (p != null) {
			Research.sync(p);
		}
	}

	/** For the research screen: "id|title|progress|target|stars|done" per goal. */
	public static List<String> describe(UUID player) {
		SeasonData.PlayerRecord r = record(player);
		List<String> out = new ArrayList<>();
		for (Goal g : ALL) {
			boolean done = r != null && r.goalsDone.contains(g.id());
			int progress = done ? g.target() : r == null ? 0 : Math.min(g.target(), r.goalProgress.getOrDefault(g.id(), 0));
			out.add(g.id() + "|" + g.title() + "|" + progress + "|" + g.target() + "|" + g.stars() + "|" + done);
		}
		return out;
	}

	/** Every second: bank goals, and distance driven or flown. */
	public static void tickSecond(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			UUID id = p.getUUID();
			reach(server, id, "bank500", (int) Math.min(Integer.MAX_VALUE, Grid.rubies(id)));
			reach(server, id, "bank2000", (int) Math.min(Integer.MAX_VALUE, Grid.rubies(id)));
			var vehicle = p.getVehicle();
			boolean driving = vehicle instanceof VehicleEntity v && v.getControllingPassenger() == p;
			boolean flying = vehicle instanceof AircraftEntity a && a.getControllingPassenger() == p && !a.onGround();
			if (!driving && !flying || Duels.inDuel(p)) {
				LAST_RIDE_POS.remove(id);
				continue;
			}
			Vec3 now = vehicle.position();
			Vec3 last = LAST_RIDE_POS.put(id, now);
			if (last != null) {
				double moved = Math.hypot(now.x - last.x, now.z - last.z);
				if (moved < 200) { // not a teleport
					add(server, id, flying ? "fly" : "drive", (int) Math.round(moved));
					Bounties.onTravel(p, (int) Math.round(moved));
				}
			}
		}
	}
}
