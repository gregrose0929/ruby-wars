package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Veteran stars: a little gold ★ after your name (in chat, above your head, in the player list) for each Ruby Wars
 * season you've played. Purely for show.
 */
public final class Veterans {
	/** On the client: everyone's seasons played, as the server sent them. */
	public static final Map<UUID, Integer> CLIENT = new ConcurrentHashMap<>();

	private Veterans() {
	}

	/** "★★★", or "★7" past five. */
	public static String stars(int seasons) {
		if (seasons <= 0) {
			return "";
		}
		return seasons <= 5 ? "★".repeat(seasons) : "★" + seasons;
	}

	/** The name with its stars (as Player.getDisplayName shows it). */
	public static Component decorate(Component name, int seasons) {
		if (seasons <= 0) {
			return name;
		}
		MutableComponent out = name.copy();
		return out.append(Component.literal(" " + stars(seasons)).withStyle(ChatFormatting.GOLD));
	}

	private static MorePayloads.VeteranSync payload() {
		List<String> lines = new ArrayList<>();
		for (var e : Stats.seasonsPlayedById().entrySet()) {
			if (e.getValue() > 0) {
				lines.add(e.getKey() + "|" + e.getValue());
			}
		}
		return new MorePayloads.VeteranSync(lines);
	}

	public static void send(ServerPlayer player) {
		Net.send(player, payload());
	}

	public static void syncAll(MinecraftServer server) {
		var p = payload();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Net.send(player, p);
		}
	}

	/** On the client, from the server's list. */
	public static void receive(List<String> lines) {
		CLIENT.clear();
		for (String line : lines) {
			String[] f = line.split("[|]");
			try {
				CLIENT.put(UUID.fromString(f[0]), Integer.parseInt(f[1]));
			} catch (RuntimeException ignored) {
				// A bad line is skipped.
			}
		}
	}
}
