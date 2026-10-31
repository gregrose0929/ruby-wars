package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
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
 * The game picker (H): the logo on top, the four main games down the left (in the same
 * order as the hub's gates), the party games in a list on the right, and the way back to
 * the hub at the bottom. Click anything to go and play it.
 */
public class ModeScreen extends Screen {
	private record Game(String id, String title, String about, String emblem, int color) {
	}

	private static final List<Game> MAIN = List.of(
			new Game("rubywars", "Ruby Wars", "Build a base for two weeks, then battle for the win.", "rubywars", Ui.WARS),
			new Game("rift", "Ruby Rift", "Fight room to room and build a wild set of relics.", "rift", Ui.RIFT),
			new Game("convoy", "Ruby Convoy", "Guard the ruby truck through five stages.", "convoy", Ui.CONVOY),
			new Game("horde", "Co-op Horde", "Defend the Ruby Core from waves, together.", "horde", Ui.HORDE));

	/** The latest info from the server (where you are, a status line per game). */
	public static MorePayloads.ModeInfo info;

	private static final String[] DIFFICULTIES = {"easy", "normal", "hard"};
	private static final int[] DIFFICULTY_COLORS = {Ui.GREEN, Ui.AMBER, Ui.RED};
	private final long opened = System.currentTimeMillis();
	private long asked = System.currentTimeMillis();
	/** What was drawn where this frame (for clicks): {x, y, w, h} and what it does. */
	private final List<int[]> boxes = new ArrayList<>();
	private final List<String> actions = new ArrayList<>();

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
		return f == null ? "..." : f[2].replace("  •  ", ", ");
	}

	private boolean here(String id) {
		return switch (id) {
			case "rubywars" -> where().equals("RUBY_WARS");
			case "horde" -> where().equals("HORDE");
			case "rift" -> where().equals("RIFT");
			case "convoy" -> where().equals("CONVOY");
			default -> where().equals("PARTY:" + id.substring(5));
		};
	}

	/** No Horde game yet: its card offers Easy / Normal / Hard instead of one Play button. */
	private boolean pickDifficulty(Game game) {
		String[] f = fields(game.id());
		return game.id().equals("horde") && f != null && f.length >= 4 && f[3].equals("false") && !here(game.id());
	}

	private static ItemStack icon(String id) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
	}

	private void click(int x, int y, int w, int h, String action) {
		boxes.add(new int[] {x, y, w, h});
		actions.add(action);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		boxes.clear();
		actions.clear();
		// Nothing from the server yet (the request went before it was listening): ask again.
		if (info == null && System.currentTimeMillis() - asked > 1000) {
			asked = System.currentTimeMillis();
			ClientPlayNetworking.send(new MorePayloads.ModeSelect(""));
		}
		float in = Ui.ease(opened, 260);
		Ui.dim(g, width, height, in);
		Ui.logo(g, width / 2, 6 - Math.round((1 - in) * 8), 1);
		g.centeredText(font, "Choose a game", width / 2, 33, Ui.alpha(Ui.DIM, in));
		int top = 46;
		int bottom = height - 30;
		int colW = Math.min(230, width / 2 - 14);
		int leftX = width / 2 - colW - 4;
		int rightX = width / 2 + 4;
		List<String> tooltip = null;
		// Left: the main games.
		int gap = 4;
		int cardH = Math.min(52, (bottom - top - gap * (MAIN.size() - 1)) / MAIN.size());
		for (int i = 0; i < MAIN.size(); i++) {
			Game game = MAIN.get(i);
			float slide = Ui.ease(opened + 40L * i, 280);
			int x = leftX - Math.round((1 - slide) * 24);
			int y = top + i * (cardH + gap);
			boolean here = here(game.id());
			boolean choose = pickDifficulty(game);
			boolean over = !here && !choose && Ui.inside(mouseX, mouseY, x, y, colW, cardH);
			Ui.card(g, x, y, colW, cardH, game.color(), Ui.hover("mode:" + game.id(), over));
			int es = cardH >= 44 ? 32 : 24;
			Ui.emblem(g, game.emblem(), x + 6, y + (cardH - es) / 2, es);
			int tx = x + 12 + es;
			int textW = colW - (tx - x) - 62;
			Ui.heading(g, game.title(), tx, y + 7, Ui.TEXT, 1.0F, false);
			if (cardH >= 40) {
				Ui.text(g, game.about(), tx, y + 19, textW + 56, Ui.DIM);
			}
			Ui.icon(g, Ui.Icon.DOT, tx - 2, y + cardH - 14);
			Ui.text(g, status(game.id()), tx + 8, y + cardH - 13, textW, here ? Ui.GREEN : Ui.GOLD);
			int bx = x + colW - 58;
			int by = y + cardH - 20;
			if (choose) {
				for (int d = 0; d < 3; d++) {
					int dx = x + colW - 6 - (3 - d) * 34;
					String label = DIFFICULTIES[d].substring(0, 1).toUpperCase() + DIFFICULTIES[d].substring(1);
					if (Ui.button(g, label, dx, by - 2, 32, 16, mouseX, mouseY, DIFFICULTY_COLORS[d], true)) {
						tooltip = List.of(label + " Horde", d == 0 ? "Fewer, weaker monsters." : d == 1 ? "The usual fight." : "More monsters, and tougher.");
					}
					click(dx, by - 2, 32, 16, "horde:" + DIFFICULTIES[d]);
				}
			} else if (here) {
				Ui.tag(g, "You're here", x + colW - 6 - Ui.font().width("You're here") - 6, y + 7, Ui.GREEN);
			} else {
				Ui.button(g, "Play", bx, by - 2, 52, 16, mouseX, mouseY, game.color(), true);
				click(x, y, colW, cardH, game.id());
			}
		}
		// Right: the party games.
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
		float fade = Ui.ease(opened + 120, 300);
		int listH = bottom - top;
		Ui.panel(g, rightX, top, colW, listH, fade);
		Ui.emblem(g, "party", rightX + 7, top + 6, 16);
		Ui.heading(g, "Party games", rightX + 28, top + 10, Ui.TEXT, 1.0F, false);
		int rowsTop = top + 26;
		int rowH = party.isEmpty() ? 20 : Math.max(16, Math.min(22, (listH - 32) / party.size()));
		Ui.inset(g, rightX + 5, rowsTop - 2, colW - 10, Math.min(listH - 30, party.size() * rowH + 4));
		for (int i = 0; i < party.size(); i++) {
			String[] f = party.get(i);
			int y = rowsTop + i * rowH;
			if (y + rowH > top + listH - 4) {
				break;
			}
			boolean here = here(f[0]);
			boolean hasBots = f.length == 6 && !f[5].equals("-");
			int botW = 46;
			boolean overBots = hasBots && Ui.inside(mouseX, mouseY, rightX + colW - 10 - botW, y + 1, botW, rowH - 2);
			boolean over = !here && !overBots && Ui.inside(mouseX, mouseY, rightX + 7, y, colW - 14, rowH);
			float hv = Ui.hover("party:" + f[0], over);
			if (hv > 0 || here) {
				g.fill(rightX + 7, y, rightX + colW - 7, y + rowH - 1, here ? 0x3028A050 : Ui.alpha(0x30FFFFFF, hv * 0.7F));
			}
			g.pose().pushMatrix();
			g.pose().translate(rightX + 9, y + (rowH - 12) / 2F);
			g.pose().scale(0.75F, 0.75F);
			g.item(icon(f[3]), 0, 0);
			g.pose().popMatrix();
			int nameW = Ui.font().width(f[1]);
			Ui.text(g, f[1], rightX + 25, y + (rowH - 8) / 2, here ? Ui.GREEN : Ui.TEXT);
			String s = here ? "You're here" : f[2];
			Ui.text(g, s, rightX + 31 + nameW, y + (rowH - 8) / 2, colW - 46 - nameW - (hasBots ? botW + 4 : 0), Ui.MUTED);
			if (over) {
				tooltip = List.of(f[1], f[4]);
			}
			if (hasBots) {
				boolean on = f[5].equals("on");
				int bx = rightX + colW - 10 - botW;
				if (Ui.button(g, on ? "Bots on" : "Bots off", bx, y + 1, botW, rowH - 2, mouseX, mouseY, on ? Ui.GREEN : 0xFF5A606C, true)) {
					tooltip = on ? List.of("Bots join this game", "Click to turn them off. Someone playing alone still gets bots.")
							: List.of("No bots", "Unless you're playing alone. Click to turn bots on.");
				}
				click(bx, y + 1, botW, rowH - 2, "bots:" + f[0].substring(5));
			}
			if (!here) {
				click(rightX + 7, y, colW - 14, rowH, f[0]);
			}
		}
		// Bottom: back to the hub.
		boolean inHub = where().equals("HUB");
		int bw = 132;
		int bx = (width - bw) / 2;
		int by = height - 24;
		Ui.button(g, inHub ? "You're in the hub" : "Back to the hub", bx, by, bw, 18, mouseX, mouseY, Ui.HUB, !inHub);
		if (!inHub) {
			click(bx, by, bw, 18, "hub");
		}
		if (tooltip != null) {
			Ui.tooltip(g, tooltip, mouseX, mouseY, width, height);
		}
	}

	private void go(String mode) {
		ClientPlayNetworking.send(new MorePayloads.ModeSelect(mode));
		onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		// Later boxes sit on top (buttons inside rows), so look from the end.
		for (int i = boxes.size() - 1; i >= 0; i--) {
			int[] b = boxes.get(i);
			if (Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
				String action = actions.get(i);
				if (action.startsWith("bots:")) {
					// Stays open: the server sends the picker back with the new setting.
					ClientPlayNetworking.send(new MorePayloads.ModeSelect(action));
				} else {
					go(action);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
