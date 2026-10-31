package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.rubymod.client.ui.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The Altar of Echoes (in the Threshold, between runs): spend Rift Shards on new
 * loadouts and new relics, pick your loadout, set the Heat, or turn on the Daily Rift.
 */
public class ThresholdScreen extends Screen {
	private static final String[] HEAT = {"No Heat", "Hardened", "Elite Swarm", "No Respite", "Bloodthirst", "Apex"};
	private static final String[] HEAT_ABOUT = {"The Rift as it is.", "Monsters have 30% more health.", "Every fight ends with an elite.",
			"No healing after rooms (the Arsenal revives at half health).", "Monsters hit 40% harder and move faster.", "Bosses have 50% more health."};

	private int shards;
	private int heat;
	private int maxHeat;
	private boolean daily;
	private List<String> dailyTop = List.of();
	private List<String> deepest = List.of();
	private final List<String[]> loadouts = new ArrayList<>();
	private final List<String[]> abilities = new ArrayList<>();
	private final List<String[]> relics = new ArrayList<>();
	private final List<String[]> mutators = new ArrayList<>();
	private final List<String[]> companions = new ArrayList<>();
	/** 0: the run (loadout, relics, Heat); 1: extras (companion, mutators). */
	private int page;
	private final List<int[]> buttons = new ArrayList<>();
	private final List<String[]> actions = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	ThresholdScreen(List<String> lines) {
		super(Component.literal("The Altar of Echoes"));
		update(lines);
	}

	void update(List<String> lines) {
		loadouts.clear();
		abilities.clear();
		relics.clear();
		mutators.clear();
		companions.clear();
		for (String line : lines) {
			String[] f = line.split("[|]", -1);
			switch (f[0]) {
				case "shards" -> shards = Integer.parseInt(f[1]);
				case "heat" -> {
					heat = Integer.parseInt(f[1]);
					maxHeat = Integer.parseInt(f[2]);
				}
				case "daily" -> {
					daily = f[1].equals("on");
					dailyTop = f.length > 2 && !f[2].isEmpty() ? List.of(f[2].split(";")) : List.of();
				}
				case "loadout" -> loadouts.add(f);
				case "ability" -> abilities.add(f);
				case "relic" -> relics.add(f);
				case "mutator" -> mutators.add(f);
				case "deepest" -> deepest = f.length > 1 && !f[1].isEmpty() ? List.of(f[1].split(";")) : List.of();
				case "companion" -> companions.add(f);
				default -> {
				}
			}
		}
	}

	/** For autotests: turn to the Extras page (and back). */
	public void flip() {
		page = page == 3 ? 0 : 3;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static final String[] TABS = {"Loadout", "Relics", "Heat & Daily", "Extras"};

	private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int color, boolean on, int mouseX, int mouseY, String kind, String id) {
		Ui.button(g, label, x, y, w, h, mouseX, mouseY, on ? Ui.lighten(color, 0.1F) : Ui.darken(color, 0.6F), true);
		if (on) {
			g.outline(x - 1, y - 1, w + 2, h + 2, Ui.TEXT);
		}
		buttons.add(new int[] {x, y, w, h});
		actions.add(new String[] {kind, id});
	}

