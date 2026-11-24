package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.client.ui.UiButton;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Season Choices (from the Ruby Wars menu): whether you'll fight on Battle Day or sit it out, your team (only when the
 * admin has turned teams on for the season), a vote on whether Battle Day is at night, and what your base is famous for
 * (just to be recognised for at the end). It runs /rubywars choose, like typing it.
 */
public class SeasonChoicesScreen extends Screen {
	private static final int W = 320;
	/** Each section: a title, a row of buttons, a line under them. */
	private static final int SECTION = 44;
	/** The latest from the server (null until it's said). */
	public static MorePayloads.ChoicesState state;
	private final long opened = System.currentTimeMillis();

	private record Section(String title, String note) {
	}

	private final List<Section> sections = new ArrayList<>();

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

	private boolean teams() {
		return state != null && state.teams();
	}

	private int panelHeight() {
		return 26 + (teams() ? 4 : 3) * SECTION + 22;
	}

	private int left() {
		return width / 2 - W / 2;
	}

	private int top() {
		return Math.max(4, height / 2 - panelHeight() / 2);
	}

	private void choose(String kind, String value) {
		var mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand("rubywars choose " + kind + " " + value);
		}
	}

	/** A row of choice buttons in section {@code row}: {id, label} each, the picked one highlighted. */
	private void row(int row, String kind, String[][] options, String picked, int[] colors, boolean enabled) {
		int x = left() + 12;
		int y = top() + 26 + row * SECTION + 12;
		int gap = 4;
		int bw = (W - 24 - gap * (options.length - 1)) / options.length;
		for (int i = 0; i < options.length; i++) {
			String id = options[i][0];
			boolean on = id.equals(picked);
			UiButton b = new UiButton(x + i * (bw + gap), y, bw, 18, (on ? "> " : "") + options[i][1], on ? colors[Math.min(i, colors.length - 1)] : 0xFF3A3F48,
					btn -> choose(kind, id));
			b.active = enabled;
			addRenderableWidget(b);
		}
	}

	@Override
	protected void init() {
		sections.clear();
		MorePayloads.ChoicesState s = state;
		boolean open = s == null || s.open();
		int r = 0;
		sections.add(new Section("Battle Day", "Sitting out keeps your base safe all day. Watch, or play in the hub."));
		row(r++, "fight", new String[][] {{"yes", "I'll fight"}, {"no", "I'll sit it out"}}, s != null && s.sitOut() ? "no" : "yes",
				new int[] {Ui.RUBY, 0xFF6E7480}, open);
		if (teams()) {
			sections.add(new Section("Battle Day team", "Same colour, same side: you win together."));
			String team = s.team().isEmpty() ? "solo" : s.team();
			row(r++, "team", new String[][] {{"red", "Red team"}, {"blue", "Blue team"}, {"solo", "On my own"}}, team,
					new int[] {Ui.RED, Ui.BLUE, 0xFF6E7480}, open);
		}
		sections.add(new Section("Battle Day at night?", open ? "Most votes win when Battle Day starts." : "Settled when Battle Day started."));
		int night = s == null ? 0 : s.night();
		row(r++, "night", new String[][] {{"yes", "Night: yes"}, {"no", "Night: no"}, {"none", "No vote"}}, night == 1 ? "yes" : night == 2 ? "no" : "none",
				new int[] {Ui.GOLD}, open);
		sections.add(new Section("My base is famous for...", "No bonus: just what you're known for. Change it any time."));
		String theme = s == null || s.theme().isEmpty() ? "none" : s.theme();
		row(r++, "theme", new String[][] {{"walls", "Walls"}, {"factory", "Factory"}, {"garage", "Garage"}, {"airbase", "Air base"}, {"none", "Nothing"}},
				theme, new int[] {Ui.GREEN}, true);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		Ui.dim(g, width, height, Ui.ease(opened, 220));
		int x = left();
		int y = top();
		int h = panelHeight();
		Ui.panel(g, x, y, W, h);
		Ui.heading(g, "Season Choices", x + 12, y + 7, Ui.TEXT, 1.3F, false);
		for (int i = 0; i < sections.size(); i++) {
			int sy = y + 26 + i * SECTION;
			Ui.section(g, sections.get(i).title(), x + 12, sy, W - 24);
			Ui.shrinkText(g, sections.get(i).note(), x + 12, sy + 32, W - 24, Ui.DIM);
		}
		if (state != null) {
			Ui.shrinkText(g, state.summary(), x + 12, y + h - 16, W - 24, Ui.GOLD);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
	}
}
