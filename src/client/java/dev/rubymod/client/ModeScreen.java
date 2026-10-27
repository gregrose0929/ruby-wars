package dev.rubymod.client;

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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The game picker (H): the two main games as big cards on the left (Ruby Wars, and
 * Co-op Horde with a difficulty choice to start it), the party games as a list on the
 * right, and a button back to the hub. Click anything to go and play it.
 */
public class ModeScreen extends Screen {
	private record Game(String id, String title, String about, String icon, int color) {
	}

	private static final List<Game> MAIN = List.of(
			new Game("rubywars", "RUBY WARS", "The main game. Build a base for two weeks, then battle for one day. Last one standing wins.",
					"rubymod:ruby_heart", 0xFFE0303A),
			new Game("horde", "CO-OP HORDE", "Build a base together and defend the Ruby Core from waves of monsters. Spend the team's rubies on gear.",
					"rubymod:arr_rifle", 0xFF3FC85A),
			new Game("rift", "RUBY RIFT", "Co-op roguelike: fight room to room, pick wild relics after each one, and beat the boss at the bottom.",
					"minecraft:end_crystal", 0xFFFF3D6E));

	/** The latest info from the server (where you are, a status line per game). */
	public static MorePayloads.ModeInfo info;

	private int[][] cards = new int[0][];
	private int[] hubButton = new int[4];
	private final int[][] difficultyButtons = new int[3][];
	private static final String[] DIFFICULTIES = {"easy", "normal", "hard"};
	private static final int[] DIFFICULTY_COLORS = {0xFF3FC85A, 0xFFD8A030, 0xFFE0303A};
	private final List<int[]> rows = new ArrayList<>();
	private final List<String> rowIds = new ArrayList<>();
	/** Each party row's Bots button (null: that game has no bots), and its game id. */
	private final List<int[]> botButtons = new ArrayList<>();

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

	private String[] fields(String id) {
		if (info != null) {
			for (String line : info.modes()) {
				String[] f = line.split("[|]");
				if (f.length >= 3 && f[0].equals(id)) {
					return f;
				}
			}
		}
		return null;
	}

	private String status(String id) {
		String[] f = fields(id);
		return f == null ? "..." : f[2];
	}

	private boolean here(String id) {
		return switch (id) {
			case "rubywars" -> where().equals("RUBY_WARS");
			case "horde" -> where().equals("HORDE");
			case "rift" -> where().equals("RIFT");
			default -> where().equals("PARTY:" + id.substring(5));
		};
	}

	/** No Horde game yet: the card offers Easy / Normal / Hard instead of one Play button. */
	private boolean pickDifficulty(Game game) {
		String[] f = fields(game.id());
		return game.id().equals("horde") && f != null && f.length >= 4 && f[3].equals("false") && !here(game.id());
	}

