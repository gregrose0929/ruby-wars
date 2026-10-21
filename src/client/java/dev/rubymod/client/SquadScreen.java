package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

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
	private EditBox name;
	private final List<Button> inviteButtons = new ArrayList<>();

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
		return width / 2 - 150;
	}

	private int top() {
		return height / 2 - 95;
	}

	@Override
	protected void init() {
		inviteButtons.clear();
		int x = left();
		int y = top();
		if (squad().isEmpty()) {
			name = new EditBox(font, x + 10, y + 40, 180, 18, Component.literal("Squad name"));
			name.setMaxLength(16);
			name.setHint(Component.literal("Squad name"));
			addRenderableWidget(name);
			addRenderableWidget(Button.builder(Component.literal("Create squad"), b -> {
				if (!name.getValue().isBlank()) {
					run("squad create " + name.getValue().trim());
				}
			}).bounds(x + 196, y + 39, 94, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("Leave squad"), b -> run("squad leave"))
					.bounds(x + 206, y + 166, 84, 20).build());
		}
		// Invite buttons for everyone online who isn't you or already a squadmate.
		var connection = Minecraft.getInstance().getConnection();
		if (connection != null && !squad().isEmpty()) {
			int i = 0;
			String me = Minecraft.getInstance().player.getName().getString();
			for (PlayerInfo info : connection.getOnlinePlayers()) {
				String player = info.getProfile().name();
				boolean mate = ClientState.map != null && ClientState.map.mates().stream().anyMatch(m -> m.name().equals(player));
				if (player.equals(me) || mate || i >= 8) {
					continue;
				}
				Button b = Button.builder(Component.literal("Invite " + player), btn -> {
					run("squad invite " + player);
					btn.active = false;
				}).bounds(x + 160, y + 46 + i * 22, 130, 20).build();
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
		int x = left();
		int y = top();
		g.fill(x + 3, y + 3, x + 303, y + 193, 0x66000000);
		g.fillGradient(x, y, x + 300, y + 190, 0xF0232830, 0xF0171A1F);
		g.outline(x, y, 300, 190, 0xFF7A0A2A);
		g.fill(x + 1, y + 1, x + 299, y + 16, 0xFF1A1E24);
		g.fill(x + 1, y + 16, x + 299, y + 17, 0xFFD0154A);
		String squad = squad();
		g.text(font, squad.isEmpty() ? "SQUAD" : "SQUAD: " + squad.toUpperCase(), x + 8, y + 5, 0xFFE8EAED, false);
		if (squad.isEmpty()) {
			g.text(font, "You're not in a squad.", x + 10, y + 24, 0xFF8B93A1, false);
			g.text(font, "Make one, or join with an invite:", x + 10, y + 66, 0xFF8B93A1, false);
			g.text(font, "/squad join <name>", x + 10, y + 78, 0xFFC8D0D8, false);
			g.text(font, "Squads of up to 4 share claims, Hearts,", x + 10, y + 100, 0xFF8B93A1, false);
			g.text(font, "research and the win. No friendly fire.", x + 10, y + 112, 0xFF8B93A1, false);
		} else {
			g.text(font, "MEMBERS NEARBY", x + 10, y + 26, 0xFFFF5A82, false);
			int my = y + 42;
			g.text(font, Minecraft.getInstance().player.getName().getString() + " (you)", x + 10, my, 0xFFFFFFFF, false);
			my += 14;
			if (ClientState.map != null) {
				for (var mate : ClientState.map.mates()) {
					g.text(font, mate.name(), x + 10, my, 0xFF7CF59A, false);
					int bw = 50;
					g.fill(x + 96, my + 2, x + 96 + bw, my + 6, 0xFF2A2F35);
					g.fill(x + 96, my + 2, x + 96 + Math.round(bw * Math.max(0, Math.min(1, mate.health()))), my + 6, 0xFF5BE37D);
					my += 14;
				}
			}
			g.text(font, "INVITE", x + 160, y + 26, 0xFFFF5A82, false);
			if (inviteButtons.isEmpty()) {
				g.text(font, "Nobody else online", x + 160, y + 46, 0xFF8B93A1, false);
			}
			g.text(font, "Chat to your squad: /sc <message>", x + 10, y + 172, 0xFF8B93A1, false);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
	}
}
