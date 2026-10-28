package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	private static final String[] SECTIONS = {"relics", "combos", "legendaries", "bosses", "charms"};
	private static final String[] NAMES = {"Relics", "Combos", "Legends", "Bosses", "Charms"};

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
		for (String line : lines) {
			String[] f = line.split("[|]", -1);
			if (f[0].equals("count") && f.length >= 4) {
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
		g.fillGradient(0, 0, width, height, 0xEC140A10, 0xF6060208);
		// Pages of an old book: a warm glow behind the grid.
		g.fillGradient(width / 2 - 170, 30, width / 2 + 170, height - 20, 0x30C27BFF, 0x10FF3D6E);
		g.pose().pushMatrix();
		g.pose().translate(width / 2.0F, 8);
		g.pose().scale(1.5F, 1.5F);
		g.centeredText(font, "✦ THE CODEX ✦", 0, 0, 0xFFFFC02E);
		g.pose().popMatrix();
		int total = 0;
		int got = 0;
		for (int[] c : counts.values()) {
			got += c[0];
			total += c[1];
		}
		g.centeredText(font, "Everything you've found in the Rift: " + got + " of " + total, width / 2, 22, 0xFFB8A8C8);
		// Tabs.
		tabs.clear();
		int tw = Math.min(80, (width - 20) / SECTIONS.length);
		int tx = width / 2 - tw * SECTIONS.length / 2;
		for (int i = 0; i < SECTIONS.length; i++) {
			int[] c = counts.getOrDefault(SECTIONS[i], new int[] {0, 0});
			boolean on = i == tab;
			boolean hover = mouseX >= tx && mouseX < tx + tw - 2 && mouseY >= 34 && mouseY < 50;
			g.fill(tx, 34, tx + tw - 2, 50, on ? 0xF0402030 : hover ? 0xD0281828 : 0xC0181018);
			g.outline(tx, 34, tw - 2, 16, on ? 0xFFFFC02E : 0xFF503848);
			g.centeredText(font, NAMES[i] + " " + c[0] + "/" + c[1], tx + tw / 2 - 1, 38, on ? 0xFFFFE680 : 0xFFD0C0D8);
			tabs.add(new int[] {tx, 34, tw - 2, 16, i});
			tx += tw;
		}
		// The grid.
		List<Entry> page = entries.stream().filter(e -> e.section().equals(SECTIONS[tab])).toList();
		int cell = 26;
		int cols = Math.max(1, Math.min(14, (width - 40) / cell));
		int gx = width / 2 - cols * cell / 2;
		int gy = 58;
		Entry hovered = null;
		for (int i = 0; i < page.size(); i++) {
			Entry e = page.get(i);
			int x = gx + (i % cols) * cell;
			int y = gy + (i / cols) * cell;
			if (y + cell > height - 14) {
				break;
			}
			boolean hover = mouseX >= x && mouseX < x + cell - 2 && mouseY >= y && mouseY < y + cell - 2;
			if (hover) {
				hovered = e;
			}
			int c = color(e.tier());
			if (e.found()) {
				float shine = (float) (0.5 + 0.5 * Math.sin(now / 400.0 + i));
				g.fill(x, y, x + cell - 2, y + cell - 2, hover ? 0xF0302838 : 0xE0181420);
				g.outline(x, y, cell - 2, cell - 2, (int) (150 + 105 * shine) << 24 | c & 0xFFFFFF);
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(e.icon()))), x + (cell - 18) / 2, y + (cell - 18) / 2);
			} else {
				g.fill(x, y, x + cell - 2, y + cell - 2, hover ? 0xE0201820 : 0xC0100C10);
				g.outline(x, y, cell - 2, cell - 2, 0xFF302830);
				g.centeredText(font, "?", x + cell / 2 - 1, y + cell / 2 - 5, 0xFF504050);
			}
		}
		if (hovered != null) {
			List<Component> tip = new ArrayList<>();
			if (hovered.found()) {
				tip.add(Component.literal(hovered.title()).withColor(color(hovered.tier()) & 0xFFFFFF).withStyle(net.minecraft.ChatFormatting.BOLD));
				tip.addAll(RelicScreen.wrap(hovered.about(), 0xE0E0E0));
			} else {
				tip.add(Component.literal("???").withColor(0x8A7A9A).withStyle(net.minecraft.ChatFormatting.BOLD));
				tip.add(Component.literal(switch (SECTIONS[tab]) {
					case "combos" -> "A combo you haven't switched on yet.";
					case "legendaries" -> "A legendary nobody's forged yet. Try fusing weapons at the Arsenal.";
					case "bosses" -> "A boss you haven't beaten yet.";
					case "charms" -> "A charm you haven't found yet.";
					default -> "A relic you haven't held yet.";
				}).withColor(0xA898B8));
			}
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
		g.centeredText(font, "Click a tab. Hover a tile to read it. Esc to close.", width / 2, height - 12, 0xFF8A7A9A);
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
