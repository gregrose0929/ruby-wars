package dev.rubymod.client;

import java.util.List;

import dev.rubymod.RubyMod;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The game picker (H): a big card for each game, and a button back to the hub.
 * Click a card to go and play it.
 */
public class ModeScreen extends Screen {
	private record Game(String id, String title, String about, String icon, int color) {
	}

	private static final List<Game> GAMES = List.of(
			new Game("rubywars", "RUBY WARS", "The main game. Build a base for two weeks, then battle for one day. Last one standing wins.",
					"ruby_heart", 0xFFE0303A),
			new Game("horde", "CO-OP HORDE", "Team up against waves of zombies, skeletons and soldiers. Clear a wave to earn new gear.",
					"arr_rifle", 0xFF3FC85A));

	/** The latest info from the server (where you are, a status line per game). */
	public static MorePayloads.ModeInfo info;

	private int[][] cards = new int[0][];
	private int[] hubButton = new int[4];

	public ModeScreen() {
		super(Component.literal("Choose a game"));
		ClientPlayNetworking.send(new MorePayloads.ModeSelect(""));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private String where() {
		return info == null ? "" : info.where();
	}

	private String status(String id) {
		if (info != null) {
			for (String line : info.modes()) {
				String[] f = line.split("\\|", 3);
				if (f.length == 3 && f[0].equals(id)) {
					return f[2];
				}
			}
		}
		return "...";
	}

	private boolean here(Game game) {
		return game.id().equals("rubywars") ? where().equals("RUBY_WARS") : where().equals("HORDE");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		int cardW = Math.min(180, (width - 30) / 2);
		int cardH = 136;
		int gap = 10;
		int x0 = (width - cardW * 2 - gap) / 2;
		int y0 = Math.max(34, (height - cardH) / 2 - 10);
		g.centeredText(font, "CHOOSE A GAME", width / 2, y0 - 22, 0xFFFFFFFF);
		cards = new int[GAMES.size()][];
		for (int i = 0; i < GAMES.size(); i++) {
			Game game = GAMES.get(i);
			int x = x0 + i * (cardW + gap);
			int y = y0;
			cards[i] = new int[] {x, y, cardW, cardH};
			boolean here = here(game);
			boolean hover = !here && mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
			g.fill(x, y, x + cardW, y + cardH, hover ? 0xF0242C34 : 0xE0161B20);
			g.outline(x, y, cardW, cardH, hover ? 0xFFFFFFFF : game.color());
			g.fill(x + 1, y + 1, x + cardW - 1, y + 24, game.color() & 0x60FFFFFF);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(RubyMod.id(game.icon()))), x + 6, y + 4);
			g.text(font, game.title(), x + 28, y + 8, 0xFFFFFFFF, true);
			int ty = y + 32;
			for (FormattedCharSequence line : font.split(Component.literal(game.about()), cardW - 14)) {
				g.text(font, line, x + 7, ty, 0xFFD8DEE4, false);
				ty += 10;
			}
			ty += 4;
			for (FormattedCharSequence line : font.split(Component.literal(status(game.id())), cardW - 14)) {
				g.text(font, line, x + 7, ty, 0xFFFFC040, false);
				ty += 10;
			}
			// The play button along the bottom.
			int by = y + cardH - 22;
			g.fill(x + 6, by, x + cardW - 6, by + 16, here ? 0xFF3A4048 : hover ? game.color() : game.color() & 0xB0FFFFFF);
			g.centeredText(font, here ? "YOU'RE HERE" : "PLAY", x + cardW / 2, by + 4, 0xFFFFFFFF);
		}
		boolean inHub = where().equals("HUB");
		int bw = 140;
		int bx = (width - bw) / 2;
		int by = y0 + cardH + 12;
		hubButton = new int[] {bx, by, bw, 18};
		boolean hover = !inHub && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 18;
		g.fill(bx, by, bx + bw, by + 18, inHub ? 0xC0202830 : hover ? 0xF02A6A88 : 0xE0204858);
		g.outline(bx, by, bw, 18, 0xFF55D8FF);
		g.centeredText(font, inHub ? "You're in the hub" : "Back to the hub", width / 2, by + 5, 0xFFFFFFFF);
		g.centeredText(font, "Your Ruby Wars base and things are saved while you're away.", width / 2, by + 26, 0xFF8A949E);
	}

	private static boolean inside(MouseButtonEvent event, int[] box) {
		return event.x() >= box[0] && event.x() < box[0] + box[2] && event.y() >= box[1] && event.y() < box[1] + box[3];
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < cards.length; i++) {
			if (inside(event, cards[i]) && !here(GAMES.get(i))) {
				ClientPlayNetworking.send(new MorePayloads.ModeSelect(GAMES.get(i).id()));
				onClose();
				return true;
			}
		}
		if (inside(event, hubButton) && !where().equals("HUB")) {
			ClientPlayNetworking.send(new MorePayloads.ModeSelect("hub"));
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubled);
	}
}
