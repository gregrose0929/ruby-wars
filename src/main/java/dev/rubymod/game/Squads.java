package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Squads: up to {@code squadMaxSize} players who fight as one. Squadmates can't
 * shoot each other, share each other's claims (build, open, defenses ignore
 * them), respawn at any standing squad Heart, see each other on the map and
 * through walls, share pings, and win together: Battle Day ends when one squad
 * (or one solo player) is left.
 */
public final class Squads {
	/** Pending invites: invited player -> squad name. */
	private static final Map<UUID, String> INVITES = new HashMap<>();

	private Squads() {
	}

	public static class Squad {
		public String name;
		public String leader;
		public Set<String> members = new LinkedHashSet<>();
	}

	private static Map<String, Squad> all() {
		return SeasonData.get().squads;
	}

	public static Squad of(UUID player) {
		String id = player.toString();
		for (Squad squad : all().values()) {
			if (squad.members.contains(id)) {
				return squad;
			}
		}
		return null;
	}

	public static boolean same(UUID a, UUID b) {
		if (a.equals(b)) {
			return true;
		}
		Squad squad = of(a);
		return squad != null && squad.members.contains(b.toString());
	}

	public static List<UUID> mates(UUID player) {
		List<UUID> out = new ArrayList<>();
		Squad squad = of(player);
		if (squad != null) {
			for (String id : squad.members) {
				if (!id.equals(player.toString())) {
					out.add(UUID.fromString(id));
				}
			}
		}
		return out;
	}

	public static String create(ServerPlayer player, String name) {
		if (of(player.getUUID()) != null) {
			return "You're already in a squad. Leave it first (/squad leave).";
		}
		if (!name.matches("[A-Za-z0-9_ -]{2,16}")) {
			return "Squad names are 2-16 letters, numbers, spaces, - or _.";
		}
		if (all().containsKey(name.toLowerCase())) {
			return "There's already a squad called " + name + ".";
		}
		Squad squad = new Squad();
		squad.name = name;
		squad.leader = player.getUUID().toString();
		squad.members.add(player.getUUID().toString());
		all().put(name.toLowerCase(), squad);
		SeasonData.markDirty();
		return null;
	}

	public static String invite(ServerPlayer from, ServerPlayer to) {
		Squad squad = of(from.getUUID());
		if (squad == null) {
			return "Make a squad first: /squad create <name>.";
		}
		if (squad.members.size() >= GameConfig.get().squadMaxSize) {
			return "Your squad is full (" + GameConfig.get().squadMaxSize + " players).";
		}
		if (of(to.getUUID()) != null) {
			return to.getName().getString() + " is already in a squad.";
		}
		INVITES.put(to.getUUID(), squad.name.toLowerCase());
		to.sendSystemMessage(Component.literal(from.getName().getString() + " invited you to the squad " + squad.name + ". Type /squad join "
				+ squad.name + " to join.").withStyle(ChatFormatting.AQUA));
		return null;
	}

	public static String join(ServerPlayer player, String name) {
		Squad squad = all().get(name.toLowerCase());
		if (squad == null) {
			return "No squad called " + name + ".";
		}
		if (!name.equalsIgnoreCase(INVITES.getOrDefault(player.getUUID(), ""))) {
			return "You need an invite from someone in " + squad.name + ".";
		}
		if (of(player.getUUID()) != null) {
			return "Leave your squad first (/squad leave).";
		}
		if (squad.members.size() >= GameConfig.get().squadMaxSize) {
			return squad.name + " is full.";
		}
		if (SeasonData.get().phase.isBattle()) {
			return "Squads lock when Battle Day starts.";
		}
		INVITES.remove(player.getUUID());
		squad.members.add(player.getUUID().toString());
		SeasonData.markDirty();
		tell(player.level().getServer(), squad, player.getName().getString() + " joined the squad.");
		return null;
	}

	public static String leave(ServerPlayer player) {
		Squad squad = of(player.getUUID());
		if (squad == null) {
			return "You're not in a squad.";
		}
		if (SeasonData.get().phase.isBattle()) {
			return "You can't leave your squad during Battle Day.";
		}
		remove(squad, player.getUUID().toString());
		tell(player.level().getServer(), squad, player.getName().getString() + " left the squad.");
		return null;
	}

	public static String kick(ServerPlayer leader, String name) {
		Squad squad = of(leader.getUUID());
		if (squad == null || !squad.leader.equals(leader.getUUID().toString())) {
			return "Only the squad leader can kick.";
		}
		if (SeasonData.get().phase.isBattle()) {
			return "Squads are locked during Battle Day.";
		}
		for (String id : squad.members) {
			SeasonData.PlayerRecord record = SeasonData.get().players.get(id);
			if (record != null && record.name.equalsIgnoreCase(name) && !id.equals(squad.leader)) {
				remove(squad, id);
				tell(leader.level().getServer(), squad, record.name + " was removed from the squad.");
				return null;
			}
		}
		return "No squadmate called " + name + ".";
	}

	private static void remove(Squad squad, String id) {
		squad.members.remove(id);
		if (squad.members.isEmpty()) {
			all().remove(squad.name.toLowerCase());
		} else if (squad.leader.equals(id)) {
			squad.leader = squad.members.iterator().next();
		}
		SeasonData.markDirty();
	}

	/** A message to every online squadmate. */
	public static void tell(MinecraftServer server, Squad squad, String text) {
		for (String id : squad.members) {
			ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(id));
			if (p != null) {
				p.sendSystemMessage(Component.literal("[" + squad.name + "] " + text).withStyle(ChatFormatting.AQUA));
			}
		}
	}

	/** Squad chat: /sc <message>. */
	public static String chat(ServerPlayer from, String text) {
		Squad squad = of(from.getUUID());
		if (squad == null) {
			return "You're not in a squad.";
		}
		tell(from.level().getServer(), squad, from.getName().getString() + ": " + text);
		return null;
	}

	/** Who's left standing, grouped: each squad counts once, solo players count alone. */
	public static Set<String> aliveSides() {
		Set<String> sides = new LinkedHashSet<>();
		for (Map.Entry<String, SeasonData.PlayerRecord> e : SeasonData.get().players.entrySet()) {
			if (!e.getValue().eliminated) {
				Squad squad = of(UUID.fromString(e.getKey()));
				sides.add(squad != null ? squad.name : e.getValue().name);
			}
		}
		return sides;
	}
}
