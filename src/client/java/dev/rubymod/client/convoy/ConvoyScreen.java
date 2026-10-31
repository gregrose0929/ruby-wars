package dev.rubymod.client.convoy;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.rubymod.client.ui.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * Picking a card on the Ruby Convoy: your job before rolling out, an upgrade at each depot.
 * Big cards side by side, each with a large icon, a title in its colour and what it does in
 * plain words. Click one to take it (Esc to look around first; J brings it back).
 */
public class ConvoyScreen extends Screen {
	private record Card(String id, String title, String about, String icon, int color) {
	}

	private final MorePayloads.ConvoyCards source;
	private final List<Card> cards = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	ConvoyScreen(MorePayloads.ConvoyCards source) {
		super(Component.literal("Ruby Convoy"));
		this.source = source;
		for (String line : source.cards()) {
			String[] f = line.split("[|]", -1);
			if (f.length >= 5) {
				int color;
				try {
					color = Integer.parseInt(f[4]);
				} catch (NumberFormatException e) {
					color = 0xFFFFC040;
				}
				cards.add(new Card(f[0], f[1], f[2], f[3], color));
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		float in = Ui.ease(opened, 240);
		Ui.dim(g, width, height, in);
		Ui.emblem(g, "convoy", width / 2 - 12, 8, 24);
		Ui.heading(g, source.title(), width / 2, 36, Ui.TEXT, 1.5F, true);
		g.centeredText(font, source.kind().equals("role") ? "Everyone gets iron armor, grenades and endless ammo."
				: "Your pick stays on the convoy for the rest of the job.", width / 2, 52, Ui.DIM);
		int n = Math.max(1, cards.size());
		int gap = 8;
		int cw = Math.min(118, (width - 20 - gap * (n - 1)) / n);
		int ch = Math.min(150, height - 96);
		int total = n * cw + (n - 1) * gap;
		int x0 = (width - total) / 2;
		int y0 = 68;
		boxes.clear();
		for (int i = 0; i < cards.size(); i++) {
			Card c = cards.get(i);
			float rise = Ui.ease(opened + 70L * i, 260);
			int x = x0 + i * (cw + gap);
			int y = y0 + Math.round((1 - rise) * 18);
			boxes.add(new int[] {x, y, cw, ch});
			boolean hover = Ui.inside(mouseX, mouseY, x, y, cw, ch);
			float hv = Ui.hover("convoycard:" + c.id(), hover);
			Ui.card(g, x, y, cw, ch, c.color(), hv);
			int lift = Math.round(hv * 2);
			// The icon, big, on a sunken plate.
			Ui.inset(g, x + cw / 2 - 20, y + 10 - lift, 40, 40);
			g.pose().pushMatrix();
			g.pose().translate(x + cw / 2F - 16, y + 14 - lift);
			g.pose().scale(2.0F, 2.0F);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c.icon()))), 0, 0);
			g.pose().popMatrix();
			int ty = y + 58 - lift;
			for (FormattedCharSequence line : font.split(Component.literal(c.title()), cw - 10)) {
				g.centeredText(font, line, x + cw / 2, ty, c.color());
				ty += 10;
			}
			ty += 3;
			Ui.wrap(g, c.about(), x + 7, ty, cw - 14, Ui.DIM, y + ch - 26);
			Ui.button(g, source.kind().equals("role") ? "Pick" : "Take it", x + 7, y + ch - 22 - lift, cw - 14, 16, mouseX, mouseY, c.color(), true);
		}
		g.centeredText(font, "Esc to look around first (J brings this back)", width / 2, height - 14, Ui.MUTED);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < boxes.size() && i < cards.size(); i++) {
			int[] b = boxes.get(i);
			if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
				ClientPlayNetworking.send(new MorePayloads.ConvoyPick(source.kind(), cards.get(i).id()));
				ConvoyClient.pending = null;
				onClose();
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	/** For autotests: take card {@code i}. */
	public void pick(int i) {
		if (i >= 0 && i < cards.size()) {
			ClientPlayNetworking.send(new MorePayloads.ConvoyPick(source.kind(), cards.get(i).id()));
			ConvoyClient.pending = null;
			onClose();
		}
	}
}