	/** One row: a card with an icon, a name and a line about it, and room for a button on the right. Returns true when hovered. */
	private boolean row(GuiGraphicsExtractor g, int x, int y, int w, int h, String icon, String title, int titleColor, String about, int accent,
			boolean on, int mouseX, int mouseY) {
		boolean over = Ui.inside(mouseX, mouseY, x, y, w, h);
		Ui.card(g, x, y, w, h, on ? accent : Ui.darken(accent, 0.5F), Ui.hover("altar:" + title, over));
		g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(icon))), x + 5, y + (h - 16) / 2);
		Ui.text(g, title, x + 26, y + (h > 24 ? 6 : (h - 8) / 2), w - 96, titleColor);
		if (h > 24) {
			Ui.text(g, about, x + 26, y + 17, w - 32 - 70, Ui.DIM);
		}
		return over;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		buttons.clear();
		actions.clear();
		long now = System.currentTimeMillis();
		float in = Ui.ease(opened, 260);
		Ui.dim(g, width, height, in);
		for (int i = 0; i < 24; i++) {
			double s = (now / 30.0 + i * 83) % height;
			int sx = (int) ((i * 157 + Math.sin(now / 800.0 + i) * 30) % width + width) % width;
			g.fill(sx, height - (int) s, sx + 1, height - (int) s + 1, 0x70C85BFF);
		}
		int pw = Math.min(440, width - 16);
		int px = (width - pw) / 2;
		int py = 46;
		int ph = height - py - 22;
		Ui.emblem(g, "rift", px, 6, 24);
		Ui.heading(g, "Altar of Echoes", px + 30, 9, Ui.TEXT, 1.5F, false);
		button(g, px + pw - 62, 8, 62, 16, "Codex", Ui.GOLD, false, mouseX, mouseY, "codex", "open");
		String shardText = shards + " Rift Shards";
		int sx = px + pw - 70 - font.width(shardText) - 12;
		Ui.icon(g, Ui.Icon.GEM, sx, 11);
		Ui.text(g, shardText, sx + 12, 12, Ui.GOLD);
		Ui.panel(g, px, py, pw, ph);
		int tx = px + 8;
		for (int i = 0; i < TABS.length; i++) {
			int tw = font.width(TABS[i]) + 18;
			Ui.tab(g, TABS[i], tx, py - 15, tw, page == i, mouseX, mouseY);
			buttons.add(new int[] {tx, py - 15, tw, 16});
			actions.add(new String[] {"page", String.valueOf(i)});
			tx += tw + 3;
		}
		List<String> tip = null;
		int x0 = px + 10;
		int w0 = pw - 20;
		int top = py + 10;
		int bottom = py + ph - 8;
		switch (page) {
			case 0 -> {
				// Loadouts on the left, abilities on the right.
				int lw = w0 * 3 / 5;
				Ui.section(g, "Loadout", x0, top, lw);
				int y = top + 13;
				int rowH = Math.max(22, Math.min(30, (bottom - y) / Math.max(1, loadouts.size()) - 2));
				for (String[] l : loadouts) {
					boolean selected = l[6].equals("selected");
					boolean locked = l[6].equals("locked");
					if (row(g, x0, y, lw, rowH, l[3], l[2], locked ? Ui.MUTED : Ui.TEXT, l[4], Ui.RIFT, selected, mouseX, mouseY)) {
						tip = List.of(l[2], l[4]);
					}
					if (selected) {
						Ui.tag(g, "Selected", x0 + lw - 62, y + (rowH - 11) / 2, Ui.GREEN);
					} else {
						button(g, x0 + lw - 62, y + (rowH - 14) / 2, 56, 14, locked ? "Unlock " + l[5] : "Select", locked ? Ui.GOLD : Ui.RIFT, false, mouseX,
								mouseY, "loadout", l[1]);
					}
					y += rowH + 2;
				}
				int ax = x0 + lw + 10;
				int aw = w0 - lw - 10;
				String key = net.minecraft.client.Minecraft.getInstance().options.keySwapOffhand.getTranslatedKeyMessage().getString();
				Ui.section(g, "Ability (" + key + ")", ax, top, aw);
				int bw = (aw - 4) / 2;
				int ay = top + 13;
				String about = null;
				for (int i = 0; i < abilities.size(); i++) {
					String[] a = abilities.get(i);
					int bx = ax + (i % 2) * (bw + 4);
					int by = ay + (i / 2) * 18;
					boolean on = a.length > 5 && a[5].equals("selected");
					button(g, bx, by, bw, 16, a[2], Ui.GOLD, on, mouseX, mouseY, "pick_ability", a[1]);
					if (Ui.inside(mouseX, mouseY, bx, by, bw, 16) || on && about == null) {
						about = a[2] + ": " + a[4];
					}
				}
				int dy = ay + (abilities.size() + 1) / 2 * 18 + 6;
				if (about != null) {
					Ui.inset(g, ax, dy, aw, Math.max(24, bottom - dy));
					Ui.wrap(g, about, ax + 6, dy + 6, aw - 12, Ui.DIM, bottom - 2);
				}
			}
			case 1 -> {
				// Relics to unlock, as a grid.
				Ui.section(g, "New relics", x0, top, w0);
				int cols = 2;
				int cw = (w0 - 6) / cols;
				int rows = (relics.size() + cols - 1) / cols;
				int rh = Math.max(24, Math.min(32, (bottom - top - 14) / Math.max(1, rows) - 2));
				for (int i = 0; i < relics.size(); i++) {
					String[] r = relics.get(i);
					int x = x0 + (i % cols) * (cw + 6);
					int y = top + 13 + (i / cols) * (rh + 2);
					boolean owned = r[6].equals("owned");
					int tier = RiftClient.tierColor(r.length > 7 ? r[7] : "RARE");
					if (row(g, x, y, cw, rh, r[3], r[2], tier, r[4], tier, owned, mouseX, mouseY)) {
						tip = List.of(r[2], r[4]);
					}
					if (owned) {
						Ui.tag(g, "Owned", x + cw - 46, y + (rh - 11) / 2, Ui.GREEN);
					} else {
						int price = Integer.parseInt(r[5]);
						button(g, x + cw - 56, y + (rh - 14) / 2, 50, 14, "Buy " + price, shards >= price ? Ui.GOLD : 0xFF6A3036, false, mouseX, mouseY, "unlock",
								r[1]);
					}
				}
			}
			case 2 -> {
				// Heat on the left, the Daily Rift and the Endless Descent on the right.
				int hw = w0 / 2 - 5;
				Ui.section(g, "Heat", x0, top, hw);
				int y = top + 13;
				for (int h = 0; h <= 5; h++) {
					boolean locked = h > maxHeat;
					if (locked) {
						Ui.inset(g, x0, y, hw, 16);
						Ui.icon(g, Ui.Icon.LOCK, x0 + 4, y + 4);
						Ui.text(g, HEAT[h] + " (win at Heat " + (h - 1) + ")", x0 + 16, y + 4, hw - 20, Ui.MUTED);
					} else {
						button(g, x0, y, hw, 16, h + ". " + HEAT[h], h == 0 ? 0xFF8A93A0 : h < 3 ? Ui.AMBER : Ui.RED, h == heat, mouseX, mouseY, "heat",
								String.valueOf(h));
					}
					y += 19;
				}
				y += 4;
				Ui.inset(g, x0, y, hw, Math.max(26, bottom - y));
				Ui.wrap(g, HEAT_ABOUT[heat] + (heat > 0 ? " Shards x" + String.format("%.2f", 1 + 0.25 * heat) + "." : ""), x0 + 6, y + 6, hw - 12, Ui.DIM,
						bottom - 2);
				int dx = x0 + hw + 10;
				Ui.section(g, "Daily Rift", dx, top, hw);
				y = top + 13;
				button(g, dx, y, hw, 16, daily ? "On: today's run for all" : "Off: click to turn on", Ui.RIFT, daily, mouseX, mouseY, "daily", "toggle");
				y += 22;
				Ui.text(g, "Today's best", dx, y, Ui.DIM);
				y += 11;
				if (dailyTop.isEmpty()) {
					Ui.text(g, "Nobody yet", dx + 6, y, Ui.MUTED);
					y += 10;
				}
				for (String s : dailyTop) {
					Ui.text(g, s, dx + 6, y, hw - 6, Ui.TEXT);
					y += 10;
				}
				y += 8;
				Ui.section(g, "Endless Descent", dx, y, hw);
				y += 13;
				Ui.text(g, "Win a run, then keep going down!", dx, y, hw, Ui.DIM);
				y += 11;
				if (deepest.isEmpty()) {
					Ui.text(g, "Nobody yet", dx + 6, y, Ui.MUTED);
				}
				for (String s : deepest) {
					Ui.text(g, s, dx + 6, y, hw - 6, Ui.TEXT);
					y += 10;
				}
			}
			default -> tip = extras(g, mouseX, mouseY, x0, w0, top, bottom);
		}
		if (tip != null) {
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
		}
		g.centeredText(font, "Choices here are for the next run. Step into the portal when everyone's ready.", width / 2, height - 14, Ui.MUTED);
	}

	/** The Extras tab: your companion (left) and the team's mutators (right). Returns a tooltip, or null. */
	private List<String> extras(GuiGraphicsExtractor g, int mouseX, int mouseY, int x0, int w0, int top, int bottom) {
		int colW = w0 / 2 - 5;
		int x1 = x0;
		int x2 = x0 + colW + 10;
		List<String> tip = null;
		Ui.section(g, "Your companion", x1, top, colW);
		int y = top + 13;
		int rh = Math.max(22, Math.min(28, (bottom - y) / Math.max(1, companions.size()) - 2));
		for (String[] c : companions) {
			boolean on = c.length > 5 && c[5].equals("selected");
			if (row(g, x1, y, colW, rh, c[3], c[2], Ui.TEXT, c[4], Ui.BLUE, on, mouseX, mouseY)) {
				tip = List.of(c[2], c[4]);
			}
			button(g, x1 + colW - 62, y + (rh - 14) / 2, 56, 14, on ? "With you" : "Choose", Ui.BLUE, on, mouseX, mouseY, "companion", c[1]);
			y += rh + 2;
		}
		Ui.section(g, "Mutators (whole team)", x2, top, colW);
		y = top + 13;
		rh = Math.max(22, Math.min(28, (bottom - y) / Math.max(1, mutators.size()) - 2));
		for (String[] m : mutators) {
			boolean on = m.length > 5 && m[5].equals("on");
			if (row(g, x2, y, colW, rh, m[3], m[2], Ui.TEXT, m[4], Ui.RIFT, on, mouseX, mouseY)) {
				tip = List.of(m[2], m[4]);
			}
			button(g, x2 + colW - 44, y + (rh - 14) / 2, 38, 14, on ? "On" : "Off", Ui.RIFT, on, mouseX, mouseY, "mutator", m[1]);
			y += rh + 2;
		}
		return tip;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < buttons.size(); i++) {
			int[] b = buttons.get(i);
			if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
				if (actions.get(i)[0].equals("page")) {
					page = Integer.parseInt(actions.get(i)[1]);
					if (minecraft != null && minecraft.player != null) {
						minecraft.player.playSound(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 0.6F, 1.1F);
					}
					return true;
				}
				ClientPlayNetworking.send(new MorePayloads.RiftChoice(actions.get(i)[0], actions.get(i)[1]));
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.1F);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
