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

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return;
		}
		String dim = level.dimension().identifier().toString();
		if (!dim.equals(dimension)) {
			CHUNKS.clear();
			SCANNED.clear();
			dimension = dim;
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
