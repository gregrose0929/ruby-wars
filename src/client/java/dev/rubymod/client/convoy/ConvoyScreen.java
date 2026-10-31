package dev.rubymod.client.convoy;

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
		long now = System.currentTimeMillis();
		g.centeredText(font, source.title(), width / 2, 18, 0xFFFFC040);
		g.centeredText(font, source.kind().equals("role") ? "Everyone gets iron armor, grenades and endless ammo. Pick what you'll do:"
				: "Your pick goes on the convoy for the rest of the job.", width / 2, 32, 0xFFD8DEE4);
		int n = Math.max(1, cards.size());
		int gap = 8;
		int cw = Math.min(124, (width - 20 - gap * (n - 1)) / n);
		int ch = Math.min(150, height - 80);
		int total = n * cw + (n - 1) * gap;
		int x0 = (width - total) / 2;
		int y0 = 48;
		boxes.clear();
		for (int i = 0; i < cards.size(); i++) {
			Card c = cards.get(i);
			float rise = Math.min(1, Math.max(0, (now - opened - i * 90) / 220F));
			int x = x0 + i * (cw + gap);
			int y = y0 + (int) ((1 - rise) * 24);
			boxes.add(new int[] {x, y, cw, ch});
			boolean hover = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + ch;
			g.fill(x + 3, y + 3, x + cw + 3, y + ch + 3, 0x66000000);
			g.fill(x, y, x + cw, y + ch, hover ? 0xF0262E36 : 0xF0161B20);
			g.outline(x, y, cw, ch, hover ? 0xFFFFFFFF : c.color());
			g.fill(x + 1, y + 1, x + cw - 1, y + 4, c.color());
			// The icon, big.
			g.pose().pushMatrix();
			g.pose().translate(x + cw / 2F - 16, y + 12);
			g.pose().scale(2.0F, 2.0F);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c.icon()))), 0, 0);
			g.pose().popMatrix();
			int ty = y + 50;
			for (FormattedCharSequence line : font.split(Component.literal(c.title()), cw - 10)) {
				g.centeredText(font, line, x + cw / 2, ty, c.color());
				ty += 10;
			}
			ty += 4;
			for (FormattedCharSequence line : font.split(Component.literal(c.about()), cw - 12)) {
				if (ty > y + ch - 24) {
					break;
				}
				g.text(font, line, x + 6, ty, 0xFFFFFFFF, false);
				ty += 10;
			}
			int by = y + ch - 18;
			g.fill(x + 6, by, x + cw - 6, by + 13, hover ? c.color() : darker(c.color()));
			g.centeredText(font, "TAKE IT", x + cw / 2, by + 3, 0xFFFFFFFF);
		}
		g.centeredText(font, "Esc to look around first (J brings this back)", width / 2, height - 14, 0xFF8A949E);
	}

	private static int darker(int argb) {
		int r = (argb >> 16 & 0xFF) * 5 / 10;
		int gg = (argb >> 8 & 0xFF) * 5 / 10;
		int b = (argb & 0xFF) * 5 / 10;
		return 0xFF000000 | r << 16 | gg << 8 | b;
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
