package dev.rubymod.client;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.material.MapColor;

/**
 * The Toy Camera, on your side: when the server asks, hide the screen clutter for a moment, grab the picture, shrink it
 * to 128 x 128 in map colours and send it back to become a photo.
 */
public final class PhotoClient {
	private PhotoClient() {
	}

	private static int countdown = -1;
	private static int[] palette;
	private static byte[] paletteIds;

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.PhotoAsk.TYPE, (payload, context) -> {
			countdown = 1;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (countdown < 0 || --countdown > 0) {
				return;
			}
			countdown = -1;
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
				try {
					byte[] out = shrink(image);
					mc.execute(() -> ClientPlayNetworking.send(new MorePayloads.PhotoData(out)));
				} finally {
					image.close();
				}
			});
		});
	}

	private static byte[] shrink(com.mojang.blaze3d.platform.NativeImage image) {
		buildPalette();
		int w = image.getWidth();
		int h = image.getHeight();
		// A square from the top of the screen down to just above the hotbar.
		int side = Math.min(w, (int) (h * 0.8));
		int ox = (w - side) / 2;
		int oy = (int) (h * 0.04);
		byte[] out = new byte[128 * 128];
		for (int y = 0; y < 128; y++) {
			for (int x = 0; x < 128; x++) {
				// Average a little patch of the screen for each dot.
				int r = 0;
				int g = 0;
				int b = 0;
				int n = 0;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int px = ox + (int) ((x + dx * 0.5) * side / 128.0);
						int py = oy + (int) ((y + dy * 0.5) * side / 128.0);
						int c = image.getPixel(Math.min(w - 1, px), Math.min(h - 1, py));
						r += (c >> 16) & 255;
						g += (c >> 8) & 255;
						b += c & 255;
						n++;
					}
				}
				out[y * 128 + x] = nearest(r / n, g / n, b / n);
			}
		}
		return out;
	}

	private static void buildPalette() {
		if (palette != null) {
			return;
		}
		java.util.List<Integer> cols = new java.util.ArrayList<>();
		java.util.List<Byte> ids = new java.util.ArrayList<>();
		for (int id = 1; id < 64; id++) {
			MapColor mc = MapColor.byId(id);
			if (mc == null || mc.col == 0) {
				continue;
			}
			for (int br = 0; br < 4; br++) {
				int packed = (id << 2) | br;
				cols.add(MapColor.getColorFromPackedId(packed));
				ids.add((byte) packed);
			}
		}
		palette = cols.stream().mapToInt(Integer::intValue).toArray();
		paletteIds = new byte[ids.size()];
		for (int i = 0; i < ids.size(); i++) {
			paletteIds[i] = ids.get(i);
		}
	}

	private static byte nearest(int r, int g, int b) {
		int best = 0;
		int bd = Integer.MAX_VALUE;
		for (int i = 0; i < palette.length; i++) {
			int c = palette[i];
			int dr = ((c >> 16) & 255) - r;
			int dg = ((c >> 8) & 255) - g;
			int db = (c & 255) - b;
			int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return paletteIds[best];
	}
}
