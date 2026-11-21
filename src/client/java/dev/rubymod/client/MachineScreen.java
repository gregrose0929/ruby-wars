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
	private static final int RUBY = 0xFFE0294A;
	private static final int RUBY_DARK = 0xFF7A0A2A;
	private static final int TEXT = 0xFFF2EEE8;
	private static final int MUTED = 0xFF6E7480;
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
	/** The Assembler's recipe picker is open. */
	private boolean picking;
	private static final int PICK_X = 62;
	private static final int PICK_Y = 20;
	private static final int PICK_COLS = 5;
	private static final int PICK_CELL = 21;

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
		if (kind() == MachineKind.AMMO_FACTORY || kind() == MachineKind.ASSEMBLER) {
			buttons.add(new Button(MachineMenu.BUTTON_RECIPE, 66, 22, 102, 13, "recipe"));
		}
		if (kind() == MachineKind.RUBY_DRILL) {
			buttons.add(new Button(MachineMenu.BUTTON_FOCUS, 22, 22, 72, 13, "focus"));
			buttons.add(new Button(MachineMenu.BUTTON_STONE, 22, 37, 72, 13, "stone"));
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
		dev.rubymod.client.ui.Ui.panel(g, x, y, w, h);
		// Divider above the inventory.
		g.fill(x + 7, y + MachineMenu.INVENTORY_Y - 6, x + w - 7, y + MachineMenu.INVENTORY_Y - 5, 0xFF2E3440);
		// Slot wells.
		for (Slot slot : menu.slots) {
			int sx = x + slot.x - 1;
			int sy = y + slot.y - 1;
			boolean machineSlot = slot.index < kind().slots();
			dev.rubymod.client.ui.Ui.inset(g, sx, sy, 18, 18);
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
		dev.rubymod.client.ui.Ui.tag(g, MachineKind.TIER_NAMES[Math.min(tier, MachineKind.MAX_TIER) - 1], imageWidth - bw - 6, 4, RUBY);

		drawStatus(g, mouseX - leftPos, mouseY - topPos);
		// Only machines that make, store or use power show the grid.
		if (usesPower(kind)) {
			drawPowerGauge(g, mouseX - leftPos, mouseY - topPos);
		}
		switch (kind) {
			case COMBUSTION_GENERATOR -> drawGenerator(g);
			case SOLAR_PANEL -> info(g, "Output", kind.output(tier) * 20 + " charge/s in sunlight", "Needs open sky above it");
			case BATTERY -> info(g, "Capacity", String.format(Locale.ROOT, "%,d charge", kind.batteryCapacity(tier)), "Adds to your whole grid");
			case RUBY_DRILL -> drawDrill(g, mouseX - leftPos, mouseY - topPos);
			case ORE_REFINERY -> drawRefinery(g);
			case AMMO_FACTORY, ASSEMBLER -> drawAmmo(g);
			case TRADE_DEPOT -> drawDepot(g);
			case VAULT -> drawVault(g);
			case SHIELD_GENERATOR -> info(g, "Dome radius", kind.radius(tier) + " blocks", "1,500 charge per rocket stopped");
			case POINT_DEFENSE -> info(g, "Range", kind.radius(tier) + " blocks", "200 charge per burst");
			case TESLA_COIL -> info(g, "Range", kind.radius(tier) + " blocks, " + (3 + tier) + " damage", "400 charge per zap");
			case CONSTRUCTOR -> drawConstructor(g);
		}
		for (Button b : buttons) {
			drawButton(g, b, mouseX - leftPos, mouseY - topPos);
		}
		depotPrices(g, mouseX - leftPos, mouseY - topPos);
		if (picking) {
			drawPicker(g, mouseX - leftPos, mouseY - topPos);
		}
	}

	/** The Refinery: what it's making from what's in it right now. */
	private void drawRefinery(GuiGraphicsExtractor g) {
		drawArrow(g, 66, 44, true);
		small(g, "sand", 23, 67, MUTED);
		int id = menu.get(40);
		ItemStack in = menu.slots.get(0).getItem();
		if (id <= 0 || in.isEmpty()) {
			small(g, "Coal + sand: gunpowder", 66, 64, MUTED);
			small(g, "Cooks like a furnace too", 66, 73, MUTED);
			return;
		}
		Item out = BuiltInRegistries.ITEM.byId(id - 1);
		int n = Math.max(1, menu.get(41));
		ItemStack sand = menu.slots.get(1).getItem();
		boolean gunpowder = out == net.minecraft.world.item.Items.GUNPOWDER && !sand.isEmpty();
		String making = "Making: " + new ItemStack(out).getHoverName().getString() + (n > 1 ? " x" + n : "");
		int room = (int) ((imageWidth - 66 - 6) / 0.75F);
		small(g, dev.rubymod.client.ui.Ui.fit(making, room), 66, 64, TEXT);
		int fed = menu.get(42);
		if (fed > 0) {
			String what = new ItemStack(BuiltInRegistries.ITEM.byId(fed - 1)).getHoverName().getString();
			small(g, dev.rubymod.client.ui.Ui.fit("Taking " + what + " from the silo", room), 66, 73, GREEN);
		} else {
			small(g, gunpowder ? "Cooks like a furnace too" : "Coal + sand: gunpowder", 66, 73, MUTED);
		}
	}

	/** The Assembler's recipes as a grid of what they make: click one to pick it. */
	private void drawPicker(GuiGraphicsExtractor g, int mx, int my) {
		List<Recipes.AmmoRecipe> all = Recipes.recipes(kind());
		int rows = (all.size() + PICK_COLS - 1) / PICK_COLS;
		int w = PICK_COLS * PICK_CELL + 6;
		int h = rows * PICK_CELL + 18;
		g.nextStratum();
		dev.rubymod.client.ui.Ui.panel(g, PICK_X, PICK_Y, w, h);
		small(g, "Pick a recipe", PICK_X + 4, PICK_Y + 4, AMBER);
		int current = menu.get(11);
		for (int i = 0; i < all.size(); i++) {
			Recipes.AmmoRecipe r = all.get(i);
			int cx = PICK_X + 3 + (i % PICK_COLS) * PICK_CELL;
			int cy = PICK_Y + 14 + (i / PICK_COLS) * PICK_CELL;
			boolean over = mx >= cx && mx < cx + PICK_CELL && my >= cy && my < cy + PICK_CELL;
			dev.rubymod.client.ui.Ui.inset(g, cx + 1, cy + 1, 19, 19);
			if (i == current) {
				g.fill(cx + 2, cy + 2, cx + 19, cy + 19, 0x885A1028);
			}
			if (over) {
				g.fill(cx + 2, cy + 2, cx + 19, cy + 19, 0x55FFFFFF);
			}
			ItemStack result = new ItemStack(r.result(), r.count());
			g.item(result, cx + 3, cy + 3);
			g.itemDecorations(font, result, cx + 3, cy + 3);
			if (over) {
				List<Component> lines = new ArrayList<>();
				lines.add(Component.literal(r.name()).withStyle(net.minecraft.ChatFormatting.YELLOW));
				String needs = r.metalCount() + " " + new ItemStack(r.metal()).getHoverName().getString()
						+ (r.extra() != null ? " + " + r.extraCount() + " " + new ItemStack(r.extra()).getHoverName().getString() : "");
				lines.add(Component.literal(needs + " -> " + r.count()).withStyle(net.minecraft.ChatFormatting.GRAY));
				if (r.result() == net.minecraft.world.item.Items.TORCH) {
					lines.add(Component.literal("Charcoal works as well as coal").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
				}
				g.setComponentTooltipForNextFrame(font, lines, mx + leftPos, my + topPos);
			}
		}
	}

	/** Opens the Assembler's recipe picker (as clicking its recipe does). */
	public void openPicker() {
		picking = kind() == MachineKind.ASSEMBLER;
	}

	/** The picker cell under (mx, my), or -1. */
	private int pickerCell(double mx, double my) {
		int n = Recipes.recipes(kind()).size();
		for (int i = 0; i < n; i++) {
			int cx = PICK_X + 3 + (i % PICK_COLS) * PICK_CELL;
			int cy = PICK_Y + 14 + (i / PICK_COLS) * PICK_CELL;
			if (mx >= cx && mx < cx + PICK_CELL && my >= cy && my < cy + PICK_CELL) {
				return i;
			}
		}
		return -1;
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		// The picker covers the slots, so their tooltips would be for things you can't see.
		if (!picking) {
			super.extractTooltip(g, mouseX, mouseY);
		}
	}

	private void drawStatus(GuiGraphicsExtractor g, int mx, int my) {
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
		// What to do about it, in small letters beside it.
		String hint = hint(status);
		if (!hint.isEmpty()) {
			int hx = 38 + font.width(text);
			// Up to the Upgrade button.
			int room = (int) ((114 - hx) / 0.75F);
			g.pose().pushMatrix();
			g.pose().translate(hx, 94);
			g.pose().scale(0.75F, 0.75F);
			g.text(font, dev.rubymod.client.ui.Ui.fit(hint, room), 0, 0, MUTED, false);
			g.pose().popMatrix();
			// Cut short? The whole of it when you point at it.
			if (font.width(hint) > room && mx >= 26 && mx < 116 && my >= 90 && my < 102) {
				g.setComponentTooltipForNextFrame(font, List.of(Component.literal(text).withStyle(net.minecraft.ChatFormatting.YELLOW),
						Component.literal(Character.toUpperCase(hint.charAt(0)) + hint.substring(1)).withStyle(net.minecraft.ChatFormatting.GRAY)),
						mx + leftPos, my + topPos);
			}
		}
	}

	/** The next thing to do when a machine has stopped (or what it's doing). */
	private String hint(int status) {
		MachineKind kind = kind();
		return switch (status) {
			case MachineBlockEntity.NO_POWER -> "add solar panels or a generator";
			case MachineBlockEntity.OUTPUT_FULL -> kind.generator() ? "add a Battery Bank to store more"
					: kind == MachineKind.RUBY_DRILL ? "crouch-click to collect, or put an Ore Silo or a belt next to it"
					: "take it out, or put an Ore Silo or a belt next to it";
			case MachineBlockEntity.NO_INPUT -> switch (kind) {
				case COMBUSTION_GENERATOR -> "coal, charcoal, wood or lava";
				case ORE_REFINERY -> "ore, something to cook, or coal + sand";
				case AMMO_FACTORY -> "metal and gunpowder (see above)";
				case ASSEMBLER -> "the parts shown above";
				default -> "";
			};
			case MachineBlockEntity.NIGHT -> "solar panels rest at night";
			case MachineBlockEntity.WORKING -> switch (kind) {
				case RUBY_DRILL -> menu.get(43) > 0 ? "fills the " + (menu.get(43) == 1 ? "silo" : menu.get(43) + " silos") + " next to it"
						: "keeps what it digs until you collect it";
				case TRADE_DEPOT -> "selling into your bank";
				default -> "";
			};
			default -> kind == MachineKind.TRADE_DEPOT ? "put anything in to sell it" : "";
		};
	}

	private static boolean usesPower(MachineKind kind) {
		return kind != MachineKind.TRADE_DEPOT && kind != MachineKind.VAULT;
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
			long made = menu.wide(9, 16);
			long used = menu.wide(10, 17);
			List<Component> lines = new java.util.ArrayList<>(List.of(
					Component.literal("Your power").withStyle(net.minecraft.ChatFormatting.YELLOW),
					Component.literal(String.format(Locale.ROOT, "%,d / %,d charge", charge, capacity)),
					Component.literal(String.format(Locale.ROOT, "+%,d/s made  -%,d/s used", made, used))
							.withStyle(net.minecraft.ChatFormatting.GRAY),
					Component.literal(PowerText.forecast(charge, capacity, made, used))
							.withStyle(made >= used ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GOLD)));
			String night = PowerText.night(charge, made, used);
			if (!night.isEmpty()) {
				lines.add(Component.literal(night).withStyle(net.minecraft.ChatFormatting.GRAY));
			}
			for (String part : PowerText.breakdown().stream().limit(4).toList()) {
				lines.add(Component.literal("  " + part).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			}
			lines.add(Component.literal("Shared by all your machines").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
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
		Recipes.AmmoRecipe r = Recipes.recipe(kind(), menu.get(11));
		g.item(new ItemStack(r.metal(), r.metalCount()), 66, 38);
		g.itemDecorations(font, new ItemStack(r.metal(), r.metalCount()), 66, 38);
		if (r.extra() != null) {
			g.text(font, "+", 86, 42, MUTED, false);
			g.item(new ItemStack(r.extra(), r.extraCount()), 94, 38);
			g.itemDecorations(font, new ItemStack(r.extra(), r.extraCount()), 94, 38);
		}
		drawArrow(g, 66, 58, true);
		g.text(font, r.count() + "x", 140, 64, MUTED, false);
		if (kind() == MachineKind.ASSEMBLER) {
			int fed = menu.get(42);
			if (fed > 0) {
				small(g, new ItemStack(BuiltInRegistries.ITEM.byId(fed - 1)).getHoverName().getString() + " comes from the silo next to it", 66, 80, GREEN);
			} else {
				small(g, "Click the recipe to pick another", 66, 80, MUTED);
			}
		}
		// Faint pictures of the parts it needs in empty input slots.
		int[][] at = MachineMenu.layout(kind());
		boolean any = false;
		for (int slot = 0; slot < 2; slot++) {
			Item want = slot == 0 ? r.metal() : r.extra();
			if (want != null && menu.slots.get(slot).getItem().isEmpty()) {
				g.item(new ItemStack(want), at[slot][0], at[slot][1]);
				any = true;
			}
		}
		if (any) {
			g.nextStratum();
			for (int slot = 0; slot < 2; slot++) {
				Item want = slot == 0 ? r.metal() : r.extra();
				if (want != null && menu.slots.get(slot).getItem().isEmpty()) {
					g.fill(at[slot][0], at[slot][1], at[slot][0] + 16, at[slot][1] + 16, 0xB00E1013);
				}
			}
		}
	}

	/** The Ruby Drill: what it looks for, what happens to plain rock, its progress, the ground here, and its finds. */
	private void drawDrill(GuiGraphicsExtractor g, int mx, int my) {
		int tier = menu.tier();
		// Progress to the next load.
		int progress = menu.get(1);
		int max = Math.max(1, menu.get(2));
		g.fill(22, 53, 94, 56, SLOT_EDGE);
		g.fill(23, 54, 93, 55, SLOT_BG);
		if (progress > 0) {
			g.fill(23, 54, 23 + 70 * Math.min(progress, max) / max, 55, RUBY);
		}
		float chance = menu.get(39) / 10.0F;
		small(g, String.format(Locale.ROOT, "Rubies %.1f%%", chance), 22, 59, 0xFFFF5A82);
		small(g, richLine(), 22, 67, menu.get(37) > 0 ? GREEN : MUTED);
		long loads = menu.wide(31, 32);
		if (loads > 0) {
			long rubies = menu.wide(33, 34);
			small(g, String.format(Locale.ROOT, "%,d loads dug", loads), 22, 75, MUTED);
			small(g, String.format(Locale.ROOT, "%,d %s found", rubies, rubies == 1 ? "ruby" : "rubies"), 22, 83, 0xFFFF5A82);
		}
		if (mx >= 22 && mx < 94 && my >= 52 && my < 90) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(String.format(Locale.ROOT, "A load every %.1f seconds", kind().cycle(tier) / 20.0)).withStyle(net.minecraft.ChatFormatting.YELLOW));
			lines.add(Component.literal(String.format(Locale.ROOT, "Rubies: %.1f%% of loads", chance)).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
			lines.add(Component.literal("Twice as many below Y 0").withStyle(net.minecraft.ChatFormatting.GRAY));
			lines.add(Component.literal(richSentence()).withStyle(net.minecraft.ChatFormatting.GRAY));
			lines.add(Component.literal("Ore in the ground nearby comes up more often,").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			lines.add(Component.literal("and so do some biomes' minerals.").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			lines.add(Component.literal("Crouch-click the drill to collect everything.").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			g.setComponentTooltipForNextFrame(font, lines, mx + leftPos, my + topPos);
		}
	}

	private Item rich(int slot) {
		int id = menu.get(slot);
		return id <= 0 ? null : BuiltInRegistries.ITEM.byId(id - 1);
	}

	/** "Rich: iron, gold" (short, for the screen). */
	private String richLine() {
		Item a = rich(37);
		Item b = rich(38);
		if (a == null) {
			return "Plain rock here";
		}
		return "Rich: " + dev.rubymod.industry.DrillSite.name(a) + (b == null ? "" : ", " + dev.rubymod.industry.DrillSite.name(b));
	}

	private String richSentence() {
		Item a = rich(37);
		Item b = rich(38);
		if (a == null) {
			return "The ground here is plain rock";
		}
		return "The ground here is rich in " + dev.rubymod.industry.DrillSite.name(a) + (b == null ? "" : " and " + dev.rubymod.industry.DrillSite.name(b));
	}

	private void small(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.75F, 0.75F);
		g.text(font, text, 0, 0, color, false);
		g.pose().popMatrix();
	}

	/** What's waiting to be sold: the Depot's slots, plus whatever you're holding or pointing at (milli-rubies). */
	private long depotPreview() {
		long milli = 0;
		int tier = menu.tier();
		for (int i = 0; i < kind().slots(); i++) {
			ItemStack s = menu.slots.get(i).getItem();
			if (!s.isEmpty()) {
				milli += (long) Recipes.price(s, tier) * s.getCount();
			}
		}
		ItemStack carried = menu.getCarried();
		if (!carried.isEmpty()) {
			milli += (long) Recipes.price(carried, tier) * carried.getCount();
		} else if (hoveredSlot != null && hoveredSlot.container instanceof Inventory && hoveredSlot.hasItem()) {
			ItemStack s = hoveredSlot.getItem();
			milli += (long) Recipes.price(s, tier) * s.getCount();
		}
		return milli;
	}

	private static String rubies(long milli) {
		double r = milli / 1000.0;
		return String.format(Locale.ROOT, r >= 100 ? "%,.0f" : r >= 10 ? "%.1f" : "%.2f", r);
	}

	/**
	 * The Trade Depot: how far you are to your next ruby (a bar and a percentage), with what's waiting to sell
	 * (or what you're holding) shown as a lighter stretch ahead of it, and what it sold last.
	 */
	private void drawDepot(GuiGraphicsExtractor g) {
		int x = 70;
		int w = imageWidth - x - 8;
		int part = menu.get(18);
		long preview = depotPreview();
		// "Next ruby" and the percentage.
		g.text(font, "Next ruby", x, 22, TEXT, false);
		String pct = part / 10 + "%";
		g.pose().pushMatrix();
		g.pose().translate(x + w - font.width(pct) * 1.4F, 19);
		g.pose().scale(1.4F, 1.4F);
		g.text(font, pct, 0, 0, 0xFFFF5A82, true);
		g.pose().popMatrix();
		// The bar: what you have, then the preview.
		int by = 33;
		int bh = 12;
		g.fill(x - 1, by - 1, x + w + 1, by + bh + 1, SLOT_EDGE);
		g.fill(x, by, x + w, by + bh, SLOT_BG);
		int have = (int) Math.round(w * part / 1000.0);
		long total = part + preview;
		int ahead = (int) Math.round(w * Math.min(1000, total) / 1000.0);
		if (ahead > have) {
			// A soft, pulsing stretch: what you're about to get.
			float pulse = 0.55F + 0.25F * (float) Math.sin(System.currentTimeMillis() / 180.0);
			g.fill(x + have, by + 1, x + ahead, by + bh - 1, dev.rubymod.client.ui.Ui.alpha(0xFFFF9AB0, pulse));
		}
		if (have > 0) {
			g.fillGradient(x, by, x + have, by + bh, 0xFFFF4D7A, 0xFFB0103E);
		}
		for (int i = 1; i < 4; i++) {
			g.fill(x + i * w / 4, by + bh - 3, x + i * w / 4 + 1, by + bh, 0x66000000);
		}
		// What the preview's worth.
		String gain;
		int gainColor = 0xFFFF9AB0;
		if (preview <= 0) {
			gain = "Add items to sell";
			gainColor = MUTED;
		} else if (total < 1000) {
			gain = "+" + Math.max(1, preview / 10) + "% (" + rubies(preview) + " rubies)";
		} else {
			long whole = total / 1000;
			gain = "+" + whole + (whole == 1 ? " ruby" : " rubies") + ", then " + (total % 1000) / 10 + "%";
		}
		g.text(font, dev.rubymod.client.ui.Ui.fit(gain, w), x, by + bh + 4, gainColor, false);
		// The last few sales.
		int sy = 62;
		g.text(font, "Just sold", x, sy, MUTED, false);
		int shown = 0;
		for (int k = 0; k < 4 && shown < 2; k++) {
			int id = menu.get(19 + k * 3);
			if (id <= 0) {
				continue;
			}
			Item item = BuiltInRegistries.ITEM.byId(id - 1);
			int count = menu.get(20 + k * 3);
			int paid = menu.get(21 + k * 3);
			int ly = sy + 10 + shown * 10;
			g.pose().pushMatrix();
			g.pose().translate(x, ly - 1);
			g.pose().scale(0.6F, 0.6F);
			g.item(new ItemStack(item), 0, 0);
			g.pose().popMatrix();
			String line = count + "x  +" + rubies(paid);
			g.text(font, dev.rubymod.client.ui.Ui.fit(line, w - 12), x + 12, ly, GREEN, false);
			shown++;
		}
		if (shown == 0) {
			g.text(font, "nothing yet", x, sy + 10, MUTED, false);
		}
		// Bank, and the price list on hover.
		g.text(font, "Prices (hover)", 12, 82, 0xFF5FC8E8, false);
	}

	/** The Trade Depot's best prices, while hovering its "best prices" line. */
	private void depotPrices(GuiGraphicsExtractor g, int mx, int my) {
		if (kind() != MachineKind.TRADE_DEPOT || mx < 12 || mx > 12 + font.width("Prices (hover)") || my < 81 || my > 91) {
			return;
		}
		List<Component> lines = new java.util.ArrayList<>();
		lines.add(Component.literal("Paid per item, into your bank").withStyle(net.minecraft.ChatFormatting.YELLOW));
		for (var item : new net.minecraft.world.item.Item[] {net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.EMERALD,
				net.minecraft.world.item.Items.GOLD_INGOT, net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.RAW_GOLD,
				net.minecraft.world.item.Items.RAW_IRON, net.minecraft.world.item.Items.COPPER_INGOT, net.minecraft.world.item.Items.COAL,
				net.minecraft.world.item.Items.LAPIS_LAZULI, net.minecraft.world.item.Items.OAK_LOG, net.minecraft.world.item.Items.COBBLESTONE}) {
			ItemStack stack = new ItemStack(item);
			double rubies = Recipes.price(stack, menu.tier()) / 1000.0;
			lines.add(Component.literal(stack.getHoverName().getString() + ": " + String.format(Locale.ROOT, rubies < 0.1 ? "%.3f" : "%.2f", rubies) + " rubies")
					.withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		lines.add(Component.literal("Storage blocks pay nine times. Anything else sells for a little.").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		g.setComponentTooltipForNextFrame(font, lines, mx + leftPos, my + topPos);
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
		String label = b.label();
		if (b.id() == MachineMenu.BUTTON_RECIPE) {
			label = Recipes.recipe(kind(), menu.get(11)).name() + "  >";
		} else if (b.id() == MachineMenu.BUTTON_FOCUS) {
			label = "Find: " + new String[] {"All", "Metals", "Gems"}[Math.floorMod(menu.get(35), 3)];
		} else if (b.id() == MachineMenu.BUTTON_STONE) {
			label = menu.get(36) == 1 ? "Stone: Leave" : "Stone: Keep";
		}
		dev.rubymod.client.ui.Ui.button(g, label, b.x(), b.y(), b.w(), b.h(), mx, my, RUBY, !disabled);
		if (hover && (b.id() == MachineMenu.BUTTON_FOCUS || b.id() == MachineMenu.BUTTON_STONE)) {
			List<Component> lines = new ArrayList<>();
			if (b.id() == MachineMenu.BUTTON_FOCUS) {
				lines.add(Component.literal("What the drill looks for").withStyle(net.minecraft.ChatFormatting.YELLOW));
				lines.add(Component.literal("All: a bit of everything").withStyle(net.minecraft.ChatFormatting.GRAY));
				lines.add(Component.literal("Metals: more iron, copper and gold").withStyle(net.minecraft.ChatFormatting.GRAY));
				lines.add(Component.literal("Gems: more rubies, diamonds, emeralds,").withStyle(net.minecraft.ChatFormatting.GRAY));
				lines.add(Component.literal("  lapis and redstone").withStyle(net.minecraft.ChatFormatting.GRAY));
				lines.add(Component.literal("Less of the rest. Right-click to go back.").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			} else {
				lines.add(Component.literal("Plain rock (cobblestone, deepslate)").withStyle(net.minecraft.ChatFormatting.YELLOW));
				lines.add(Component.literal("Keep: it goes in the drill with the rest").withStyle(net.minecraft.ChatFormatting.GRAY));
				lines.add(Component.literal("Leave: it stays in the hole, so the").withStyle(net.minecraft.ChatFormatting.GRAY));
				lines.add(Component.literal("  drill fills up much slower").withStyle(net.minecraft.ChatFormatting.GRAY));
			}
			g.setComponentTooltipForNextFrame(font, lines, mx + leftPos, my + topPos);
		}
		if (hover && b.id() == MachineMenu.BUTTON_UPGRADE && cost >= 0) {
			MachineKind kind = kind();
			int next = menu.tier() + 1;
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal("Upgrade to " + MachineKind.TIER_NAMES[next - 1]).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
			lines.add(Component.literal("Costs " + cost + " rubies (inventory, then bank)").withStyle(net.minecraft.ChatFormatting.RED));
			String gain = switch (kind) {
				case COMBUSTION_GENERATOR, SOLAR_PANEL -> kind.output(menu.tier()) * 20 + " -> " + kind.output(next) * 20 + " charge/s";
				case BATTERY -> kind.batteryCapacity(menu.tier()) + " -> " + kind.batteryCapacity(next) + " charge";
				case RUBY_DRILL, ORE_REFINERY, AMMO_FACTORY, ASSEMBLER -> String.format(Locale.ROOT, "%.1fs -> %.1fs per job", kind.cycle(menu.tier()) / 20.0,
						kind.cycle(next) / 20.0) + (kind == MachineKind.RUBY_DRILL ? ", more rubies" : kind == MachineKind.ORE_REFINERY && next == 4 ? ", x3 ore" : "");
				case TRADE_DEPOT -> "+6% prices, sells faster";
				case VAULT -> "Interest " + kind.interestPercent(menu.tier()) + "% -> " + kind.interestPercent(next) + "%";
				case SHIELD_GENERATOR, POINT_DEFENSE, TESLA_COIL -> kind.radius(menu.tier()) + " -> " + kind.radius(next) + " blocks";
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
		if (picking) {
			// Any click closes the picker; one on a recipe picks it.
			int cell = pickerCell(mx, my);
			picking = false;
			if (cell >= 0) {
				minecraft.gameMode.handleInventoryButtonClick(menu.containerId, MachineMenu.BUTTON_PICK + cell);
				minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.0F);
			}
			return true;
		}
		for (Button b : buttons) {
			if (b.over(mx, my)) {
				int id = b.id();
				if (id == MachineMenu.BUTTON_RECIPE && kind() == MachineKind.ASSEMBLER && event.button() == 0) {
					picking = true;
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.2F);
					return true;
				}
				if (event.button() == 1) {
					// Right-click goes back through recipes and drill focus.
					id = id == MachineMenu.BUTTON_RECIPE ? MachineMenu.BUTTON_RECIPE_BACK : id == MachineMenu.BUTTON_FOCUS ? MachineMenu.BUTTON_FOCUS_BACK : id;
				}
				minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
				minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.0F);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
