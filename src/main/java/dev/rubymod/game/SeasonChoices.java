package dev.rubymod.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The season choices, made together on the Season Choices screen (or /rubywars choose):
 * <ul>
 * <li>Battle Day teams: pick Red, Blue or "on my own". Anyone who picked the same colour is on the same side on Battle Day
 * (each keeps their own base and Heart; teammates can't hurt each other, their defenses leave each other alone, they can
 * respawn at each other's Hearts, and they share the win). Decided when Battle Day starts; until then anyone can change.</li>
 * <li>Night Battle: everyone votes yes or no; the most votes win when Battle Day starts (a tie, or no votes, leaves it as the
 * server's setting).</li>
 * <li>What your base is famous for (walls, factory, garage, air base): no bonus, just something to be recognised for at the
 * end of the season. Change it any time.</li>
 * </ul>
 */
public final class SeasonChoices {
	private SeasonChoices() {
	}

	public static final List<String> TEAMS = List.of("", "red", "blue");
	public static final List<String> THEMES = List.of("", "walls", "factory", "garage", "airbase");

	public static String teamName(String team) {
		return switch (team) {
			case "red" -> "Red team";
			case "blue" -> "Blue team";
			default -> "On my own";
		};
	}

	public static String themeName(String theme) {
		return switch (theme) {
			case "walls" -> "Its walls";
			case "factory" -> "Its factory";
			case "garage" -> "Its garage";
			case "airbase" -> "Its air base";
			default -> "Nothing yet";
		};
	}

	/** Can the Battle Day choices (team, night) still be changed? Up until Battle Day starts. */
	public static boolean battleChoicesOpen() {
		Phase phase = SeasonData.get().phase;
		return phase == Phase.LOBBY || phase == Phase.BUILD;
	}

	/** A choice from the screen: kind "team", "night" (yes/no/none) or "theme". Returns an error, or null. */
	public static String choose(ServerPlayer player, String kind, String value) {
		SeasonData data = SeasonData.get();
		if (!Hub.inRubyWarsWorld(player) && data.player(player.getUUID()) == null) {
			return "Season choices are for Ruby Wars players.";
		}
		SeasonData.PlayerRecord record = data.player(player.getUUID(), player.getName().getString());
		switch (kind) {
			case "team" -> {
				if (!battleChoicesOpen()) {
					return "Teams were settled when Battle Day started.";
				}
				if (!TEAMS.contains(value)) {
					return "Pick red, blue or solo.";
				}
				record.team = value;
			}
			case "night" -> {
				if (!battleChoicesOpen()) {
					return "The Night Battle vote closed when Battle Day started.";
				}
				record.nightVote = switch (value) {
					case "yes" -> 1;
					case "no" -> 2;
					default -> 0;
				};
			}
			case "theme" -> {
				if (data.phase == Phase.ENDED) {
					return "The season's over: pick again next season.";
				}
				if (!THEMES.contains(value)) {
					return "Pick walls, factory, garage, airbase or none.";
				}
				record.theme = value;
			}
			default -> {
				return "Choose team, night or theme.";
			}
		}
		SeasonData.markDirty();
		syncAll(player.level().getServer());
		return null;
	}

	/** Who's picked which team, for the screen ("Red team: Ann, Ben"). */
	private static Map<String, List<String>> sides() {
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (SeasonData.PlayerRecord r : SeasonData.get().players.values()) {
			if (!r.team.isEmpty()) {
				out.computeIfAbsent(r.team, k -> new ArrayList<>()).add(r.name);
			}
		}
		return out;
	}

	/** How the Night Battle vote stands: {yes, no}. */
	public static int[] nightVotes() {
		int yes = 0;
		int no = 0;
		for (SeasonData.PlayerRecord r : SeasonData.get().players.values()) {
			if (r.nightVote == 1) {
				yes++;
			} else if (r.nightVote == 2) {
				no++;
			}
		}
		return new int[] {yes, no};
	}

	/** A line on how things stand, for the screen. */
	public static String summary() {
		StringBuilder b = new StringBuilder();
		for (Map.Entry<String, List<String>> e : sides().entrySet()) {
			if (b.length() > 0) {
				b.append("  |  ");
			}
			b.append(teamName(e.getKey())).append(": ").append(String.join(", ", e.getValue()));
		}
		if (b.length() == 0) {
			b.append("No teams picked: everyone's on their own");
		}
		int[] night = nightVotes();
		b.append("  |  Night Battle: ").append(night[0]).append(" yes, ").append(night[1]).append(" no");
		return b.toString();
	}

	public static void sync(ServerPlayer player) {
		SeasonData.PlayerRecord r = SeasonData.get().player(player.getUUID());
		Net.send(player, new MorePayloads.ChoicesState(r == null ? "" : r.team, r == null ? 0 : r.nightVote, r == null ? "" : r.theme,
				battleChoicesOpen(), summary()));
	}

	public static void syncAll(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			sync(p);
		}
	}

	/**
	 * Battle Day's starting: the night vote decides whether it's at night, and the teams are made (a team needs two or more
	 * people with a Heart; anyone alone in a colour plays on their own).
	 */
	public static void onBattleDay(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		int[] night = nightVotes();
		data.nightChoice = night[0] > night[1] ? Boolean.TRUE : night[1] > night[0] ? Boolean.FALSE : null;
		data.squads.clear();
		Map<String, List<String>> members = new LinkedHashMap<>();
		for (Map.Entry<String, SeasonData.PlayerRecord> e : data.players.entrySet()) {
			SeasonData.Claim claim = data.claimOf(UUID.fromString(e.getKey()));
			if (!e.getValue().team.isEmpty() && !e.getValue().satOut && claim != null && !claim.destroyed) {
				members.computeIfAbsent(e.getValue().team, k -> new ArrayList<>()).add(e.getKey());
			}
		}
		List<String> said = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : members.entrySet()) {
			if (e.getValue().size() < 2) {
				continue;
			}
			Squads.Squad squad = new Squads.Squad();
			squad.name = teamName(e.getKey());
			squad.leader = e.getValue().get(0);
			squad.members.addAll(e.getValue());
			data.squads.put(e.getKey(), squad);
			List<String> names = new ArrayList<>();
			for (String id : e.getValue()) {
				names.add(data.players.get(id).name);
			}
			said.add(squad.name + " (" + String.join(", ", names) + ")");
		}
		SeasonData.markDirty();
		if (!said.isEmpty()) {
			Alerts.broadcast(server, Component.literal("Battle Day teams: " + String.join(" vs ", said)
					+ ". Teammates can't hurt each other, and they win together.").withStyle(ChatFormatting.AQUA));
		}
		if (data.nightChoice != null) {
			Alerts.broadcast(server, Component.literal(data.nightChoice ? "You voted for a Night Battle: Battle Day is at night!"
					: "You voted against a Night Battle: Battle Day is in daylight.").withStyle(ChatFormatting.AQUA));
		}
		syncAll(server);
	}
}
