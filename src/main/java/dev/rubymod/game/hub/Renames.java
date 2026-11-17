package dev.rubymod.game.hub;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Houses, pets, post, the fish and butterfly books and the museum's labels are kept by player name. If someone changes
 * their Minecraft name, they'd lose them: so every account's last name is remembered, and when it changes, everything
 * moves over to the new name.
 */
public final class Renames {
	private Renames() {
	}

	private static final Map<UUID, String> LAST = new HashMap<>();
	private static boolean loaded;

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("names.txt");
	}

	public static void onJoin(MinecraftServer server, ServerPlayer p) {
		load(server);
		String now = p.getName().getString();
		String before = LAST.put(p.getUUID(), now);
		if (before != null && !before.equals(now)) {
			ServerLevel hub = server.getLevel(dev.rubymod.game.Hub.HUB);
			if (hub != null) {
				dev.rubymod.RubyMod.LOGGER.info("Renames: {} is now {}: moving their house, pet, post and books", before, now);
				Pets.rename(hub, before, now);
				HomeStreet.rename(hub, before, now);
				Fishing.rename(hub, before, now);
				Butterflies.rename(hub, before, now);
				Museum.rename(hub, before, now);
			}
		}
		if (!now.equals(before)) {
			save(server);
		}
	}

	private static void load(MinecraftServer server) {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file(server);
			if (Files.exists(f)) {
				for (String line : Files.readAllLines(f)) {
					String[] q = line.split(" ", 2);
					if (q.length == 2) {
						LAST.put(UUID.fromString(q[0]), q[1]);
					}
				}
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the names", e);
		}
	}

	private static void save(MinecraftServer server) {
		StringBuilder b = new StringBuilder();
		for (var e : LAST.entrySet()) {
			b.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
		}
		try {
			dev.rubymod.game.SafeFiles.write(file(server), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the names", e);
		}
	}
}
