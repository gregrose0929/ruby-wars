package dev.rubymod.game;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.rubymod.RubyMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Zips the world into backups/ next to the server every few hours (24 by
 * default), keeping the newest few. Saving is paused while the zip is written
 * so the copy is consistent.
 */
public final class Backups {
	private static volatile boolean running;

	private Backups() {
	}

	public static void tick(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		long interval = GameConfig.get().backupIntervalHours * 3_600_000L;
		if (interval <= 0 || running) {
			return;
		}
		if (data.lastBackupAt == 0) {
			data.lastBackupAt = SeasonTicker.now();
			SeasonData.markDirty();
			return;
		}
		if (SeasonTicker.now() - data.lastBackupAt >= interval) {
			run(server);
		}
	}

	public static boolean run(MinecraftServer server) {
		if (running) {
			return false;
		}
		running = true;
		SeasonData.get().lastBackupAt = SeasonTicker.now();
		SeasonData.save();
		server.saveEverything(true, true, true);
		for (ServerLevel level : server.getAllLevels()) {
			level.noSave = true;
		}
		Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Path backups = world.getParent().resolve("backups");
		String name = world.getFileName() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".zip";
		Thread thread = new Thread(() -> {
			try {
				Files.createDirectories(backups);
				zip(world, backups.resolve(name));
				prune(backups, GameConfig.get().backupsToKeep);
				RubyMod.LOGGER.info("World backup saved to {}", backups.resolve(name));
			} catch (Exception e) {
				RubyMod.LOGGER.error("World backup failed", e);
			} finally {
				server.execute(() -> {
					for (ServerLevel level : server.getAllLevels()) {
						level.noSave = false;
					}
					running = false;
				});
			}
		}, "RubyWars-Backup");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	private static void zip(Path source, Path target) throws IOException {
		try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out); Stream<Path> files = Files.walk(source)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				if (Files.isDirectory(file) || file.getFileName().toString().equals("session.lock")) {
					continue;
				}
				zip.putNextEntry(new ZipEntry(source.relativize(file).toString().replace('\\', '/')));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
	}

	private static void prune(Path dir, int keep) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			List<Path> zips = files.filter(p -> p.toString().endsWith(".zip"))
					.sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
					.toList();
			for (int i = keep; i < zips.size(); i++) {
				Files.deleteIfExists(zips.get(i));
			}
		}
	}
}
