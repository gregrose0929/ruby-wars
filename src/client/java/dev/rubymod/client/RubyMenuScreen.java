package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import dev.rubymod.client.ui.Ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The Ruby Wars menu (M): one place for everything: the map, your guide and goals, your profile, the Field Manual,
 * the game picker and your settings (plus your squad when squads are on, and the admin panel for admins). The keys
 * for each still work as shortcuts. Number keys pick a tile.
 */
public class RubyMenuScreen extends Screen {
	private record Tile(String title, String key, String about, String icon, Supplier<Screen> open) {
	}

	private final List<Tile> tiles = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	public RubyMenuScreen() {
		super(Component.literal("Ruby Wars"));
		tiles.add(new Tile("Map", "K", "The island, your base, pings", "minecraft:filled_map", dev.rubymod.client.map.MapScreen::new));
		tiles.add(new Tile("Guide & Goals", "J", "What to do next, stars, upgrades", "minecraft:nether_star", ResearchScreen::new));
		if (ClientState.inRubyWarsWorld()) {
			tiles.add(new Tile("My Base", "", "Your power and every machine", "rubymod:ruby_drill", BaseScreen::new));
			tiles.add(new Tile("Season Choices", "", "Teams, a Night Battle vote, your base", "minecraft:white_banner", SeasonChoicesScreen::new));
		}
		tiles.add(new Tile("Profile", "I", "Your stats and cosmetics", "minecraft:player_head", ProfileScreen::new));
		tiles.add(new Tile("Field Manual", "Y", "How everything works", "minecraft:book", ManualScreen::new));
		tiles.add(new Tile("Games", "H", "The hub, Ruby Wars, party games", "minecraft:compass", ModeScreen::new));
		tiles.add(new Tile("Settings", "", "HUD, minimap, toasts", "minecraft:comparator", SettingsScreen::new));
		if (ClientState.hud != null && ClientState.hud.squads()) {
			tiles.add(new Tile("Team", "", "Your Battle Day team", "minecraft:shield", SquadScreen::new));
		}
		var player = Minecraft.getInstance().player;
		if (player != null && player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
			tiles.add(new Tile("Admin", "", "Seasons, Battle Day, the server", "minecraft:command_block", AdminScreen::new));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 180));
		int cols = width >= 420 ? 4 : width >= 320 ? 3 : 2;
		int tw = Math.min(110, (width - 24 - (cols - 1) * 6) / cols);
		int th = 54;
		int rows = (tiles.size() + cols - 1) / cols;
		int totalW = cols * tw + (cols - 1) * 6;
		int totalH = 36 + rows * (th + 6);
		int x0 = (width - totalW) / 2;
		int y0 = Ui.centre(height, totalH) + Math.round((1 - Ui.ease(opened, 220)) * 10);
		Ui.logo(g, width / 2, y0, 1);
		boxes.clear();
		for (int i = 0; i < tiles.size(); i++) {
			Tile t = tiles.get(i);
			int x = x0 + (i % cols) * (tw + 6);
			int y = y0 + 36 + (i / cols) * (th + 6);
			boxes.add(new int[] {x, y, tw, th});
			boolean over = Ui.inside(mouseX, mouseY, x, y, tw, th);
			Ui.card(g, x, y, tw, th, Ui.RUBY, Ui.hover(t, over));
			ItemStack icon = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(t.icon())));
			g.pose().pushMatrix();
			g.pose().translate(x + tw / 2.0F - 12, y + 6);
			g.pose().scale(1.5F, 1.5F);
			g.item(icon, 0, 0);
			g.pose().popMatrix();
			g.centeredText(font, Ui.fit(t.title(), tw - 6), x + tw / 2, y + 32, over ? Ui.TEXT : Ui.DIM);
			String sub = (i < 9 ? (i + 1) + "  " : "") + (t.key().isEmpty() ? "" : "[" + t.key() + "]");
			g.centeredText(font, sub, x + tw / 2, y + 43, Ui.MUTED);
			if (over) {
				Ui.tooltip(g, List.of(t.title(), t.about()), mouseX, mouseY, width, height);
			}
		}
		Ui.foot(g, "Esc to close  -  the letter keys open each one directly", width, y0 + totalH + 4, Ui.MUTED);
	}

	private void open(int i) {
		if (i >= 0 && i < tiles.size()) {
			Minecraft.getInstance().gui.setScreen(tiles.get(i).open().get());
			Ui.click();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < boxes.size(); i++) {
			int[] b = boxes.get(i);
			if (Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
				open(i);
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (k >= 49 && k <= 57) {
			open(k - 49);
			return true;
		}
		return super.keyPressed(event);
	}
}
