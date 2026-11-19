package dev.rubymod.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * "What's new": after an update, the first time each player joins, a card (like the how-to cards) says what changed.
 * Shown once per player per update; change {@link #VERSION} and the lines for the next one.
 */
public final class WhatsNew {
	private WhatsNew() {
	}

	private static final String VERSION = "2026-11-19-polish";
	private static final List<String> LINES = List.of(
			"minecraft:compass|Press M for the new Ruby Wars menu: map, guide, profile, manual, games and Settings (choose what your HUD shows).",
			"minecraft:nether_star|Goals, unlocks and news now pop up as toasts in the corner instead of filling chat. Goals show in the advancements screen too.",
			"rubymod:ruby_wrench|Machines glow and hum while they work; the Ruby Wrench turns and picks them up; Belt Ramps carry things up a level.");

	/** Who's seen this update's card. */
	private static Set<UUID> seen;
	/** Who's waiting to see it, and the server tick to show it on (a few seconds after joining). */
	private static final Map<UUID, Integer> WAITING = new HashMap<>();

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("whatsnew.txt");
	}

	private static Set<UUID> seen(MinecraftServer server) {
		if (seen == null) {
			seen = new HashSet<>();
			try {
				Path f = file(server);
				if (Files.exists(f)) {
					List<String> lines = Files.readAllLines(f);
					if (!lines.isEmpty() && lines.get(0).trim().equals(VERSION)) {
						for (String l : lines.subList(1, lines.size())) {
							if (!l.isBlank()) {
								seen.add(UUID.fromString(l.trim()));
							}
						}
					}
				}
			} catch (Exception e) {
				dev.rubymod.RubyMod.LOGGER.warn("Couldn't read who's seen what's new", e);
			}
		}
		return seen;
	}

	public static void onJoin(MinecraftServer server, ServerPlayer player) {
		if (!seen(server).contains(player.getUUID())) {
			WAITING.put(player.getUUID(), server.getTickCount() + 200);
		}
	}

	public static void tick(MinecraftServer server) {
		if (WAITING.isEmpty()) {
			return;
		}
		var it = WAITING.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (server.getTickCount() < e.getValue()) {
				continue;
			}
			it.remove();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				continue;
			}
			Net.send(p, new MorePayloads.HowTo("What's new", LINES, 25));
			seen(server).add(p.getUUID());
			StringBuilder out = new StringBuilder(VERSION).append(System.lineSeparator());
			for (UUID id : seen) {
				out.append(id).append(System.lineSeparator());
			}
			try {
				Files.createDirectories(file(server).getParent());
				SafeFiles.write(file(server), out.toString());
			} catch (Exception ex) {
				dev.rubymod.RubyMod.LOGGER.warn("Couldn't save who's seen what's new", ex);
			}
		}
	}
}
