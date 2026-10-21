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
		layout();
		int points = ClientState.research == null ? 0 : ClientState.research.points();
		g.centeredText(font, "RESEARCH", width / 2, 10, 0xFF55D8FF);
		g.centeredText(font, points + " point" + (points == 1 ? "" : "s") + " to spend", width / 2, 22, 0xFFFFC040);
		g.centeredText(font, "1 a day, 1 for all bounties, 2 per raid repelled", width / 2, 33, 0xFF8A949E);
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
			g.text(font, done ? "Researched" : n.cost() + " points", x + 26, y + 17, done ? 0xFF5BE37D : affordable ? 0xFFFFC040 : 0xFF8A949E, false);
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

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
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
