package dev.rubymod.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.platform.NativeImage;

import dev.rubymod.RubyMod;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Plays the How It Works films (H.264 videos in assets/rubymod/films/, made by MakeFilms): one at a time, looping,
 * decoded a few frames ahead on a worker thread and shown twenty a second through one texture. films.txt lists them:
 * "id frames file section" per item.
 */
public final class FilmPlayer {
	private FilmPlayer() {
	}

	public static final int W = MakeFilms.W;
	public static final int H = MakeFilms.H;
	private static final int FRAME_MS = 1000 / MakeFilms.FPS;
	/** A pause on the last frame before it plays again. */
	private static final int PAUSE_MS = 900;
	public static final Identifier TEXTURE = RubyMod.id("help_film");

	/** An item's film: how many frames, which video, and its Armory section. */
	public record Film(String id, int frames, String file, String section) {
	}

	private static Map<String, Film> films;

	/** Every item with a film, in the Armory's order. */
	public static synchronized Map<String, Film> films() {
		if (films == null) {
			Map<String, Film> out = new LinkedHashMap<>();
			try (var in = Minecraft.getInstance().getResourceManager().open(RubyMod.id("films/films.txt"));
					var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String[] f = line.trim().split(" ", 4);
					if (f.length == 4) {
						out.put(f[0], new Film(f[0], Integer.parseInt(f[1]), f[2], f[3]));
					}
				}
			} catch (Exception e) {
				RubyMod.LOGGER.warn("No help films", e);
			}
			films = Collections.unmodifiableMap(out);
		}
		return films;
	}

	public static boolean has(String id) {
		return id != null && films().containsKey(id);
	}

	private record Frame(int index, int[] pixels) {
	}

	/** The end of a play-through (then it pauses, and starts again). */
	private static final Frame END = new Frame(-1, null);

	private static DynamicTexture texture;
	private static volatile Film playing;
	private static Thread worker;
	private static ArrayBlockingQueue<Frame> ready = new ArrayBlockingQueue<>(4);
	private static final ArrayBlockingQueue<int[]> SPARE = new ArrayBlockingQueue<>(8);
	private static long nextAt;
	private static int shown = -1;

	/** Start playing this one (from the beginning). */
	public static void play(Film film) {
		if (film == null || film == playing) {
			return;
		}
		stop();
		if (texture == null) {
			texture = new DynamicTexture(() -> "rubymod help film", W, H, false);
			Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
		}
		texture.getPixels().fillRect(0, 0, W, H, 0xFF101216);
		texture.upload();
		playing = film;
		shown = -1;
		nextAt = 0;
		ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(4);
		ready = queue;
		byte[] bytes;
		try (var in = Minecraft.getInstance().getResourceManager().open(RubyMod.id("films/" + film.file()))) {
			bytes = in.readAllBytes();
		} catch (Exception e) {
			RubyMod.LOGGER.warn("Couldn't open the film {}", film.file(), e);
			return;
		}
		Thread t = new Thread(() -> decode(film, bytes, queue), "rubymod film " + film.id());
		t.setDaemon(true);
		t.start();
		worker = t;
	}

	public static void stop() {
		playing = null;
		Thread t = worker;
		worker = null;
		if (t != null) {
			t.interrupt();
		}
	}

	/** Every frame drawn: time for the next picture? Returns how far through it is (0 to 1), or -1 before the first. */
	public static float tick() {
		long now = System.currentTimeMillis();
		Film film = playing;
		if (film == null || texture == null || now < nextAt) {
			return film == null || shown < 0 ? -1 : shown / (float) Math.max(1, film.frames() - 1);
		}
		Frame f = ready.poll();
		if (f == END) {
			nextAt = now + PAUSE_MS;
		} else if (f != null) {
			NativeImage img = texture.getPixels();
			MemoryUtil.memIntBuffer(img.getPointer(), W * H).put(f.pixels());
			texture.upload();
			SPARE.offer(f.pixels());
			shown = f.index();
			nextAt = now + FRAME_MS;
		}
		return shown < 0 ? -1 : shown / (float) Math.max(1, film.frames() - 1);
	}

	private static void decode(Film film, byte[] bytes, ArrayBlockingQueue<Frame> queue) {
		try {
			while (playing == film) {
				FrameGrab grab = FrameGrab.createFrameGrab(new ByteBufferSeekableByteChannel(ByteBuffer.wrap(bytes), bytes.length));
				Picture rgb = null;
				Transform transform = null;
				int index = 0;
				Picture yuv;
				while (playing == film && (yuv = grab.getNativeFrame()) != null) {
					if (rgb == null || rgb.getWidth() != yuv.getWidth() || rgb.getHeight() != yuv.getHeight()) {
						rgb = Picture.create(yuv.getWidth(), yuv.getHeight(), ColorSpace.RGB);
						transform = ColorUtil.getTransform(yuv.getColor(), ColorSpace.RGB);
					}
					transform.transform(yuv, rgb);
					int[] out = SPARE.poll();
					if (out == null) {
						out = new int[W * H];
					}
					byte[] d = rgb.getPlaneData(0);
					int stride = rgb.getWidth() * 3;
					int w = Math.min(W, rgb.getWidth());
					int h = Math.min(H, rgb.getHeight());
					for (int y = 0; y < h; y++) {
						int src = y * stride;
						int dst = y * W;
						for (int x = 0; x < w; x++) {
							int r = d[src++] + 128;
							int g = d[src++] + 128;
							int b = d[src++] + 128;
							out[dst + x] = 0xFF000000 | b << 16 | g << 8 | r;
						}
					}
					while (playing == film && !queue.offer(new Frame(index, out), 100, TimeUnit.MILLISECONDS)) {
						// (waiting for it to be shown)
					}
					index++;
				}
				while (playing == film && !queue.offer(END, 100, TimeUnit.MILLISECONDS)) {
					// (waiting)
				}
			}
		} catch (InterruptedException e) {
			// stopped
		} catch (Exception e) {
			RubyMod.LOGGER.warn("Couldn't play the film {}", film.file(), e);
		}
	}

	/** What's on the list, for the help screen: the ids in order. */
	public static List<String> ids() {
		return new ArrayList<>(films().keySet());
	}
}
