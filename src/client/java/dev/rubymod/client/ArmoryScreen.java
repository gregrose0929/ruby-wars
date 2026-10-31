package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.game.ArmoryMenu;
import dev.rubymod.game.Shop;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;

import dev.rubymod.client.ui.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Armory: page tabs across the top, a grid of item cards with their price,
 * locked research items dimmed with a padlock, and a detail panel showing the
 * selected item large, its price, what it does, and for guns their stats.
 * Click an item to buy it.
 */
public class ArmoryScreen extends AbstractContainerScreen<ArmoryMenu> {
	private static final int W = 368;
	private static final int H = 178;
	private static final int PANEL_X = 216;
	private static final int RUBY = Ui.RUBY;
	private static final int TEXT = Ui.TEXT;
	private static final int MUTED = Ui.MUTED;
	private static final int GREEN = Ui.GREEN;
	private static final String[] TABS = {"Weapons & Base", "Vehicles & Air", "Industry", "Gear & Support"};
	/** The card last clicked (shown when nothing is hovered); public so tests can pick one. */
	public int selected = -1;

	public ArmoryScreen(ArmoryMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, W, H);
	}

	private static boolean locked(ItemStack stack) {
		return stack.getHoverName().getString().startsWith("[Locked]");
	}

	private static int price(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) {
				String s = line.getString();
				if (s.startsWith("Cost: ")) {
					try {
						return Integer.parseInt(s.substring(6, s.indexOf(' ', 6)));
					} catch (RuntimeException e) {
						return -1;
					}
				}
			}
		}
		return -1;
	}

	private String rubies() {
		String info = menu.display(49).getHoverName().getString();
		return info.replace("You have ", "").replace(" rubies", "");
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int x = leftPos;
		int y = topPos;
		Ui.panel(g, x, y, W, H);
		// Tabs
		int tx = x + 8;
		for (int p = 0; p < 4; p++) {
			String name = TABS[p];
			int tw = font.width(name) + 10;
			boolean on = p == menu.page();
			boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= y + 17 && mouseY < y + 29;
			g.fill(tx, y + 17, tx + tw, y + 29, on ? 0xFF3A2430 : hover ? 0xFF2A2E36 : 0xFF1A1D23);
			if (on) {
				g.fill(tx, y + 27, tx + tw, y + 29, RUBY);
			}
			tx += tw + 3;
		}
		// Item cards
		for (int i = 0; i < 45; i++) {
			int cx = x + ArmoryMenu.GRID_X + (i % 9) * ArmoryMenu.CELL;
			int cy = y + ArmoryMenu.GRID_Y + (i / 9) * ArmoryMenu.CELL;
			ItemStack stack = menu.display(i);
			Ui.inset(g, cx, cy, 20, 20);
			if (stack.isEmpty()) {
				continue;
			}
			if (i == selected) {
				g.outline(cx, cy, 20, 20, RUBY);
			} else if (locked(stack)) {
				g.fill(cx + 1, cy + 1, cx + 19, cy + 19, 0x30000000);
			}
		}
		// Detail panel
		Ui.inset(g, x + PANEL_X, y + 30, W - 8 - PANEL_X, H - 38);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, "Armory", 9, 6, TEXT, false);
		String r = rubies() + " rubies";
		Ui.icon(g, Ui.Icon.GEM, W - 20 - font.width(r), 5);
		g.text(font, r, W - 8 - font.width(r), 6, Ui.GOLD, false);
		int tx = 8;
		for (int p = 0; p < 4; p++) {
			String name = TABS[p];
			g.text(font, name, tx + 5, 19, p == menu.page() ? TEXT : MUTED, false);
			tx += font.width(name) + 13;
		}
		// Price tags and padlocks on the cards (items themselves are drawn by the slots).
		for (int i = 0; i < 45; i++) {
			ItemStack stack = menu.display(i);
			if (stack.isEmpty()) {
				continue;
			}
			int cx = ArmoryMenu.GRID_X + (i % 9) * ArmoryMenu.CELL;
			int cy = ArmoryMenu.GRID_Y + (i / 9) * ArmoryMenu.CELL;
			if (locked(stack)) {
				padlock(g, cx + 13, cy + 12);
			} else {
				int price = price(stack);
				if (price >= 0) {
					String s = String.valueOf(price);
					g.pose().pushMatrix();
					g.pose().translate(cx + 20 - font.width(s) * 0.5F - 1, cy + 1.5F);
					g.pose().scale(0.5F, 0.5F);
					g.text(font, s, 0, 0, Ui.GOLD, true);
					g.pose().popMatrix();
				}
			}
		}
		// Details for the hovered (or last clicked) item.
		int show = hoveredSlot != null && hoveredSlot.index < 45 && !hoveredSlot.getItem().isEmpty() ? hoveredSlot.index : selected;
		if (show < 0 || menu.display(show).isEmpty()) {
			g.text(font, "Hover an item to see it", PANEL_X + 8, 40, MUTED, false);
			g.text(font, "Click to buy", PANEL_X + 8, 52, MUTED, false);
			return;
		}
		ItemStack stack = menu.display(show);
		int px = PANEL_X + 6;
		g.pose().pushMatrix();
		g.pose().translate(px + 2, 36);
		g.pose().scale(2.5F, 2.5F);
		g.item(stack, 0, 0);
		g.pose().popMatrix();
		int tx2 = px + 48;
		String name = stack.getHoverName().getString().replace("[Locked] ", "");
		List<FormattedCharSequence> nameLines = font.split(Component.literal(name), W - 14 - tx2);
		int ny = 38;
		for (FormattedCharSequence line : nameLines) {
			g.text(font, line, tx2, ny, TEXT, false);
			ny += 10;
		}
		boolean lock = locked(stack);
		int price = price(stack);
		g.text(font, lock ? "Locked" : price >= 0 ? price + " rubies" : "", tx2, ny + 2, lock ? MUTED : Ui.GOLD, false);
		int ly = 82;
		List<Component> lines = new ArrayList<>();
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component c : lore.lines()) {
				if (!c.getString().startsWith("Cost: ") && !c.getString().startsWith("Click to buy")) {
					lines.add(c);
				}
			}
		}
		if (stack.getItem() instanceof GunItem gun) {
			GunStats s = gun.stats(stack);
			ly = bar(g, px, ly, "Damage", s.damage() * s.pellets(), 24.0F, String.format("%.0f%s", s.damage(), s.pellets() > 1 ? "x" + s.pellets() : ""));
			ly = bar(g, px, ly, "Fire rate", 1200.0F / s.fireInterval(), 400.0F, Math.round(1200.0F / s.fireInterval()) + "/min");
			ly = bar(g, px, ly, "Magazine", s.magazine(), 30.0F, String.valueOf(s.magazine()));
			ly = bar(g, px, ly, "Range", (float) s.range(), 512.0F, (int) s.range() + "m");
			ly = bar(g, px, ly, "Accuracy", 8.0F - s.aimSpread(), 8.0F, s.aimSpread() < 0.5F ? "pinpoint" : String.format("%.1f°", s.aimSpread()));
		} else {
			List<Component> tip = stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(minecraft.level), minecraft.player,
					net.minecraft.world.item.TooltipFlag.NORMAL);
			List<Component> about = new ArrayList<>();
			for (int i = 1; i < tip.size() && about.size() < 4; i++) {
				if (lore == null || !lore.lines().contains(tip.get(i))) {
					about.add(tip.get(i));
				}
			}
			lines.addAll(0, about);
		}
		for (Component c : lines) {
			for (FormattedCharSequence line : font.split(c, W - 14 - px)) {
				if (ly > H - 22) {
					break;
				}
				g.text(font, line, px, ly, lock ? MUTED : Ui.DIM, false);
				ly += 10;
			}
		}
		g.text(font, lock ? "Unlock it with stars (J)" : "Click to buy", px, H - 20, lock ? MUTED : GREEN, false);
	}

	private int bar(GuiGraphicsExtractor g, int x, int y, String label, float value, float max, String text) {
		g.text(font, label, x, y, MUTED, false);
		int bx = x + 52;
		int bw = W - 14 - bx - 40;
		Ui.bar(g, bx, y + 1, bw, 6, Math.max(0.04F, Math.min(1.0F, value / max)), RUBY);
		g.text(font, text, bx + bw + 4, y, TEXT, false);
		return y + 11;
	}

	private static void padlock(GuiGraphicsExtractor g, int x, int y) {
		Ui.icon(g, Ui.Icon.LOCK, x - 2, y - 3);
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		// The detail panel replaces item tooltips.
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x() - leftPos;
		double my = event.y() - topPos;
		int tx = 8;
		for (int p = 0; p < 4; p++) {
			int tw = font.width(TABS[p]) + 10;
			if (mx >= tx && mx < tx + tw && my >= 18 && my < 28) {
				if (p != menu.page()) {
					minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 100 + p);
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.2F);
				}
				return true;
			}
			tx += tw + 3;
		}
		Slot slot = hoveredSlot;
		if (slot != null && slot.index < 45) {
			selected = slot.index;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
