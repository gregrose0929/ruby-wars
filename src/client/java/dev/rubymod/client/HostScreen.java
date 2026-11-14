package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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
 * A park host's card: what their game is, how to play in a couple of lines, and big buttons (Play, or a list of
 * choices, like the DJ's songs). Enter presses the first button.
 */
public class HostScreen extends Screen {
	private record Choice(String key, String label) {
	}

	private final MorePayloads.HostCard card;
	private final List<Choice> choices = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	HostScreen(MorePayloads.HostCard card) {
		super(Component.literal(card.title()));
		this.card = card;
		for (String b : card.buttons()) {
			int bar = b.indexOf('|');
			choices.add(bar < 0 ? new Choice(b, b) : new Choice(b.substring(0, bar), b.substring(bar + 1)));
		}
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.HostCard.TYPE, (p, c) -> Minecraft.getInstance().gui.setScreen(new HostScreen(p)));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 200));
		int color = 0xFF000000 | card.color();
		int w = Math.min(260, width - 24);
		int textLines = Ui.lines(card.body(), w - 20);
		boolean grid = choices.size() > 3;
		int cols = grid ? 2 : 1;
		int rows = (choices.size() + cols - 1) / cols;
		int bh = choices.size() == 1 ? 22 : 16;
		int h = 52 + textLines * 10 + 10 + rows * (bh + 4) + 16;
		int x = (width - w) / 2;
		int y = Ui.centre(height, h) + Math.round((1 - Ui.ease(opened, 240)) * 12);
		Ui.card(g, x, y, w, h, color, 0);
		Ui.inset(g, x + 10, y + 10, 32, 32);
		g.pose().pushMatrix();
		g.pose().translate(x + 12, y + 12);
		g.pose().scale(1.75F, 1.75F);
		g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(card.icon()))), 0, 0);
		g.pose().popMatrix();
		Ui.heading(g, card.title(), x + 50, y + 18, color, 1.4F, false);
		int ty = Ui.wrap(g, card.body(), x + 10, y + 50, w - 20, Ui.TEXT, y + h);
		int by = Math.max(ty, y + 50 + textLines * 10) + 8;
		boxes.clear();
		int bw = (w - 20 - (cols - 1) * 6) / cols;
		for (int i = 0; i < choices.size(); i++) {
			int bx = x + 10 + (i % cols) * (bw + 6);
			int byy = by + (i / cols) * (bh + 4);
			boxes.add(new int[] {bx, byy, bw, bh});
			Ui.button(g, choices.get(i).label(), bx, byy, bw, bh, mouseX, mouseY, i == 0 ? color : 0xFF8A93A6, true);
		}
		g.centeredText(font, "Esc to close", width / 2, y + h - 12, Ui.MUTED);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < boxes.size(); i++) {
			int[] b = boxes.get(i);
			if (Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
				pick(i);
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 257 || event.key() == 335) {
			pick(0);
			return true;
		}
		return super.keyPressed(event);
	}

	/** Press button {@code i} (also for autotests). */
	public void pick(int i) {
		if (i >= 0 && i < choices.size()) {
			ClientPlayNetworking.send(new MorePayloads.HostPick(card.host(), choices.get(i).key()));
			onClose();
		}
	}

	/** For autotests. */
	public String host() {
		return card.host();
	}

	public List<String> keys() {
		return choices.stream().map(Choice::key).toList();
	}
}
