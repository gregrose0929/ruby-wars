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
	private final List<String[]> loadouts = new ArrayList<>();
	private final List<String[]> abilities = new ArrayList<>();
	private final List<String[]> relics = new ArrayList<>();
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
				default -> {
				}
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int color, boolean on, int mouseX, int mouseY, String kind, String id) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		g.fill(x, y, x + w, y + h, on ? 0xFF000000 | color & 0xFFFFFF : hover ? 0xE0000000 | color & 0x7F7F7F : 0xC0181018);
		g.outline(x, y, w, h, 0xFF000000 | color & 0xFFFFFF);
		g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, on ? 0xFF101010 : 0xFFFFFFFF);
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
			g.text(font, font.plainSubstrByWidth(l[2], colW - 84), x1 + 22, y + 3, locked ? 0xFF8A7A9A : 0xFFFFFFFF, false);
			g.text(font, font.plainSubstrByWidth(l[4], colW - 26), x1 + 22, y + 14, 0xFFA898B8, false);
			String label = selected ? "SELECTED" : locked ? "◆ " + l[5] : "SELECT";
			button(g, x1 + colW - 58, y + 2, 54, 10, label, locked ? 0xFFFFC02E : 0xFFFF3D6E, selected, mouseX, mouseY, "loadout", l[1]);
			y += rowH;
		}
		// Abilities (Z in the Rift).
		y += 2;
		g.text(font, "ABILITY  (Z)", x1, y, 0xFFFFC02E, true);
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
			g.text(font, font.plainSubstrByWidth(r[4], colW - 26), x2 + 22, y + 14, 0xFFA898B8, false);
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
		g.text(font, "Today's best:", x3, y, 0xFFA898B8, false);
		y += 10;
		if (dailyTop.isEmpty()) {
			g.text(font, "  nobody yet", x3, y, 0xFF706070, false);
		}
		for (String s : dailyTop) {
			g.text(font, "  " + s, x3, y, 0xFFFFFFFF, false);
			y += 10;
		}
		if (tip != null) {
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
		g.centeredText(font, "Choices here are for the next run. Step into the portal when everyone's ready.", width / 2, height - 12, 0xFF8A7A9A);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < buttons.size(); i++) {
			int[] b = buttons.get(i);
			if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
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
