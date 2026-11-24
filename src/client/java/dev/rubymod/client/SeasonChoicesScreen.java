package dev.rubymod.client;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.client.ui.UiButton;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Season Choices (from the Ruby Wars menu): what the family decides together for the season. Your Battle Day team (Red,
 * Blue or on your own: the same colour fights on the same side), a vote on whether Battle Day is at night, and what your
 * base is famous for (just to be recognised for at the end). It runs /rubywars choose, like typing it.
 */
public class SeasonChoicesScreen extends Screen {
	private static final int W = 320;
	private static final int H = 214;
	/** The latest from the server (null until it's said). */
	public static MorePayloads.ChoicesState state;
	private final long opened = System.currentTimeMillis();

	public SeasonChoicesScreen() {
		super(Component.literal("Season Choices"));
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.ChoicesState.TYPE, (p, c) -> {
			state = p;
			if (Minecraft.getInstance().gui.screen() instanceof SeasonChoicesScreen open) {
				open.rebuildWidgets();
			}
		});
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int left() {
		return width / 2 - W / 2;
	}

	private int top() {
		return height / 2 - H / 2;
	}

	private void choose(String kind, String value) {
		var mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand("rubywars choose " + kind + " " + value);
		}
	}

	@Override
	protected void init() {
		int x = left() + 12;
		int y = top();
		MorePayloads.ChoicesState s = state;
		boolean open = s == null || s.open();
		String team = s == null ? "" : s.team();
		int night = s == null ? 0 : s.night();
		String theme = s == null ? "" : s.theme();
		// Battle Day team.
		String[][] teams = {{"red", "Red team"}, {"blue", "Blue team"}, {"solo", "On my own"}};
		int[] teamColors = {Ui.RED, Ui.BLUE, 0xFF6E7480};
		for (int i = 0; i < teams.length; i++) {
			String id = teams[i][0];
			boolean picked = id.equals("solo") ? team.isEmpty() : id.equals(team);
			UiButton b = new UiButton(x + i * 98, y + 44, 92, 18, (picked ? "> " : "") + teams[i][1], picked ? teamColors[i] : 0xFF3A3F48,
					btn -> choose("team", id));
			b.active = open;
			addRenderableWidget(b);
		}
		// Night Battle vote.
		String[][] votes = {{"yes", "Night: yes"}, {"no", "Night: no"}, {"none", "No vote"}};
		for (int i = 0; i < votes.length; i++) {
			String id = votes[i][0];
			boolean picked = (i + 1) % 3 == night;
			UiButton b = new UiButton(x + i * 98, y + 96, 92, 18, (picked ? "> " : "") + votes[i][1], picked ? Ui.GOLD : 0xFF3A3F48,
					btn -> choose("night", id));
			b.active = open;
			addRenderableWidget(b);
		}
		// What your base is famous for.
		String[][] themes = {{"walls", "Walls"}, {"factory", "Factory"}, {"garage", "Garage"}, {"airbase", "Air base"}, {"none", "Nothing"}};
		for (int i = 0; i < themes.length; i++) {
			String id = themes[i][0];
			boolean picked = id.equals("none") ? theme.isEmpty() : id.equals(theme);
			addRenderableWidget(new UiButton(x + i * 59, y + 150, 55, 18, themes[i][1], picked ? Ui.GREEN : 0xFF3A3F48, btn -> choose("theme", id)));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		Ui.dim(g, width, height, Ui.ease(opened, 220));
		int x = left();
		int y = top();
		Ui.panel(g, x, y, W, H);
		Ui.heading(g, "Season Choices", x + 12, y + 8, Ui.TEXT, 1.5F, false);
		MorePayloads.ChoicesState s = state;
		boolean open = s == null || s.open();
		Ui.section(g, "Battle Day team", x + 12, y + 28, W - 24);
		Ui.shrinkText(g, "Same colour, same side: you win together.", x + 12, y + 66, W - 24, Ui.DIM);
		Ui.section(g, "Battle Day at night?", x + 12, y + 80, W - 24);
		Ui.shrinkText(g, open ? "Most votes win when Battle Day starts." : "Settled when Battle Day started.", x + 12, y + 118, W - 24, Ui.DIM);
		Ui.section(g, "My base is famous for...", x + 12, y + 134, W - 24);
		Ui.shrinkText(g, "No bonus: just what you're known for. Change it any time.", x + 12, y + 172, W - 24, Ui.DIM);
		if (s != null) {
			Ui.shrinkWrap(g, s.summary(), x + 12, y + 186, W - 24, Ui.GOLD);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
	}
}
