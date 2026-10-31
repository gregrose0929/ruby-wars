package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Your profile (I): lifetime stats on the left, cosmetics on the right (hats, trails,
 * kill effects). Click one you've unlocked to wear it (click again to take it off);
 * hover a locked one to see how to earn it.
 */
public class ProfileScreen extends Screen {
	/** The latest from the server. */
	public static MorePayloads.ProfileSync data;

	private record Slot(String id, String kind, int x, int y, boolean unlocked, boolean wearing) {
	}

	private final List<Slot> slots = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	public ProfileScreen() {
		super(Component.literal("Profile"));
		ClientPlayNetworking.send(new MorePayloads.ProfileRequest());
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
		String name = minecraft != null && minecraft.player != null ? minecraft.player.getName().getString() : "";
		int colW = Math.min(214, width / 2 - 12);
		int leftX = width / 2 - colW - 3;
		int rightX = width / 2 + 3;
		int top = 34 + Math.round((1 - in) * 8);
		int bottom = height - 22;
		Ui.heading(g, name, width / 2, 10, Ui.TEXT, 1.5F, true);
		g.centeredText(font, "Your profile", width / 2, 26, Ui.MUTED);
		// Stats.
		Ui.panel(g, leftX, top, colW, bottom - top);
		Ui.section(g, "Stats", leftX + 10, top + 9, colW - 20);
		int y = top + 24;
		if (data != null) {
			int row = 0;
			for (String line : data.stats()) {
				String[] f = line.split("[|]", 2);
				boolean sub = f[0].startsWith("  ");
				int split = colW * 9 / 20;
				if (!sub && row++ % 2 == 0) {
					g.fill(leftX + 6, y - 2, leftX + colW - 6, y + 9, 0x18FFFFFF);
				}
				Ui.text(g, f[0].trim(), leftX + (sub ? 16 : 10), y, split - 14, sub ? Ui.MUTED : Ui.DIM);
				String value = f.length > 1 ? f[1] : "";
				Ui.text(g, value, leftX + split, y, colW - split - 10, sub ? Ui.MUTED : Ui.TEXT);
				y += sub ? 10 : 12;
				if (y > bottom - 12) {
					break;
				}
			}
		}
		// Cosmetics.
		slots.clear();
		Ui.panel(g, rightX, top, colW, bottom - top);
		Ui.section(g, "Cosmetics", rightX + 10, top + 9, colW - 20);
		List<String> tip = null;
		if (data != null) {
			int sy = top + 24;
			for (String kind : new String[] {"HAT", "TRAIL", "KILL"}) {
				Ui.text(g, kind.equals("HAT") ? "Hats" : kind.equals("TRAIL") ? "Trails" : "Kill effects", rightX + 10, sy, Ui.DIM);
				sy += 11;
				int sx = rightX + 10;
				for (String line : data.cosmetics()) {
					String[] f = line.split("[|]");
					if (f.length < 7 || !f[1].equals(kind)) {
						continue;
					}
					if (sx + 22 > rightX + colW - 8) {
						sx = rightX + 10;
						sy += 24;
					}
					boolean unlocked = f[5].equals("true");
					boolean wearing = f[6].equals("true");
					slots.add(new Slot(f[0], kind, sx, sy, unlocked, wearing));
					boolean over = Ui.inside(mouseX, mouseY, sx, sy, 20, 20);
					Ui.inset(g, sx, sy, 20, 20);
					if (wearing) {
						g.fill(sx + 1, sy + 1, sx + 19, sy + 19, 0x405BD27A);
						g.outline(sx, sy, 20, 20, Ui.GREEN);
					} else if (over && unlocked) {
						g.outline(sx, sy, 20, 20, Ui.TEXT);
					}
					g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[3]))), sx + 2, sy + 2);
					if (!unlocked) {
						g.fill(sx + 1, sy + 1, sx + 19, sy + 19, 0xB0101216);
						Ui.icon(g, Ui.Icon.LOCK, sx + 6, sy + 6);
					}
					if (over) {
						tip = List.of(f[2], wearing ? "You're wearing it. Click to take it off." : unlocked ? "Click to wear it." : "To unlock: " + f[4]);
					}
					sx += 23;
				}
				sy += 30;
			}
		} else {
			Ui.text(g, "Loading...", rightX + 10, top + 26, Ui.MUTED);
		}
		g.centeredText(font, "Win games and set records to unlock more.", width / 2, height - 14, Ui.MUTED);
		if (tip != null) {
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (Slot s : slots) {
			if (event.x() >= s.x() && event.x() < s.x() + 20 && event.y() >= s.y() && event.y() < s.y() + 20) {
				if (s.unlocked()) {
					ClientPlayNetworking.send(new MorePayloads.CosmeticSelect(s.wearing() ? "none:" + s.kind() : s.id()));
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
