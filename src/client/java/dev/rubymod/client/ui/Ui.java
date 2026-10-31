package dev.rubymod.client.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.RubyMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The mod's one look, shared by every screen and HUD: dark slate stone panels with ruby
 * rivets, chunky buttons, sunken lists and bars, pixel icons (tools/ui_art.py draws them),
 * one palette, and a little motion (things ease in, hovers glow).
 *
 * Text inside panels has no shadow; headings are larger, with a dark drop.
 */
public final class Ui {
	// ----- The palette -----
	public static final int TEXT = 0xFFF2EEE8;
	public static final int DIM = 0xFFAAB0BA;
	public static final int MUTED = 0xFF6E7480;
	public static final int RUBY = 0xFFE0294A;
	public static final int RUBY_LIGHT = 0xFFFF6A84;
	public static final int GOLD = 0xFFF5C542;
	public static final int GREEN = 0xFF5BD27A;
	public static final int AMBER = 0xFFF2A93B;
	public static final int RED = 0xFFE5484D;
	public static final int BLUE = 0xFF4FA8FF;
	public static final int STONE = 0xFF20242C;
	public static final int DARK = 0xFF15181E;
	/** Each game's tag colour (used small: tags, stripes, emblems). */
	public static final int WARS = RUBY;
	public static final int RIFT = 0xFFC85BFF;
	public static final int CONVOY = 0xFFF29A2E;
	public static final int HORDE = 0xFF4CC66A;
	public static final int PARTY = 0xFF9B7BFF;
	public static final int HUB = 0xFF55C8E8;

	public enum Icon { HEART, SHIELD, STAR, GEM, SKULL, CLOCK, FLAG, WRENCH, WARN, CHECK, CROSS, LOCK, TRUCK, RIGHT, LEFT, DOWN, BOLT, CROWN, DOT }

	private static final Map<String, Identifier> SPRITES = new HashMap<>();
	private static final Map<Object, float[]> HOVER = new HashMap<>();

	private Ui() {
	}

