package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Ruby Rift on your screen: the run header and the team's gold, everyone's health, your
 * relics along the bottom, damage numbers popping off what you hit, and the big screen
 * effects (colour flashes, the cold blur of Bullet Time, the Time Loop rewind). Also the
 * Rocket Boots double jump and Blink keys, and screens opening when the server says.
 */
public final class RiftClient {
	public static MorePayloads.RiftHud hud;
	/** The last screen the server sent (to reopen with J). */
	public static MorePayloads.RiftScreen lastScreen;

	private record Number(Vec3 pos, float amount, int color, long born) {
	}

	private static final List<Number> NUMBERS = new ArrayList<>();
	private static String fx = "";
	private static int fxColor;
	private static long fxStart;
	private static int fxTicks;
	private static boolean prevJump;

	private RiftClient() {
	}

	public static boolean active() {
		return hud != null && !hud.header().isEmpty();
	}

	static boolean hasRelic(String id) {
		if (!active()) {
			return false;
		}
		for (String r : hud.relics()) {
			if (r.startsWith(id + "|")) {
				return true;
			}
		}
		return false;
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.RiftHud.TYPE, (p, c) -> hud = p);
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.DamageNumber.TYPE, (p, c) -> {
			NUMBERS.add(new Number(new Vec3(p.x(), p.y(), p.z()), p.amount(), p.color(), System.currentTimeMillis()));
			if (NUMBERS.size() > 60) {
				NUMBERS.remove(0);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.ScreenFx.TYPE, (p, c) -> {
			Minecraft mc = Minecraft.getInstance();
			if (p.kind().startsWith("relic:")) {
				if (mc.player != null) {
					mc.player.displayItemActivation(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(p.kind().substring(6)))));
				}
				return;
			}
			fx = p.kind();
			fxColor = p.color();
			fxTicks = p.ticks();
			fxStart = System.currentTimeMillis();
		});
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.RiftScreen.TYPE, (p, c) -> {
			Minecraft mc = Minecraft.getInstance();
			if (p.kind().equals("close")) {
				lastScreen = null;
				if (mc.gui.screen() instanceof RiftMapScreen || mc.gui.screen() instanceof RelicScreen) {
					mc.gui.setScreen(null);
				}
				return;
			}
			lastScreen = p;
			open(p);
		});
	}

	static void open(MorePayloads.RiftScreen p) {
		Minecraft mc = Minecraft.getInstance();
		if (p.kind().equals("map")) {
			if (mc.gui.screen() instanceof RiftMapScreen map) {
				map.update(p.lines());
			} else {
				mc.gui.setScreen(new RiftMapScreen(p.lines()));
			}
		} else if (mc.gui.screen() instanceof RelicScreen relics && relics.kind.equals(p.kind())) {
			relics.update(p.lines());
		} else {
			mc.gui.setScreen(new RelicScreen(p.kind(), p.lines()));
		}
	}

	/** J in the Rift: reopen whatever's waiting (the map vote, a relic choice, the shop). */
	public static boolean reopen() {
		if (!active() || lastScreen == null) {
			return false;
		}
		open(lastScreen);
		return true;
	}

	/** Every client tick: the double jump and Blink keys. */
	public static void tick(Minecraft mc) {
		if (!active() || mc.player == null || mc.gui.screen() != null) {
			prevJump = false;
			return;
		}
		boolean jump = mc.options.keyJump.isDown();
		if (jump && !prevJump) {
			if (mc.options.keyShift.isDown() && hasRelic("blink")) {
				ClientPlayNetworking.send(new MorePayloads.RiftChoice("ability", "blink"));
			} else if (!mc.player.onGround() && hasRelic("rocket_boots")) {
				ClientPlayNetworking.send(new MorePayloads.RiftChoice("ability", "jump"));
			}
		}
		prevJump = jump;
	}

	// ----- On screen -----

	public static final class Hud implements HudElement {
		@Override
		public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) {
				return;
			}
			drawNumbers(g, mc);
			drawFx(g, mc);
			if (!active()) {
				return;
			}
			Font font = mc.font;
			int w = g.guiWidth();
			int h = g.guiHeight();
			long now = System.currentTimeMillis();
			// The run: where you are and the team's gold.
			int pw = Math.max(150, font.width(hud.header()) + 14);
			g.fillGradient(4, 4, 4 + pw, 34, 0xD0200814, 0xD0100410);
			g.outline(4, 4, pw, 30, 0xFFFF3D6E);
			g.text(font, "RUBY RIFT", 10, 8, 0xFFFF3D6E, true);
			g.text(font, hud.header(), 10, 20, 0xFFE8D8F0, false);
			String gold = "◆ " + hud.gold();
			g.text(font, gold, 4 + pw - font.width(gold) - 6, 8, 0xFFFFC02E, true);
			// The team.
			int y = 40;
			for (String line : hud.team()) {
				String[] f = line.split("[|]");
				if (f.length < 4) {
					continue;
				}
				boolean down = f[3].equals("down");
				float hp = Mth.clamp(Float.parseFloat(f[1]) / Math.max(1, Float.parseFloat(f[2])), 0, 1);
				g.fill(4, y, 104, y + 13, 0xB0101014);
				g.text(font, font.plainSubstrByWidth(f[0], 60), 7, y + 3, down ? 0xFFFF5050 : 0xFFFFFFFF, false);
				if (down) {
					boolean blink = (now / 300) % 2 == 0;
					g.text(font, "DOWN", 70, y + 3, blink ? 0xFFFF3030 : 0xFF801010, true);
				} else {
					int bar = (int) (32 * hp);
					int col = hp > 0.5F ? 0xFF5BE37D : hp > 0.25F ? 0xFFFFB040 : 0xFFFF4040;
					g.fill(68, y + 4, 100, y + 9, 0xFF301010);
					g.fill(68, y + 4, 68 + bar, y + 9, col);
				}
				y += 15;
			}
			// Your relics, along the bottom left, framed in their tier's colour.
			int x = 4;
			int ry = h - 24;
			int n = 0;
			for (String r : hud.relics()) {
				String[] f = r.split("[|]");
				if (f.length < 3) {
					continue;
				}
				int tier = tierColor(f[2]);
				float pulse = 0.6F + 0.4F * (float) Math.sin(now / 400.0 + n);
				int glow = (int) (pulse * 255) << 24 | tier & 0xFFFFFF;
				g.fill(x, ry, x + 20, ry + 20, 0xC0101014);
				g.outline(x, ry, 20, 20, glow);
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[1]))), x + 2, ry + 2);
				x += 22;
				n++;
				if (x > w / 2 - 110) {
					x = 4;
					ry -= 22;
				}
			}
			if (lastScreen != null && mc.gui.screen() == null) {
				String hint = "Press J to " + (lastScreen.kind().equals("map") ? "choose the next room" : lastScreen.kind().equals("shop")
						? "open the shop" : "choose your relic");
				boolean blink = (now / 500) % 2 == 0;
				g.centeredText(font, hint, w / 2, h - 72, blink ? 0xFFFFC02E : 0xFFFF3D6E);
			}
		}
	}

	static int tierColor(String tier) {
		return switch (tier) {
			case "RARE" -> 0xFF4FA8FF;
			case "LEGENDARY" -> 0xFFFFC02E;
			case "CURSED" -> 0xFFB04CFF;
			default -> 0xFFB8C4D0;
		};
	}

	/** Damage numbers: pop up, drift up, fade out. Big hits are big. */
	private static void drawNumbers(GuiGraphicsExtractor g, Minecraft mc) {
		if (NUMBERS.isEmpty()) {
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		long now = System.currentTimeMillis();
		Font font = mc.font;
		for (Iterator<Number> it = NUMBERS.iterator(); it.hasNext();) {
			Number n = it.next();
			float age = (now - n.born()) / 900.0F;
			if (age >= 1) {
				it.remove();
				continue;
			}
			float[] s = dev.rubymod.client.map.Markers.project(camera, n.pos().add(0, age * 0.9, 0), g.guiWidth(), g.guiHeight());
			if (s == null) {
				continue;
			}
			String text = n.amount() >= 9999 ? "☠" : n.amount() >= 10 ? String.valueOf(Math.round(n.amount())) : String.format("%.1f", n.amount());
			float pop = age < 0.12F ? 1 + (0.12F - age) * 5 : 1;
			float size = (n.amount() >= 30 ? 1.8F : n.amount() >= 12 ? 1.35F : 1.0F) * pop;
			int alpha = (int) (255 * (1 - Math.max(0, age - 0.6F) / 0.4F));
			int color = Math.max(16, alpha) << 24 | n.color() & 0xFFFFFF;
			g.pose().pushMatrix();
			g.pose().translate(s[0], s[1]);
			g.pose().scale(size, size);
			g.text(font, text, -font.width(text) / 2 + 1, 1, Math.max(16, alpha) << 24, false);
			g.text(font, text, -font.width(text) / 2, 0, color, false);
			g.pose().popMatrix();
		}
	}

	/** Full-screen effects: a colour flash, Bullet Time's frosty edges, the Time Loop rewind. */
	private static void drawFx(GuiGraphicsExtractor g, Minecraft mc) {
		if (fx.isEmpty()) {
			return;
		}
		float t = (System.currentTimeMillis() - fxStart) / (fxTicks * 50.0F);
		if (t >= 1) {
			fx = "";
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		int rgb = fxColor & 0xFFFFFF;
		switch (fx) {
			case "flash" -> g.fill(0, 0, w, h, (int) (170 * (1 - t)) << 24 | rgb);
			case "slowmo" -> {
				float a = t < 0.1F ? t * 10 : t > 0.85F ? (1 - t) / 0.15F : 1;
				int edge = (int) (150 * a) << 24 | rgb;
				int band = Math.max(20, w / 6);
				g.fillGradient(0, 0, w, band, edge, 0x00000000 | rgb);
				g.fillGradient(0, h - band, w, h, 0x00000000 | rgb, edge);
				g.fill(0, 0, w, h, (int) (35 * a) << 24 | rgb);
			}
			case "rewind" -> {
				g.fill(0, 0, w, h, (int) (90 * (1 - t)) << 24 | rgb);
				int off = (int) (System.currentTimeMillis() / 30 % 12);
				for (int y = -off; y < h; y += 12) {
					g.fill(0, y, w, y + 2, (int) (120 * (1 - t)) << 24 | 0xFFFFFF);
				}
			}
			default -> {
			}
		}
	}
}
