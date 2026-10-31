package dev.rubymod.client.rift;

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
		float in = Ui.ease(opened, 260);
		Ui.dim(g, width, height, in);
		boolean anyLegend = cards.stream().anyMatch(c -> c.tier().equals("LEGENDARY"));
		// Motes drifting up behind the cards.
		for (int i = 0; i < 18; i++) {
			double s = (now / 30.0 + i * 61) % height;
			int sx = (int) ((i * 173 + Math.sin(now / 700.0 + i) * 25) % width + width) % width;
			int sy = height - (int) s;
			g.fill(sx, sy, sx + 2, sy + 2, anyLegend && i % 2 == 0 ? 0x60F5C542 : 0x40C85BFF);
		}
		Ui.emblem(g, "rift", width / 2 - 12, 6, 24);
		Ui.heading(g, heading, width / 2, 33, kind.equals("shop") ? Ui.GOLD : Ui.TEXT, 1.5F, true);
		if (kind.equals("shop")) {
			String goldText = gold + " gold (the whole team's)";
			int gw = font.width(goldText) + 12;
			Ui.icon(g, Ui.Icon.GEM, width / 2 - gw / 2, 47);
			Ui.text(g, goldText, width / 2 - gw / 2 + 12, 48, Ui.GOLD);
		}
		int n = cards.size();
		if (n == 0) {
			return;
		}
		int cols = Math.min(n, 3);
		int rows = (n + cols - 1) / cols;
		int gap = 10;
		int cardW = Math.min(128, (width - 40 - gap * (cols - 1)) / cols);
		int cardH = Math.min(rows == 1 ? 180 : 116, (height - 84 - gap * (rows - 1)) / rows);
		int x0 = (width - (cardW * cols + gap * (cols - 1))) / 2;
		int y0 = kind.equals("shop") ? 62 : Math.max(56, (height - cardH * rows - gap * (rows - 1)) / 2 + 12);
		boxes.clear();
		Card tipCard = null;
		for (int i = 0; i < n; i++) {
			Card c = cards.get(i);
			int col = i % cols;
			int row = i / cols;
			float rise = Ui.ease(opened + 80L * i, 300);
			int x = x0 + col * (cardW + gap);
			int y = y0 + row * (cardH + gap) + Math.round((1 - rise) * 24);
			boolean hover = Ui.inside(mouseX, mouseY, x, y, cardW, cardH);
			int tier = tierColor(c.tier());
			boolean legendary = c.tier().equals("LEGENDARY");
			boolean cursed = c.tier().equals("CURSED");
			boolean cantAfford = c.price() > 0 && kind.equals("shop") && gold < c.price();
			// -2: a relic you already have (the merchant still sells it to teammates).
			boolean owned = c.price() == -2;
			int accent = !c.combo().isEmpty() ? Ui.mix(0xFFFF9A2E, 0xFFFFE680, Ui.pulse(500)) : legendary ? Ui.mix(Ui.GOLD, 0xFFFFF0B0, Ui.pulse(900))
					: cursed && (now / 90 + i) % 9 == 0 ? 0xFFFF40FF : tier;
			float hv = Ui.hover("relic:" + c.id() + i, hover && !owned);
			Ui.card(g, x, y, cardW, cardH, accent, hv);
			int lift = Math.round(hv * 2);
			y -= lift;
			if (legendary) {
				// A sweep of light across the card.
				int sweep = (int) ((now / 6) % (cardW * 3)) - cardW;
				g.enableScissor(x + 2, y + 2, x + cardW - 2, y + cardH - 2);
				for (int k = 0; k < 8; k++) {
					g.fill(x + sweep + k, y, x + sweep + k + 2, y + cardH, 0x14FFFFFF);
				}
				g.disableScissor();
			}
			// The icon, big, on a sunken plate glowing in the tier's colour.
			boolean compact = cardH < 130;
			int plate = compact ? 30 : 44;
			int py = y + 9;
			Ui.inset(g, x + cardW / 2 - plate / 2, py, plate, plate);
			g.fill(x + cardW / 2 - plate / 2 + 1, py + 1, x + cardW / 2 + plate / 2 - 1, py + plate - 1,
					Ui.alpha(tier, 0.12F + 0.08F * (float) Math.sin(now / 300.0 + i)));
			float iconScale = compact ? 1.5F : 2.25F;
			float bob = (float) Math.sin(now / 350.0 + i) * 1.5F;
			g.pose().pushMatrix();
			g.pose().translate(x + cardW / 2.0F - 8 * iconScale, py + plate / 2.0F - 8 * iconScale + bob);
			g.pose().scale(iconScale, iconScale);
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c.icon()))), 0, 0);
			g.pose().popMatrix();
			if (!c.combo().isEmpty()) {
				int tw = font.width("Combo!") + 6;
				Ui.tag(g, "Combo!", x + cardW / 2 - tw / 2, y - 5, 0xFFFF9A2E);
			}
			int ty = py + plate + 5;
			// Tier and element, as tags.
			if (!c.tier().equals("EVENT") && !c.tier().equals("SERVICE")) {
				int el = elementColor(c.element());
				String a = pretty(c.tier());
				String b = el != 0 ? pretty(c.element()) : "";
				int tw = font.width(a) + 6 + (b.isEmpty() ? 0 : font.width(b) + 9);
				int tx = x + cardW / 2 - tw / 2;
				tx += Ui.tag(g, a, tx, ty, tier) + 3;
				if (!b.isEmpty()) {
					Ui.tag(g, b, tx, ty, el);
				}
				ty += 15;
			}
			// Name and what it does.
			for (FormattedCharSequence line : font.split(Component.literal(c.title()), cardW - 10)) {
				g.centeredText(font, line, x + cardW / 2, ty, Ui.TEXT);
				ty += 10;
			}
			ty += 3;
			int maxY = y + cardH - 24;
			if (Ui.lines(c.about(), cardW - 14) * 10 > maxY - ty || !c.combo().isEmpty()) {
				if (hover) {
					tipCard = c;
				}
			}
			for (FormattedCharSequence line : font.split(Component.literal(c.about()), cardW - 14)) {
				if (ty + 9 > maxY) {
					break;
				}
				g.centeredText(font, line, x + cardW / 2, ty, Ui.DIM);
				ty += 10;
			}
			// The button.
			String label = owned ? "Owned" : kind.equals("shop") ? (c.combine() > 0 ? "Combine " : "Buy ") + c.price()
					: kind.equals("event") ? "Choose" : c.combine() > 0 ? "Combine" : "Take it";
			Ui.button(g, label, x + 7, y + cardH - 21, cardW - 14, 16, mouseX, mouseY, cantAfford ? 0xFF6A3036 : tier, !owned);
			if (rise >= 0.95F && !owned) {
				boxes.add(new int[] {x, y, cardW, cardH, i});
			}
		}
		String foot = kind.equals("shop") ? "Buy as many as the team's gold allows. Esc when you're done, then step into the portal."
				: kind.equals("event") ? "Choose wisely." : "Relics last the whole run. Esc to look around first (J brings this back).";
		g.centeredText(font, foot, width / 2, height - 14, Ui.MUTED);
		// Hover a card whose text didn't fit (or that completes a combo): all of it.
		if (tipCard != null) {
			List<String> tip = new ArrayList<>();
			tip.add(tipCard.title());
			tip.add(tipCard.about());
			if (!tipCard.combo().isEmpty()) {
				tip.add("Combo! " + tipCard.combo());
			}
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
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
