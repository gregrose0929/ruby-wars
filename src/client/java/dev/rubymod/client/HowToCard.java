package dev.rubymod.client;

import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The how-to card: a game's rules in three pictures and three sentences. The server sends it in a game's lobby (and
 * as you join the Horde, the Rift or the convoy). It comes up by itself only the first time you ever play that game;
 * after that it waits for the help key (N), which shows or hides it, and remembers which you chose (see HelpPrefs).
 * Riding a tank or an aircraft, the same key shows or hides that vehicle's control hints instead.
 */
public class HowToCard implements HudElement {
	private static int showMs = 12_000;
	private static int shownBottom;
	private static String title = "";
	private static List<String> lines = List.of();
	/** This game's card is there to show (from joining until leaving), and whether it's on screen. */
	private static boolean available;
	private static boolean visible;
	private static long shownAt;

	public static void show(MorePayloads.HowTo card) {
		if (card.seconds() < 0) {
			// Left the game: nothing to show any more.
			available = false;
			visible = false;
			return;
		}
		if (card.lines().isEmpty()) {
			// The round started: off the screen (the help key still brings it back).
			visible = false;
			return;
		}
		title = card.title();
		lines = card.lines();
		available = true;
		showMs = card.seconds() * 1000;
		shownAt = System.currentTimeMillis();
		visible = HelpPrefs.wanted("game:" + title);
	}

	/** The help key (N). */
	public static void helpKey(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		String key;
		String what;
		if (mc.player.getVehicle() instanceof dev.rubymod.entity.AircraftEntity) {
			key = "aircraft";
			what = "Flying hints";
		} else if (mc.player.getVehicle() instanceof dev.rubymod.entity.VehicleEntity v) {
			key = v.isTank() ? "vehicle:tank" : "vehicle:jeep";
			what = v.isTank() ? "Tank controls" : "Jeep controls";
		} else if (available) {
			boolean on = HelpPrefs.toggle("game:" + title, visible);
			visible = on;
			if (on) {
				// Up until it's hidden again.
				showMs = Integer.MAX_VALUE;
				shownAt = System.currentTimeMillis();
			}
			mc.player.sendOverlayMessage(Component.literal(on ? "How to play: shown (N to hide)" : "How to play: hidden (N shows it again)"));
			return;
		} else {
			mc.player.sendOverlayMessage(Component.literal("Nothing to explain here. N shows how to play a game, or a vehicle's controls."));
			return;
		}
		boolean on = HelpPrefs.toggle(key, HelpPrefs.on(key) || VehicleHelp.firstLook(key));
		VehicleHelp.endFirstLook(key);
		mc.player.sendOverlayMessage(Component.literal(what + (on ? ": shown (N to hide)" : ": hidden (N shows them again)")));
	}

	/**
	 * On a narrow screen the card spans the top-left corner where the games keep their info panel: those panels step
	 * aside while it's up.
	 */
	public static boolean covers(int screenWidth) {
		return bottom() > 0 && narrow(screenWidth);
	}

	/** Too narrow for the card and the corner panels side by side. */
	public static boolean narrow(int screenWidth) {
		return screenWidth / 2 - Math.min(250, screenWidth - 20) / 2 < 180;
	}

	/** Where the card ends on screen right now (0 when it isn't up), so big titles can sit under it. */
	public static int bottom() {
		return up() && Minecraft.getInstance().gui.screen() == null ? shownBottom : 0;
	}

	private static boolean up() {
		return visible && !lines.isEmpty() && System.currentTimeMillis() - shownAt <= showMs;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		long age = System.currentTimeMillis() - shownAt;
		Minecraft mc = Minecraft.getInstance();
		if (!up() || mc.gui.screen() != null) {
			shownBottom = 0;
			return;
		}
		// On a narrow screen a big announcement gets the top of the screen for its few seconds.
		if (narrow(g.guiWidth()) && BigTitle.active()) {
			return;
		}
		int w = Math.min(250, g.guiWidth() - 20);
		int rowH = 20;
		int h = 20 + lines.size() * rowH + 14;
		// Slides down into place, and back up at the end.
		float in = Math.min(1.0F, age / 300.0F);
		float out = showMs == Integer.MAX_VALUE ? 1 : Math.min(1.0F, (showMs - age) / 400.0F);
		float shown = Math.min(in, out);
		int x = g.guiWidth() / 2 - w / 2;
		// Under the boss bars (a game has one or two up there).
		int y = 44 - Math.round((1 - shown) * (h + 48));
		shownBottom = y + h;
		Ui.panel(g, x, y, w, h);
		Ui.heading(g, "How to play: " + title, g.guiWidth() / 2, y + 7, Ui.GOLD, 1.0F, true);
		for (int i = 0; i < lines.size(); i++) {
			String[] f = lines.get(i).split("[|]", 2);
			int ry = y + 20 + i * rowH;
			Ui.inset(g, x + 8, ry, 18, 18);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[0]))), x + 9, ry + 1);
			Ui.wrap(g, f.length > 1 ? f[1] : "", x + 32, ry + (mc.font.width(f.length > 1 ? f[1] : "") > w - 42 ? 0 : 5), w - 42, Ui.TEXT, ry + rowH);
		}
		g.centeredText(mc.font, "N: hide (it stays hidden next time)", g.guiWidth() / 2, y + h - 11, Ui.MUTED);
	}
}
