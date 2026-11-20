package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import dev.rubymod.client.ui.Ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Ruby Wars settings (M, then Settings): what the HUD shows, the minimap, toasts and the look-at panel. Each row
 * is a button that cycles its choice; everything is saved straight away (config/rubywars_settings.properties).
 */
public class SettingsScreen extends Screen {
	private record Row(String label, String about, Supplier<String> value, Runnable next) {
	}

	private final List<Row> rows = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private final long opened = System.currentTimeMillis();
	private String done = "";
	private long doneAt;

	public SettingsScreen() {
		super(Component.literal("Settings"));
		rows.add(new Row("Guide", "The next step, top left: one line, the full card, or hidden (J always has the full guide)",
				() -> switch (ClientSettings.guide) {
					case "full" -> "Full card";
					case "off" -> "Hidden";
					default -> "One line";
				}, () -> ClientSettings.guide = switch (ClientSettings.guide) {
					case "compact" -> "full";
					case "full" -> "off";
					default -> "compact";
				}));
		rows.add(new Row("HUD size", "How big the Ruby Wars HUD is: small (a step smaller than Minecraft's GUI scale) or the same as the GUI",
				() -> "small".equals(ClientSettings.hudSize) ? "Small" : "Normal",
				() -> ClientSettings.hudSize = "small".equals(ClientSettings.hudSize) ? "normal" : "small"));
		rows.add(new Row("Minimap", "The map in the top right (U turns it on and off too)", () -> onOff(dev.rubymod.client.map.Minimap.enabled),
				() -> dev.rubymod.client.map.Minimap.enabled = !dev.rubymod.client.map.Minimap.enabled));
		rows.add(new Row("Minimap size", "Normal or big", () -> ClientSettings.bigMinimap ? "Big" : "Normal",
				() -> ClientSettings.bigMinimap = !ClientSettings.bigMinimap));
		rows.add(new Row("Heart panel", "Your Heart's health, top left", () -> onOff(ClientSettings.heartPanel),
				() -> ClientSettings.heartPanel = !ClientSettings.heartPanel));
		rows.add(new Row("Bank & power panel", "Your rubies and your power grid, top left", () -> onOff(ClientSettings.bankPanel),
				() -> ClientSettings.bankPanel = !ClientSettings.bankPanel));
		rows.add(new Row("Kill feed", "Who knocked out whom, on the right", () -> onOff(ClientSettings.killFeed),
				() -> ClientSettings.killFeed = !ClientSettings.killFeed));
		rows.add(new Row("Toasts", "Goals, unlocks and news pop up in the corner (off: a line over the hotbar)", () -> onOff(ClientSettings.toasts),
				() -> ClientSettings.toasts = !ClientSettings.toasts));
		rows.add(new Row("Look-at info", "What you're looking at (machines, belts, chests), under the crosshair", () -> onOff(ClientSettings.lookInfo),
				() -> ClientSettings.lookInfo = !ClientSettings.lookInfo));
		rows.add(new Row("How-to cards", "Show every game's how-to card again the next time you play it", () -> "Show again", () -> {
			HelpPrefs.reset();
			done = "How-to cards will show again";
			doneAt = System.currentTimeMillis();
		}));
	}

	private static String onOff(boolean on) {
		return on ? "On" : "Off";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 180));
		int w = Math.min(300, width - 24);
		int rowH = 20;
		int h = 34 + rows.size() * rowH + 22;
		int x = (width - w) / 2;
		int y = Ui.centre(height, h);
		Ui.panel(g, x, y, w, h);
		Ui.heading(g, "Settings", x + 10, y + 9, Ui.TEXT, 1.2F, false);
		boxes.clear();
		List<String> tip = null;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			int ry = y + 30 + i * rowH;
			if (i % 2 == 0) {
				g.fill(x + 6, ry - 2, x + w - 6, ry + rowH - 4, 0x14FFFFFF);
			}
			g.text(font, r.label(), x + 12, ry + 3, Ui.DIM, false);
			int bw = 92;
			int bx = x + w - bw - 10;
			boxes.add(new int[] {bx, ry - 1, bw, 15});
			Ui.button(g, r.value().get(), bx, ry - 1, bw, 15, mouseX, mouseY, Ui.RUBY, true);
			if (Ui.inside(mouseX, mouseY, x + 6, ry - 2, w - bw - 24, rowH - 2)) {
				tip = List.of(r.label(), r.about());
			}
		}
		String foot = System.currentTimeMillis() - doneAt < 2500 ? done : "Saved on this computer  -  Esc to close";
		g.centeredText(font, foot, x + w / 2, y + h - 14, Ui.MUTED);
		if (tip != null) {
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < boxes.size(); i++) {
			int[] b = boxes.get(i);
			if (Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
				rows.get(i).next().run();
				ClientSettings.save();
				Ui.click();
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public void onClose() {
		ClientSettings.save();
		Minecraft.getInstance().gui.setScreen(new RubyMenuScreen());
	}
}
