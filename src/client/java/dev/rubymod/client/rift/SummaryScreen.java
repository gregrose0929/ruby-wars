package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The end of a Ruby Rift run: how far you got and how long it took, then a card for each
 * runner: kills, damage, MVP crown, every relic they held, the combos they switched on and
 * any legendary weapon they forged.
 */
public class SummaryScreen extends Screen {
	private record Card(String name, int kills, int damage, boolean mvp, List<String[]> relics, List<String> combos, List<String> legends) {
	}

	private String title = "RUN OVER";
	private String where = "";
	private String time = "";
	private int rooms;
	private int elites;
	private int bosses;
	private int keys;
	private final List<Card> cards = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	SummaryScreen(List<String> lines) {
		super(Component.literal("Run Summary"));
		for (String line : lines) {
			String[] f = line.split("[|]", -1);
			try {
				switch (f[0]) {
					case "title" -> title = f[1];
					case "info" -> {
						where = f[1];
						time = f[2];
						rooms = Integer.parseInt(f[3]);
						elites = Integer.parseInt(f[4]);
						bosses = Integer.parseInt(f[5]);
						keys = Integer.parseInt(f[6]);
					}
					case "runner" -> {
						List<String[]> relics = new ArrayList<>();
						if (!f[5].isEmpty()) {
							for (String r : f[5].split(",")) {
								relics.add(r.split(";"));
							}
						}
						cards.add(new Card(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4].equals("1"), relics,
								f[6].isEmpty() ? List.of() : List.of(f[6].split(",")), f.length > 7 && !f[7].isEmpty() ? List.of(f[7].split(",")) : List.of()));
					}
					default -> {
					}
				}
			} catch (RuntimeException e) {
				// A line we can't read is skipped.
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		long now = System.currentTimeMillis();
		boolean won = !title.equals("RUN OVER");
		g.fillGradient(0, 0, width, height, won ? 0xEC201008 : 0xEC140608, 0xF6060208);
		// Confetti for a win, slow embers for a loss.
		for (int i = 0; i < 60; i++) {
			double s = (now / (won ? 18.0 : 40.0) + i * 53) % height;
			int sx = (int) ((i * 97 + Math.sin(now / 700.0 + i) * 25) % width + width) % width;
			int c = won ? new int[] {0xC0FFC02E, 0xC0FF3D6E, 0xC066E0FF, 0xC06EE38A}[i % 4] : 0x60FF5030;
			g.fill(sx, (int) s, sx + 2, (int) s + 2, c);
		}
		g.pose().pushMatrix();
		g.pose().translate(width / 2.0F, 10);
		float pop = Math.min(1, (now - opened) / 400F);
		g.pose().scale(2.2F * pop, 2.2F * pop);
		g.centeredText(font, title, 0, 0, won ? 0xFFFFC02E : 0xFFFF5050);
		g.pose().popMatrix();
		g.centeredText(font, where + "   •   " + time, width / 2, 34, 0xFFE0D0E8);
		g.centeredText(font, rooms + " rooms   •   " + elites + " elites   •   " + bosses + " bosses" + (keys > 0 ? "   •   " + keys + "/3 Rift Keys" : ""),
				width / 2, 45, 0xFFB8A8C8);
		int n = Math.max(1, cards.size());
		int gap = 8;
		int cw = Math.min(180, (width - 20 - gap * (n - 1)) / n);
		int ch = height - 90;
		int x0 = width / 2 - (cw * n + gap * (n - 1)) / 2;
		for (int i = 0; i < cards.size(); i++) {
			Card c = cards.get(i);
			int x = x0 + i * (cw + gap);
			int y = 60 + (int) Math.max(0, 30 - (now - opened - i * 150) / 8.0);
			int frame = c.mvp() ? ((now / 250) % 2 == 0 ? 0xFFFFC02E : 0xFFFFE680) : 0xFF603848;
			g.fill(x, y, x + cw, y + ch, 0xE0100810);
			g.outline(x, y, cw, ch, frame);
			int ty = y + 6;
			g.centeredText(font, (c.mvp() ? "♔ " : "") + c.name() + (c.mvp() ? " ♔" : ""), x + cw / 2, ty, c.mvp() ? 0xFFFFC02E : 0xFFFFFFFF);
			ty += 10;
			if (c.mvp()) {
				g.centeredText(font, "MVP", x + cw / 2, ty, 0xFFFFE680);
				ty += 10;
			}
			g.text(font, "Kills", x + 8, ty + 2, 0xFFB8A8C8, false);
			g.text(font, String.valueOf(c.kills()), x + cw - 8 - font.width(String.valueOf(c.kills())), ty + 2, 0xFFFFFFFF, false);
			ty += 11;
			g.text(font, "Damage", x + 8, ty + 2, 0xFFB8A8C8, false);
			g.text(font, String.valueOf(c.damage()), x + cw - 8 - font.width(String.valueOf(c.damage())), ty + 2, 0xFFFF7A2E, false);
			ty += 14;
			g.text(font, "Relics (" + c.relics().size() + ")", x + 8, ty, 0xFFFF3D6E, false);
			ty += 10;
			int per = Math.max(1, (cw - 12) / 17);
			for (int k = 0; k < c.relics().size(); k++) {
				String[] r = c.relics().get(k);
				int rx = x + 6 + (k % per) * 17;
				int ry = ty + (k / per) * 17;
				if (ry > y + ch - 60) {
					break;
				}
				int tier = RiftClient.tierColor(r.length > 1 ? r[1] : "COMMON");
				g.outline(rx, ry, 16, 16, tier);
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(r[0]))), rx, ry);
			}
			ty += ((c.relics().size() + per - 1) / per) * 17 + 4;
			if (!c.combos().isEmpty() && ty < y + ch - 30) {
				g.text(font, "Combos", x + 8, ty, 0xFFFF9A2E, false);
				ty += 10;
				for (String combo : c.combos()) {
					if (ty > y + ch - 20) {
						break;
					}
					g.text(font, font.plainSubstrByWidth("⚡ " + combo, cw - 14), x + 10, ty, 0xFFFFC080, false);
					ty += 9;
				}
			}
			if (!c.legends().isEmpty() && ty < y + ch - 20) {
				g.text(font, "Legendary", x + 8, ty + 2, 0xFFFFC02E, false);
				ty += 12;
				for (String l : c.legends()) {
					if (ty > y + ch - 10) {
						break;
					}
					g.text(font, font.plainSubstrByWidth("★ " + l, cw - 14), x + 10, ty, 0xFFFFE680, false);
					ty += 9;
				}
			}
		}
		g.centeredText(font, "Esc to close. Back to the Threshold for another run!", width / 2, height - 14, 0xFF8A7A9A);
	}
}
