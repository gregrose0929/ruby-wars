package dev.rubymod.client;

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
import net.minecraft.world.item.ItemStack;

/**
 * Your profile (P): lifetime stats on the left, cosmetics on the right (hats, trails,
 * kill effects). Click one you've unlocked to wear it (click again to take it off);
 * hover a locked one to see how to earn it.
 */
public class ProfileScreen extends Screen {
	/** The latest from the server. */
	public static MorePayloads.ProfileSync data;

	private record Slot(String id, String kind, int x, int y, boolean unlocked, boolean wearing) {
	}

	private final List<Slot> slots = new ArrayList<>();

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
		String name = minecraft != null && minecraft.player != null ? minecraft.player.getName().getString() : "";
		g.centeredText(font, "PROFILE: " + name, width / 2, 8, 0xFFFFFFFF);
		int colW = Math.min(210, width / 2 - 12);
		int leftX = width / 2 - colW - 4;
		int rightX = width / 2 + 4;
		int top = 24;
		// Stats.
		g.fill(leftX, top, leftX + colW, height - 20, 0xD0141A20);
		g.text(font, "STATS", leftX + 5, top + 4, 0xFF55D8FF, true);
		int y = top + 18;
		if (data != null) {
			for (String line : data.stats()) {
				String[] f = line.split("[|]", 2);
				boolean sub = f[0].startsWith("  ");
				int split = colW * 11 / 20;
				g.text(font, font.plainSubstrByWidth(f[0].trim(), split - 14), leftX + (sub ? 12 : 5), y, sub ? 0xFF9AA4AE : 0xFFD8DEE4, false);
				String value = f.length > 1 ? f[1] : "";
				g.text(font, font.plainSubstrByWidth(value, colW - split - 4), leftX + split, y, sub ? 0xFF9AA4AE : 0xFFFFC040, false);
				y += sub ? 10 : 12;
				if (y > height - 30) {
					break;
				}
			}
		}
		// Cosmetics.
		slots.clear();
		g.fill(rightX, top, rightX + colW, height - 20, 0xD0141A20);
		g.text(font, "COSMETICS", rightX + 5, top + 4, 0xFFC080FF, true);
		String hover = null;
		if (data != null) {
			int sy = top + 18;
			for (String kind : new String[] {"HAT", "TRAIL", "KILL"}) {
				g.text(font, kind.equals("HAT") ? "Hats" : kind.equals("TRAIL") ? "Trails" : "Kill effects", rightX + 5, sy, 0xFFD8DEE4, false);
				sy += 11;
				int sx = rightX + 5;
				for (String line : data.cosmetics()) {
					String[] f = line.split("[|]");
					if (f.length < 7 || !f[1].equals(kind)) {
						continue;
					}
					if (sx + 22 > rightX + colW) {
						sx = rightX + 5;
						sy += 24;
					}
					boolean unlocked = f[5].equals("true");
					boolean wearing = f[6].equals("true");
					slots.add(new Slot(f[0], kind, sx, sy, unlocked, wearing));
					boolean over = mouseX >= sx && mouseX < sx + 20 && mouseY >= sy && mouseY < sy + 20;
					g.fill(sx, sy, sx + 20, sy + 20, wearing ? 0xFF2E6B3C : unlocked ? (over ? 0xFF3A4450 : 0xFF283038) : 0xFF15181C);
					g.outline(sx, sy, 20, 20, wearing ? 0xFF5BE37D : over ? 0xFFFFFFFF : unlocked ? 0xFF6A7480 : 0xFF2A2E34);
					g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[3]))), sx + 2, sy + 2);
					if (!unlocked) {
						g.fill(sx + 1, sy + 1, sx + 19, sy + 19, 0xA0000000);
						g.text(font, "?", sx + 8, sy + 6, 0xFF8A949E, false);
					}
					if (over) {
						hover = f[2] + "|" + (wearing ? "Wearing it: click to take it off" : unlocked ? "Click to wear it" : "To unlock: " + f[4]);
					}
					sx += 23;
				}
				sy += 28;
			}
		} else {
			g.text(font, "Loading...", rightX + 5, top + 20, 0xFF8A949E, false);
		}
		g.centeredText(font, "Win games and hit records to unlock more!", width / 2, height - 13, 0xFF8A949E);
		if (hover != null) {
			String[] h = hover.split("[|]", 2);
			g.setComponentTooltipForNextFrame(font, List.of(Component.literal(h[0]), Component.literal(h[1])), mouseX, mouseY);
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
