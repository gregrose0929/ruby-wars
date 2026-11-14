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

/** The hub's travel pads: "Where to?" with a big button for every island. Click one and off you fly. */
public class TravelScreen extends Screen {
	private record Place(String id, String name, String about, String icon) {
	}

	private static final int ACCENT = 0xFF55DD77;
	private final List<Place> places = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	TravelScreen(MorePayloads.TravelMenu menu) {
		super(Component.literal("Where to?"));
		for (String line : menu.places()) {
			String[] f = line.split("[|]", -1);
			if (f.length >= 4) {
				places.add(new Place(f[0], f[1], f[2], f[3]));
			}
		}
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.TravelMenu.TYPE, (p, c) -> Minecraft.getInstance().gui.setScreen(new TravelScreen(p)));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 200));
		int cols = width >= 330 ? 2 : 1;
		int gap = 6;
		int cw = Math.min(170, (width - 24 - gap * (cols - 1)) / cols);
		int chh = 34;
		int rows = (places.size() + cols - 1) / cols;
		int total = 40 + rows * (chh + gap) + 14;
		int oy = Ui.centre(height, total);
		Ui.heading(g, "Where to?", width / 2, oy + 4, Ui.TEXT, 1.5F, true);
		g.centeredText(font, "Click a place to fly there.", width / 2, oy + 22, Ui.DIM);
		int x0 = (width - (cols * cw + (cols - 1) * gap)) / 2;
		int y0 = oy + 38;
		boxes.clear();
		for (int i = 0; i < places.size(); i++) {
			Place pl = places.get(i);
			int x = x0 + (i % cols) * (cw + gap);
			int y = y0 + (i / cols) * (chh + gap) + Math.round((1 - Ui.ease(opened + 40L * i, 220)) * 10);
			boxes.add(new int[] {x, y, cw, chh});
			boolean hover = Ui.inside(mouseX, mouseY, x, y, cw, chh);
			Ui.card(g, x, y, cw, chh, ACCENT, Ui.hover("travel:" + pl.id(), hover));
			Ui.inset(g, x + 5, y + 5, 24, 24);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(pl.icon()))), x + 9, y + 9);
			Ui.text(g, Ui.fit(pl.name(), cw - 40), x + 34, y + 7, hover ? 0xFFFFFFFF : ACCENT);
			Ui.text(g, Ui.fit(pl.about(), cw - 40), x + 34, y + 19, Ui.DIM);
		}
		g.centeredText(font, "Esc to stay here", width / 2, y0 + rows * (chh + gap) + 2, Ui.MUTED);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int i = 0; i < boxes.size() && i < places.size(); i++) {
			int[] b = boxes.get(i);
			if (Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
				pick(i);
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	/** Fly to place {@code i} (also for autotests). */
	public void pick(int i) {
		if (i >= 0 && i < places.size()) {
			ClientPlayNetworking.send(new MorePayloads.TravelPick(places.get(i).id()));
			onClose();
		}
	}

	/** For autotests: the place ids in order. */
	public List<String> ids() {
		return places.stream().map(Place::id).toList();
	}
}
