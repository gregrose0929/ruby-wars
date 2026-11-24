package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.game.Phase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The admin panel (M, then Admin; admins only): the season at a glance, and buttons for what /rubywars admin does in
 * chat: start a season, call Battle Day, pause, Night Battle, a new season. Anything that can't be undone
 * asks for a second click. Each button runs the same /rubywars command you could type.
 */
public class AdminScreen extends Screen {
	private record Action(String section, String label, String command, boolean confirm, String about) {
	}

	private final List<Action> actions = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private final long opened = System.currentTimeMillis();
	private int armed = -1;
	private long armedAt;
	private String lastRan = "";
	private long ranAt;

	public AdminScreen() {
		super(Component.literal("Admin"));
		actions.add(new Action("Season", "Start the season", "rubywars start", false, "Start the build phase; Battle Day starts when you start it"));
		actions.add(new Action("Battle Day", "Battle Day now", "rubywars battle", true, "A one-minute countdown, then Battle Day (anyone not on sits it out, their base safe)"));
		actions.add(new Action("Battle Day", "Call it off", "rubywars battle cancel", false, "Stop a Battle Day countdown"));
		actions.add(new Action("Battle Day", "Teams on", "rubywars teams on", false, "Let everyone pick a Battle Day team (Season Choices)"));
		actions.add(new Action("Battle Day", "Teams off", "rubywars teams off", false, "No teams: everyone on their own (the default)"));
		actions.add(new Action("Battle Day", "Spare Hearts on", "rubywars spareheart on", false, "Lose your Heart in Open War: get one Spare Heart to come back to"));
		actions.add(new Action("Battle Day", "Spare Hearts off", "rubywars spareheart off", false, "Heart broken: next knockdown puts you out (the default)"));
		actions.add(new Action("Battle Day", "Pause timer", "rubywars pause", false, "Stop the clock"));
		actions.add(new Action("Battle Day", "Resume timer", "rubywars resume", false, "Start the clock again"));
		actions.add(new Action("World events", "Supply Cache", "rubywars event cache", false, "A Supply Cache, told two minutes ahead (while building)"));
		actions.add(new Action("World events", "Ruby Shrine", "rubywars event shrine", false, "A Ruby Shrine, told two minutes ahead (while building)"));
		actions.add(new Action("World events", "Ruby Meteor", "rubywars event meteor", false, "A Ruby Meteor, told two minutes ahead (while building)"));
		actions.add(new Action("World events", "Supply drop", "rubywars event supplydrop", false, "A supply drop in 30 seconds (Battle Day)"));
		actions.add(new Action("World events", "Stop the event", "rubywars event stop", false, "End the world event that's on (the ground goes back)"));
		actions.add(new Action("Options", "Night Battle on", "rubywars nightbattle on", false, "Battle Day fought at night"));
		actions.add(new Action("Options", "Night Battle off", "rubywars nightbattle off", false, "Battle Day by daylight"));
		actions.add(new Action("New season", "New season", "rubywars reset confirm", true,
				"Back to the lobby for the next season (on the server, 'rubywars newseason' also remakes the island)"));
		actions.add(new Action("New season", "Status in chat", "rubywars status", false, "The season's details, in chat"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 180));
		int w = Math.min(360, width - 24);
		// How tall it needs to be: each section's heading, and its buttons three to a row.
		int sections = 0;
		int rowsNeeded = 0;
		String last = null;
		int inSection = 0;
		for (Action a : actions) {
			if (!a.section().equals(last)) {
				rowsNeeded += (inSection + 2) / 3;
				inSection = 0;
				sections++;
				last = a.section();
			}
			inSection++;
		}
		rowsNeeded += (inSection + 2) / 3;
		int step = 18;
		int bh = 16;
		int head = 11;
		int need = 28 + sections * head + rowsNeeded * step + 18;
		int h = Math.min(height - 16, Math.max(236, need));
		if (need > h) {
			// A small window: squeeze the rows so it all fits.
			step = 15;
			bh = 13;
			head = 10;
		}
		int x = (width - w) / 2;
		int y = Ui.centre(height, h);
		Ui.panel(g, x, y, w, h);
		Ui.heading(g, "Admin", x + 10, y + 9, Ui.TEXT, 1.2F, false);
		// The season at a glance.
		Phase phase = ClientState.phase();
		long secs = ClientState.secondsLeft();
		String status = phase.title() + (secs >= 0 ? "  -  " + GameHud.formatTime(secs) + " left" : "")
				+ (ClientState.hud != null && phase.isBattle() ? "  -  " + ClientState.hud.alive() + " standing" : "");
		g.text(font, Ui.fit(status, w - 80), x + 70, y + 11, Ui.GOLD, false);
		boxes.clear();
		String section = "";
		int cy = y + 28;
		int col = 0;
		int bw = (w - 20 - 8) / 3;
		List<String> tip = null;
		if (System.currentTimeMillis() - armedAt > 4000) {
			armed = -1;
		}
		for (int i = 0; i < actions.size(); i++) {
			Action a = actions.get(i);
			if (!a.section().equals(section)) {
				if (col != 0) {
					cy += step;
				}
				section = a.section();
				Ui.section(g, section, x + 10, cy, w - 20);
				cy += head;
				col = 0;
			}
			int bx = x + 10 + col * (bw + 4);
			boxes.add(new int[] {bx, cy, bw, bh});
			String label = armed == i ? "Click again" : a.label();
			Ui.button(g, label, bx, cy, bw, bh, mouseX, mouseY, armed == i ? Ui.GOLD : Ui.RUBY, true);
			if (Ui.inside(mouseX, mouseY, bx, cy, bw, bh)) {
				tip = List.of(a.label(), a.about(), "/" + a.command());
			}
			col++;
			if (col == 3) {
				col = 0;
				cy += step;
			}
		}
		String foot = System.currentTimeMillis() - ranAt < 3000 ? "Ran /" + lastRan : "Buttons run /rubywars commands  -  Esc to close";
		g.centeredText(font, Ui.fit(foot, w - 20), x + w / 2, y + h - 14, Ui.MUTED);
		if (tip != null) {
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < boxes.size(); i++) {
			int[] b = boxes.get(i);
			if (!Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
				continue;
			}
			Action a = actions.get(i);
			if (a.confirm() && armed != i) {
				armed = i;
				armedAt = System.currentTimeMillis();
				Ui.click();
				return true;
			}
			armed = -1;
			var mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.connection.sendCommand(a.command());
			}
			lastRan = a.command();
			ranAt = System.currentTimeMillis();
			Ui.click();
			return true;
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(new RubyMenuScreen());
	}
}
