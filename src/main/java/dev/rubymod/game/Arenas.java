package dev.rubymod.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import dev.rubymod.RubyMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Which game arenas have been built in this world (world/rubywars/arenas.txt), so a
 * server restart doesn't rebuild them all at once. Rounds still repair their own arena.
 */
public final class Arenas {
	private static final Set<String> BUILT = new HashSet<>();
	private static Path file;

	private Arenas() {
	}

	public static void load(MinecraftServer server) {
		BUILT.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("arenas.txt");
		try {
			if (Files.exists(file)) {
				for (String line : Files.readAllLines(file)) {
					if (!line.isBlank()) {
						BUILT.add(line.trim());
					}
				}
			}
		} catch (IOException e) {
			RubyMod.LOGGER.warn("Couldn't read the arena list", e);
		}
	}

	public static boolean built(String id) {
		return BUILT.contains(id);
	}

	public static void markBuilt(String id) {
		if (BUILT.add(id) && file != null) {
			try {
				Files.createDirectories(file.getParent());
				Files.write(file, BUILT);
			} catch (IOException e) {
				RubyMod.LOGGER.warn("Couldn't save the arena list", e);
			}
		}
	}
}
