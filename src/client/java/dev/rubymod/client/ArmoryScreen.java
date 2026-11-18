package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.game.ArmoryMenu;
import dev.rubymod.game.Shop;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;
import dev.rubymod.industry.MachineBlock;
import dev.rubymod.industry.MachineKind;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Armory. Down the left, a tab for each kind of thing (Your Heart, Guns, Ammo, Defenses...). In the middle, that
 * tab's cards in sections, each with its name and price (green when you can afford it, red when you can't; locked ones
 * say so). On the right, the card you're looking at: what it is, what it does, its numbers, and a Buy button (and Buy
 * ammo for guns). Click a card to look at it, then Buy; shift-click a card to buy it straight away.
 */
public class ArmoryScreen extends AbstractContainerScreen<ArmoryMenu> {
	private static final int SIDEBAR = 76;
	private static final int CARD_W = 112;
	private static final int CARD_H = 21;
	private static final int HEAD_H = 12;
	private static final int DETAIL_MIN = 126;
	private static final int TOP = 20;
	/** The card last clicked (shown when nothing is hovered); public so tests can pick one. */
	public int selected = -1;
	private int shownPage = -1;
	private int scroll;
	private int scrollMax;
	/** Where the Buy / Buy ammo / upgrades buttons are this frame (local coordinates; w = 0 when not shown). */
	private final int[] buy = new int[4];
	private final int[] ammo = new int[4];
	private final int[] upgrades = new int[4];

