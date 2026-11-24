package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The season wrap-up: who won, a few friendly awards (top fighter, richest base, biggest Heart...), and how your
 * own season went. Shown to everyone when a season ends; /rubywars recap brings it back.
 */
public class SeasonRecapScreen extends Screen {
	private record Award(String title, String who, String detail, String item) {
	}

	private static final int GOLD = 0xFFF5C542;
	private final MorePayloads.SeasonRecap recap;
	private final List<Award> awards = new ArrayList<>();
	private final List<String[]> mine = new ArrayList<>();
	/** "How your base held up": the second page, with the season's keepsake map. */
	private final List<String[]> base = new ArrayList<>();
	private int page;
	private int[] pageButton = {0, 0, 0, 0};
	private final long opened = System.currentTimeMillis();
	private int[] close = {0, 0, 0, 0};

	SeasonRecapScreen(MorePayloads.SeasonRecap recap) {
		super(Component.literal(recap.title()));
		this.recap = recap;
		for (String a : recap.awards()) {
			String[] p = a.split("\\|", -1);
			if (p.length >= 4) {
				awards.add(new Award(p[0], p[1], p[2], p[3]));
			}
		}
		for (String m : recap.mine()) {
			boolean onBase = m.startsWith("@");
			String[] p = (onBase ? m.substring(1) : m).split("\\|", -1);
			if (p.length >= 2) {
				(onBase ? base : mine).add(p);
			}
		}
		dev.rubymod.client.map.Keepsake.make(recap.title());
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.SeasonRecap.TYPE,
				(p, c) -> Minecraft.getInstance().gui.setScreen(new SeasonRecapScreen(p)));
	}

	/** For the autotest: show this page (0 awards, 1 your base). */
	public void showPage(int page) {
		this.page = page;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 200));
		int w = Math.min(360, width - 24);
		int cols = w >= 330 ? 3 : w >= 220 ? 2 : 1;
		int cardH = 28;
		int rows = (awards.size() + cols - 1) / cols;
		int mineRows = (mine.size() + 1) / 2;
		int h = 68 + rows * (cardH + 3) + (mine.isEmpty() ? 0 : 18 + mineRows * 12) + 28;
		if (page == 1) {
			h = 220;
		}
		h = Math.min(h, height - 16);
		int x = (width - w) / 2;
		int y = Ui.centre(height, h) + Math.round((1 - Ui.ease(opened, 240)) * 12);
		Ui.card(g, x, y, w, h, GOLD, 0);

		Ui.heading(g, recap.title().toUpperCase(), x + w / 2, y + 10, Ui.DIM, 1.0F, true);
		int bw = 90;
		close = new int[] {x + w / 2 + 4, y + h - 24, bw, 16};
		pageButton = new int[] {x + w / 2 - 4 - bw, y + h - 24, bw, 16};
		if (page == 1) {
			drawBasePage(g, x, y, w, h);
			Ui.button(g, "Awards", pageButton[0], pageButton[1], pageButton[2], pageButton[3], mouseX, mouseY, Ui.BLUE, true);
			Ui.button(g, "Close", close[0], close[1], close[2], close[3], mouseX, mouseY, GOLD, true);
			return;
		}
		boolean draw = recap.winner().isEmpty();
		boolean noContest = recap.title().endsWith("no contest");
		float pop = 1.6F + 0.4F * (1 - Ui.ease(opened, 500));
		boolean team = recap.winner().endsWith(" team");
		Ui.heading(g, noContest ? "No contest" : draw ? "No survivors" : recap.winner() + (team ? " win!" : " wins!"), x + w / 2, y + 26,
				draw ? Ui.MUTED : GOLD, pop, true);
		if (noContest) {
			g.centeredText(font, "Nobody fought this Battle Day, so nobody's crowned.", x + w / 2, y + 48, Ui.TEXT);
		} else if (!draw) {
			g.centeredText(font, "Champion of the season. Well played, everyone!", x + w / 2, y + 48, Ui.TEXT);
		} else {
			g.centeredText(font, "Nobody was left standing. Well played, everyone!", x + w / 2, y + 48, Ui.TEXT);
		}

		int cy = y + 64;
		int cw = (w - 20 - (cols - 1) * 6) / cols;
		for (int i = 0; i < awards.size(); i++) {
			Award a = awards.get(i);
			int ax = x + 10 + (i % cols) * (cw + 6);
			int ay = cy + (i / cols) * (cardH + 3);
			if (ay + cardH > y + h - 26) {
				break;
			}
			Ui.inset(g, ax, ay, cw, cardH);
			try {
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(a.item()))), ax + 4, ay + 6);
			} catch (RuntimeException ignored) {
				// An unknown icon just leaves a gap.
			}
			Ui.text(g, a.title(), ax + 24, ay + 2, cw - 26, Ui.DIM);
			Ui.text(g, a.who(), ax + 24, ay + 10, cw - 26, Ui.TEXT);
			Ui.text(g, a.detail(), ax + 24, ay + 19, cw - 26, Ui.MUTED);
		}
		int my = cy + rows * (cardH + 3) + 4;
		if (!mine.isEmpty() && my + 20 < y + h - 26) {
			Ui.section(g, "Your season", x + 10, my, w - 20);
			my += 14;
			int half = (w - 20) / 2;
			for (int i = 0; i < mine.size(); i++) {
				int lx = x + 10 + (i % 2) * half;
				int ly = my + (i / 2) * 12;
				if (ly + 10 > y + h - 26) {
					break;
				}
				String[] m = mine.get(i);
				Ui.text(g, m[0], lx, ly, half - 50, Ui.DIM);
				String v = m[1];
				g.text(font, v, lx + half - 8 - font.width(v), ly, i == 0 ? GOLD : Ui.TEXT, false);
			}
		}
		Ui.button(g, "Your base", pageButton[0], pageButton[1], pageButton[2], pageButton[3], mouseX, mouseY, Ui.BLUE, true);
		Ui.button(g, "Close", close[0], close[1], close[2], close[3], mouseX, mouseY, GOLD, true);
	}

	/** The second page: how your base held up on Battle Day, and the keepsake map of the season. */
	private void drawBasePage(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Ui.heading(g, "How your base held up", x + w / 2, y + 24, GOLD, 1.3F, true);
		int ly = y + 44;
		int textW = Math.max(110, w - 20 - 150);
		if (base.isEmpty()) {
			Ui.wrap(g, "Battle Day didn't come this season, so there's nothing to show yet.", x + 10, ly, textW, Ui.DIM, y + h - 30);
		}
		for (String[] m : base) {
			Ui.text(g, m[0], x + 10, ly, textW - 50, Ui.DIM);
			g.text(font, m[1], x + 10 + textW - font.width(m[1]), ly, Ui.TEXT, false);
			ly += 13;
		}
		// The keepsake map.
		if (dev.rubymod.client.map.Keepsake.ready()) {
			int box = Math.min(120, h - 100);
			int mw = dev.rubymod.client.map.Keepsake.shownW;
			int mh = dev.rubymod.client.map.Keepsake.shownH;
			float scale = Math.min(box / (float) mw, box / (float) mh);
			int dw = Math.max(1, Math.round(mw * scale));
			int dh = Math.max(1, Math.round(mh * scale));
			int mx = x + w - 10 - box + (box - dw) / 2;
			int my = y + 44;
			g.fill(mx - 2, my - 2, mx + dw + 2, my + dh + 2, 0xFF0C0E12);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, dev.rubymod.client.map.Keepsake.TEXTURE, mx, my, 0, 0, dw, dh, mw, mh, mw, mh);
			Ui.shrinkText(g, "The season's map: yours green, the others red.", x + w - 10 - box, my + dh + 4, box, Ui.MUTED);
			if (dev.rubymod.client.map.Keepsake.savedTo != null) {
				Ui.shrinkText(g, "Saved: screenshots/" + dev.rubymod.client.map.Keepsake.savedTo.getFileName(), x + w - 10 - box, my + dh + 14, box, Ui.MUTED);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (Ui.inside(event.x(), event.y(), close[0], close[1], close[2], close[3])) {
			onClose();
			return true;
		}
		if (Ui.inside(event.x(), event.y(), pageButton[0], pageButton[1], pageButton[2], pageButton[3])) {
			page = 1 - page;
			Ui.click();
			return true;
		}
		return super.mouseClicked(event, doubled);
	}
}
