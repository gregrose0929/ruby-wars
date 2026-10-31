package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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

	/** For autotests: turn to the other page. */
	public void flip() {
		page = 1 - page;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int color, boolean on, int mouseX, int mouseY, String kind, String id) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		g.fill(x, y, x + w, y + h, on ? 0xFF000000 | RelicScreen.darker(color, 0.6F) : hover ? 0xE0000000 | RelicScreen.darker(color, 0.35F) : 0xC0181018);
		g.outline(x, y, w, h, 0xFF000000 | color & 0xFFFFFF);
		g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
		buttons.add(new int[] {x, y, w, h});
		actions.add(new String[] {kind, id});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		buttons.clear();
		actions.clear();
		long now = System.currentTimeMillis();
		g.fillGradient(0, 0, width, height, 0xE8180614, 0xF4060208);
		for (int i = 0; i < 40; i++) {
			double s = (now / 30.0 + i * 83) % height;
			int sx = (int) ((i * 157 + Math.sin(now / 800.0 + i) * 30) % width + width) % width;
			g.fill(sx, height - (int) s, sx + 1, height - (int) s + 1, i % 2 == 0 ? 0x90C27BFF : 0x80FF3D6E);
		}
		g.pose().pushMatrix();
		g.pose().translate(width / 2.0F, 8);
		g.pose().scale(1.5F, 1.5F);
		g.centeredText(font, "◆ THE ALTAR OF ECHOES ◆", 0, 0, 0xFFC27BFF);
		g.pose().popMatrix();
		g.centeredText(font, "◆ " + shards + " Rift Shards  •  earned every run (deeper, elites, bosses and Heat give more)", width / 2, 24, 0xFFFFC02E);
		// The Codex: everything you've found.
		button(g, width - 74, 6, 68, 14, "\u2726 CODEX", 0xFFFFC02E, false, mouseX, mouseY, "codex", "open");
		button(g, 6, 6, 74, 14, page == 0 ? "EXTRAS \u25B6" : "\u25C0 THE RUN", 0xFF66E0FF, page == 1, mouseX, mouseY, "page", "flip");
		if (page == 1) {
			extras(g, mouseX, mouseY);
			return;
		}
		int colW = Math.min(170, (width - 32) / 3);
		int top = 40;
		int x1 = width / 2 - colW * 3 / 2 - 8;
		int x2 = x1 + colW + 8;
		int x3 = x2 + colW + 8;
		// Loadouts.
		g.text(font, "LOADOUT", x1, top, 0xFFFF3D6E, true);
		int y = top + 12;
		int abilityRows = (abilities.size() + 1) / 2;
		int rowH = Math.max(25, Math.min(36, (height - top - 30 - 16 - abilityRows * 15) / Math.max(1, loadouts.size())));
		List<Component> tip = null;
		for (String[] l : loadouts) {
			if (mouseX >= x1 && mouseX < x1 + colW - 60 && mouseY >= y && mouseY < y + rowH - 3) {
				tip = List.of(Component.literal(l[2]).withColor(0xFF3D6E), Component.literal(l[4]).withColor(0xE0D8E8));
			}
			boolean selected = l[6].equals("selected");
			boolean locked = l[6].equals("locked");
			g.fill(x1, y, x1 + colW, y + rowH - 3, selected ? 0xD0402030 : 0xB0140C14);
			g.outline(x1, y, colW, rowH - 3, selected ? 0xFFFFC02E : 0xFF402838);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(l[3]))), x1 + 3, y + 3);
			g.text(font, font.plainSubstrByWidth(l[2], colW - 84), x1 + 22, y + 3, locked ? 0xFFD0C4DC : 0xFFFFFFFF, false);
			g.text(font, font.plainSubstrByWidth(l[4], colW - 26), x1 + 22, y + 14, 0xFFDCD2E6, false);
			String label = selected ? "SELECTED" : locked ? "◆ " + l[5] : "SELECT";
			button(g, x1 + colW - 58, y + 2, 54, 10, label, locked ? 0xFFFFC02E : 0xFFFF3D6E, selected, mouseX, mouseY, "loadout", l[1]);
			y += rowH;
		}
		// Abilities (Z in the Rift).
		y += 2;
		g.text(font, "ABILITY  (" + net.minecraft.client.Minecraft.getInstance().options.keySwapOffhand.getTranslatedKeyMessage().getString() + ")", x1, y, 0xFFFFC02E, true);
		y += 11;
		int bw = (colW - 4) / 2;
		for (int i = 0; i < abilities.size(); i++) {
			String[] a = abilities.get(i);
			int bx = x1 + (i % 2) * (bw + 4);
			int by = y + (i / 2) * 15;
			boolean on = a.length > 5 && a[5].equals("selected");
			button(g, bx, by, bw, 13, a[2], 0xFFFFC02E, on, mouseX, mouseY, "pick_ability", a[1]);
			if (mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 13) {
				tip = List.of(Component.literal(a[2]).withColor(0xFFC02E), Component.literal(a[4]).withColor(0xE0D8E8));
			}
		}
		// Relics to unlock.
		g.text(font, "NEW RELICS", x2, top, 0xFF4FA8FF, true);
		y = top + 12;
		int rh = Math.max(26, Math.min(38, (height - top - 30) / Math.max(1, relics.size())));
		for (String[] r : relics) {
			if (mouseX >= x2 && mouseX < x2 + colW - 60 && mouseY >= y && mouseY < y + rh - 3) {
				tip = List.of(Component.literal(r[2]).withColor(RiftClient.tierColor(r.length > 7 ? r[7] : "RARE") & 0xFFFFFF),
						Component.literal(r[4]).withColor(0xE0D8E8));
			}
			boolean owned = r[6].equals("owned");
			int tier = RiftClient.tierColor(r.length > 7 ? r[7] : "RARE");
			float shine = (float) (0.5 + 0.5 * Math.sin(now / 300.0 + y));
			g.fill(x2, y, x2 + colW, y + rh - 3, owned ? 0xC0102018 : 0xB0140C14);
			g.outline(x2, y, colW, rh - 3, owned ? 0xFF5BE37D : (int) (120 + 135 * shine) << 24 | tier & 0xFFFFFF);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(r[3]))), x2 + 3, y + 3);
			g.text(font, font.plainSubstrByWidth(r[2], colW - 84), x2 + 22, y + 3, tier, false);
			g.text(font, font.plainSubstrByWidth(r[4], colW - 26), x2 + 22, y + 14, 0xFFDCD2E6, false);
			if (owned) {
				g.text(font, "✔ OWNED", x2 + colW - font.width("✔ OWNED") - 4, y + 3, 0xFF5BE37D, false);
			} else {
				button(g, x2 + colW - 58, y + 2, 54, 10, "◆ " + r[5], shards >= Integer.parseInt(r[5]) ? 0xFFFFC02E : 0xFF804040, false, mouseX, mouseY,
						"unlock", r[1]);
			}
			y += rh;
		}
		// Heat and the Daily Rift.
		g.text(font, "HEAT", x3, top, 0xFFFF7A2E, true);
		y = top + 12;
		for (int h = 0; h <= 5; h++) {
			boolean locked = h > maxHeat;
			String label = (locked ? "🔒 " : "") + h + "  " + HEAT[h];
			if (locked) {
				g.fill(x3, y, x3 + colW, y + 13, 0x80101010);
				g.text(font, font.plainSubstrByWidth(h + "  " + HEAT[h] + "  (win at Heat " + (h - 1) + ")", colW - 4), x3 + 2, y + 3, 0xFF605060, false);
			} else {
				button(g, x3, y, colW, 13, h + "  " + HEAT[h], h == 0 ? 0xFFB8C4D0 : h < 3 ? 0xFFFFB040 : 0xFFFF4040, h == heat, mouseX, mouseY, "heat",
						String.valueOf(h));
			}
			y += 15;
		}
		for (var line : font.split(Component.literal(HEAT_ABOUT[heat] + (heat > 0 ? "  Shards x" + String.format("%.2f", 1 + 0.25 * heat) : "")), colW)) {
			g.text(font, line, x3, y, 0xFFFFC080, false);
			y += 10;
		}
		y += 8;
		g.text(font, "DAILY RIFT", x3, y, 0xFFC27BFF, true);
		y += 12;
		button(g, x3, y, colW, 14, daily ? "ON: today's run for everyone" : "OFF (click to turn on)", 0xFFC27BFF, daily, mouseX, mouseY, "daily", "toggle");
		y += 18;
		g.text(font, "Today's best:", x3, y, 0xFFDCD2E6, false);
		y += 10;
		if (dailyTop.isEmpty()) {
			g.text(font, "  nobody yet", x3, y, 0xFF706070, false);
		}
		for (String s : dailyTop) {
			g.text(font, "  " + s, x3, y, 0xFFFFFFFF, false);
			y += 10;
		}
		y += 8;
		g.text(font, "ENDLESS DESCENT", x3, y, 0xFFC27BFF, true);
		y += 11;
		g.text(font, font.plainSubstrByWidth("Win a run, then keep going down!", colW), x3, y, 0xFFDCD2E6, false);
		y += 10;
		if (deepest.isEmpty()) {
			g.text(font, "  nobody yet", x3, y, 0xFF706070, false);
		}
		for (String s : deepest) {
			g.text(font, "  " + s, x3, y, 0xFFFFFFFF, false);
			y += 10;
		}
		if (tip != null) {
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
		g.centeredText(font, "Choices here are for the next run. Step into the portal when everyone's ready.", width / 2, height - 12, 0xFFD0C4DC);
	}

	/** The EXTRAS page: your companion (left) and the team's mutators (right). */
	private void extras(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int colW = Math.min(230, (width - 40) / 2);
		int x1 = width / 2 - colW - 8;
		int x2 = width / 2 + 8;
		int top = 40;
		List<Component> tip = null;
		g.text(font, "YOUR COMPANION", x1, top, 0xFF66E0FF, true);
		int y = top + 12;
		int rh = Math.max(22, Math.min(30, (height - top - 40) / Math.max(1, companions.size())));
		for (String[] c : companions) {
			boolean on = c.length > 5 && c[5].equals("selected");
			g.fill(x1, y, x1 + colW, y + rh - 3, on ? 0xD0203040 : 0xB0140C14);
			g.outline(x1, y, colW, rh - 3, on ? 0xFF66E0FF : 0xFF402838);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c[3]))), x1 + 3, y + (rh - 3 - 16) / 2);
			g.text(font, c[2], x1 + 22, y + 3, 0xFFFFFFFF, false);
			g.text(font, font.plainSubstrByWidth(c[4], colW - 84), x1 + 22, y + 13, 0xFFDCD2E6, false);
			button(g, x1 + colW - 58, y + 2, 54, 10, on ? "WITH YOU" : "CHOOSE", 0xFF66E0FF, on, mouseX, mouseY, "companion", c[1]);
			if (mouseX >= x1 && mouseX < x1 + colW - 60 && mouseY >= y && mouseY < y + rh - 3) {
				tip = List.of(Component.literal(c[2]).withColor(0x66E0FF), Component.literal(c[4]).withColor(0xE0D8E8));
			}
			y += rh;
		}
		g.text(font, "MUTATORS (whole team)", x2, top, 0xFFFF7AE0, true);
		y = top + 12;
		rh = Math.max(22, Math.min(30, (height - top - 40) / Math.max(1, mutators.size())));
		for (String[] m : mutators) {
			boolean on = m.length > 5 && m[5].equals("on");
			g.fill(x2, y, x2 + colW, y + rh - 3, on ? 0xD0402040 : 0xB0140C14);
			g.outline(x2, y, colW, rh - 3, on ? 0xFFFF7AE0 : 0xFF402838);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(m[3]))), x2 + 3, y + (rh - 3 - 16) / 2);
			g.text(font, m[2], x2 + 22, y + 3, 0xFFFFFFFF, false);
			g.text(font, font.plainSubstrByWidth(m[4], colW - 84), x2 + 22, y + 13, 0xFFDCD2E6, false);
			button(g, x2 + colW - 58, y + 2, 54, 10, on ? "ON" : "OFF", 0xFFFF7AE0, on, mouseX, mouseY, "mutator", m[1]);
			if (mouseX >= x2 && mouseX < x2 + colW - 60 && mouseY >= y && mouseY < y + rh - 3) {
				tip = List.of(Component.literal(m[2]).withColor(0xFF7AE0), Component.literal(m[4]).withColor(0xE0D8E8));
			}
			y += rh;
		}
		if (tip != null) {
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
		g.centeredText(font, "Companions level up with your kills. Mutators are just for fun. Hover for details.", width / 2, height - 12, 0xFFD0C4DC);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < buttons.size(); i++) {
			int[] b = buttons.get(i);
			if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
				if (actions.get(i)[0].equals("page")) {
					page = 1 - page;
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
