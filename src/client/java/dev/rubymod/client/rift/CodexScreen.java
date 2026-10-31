package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.client.ui.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The Codex: a collection book of everything found in the Rift, in tabs (Relics, Combos,
 * Legendaries, Bosses, Charms). Found entries show their icon in their colour; the rest
 * are dark "?" tiles, waiting to be discovered.
 */
public class CodexScreen extends Screen {
	private record Entry(String section, String title, String icon, String tier, String about, boolean found) {
	}

	private static final String[] SECTIONS = {"relics", "combos", "legendaries", "bosses", "charms", "rewards"};
	private static final String[] NAMES = {"Relics", "Combos", "Legends", "Bosses", "Charms", "Rewards"};
	private int found;
	private int nextAt = 10;
	private final List<String[]> perks = new ArrayList<>();

	private final List<Entry> entries = new ArrayList<>();
	private final Map<String, int[]> counts = new HashMap<>();
	private final List<int[]> tabs = new ArrayList<>();
	private int tab;
	private final long opened = System.currentTimeMillis();

	CodexScreen(List<String> lines) {
		super(Component.literal("The Codex"));
		update(lines);
	}

	void update(List<String> lines) {
		entries.clear();
		counts.clear();
		perks.clear();
		for (String line : lines) {
			String[] f = line.split("[|]", -1);
			if (f[0].equals("milestone") && f.length >= 3) {
				found = Integer.parseInt(f[1]);
				nextAt = Integer.parseInt(f[2]);
			} else if (f[0].equals("perk") && f.length >= 4) {
				perks.add(f);
			} else if (f[0].equals("count") && f.length >= 4) {
				counts.put(f[1], new int[] {Integer.parseInt(f[2]), Integer.parseInt(f[3])});
			} else if (f[0].equals("entry") && f.length >= 8) {
				entries.add(new Entry(f[1], f[3], f[4], f[5], f[6], f[7].equals("1")));
			}
		}
	}

