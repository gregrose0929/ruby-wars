package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.client.ui.UiButton;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * The Team screen: who's on your Battle Day team (with health when they're near). Teams are picked on the Season Choices
 * screen before Battle Day, so there's nothing to make or join here.
 */
public class SquadScreen extends Screen {
	private static final int W = 300;
	private static final int H = 190;
	private EditBox name;
	private final List<Button> inviteButtons = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	public SquadScreen() {
		super(Component.literal("Squad"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private String squad() {
		return ClientState.map == null ? "" : ClientState.map.squad();
	}

	private int left() {
		return width / 2 - W / 2;
	}

	private int top() {
		return height / 2 - H / 2;
	}

	@Override
	protected void init() {
		// Teams come from the Season Choices (picked before Battle Day): nothing to make, join or leave here.
	}

	private void run(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand(command);
		}
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		float in = Ui.ease(opened, 220);
		Ui.dim(g, width, height, in);
		int x = left();
		int y = top();
		Ui.panel(g, x, y, W, H);
		String squad = squad();
		Ui.heading(g, squad.isEmpty() ? "Battle Day" : squad, x + 12, y + 10, Ui.TEXT, 1.5F, false);
		if (squad.isEmpty()) {
			Ui.text(g, "You're on your own this Battle Day.", x + 12, y + 34, Ui.DIM);
			Ui.wrap(g, "Teams are picked before Battle Day, on the Season Choices screen (Ruby Wars menu).", x + 12, y + 52, W - 24, Ui.DIM,
					y + H - 6);
		} else {
			Ui.section(g, "On your team", x + 12, y + 32, W - 24);
			int my = y + 48;
			Ui.icon(g, Ui.Icon.CROWN, x + 12, my - 1);
			Ui.text(g, Minecraft.getInstance().player.getName().getString() + " (you)", x + 24, my, Ui.TEXT);
			my += 16;
			if (ClientState.map != null) {
				for (var mate : ClientState.map.mates()) {
					Ui.icon(g, Ui.Icon.HEART, x + 12, my - 1);
					Ui.text(g, mate.name(), x + 24, my, 160, Ui.TEXT);
					Ui.bar(g, x + 190, my + 1, 60, 6, Math.max(0, Math.min(1, mate.health())), Ui.GREEN);
					my += 16;
				}
			}
			Ui.wrap(g, "You can't hurt each other, your defenses leave each other alone, you can respawn at each other's Hearts, and you win together.",
					x + 12, my + 6, W - 24, Ui.DIM, y + H - 28);
			Ui.text(g, "Team chat: /sc <message>", x + 12, y + H - 22, Ui.MUTED);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
	}
}
