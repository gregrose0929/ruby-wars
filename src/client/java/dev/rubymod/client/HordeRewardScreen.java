package dev.rubymod.client;

import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** Co-op Horde, after a wave: three reward cards, click one. */
public class HordeRewardScreen extends Screen {
	private final int wave;
	private final List<String[]> options;
	private int[][] cards = new int[0][];

	private HordeRewardScreen(MorePayloads.HordeOffer offer) {
		super(Component.literal("Pick a reward"));
		this.wave = offer.wave();
		this.options = offer.options().stream().map(s -> s.split("[|]", 4)).filter(f -> f.length == 4).toList();
	}

	/** Open the offer (or close the menu when the server says it's over). */
	public static void show(MorePayloads.HordeOffer offer) {
		Minecraft mc = Minecraft.getInstance();
		if (offer.options().isEmpty()) {
			if (mc.gui.screen() instanceof HordeRewardScreen) {
				mc.gui.setScreen(null);
			}
			return;
		}
		mc.gui.setScreen(new HordeRewardScreen(offer));
	}

	/** For autotests. */
	String firstOptionId() {
		return options.isEmpty() ? "" : options.get(0)[0];
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		int cardW = Math.min(130, (width - 40) / 3);
		int cardH = 120;
		int gap = 10;
		int x0 = (width - cardW * 3 - gap * 2) / 2;
		int y0 = Math.max(40, (height - cardH) / 2);
		g.centeredText(font, "WAVE " + wave + " CLEARED!", width / 2, y0 - 30, 0xFF5BE37D);
		g.centeredText(font, "Pick your reward", width / 2, y0 - 16, 0xFFFFFFFF);
		cards = new int[options.size()][];
		for (int i = 0; i < options.size(); i++) {
			String[] f = options.get(i);
			int x = x0 + i * (cardW + gap);
			cards[i] = new int[] {x, y0, cardW, cardH};
			boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y0 && mouseY < y0 + cardH;
			g.fill(x, y0, x + cardW, y0 + cardH, hover ? 0xF0263A2C : 0xE0161B20);
			g.outline(x, y0, cardW, cardH, hover ? 0xFFFFFFFF : 0xFF3FC85A);
			var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(f[3]));
			g.item(new ItemStack(item), x + cardW / 2 - 8, y0 + 10);
			int ty = y0 + 34;
			for (FormattedCharSequence line : font.split(Component.literal(f[1]), cardW - 10)) {
				g.centeredText(font, line, x + cardW / 2, ty, 0xFFFFC040);
				ty += 10;
			}
			ty += 4;
			for (FormattedCharSequence line : font.split(Component.literal(f[2]), cardW - 12)) {
				g.centeredText(font, line, x + cardW / 2, ty, 0xFFD8DEE4);
				ty += 10;
			}
			int by = y0 + cardH - 20;
			g.fill(x + 8, by, x + cardW - 8, by + 14, hover ? 0xFF3FC85A : 0xB03FC85A);
			g.centeredText(font, "TAKE IT", x + cardW / 2, by + 3, 0xFFFFFFFF);
		}
		g.centeredText(font, "Not sure? The first one is taken for you when the next wave starts.", width / 2, y0 + cardH + 12, 0xFF8A949E);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < cards.length; i++) {
			int[] b = cards[i];
			if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
				ClientPlayNetworking.send(new MorePayloads.HordePick(options.get(i)[0]));
				onClose();
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
