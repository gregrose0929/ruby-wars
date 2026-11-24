package dev.rubymod.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.rubymod.RubyMod;
import dev.rubymod.client.ui.Ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * "How it works": a little film of every Armory item in use (a Keycard Door opening for its Keycard, a jeep driving
 * off, a Sentry Turret firing on a Practice Dummy...), playing over and over, with what it's for under it. Grouped
 * as the Armory is; scroll the list. The films are real gameplay, filmed by the "clips" client test (HelpFilms) and
 * packed by tools/make_clips.py into textures/gui/clips/ (one sprite sheet per film; clips.txt lists them).
 */
public class HowItWorksScreen extends Screen {
	/** Frames are this big in the sheet, COLS to a row (as tools/make_clips.py makes them). */
	private static final int FRAME_W = 320;
	private static final int FRAME_H = 180;
	private static final int COLS = 8;
	/** Ten frames a second, and a pause at the end before it plays again. */
	private static final int FRAME_MS = 100;
	private static final int PAUSE_MS = 900;
	private static final int ROW = 18;
	private static final int HEADING = 13;

	private record Clip(String id, int frames, String sheet, String section, ItemStack icon, String name) {
	}

	private final List<Clip> clips = new ArrayList<>();
	/** On screen: {x, y, w, h, index} for each item row. */
	private final List<int[]> rows = new ArrayList<>();
	private int picked;
	private int scroll;
	private int scrollMax;
	private long started = System.currentTimeMillis();
	private final long opened = System.currentTimeMillis();

	public HowItWorksScreen() {
		super(Component.literal("How It Works"));
		try (var in = Minecraft.getInstance().getResourceManager().open(RubyMod.id("textures/gui/clips/clips.txt"));
				var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] f = line.trim().split(" ", 4);
				if (f.length == 4) {
					ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(RubyMod.id(f[0])));
					clips.add(new Clip(f[0], Integer.parseInt(f[1]), f[2], f[3], stack, stack.getHoverName().getString()));
				}
			}
		} catch (Exception e) {
			RubyMod.LOGGER.warn("No help films", e);
		}
		// Grouped by section, in the order each section first comes.
		List<String> order = new ArrayList<>();
		for (Clip c : clips) {
			if (!order.contains(c.section())) {
				order.add(c.section());
			}
		}
		clips.sort(java.util.Comparator.comparingInt(c -> order.indexOf(c.section())));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int panelW() {
		return Math.min(width - 16, 460);
	}

	private int panelH() {
		return Math.min(height - 16, 300);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 180));
		int pw = panelW();
		int ph = panelH();
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		Ui.panel(g, px, py, pw, ph);
		Ui.heading(g, "How It Works", px + 10, py + 7, Ui.TEXT, 1.3F, false);
		if (clips.isEmpty()) {
			Ui.text(g, "No films here yet.", px + 10, py + 30, Ui.DIM);
			return;
		}
		// The list down the left, by section, scrolling.
		int listW = 128;
		int top = py + 24;
		int bottom = py + ph - 8;
		rows.clear();
		int total = 0;
		String section = null;
		for (Clip c : clips) {
			if (!c.section().equals(section)) {
				section = c.section();
				total += HEADING;
			}
			total += ROW;
		}
		scrollMax = Math.max(0, total - (bottom - top));
		scroll = Math.max(0, Math.min(scroll, scrollMax));
		g.enableScissor(px + 4, top, px + 10 + listW, bottom);
		int y = top - scroll;
		section = null;
		for (int i = 0; i < clips.size(); i++) {
			Clip c = clips.get(i);
			if (!c.section().equals(section)) {
				section = c.section();
				Ui.text(g, section, px + 8, y + 3, listW, Ui.GOLD);
				y += HEADING;
			}
			boolean on = i == picked;
			boolean over = Ui.inside(mouseX, mouseY, px + 8, y, listW, ROW - 2) && mouseY >= top && mouseY < bottom;
			g.fill(px + 8, y, px + 8 + listW, y + ROW - 2, on ? 0x55E0203A : over ? 0x22FFFFFF : 0x11FFFFFF);
			g.item(c.icon(), px + 9, y);
			Ui.text(g, c.name(), px + 28, y + 4, listW - 22, on ? Ui.TEXT : Ui.DIM);
			if (y + ROW > top && y < bottom) {
				rows.add(new int[] {px + 8, y, listW, ROW - 2, i});
			}
			y += ROW;
		}
		g.disableScissor();
		if (scrollMax > 0) {
			int barH = Math.max(16, (bottom - top) * (bottom - top) / total);
			int barY = top + (int) ((bottom - top - barH) * (scroll / (float) scrollMax));
			g.fill(px + 10 + listW, barY, px + 12 + listW, barY + barH, 0x66FFFFFF);
		}
		// The film, and what it's for.
		Clip c = clips.get(Math.min(picked, clips.size() - 1));
		int fx = px + listW + 18;
		int fw = pw - listW - 26;
		int fh = fw * 9 / 16;
		if (fh > ph - 96) {
			fh = ph - 96;
			fw = fh * 16 / 9;
		}
		long t = System.currentTimeMillis() - started;
		long loop = (long) c.frames() * FRAME_MS + PAUSE_MS;
		int frame = (int) Math.min(c.frames() - 1, (t % loop) / FRAME_MS);
		int u = (frame % COLS) * FRAME_W;
		int v = (frame / COLS) * FRAME_H;
		int sheetW = FRAME_W * COLS;
		int sheetH = FRAME_H * ((c.frames() + COLS - 1) / COLS);
		int fy = top + 2;
		g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF0E1014);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, RubyMod.id("textures/gui/clips/" + c.sheet() + ".png"), fx, fy, u, v, fw, fh,
				FRAME_W, FRAME_H, sheetW, sheetH);
		Ui.bar(g, fx, fy + fh + 2, fw, 2, (frame + 1) / (float) c.frames(), Ui.RUBY);
		ArmoryGuide.Entry guide = ArmoryGuide.get(BuiltInRegistries.ITEM.getKey(c.icon().getItem()).toString());
		int ty = fy + fh + 9;
		Ui.heading(g, c.name(), fx, ty, Ui.GOLD, 1.0F, false);
		if (guide != null) {
			Ui.wrap(g, guide.text(), fx, ty + 12, fw, Ui.TEXT, py + ph - 6);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(scrollMax, scroll - (int) (scrollY * 18)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int[] r : rows) {
			if (Ui.inside(event.x(), event.y(), r[0], r[1], r[2], r[3])) {
				if (picked != r[4]) {
					picked = r[4];
					started = System.currentTimeMillis();
					Ui.click();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	/** For the test client: show this one. */
	public void pick(String id) {
		for (int i = 0; i < clips.size(); i++) {
			if (clips.get(i).id().equals(id)) {
				picked = i;
				started = System.currentTimeMillis();
			}
		}
	}

	/** For the test client: jump the film to about this far through (0 to 1). */
	public void seek(float fraction) {
		Clip c = clips.isEmpty() ? null : clips.get(picked);
		if (c != null) {
			started = System.currentTimeMillis() - (long) (fraction * c.frames() * FRAME_MS);
		}
	}
}