	private static Identifier sprite(String name) {
		return SPRITES.computeIfAbsent(name, n -> RubyMod.id("ui/" + n));
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	// ----- Pieces -----

	/** The main stone panel (screens, HUD cards). */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("panel"), x, y, w, h);
	}

	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("panel"), x, y, w, h, alpha(0xFFFFFFFF, alpha));
	}

	/** A sunken well (lists, slots, text fields). */
	public static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("inset"), x, y, w, h);
	}

	/** A raised card; a stripe of {@code accent} across the top, brighter when hovered. */
	public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int accent, float hover) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("card"), x, y - Math.round(hover * 2), w, h);
		int top = y - Math.round(hover * 2);
		g.fill(x + 2, top + 2, x + w - 2, top + 4, mix(darken(accent, 0.75F), accent, hover));
		if (hover > 0) {
			g.outline(x, top, w, h, alpha(accent, 0.55F * hover));
		}
	}

	/** A chunky button in {@code color}. Returns true while the mouse is on it. */
	public static boolean button(GuiGraphicsExtractor g, String label, int x, int y, int w, int h, int mouseX, int mouseY, int color, boolean enabled) {
		boolean over = enabled && inside(mouseX, mouseY, x, y, w, h);
		float hv = hover(label + "@" + x + "," + y, over);
		int tint = !enabled ? 0xFF4A4F58 : mix(darken(color, 0.82F), lighten(color, 0.12F), hv);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("button"), x, y, w, h, tint);
		Font font = font();
		int ty = y + (h - 2 - 8) / 2;
		g.centeredText(font, label, x + w / 2, ty + 1, 0x60000000);
		g.centeredText(font, label, x + w / 2, ty, enabled ? 0xFFFFFFFF : 0xFF9AA0AA);
		return over;
	}

	/** A dark, quiet button (secondary actions). */
	public static boolean quietButton(GuiGraphicsExtractor g, String label, int x, int y, int w, int h, int mouseX, int mouseY) {
		return button(g, label, x, y, w, h, mouseX, mouseY, 0xFF5A606C, true);
	}

	/** A tab on top of a panel; the one you're on joins the panel. Returns true when hovered. */
	public static boolean tab(GuiGraphicsExtractor g, String label, int x, int y, int w, boolean on, int mouseX, int mouseY) {
		boolean over = inside(mouseX, mouseY, x, y, w, 16);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(on ? "tab_on" : "tab"), x, y, w, on ? 18 : 16);
		g.centeredText(font(), label, x + w / 2, y + 5, on ? TEXT : over ? DIM : MUTED);
		return over;
	}

	/** A small coloured tag ("RARE", "NEW"...). Returns its width. */
	public static int tag(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		int w = font().width(text) + 6;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("pill"), x, y, w, 11, darken(color, 0.85F));
		g.text(font(), text, x + 3, y + 2, 0xFFFFFFFF, false);
		return w;
	}

	/** A sunken bar filled {@code frac} of the way in {@code color}. */
	public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float frac, int color) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("bar"), x, y, w, h);
		int fw = Math.round((w - 2) * Mth.clamp(frac, 0, 1));
		if (fw > 0) {
			g.fill(x + 1, y + 1, x + 1 + fw, y + h - 1, color);
			g.fill(x + 1, y + 1, x + 1 + fw, y + 2, lighten(color, 0.35F));
			if (h > 4) {
				g.fill(x + 1, y + h - 2, x + 1 + fw, y + h - 1, darken(color, 0.7F));
			}
		}
	}

	public static void icon(GuiGraphicsExtractor g, Icon icon, int x, int y) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("icon_" + icon.name().toLowerCase()), x, y, 9, 9);
	}

	/** A game's emblem ("rubywars", "rift", "convoy", "horde", "party", "hub"), {@code size} pixels square. */
	public static void emblem(GuiGraphicsExtractor g, String mode, int x, int y, int size) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("emblem_" + mode), x, y, size, size);
	}

	/** The RUBY WARS logo, {@code scale} times its pixel size (it's 110 x 24), centred on x. */
	public static void logo(GuiGraphicsExtractor g, int cx, int y, int scale) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("logo"), cx - 55 * scale, y, 110 * scale, 24 * scale);
	}

	// ----- Text -----

	/** Plain text, no shadow. */
	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font(), s, x, y, color, false);
	}

	/** Text cut to {@code w} pixels (with "..." when it doesn't fit). */
	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int w, int color) {
		g.text(font(), fit(s, w), x, y, color, false);
	}

	public static String fit(String s, int w) {
		Font font = font();
		if (font.width(s) <= w) {
			return s;
		}
		return font.plainSubstrByWidth(s, Math.max(0, w - font.width("..."))) + "...";
	}

	/** A heading: larger, with a dark drop under it. */
	public static void heading(GuiGraphicsExtractor g, String s, int x, int y, int color, float scale, boolean centred) {
		Font font = font();
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		int ox = centred ? -font.width(s) / 2 : 0;
		g.text(font, s, ox + 1, 1, darken(color, 0.3F), false);
		g.text(font, s, ox, 0, color, false);
		g.pose().popMatrix();
	}

	/** A small label over a section ("Your stats"), in the muted colour with a rule after it. */
	public static void section(GuiGraphicsExtractor g, String s, int x, int y, int w) {
		g.text(font(), s, x, y, GOLD, false);
		int tw = font().width(s);
		if (w > tw + 6) {
			g.fill(x + tw + 4, y + 4, x + w, y + 5, 0x40FFFFFF);
		}
	}

	/** Wrapped text; returns the y just under it. Stops before {@code maxY}. */
	public static int wrap(GuiGraphicsExtractor g, String s, int x, int y, int w, int color, int maxY) {
		for (FormattedCharSequence line : font().split(Component.literal(s), w)) {
			if (y + 9 > maxY) {
				break;
			}
			g.text(font(), line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	public static int lines(String s, int w) {
		return font().split(Component.literal(s), w).size();
	}

	/** A tooltip box on top of everything, kept on screen. */
	public static void tooltip(GuiGraphicsExtractor g, List<String> lines, int mouseX, int mouseY, int screenW, int screenH) {
		if (lines.isEmpty()) {
			return;
		}
		Font font = font();
		int maxW = Math.min(200, screenW - 16);
		java.util.List<FormattedCharSequence> out = new java.util.ArrayList<>();
		java.util.List<Integer> colors = new java.util.ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			for (FormattedCharSequence seq : font.split(Component.literal(lines.get(i)), maxW)) {
				out.add(seq);
				colors.add(i == 0 ? TEXT : DIM);
			}
		}
		int w = 0;
		for (FormattedCharSequence seq : out) {
			w = Math.max(w, font.width(seq));
		}
		int h = out.size() * 10 + 6;
		w += 10;
		int x = mouseX + 10;
		int y = mouseY - 4;
		if (x + w > screenW - 4) {
			x = mouseX - w - 6;
		}
		x = Math.max(4, x);
		y = Mth.clamp(y, 4, Math.max(4, screenH - h - 4));
		g.nextStratum();
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("tooltip"), x, y, w, h);
		for (int i = 0; i < out.size(); i++) {
			g.text(font, out.get(i), x + 5, y + 4 + i * 10, colors.get(i), false);
		}
	}

	/** A soft dark wash over the world behind a screen. */
	public static void dim(GuiGraphicsExtractor g, int w, int h, float amount) {
		g.fillGradient(0, 0, w, h, alpha(0xFF08090C, 0.55F * amount), alpha(0xFF08090C, 0.75F * amount));
	}

	// ----- Motion -----

	/** 0 to 1 over {@code ms} since {@code start}, easing out. */
	public static float ease(long start, int ms) {
		float t = Mth.clamp((System.currentTimeMillis() - start) / (float) ms, 0, 1);
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	/** A hover that fades in and out (keyed by whatever identifies the thing). */
	public static float hover(Object key, boolean on) {
		float[] v = HOVER.computeIfAbsent(key, k -> new float[] {0, System.nanoTime()});
		long now = System.nanoTime();
		float dt = Math.min(0.1F, (now - (long) v[1]) / 1.0E9F);
		v[1] = now;
		v[0] = Mth.clamp(v[0] + (on ? dt * 8 : -dt * 6), 0, 1);
		if (HOVER.size() > 400) {
			HOVER.clear();
		}
		return v[0];
	}

	/** A value that glides toward its target (bars). */
	public static float glide(Object key, float target) {
		float[] v = HOVER.computeIfAbsent("glide:" + key, k -> new float[] {target, System.nanoTime()});
		long now = System.nanoTime();
		float dt = Math.min(0.1F, (now - (long) v[1]) / 1.0E9F);
		v[1] = now;
		v[0] += (target - v[0]) * Math.min(1, dt * 8);
		return v[0];
	}

	/** A slow 0..1..0 pulse. */
	public static float pulse(int periodMs) {
		return 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() * Math.PI * 2 / periodMs);
	}

	// ----- Colour -----

	public static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public static int alpha(int argb, float a) {
		int base = argb >>> 24;
		return Math.round(base * Mth.clamp(a, 0, 1)) << 24 | argb & 0xFFFFFF;
	}

	public static int darken(int argb, float f) {
		int r = (int) ((argb >> 16 & 0xFF) * f);
		int gg = (int) ((argb >> 8 & 0xFF) * f);
		int b = (int) ((argb & 0xFF) * f);
		return argb & 0xFF000000 | r << 16 | gg << 8 | b;
	}

	public static int lighten(int argb, float f) {
		int r = argb >> 16 & 0xFF;
		int gg = argb >> 8 & 0xFF;
		int b = argb & 0xFF;
		r += (int) ((255 - r) * f);
		gg += (int) ((255 - gg) * f);
		b += (int) ((255 - b) * f);
		return argb & 0xFF000000 | r << 16 | gg << 8 | b;
	}

	public static int mix(int a, int b, float t) {
		t = Mth.clamp(t, 0, 1);
		int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF, aa = a >>> 24;
		int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF, ba = b >>> 24;
		return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
	}
}