	/** For autotests: turn to a tab. */
	public void tab(int i) {
		tab = i;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static int color(String tier) {
		return switch (tier) {
			case "BOSS" -> 0xFFFF4040;
			case "CHARM" -> 0xFFC27BFF;
			default -> RiftClient.tierColor(tier);
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		long now = System.currentTimeMillis();
		float in = Ui.ease(opened, 260);
		Ui.dim(g, width, height, in);
		int total = 0;
		int got = 0;
		for (int[] c : counts.values()) {
			got += c[0];
			total += c[1];
		}
		int pw = Math.min(400, width - 16);
		int px = (width - pw) / 2;
		int py = 46;
		int ph = height - py - 22;
		Ui.emblem(g, "rift", px, 6, 24);
		Ui.heading(g, "The Codex", px + 30, 9, Ui.TEXT, 1.5F, false);
		String count = got + " of " + total + " found";
		Ui.text(g, count, px + pw - font.width(count), 13, Ui.GOLD);
		Ui.panel(g, px, py, pw, ph);
		// Tabs.
		tabs.clear();
		// The counts go on the tabs when they all fit.
		int needed = 0;
		for (int i = 0; i < SECTIONS.length; i++) {
			int[] c = counts.getOrDefault(SECTIONS[i], new int[] {found, nextAt});
			needed += font.width(SECTIONS[i].equals("rewards") ? NAMES[i] : NAMES[i] + " " + c[0] + "/" + c[1]) + 14;
		}
		boolean withCounts = needed <= pw - 12;
		int tx = px + 6;
		for (int i = 0; i < SECTIONS.length; i++) {
			int[] c = counts.getOrDefault(SECTIONS[i], new int[] {found, nextAt});
			String label = SECTIONS[i].equals("rewards") || !withCounts ? NAMES[i] : NAMES[i] + " " + c[0] + "/" + c[1];
			int tw = font.width(label) + 12;
			Ui.tab(g, label, tx, py - 15, tw, i == tab, mouseX, mouseY);
			tabs.add(new int[] {tx, py - 15, tw, 16, i});
			tx += tw + 2;
		}
		if (SECTIONS[tab].equals("rewards")) {
			rewards(g, px + 12, py + 12, pw - 24);
			g.centeredText(font, "Click a tab. Esc to close.", width / 2, height - 14, Ui.MUTED);
			return;
		}
		// The grid.
		List<Entry> page = entries.stream().filter(e -> e.section().equals(SECTIONS[tab])).toList();
		int cell = 24;
		int cols = Math.max(1, (pw - 20) / cell);
		int gx = px + (pw - cols * cell) / 2;
		int gy = py + 10;
		Entry hovered = null;
		for (int i = 0; i < page.size(); i++) {
			Entry e = page.get(i);
			int x = gx + (i % cols) * cell;
			int y = gy + (i / cols) * cell;
			if (y + cell > py + ph - 6) {
				break;
			}
			boolean hover = Ui.inside(mouseX, mouseY, x, y, cell - 2, cell - 2);
			if (hover) {
				hovered = e;
			}
			int c = color(e.tier());
			Ui.inset(g, x, y, cell - 2, cell - 2);
			if (e.found()) {
				g.fill(x + 1, y + 1, x + cell - 3, y + cell - 3, Ui.alpha(c, 0.15F + 0.1F * (float) Math.sin(now / 400.0 + i)));
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(e.icon()))), x + (cell - 18) / 2, y + (cell - 18) / 2);
			} else {
				Ui.icon(g, Ui.Icon.LOCK, x + (cell - 11) / 2, y + (cell - 11) / 2);
			}
			if (hover) {
				g.outline(x, y, cell - 2, cell - 2, e.found() ? c : Ui.DIM);
			}
		}
		if (hovered != null) {
			List<String> tip = new ArrayList<>();
			if (hovered.found()) {
				tip.add(hovered.title());
				tip.add(hovered.about());
			} else {
				tip.add("Not found yet");
				tip.add(switch (SECTIONS[tab]) {
					case "combos" -> "A combo you haven't switched on yet.";
					case "legendaries" -> "A legendary nobody's forged yet. Try fusing weapons at the Arsenal.";
					case "bosses" -> "A boss you haven't beaten yet.";
					case "charms" -> "A charm you haven't found yet.";
					default -> "A relic you haven't held yet.";
				});
			}
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
		}
		g.centeredText(font, "Hover a tile to read it. Esc to close.", width / 2, height - 14, Ui.MUTED);
	}

	/** The Rewards tab: the next milestone, and what each finished section gives you. */
	private void rewards(GuiGraphicsExtractor g, int x, int y, int w) {
		Ui.section(g, "Every 10 things you find: +50 Rift Shards", x, y, w);
		y += 14;
		Ui.bar(g, x, y, w, 10, Ui.glide("codex:next", (found % 10) / 10F), Ui.GOLD);
		y += 13;
		Ui.text(g, found + " found, next reward at " + nextAt, x, y, Ui.DIM);
		y += 18;
		Ui.section(g, "Finish a whole section for a perk in every run", x, y, w);
		y += 14;
		for (String[] p : perks) {
			boolean done = p[3].equals("1");
			Ui.card(g, x, y, w, 20, done ? Ui.GREEN : 0xFF3A3F48, 0);
			Ui.icon(g, done ? Ui.Icon.CHECK : Ui.Icon.LOCK, x + 6, y + 6);
			String name = p[1].substring(0, 1).toUpperCase() + p[1].substring(1);
			Ui.text(g, name, x + 20, y + 7, done ? Ui.GREEN : Ui.DIM);
			Ui.text(g, p[2], x + 96, y + 7, w - 102, done ? Ui.TEXT : Ui.MUTED);
			y += 23;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int[] t : tabs) {
			if (event.x() >= t[0] && event.x() < t[0] + t[2] && event.y() >= t[1] && event.y() < t[1] + t[3]) {
				tab = t[4];
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 0.7F, 1.2F);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