	private static ItemStack icon(String id) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		g.centeredText(font, "CHOOSE A GAME", width / 2, 8, 0xFFFFFFFF);
		int top = 22;
		int bottom = height - 34;
		int colW = Math.min(210, width / 2 - 12);
		int leftX = width / 2 - colW - 4;
		int rightX = width / 2 + 4;
		// Left: the main games.
		int cardH = Math.min(104, (bottom - top - 6 * (MAIN.size() - 1)) / MAIN.size());
		cards = new int[MAIN.size()][];
		for (int i = 0; i < MAIN.size(); i++) {
			Game game = MAIN.get(i);
			int x = leftX;
			int y = top + i * (cardH + 6);
			cards[i] = new int[] {x, y, colW, cardH};
			boolean here = here(game.id());
			boolean hover = !here && mouseX >= x && mouseX < x + colW && mouseY >= y && mouseY < y + cardH;
			g.fill(x, y, x + colW, y + cardH, hover ? 0xF0242C34 : 0xE0161B20);
			g.outline(x, y, colW, cardH, hover ? 0xFFFFFFFF : game.color());
			g.fill(x + 1, y + 1, x + colW - 1, y + 20, game.color() & 0x60FFFFFF);
			g.item(icon(game.icon()), x + 4, y + 2);
			g.text(font, game.title(), x + 24, y + 6, 0xFFFFFFFF, true);
			int ty = y + 24;
			for (FormattedCharSequence line : font.split(Component.literal(game.about()), colW - 10)) {
				if (ty > y + cardH - 40) {
					break;
				}
				g.text(font, line, x + 5, ty, 0xFFD8DEE4, false);
				ty += 10;
			}
			g.text(font, font.plainSubstrByWidth(status(game.id()), colW - 10), x + 5, y + cardH - 32, 0xFFFFC040, false);
			int by = y + cardH - 19;
			if (pickDifficulty(game)) {
				int bw = (colW - 10 - 8) / 3;
				for (int d = 0; d < 3; d++) {
					int bx = x + 5 + d * (bw + 4);
					difficultyButtons[d] = new int[] {bx, by, bw, 15};
					boolean over = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 15;
					g.fill(bx, by, bx + bw, by + 15, over ? DIFFICULTY_COLORS[d] : DIFFICULTY_COLORS[d] & 0xB0FFFFFF);
					g.centeredText(font, DIFFICULTIES[d].toUpperCase(), bx + bw / 2, by + 4, 0xFFFFFFFF);
				}
			} else {
				g.fill(x + 5, by, x + colW - 5, by + 15, here ? 0xFF3A4048 : hover ? game.color() : game.color() & 0xB0FFFFFF);
				g.centeredText(font, here ? "YOU'RE HERE" : "PLAY", x + colW / 2, by + 4, 0xFFFFFFFF);
			}
		}
		// Right: the party games.
		g.text(font, "PARTY GAMES", rightX + 2, top, 0xFFC080FF, true);
		rows.clear();
		rowIds.clear();
		botButtons.clear();
		List<String[]> party = new ArrayList<>();
		if (info != null) {
			for (String line : info.modes()) {
				if (line.startsWith("game:")) {
					String[] f = line.split("[|]", 6);
					if (f.length >= 5) {
						party.add(f);
					}
				}
			}
		}
		int rowH = party.isEmpty() ? 20 : Math.min(24, (bottom - top - 12) / party.size());
		String tooltip = null;
		for (int i = 0; i < party.size(); i++) {
			String[] f = party.get(i);
			int y = top + 12 + i * rowH;
			boolean here = here(f[0]);
			boolean hover = !here && mouseX >= rightX && mouseX < rightX + colW && mouseY >= y && mouseY < y + rowH - 2;
			g.fill(rightX, y, rightX + colW, y + rowH - 2, here ? 0xE0204030 : hover ? 0xF0302440 : 0xD0181520);
			if (hover) {
				g.outline(rightX, y, colW, rowH - 2, 0xFFC080FF);
				tooltip = f[4];
			}
			g.item(icon(f[3]), rightX + 2, y + (rowH - 2 - 16) / 2);
			int textY = y + (rowH - 2) / 2 - (rowH >= 22 ? 8 : 4);
			g.text(font, f[1], rightX + 22, textY, 0xFFFFFFFF, false);
			String s = here ? "You're here" : f[2];
			// Room for the Bots button at the end of the row.
			int reserve = f.length == 6 && !f[5].equals("-") ? font.width("Bots: OFF") + 10 : 0;
			if (rowH >= 22) {
				g.text(font, font.plainSubstrByWidth(s, colW - 26 - reserve), rightX + 22, textY + 10, here ? 0xFF7CF59A : 0xFF9AA4AE, false);
			} else {
				int tw = font.width(f[1]) + 6;
				g.text(font, font.plainSubstrByWidth(s, colW - 26 - tw - reserve), rightX + 22 + tw, textY, here ? 0xFF7CF59A : 0xFF9AA4AE, false);
			}
			// Bots on/off, at the right end of the row.
			int[] botButton = null;
			if (f.length == 6 && !f[5].equals("-")) {
				boolean on = f[5].equals("on");
				int bw2 = font.width("Bots: OFF") + 6;
				int bx2 = rightX + colW - bw2 - 3;
				int bh = Math.min(12, rowH - 6);
				int by2 = y + (rowH - 2 - bh) / 2;
				botButton = new int[] {bx2, by2, bw2, bh};
				boolean overBots = mouseX >= bx2 && mouseX < bx2 + bw2 && mouseY >= by2 && mouseY < by2 + bh;
				g.fill(bx2, by2, bx2 + bw2, by2 + bh, on ? (overBots ? 0xFF3FA055 : 0xD02E7A40) : (overBots ? 0xFF6A5060 : 0xD0403440));
				g.outline(bx2, by2, bw2, bh, on ? 0xFF7CF59A : 0xFF9AA4AE);
				g.centeredText(font, on ? "Bots: ON" : "Bots: OFF", bx2 + bw2 / 2, by2 + (bh - 8) / 2, 0xFFFFFFFF);
				if (overBots) {
					tooltip = on ? "Bots join this game. Click to turn them off (someone playing alone still gets bots)."
							: "No bots unless you're playing alone. Click to turn bots on.";
				}
			}
			botButtons.add(botButton);
			rows.add(new int[] {rightX, y, colW, rowH - 2});
			rowIds.add(f[0]);
		}
		// Bottom: back to the hub.
		boolean inHub = where().equals("HUB");
		int bw = 140;
		int bx = (width - bw) / 2;
		int by = height - 28;
		hubButton = new int[] {bx, by, bw, 16};
		boolean hover = !inHub && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 16;
		g.fill(bx, by, bx + bw, by + 16, inHub ? 0xC0202830 : hover ? 0xF02A6A88 : 0xE0204858);
		g.outline(bx, by, bw, 16, 0xFF55D8FF);
		g.centeredText(font, inHub ? "You're in the hub" : "Back to the hub", width / 2, by + 4, 0xFFFFFFFF);
		g.centeredText(font, "Your Ruby Wars base and things are saved while you're away.", width / 2, height - 10, 0xFF8A949E);
		if (tooltip != null) {
			g.setComponentTooltipForNextFrame(font, List.of(Component.literal(tooltip)), mouseX, mouseY);
		}
	}

	private static boolean inside(MouseButtonEvent event, int[] box) {
		return box != null && event.x() >= box[0] && event.x() < box[0] + box[2] && event.y() >= box[1] && event.y() < box[1] + box[3];
	}

	private void go(String mode) {
		ClientPlayNetworking.send(new MorePayloads.ModeSelect(mode));
		onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (pickDifficulty(MAIN.get(1))) {
			for (int d = 0; d < 3; d++) {
				if (inside(event, difficultyButtons[d])) {
					go("horde:" + DIFFICULTIES[d]);
					return true;
				}
			}
		}
		for (int i = 0; i < cards.length; i++) {
			if (inside(event, cards[i]) && !here(MAIN.get(i).id()) && !pickDifficulty(MAIN.get(i))) {
				go(MAIN.get(i).id());
				return true;
			}
		}
		for (int i = 0; i < botButtons.size(); i++) {
			if (inside(event, botButtons.get(i))) {
				// Stays open: the server sends the picker back with the new setting.
				ClientPlayNetworking.send(new MorePayloads.ModeSelect("bots:" + rowIds.get(i).substring("game:".length())));
				return true;
			}
		}
		for (int i = 0; i < rows.size(); i++) {
			if (inside(event, rows.get(i)) && !here(rowIds.get(i))) {
				go(rowIds.get(i));
				return true;
			}
		}
		if (inside(event, hubButton) && !where().equals("HUB")) {
			go("hub");
			return true;
		}
		return super.mouseClicked(event, doubled);
	}
}
