package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;

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
		Ui.dim(g, width, height, Ui.ease(opened, 260));
		// Confetti for a win, slow embers for a loss.
		for (int i = 0; i < 50; i++) {
			double s = (now / (won ? 18.0 : 40.0) + i * 53) % height;
			int sx = (int) ((i * 97 + Math.sin(now / 700.0 + i) * 25) % width + width) % width;
			int c = won ? new int[] {0xC0F5C542, 0xC0E0294A, 0xC04FA8FF, 0xC05BD27A}[i % 4] : 0x60E5484D;
			g.fill(sx, (int) s, sx + 2, (int) s + 2, c);
		}
		float pop = Ui.ease(opened, 400);
		String heading = won ? "Victory!" : "Run over";
		Ui.heading(g, heading, width / 2, 8, won ? Ui.GOLD : Ui.RED, 1.0F + 1.2F * pop, true);
		String line = where + ", " + time + ".  " + rooms + " rooms, " + elites + " elites, " + bosses + " bosses" + (keys > 0 ? ", " + keys + " of 3 Rift Keys" : "");
		g.centeredText(font, line, width / 2, 34, Ui.DIM);
		int n = Math.max(1, cards.size());
		int gap = 8;
		int cw = Math.min(170, (width - 20 - gap * (n - 1)) / n);
		int ch = height - 72;
		int x0 = width / 2 - (cw * n + gap * (n - 1)) / 2;
		for (int i = 0; i < cards.size(); i++) {
			Card c = cards.get(i);
			int x = x0 + i * (cw + gap);
			int y = 50 + Math.round((1 - Ui.ease(opened + 150L * i, 320)) * 24);
			Ui.card(g, x, y, cw, ch, c.mvp() ? Ui.mix(Ui.GOLD, 0xFFFFF0B0, Ui.pulse(900)) : Ui.RIFT, 0);
			int ty = y + 8;
			if (c.mvp()) {
				Ui.icon(g, Ui.Icon.CROWN, x + 7, ty - 1);
				Ui.tag(g, "MVP", x + cw - 30, ty - 2, Ui.GOLD);
			}
			Ui.text(g, c.name(), x + (c.mvp() ? 19 : 8), ty, cw - 44, Ui.TEXT);
			ty += 14;
			Ui.text(g, "Kills", x + 8, ty, Ui.DIM);
			Ui.text(g, String.valueOf(c.kills()), x + cw - 8 - font.width(String.valueOf(c.kills())), ty, Ui.TEXT);
			ty += 11;
			Ui.text(g, "Damage", x + 8, ty, Ui.DIM);
			Ui.text(g, String.valueOf(c.damage()), x + cw - 8 - font.width(String.valueOf(c.damage())), ty, Ui.AMBER);
			ty += 15;
			Ui.section(g, "Relics (" + c.relics().size() + ")", x + 8, ty, cw - 16);
			ty += 12;
			int per = Math.max(1, (cw - 12) / 18);
			for (int k = 0; k < c.relics().size(); k++) {
				String[] r = c.relics().get(k);
				int rx = x + 7 + (k % per) * 18;
				int ry = ty + (k / per) * 18;
				if (ry > y + ch - 60) {
					break;
				}
				Ui.inset(g, rx, ry, 17, 17);
				g.fill(rx + 1, ry + 1, rx + 16, ry + 16, Ui.alpha(RiftClient.tierColor(r.length > 1 ? r[1] : "COMMON"), 0.25F));
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(r[0]))), rx, ry);
			}
			ty += ((c.relics().size() + per - 1) / per) * 18 + 6;
			if (!c.combos().isEmpty() && ty < y + ch - 30) {
				Ui.section(g, "Combos", x + 8, ty, cw - 16);
				ty += 12;
				for (String combo : c.combos()) {
					if (ty > y + ch - 20) {
						break;
					}
					Ui.icon(g, Ui.Icon.BOLT, x + 8, ty - 1);
					Ui.text(g, combo, x + 20, ty, cw - 26, Ui.TEXT);
					ty += 10;
				}
				ty += 4;
			}
			if (!c.legends().isEmpty() && ty < y + ch - 20) {
				Ui.section(g, "Legendary", x + 8, ty, cw - 16);
				ty += 12;
				for (String l : c.legends()) {
					if (ty > y + ch - 10) {
						break;
					}
					Ui.icon(g, Ui.Icon.STAR, x + 8, ty - 1);
					Ui.text(g, l, x + 20, ty, cw - 26, Ui.GOLD);
					ty += 10;
				}
			}
		}
		g.centeredText(font, "Esc to close. Back to the Threshold for another run!", width / 2, height - 14, Ui.MUTED);
	}
}
