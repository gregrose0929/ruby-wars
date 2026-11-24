package dev.rubymod.client.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import dev.rubymod.RubyMod;
import dev.rubymod.client.ClientState;
import dev.rubymod.net.MorePayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

/**
 * The season's keepsake map: when a season ends, a picture of the island as you explored it, with every base you know of
 * (yours green, a teammate's blue, the others red, each Heart a dot), saved to your screenshots folder and shown on the
 * wrap-up. Only after the season's over, and only what you saw yourself: it's a memento, not a look at anyone's base.
 */
public final class Keepsake {
	private Keepsake() {
	}

	public static final Identifier TEXTURE = RubyMod.id("keepsake_map");
	private static DynamicTexture texture;
	/** The picture now showing (its size), and where it was saved (null: none this time). */
	public static int shownW;
	public static int shownH;
	public static Path savedTo;
	private static final int MAX = 512;

	/** Make (or remake) the picture for this season. Returns whether there was anything to draw. */
	public static boolean make(String seasonTitle) {
		Minecraft mc = Minecraft.getInstance();
		savedTo = null;
		Map<Long, int[]> chunks = MapCache.overworldChunks(mc);
		if (chunks.isEmpty()) {
			return false;
		}
		// Framed on the bases (with room round them), or on the middle of the island if there are none: the map you've
		// explored may also reach far-off places (party games, the Rift) that would shrink the island to a speck.
		MorePayloads.MapSync map = ClientState.map;
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		if (map != null) {
			for (MorePayloads.ClaimInfo c : map.claims()) {
				minX = Math.min(minX, c.x() - c.radius());
				minZ = Math.min(minZ, c.z() - c.radius());
				maxX = Math.max(maxX, c.x() + c.radius());
				maxZ = Math.max(maxZ, c.z() + c.radius());
			}
		}
		if (minX == Integer.MAX_VALUE) {
			minX = -150;
			minZ = -150;
			maxX = 150;
			maxZ = 150;
		}
		// Square, at least 300 blocks across, at most 2,000, with a margin.
		int cx = (minX + maxX) / 2;
		int cz = (minZ + maxZ) / 2;
		int half = Math.min(1000, Math.max(150, Math.max(maxX - minX, maxZ - minZ) / 2 + 80));
		minX = cx - half;
		maxX = cx + half;
		minZ = cz - half;
		maxZ = cz + half;
		int spanX = maxX - minX;
		int spanZ = maxZ - minZ;
		double step = Math.max(1, Math.max(spanX, spanZ) / (double) MAX);
		int w = Math.max(1, (int) Math.ceil(spanX / step));
		int h = Math.max(1, (int) Math.ceil(spanZ / step));
		NativeImage img = new NativeImage(w, h, false);
		for (int pz = 0; pz < h; pz++) {
			for (int px = 0; px < w; px++) {
				int bx = (int) Math.floor(minX + px * step);
				int bz = (int) Math.floor(minZ + pz * step);
				int[] chunk = chunks.get(ChunkPos.pack(bx >> 4, bz >> 4));
				int c = chunk == null ? 0 : chunk[(bz & 15) * 16 + (bx & 15)];
				img.setPixel(px, pz, c == 0 ? 0xFF15191D : c);
			}
		}
		if (map != null) {
			for (MorePayloads.ClaimInfo c : map.claims()) {
				int color = c.relation() == 0 ? 0xFF5BE37D : c.relation() == 1 ? 0xFF55AAFF : 0xFFFF5050;
				int x0 = (int) ((c.x() - c.radius() - minX) / step);
				int z0 = (int) ((c.z() - c.radius() - minZ) / step);
				int x1 = (int) ((c.x() + c.radius() + 1 - minX) / step);
				int z1 = (int) ((c.z() + c.radius() + 1 - minZ) / step);
				for (int x = x0; x <= x1; x++) {
					dot(img, x, z0, color);
					dot(img, x, z1, color);
				}
				for (int z = z0; z <= z1; z++) {
					dot(img, x0, z, color);
					dot(img, x1, z, color);
				}
				int hx = (int) ((c.x() - minX) / step);
				int hz = (int) ((c.z() - minZ) / step);
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						dot(img, hx + dx, hz + dz, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? 0xFF101010 : color);
					}
				}
			}
		}
		// Shown on the wrap-up.
		if (texture != null) {
			texture.close();
		}
		texture = new DynamicTexture(() -> "rubymod keepsake map", w, h, false);
		NativeImage pixels = texture.getPixels();
		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				pixels.setPixel(x, z, img.getPixel(x, z));
			}
		}
		texture.upload();
		mc.getTextureManager().register(TEXTURE, texture);
		shownW = w;
		shownH = h;
		// And kept, in the screenshots folder.
		try {
			Path dir = mc.gameDirectory.toPath().resolve("screenshots");
			Files.createDirectories(dir);
			Path file = dir.resolve("ruby-wars-" + seasonTitle.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("-+$", "").toLowerCase() + ".png");
			img.writeToFile(file);
			savedTo = file;
		} catch (Exception e) {
			RubyMod.LOGGER.warn("Couldn't save the season's keepsake map", e);
		} finally {
			img.close();
		}
		return true;
	}

	private static void dot(NativeImage img, int x, int z, int color) {
		if (x >= 0 && z >= 0 && x < img.getWidth() && z < img.getHeight()) {
			img.setPixel(x, z, color);
		}
	}

	/** Is there a picture to show? */
	public static boolean ready() {
		return texture != null && shownW > 0;
	}
}
