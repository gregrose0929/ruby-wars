package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

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
 * Co-op Horde: the Armory by the Ruby Core. Spend the team's rubies on gear, sorted into
 * tabs. Locked things say which wave unlocks them.
 */
public class HordeShopScreen extends Screen {
	private static final String[][] TABS = {{"WEAPONS", "Weapons"}, {"AMMO", "Ammo"}, {"BUILD", "Build"}, {"DEFENSES", "Defenses"},
			{"POWER", "Power"}, {"SUPPORT", "Support"}};
	private static int tab;

	private int bank;
	private int cleared;
	/** id, category, name, icon, price, wave, about. */
	private List<String[]> entries = List.of();
	private final List<int[]> tabBoxes = new ArrayList<>();
	private final List<int[]> cardBoxes = new ArrayList<>();
	private final List<String> cardIds = new ArrayList<>();
	private int[] readyButton;
	private boolean ready;

	private HordeShopScreen() {
		super(Component.literal("Horde Armory"));
	}

	/** Open the shop, or refresh it if it's already open (the bank changed). */
	public static void show(MorePayloads.HordeShopSync sync) {
		Minecraft mc = Minecraft.getInstance();
		HordeShopScreen screen;
		if (sync.entries().isEmpty()) {
			// The game ended (or you left): close it.
			if (mc.gui.screen() instanceof HordeShopScreen) {
				mc.gui.setScreen(null);
			}
			return;
		} else if (mc.gui.screen() instanceof HordeShopScreen open) {
			screen = open;
		} else if (sync.open()) {
			screen = new HordeShopScreen();
			mc.gui.setScreen(screen);
		} else {
			return;
		}
		screen.bank = sync.bank();
		screen.cleared = sync.cleared();
		screen.entries = sync.entries().stream().map(s -> s.split("[|]", 7)).filter(f -> f.length == 7).toList();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static ItemStack icon(String id) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		int panelW = Math.min(width - 20, 420);
		int left = (width - panelW) / 2;
		int top = 12;
		g.centeredText(font, "HORDE ARMORY", width / 2, top, 0xFFFFC040);
		g.centeredText(font, "Team bank: " + bank + " rubies   •   Waves cleared: " + cleared + "   •   Kills and waves fill the bank", width / 2, top + 12, 0xFFFF6070);
		// Tabs.
		tabBoxes.clear();
		int tabY = top + 28;
		int tabW = panelW / TABS.length;
		for (int i = 0; i < TABS.length; i++) {
			int x = left + i * tabW;
			boolean on = i == tab;
			boolean hover = mouseX >= x && mouseX < x + tabW - 2 && mouseY >= tabY && mouseY < tabY + 16;
			g.fill(x, tabY, x + tabW - 2, tabY + 16, on ? 0xF0A03040 : hover ? 0xE0402830 : 0xD0201418);
			g.outline(x, tabY, tabW - 2, 16, on ? 0xFFFF6070 : 0xFF603038);
			g.centeredText(font, font.plainSubstrByWidth(TABS[i][1], tabW - 6), x + (tabW - 2) / 2, tabY + 4, 0xFFFFFFFF);
			tabBoxes.add(new int[] {x, tabY, tabW - 2, 16});
		}
		// Cards for this tab.
		cardBoxes.clear();
		cardIds.clear();
		int y0 = tabY + 22;
		int count = (int) entries.stream().filter(f -> f[1].equals(TABS[tab][0])).count();
		int cols = panelW >= 330 ? 3 : 2;
		int rows = Math.max(1, (count + cols - 1) / cols);
		int cardW = (panelW - (cols - 1) * 4) / cols;
		int cardH = Math.max(22, Math.min(34, (height - 36 - y0) / rows - 4));
		String tooltip = null;
		String[] tipEntry = null;
		int n = 0;
		for (String[] f : entries) {
			if (!f[1].equals(TABS[tab][0])) {
				continue;
			}
			int x = left + (n % cols) * (cardW + 4);
			int y = y0 + (n / cols) * (cardH + 4);
			n++;
			int price = Integer.parseInt(f[4]);
			int wave = Integer.parseInt(f[5]);
			boolean locked = cleared < wave;
			boolean afford = bank >= price;
			boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
			g.fill(x, y, x + cardW, y + cardH, locked ? 0xD0101214 : hover ? 0xF0303A44 : 0xE0182028);
			g.outline(x, y, cardW, cardH, locked ? 0xFF33383E : hover ? 0xFFFFFFFF : afford ? 0xFFD8A030 : 0xFF703030);
			g.item(icon(f[3]), x + 4, y + (cardH - 16) / 2);
			int ty = y + cardH / 2 - 9;
			g.text(font, font.plainSubstrByWidth(f[2], cardW - 26), x + 24, ty, locked ? 0xFF6A727A : 0xFFFFFFFF, false);
			String cost = locked ? "After wave " + wave : price + " rubies";
			g.text(font, cost, x + 24, ty + 10, locked ? 0xFF6A727A : afford ? 0xFFFFC040 : 0xFFE05050, false);
			cardBoxes.add(new int[] {x, y, cardW, cardH});
			cardIds.add(f[0]);
			if (hover) {
				tooltip = f[6];
				tipEntry = f;
			}
		}
		// Ready for the next wave.
		int bw = 200;
		int bx = (width - bw) / 2;
		int by = height - 26;
		readyButton = new int[] {bx, by, bw, 16};
		boolean over = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 16;
		g.fill(bx, by, bx + bw, by + 16, over ? 0xFFD8A030 : 0xC0A07820);
		g.centeredText(font, ready ? "You're ready! Waiting for the others" : "I'm ready: start the next wave", width / 2, by + 4, 0xFFFFFFFF);
		if (tipEntry != null) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(tipEntry[2]).withStyle(net.minecraft.ChatFormatting.GOLD));
			for (var line : font.split(Component.literal(tooltip), 220)) {
				StringBuilder b = new StringBuilder();
				line.accept((index, style, codePoint) -> {
					b.appendCodePoint(codePoint);
					return true;
				});
				lines.add(Component.literal(b.toString()).withStyle(net.minecraft.ChatFormatting.GRAY));
			}
			int wave = Integer.parseInt(tipEntry[5]);
			lines.add(cleared < wave ? Component.literal("Unlocks after wave " + wave).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
					: Component.literal(tipEntry[4] + " rubies: click to buy").withStyle(net.minecraft.ChatFormatting.YELLOW));
			g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
	}

	private static boolean inside(MouseButtonEvent event, int[] box) {
		return box != null && event.x() >= box[0] && event.x() < box[0] + box[2] && event.y() >= box[1] && event.y() < box[1] + box[3];
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < tabBoxes.size(); i++) {
			if (inside(event, tabBoxes.get(i))) {
				tab = i;
				return true;
			}
		}
		for (int i = 0; i < cardBoxes.size(); i++) {
			if (inside(event, cardBoxes.get(i))) {
				ClientPlayNetworking.send(new MorePayloads.HordeBuy(cardIds.get(i)));
				return true;
			}
		}
		if (!ready && inside(event, readyButton) && minecraft != null && minecraft.player != null) {
			ready = true;
			minecraft.player.connection.sendCommand("horde ready");
			return true;
		}
		return super.mouseClicked(event, doubled);
	}
}
