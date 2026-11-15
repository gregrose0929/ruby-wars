package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A collection book (the fish book, the butterfly book): a picture of every kind, the ones you haven't found yet dark. */
public class BookScreen extends Screen {
	private record Entry(String name, ItemStack icon, boolean got, String about) {
	}

	private final List<Entry> entries = new ArrayList<>();
	private final String heading;
	private final long opened = System.currentTimeMillis();

	BookScreen(MorePayloads.BookScreen book) {
		super(Component.literal(book.title()));
		heading = book.title();
		for (String line : book.entries()) {
			String[] f = line.split("[|]", -1);
			if (f.length < 4) {
				continue;
			}
			ItemStack icon;
			if (f[1].startsWith("rubymod:park_")) {
				icon = new ItemStack(Items.STICK);
				icon.set(DataComponents.ITEM_MODEL, Identifier.parse(f[1]));
			} else {
				icon = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[1])));
			}
			entries.add(new Entry(f[0], icon, Boolean.parseBoolean(f[2]), f[3]));
		}
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.BookScreen.TYPE, (p, c) -> Minecraft.getInstance().gui.setScreen(new BookScreen(p)));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 200));
		int cols = Math.max(3, Math.min(9, (width - 20) / 46));
		int cw = 44;
		int ch = 40;
		int rows = (entries.size() + cols - 1) / cols;
		int total = 30 + rows * ch + 14;
		int oy = Ui.centre(height, total);
		Ui.heading(g, heading, width / 2, oy + 4, Ui.TEXT, 1.2F, true);
		int x0 = (width - cols * cw) / 2;
		Entry hover = null;
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			int x = x0 + (i % cols) * cw;
			int y = oy + 26 + (i / cols) * ch;
			Ui.inset(g, x + cw / 2 - 12, y, 24, 24);
			if (e.got()) {
				g.item(e.icon(), x + cw / 2 - 8, y + 4);
			} else {
				g.centeredText(font, "?", x + cw / 2, y + 8, Ui.MUTED);
			}
			g.centeredText(font, Ui.fit(e.got() ? e.name() : "???", cw - 2), x + cw / 2, y + 27, e.got() ? Ui.TEXT : Ui.MUTED);
			if (Ui.inside(mouseX, mouseY, x, y, cw, ch)) {
				hover = e;
			}
		}
		if (hover != null) {
			g.centeredText(font, (hover.got() ? hover.name() : "Not found yet") + "  (" + hover.about() + ")", width / 2, oy + 26 + rows * ch + 2, Ui.DIM);
		} else {
			g.centeredText(font, "Esc to close", width / 2, oy + 26 + rows * ch + 2, Ui.MUTED);
		}
	}
}
