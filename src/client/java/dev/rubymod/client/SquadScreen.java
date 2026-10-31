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
 * The Squad screen (O): your squad and its members (with health when they're
 * near), make or leave a squad, and invite anyone online with one click. It runs
 * the same /squad commands you could type.
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
		inviteButtons.clear();
		int x = left();
		int y = top();
		if (squad().isEmpty()) {
			name = new EditBox(font, x + 16, y + 52, 168, 12, Component.literal("Squad name"));
			name.setMaxLength(16);
			name.setBordered(false);
			name.setHint(Component.literal("Squad name"));
			addRenderableWidget(name);
			addRenderableWidget(new UiButton(x + 196, y + 46, 92, 20, "Create squad", Ui.RUBY, b -> {
				if (!name.getValue().isBlank()) {
					run("squad create " + name.getValue().trim());
				}
			}));
		} else {
			addRenderableWidget(new UiButton(x + W - 96, y + H - 28, 84, 18, "Leave squad", 0xFF5A606C, b -> run("squad leave")));
		}
		// Invite buttons for everyone online who isn't you or already a squadmate.
		var connection = Minecraft.getInstance().getConnection();
		if (connection != null && !squad().isEmpty()) {
			int i = 0;
			String me = Minecraft.getInstance().player.getName().getString();
			for (PlayerInfo info : connection.getOnlinePlayers()) {
				String player = info.getProfile().name();
				boolean mate = ClientState.map != null && ClientState.map.mates().stream().anyMatch(m -> m.name().equals(player));
				if (player.equals(me) || mate || i >= 6) {
					continue;
				}
				Button b = new UiButton(x + 160, y + 48 + i * 20, 128, 18, "Invite " + player, Ui.GREEN, btn -> {
					run("squad invite " + player);
					btn.active = false;
				});
				inviteButtons.add(b);
				addRenderableWidget(b);
				i++;
			}
		}
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
		Ui.heading(g, squad.isEmpty() ? "Squad" : squad, x + 12, y + 10, Ui.TEXT, 1.5F, false);
		if (squad.isEmpty()) {
			Ui.text(g, "You're not in a squad yet.", x + 12, y + 30, Ui.DIM);
			Ui.inset(g, x + 10, y + 46, 180, 20);
			Ui.section(g, "Join one", x + 12, y + 82, W - 24);
			Ui.text(g, "Ask a friend to invite you, or type", x + 12, y + 96, Ui.DIM);
			Ui.text(g, "/squad join <name>", x + 12, y + 108, Ui.GOLD);
			Ui.section(g, "Why squad up?", x + 12, y + 128, W - 24);
			Ui.wrap(g, "Up to 4 of you share claims, Hearts, upgrades and the win. You can't hurt each other.", x + 12, y + 142, W - 24, Ui.DIM,
					y + H - 6);
		} else {
			Ui.section(g, "Members", x + 12, y + 32, 132);
			int my = y + 48;
			Ui.icon(g, Ui.Icon.CROWN, x + 12, my - 1);
			Ui.text(g, Minecraft.getInstance().player.getName().getString() + " (you)", x + 24, my, Ui.TEXT);
			my += 16;
			if (ClientState.map != null) {
				for (var mate : ClientState.map.mates()) {
					Ui.icon(g, Ui.Icon.HEART, x + 12, my - 1);
					Ui.text(g, mate.name(), x + 24, my, 80, Ui.TEXT);
					Ui.bar(g, x + 106, my + 1, 40, 6, Math.max(0, Math.min(1, mate.health())), Ui.GREEN);
					my += 16;
				}
			}
			Ui.section(g, "Invite", x + 160, y + 32, W - 172);
			if (inviteButtons.isEmpty()) {
				Ui.text(g, "Nobody else is online.", x + 160, y + 50, Ui.MUTED);
			}
			Ui.text(g, "Squad chat: /sc <message>", x + 12, y + H - 22, Ui.MUTED);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
	}
}
