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
 * "How it works": a little film of each thing being used (a Keycard Door opening for its Keycard, the Builder's Wand
 * running out a wall, a Sentry Turret firing on a Practice Dummy), playing over and over, with what it's for under it.
 * The films are real gameplay, filmed by the "clips" client test and packed by tools/make_clips.py into
 * textures/gui/clips/ (one sprite sheet per item; clips.txt lists them).
 */
public class HowItWorksScreen extends Screen {
	/** Frames are this big in the sheet, COLS to a row (as tools/make_clips.py makes them). */
	private static final int FRAME_W = 256;
	private static final int FRAME_H = 144;
	private static final int COLS = 8;
	/** Ten frames a second (they were filmed a frame every two ticks), and a pause at the end before it plays again. */
	private static final int FRAME_MS = 100;
	private static final int PAUSE_MS = 900;

	private record Clip(String id, int frames, ItemStack icon, String name) {
	}

	private final List<Clip> clips = new ArrayList<>();
	private final List<int[]> rows = new ArrayList<>();
	private int picked;
	private long started = System.currentTimeMillis();
	private final long opened = System.currentTimeMillis();

	public HowItWorksScreen() {
		super(Component.literal("How It Works"));
		try (var in = Minecraft.getInstance().getResourceManager().open(RubyMod.id("textures/gui/clips/clips.txt"));
				var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] f = line.trim().split(" ");
				if (f.length == 2) {
					var item = BuiltInRegistries.ITEM.getValue(RubyMod.id(f[0]));
					ItemStack stack = new ItemStack(item);
					clips.add(new Clip(f[0], Integer.parseInt(f[1]), stack, stack.getHoverName().getString()));
				}
			}
		} catch (Exception e) {
			RubyMod.LOGGER.warn("No help clips", e);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int panelW() {
		return Math.min(width - 16, 420);
	}

	private int panelH() {
		return Math.min(height - 16, 290);
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
		// The list down the left.
		int listW = 120;
		rows.clear();
		int y = py + 26;
		for (int i = 0; i < clips.size(); i++) {
			Clip c = clips.get(i);
			boolean on = i == picked;
			boolean over = Ui.inside(mouseX, mouseY, px + 8, y, listW, 20);
			g.fill(px + 8, y, px + 8 + listW, y + 20, on ? 0x55E0203A : over ? 0x22FFFFFF : 0x11FFFFFF);
			g.item(c.icon(), px + 10, y + 2);
			Ui.text(g, c.name(), px + 30, y + 6, listW - 24, on ? Ui.TEXT : Ui.DIM);
			rows.add(new int[] {px + 8, y, listW, 20});
			y += 22;
		}
		// The film, and what it's for.
		Clip c = clips.get(Math.min(picked, clips.size() - 1));
		int fx = px + listW + 16;
		int fw = pw - listW - 24;
		int fh = fw * 9 / 16;
		if (fh > ph - 90) {
			fh = ph - 90;
			fw = fh * 16 / 9;
		}
		long t = System.currentTimeMillis() - started;
		long loop = (long) c.frames() * FRAME_MS + PAUSE_MS;
		int frame = (int) Math.min(c.frames() - 1, (t % loop) / FRAME_MS);
		int u = (frame % COLS) * FRAME_W;
		int v = (frame / COLS) * FRAME_H;
		int sheetW = FRAME_W * COLS;
		int sheetH = FRAME_H * ((c.frames() + COLS - 1) / COLS);
		int fy = py + 26;
		g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF0E1014);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, RubyMod.id("textures/gui/clips/" + c.id() + ".png"), fx, fy, u, v, fw, fh,
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
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < rows.size(); i++) {
			int[] r = rows.get(i);
			if (Ui.inside(event.x(), event.y(), r[0], r[1], r[2], r[3])) {
				if (picked != i) {
					picked = i;
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
