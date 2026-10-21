package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.rubymod.industry.MachineBlockEntity;
import dev.rubymod.industry.MachineKind;
import dev.rubymod.industry.MachineMenu;
import dev.rubymod.industry.Recipes;
import dev.rubymod.registry.ModItems;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The screen for every machine: a dark panel with a ruby trim, the machine's
 * tier badge and status, the shared power grid gauge, its slots, a progress
 * indicator, and its buttons (upgrade, recipe, withdraw). Drawn entirely in code,
 * so every machine shares one consistent look.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
	private static final int BG_TOP = 0xFF232830;
	private static final int BG_BOTTOM = 0xFF171A1F;
	private static final int RUBY = 0xFFD0154A;
	private static final int RUBY_DARK = 0xFF7A0A2A;
	private static final int TEXT = 0xFFE8EAED;
	private static final int MUTED = 0xFF8B93A1;
	private static final int GREEN = 0xFF5BE37D;
	private static final int AMBER = 0xFFF2B33D;
	private static final int RED = 0xFFF0506E;
	private static final int SLOT_BG = 0xFF0E1013;
	private static final int SLOT_EDGE = 0xFF3A414D;

	private record Button(int id, int x, int y, int w, int h, String label) {
		boolean over(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Button> buttons = new ArrayList<>();

	public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, MachineMenu.WIDTH, MachineMenu.HEIGHT);
	}

	private MachineKind kind() {
		return menu.kind();
	}

	private void layoutButtons() {
		buttons.clear();
		int cost = kind().upgradeCost(menu.tier());
		buttons.add(new Button(MachineMenu.BUTTON_UPGRADE, 118, 90, 52, 13, cost < 0 ? "MAX" : "Upgrade"));
		if (kind() == MachineKind.AMMO_FACTORY) {
			buttons.add(new Button(MachineMenu.BUTTON_RECIPE, 66, 22, 102, 13, "recipe"));
		}
		if (kind() == MachineKind.VAULT) {
			buttons.add(new Button(MachineMenu.BUTTON_WITHDRAW_1, 66, 70, 30, 13, "-1"));
			buttons.add(new Button(MachineMenu.BUTTON_WITHDRAW_16, 100, 70, 30, 13, "-16"));
			buttons.add(new Button(MachineMenu.BUTTON_WITHDRAW_64, 134, 70, 34, 13, "-64"));
		}
	}

	// ----- Background -----

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int x = leftPos;
		int y = topPos;
		int w = imageWidth;
		int h = imageHeight;
		// Drop shadow, panel, ruby frame.
		g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
		g.fillGradient(x, y, x + w, y + h, BG_TOP, BG_BOTTOM);
		g.outline(x, y, w, h, RUBY_DARK);
		g.outline(x + 1, y + 1, w - 2, h - 2, 0xFF2E3440);
		g.fill(x + 1, y + 1, x + w - 1, y + 16, 0xFF1A1E24);
		g.fill(x + 1, y + 16, x + w - 1, y + 17, RUBY);
		// Divider above the inventory.
		g.fill(x + 7, y + MachineMenu.INVENTORY_Y - 6, x + w - 7, y + MachineMenu.INVENTORY_Y - 5, 0xFF2E3440);
		// Slot wells.
		for (Slot slot : menu.slots) {
			int sx = x + slot.x - 1;
			int sy = y + slot.y - 1;
			boolean machineSlot = slot.index < kind().slots();
			g.fill(sx, sy, sx + 18, sy + 18, machineSlot ? SLOT_EDGE : 0xFF2A2F38);
			g.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_BG);
			if (machineSlot && slot.index >= kind().inputs) {
				g.fill(sx + 1, sy + 16, sx + 17, sy + 17, 0xFF5A1028); // outputs get a ruby underline
			}
		}
	}

	// ----- Foreground (panel-relative) -----

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		layoutButtons();
		MachineKind kind = kind();
		int tier = menu.tier();
		// Header: name and tier badge.
		g.text(font, kind.title, 8, 5, TEXT, false);
		String badge = MachineKind.TIER_NAMES[Math.min(tier, MachineKind.MAX_TIER) - 1].toUpperCase(Locale.ROOT);
		int bw = font.width(badge) + 8;
		g.fill(imageWidth - bw - 6, 3, imageWidth - 6, 14, RUBY);
		g.text(font, badge, imageWidth - bw - 2, 5, 0xFFFFFFFF, false);

		drawStatus(g);
		drawPowerGauge(g, mouseX - leftPos, mouseY - topPos);
		switch (kind) {
			case COMBUSTION_GENERATOR -> drawGenerator(g);
			case SOLAR_PANEL -> info(g, "Output", kind.output(tier) * 20 + " charge/s in sunlight", "Needs open sky above it");
			case BATTERY -> info(g, "Capacity", String.format(Locale.ROOT, "%,d charge", kind.batteryCapacity(tier)), "Adds to your whole grid");
			case RUBY_DRILL -> {
				drawArrow(g, 66, 44, true);
				float chance = new float[] {1.5F, 2.0F, 3.0F, 4.0F, 5.5F}[tier - 1];
				g.text(font, "Rubies", 24, 26, MUTED, false);
				g.text(font, chance + "%", 24, 36, 0xFFFF5A82, false);
				g.text(font, "x2 deep", 24, 50, MUTED, false);
				g.text(font, "(Y<0)", 24, 60, MUTED, false);
				g.text(font, String.format(java.util.Locale.ROOT, "%.1fs", kind.cycle(tier) / 20.0), 66, 58, MUTED, false);
			}
			case ORE_REFINERY -> drawArrow(g, 66, 44, true);
			case AMMO_FACTORY -> drawAmmo(g);
			case TRADE_DEPOT -> drawDepot(g);
			case VAULT -> drawVault(g);
			case SHIELD_GENERATOR -> info(g, "Dome radius", kind.radius(tier) + " blocks", "1,500 charge per rocket stopped");
			case POINT_DEFENSE -> info(g, "Range", kind.radius(tier) + " blocks", "200 charge per burst");
			case CONSTRUCTOR -> drawConstructor(g);
		}
		for (Button b : buttons) {
			drawButton(g, b, mouseX - leftPos, mouseY - topPos);
		}
	}

	private void drawStatus(GuiGraphicsExtractor g) {
		int status = menu.get(14);
		String text;
		int color;
		switch (status) {
			case MachineBlockEntity.WORKING -> {
				text = "Working";
				color = GREEN;
			}
			case MachineBlockEntity.NO_POWER -> {
				text = "No power";
				color = RED;
			}
			case MachineBlockEntity.OUTPUT_FULL -> {
				text = kind().generator() ? "Grid full" : "Output full";
				color = AMBER;
			}
			case MachineBlockEntity.NO_INPUT -> {
				text = switch (kind()) {
					case COMBUSTION_GENERATOR -> "Needs fuel";
					case CONSTRUCTOR -> menu.get(15) > 0 ? "Needs blocks" : "Needs a Blueprint";
					default -> "Needs input";
				};
				color = AMBER;
			}
			case MachineBlockEntity.NIGHT -> {
				text = "No sunlight";
				color = MUTED;
			}
			default -> {
				text = "Idle";
				color = MUTED;
			}
		}
		g.fill(26, 95, 30, 99, color);
		g.text(font, text, 34, 93, color, false);
	}

	/** The grid gauge: one bar for all of this player's machines. */
	private void drawPowerGauge(GuiGraphicsExtractor g, int mx, int my) {
		long charge = menu.wide(5, 6);
		long capacity = Math.max(1, menu.wide(7, 8));
		int x = 8;
		int y = 22;
		int h = 66;
		g.fill(x - 1, y - 1, x + 11, y + h + 1, SLOT_EDGE);
		g.fill(x, y, x + 10, y + h, SLOT_BG);
		int filled = (int) Math.round(h * Math.min(1.0, charge / (double) capacity));
		if (filled > 0) {
			g.fillGradient(x + 1, y + h - filled, x + 9, y + h, 0xFFFFE066, 0xFFE09A1A);
		}
		for (int i = 1; i < 6; i++) {
			g.fill(x + 7, y + i * h / 6, x + 10, y + i * h / 6 + 1, 0x66000000);
		}
		if (mx >= x && mx < x + 10 && my >= y && my < y + h) {
			List<Component> lines = List.of(
					Component.literal("Power grid").withStyle(net.minecraft.ChatFormatting.YELLOW),
					Component.literal(String.format(Locale.ROOT, "%,d / %,d charge", charge, capacity)),
					Component.literal(String.format(Locale.ROOT, "+%,d/s made  -%,d/s used", menu.wide(9, 16), menu.wide(10, 17)))
							.withStyle(net.minecraft.ChatFormatting.GRAY),
					Component.literal("Shared by all your machines").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			g.setComponentTooltipForNextFrame(font, lines, mx + leftPos, my + topPos);
		}
	}

	private void drawGenerator(GuiGraphicsExtractor g) {
		int burn = menu.get(3);
		int max = Math.max(1, menu.get(4));
		// A flame gauge next to the fuel slot.
		int x = 46;
		int y = 30;
		int h = 16;
		int lit = burn > 0 ? Math.max(1, h * burn / max) : 0;
		g.fill(x - 1, y - 1, x + 13, y + h + 1, SLOT_EDGE);
		g.fill(x, y, x + 12, y + h, SLOT_BG);
		if (lit > 0) {
			g.fillGradient(x + 1, y + h - lit, x + 11, y + h, 0xFFFFD25A, 0xFFE0402A);
		}
		info(g, "Output", kind().output(menu.tier()) * 20 + " charge/s", burn > 0 ? "Burning" : "Coal, wood, blaze rods, lava");
	}

	private void drawAmmo(GuiGraphicsExtractor g) {
		Recipes.AmmoRecipe r = Recipes.AMMO.get(Math.floorMod(menu.get(11), Recipes.AMMO.size()));
		g.item(new ItemStack(r.metal(), r.metalCount()), 66, 38);
		g.text(font, "+", 86, 42, MUTED, false);
		g.item(new ItemStack(r.extra(), r.extraCount()), 94, 38);
		drawArrow(g, 66, 58, true);
		g.text(font, r.count() + "x", 140, 64, MUTED, false);
	}

	private void drawDepot(GuiGraphicsExtractor g) {
		g.text(font, "Sells for rubies", 86, 26, TEXT, false);
		g.text(font, "straight to your bank", 86, 36, MUTED, false);
		g.item(new ItemStack(ModItems.RUBY), 86, 52);
		g.text(font, String.format(Locale.ROOT, "%,d earned", menu.get(15)), 106, 56, GREEN, false);
		g.text(font, String.format(Locale.ROOT, "Bank: %,d", menu.wide(12, 13)), 86, 72, AMBER, false);
	}

	private void drawVault(GuiGraphicsExtractor g) {
		long bank = menu.wide(12, 13);
		g.text(font, "Deposit", 22, 28, MUTED, false);
		g.item(new ItemStack(ModItems.RUBY), 66, 24);
		String balance = String.format(Locale.ROOT, "%,d", bank);
		g.pose().pushMatrix();
		g.pose().translate(86, 24);
		g.pose().scale(1.6F, 1.6F);
		g.text(font, balance, 0, 0, 0xFFFF5A82, true);
		g.pose().popMatrix();
		g.text(font, "rubies in the bank", 86, 40, MUTED, false);
		int tier = menu.tier();
		g.text(font, "Interest " + kind().interestPercent(tier) + "%/day (max " + kind().interestCap(tier) + ")", 66, 54, GREEN, false);
	}

	private void drawConstructor(GuiGraphicsExtractor g) {
		int done = menu.get(1);
		int total = Math.max(1, menu.get(2));
		int x = 66;
		int y = 46;
		g.fill(x - 1, y - 1, x + 103, y + 9, SLOT_EDGE);
		g.fill(x, y, x + 102, y + 8, SLOT_BG);
		g.fillGradient(x + 1, y + 1, x + 1 + 100 * Math.min(done, total) / total, y + 7, 0xFFFF4D7A, 0xFFB0103E);
		g.text(font, "Plan", 26, 32, MUTED, false);
		g.text(font, Math.min(100, 100 * done / total) + "% built", 66, 30, TEXT, false);
		int missing = menu.get(15);
		if (missing > 0) {
			Item item = BuiltInRegistries.ITEM.byId(missing - 1);
			g.text(font, "Needs:", 66, 62, AMBER, false);
			g.item(new ItemStack(item), 100, 58);
			g.text(font, item.getName(item.getDefaultInstance()).getString(), 118, 62, AMBER, false);
		} else {
			g.text(font, "Pulls blocks from chests next to it", 66, 62, MUTED, false);
		}
	}

	private void drawArrow(GuiGraphicsExtractor g, int x, int y, boolean showProgress) {
		int progress = menu.get(1);
		int max = Math.max(1, menu.get(2));
		int w = 24;
		g.fill(x, y + 3, x + w - 6, y + 7, SLOT_EDGE);
		for (int i = 0; i < 6; i++) {
			g.fill(x + w - 6 + i, y + i, x + w - 5 + i, y + 10 - i, SLOT_EDGE);
		}
		if (showProgress && progress > 0) {
			int fill = w * progress / max;
			for (int px = 0; px < fill; px++) {
				int top = px < w - 6 ? y + 3 : y + (px - (w - 6));
				int bottom = px < w - 6 ? y + 7 : y + 10 - (px - (w - 6));
				g.fill(x + px, top, x + px + 1, bottom, RUBY);
			}
		}
	}

	private void info(GuiGraphicsExtractor g, String label, String value, String note) {
		g.text(font, label, 66, 30, MUTED, false);
		g.text(font, value, 66, 42, TEXT, false);
		g.text(font, note, 66, 56, MUTED, false);
	}

	private void drawButton(GuiGraphicsExtractor g, Button b, int mx, int my) {
		boolean hover = b.over(mx, my);
		int cost = kind().upgradeCost(menu.tier());
		boolean disabled = b.id() == MachineMenu.BUTTON_UPGRADE && cost < 0;
		int base = disabled ? 0xFF2A2F38 : hover ? 0xFFE0275C : RUBY_DARK;
		g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), base);
		g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + 1, disabled ? 0xFF3A414D : 0xFFFF6E93);
		String label = b.label();
		if (b.id() == MachineMenu.BUTTON_RECIPE) {
			label = Recipes.AMMO.get(Math.floorMod(menu.get(11), Recipes.AMMO.size())).name() + "  >";
		}
		g.text(font, label, b.x() + (b.w() - font.width(label)) / 2, b.y() + 3, disabled ? MUTED : 0xFFFFFFFF, false);
		if (hover && b.id() == MachineMenu.BUTTON_UPGRADE && cost >= 0) {
			MachineKind kind = kind();
			int next = menu.tier() + 1;
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal("Upgrade to " + MachineKind.TIER_NAMES[next - 1]).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
			lines.add(Component.literal("Costs " + cost + " rubies (inventory, then bank)").withStyle(net.minecraft.ChatFormatting.RED));
			String gain = switch (kind) {
				case COMBUSTION_GENERATOR, SOLAR_PANEL -> kind.output(menu.tier()) * 20 + " -> " + kind.output(next) * 20 + " charge/s";
				case BATTERY -> kind.batteryCapacity(menu.tier()) + " -> " + kind.batteryCapacity(next) + " charge";
				case RUBY_DRILL, ORE_REFINERY, AMMO_FACTORY -> String.format(Locale.ROOT, "%.1fs -> %.1fs per job", kind.cycle(menu.tier()) / 20.0,
						kind.cycle(next) / 20.0) + (kind == MachineKind.RUBY_DRILL ? ", more rubies" : kind == MachineKind.ORE_REFINERY && next == 4 ? ", x3 ore" : "");
				case TRADE_DEPOT -> "+6% prices, sells faster";
				case VAULT -> "Interest " + kind.interestPercent(menu.tier()) + "% -> " + kind.interestPercent(next) + "%";
				case SHIELD_GENERATOR, POINT_DEFENSE -> kind.radius(menu.tier()) + " -> " + kind.radius(next) + " blocks";
				case CONSTRUCTOR -> "Builds faster";
			};
			lines.add(Component.literal(gain).withStyle(net.minecraft.ChatFormatting.GRAY));
			g.setComponentTooltipForNextFrame(font, lines, mx + leftPos, my + topPos);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x() - leftPos;
		double my = event.y() - topPos;
		for (Button b : buttons) {
			if (b.over(mx, my)) {
				minecraft.gameMode.handleInventoryButtonClick(menu.containerId, b.id());
				minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.0F);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
