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

	/** The RELICS panel left of the inventory: icons framed in their tier's colour, hover for the description. */
	private static void drawInventoryRelics(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		java.util.List<String> owned = relicLines();
		if (!active() || owned.isEmpty()) {
			return;
		}
		var font = Minecraft.getInstance().font;
		int cols = 4;
		int rows = (owned.size() + cols - 1) / cols;
		int pw = cols * 20 + 8;
		int x0 = Math.max(4, (screen.width - 176) / 2 - pw - 6);
		int y0 = (screen.height - 166) / 2;
		g.fill(x0, y0, x0 + pw, y0 + 16 + rows * 20, 0xE0100810);
		g.outline(x0, y0, pw, 16 + rows * 20, 0xFFFF3D6E);
		g.text(font, "RELICS", x0 + 5, y0 + 4, 0xFFFF3D6E, false);
		java.util.List<net.minecraft.network.chat.Component> tip = null;
		int i = 0;
		for (String r : owned) {
			String[] f = r.split("[|]", -1);
			if (f.length < 4) {
				continue;
			}
			int x = x0 + 4 + (i % cols) * 20;
			int y = y0 + 14 + (i / cols) * 20;
			g.outline(x, y, 18, 18, tierColor(f[2]));
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[1]))), x + 1, y + 1);
			if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
				tip = RelicScreen.tooltip(f[3], f[2], "NONE", f.length > 4 ? f[4] : "");
			}
			i++;
		}
		if (tip != null) {
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
	}

	/** Your relics (the HUD list without the ability line). */
	static java.util.List<String> relicLines() {
		return hud == null ? java.util.List.of() : hud.relics().stream().filter(x -> !x.startsWith("@")).toList();
	}

	/** "@ability|id|title|icon|ticks left|cooldown", or null. */
	static String[] abilityLine() {
		if (hud == null) {
			return null;
		}
		for (String x : hud.relics()) {
			if (x.startsWith("@ability|")) {
				return x.split("[|]", -1);
			}
		}
		return null;
	}

	/** When the last HUD arrived (the ability cooldown counts down from it). */
	static long hudAt;

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
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.RiftHud.TYPE, (p, c) -> {
			hud = p;
			hudAt = System.currentTimeMillis();
		});
		// Your relics beside the inventory (E), with what each one does on hover.
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register(RiftClient::drawInventoryRelics);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.DamageNumber.TYPE, (p, c) -> {
			NUMBERS.add(new Number(new Vec3(p.x(), p.y(), p.z()), p.amount(), p.color(), System.currentTimeMillis()));
			if (NUMBERS.size() > 60) {
				NUMBERS.remove(0);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.ScreenFx.TYPE, (p, c) -> {
			Minecraft mc = Minecraft.getInstance();
			if (p.kind().startsWith("music:")) {
				RiftMusic.set(p.kind().substring(6));
				return;
			}
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
				if (mc.gui.screen() instanceof RiftMapScreen || mc.gui.screen() instanceof RelicScreen || mc.gui.screen() instanceof ThresholdScreen
						|| mc.gui.screen() instanceof CodexScreen) {
					mc.gui.setScreen(null);
				}
				return;
			}
			if (p.kind().equals("summary")) {
				mc.gui.setScreen(new SummaryScreen(p.lines()));
				return;
			}
			if (p.kind().equals("codex")) {
				// The Codex opens on its own (it's never "waiting" behind J).
				if (mc.gui.screen() instanceof CodexScreen codex) {
					codex.update(p.lines());
				} else {
					mc.gui.setScreen(new CodexScreen(p.lines()));
				}
				return;
			}
			lastScreen = p;
			// The Altar opens when you ask (J), or refreshes if it's already open.
			if (!p.kind().equals("threshold") || mc.gui.screen() instanceof ThresholdScreen) {
				open(p);
			}
		});
	}

	static void open(MorePayloads.RiftScreen p) {
		// A broken screen is logged and skipped: it must never drop the player from the run.
		try {
			openScreen(p);
		} catch (RuntimeException e) {
			dev.rubymod.RubyMod.LOGGER.error("Ruby Rift screen {} failed", p.kind(), e);
		}
	}

	private static void openScreen(MorePayloads.RiftScreen p) {
		Minecraft mc = Minecraft.getInstance();
		if (p.kind().equals("threshold")) {
			if (mc.gui.screen() instanceof ThresholdScreen t) {
				t.update(p.lines());
			} else {
				mc.gui.setScreen(new ThresholdScreen(p.lines()));
			}
		} else if (p.kind().equals("map")) {
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
			// The run: one clean card, top left.
			String gold = String.valueOf(hud.gold());
			int pw = Math.max(font.width(hud.header()) + 12, 100);
			// On narrow screens, below the boss bars instead of beside them.
			int top = 4 + pw > w / 2 - 92 ? 42 : 4;
			g.fill(4, top, 4 + pw, top + 28, 0xE0100810);
			g.fill(4, top, 4 + pw, top + 1, 0xFFFF3D6E);
			g.text(font, "RUBY RIFT", 9, top + 5, 0xFFFF5A82, true);
			g.item(new ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET), 4 + pw - font.width(gold) - 22, top + 1);
			g.text(font, gold, 4 + pw - font.width(gold) - 5, top + 5, 0xFFFFD040, true);
			g.text(font, hud.header(), 9, top + 16, 0xFFF0E6F6, true);
			// The team (only when there is one).
			int y = top + 32;
			if (hud.team().size() > 1) {
				for (String line : hud.team()) {
					String[] f = line.split("[|]");
					if (f.length < 4) {
						continue;
					}
					boolean down = f[3].equals("down");
					float hp = Mth.clamp(Float.parseFloat(f[1]) / Math.max(1, Float.parseFloat(f[2])), 0, 1);
					g.fill(4, y, 110, y + 14, 0xC0100810);
					g.text(font, font.plainSubstrByWidth(f[0], 58), 8, y + 3, down ? 0xFFFF6060 : 0xFFFFFFFF, true);
					if (down) {
						g.text(font, "DOWN", 72, y + 3, (now / 300) % 2 == 0 ? 0xFFFF3030 : 0xFFA02020, true);
					} else {
						int col = hp > 0.5F ? 0xFF5BE37D : hp > 0.25F ? 0xFFFFB040 : 0xFFFF4040;
						g.fill(70, y + 5, 106, y + 9, 0xFF301018);
						g.fill(70, y + 5, 70 + (int) (36 * hp), y + 9, col);
					}
					y += 16;
				}
			}
			// Your relics: a small grid in the top-right corner, framed in their tier's colour (the full list: E).
			java.util.List<String> owned = relicLines();
			int cell = 13;
			int rows = Math.max(4, Math.min(owned.size(), (h - 90) / cell));
			int cols = 3;
			int shown = Math.min(owned.size(), rows * cols);
			if (owned.size() > rows * cols) {
				shown = rows * cols - 1;
			}
			for (int n = 0; n < shown; n++) {
				String[] f = owned.get(n).split("[|]");
				if (f.length < 3) {
					continue;
				}
				int x = w - 4 - cell * (1 + n / rows);
				int ry = 4 + (n % rows) * cell;
				int tier = tierColor(f[2]);
				float pulse = 0.55F + 0.45F * (float) Math.sin(now / 450.0 + n);
				g.fill(x, ry, x + cell - 1, ry + cell - 1, 0xC0100810);
				g.outline(x, ry, cell - 1, cell - 1, (int) (pulse * 255) << 24 | tier & 0xFFFFFF);
				g.pose().pushMatrix();
				g.pose().translate(x + 1, ry + 1);
				g.pose().scale(0.65F, 0.65F);
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(f[1]))), 0, 0);
				g.pose().popMatrix();
			}
			if (owned.size() > shown) {
				int x = w - 4 - cell * (1 + shown / rows);
				int ry = 4 + (shown % rows) * cell;
				g.fill(x, ry, x + cell - 1, ry + cell - 1, 0xE0302030);
				g.centeredText(font, "+" + (owned.size() - shown), x + cell / 2, ry + 3, 0xFFFFC02E);
			}
			// Your ability (Z): icon, name, and a cooldown sweep; it flashes when it's ready.
			String[] ab = abilityLine();
			if (ab != null && ab.length >= 6) {
				int left = Math.max(0, Integer.parseInt(ab[4]) - (int) ((now - hudAt) / 50));
				int max = Math.max(1, Integer.parseInt(ab[5]));
				boolean ready = left == 0;
				int ax = w / 2 - 91 - 66;
				int ay = h - 22;
				g.fill(ax, ay, ax + 62, ay + 20, 0xD0100810);
				int edge = ready ? ((now / 300) % 2 == 0 ? 0xFFFFC02E : 0xFFFF3D6E) : 0xFF403040;
				g.outline(ax, ay, 62, 20, edge);
				g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(ab[3]))), ax + 2, ay + 2);
				if (!ready) {
					int cover = (int) (16 * left / (float) max);
					g.fill(ax + 2, ay + 2 + 16 - cover, ax + 18, ay + 18, 0xA0000000);
				}
				g.text(font, "[" + net.minecraft.client.Minecraft.getInstance().options.keySwapOffhand.getTranslatedKeyMessage().getString() + "]", ax + 21, ay + 2, ready ? 0xFFFFC02E : 0xFF8A7A9A, false);
				String label = ready ? "READY" : (left + 19) / 20 + "s";
				g.text(font, label, ax + 21, ay + 11, ready ? 0xFF7CF59A : 0xFFB8A8C8, false);
				g.text(font, font.plainSubstrByWidth(ab[2], 60), ax + 1, ay - 9, 0xFFEDE6F2, true);
			}
			// What to do next, in a pill above the hotbar.
			if (lastScreen != null && mc.gui.screen() == null) {
				String hint = "Press J to " + (lastScreen.kind().equals("map") ? "choose the next room" : lastScreen.kind().equals("shop")
						? "open the shop" : lastScreen.kind().equals("threshold") ? "open the Altar of Echoes" : "choose your relic");
				int tw = font.width(hint);
				int hy = h - 74;
				g.fill(w / 2 - tw / 2 - 8, hy - 4, w / 2 + tw / 2 + 8, hy + 12, 0xE0100810);
				g.outline(w / 2 - tw / 2 - 8, hy - 4, tw + 16, 16, (now / 400) % 2 == 0 ? 0xFFFFC02E : 0xFFFF3D6E);
				g.centeredText(font, hint, w / 2, hy, 0xFFFFFFFF);
			}
		}
	}

	static int tierColor(String tier) {
		return switch (tier) {
			case "RARE" -> 0xFF4FA8FF;
			case "LEGENDARY" -> 0xFFFFC02E;
			case "CURSED" -> 0xFFB04CFF;
			case "BOND" -> 0xFF2EE6C8;
			case "LEGENDARY_WEAPON" -> 0xFFFFC02E;
			case "DUO" -> 0xFFFF9A2E;
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
			g.text(font, text, -font.width(text) / 2, 0, color, true);
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
