package dev.rubymod.client;

import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The how-to card: a game's rules in three pictures and three sentences, at the top of the screen for its first
 * twelve seconds (the server sends it the first few times you play each game).
 */
public class HowToCard implements HudElement {
	private static final int SHOW_MS = 12_000;
	private static String title = "";
	private static List<String> lines = List.of();
	private static long shownAt;

	public static void show(MorePayloads.HowTo card) {
		title = card.title();
		lines = card.lines();
		shownAt = System.currentTimeMillis();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		long age = System.currentTimeMillis() - shownAt;
		Minecraft mc = Minecraft.getInstance();
		if (lines.isEmpty() || age > SHOW_MS || mc.gui.screen() != null) {
			return;
		}
		int w = Math.min(250, g.guiWidth() - 20);
		int rowH = 22;
		int h = 22 + lines.size() * rowH + 6;
		// Slides down into place, and back up at the end.
		float in = Math.min(1.0F, age / 300.0F);
		float out = Math.min(1.0F, (SHOW_MS - age) / 400.0F);
		float shown = Math.min(in, out);
		int x = g.guiWidth() / 2 - w / 2;
		int y = 26 - Math.round((1 - shown) * (h + 30));
		Ui.panel(g, x, y, w, h);
		Ui.heading(g, "How to play: " + title, g.guiWidth() / 2, y + 7, Ui.GOLD, 1.0F, true);
		for (int i = 0; i < lines.size(); i++) {
			String[] f = lines.get(i).split("[|]", 2);
			int ry = y + 22 + i * rowH;
			Ui.inset(g, x + 8, ry, 18, 18);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[0]))), x + 9, ry + 1);
			Ui.wrap(g, f.length > 1 ? f[1] : "", x + 32, ry + (mc.font.width(f.length > 1 ? f[1] : "") > w - 42 ? 0 : 5), w - 42, Ui.TEXT, ry + rowH);
		}
		// The time left, as a thin bar along the bottom.
		Ui.bar(g, x + 8, y + h - 5, w - 16, 2, 1.0F - age / (float) SHOW_MS, Ui.GOLD);
	}
}
