package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.RubyMod;
import dev.rubymod.client.ui.Ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "How it works": a little film of every Armory item in use (a Keycard Door opening for its Keycard, a jeep driving
 * off, a Sentry Turret firing on a Practice Dummy...), playing over and over, with what it's for under it. Grouped
 * as the Armory is; scroll the list. Opens at the item you're holding (or the one you were looking at in the Armory,
 * which it goes back to). The films are real gameplay, filmed by the "clips" client test (HelpFilms), made into videos
 * by MakeFilms and played by FilmPlayer.
 */
public class HowItWorksScreen extends Screen {
	private static final int ROW = 18;
	private static final int HEADING = 13;

	private record Clip(FilmPlayer.Film film, ItemStack icon, String name, String text) {
	}

	private final List<Clip> clips = new ArrayList<>();
	/** On screen: {x, y, w, h, index} for each item row. */
	private final List<int[]> rows = new ArrayList<>();
	private final Screen parent;
	private int picked;
	private int scroll;
	private int scrollMax;
	private boolean scrollToPicked;
	private final long opened = System.currentTimeMillis();

	/** From the menu: open at what you're holding, if it has a film. */
	public HowItWorksScreen() {
		this(held(), null);
	}

	private static String held() {
		var mc = Minecraft.getInstance();
		if (mc.player == null) {
			return null;
		}
		var id = BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem());
		return id.getNamespace().equals(RubyMod.MOD_ID) ? id.getPath() : null;
	}

	/** Open at this item's film ({@code pick}, or null for the first), going back to {@code parent} after (or null). */
	public HowItWorksScreen(String pick, Screen parent) {
		super(Component.literal("How It Works"));
		this.parent = parent;
		for (FilmPlayer.Film f : FilmPlayer.films().values()) {
			clips.add(clip(f));
		}
		// Grouped by section, in the order each section first comes.
		List<String> order = new ArrayList<>();
		for (Clip c : clips) {
			if (!order.contains(c.film().section())) {
				order.add(c.film().section());
			}
		}
		clips.sort(java.util.Comparator.comparingInt(c -> order.indexOf(c.film().section())));
		for (int i = 0; i < clips.size(); i++) {
			if (clips.get(i).film().id().equals(pick)) {
				picked = i;
				scrollToPicked = true;
			}
		}
	}

	/** The icon, name and words for a film: an item's own, or the Heart upgrade's, or a ready-made plan's. */
	private static Clip clip(FilmPlayer.Film f) {
		if (f.id().equals("heart_upgrade")) {
			ArmoryGuide.Entry g = ArmoryGuide.get("heart_upgrade");
			return new Clip(f, new ItemStack(Items.NETHER_STAR), "Heart Upgrade", g == null ? "" : g.text());
		}
		if (f.id().startsWith("blueprint_")) {
			for (var d : dev.rubymod.industry.ReadyBlueprints.ALL) {
				if (f.id().equals("blueprint_" + d.id())) {
					return new Clip(f, new ItemStack(dev.rubymod.registry.ModItems.BLUEPRINT), "Plan: " + d.title(),
							d.blurb() + ". Put it in a Constructor, with the blocks in a chest beside it, and it builds itself.");
				}
			}
		}
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(RubyMod.id(f.id())));
		ArmoryGuide.Entry g = ArmoryGuide.get(RubyMod.MOD_ID + ":" + f.id());
		return new Clip(f, stack, stack.getHoverName().getString(), g == null ? "" : g.text());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		FilmPlayer.stop();
		if (parent != null) {
			minecraft.gui.setScreen(parent);
		} else {
			super.onClose();
		}
	}

	@Override
	public void removed() {
		FilmPlayer.stop();
		super.removed();
	}

	private int panelW() {
		return Math.min(width - 16, 500);
	}

	private int panelH() {
		return Math.min(height - 16, 320);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 180));
		int pw = panelW();
		int ph = panelH();
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		Ui.panel(g, px, py, pw, ph);
		Ui.heading(g, "How It Works", px + 10, py + 7, Ui.TEXT, 1.3F, false);
		if (parent != null) {
			Ui.text(g, "Esc: back to the Armory", px + pw - 10 - font.width("Esc: back to the Armory"), py + 9, Ui.MUTED);
		}
		if (clips.isEmpty()) {
			Ui.text(g, "No films here yet.", px + 10, py + 30, Ui.DIM);
			return;
		}
		// The list down the left, by section, scrolling.
		int listW = 128;
		int top = py + 24;
		int bottom = py + ph - 8;
		rows.clear();
		int total = 0;
		int pickedY = 0;
		String section = null;
		for (int i = 0; i < clips.size(); i++) {
			Clip c = clips.get(i);
			if (!c.film().section().equals(section)) {
				section = c.film().section();
				total += HEADING;
			}
			if (i == picked) {
				pickedY = total;
			}
			total += ROW;
		}
		scrollMax = Math.max(0, total - (bottom - top));
		if (scrollToPicked) {
			scroll = pickedY - (bottom - top) / 3;
			scrollToPicked = false;
		}
		scroll = Math.max(0, Math.min(scroll, scrollMax));
		g.enableScissor(px + 4, top, px + 10 + listW, bottom);
		int y = top - scroll;
		section = null;
		for (int i = 0; i < clips.size(); i++) {
			Clip c = clips.get(i);
			if (!c.film().section().equals(section)) {
				section = c.film().section();
				Ui.text(g, section, px + 8, y + 3, listW, Ui.GOLD);
				y += HEADING;
			}
			boolean on = i == picked;
			boolean over = Ui.inside(mouseX, mouseY, px + 8, y, listW, ROW - 2) && mouseY >= top && mouseY < bottom;
			g.fill(px + 8, y, px + 8 + listW, y + ROW - 2, on ? 0x55E0203A : over ? 0x22FFFFFF : 0x11FFFFFF);
			g.item(c.icon(), px + 9, y);
			Ui.text(g, c.name(), px + 28, y + 4, listW - 22, on ? Ui.TEXT : Ui.DIM);
			if (y + ROW > top && y < bottom) {
				rows.add(new int[] {px + 8, y, listW, ROW - 2, i});
			}
			y += ROW;
		}
		g.disableScissor();
		if (scrollMax > 0) {
			int barH = Math.max(16, (bottom - top) * (bottom - top) / total);
			int barY = top + (int) ((bottom - top - barH) * (scroll / (float) scrollMax));
			g.fill(px + 10 + listW, barY, px + 12 + listW, barY + barH, 0x66FFFFFF);
		}
		// The film, and what it's for.
		Clip c = clips.get(Math.min(picked, clips.size() - 1));
		FilmPlayer.play(c.film());
		float through = FilmPlayer.tick();
		int fx = px + listW + 18;
		int fw = pw - listW - 26;
		int fh = fw * 9 / 16;
		if (fh > ph - 96) {
			fh = ph - 96;
			fw = fh * 16 / 9;
		}
		int fy = top + 2;
		g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF0E1014);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, FilmPlayer.TEXTURE, fx, fy, 0, 0, fw, fh, FilmPlayer.W, FilmPlayer.H,
				FilmPlayer.W, FilmPlayer.H);
		if (through < 0) {
			g.centeredText(font, "Loading...", fx + fw / 2, fy + fh / 2 - 4, Ui.MUTED);
		}
		Ui.bar(g, fx, fy + fh + 2, fw, 2, Math.max(0, through), Ui.RUBY);
		int ty = fy + fh + 9;
		Ui.heading(g, c.name(), fx, ty, Ui.GOLD, 1.0F, false);
		Ui.wrap(g, c.text(), fx, ty + 12, fw, Ui.TEXT, py + ph - 6);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(scrollMax, scroll - (int) (scrollY * 18)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int[] r : rows) {
			if (Ui.inside(event.x(), event.y(), r[0], r[1], r[2], r[3])) {
				if (picked != r[4]) {
					picked = r[4];
					Ui.click();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	/** For the test client: show this one. */
	public void pick(String id) {
		for (int i = 0; i < clips.size(); i++) {
			if (clips.get(i).film().id().equals(id)) {
				picked = i;
				scrollToPicked = true;
			}
		}
	}
}
