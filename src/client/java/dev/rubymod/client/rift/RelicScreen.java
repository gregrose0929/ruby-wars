package dev.rubymod.client.rift;

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
 * Choosing a relic (or buying one, or answering a mystery): big cards framed in their
 * tier's colour, the relic's icon large, what it does in plain words. Legendaries shimmer
 * gold with a sweep of light; Cursed ones flicker purple. Cards rise in one by one.
 */
public class RelicScreen extends Screen {
	/**
	 * id, title, tier, element, icon, about, price ("" where it doesn't apply), the stars it combines to (0: a new relic),
	 * and the relic combo it would complete for you ("": none).
	 */
	private record Card(String id, String title, String tier, String element, String icon, String about, int price, int combine, String combo) {
		Card(String id, String title, String tier, String element, String icon, String about, int price) {
			this(id, title, tier, element, icon, about, price, 0, "");
		}
	}

	final String kind;
	private String heading = "";
	private int gold;
	private List<Card> cards = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private final long opened = System.currentTimeMillis();

	RelicScreen(String kind, List<String> lines) {
		super(Component.literal("Relics"));
		this.kind = kind;
		update(lines);
	}

	void update(List<String> lines) {
		List<Card> list = new ArrayList<>();
		for (String line : lines) {
			String[] f = line.split("[|]", -1);
			// "title|..." and "gold|N" are two-field headers; an event choice can also be called "gold".
			try {
				if (f[0].equals("title") && f.length <= 2) {
					heading = f.length > 1 ? f[1] : "";
				} else if (f[0].equals("gold") && f.length == 2) {
					gold = Integer.parseInt(f[1]);
				} else if (kind.equals("event") || f[0].equals("heal") || f[0].equals("restock")) {
					if (f.length >= 4) {
						list.add(new Card(f[0], f[1], kind.equals("event") ? "EVENT" : "SERVICE", "NONE", f[3], f[2], f.length > 4 ? Integer.parseInt(f[4]) : -1));
					}
				} else if (f.length >= 7) {
					list.add(new Card(f[0], f[1], f[2], f[3], f[4], f[5], Integer.parseInt(f[6]), f.length > 7 ? Integer.parseInt(f[7]) : 0, f.length > 8 ? f[8] : ""));
				}
			} catch (NumberFormatException e) {
				// A line we can't read is skipped, never a crash.
			}
		}
		cards = list;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static int tierColor(String tier) {
		return switch (tier) {
			case "EVENT" -> 0xFFC27BFF;
			case "SERVICE" -> 0xFF6EE38A;
			default -> RiftClient.tierColor(tier);
		};
	}

	private static int elementColor(String e) {
		return switch (e) {
			case "SHOCK" -> 0xFF66E0FF;
			case "FIRE" -> 0xFFFF7A2E;
			case "FROST" -> 0xFFA8E8FF;
			case "EXPLOSIVE" -> 0xFFFFD040;
			case "RUBY" -> 0xFFFF3D6E;
			case "POISON" -> 0xFF7AE33A;
			default -> 0;
		};
	}

	/** A colour darkened (0..1 of its brightness), for buttons that white text stands out on. */
	static int darker(int color, float f) {
		int r = (int) (((color >> 16) & 0xFF) * f);
		int gr = (int) (((color >> 8) & 0xFF) * f);
		int b = (int) ((color & 0xFF) * f);
		return r << 16 | gr << 8 | b;
	}

	private static String pretty(String s) {
		return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		long now = System.currentTimeMillis();
		float intro = Math.min(1, (now - opened) / 450.0F);
		g.fillGradient(0, 0, width, height, 0xC8100410, 0xE8040208);
		boolean anyLegend = cards.stream().anyMatch(c -> c.tier().equals("LEGENDARY"));
		// Sparks rising behind the cards.
		for (int i = 0; i < 24; i++) {
			double s = (now / 25.0 + i * 61) % height;
			int sx = (int) ((i * 173 + Math.sin(now / 700.0 + i) * 25) % width + width) % width;
			int sy = height - (int) s;
			int c = anyLegend && i % 2 == 0 ? 0x70FFC02E : i % 3 == 0 ? 0x50FF3D6E : 0x40C27BFF;
			g.fill(sx, sy, sx + 2, sy + 2, c);
		}
		// Heading.
		g.pose().pushMatrix();
		g.pose().translate(width / 2.0F, 14);
		g.pose().scale(1.6F, 1.6F);
		g.centeredText(font, heading, 0, 0, kind.equals("shop") ? 0xFFFFC02E : 0xFFFF3D6E);
		g.pose().popMatrix();
		if (kind.equals("shop")) {
			g.centeredText(font, "◆ " + gold + " gold (the whole team's)", width / 2, 34, 0xFFFFC02E);
		}
		int n = cards.size();
		if (n == 0) {
			return;
		}
		int cols = Math.min(n, kind.equals("shop") ? 3 : 3);
		int rows = (n + cols - 1) / cols;
		int gap = 10;
		int cardW = Math.min(132, (width - 40 - gap * (cols - 1)) / cols);
		int cardH = Math.min(rows == 1 ? 190 : 120, (height - 70 - gap * (rows - 1)) / rows);
		int x0 = (width - (cardW * cols + gap * (cols - 1))) / 2;
		int y0 = kind.equals("shop") ? 48 : Math.max(44, (height - cardH * rows - gap * (rows - 1)) / 2 + 8);
		boxes.clear();
		Card hoverCard = null;
		for (int i = 0; i < n; i++) {
			Card c = cards.get(i);
			int col = i % cols;
			int row = i / cols;
			float delay = Math.max(0, Math.min(1, intro * 1.6F - i * 0.18F));
			int x = x0 + col * (cardW + gap);
			int y = y0 + row * (cardH + gap) + (int) ((1 - delay) * 40);
			boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
			if (hover) {
				y -= 4;
				hoverCard = c;
			}
			int tier = tierColor(c.tier());
			boolean legendary = c.tier().equals("LEGENDARY");
			boolean cursed = c.tier().equals("CURSED");
			boolean cantAfford = c.price() > 0 && kind.equals("shop") && gold < c.price();
			// -2: a relic you already have (the merchant still sells it to teammates).
			boolean owned = c.price() == -2;
			// Body: dark, tinted by the tier.
			g.fill(x, y, x + cardW, y + cardH, 0xF8100A12);
			// Glow behind the icon.
			int glowA = (int) (60 + 40 * Math.sin(now / 300.0 + i));
			g.fillGradient(x + 1, y + 1, x + cardW - 1, y + 50, glowA << 24 | tier & 0xFFFFFF, 0x00000000);
			// The frame: shimmering gold for Legendary, flickering purple for Cursed.
			int frame = tier;
			if (!c.combo().isEmpty()) {
				// Completes a combo: an orange frame that flickers with the duo's colour.
				frame = (now / 150 + i) % 2 == 0 ? 0xFFFF9A2E : 0xFFFFE680;
			} else if (c.combine() > 0) {
				// Combining: a gold frame that pulses.
				frame = (now / 200 + i) % 2 == 0 ? 0xFFFFE680 : 0xFFFFB020;
			} else if (legendary) {
				float sh = (float) (0.5 + 0.5 * Math.sin(now / 160.0));
				frame = sh > 0.5F ? 0xFFFFE680 : 0xFFFFC02E;
			} else if (cursed && (now / 90 + i) % 7 == 0) {
				frame = 0xFFFF40FF;
			}
			g.outline(x, y, cardW, cardH, frame);
			g.outline(x + 1, y + 1, cardW - 2, cardH - 2, (hover ? 0xC0000000 : 0x60000000) | frame & 0xFFFFFF);
			if (legendary) {
				// A sweep of light across the card.
				int sweep = (int) ((now / 6) % (cardW * 3)) - cardW;
				g.enableScissor(x + 2, y + 2, x + cardW - 2, y + cardH - 2);
				for (int k = 0; k < 10; k++) {
					g.fill(x + sweep + k * 2 - k, y, x + sweep + k * 2 - k + 2, y + cardH, 0x18FFFFFF);
				}
				g.disableScissor();
			}
			if (!c.combo().isEmpty()) {
				// "COMBO!" ribbon across the top, with a little lightning either side.
				int bw = Math.min(cardW - 8, font.width("\u26A1 COMBO! \u26A1") + 12);
				int bx = x + cardW / 2 - bw / 2;
				int glow = (int) (180 + 75 * Math.sin(now / 120.0));
				g.fill(bx, y - 5, bx + bw, y + 7, glow << 24 | 0xFF9A2E);
				g.outline(bx, y - 5, bw, 12, 0xFFFFE680);
				g.centeredText(font, "\u26A1 COMBO! \u26A1", x + cardW / 2, y - 3, 0xFF201008);
			}
			// Big icon.
			ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c.icon())));
			float bob = (float) Math.sin(now / 350.0 + i) * 2;
			g.pose().pushMatrix();
			// Short cards (two rows of them): a smaller icon, so the name fits above the button.
			boolean compact = cardH < 140;
			float iconScale = compact ? 1.6F : 2.5F;
			g.pose().translate(x + cardW / 2.0F - 8 * iconScale, y + (compact ? 4 : 8) + bob);
			g.pose().scale(iconScale, iconScale);
			g.item(stack, 0, 0);
			g.pose().popMatrix();
			int ty = y + (compact ? 33 : 52);
			// Tier and element.
			String tierText = c.tier().equals("EVENT") || c.tier().equals("SERVICE") ? "" : pretty(c.tier()).toUpperCase();
			int el = elementColor(c.element());
			String elText = el != 0 ? "  •  " + pretty(c.element()) : "";
			if (!tierText.isEmpty()) {
				int tw = font.width(tierText + elText);
				g.text(font, tierText, x + cardW / 2 - tw / 2, ty, tier, true);
				if (el != 0) {
					g.text(font, elText, x + cardW / 2 - tw / 2 + font.width(tierText), ty, el, true);
				}
				ty += 12;
			}
			// Name and what it does.
			for (FormattedCharSequence line : font.split(Component.literal(c.title()).withStyle(net.minecraft.ChatFormatting.BOLD), cardW - 10)) {
				if (ty > y + cardH - 32) {
					break;
				}
				g.centeredText(font, line, x + cardW / 2, ty, 0xFFFFFFFF);
				ty += 10;
			}
			ty += 4;
			g.fill(x + 12, ty - 3, x + cardW - 12, ty - 2, 0x40000000 | tier & 0xFFFFFF);
			for (FormattedCharSequence line : font.split(Component.literal(c.about()), cardW - 14)) {
				if (ty > y + cardH - 26) {
					break;
				}
				g.centeredText(font, line, x + cardW / 2, ty, 0xFFEDE6F2);
				ty += 10;
			}
			// The button.
			String label = owned ? "OWNED" : kind.equals("shop") ? (cantAfford ? "◆ " + c.price() : (c.combine() > 0 ? "COMBINE  ◆ " : "BUY  ◆ ") + c.price())
					: kind.equals("event") ? "CHOOSE" : c.combine() > 0 ? "COMBINE" : "TAKE IT";
			int by = y + cardH - 20;
			int btn = owned ? 0xC0303038 : cantAfford ? 0xC0602020 : hover ? 0xFF000000 | darker(tier, 0.75F) : 0xFF000000 | darker(tier, 0.5F);
			g.fill(x + 8, by, x + cardW - 8, by + 14, btn);
			g.outline(x + 8, by, cardW - 16, 14, owned || cantAfford ? 0x60FFFFFF : 0xFF000000 | tier & 0xFFFFFF);
			g.centeredText(font, label, x + cardW / 2, by + 3, owned ? 0xFF9AA4AE : cantAfford ? 0xFFFF9090 : 0xFFFFFFFF);
			if (delay >= 1 && !owned) {
				boxes.add(new int[] {x, y, cardW, cardH, i});
			}
		}
		String foot = kind.equals("shop") ? "Buy as many as the team's gold allows. Press Esc when you're done, then step into the portal."
				: kind.equals("event") ? "Choose wisely." : "Relics last the whole run. Press Esc to look around first (J brings this back).";
		g.centeredText(font, foot, width / 2, height - 14, 0xFFD0C4DC);
		// Hover a card: everything it does, in full.
		if (hoverCard != null) {
			List<Component> tip = tooltip(hoverCard.title(), hoverCard.tier(), hoverCard.element(), hoverCard.about());
			if (!hoverCard.combo().isEmpty()) {
				tip.add(Component.literal(""));
				tip.addAll(wrap("\u26A1 COMBO! " + hoverCard.combo(), 0xFF9A2E));
			}
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
	}

	/** A relic's full description as tooltip lines: name, tier and element, what it does. */
	static List<Component> tooltip(String title, String tier, String element, String about) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(title).withColor(tierColor(tier) & 0xFFFFFF).withStyle(net.minecraft.ChatFormatting.BOLD));
		if (!tier.equals("EVENT") && !tier.equals("SERVICE")) {
			lines.add(Component.literal(pretty(tier) + (elementColor(element) != 0 ? "  •  " + pretty(element) : "")).withColor(0x9AA4AE));
		}
		lines.addAll(wrap(about, 0xEDE6F2));
		if (tier.equals("CURSED")) {
			lines.add(Component.literal("Cursed: huge power, a real cost.").withColor(0xB04CFF));
		}
		return lines;
	}

	/** Text in tooltip-width lines, all one colour. */
	static List<Component> wrap(String text, int color) {
		List<Component> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && line.length() + word.length() > 42) {
				lines.add(Component.literal(line.toString()).withColor(color));
				line.setLength(0);
			}
			line.append(line.length() > 0 ? " " : "").append(word);
		}
		if (line.length() > 0) {
			lines.add(Component.literal(line.toString()).withColor(color));
		}
		return lines;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (int[] b : boxes) {
			if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
				Card c = cards.get(b[4]);
				String what = kind.equals("shop") ? "buy" : kind.equals("event") ? "event" : "pick";
				ClientPlayNetworking.send(new MorePayloads.RiftChoice(what, c.id()));
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.2F);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
