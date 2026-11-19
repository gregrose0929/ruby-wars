package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The season wrap-up: who won, a few friendly awards (top fighter, richest base, biggest Heart...), and how your
 * own season went. Shown to everyone when a season ends; /rubywars recap brings it back.
 */
public class SeasonRecapScreen extends Screen {
	private record Award(String title, String who, String detail, String item) {
	}

	private static final int GOLD = 0xFFF5C542;
	private final MorePayloads.SeasonRecap recap;
	private final List<Award> awards = new ArrayList<>();
	private final List<String[]> mine = new ArrayList<>();
	private final long opened = System.currentTimeMillis();
	private int[] close = {0, 0, 0, 0};

	SeasonRecapScreen(MorePayloads.SeasonRecap recap) {
		super(Component.literal(recap.title()));
		this.recap = recap;
		for (String a : recap.awards()) {
			String[] p = a.split("\\|", -1);
			if (p.length >= 4) {
				awards.add(new Award(p[0], p[1], p[2], p[3]));
			}
		}
		for (String m : recap.mine()) {
			String[] p = m.split("\\|", -1);
			if (p.length >= 2) {
				mine.add(p);
			}
		}
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.SeasonRecap.TYPE,
				(p, c) -> Minecraft.getInstance().gui.setScreen(new SeasonRecapScreen(p)));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 200));
		int w = Math.min(360, width - 24);
		int cols = w >= 330 ? 3 : w >= 220 ? 2 : 1;
		int cardH = 28;
		int rows = (awards.size() + cols - 1) / cols;
		int mineRows = (mine.size() + 1) / 2;
		int h = 68 + rows * (cardH + 3) + (mine.isEmpty() ? 0 : 18 + mineRows * 12) + 28;
		h = Math.min(h, height - 16);
		int x = (width - w) / 2;
		int y = Ui.centre(height, h) + Math.round((1 - Ui.ease(opened, 240)) * 12);
		Ui.card(g, x, y, w, h, GOLD, 0);

		Ui.heading(g, recap.title().toUpperCase(), x + w / 2, y + 10, Ui.DIM, 1.0F, true);
		boolean draw = recap.winner().isEmpty();
		float pop = 1.6F + 0.4F * (1 - Ui.ease(opened, 500));
		Ui.heading(g, draw ? "No survivors" : recap.winner() + " wins!", x + w / 2, y + 26, draw ? Ui.MUTED : GOLD, pop, true);
		if (!draw) {
			g.centeredText(font, "Champion of the season. Well played, everyone!", x + w / 2, y + 48, Ui.TEXT);
		} else {
			g.centeredText(font, "Nobody was left standing. Well played, everyone!", x + w / 2, y + 48, Ui.TEXT);
		}

		int cy = y + 64;
		int cw = (w - 20 - (cols - 1) * 6) / cols;
		for (int i = 0; i < awards.size(); i++) {
			Award a = awards.get(i);
			int ax = x + 10 + (i % cols) * (cw + 6);
			int ay = cy + (i / cols) * (cardH + 3);
			if (ay + cardH > y + h - 26) {
				break;
			}
			Ui.inset(g, ax, ay, cw, cardH);
			try {
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(a.item()))), ax + 4, ay + 6);
			} catch (RuntimeException ignored) {
				// An unknown icon just leaves a gap.
			}
			Ui.text(g, a.title(), ax + 24, ay + 2, cw - 26, Ui.DIM);
			Ui.text(g, a.who(), ax + 24, ay + 10, cw - 26, Ui.TEXT);
			Ui.text(g, a.detail(), ax + 24, ay + 19, cw - 26, Ui.MUTED);
		}
		int my = cy + rows * (cardH + 3) + 4;
		if (!mine.isEmpty() && my + 20 < y + h - 26) {
			Ui.section(g, "Your season", x + 10, my, w - 20);
			my += 14;
			int half = (w - 20) / 2;
			for (int i = 0; i < mine.size(); i++) {
				int lx = x + 10 + (i % 2) * half;
				int ly = my + (i / 2) * 12;
				if (ly + 10 > y + h - 26) {
					break;
				}
				String[] m = mine.get(i);
				Ui.text(g, m[0], lx, ly, half - 50, Ui.DIM);
				String v = m[1];
				g.text(font, v, lx + half - 8 - font.width(v), ly, i == 0 ? GOLD : Ui.TEXT, false);
			}
		}
		int bw = 90;
		close = new int[] {x + (w - bw) / 2, y + h - 24, bw, 16};
		Ui.button(g, "Close", close[0], close[1], close[2], close[3], mouseX, mouseY, GOLD, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (Ui.inside(event.x(), event.y(), close[0], close[1], close[2], close[3])) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubled);
	}
}
