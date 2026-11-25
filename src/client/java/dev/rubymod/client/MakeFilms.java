package dev.rubymod.client;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rational;

/**
 * Turns the frames the "clips" client test filmed (run/screenshots/autotest-clip-&lt;id&gt;-NNN.png and clips-index.txt)
 * into the How It Works films: one H.264 video per film, assets/rubymod/films/&lt;id&gt;.mp4, at {@link #W} x {@link #H}
 * and {@link #FPS} frames a second. films.txt beside them lists "id frames file section" per item in order (an item
 * sharing another's film names that film's file); films not filmed this time keep what they had.
 *
 * Run it with: ./gradlew makeFilms
 */
public final class MakeFilms {
	private MakeFilms() {
	}

	public static final int W = 640;
	public static final int H = 360;
	public static final int FPS = 20;
	/** The encoder's quality: lower is sharper (and bigger); each 6 halves or doubles the size. */
	private static final int QP = Integer.getInteger("films.qp", 28);

	public static void main(String[] args) throws Exception {
		Path shots = Path.of("run/screenshots");
		Path out = Path.of("src/main/resources/assets/rubymod/films");
		Files.createDirectories(out);
		Path listing = out.resolve("films.txt");
		Map<String, String[]> old = new LinkedHashMap<>();
		if (Files.exists(listing)) {
			for (String line : Files.readAllLines(listing, StandardCharsets.UTF_8)) {
				String[] f = line.trim().split(" ", 4);
				if (f.length == 4) {
					old.put(f[0], f);
				}
			}
		}
		Map<String, TreeMap<Integer, Path>> frames = new TreeMap<>();
		Pattern name = Pattern.compile("autotest-clip-(.+)-(\\d+)\\.png");
		try (var list = Files.list(shots)) {
			for (Path p : (Iterable<Path>) list::iterator) {
				Matcher m = name.matcher(p.getFileName().toString());
				if (m.matches()) {
					frames.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(Integer.parseInt(m.group(2)), p);
				}
			}
		}
		Path index = shots.resolve("clips-index.txt");
		List<String> lines = Files.exists(index) ? Files.readAllLines(index, StandardCharsets.UTF_8) : List.of();
		Map<String, String[]> made = new LinkedHashMap<>();
		long total = 0;
		for (String line : lines) {
			String[] f = line.trim().split(" ", 3);
			if (f.length == 3 && f[0].equals("film") && frames.containsKey(f[1])) {
				List<Path> shotsOf = new ArrayList<>(frames.get(f[1]).values());
				File file = out.resolve(f[1] + ".mp4").toFile();
				encode(shotsOf, file);
				total += file.length();
				made.put(f[1], new String[] {f[1], String.valueOf(shotsOf.size()), f[1] + ".mp4", f[2]});
				System.out.println(f[1] + ": " + shotsOf.size() + " frames, " + file.length() / 1024 + " KB");
			}
		}
		for (String line : lines) {
			String[] f = line.trim().split(" ", 4);
			if (f.length == 4 && f[0].equals("alias")) {
				String[] target = made.containsKey(f[2]) ? made.get(f[2]) : old.get(f[2]);
				if (target != null) {
					made.put(f[1], new String[] {f[1], target[1], target[2], f[3]});
				}
			}
		}
		// Keep the old order; new ones after.
		Map<String, String[]> all = new LinkedHashMap<>();
		for (var e : old.entrySet()) {
			all.put(e.getKey(), made.getOrDefault(e.getKey(), e.getValue()));
		}
		for (var e : made.entrySet()) {
			all.putIfAbsent(e.getKey(), e.getValue());
		}
		List<String> outLines = new ArrayList<>();
		for (String[] f : all.values()) {
			outLines.add(String.join(" ", f));
		}
		Files.write(listing, outLines, StandardCharsets.UTF_8);
		System.out.println(all.size() + " items, " + made.size() + " updated, " + total / 1024 + " KB of new film");
	}

	private static void encode(List<Path> shots, File file) throws Exception {
		SequenceEncoder enc = SequenceEncoder.createWithFps(NIOUtils.writableChannel(file), new Rational(FPS, 1));
		// jcodec's own encoder runs at a very high quality (QP 20): a little lower (QP) is about half the size, and looks the same here.
		var sinkField = SequenceEncoder.class.getDeclaredField("sink");
		sinkField.setAccessible(true);
		Object sink = sinkField.get(enc);
		var encoderField = sink.getClass().getDeclaredField("videoEncoder");
		encoderField.setAccessible(true);
		encoderField.set(sink, new org.jcodec.codecs.h264.H264Encoder(new org.jcodec.codecs.h264.encode.RateControl() {
			@Override
			public int startPicture(org.jcodec.common.model.Size size, int maxSize, org.jcodec.codecs.h264.io.model.SliceType type) {
				return QP;
			}

			@Override
			public int initialQpDelta() {
				return 0;
			}

			@Override
			public int accept(int bits) {
				return 0;
			}
		}));
		BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Picture pic = Picture.create(W, H, ColorSpace.RGB);
		byte[] data = pic.getPlaneData(0);
		for (Path p : shots) {
			BufferedImage src = ImageIO.read(p.toFile());
			int sw = src.getWidth();
			int sh = src.getHeight();
			int want = sw * 9 / 16;
			int top = want < sh ? (sh - want) / 2 : 0;
			int used = Math.min(sh, want);
			Graphics2D g = frame.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, W, H, 0, top, sw, top + used, null);
			g.dispose();
			int i = 0;
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					int rgb = frame.getRGB(x, y);
					data[i++] = (byte) (((rgb >> 16) & 0xFF) - 128);
					data[i++] = (byte) (((rgb >> 8) & 0xFF) - 128);
					data[i++] = (byte) ((rgb & 0xFF) - 128);
				}
			}
			enc.encodeNativeFrame(pic);
		}
		enc.finish();
	}
}