	public ArmoryScreen(ArmoryMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, fitWidth(), height());
	}

	/** As wide as the layout wants, but never wider than the screen: the details panel gives up the room (down to a narrow strip). */
	private static int fitWidth() {
		int screen = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth();
		return Math.max(width(1) - DETAIL_MIN + 90, Math.min(width(columns()), screen - 4));
	}

	/** Two columns of cards when there's room, else one. */
	private static int columns() {
		int screen = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth();
		return screen >= width(2) + 8 ? 2 : 1;
	}

	private static int width(int columns) {
		return 6 + SIDEBAR + 4 + columns * (CARD_W + 2) + 2 + 4 + DETAIL_MIN + 6;
	}

	private static int height() {
		int screen = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
		return Math.max(170, Math.min(222, screen - 8));
	}

	@Override
	protected void init() {
		super.init();
		leftPos = Math.max(0, leftPos);
		topPos = Math.max(0, topPos);
	}

	private int cols() {
		return imageWidth >= width(2) ? 2 : 1;
	}

	private int listX() {
		return 6 + SIDEBAR + 4;
	}

	private int listW() {
		return cols() * (CARD_W + 2) + 2;
	}

	private int detailX() {
		return listX() + listW() + 4;
	}

	private int detailW() {
		return imageWidth - 6 - detailX();
	}

	private int listH() {
		return imageHeight - TOP - 6;
	}

	// ----- Reading the cards -----

	private static boolean locked(ItemStack stack) {
		return stack.getHoverName().getString().startsWith("[Locked]");
	}

	private static String name(ItemStack stack) {
		return stack.getHoverName().getString().replace("[Locked] ", "");
	}

	private static int price(ItemStack stack) {
		String line = lore(stack, "Cost: ");
		if (line != null) {
			try {
				return Integer.parseInt(line.substring(6, line.indexOf(' ', 6)));
			} catch (RuntimeException e) {
				return -1;
			}
		}
		return -1;
	}

	private static String lore(ItemStack stack, String starts) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) {
				if (line.getString().startsWith(starts)) {
					return line.getString();
				}
			}
		}
		return null;
	}

	private int rubies() {
		String info = menu.display(49).getHoverName().getString().replace("You have ", "").replace(" rubies", "");
		try {
			return Integer.parseInt(info.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private boolean creative() {
		return minecraft.player != null && minecraft.player.isCreative();
	}

	/** One thing drawn in the list: a section heading, or a card. */
	private record Row(String heading, int slot, int x, int y) {
	}

	/** The list laid out (in list coordinates, before scrolling). */
	private List<Row> layout() {
		List<Row> rows = new ArrayList<>();
		int y = 4;
		int col = 0;
		String section = null;
		for (Shop.Card card : Shop.cards(menu.page())) {
			if (menu.display(card.slot()).isEmpty()) {
				continue;
			}
			if (!card.section().equals(section)) {
				if (col > 0) {
					y += CARD_H + 1;
					col = 0;
				}
				section = card.section();
				if (!section.isEmpty()) {
					rows.add(new Row(section, -1, 4, y));
					y += HEAD_H;
				}
			}
			rows.add(new Row(null, card.slot(), 2 + col * (CARD_W + 2), y));
			if (++col >= cols()) {
				col = 0;
				y += CARD_H + 1;
			}
		}
		return rows;
	}

	private static int listBottom(List<Row> rows) {
		int b = 0;
		for (Row r : rows) {
			b = Math.max(b, r.y() + (r.heading() != null ? HEAD_H : CARD_H + 1));
		}
		return b + 4;
	}

	/** The card under the mouse (local coordinates), or -1. */
	private int cardAt(double mx, double my) {
		if (mx < listX() || mx >= listX() + listW() || my < TOP || my >= TOP + listH()) {
			return -1;
		}
		for (Row r : layout()) {
			if (r.heading() == null) {
				int cx = listX() + r.x();
				int cy = TOP + r.y() - scroll;
				if (mx >= cx && mx < cx + CARD_W && my >= cy && my < cy + CARD_H) {
					return r.slot();
				}
			}
		}
		return -1;
	}

	private int tabRow() {
		return Math.max(11, Math.min(14, (listH() - 4) / Math.max(1, Shop.pageCount())));
	}

	private int tabAt(double mx, double my) {
		if (mx < 8 || mx >= 6 + SIDEBAR - 2 || my < TOP + 2) {
			return -1;
		}
		int p = (int) ((my - TOP - 2) / tabRow());
		return p < Shop.pageCount() ? p : -1;
	}

	// ----- Drawing -----

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		if (menu.page() != shownPage) {
			// A new tab: from the top, and (on Your Heart) the upgrade ready to look at.
			shownPage = menu.page();
			selected = menu.page() == 0 && !menu.display(0).isEmpty() ? 0 : -1;
			scroll = 0;
		}
		int x = leftPos;
		int y = topPos;
		Ui.panel(g, x, y, imageWidth, imageHeight);
		Ui.inset(g, x + 6, y + TOP, SIDEBAR, listH());
		Ui.inset(g, x + listX(), y + TOP, listW(), listH());
		Ui.inset(g, x + detailX(), y + TOP, detailW(), listH());
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int mx = mouseX - leftPos;
		int my = mouseY - topPos;
		g.text(font, "Armory", 8, 7, Ui.TEXT, false);
		String r = rubies() + " rubies";
		Ui.icon(g, Ui.Icon.GEM, imageWidth - 20 - font.width(r), 6);
		g.text(font, r, imageWidth - 8 - font.width(r), 7, Ui.GOLD, false);
		int hx = 50;
		g.text(font, Ui.fit("Click to look, then Buy. Shift-click buys.", imageWidth - 30 - font.width(r) - hx), hx, 7, Ui.MUTED, false);
		tabs(g, mx, my);
		List<Row> rows = layout();
		scrollMax = Math.max(0, listBottom(rows) - listH());
		scroll = Math.max(0, Math.min(scrollMax, scroll));
		int hovered = cardAt(mx, my);
		list(g, rows, hovered);
		int show = hovered >= 0 ? hovered : selected;
		details(g, show, mx, my, show == selected);
	}

	private void tabs(GuiGraphicsExtractor g, int mx, int my) {
		int rowH = tabRow();
		for (int p = 0; p < Shop.pageCount(); p++) {
			int ty = TOP + 2 + p * rowH;
			boolean on = p == menu.page();
			boolean over = tabAt(mx, my) == p;
			if (on || over) {
				g.fill(8, ty, 6 + SIDEBAR - 2, ty + rowH - 1, on ? 0xFF3A2430 : 0xFF2A2E36);
			}
			if (on) {
				g.fill(8, ty, 10, ty + rowH - 1, Ui.RUBY);
			}
			g.text(font, Ui.fit(Shop.pageName(p), SIDEBAR - 12), 13, ty + (rowH - 8) / 2, on ? Ui.TEXT : over ? Ui.DIM : Ui.MUTED, false);
		}
	}

	private void list(GuiGraphicsExtractor g, List<Row> rows, int hovered) {
		int x0 = listX();
		// (Labels are drawn already shifted to the window, so the clip is in the same local coordinates.)
		g.enableScissor(x0 + 1, TOP + 1, x0 + listW() - 1, TOP + listH() - 1);
		int have = rubies();
		for (Row r : rows) {
			int x = x0 + r.x();
			int y = TOP + r.y() - scroll;
			if (y + CARD_H < TOP || y > TOP + listH()) {
				continue;
			}
			if (r.heading() != null) {
				Ui.section(g, r.heading(), x, y + 2, listW() - 12);
				continue;
			}
			ItemStack stack = menu.display(r.slot());
			boolean lock = locked(stack);
			int price = price(stack);
			boolean on = r.slot() == selected;
			boolean over = r.slot() == hovered;
			g.fill(x, y, x + CARD_W, y + CARD_H, on ? 0xFF3A2430 : over ? 0xFF2C313A : 0xFF20242C);
			if (on) {
				g.outline(x, y, CARD_W, CARD_H, Ui.RUBY);
			}
			g.item(stack, x + 2, y + 2);
			if (lock) {
				Ui.icon(g, Ui.Icon.LOCK, x + 11, y + 11);
			}
			g.text(font, Ui.fit(name(stack), CARD_W - 24), x + 21, y + 3, lock ? Ui.MUTED : Ui.TEXT, false);
			String line;
			int color;
			if (lock) {
				line = "Locked";
				color = Ui.MUTED;
			} else if (price >= 0) {
				line = price + (price == 1 ? " ruby" : " rubies") + (stack.getCount() > 1 ? "  (" + stack.getCount() + ")" : "");
				color = creative() || have >= price ? Ui.GREEN : Ui.RED;
			} else {
				line = "Max tier";
				color = Ui.MUTED;
			}
			small(g, line, x + 21, y + 13, color);
		}
		g.disableScissor();
		if (scrollMax > 0) {
			int trackH = listH() - 4;
			int barH = Math.max(12, trackH * listH() / (listH() + scrollMax));
			int barY = TOP + 2 + (trackH - barH) * scroll / scrollMax;
			g.fill(x0 + listW() - 4, TOP + 2, x0 + listW() - 2, TOP + 2 + trackH, 0x40FFFFFF);
			g.fill(x0 + listW() - 4, barY, x0 + listW() - 2, barY + barH, 0xFFAAB0BA);
		}
	}

	/** Small text (three quarters size). */
	private void small(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.75F, 0.75F);
		g.text(font, s, 0, 0, color, true);
		g.pose().popMatrix();
	}

	private void details(GuiGraphicsExtractor g, int slot, int mx, int my, boolean buttons) {
		int px = detailX() + 6;
		int pw = detailW() - 12;
		int bottom = TOP + listH() - 4;
		buy[2] = 0;
		ammo[2] = 0;
		upgrades[2] = 0;
		if (slot < 0 || menu.display(slot).isEmpty()) {
			int y = TOP + 8;
			g.text(font, Shop.pageName(menu.page()), px, y, Ui.GOLD, false);
			Ui.wrap(g, "Pick something on the left to see what it does and what it costs.", px, y + 14, pw, Ui.DIM, bottom);
			return;
		}
		ItemStack stack = menu.display(slot);
		boolean lock = locked(stack);
		int price = price(stack);
		int have = rubies();
		// The item, big, with its name and price.
		g.pose().pushMatrix();
		g.pose().translate(px, TOP + 6);
		g.pose().scale(2.0F, 2.0F);
		g.item(stack, 0, 0);
		g.pose().popMatrix();
		int tx = px + 38;
		int ny = TOP + 7;
		for (FormattedCharSequence line : font.split(Component.literal(name(stack)), pw - 38)) {
			if (ny > TOP + 17) {
				break;
			}
			g.text(font, line, tx, ny, Ui.TEXT, false);
			ny += 10;
		}
		String cost = lock ? "Locked" : price < 0 ? "Max tier" : price + (price == 1 ? " ruby" : " rubies") + (stack.getCount() > 1 ? " for " + stack.getCount() : "");
		g.text(font, Ui.fit(cost, pw - 38), tx, TOP + 28, lock || price < 0 ? Ui.MUTED : creative() || have >= price ? Ui.GOLD : Ui.RED, false);
		int y = TOP + 44;
		// Tags: what kind of thing it is.
		int tagX = px;
		for (String[] tag : tags(stack)) {
			int w = font.width(tag[0]) + 6;
			if (tagX > px && tagX + w > px + pw) {
				tagX = px;
				y += 13;
			}
			tagX += Ui.tag(g, tag[0], tagX, y, (int) Long.parseLong(tag[1], 16)) + 3;
		}
		if (tagX > px) {
			y += 15;
		}
		int textBottom = buttons ? bottom - 20 : bottom - 11;
		// Numbers for guns.
		if (stack.getItem() instanceof GunItem gun && y + 44 < textBottom) {
			GunStats s = gun.stats(stack);
			y = bar(g, px, y, pw, "Damage", s.damage() * s.pellets(), 24.0F, String.format("%.0f%s", s.damage(), s.pellets() > 1 ? "x" + s.pellets() : ""));
			y = bar(g, px, y, pw, "Fire rate", 1200.0F / s.fireInterval(), 400.0F, Math.round(1200.0F / s.fireInterval()) + "/min");
			y = bar(g, px, y, pw, "Magazine", s.magazine(), 30.0F, String.valueOf(s.magazine()));
			y = bar(g, px, y, pw, "Range", (float) s.range(), 512.0F, (int) s.range() + "m");
			y += 2;
		}
		// What it does: its description (and, if locked, how to unlock it).
		String unlock = lore(stack, "Unlock: ");
		if (lock && unlock != null) {
			y = Ui.wrap(g, unlock.replace("Unlock: ", "Unlock it with ") + ".", px, y, pw, 0xFF5FC8E8, textBottom);
		}
		for (Component c : about(stack)) {
			if (y + 9 > textBottom) {
				break;
			}
			y = Ui.wrap(g, c.getString(), px, y, pw, lock ? Ui.MUTED : Ui.DIM, textBottom);
		}
		if (!buttons) {
			g.text(font, "Click it to choose it", px, bottom - 9, Ui.MUTED, false);
			return;
		}
		// Buttons along the bottom.
		int by = bottom - 16;
		if (lock) {
			set(upgrades, px, by, pw, 16);
			Ui.button(g, "Open upgrades (J)", px, by, pw, 16, mx, my, 0xFF2F7FA8, true);
			return;
		}
		boolean gunAmmo = stack.getItem() instanceof GunItem;
		boolean afford = creative() || price >= 0 && have >= price;
		int bw = gunAmmo ? (pw - 4) / 2 : pw;
		String label = price < 0 ? "Max tier" : afford ? "Buy" : "Need " + (price - have) + " more";
		set(buy, px, by, bw, 16);
		Ui.button(g, label, px, by, bw, 16, mx, my, 0xFF2E8B4E, afford && price >= 0);
		if (gunAmmo) {
			set(ammo, px + bw + 4, by, pw - bw - 4, 16);
			Ui.button(g, "Buy ammo", px + bw + 4, by, pw - bw - 4, 16, mx, my, 0xFF8A6A2A, true);
		}
	}

	private static void set(int[] r, int x, int y, int w, int h) {
		r[0] = x;
		r[1] = y;
		r[2] = w;
		r[3] = h;
	}

	private static boolean in(int[] r, double mx, double my) {
		return r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
	}

	/** Coloured tags for the detail panel: {text, colour}. */
	private static List<String[]> tags(ItemStack stack) {
		List<String[]> out = new ArrayList<>();
		if (stack.getItem() instanceof GunItem gun) {
			out.add(new String[] {"Uses " + new ItemStack(gun.stats().ammo().item()).getHoverName().getString(), "FF8A6A2A"});
		}
		if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof MachineBlock mb) {
			MachineKind kind = mb.kind();
			if (kind == MachineKind.COMBUSTION_GENERATOR || kind == MachineKind.SOLAR_PANEL) {
				out.add(new String[] {"Makes power", "FFB8901A"});
			} else if (kind == MachineKind.BATTERY) {
				out.add(new String[] {"Stores power", "FFB8901A"});
			} else if (kind.draw(1) > 0) {
				out.add(new String[] {"Needs power", "FFB8901A"});
			}
			out.add(new String[] {"Upgrades to Mk V", "FF3F6E9E"});
		} else if (stack.getItem() instanceof BlockItem) {
			out.add(new String[] {"Place it", "FF4A5260"});
		}
		return out;
	}

	/** The words about it: its tooltip and lore, without the price lines. */
	private List<Component> about(ItemStack stack) {
		List<Component> lines = new ArrayList<>();
		List<Component> tip = stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(minecraft.level), minecraft.player,
				net.minecraft.world.item.TooltipFlag.NORMAL);
		for (int i = 1; i < tip.size(); i++) {
			String s = tip.get(i).getString();
			if (s.isBlank() || s.startsWith("Cost: ") || s.startsWith("Click to buy") || s.startsWith("Unlock: ") || s.startsWith("Press J")
					|| s.startsWith("rubymod:") || s.startsWith("minecraft:")) {
				continue;
			}
			lines.add(tip.get(i));
		}
		return lines;
	}

	private int bar(GuiGraphicsExtractor g, int x, int y, int w, String label, float value, float max, String text) {
		g.text(font, label, x, y, Ui.MUTED, false);
		int bx = x + 50;
		int bw = Math.max(20, w - 50 - font.width(text) - 4);
		Ui.bar(g, bx, y + 1, bw, 6, Math.max(0.04F, Math.min(1.0F, value / max)), Ui.RUBY);
		g.text(font, text, bx + bw + 4, y, Ui.TEXT, false);
		return y + 11;
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		// The detail panel replaces item tooltips.
	}

	// ----- Clicking -----

	private void send(int button) {
		minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x() - leftPos;
		double my = event.y() - topPos;
		int tab = tabAt(mx, my);
		if (tab >= 0) {
			if (tab != menu.page()) {
				send(100 + tab);
				minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.2F);
			}
			return true;
		}
		int card = cardAt(mx, my);
		if (card >= 0) {
			selected = card;
			minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.25F, 1.6F);
			if (event.hasShiftDown() && !locked(menu.display(card))) {
				send(1000 + card);
			}
			return true;
		}
		if (selected >= 0 && in(buy, mx, my)) {
			send(1000 + selected);
			return true;
		}
		if (selected >= 0 && in(ammo, mx, my)) {
			send(2000 + selected);
			return true;
		}
		if (in(upgrades, mx, my)) {
			minecraft.gui.setScreen(new ResearchScreen());
			return true;
		}
		if (mx >= 0 && mx < imageWidth && my >= 0 && my < imageHeight) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollMax > 0) {
			scroll = Math.max(0, Math.min(scrollMax, scroll - (int) Math.round(scrollY * 22)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
