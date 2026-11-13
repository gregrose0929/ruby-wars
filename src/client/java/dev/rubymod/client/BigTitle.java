package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The big announcements ("WAVE 5", "RED WINS!") and the line under them. Minecraft's own titles are a fixed size and
 * run off both sides of the screen when they're long; these are drawn to fit whatever screen they're on: the title
 * shrinks until it fits, and the line under it wraps onto two or three lines. They also keep clear of the how-to card.
 */
public class BigTitle implements HudElement {
	private static final int IN_MS = 350;
	private static final int STAY_MS = 3300;
	private static final int OUT_MS = 900;
	private static String title = "";
	private static String subtitle = "";
	private static int color = 0xFFFFFFFF;
	private static long shownAt;

	/** A title is on screen right now. */
	public static boolean active() {
		return !(title.isEmpty() && subtitle.isEmpty()) && System.currentTimeMillis() - shownAt < IN_MS + STAY_MS + OUT_MS;
	}

	public static void show(MorePayloads.Title t) {
		title = t.title();
		subtitle = t.subtitle();
		color = 0xFF000000 | t.color();
		shownAt = System.currentTimeMillis();
	}

	/** Break a line into as few lines as fit {@code width} (at whole words). */
	private static List<String> wrap(Font font, String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && font.width(line + " " + word) > width) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line.append(line.length() > 0 ? " " : "").append(word);
			}
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		long age = System.currentTimeMillis() - shownAt;
		if (age > IN_MS + STAY_MS + OUT_MS || title.isEmpty() && subtitle.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		float alpha = age < IN_MS ? age / (float) IN_MS : age > IN_MS + STAY_MS ? 1 - (age - IN_MS - STAY_MS) / (float) OUT_MS : 1;
		if (alpha <= 0.04F) {
			return;
		}
		int w = g.guiWidth();
		int room = w - 24;
		// The title: as big as fits, up to three times the size of ordinary text.
		float scale = title.isEmpty() ? 0 : Math.max(1.0F, Math.min(3.0F, room / (float) Math.max(1, font.width(title))));
		int titleH = Math.round(9 * scale);
		// The line under it: half again as big as ordinary text if that fits on one line, ordinary size and wrapped if not.
		float subScale = font.width(subtitle) * 1.5F <= room ? 1.5F : 1.0F;
		List<String> lines = subtitle.isEmpty() ? List.of() : wrap(font, subtitle, Math.round(room / subScale));
		int lineH = Math.round(11 * subScale);
		int total = titleH + (lines.isEmpty() ? 0 : 5 + lines.size() * lineH);
		// A quarter of the way down, or under the how-to card when that's up (on a narrow screen the card steps aside
		// instead); never so low it reaches the hotbar or the ammo panel.
		int y = HowToCard.narrow(w) ? g.guiHeight() / 4 : Math.max(g.guiHeight() / 4, HowToCard.bottom() + 8);
		y = Math.max(4, Math.min(y, g.guiHeight() - 104 - total));
		if (!title.isEmpty()) {
			Ui.heading(g, title, w / 2, y, Ui.alpha(color, alpha), scale, true);
		}
		int ly = y + titleH + 5;
		for (String line : lines) {
			Ui.heading(g, line, w / 2, ly, Ui.alpha(0xFFFFFFFF, alpha), subScale, true);
			ly += lineH;
		}
	}
}
