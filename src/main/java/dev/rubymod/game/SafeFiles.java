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
