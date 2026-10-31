package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.rubymod.client.ui.Ui;

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
	private final long opened = System.currentTimeMillis();

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
		Ui.dim(g, width, height, Ui.ease(opened, 240));
		int panelW = Math.min(width - 16, 430);
		int left = (width - panelW) / 2;
		Ui.emblem(g, "horde", left, 6, 24);
		Ui.heading(g, "Horde Armory", left + 30, 9, Ui.TEXT, 1.5F, false);
		Ui.text(g, "Kills and cleared waves fill the bank", left + 30, 24, Ui.MUTED);
		String bankText = bank + " rubies";
		Ui.icon(g, Ui.Icon.GEM, left + panelW - font.width(bankText) - 12, 9);
		Ui.text(g, bankText, left + panelW - font.width(bankText), 10, Ui.GOLD);
		String waves = "Waves cleared: " + cleared;
		Ui.text(g, waves, left + panelW - font.width(waves), 22, Ui.DIM);
		int top = 50;
		int bottom = height - 32;
		Ui.panel(g, left, top, panelW, bottom - top);
		// Tabs.
		tabBoxes.clear();
		int tx = left + 6;
		for (int i = 0; i < TABS.length; i++) {
			int tw = font.width(TABS[i][1]) + 16;
			Ui.tab(g, TABS[i][1], tx, top - 15, tw, i == tab, mouseX, mouseY);
			tabBoxes.add(new int[] {tx, top - 15, tw, 16});
			tx += tw + 2;
		}
		// Cards for this tab.
		cardBoxes.clear();
		cardIds.clear();
		int inner = panelW - 16;
		int y0 = top + 8;
		int count = (int) entries.stream().filter(f -> f[1].equals(TABS[tab][0])).count();
		int cols = inner >= 330 ? 3 : 2;
		int rows = Math.max(1, (count + cols - 1) / cols);
		int cardW = (inner - (cols - 1) * 4) / cols;
		int cardH = Math.max(22, Math.min(30, (bottom - 8 - y0) / rows - 4));
		String[] tipEntry = null;
		int n = 0;
		for (String[] f : entries) {
			if (!f[1].equals(TABS[tab][0])) {
				continue;
			}
			int x = left + 8 + (n % cols) * (cardW + 4);
			int y = y0 + (n / cols) * (cardH + 4);
			n++;
			int price = Integer.parseInt(f[4]);
			int wave = Integer.parseInt(f[5]);
			boolean locked = cleared < wave;
			boolean afford = bank >= price;
			boolean hover = Ui.inside(mouseX, mouseY, x, y, cardW, cardH);
			if (locked) {
				Ui.inset(g, x, y, cardW, cardH);
			} else {
				Ui.card(g, x, y, cardW, cardH, afford ? Ui.GOLD : Ui.RED, Ui.hover("horde:" + f[0], hover));
			}
			g.item(icon(f[3]), x + 5, y + (cardH - 16) / 2);
			if (locked) {
				g.fill(x + 5, y + (cardH - 16) / 2, x + 21, y + (cardH - 16) / 2 + 16, 0x90101216);
			}
			int ty = y + cardH / 2 - 9;
			Ui.text(g, f[2], x + 25, ty, cardW - 29, locked ? Ui.MUTED : Ui.TEXT);
			if (locked) {
				Ui.icon(g, Ui.Icon.LOCK, x + 25, ty + 9);
				Ui.text(g, "After wave " + wave, x + 36, ty + 10, Ui.MUTED);
			} else {
				Ui.text(g, price + " rubies", x + 25, ty + 10, afford ? Ui.GOLD : Ui.RED);
			}
			cardBoxes.add(new int[] {x, y, cardW, cardH});
			cardIds.add(f[0]);
			if (hover) {
				tipEntry = f;
			}
		}
		// Ready for the next wave.
		int bw = 210;
		int bx = (width - bw) / 2;
		int by = height - 26;
		readyButton = new int[] {bx, by, bw, 18};
		Ui.button(g, ready ? "You're ready! Waiting for the others" : "I'm ready: start the next wave", bx, by, bw, 18, mouseX, mouseY, Ui.HORDE, !ready);
		if (tipEntry != null) {
			int wave = Integer.parseInt(tipEntry[5]);
			Ui.tooltip(g, List.of(tipEntry[2], tipEntry[6], cleared < wave ? "Unlocks after wave " + wave : tipEntry[4] + " rubies: click to buy"),
					mouseX, mouseY, width, height);
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
