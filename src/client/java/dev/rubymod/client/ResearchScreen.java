package dev.rubymod.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.RubyMod;
import dev.rubymod.client.ui.Ui;
import dev.rubymod.game.Research;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The J screen, three tabs on one panel: the Guide (what to do next in the season, step
 * by step), Goals (how you earn stars) and Upgrades (four branches of unlocks for advanced
 * Armory gear: green is unlocked, gold is ready, grey needs the one above it). Click an
 * upgrade to spend your stars.
 */
public class ResearchScreen extends Screen {
	private static final int NODE_H = 30;
	private static final String[] TABS = {"Guide", "Goals", "Upgrades"};
	private int nodeW = 118;
	private final Map<Research.Node, int[]> boxes = new LinkedHashMap<>();
	/** Which tab is showing: 0 the guide, 1 the goals checklist (how you earn stars), 2 the upgrade tree (how you spend them). */
	private static int tab = 0;
	private final int[][] tabBoxes = new int[3][];
	private final long opened = System.currentTimeMillis();
	private long switched = System.currentTimeMillis();

	public ResearchScreen() {
		super(Component.literal("Research"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int pw() {
		return Math.min(470, width - 16);
	}

	private int px() {
		return (width - pw()) / 2;
	}

	private static final int PY = 44;

	private void layout() {
		boxes.clear();
		List<String> branches = new ArrayList<>();
		for (Research.Node n : Research.NODES.values()) {
			if (!branches.contains(n.branch())) {
				branches.add(n.branch());
			}
		}
		int gap = 8;
		nodeW = Math.min(108, (pw() - 20 - (branches.size() - 1) * gap) / branches.size());
		int total = branches.size() * nodeW + (branches.size() - 1) * gap;
		int x0 = (width - total) / 2;
		for (int b = 0; b < branches.size(); b++) {
			int row = 0;
			for (Research.Node n : Research.NODES.values()) {
				if (n.branch().equals(branches.get(b))) {
					boxes.put(n, new int[] {x0 + b * (nodeW + gap), PY + 24 + row * (NODE_H + 10)});
					row++;
				}
			}
		}
	}

	private boolean has(String id) {
		return ClientState.research != null && ClientState.research.unlocked().contains(id);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		float in = Ui.ease(opened, 240);
		Ui.dim(g, width, height, in);
		int points = ClientState.research == null ? 0 : ClientState.research.points();
		int px = px();
		int pw = pw();
		Ui.heading(g, "Your season", px + 2, 10, Ui.TEXT, 1.5F, false);
		String stars = points + " star" + (points == 1 ? "" : "s") + " to spend";
		Ui.icon(g, Ui.Icon.STAR, px + pw - font.width(stars) - 14, 13);
		Ui.text(g, stars, px + pw - font.width(stars) - 2, 14, Ui.GOLD);
		Ui.panel(g, px, PY, pw, height - PY - 8);
		int tx = px + 8;
		for (int i = 0; i < 3; i++) {
			int tw = font.width(TABS[i]) + 20;
			tabBoxes[i] = new int[] {tx, PY - 15, tw, 16};
			Ui.tab(g, TABS[i], tx, PY - 15, tw, tab == i, mouseX, mouseY);
			tx += tw + 3;
		}
		float fade = Ui.ease(switched, 160);
		if (tab == 0) {
			drawGuide(g, mouseX, mouseY, fade);
		} else if (tab == 1) {
			drawGoals(g, fade);
		} else {
			drawTree(g, mouseX, mouseY, points);
		}
	}

	private void drawTree(GuiGraphicsExtractor g, int mouseX, int mouseY, int points) {
		layout();
		String lastBranch = "";
		Research.Node hovered = null;
		for (Map.Entry<Research.Node, int[]> e : boxes.entrySet()) {
			Research.Node n = e.getKey();
			int x = e.getValue()[0];
			int y = e.getValue()[1];
			if (!n.branch().equals(lastBranch)) {
				g.centeredText(font, n.branch(), x + nodeW / 2, y - 12, Ui.GOLD);
				lastBranch = n.branch();
			}
			if (n.requires() != null) {
				int[] above = boxes.get(Research.NODES.get(n.requires()));
				if (above != null) {
					g.fill(x + nodeW / 2 - 1, above[1] + NODE_H, x + nodeW / 2 + 1, y, has(n.requires()) ? Ui.GREEN : 0xFF3A3F48);
				}
			}
			boolean done = has(n.id());
			boolean ready = !done && (n.requires() == null || has(n.requires()));
			boolean affordable = ready && points >= n.cost();
			boolean hover = Ui.inside(mouseX, mouseY, x, y, nodeW, NODE_H);
			int accent = done ? Ui.GREEN : affordable ? Ui.GOLD : ready ? Ui.AMBER : 0xFF3A3F48;
			Ui.card(g, x, y, nodeW, NODE_H, accent, Ui.hover("node:" + n.id(), hover && !done));
			var item = BuiltInRegistries.ITEM.getValue(RubyMod.id(n.icon()));
			g.item(new ItemStack(item), x + 6, y + 9);
			Ui.text(g, n.title(), x + 25, y + 7, nodeW - 29, done ? Ui.GREEN : ready ? Ui.TEXT : Ui.MUTED);
			if (done) {
				Ui.icon(g, Ui.Icon.CHECK, x + 25, y + 17);
				Ui.text(g, "Unlocked", x + 36, y + 18, Ui.GREEN);
			} else if (!ready) {
				Ui.icon(g, Ui.Icon.LOCK, x + 25, y + 17);
				Ui.text(g, n.cost() + " stars", x + 36, y + 18, Ui.MUTED);
			} else {
				Ui.icon(g, Ui.Icon.STAR, x + 25, y + 17);
				Ui.text(g, n.cost() + " stars", x + 36, y + 18, affordable ? Ui.GOLD : Ui.MUTED);
			}
			if (hover) {
				hovered = n;
			}
		}
		if (hovered != null) {
			List<String> tip = new ArrayList<>();
			tip.add(hovered.title());
			tip.add("Unlocks: " + hovered.description());
			if (hovered.requires() != null && !has(hovered.requires())) {
				tip.add("Needs " + Research.NODES.get(hovered.requires()).title() + " first.");
			}
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
		}
	}

	/** The guide: every step in order (two columns), the next one highlighted, with how to do it underneath; hover a step for its how-to. */
	private void drawGuide(GuiGraphicsExtractor g, int mouseX, int mouseY, float fade) {
		int current = Guide.current();
		int px = px();
		int pw = pw();
		int cols = pw >= 380 ? 2 : 1;
		int total = pw - 20;
		int colW = (total - (cols - 1) * 8) / cols;
		int x0 = px + 10;
		int y0 = PY + 12;
		y0 = Ui.wrap(g, Guide.season(), x0, y0, total, Ui.alpha(Ui.DIM, fade), height) + 6;
		int n = Guide.STEPS.size();
		int rows = (n + cols - 1) / cols;
		int rowH = 14;
		List<String> tip = null;
		for (int i = 0; i < n; i++) {
			Guide.Step s = Guide.STEPS.get(i);
			boolean done = Guide.done(s);
			boolean now = i == current;
			int x = x0 + (i / rows) * (colW + 8);
			int y = y0 + (i % rows) * rowH;
			boolean hover = Ui.inside(mouseX, mouseY, x, y, colW, rowH - 1);
			if (now) {
				g.fill(x, y, x + colW, y + rowH - 2, 0x40F5C542);
				g.fill(x, y, x + 2, y + rowH - 2, Ui.GOLD);
			} else if (hover) {
				g.fill(x, y, x + colW, y + rowH - 2, 0x18FFFFFF);
			}
			if (done) {
				Ui.icon(g, Ui.Icon.CHECK, x + 4, y + 2);
			} else {
				Ui.text(g, (i + 1) + ".", x + 5, y + 3, now ? Ui.GOLD : Ui.MUTED);
			}
			String right = s.optional() ? "optional" : "";
			int[] p = Guide.progress(s);
			if (!done && p != null && p[1] > 1) {
				right = p[0] + "/" + p[1];
			}
			Ui.text(g, s.title(), x + 18, y + 3, colW - 24 - font.width(right), done ? Ui.MUTED : now ? Ui.TEXT : Ui.DIM);
			Ui.text(g, right, x + colW - 3 - font.width(right), y + 3, Ui.MUTED);
			if (hover && !now) {
				tip = List.of(s.title(), s.hint());
			}
		}
		int y = y0 + rows * rowH + 6;
		if (current >= 0) {
			Guide.Step s = Guide.STEPS.get(current);
			int lines = Ui.lines(s.hint(), total - 16);
			int boxH = 22 + lines * 10;
			Ui.inset(g, x0, y, total, boxH);
			g.fill(x0 + 1, y + 1, x0 + 3, y + boxH - 1, Ui.GOLD);
			Ui.text(g, "Next: " + s.title(), x0 + 9, y + 6, Ui.GOLD);
			Ui.wrap(g, s.hint(), x0 + 9, y + 18, total - 16, Ui.TEXT, y + boxH);
			y += boxH + 6;
		} else {
			Ui.icon(g, Ui.Icon.CHECK, x0, y);
			Ui.text(g, "Every step done! Keep growing your base and get ready for Battle Day.", x0 + 12, y + 1, Ui.GREEN);
			y += 14;
		}
		Ui.wrap(g, "Any time, press H to try Free Roam, the party games, Co-op Horde, the Rift or the Convoy.", x0, y, total, Ui.MUTED, height - 10);
		if (tip != null) {
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
		}
	}

	/** The goals checklist: what to do next, with progress and the stars each one gives. */
	private void drawGoals(GuiGraphicsExtractor g, float fade) {
		List<String> goals = ClientState.research == null ? List.of() : ClientState.research.goals();
		int px = px();
		int pw = pw();
		int cols = pw >= 380 ? 2 : 1;
		int colW = (pw - 20 - (cols - 1) * 8) / cols;
		int x0 = px + 10;
		int rowH = 17;
		int rows = (goals.size() + cols - 1) / cols;
		int y0 = PY + 12;
		for (int i = 0; i < goals.size(); i++) {
			String[] f = goals.get(i).split("\\|");
			if (f.length < 6) {
				continue;
			}
			int progress = Integer.parseInt(f[2]);
			int target = Integer.parseInt(f[3]);
			int stars = Integer.parseInt(f[4]);
			boolean done = Boolean.parseBoolean(f[5]);
			int x = x0 + (i / rows) * (colW + 8);
			int y = y0 + (i % rows) * rowH;
			if (y + rowH > height - 24) {
				continue;
			}
			if (i % rows % 2 == 0) {
				g.fill(x, y - 1, x + colW, y + rowH - 3, 0x12FFFFFF);
			}
			if (done) {
				Ui.icon(g, Ui.Icon.CHECK, x + 3, y + 1);
			} else {
				g.fill(x + 4, y + 2, x + 11, y + 9, 0xFF0E1014);
				g.outline(x + 4, y + 2, 7, 7, Ui.MUTED);
			}
			int starW = stars * 10;
			String count = !done && target > 1 ? progress + "/" + target : "";
			Ui.text(g, f[1], x + 16, y + 2, colW - 26 - starW - font.width(count), done ? Ui.MUTED : Ui.TEXT);
			Ui.text(g, count, x + colW - starW - 6 - font.width(count), y + 2, Ui.DIM);
			for (int k = 0; k < stars; k++) {
				Ui.icon(g, Ui.Icon.STAR, x + colW - 10 * (k + 1), y + 1);
			}
			if (!done && target > 1) {
				Ui.bar(g, x + 16, y + 11, colW - 26 - starW, 3, Math.min(1.0F, progress / (float) target), Ui.GOLD);
			}
		}
		g.centeredText(font, "Each goal counts once. Squadmates share unlocks.", width / 2, height - 20, Ui.MUTED);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < 3; i++) {
			int[] b = tabBoxes[i];
			if (b != null && Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
				if (tab != i) {
					tab = i;
					switched = System.currentTimeMillis();
				}
				return true;
			}
		}
		if (tab != 2) {
			return super.mouseClicked(event, doubled);
		}
		for (Map.Entry<Research.Node, int[]> e : boxes.entrySet()) {
			int x = e.getValue()[0];
			int y = e.getValue()[1];
			if (event.x() >= x && event.x() < x + nodeW && event.y() >= y && event.y() < y + NODE_H && !has(e.getKey().id())) {
				ClientPlayNetworking.send(new MorePayloads.ResearchUnlock(e.getKey().id()));
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
