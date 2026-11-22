package dev.rubymod.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Saving a file safely: written to a copy beside it first, then swapped in. A crash or power cut part-way through leaves
 * the old file whole (never half-written or empty).
 */
public final class SafeFiles {
	private SafeFiles() {
	}

	public static void write(Path file, String text) throws IOException {
		write(file, text.getBytes(StandardCharsets.UTF_8));
	}

	/** A compressed NBT file (like a player's saved inventory), the same safe way. */
	public static void writeNbt(Path file, net.minecraft.nbt.CompoundTag tag) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		net.minecraft.nbt.NbtIo.writeCompressed(tag, out);
		write(file, out.toByteArray());
	}

	/** Lines of text, one per line. */
	public static void writeLines(Path file, java.util.List<String> lines) throws IOException {
		write(file, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
	}

	/**
	 * A save file that couldn't be read: a copy is kept beside it (name.unreadable-date) before the game starts afresh
	 * and saves over it, so what was in it isn't lost for good.
	 */
	public static void keepUnreadable(Path file, Exception why) {
		try {
			if (file != null && Files.exists(file)) {
				Path copy = file.resolveSibling(file.getFileName() + ".unreadable-"
						+ java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
				Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
				dev.rubymod.RubyMod.LOGGER.error("Couldn't read {} ({}). A copy is kept at {}; starting afresh.", file, why.toString(), copy);
			}
		} catch (IOException e) {
			dev.rubymod.RubyMod.LOGGER.error("Couldn't keep a copy of unreadable {}", file, e);
		}
	}

	public static void write(Path file, byte[] bytes) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) {
			Files.createDirectories(dir);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
