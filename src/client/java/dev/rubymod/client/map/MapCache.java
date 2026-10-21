package dev.rubymod.client.map;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Colors of the terrain you've seen, one pixel per block, kept per chunk for the
 * minimap and the full map. Chunks around you are (re)scanned a few per tick, so
 * the map fills in as you explore and keeps up with what's built.
 */
public final class MapCache {
	private static final Map<Long, int[]> CHUNKS = new HashMap<>();
	private static final Map<Long, Long> SCANNED = new HashMap<>();
	private static String dimension = "";
	private static int cursor;

	private MapCache() {
	}

	/** ARGB color of the block column at (x, z), or 0 if unknown. */
	public static int color(int x, int z) {
		int[] chunk = CHUNKS.get(ChunkPos.pack(x >> 4, z >> 4));
		return chunk == null ? 0 : chunk[(z & 15) * 16 + (x & 15)];
	}

	private static java.nio.file.Path savedAs;

	/** Chunks known (for tests). */
	public static int size() {
		return CHUNKS.size();
	}

	/** Where this world's explored map is kept: one file per server (or singleplayer world) and dimension. */
	private static java.nio.file.Path file(Minecraft mc, String dim) {
		String world;
		if (mc.getSingleplayerServer() != null) {
			world = "local-" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else if (mc.getCurrentServer() != null) {
			world = "server-" + mc.getCurrentServer().ip;
		} else {
			world = "unknown";
		}
		String safe = (world + "-" + dim).replaceAll("[^A-Za-z0-9._-]", "_");
		return mc.gameDirectory.toPath().resolve("rubywars").resolve("maps").resolve(safe + ".bin.gz");
	}

	/** Save what's been explored (called on leaving a world or dimension). */
	public static void save() {
		if (savedAs == null || CHUNKS.isEmpty()) {
			return;
		}
		try {
			java.nio.file.Files.createDirectories(savedAs.getParent());
			try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(savedAs)))) {
				out.writeInt(CHUNKS.size());
				for (var e : CHUNKS.entrySet()) {
					out.writeLong(e.getKey());
					for (int c : e.getValue()) {
						out.writeInt(c);
					}
				}
			}
		} catch (java.io.IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the map to {}", savedAs, e);
		}
	}

	private static void load(java.nio.file.Path path) {
		CHUNKS.clear();
		SCANNED.clear();
		savedAs = path;
		if (!java.nio.file.Files.exists(path)) {
			return;
		}
		try (var in = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(path)))) {
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				long key = in.readLong();
				int[] colors = new int[256];
				for (int j = 0; j < 256; j++) {
					colors[j] = in.readInt();
				}
				CHUNKS.put(key, colors);
			}
		} catch (java.io.IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the map from {}", path, e);
		}
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			if (savedAs != null) {
				save();
				savedAs = null;
				dimension = "";
				CHUNKS.clear();
				SCANNED.clear();
			}
			return;
		}
		String dim = level.dimension().identifier().toString();
		if (!dim.equals(dimension)) {
			save();
			dimension = dim;
			load(file(mc, dim));
		}
		if (level.getGameTime() % 6000 == 0) {
			save(); // every 5 minutes, in case the game crashes
		}
		int pcx = mc.player.blockPosition().getX() >> 4;
		int pcz = mc.player.blockPosition().getZ() >> 4;
		int radius = 9;
		int side = radius * 2 + 1;
		long now = System.currentTimeMillis();
		// Walk the square around the player a few chunks per tick; rescan each chunk every few seconds.
		for (int n = 0; n < 6; n++) {
			cursor = (cursor + 1) % (side * side);
			int cx = pcx + cursor % side - radius;
			int cz = pcz + cursor / side - radius;
			long key = ChunkPos.pack(cx, cz);
			Long last = SCANNED.get(key);
			if (last != null && now - last < 4000) {
				continue;
			}
			if (!level.hasChunk(cx, cz)) {
				continue;
			}
			CHUNKS.put(key, scan(level, cx, cz));
			SCANNED.put(key, now);
		}
		if (CHUNKS.size() > 30000) {
			CHUNKS.clear();
			SCANNED.clear();
		}
	}

	private static int[] scan(ClientLevel level, int cx, int cz) {
		int[] out = new int[256];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int x = (cx << 4) + dx;
				int z = (cz << 4) + dz;
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				pos.set(x, y, z);
				var state = level.getBlockState(pos);
				MapColor mc = state.getMapColor(level, pos);
				int rgb = mc == MapColor.NONE ? 0x202020 : mc.col;
				// Height shading against the column to the north, like vanilla maps; water darkens with depth.
				int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1;
				float shade = y > north ? 1.12F : y < north ? 0.84F : 1.0F;
				if (!state.getFluidState().isEmpty()) {
					int depth = 0;
					while (depth < 12 && !level.getBlockState(pos.move(0, -1, 0)).getFluidState().isEmpty()) {
						depth++;
					}
					shade = 1.05F - depth * 0.04F;
				}
				int r = Math.min(255, (int) (((rgb >> 16) & 255) * shade));
				int g = Math.min(255, (int) (((rgb >> 8) & 255) * shade));
				int b = Math.min(255, (int) ((rgb & 255) * shade));
				out[dz * 16 + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		return out;
	}
}
