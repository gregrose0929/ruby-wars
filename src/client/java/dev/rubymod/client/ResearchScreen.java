package dev.rubymod.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.RubyMod;
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
 * The research tree (J): four branches of unlocks for advanced Armory gear.
 * Green = researched, gold = ready to research, gray = needs the one above it.
 * Click a node to spend your points.
 */
public class ResearchScreen extends Screen {
	private static final int NODE_H = 30;
	private int nodeW = 118;
	private final Map<Research.Node, int[]> boxes = new LinkedHashMap<>();
	/** Which tab is showing: the goals checklist (how you earn stars) or the upgrade tree (how you spend them). */
	private static boolean goalsTab = true;
	private int[] goalsTabBox = new int[4];
	private int[] treeTabBox = new int[4];

	public ResearchScreen() {
		super(Component.literal("Research"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void layout() {
		boxes.clear();
		List<String> branches = new ArrayList<>();
		for (Research.Node n : Research.NODES.values()) {
			if (!branches.contains(n.branch())) {
				branches.add(n.branch());
			}
		}
		int gap = 8;
		nodeW = Math.min(118, (width - 16 - (branches.size() - 1) * gap) / branches.size());
		int total = branches.size() * nodeW + (branches.size() - 1) * gap;
		int x0 = (width - total) / 2;
		for (int b = 0; b < branches.size(); b++) {
			int row = 0;
			for (Research.Node n : Research.NODES.values()) {
				if (n.branch().equals(branches.get(b))) {
					boxes.put(n, new int[] {x0 + b * (nodeW + gap), 50 + row * (NODE_H + 12)});
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
		int points = ClientState.research == null ? 0 : ClientState.research.points();
		g.centeredText(font, "UPGRADES", width / 2, 8, 0xFF55D8FF);
		g.centeredText(font, "\u2605 " + points + " star" + (points == 1 ? "" : "s") + " to spend", width / 2, 20, 0xFFFFC040);
		goalsTabBox = tab(g, "GOALS: earn stars", width / 2 - 124, 31, goalsTab, mouseX, mouseY);
		treeTabBox = tab(g, "UPGRADES: spend stars", width / 2 + 4, 31, !goalsTab, mouseX, mouseY);
		if (goalsTab) {
			drawGoals(g, mouseX, mouseY);
			return;
		}
		layout();
		String lastBranch = "";
		Research.Node hovered = null;
		for (Map.Entry<Research.Node, int[]> e : boxes.entrySet()) {
			Research.Node n = e.getKey();
			int x = e.getValue()[0];
			int y = e.getValue()[1];
			if (!n.branch().equals(lastBranch)) {
				g.centeredText(font, n.branch().toUpperCase(), x + nodeW / 2, y - 10, 0xFFDDE6EE);
				lastBranch = n.branch();
			}
			if (n.requires() != null) {
				int[] above = boxes.get(Research.NODES.get(n.requires()));
				if (above != null) {
					g.fill(x + nodeW / 2 - 1, above[1] + NODE_H, x + nodeW / 2 + 1, y, has(n.requires()) ? 0xFF5BE37D : 0xFF4A5058);
				}
			}
			boolean done = has(n.id());
			boolean ready = !done && (n.requires() == null || has(n.requires()));
			boolean affordable = ready && points >= n.cost();
			int border = done ? 0xFF5BE37D : affordable ? 0xFFFFC040 : ready ? 0xFFB08030 : 0xFF3A4048;
			boolean hover = mouseX >= x && mouseX < x + nodeW && mouseY >= y && mouseY < y + NODE_H;
			g.fill(x, y, x + nodeW, y + NODE_H, hover ? 0xF0263038 : 0xE0181D22);
			g.outline(x, y, nodeW, NODE_H, border);
			var item = BuiltInRegistries.ITEM.getValue(RubyMod.id(n.icon()));
			g.item(new ItemStack(item), x + 6, y + 9);
			String title = font.plainSubstrByWidth(n.title(), nodeW - 30);
			g.text(font, title, x + 26, y + 5, done ? 0xFF7CF59A : 0xFFFFFFFF, false);
			g.text(font, done ? "Unlocked" : n.cost() + " stars", x + 26, y + 17, done ? 0xFF5BE37D : affordable ? 0xFFFFC040 : 0xFF8A949E, false);
			if (hover) {
				hovered = n;
			}
		}
		if (hovered != null) {
			List<Component> tip = new ArrayList<>();
			tip.add(Component.literal(hovered.title()));
			tip.add(Component.literal("Unlocks: " + hovered.description()));
			if (hovered.requires() != null) {
				tip.add(Component.literal("Needs: " + Research.NODES.get(hovered.requires()).title()));
			}
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
	}

	private static boolean inside(MouseButtonEvent event, int[] box) {
		return event.x() >= box[0] && event.x() < box[0] + box[2] && event.y() >= box[1] && event.y() < box[1] + box[3];
	}

	private int[] tab(GuiGraphicsExtractor g, String label, int x, int y, boolean active, int mouseX, int mouseY) {
		int w = 120;
		int h = 13;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		g.fill(x, y, x + w, y + h, active ? 0xF02A3A48 : hover ? 0xE0202830 : 0xC0141A20);
		g.outline(x, y, w, h, active ? 0xFF55D8FF : 0xFF3A4048);
		g.centeredText(font, label, x + w / 2, y + 3, active ? 0xFFFFFFFF : 0xFF9AA4AE);
		return new int[] {x, y, w, h};
	}

	/** The goals checklist: what to do next, with progress and the stars each one gives. */
	private void drawGoals(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<String> goals = ClientState.research == null ? List.of() : ClientState.research.goals();
		int cols = width >= 380 ? 2 : 1;
		int colW = Math.min(250, (width - 24) / cols);
		int x0 = (width - cols * colW - (cols - 1) * 8) / 2;
		int rowH = 15;
		int rows = (goals.size() + cols - 1) / cols;
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
			int y = 52 + (i % rows) * rowH;
			g.fill(x, y, x + colW, y + rowH - 2, done ? 0xC0153020 : 0xC0181D22);
			g.text(font, done ? "\u2714" : "\u2610", x + 4, y + 3, done ? 0xFF5BE37D : 0xFF8A949E, false);
			String right = (done ? "" : (target > 1 ? progress + "/" + target + "  " : "")) + "\u2605".repeat(stars);
			String title = font.plainSubstrByWidth(f[1], colW - 26 - font.width(right));
			g.text(font, title, x + 16, y + 3, done ? 0xFF7CF59A : 0xFFFFFFFF, false);
			g.text(font, right, x + colW - 4 - font.width(right), y + 3, done ? 0xFF5BE37D : 0xFFFFC040, false);
			if (!done && target > 1) {
				g.fill(x + 16, y + rowH - 4, x + 16 + (int) ((colW - 20) * Math.min(1.0, progress / (double) target)), y + rowH - 3, 0xFFFFC040);
			}
		}
		g.centeredText(font, "Each goal counts once. Squadmates share unlocks.",
				width / 2, 56 + rows * rowH, 0xFF8A949E);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (inside(event, goalsTabBox)) {
			goalsTab = true;
			return true;
		}
		if (inside(event, treeTabBox)) {
			goalsTab = false;
			return true;
		}
		if (goalsTab) {
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
