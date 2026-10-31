package dev.rubymod.client.convoy;

import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Ruby Convoy on screen: a card in the top-left corner with the road (five stages, the
 * truck on it, the finish flag), the truck's armor and shield, spare trucks, what the
 * truck is doing ("MOVING", "STAY CLOSE", "CONTESTED") and what to do next; the team below
 * it; and a marker pointing at the truck whenever you're away from it.
 */
public final class ConvoyClient {
	public static MorePayloads.ConvoyHud hud;
	/** The cards waiting to be picked (J opens them again if the screen was closed). */
	static MorePayloads.ConvoyCards pending;
	private static final String[] NAMES = {"Greenwood", "Ember Town", "Red Canyon", "Frost Pass", "Fortress"};
	private static final int[] COLORS = {0xFF5BC85A, 0xFFFF8A3D, 0xFFD8643A, 0xFF9AD8FF, 0xFFB04060};
	/** Where each stage starts along the road, as a fraction of it (matches ConvoyRoute). */
	private static final float[] STARTS = {0, 176 / 822F, 352 / 822F, 528 / 822F, 704 / 822F};

	private ConvoyClient() {
	}

	public static boolean active() {
		return hud != null && !hud.header().isEmpty() && hud.nums().size() >= 12;
	}

	private static float num(int i) {
		return hud.nums().get(i);
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.ConvoyHud.TYPE, (p, c) -> hud = p);
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.ConvoyCards.TYPE, (p, c) -> {
			Minecraft mc = Minecraft.getInstance();
			if (p.kind().equals("close")) {
				pending = null;
				if (mc.gui.screen() instanceof ConvoyScreen) {
					mc.gui.setScreen(null);
				}
				return;
			}
			pending = p;
			mc.gui.setScreen(new ConvoyScreen(p));
		});
	}

	/** J: open the cards again if there's a pick waiting. True if it did. */
	public static boolean reopen() {
		Minecraft mc = Minecraft.getInstance();
		if (pending != null && mc.gui.screen() == null) {
			mc.gui.setScreen(new ConvoyScreen(pending));
			return true;
		}
		return false;
	}

	public static final class Hud implements HudElement {
		@Override
		public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || !active()) {
				return;
			}
			Font font = mc.font;
			int w = g.guiWidth();
			int h = g.guiHeight();
			long now = System.currentTimeMillis();
			int pw = 172;
			int x = 4;
			// On narrow screens, below the boss bars instead of beside them.
			int top = 4 + pw > w / 2 - 92 ? 44 : 4;
			int stage = (int) num(11);
			int accent = COLORS[Mth.clamp(stage, 0, 4)];
			int ph = 74;
			g.fill(x, top, x + pw, top + ph, 0xE0101418);
			g.fill(x, top, x + pw, top + 1, accent);
			g.text(font, "RUBY CONVOY", x + 5, top + 4, 0xFFFFC040, true);
			String spares = "x" + (int) num(9);
			g.item(new ItemStack(Items.MINECART), x + pw - font.width(spares) - 22, top);
			g.text(font, spares, x + pw - font.width(spares) - 5, top + 4, 0xFFFFFFFF, true);
			g.text(font, font.plainSubstrByWidth(hud.header(), pw - 10), x + 5, top + 15, 0xFFFFFFFF, true);
			// The road: five stages, the truck on it.
			int rx = x + 6;
			int ry = top + 28;
			int rw = pw - 20;
			for (int i = 0; i < 5; i++) {
				int sx = rx + (int) (rw * STARTS[i]);
				int ex = i == 4 ? rx + rw : rx + (int) (rw * STARTS[i + 1]);
				g.fill(sx, ry, ex - 1, ry + 4, i < stage ? COLORS[i] : COLORS[i] & 0x80FFFFFF);
				g.fill(sx, ry - 1, sx + 1, ry + 5, 0xFFE8E8E8);
			}
			g.text(font, "⚑", rx + rw + 2, ry - 3, 0xFFFFD040, true);
			float progress = Mth.clamp(num(0), 0, 1);
			int tx = rx + (int) (rw * progress);
			g.fill(tx - 3, ry - 3, tx + 3, ry + 7, 0xFFFF3050);
			g.outline(tx - 3, ry - 3, 6, 10, 0xFFFFFFFF);
			// The truck's armor (and shield).
			float hp = num(1);
			float max = Math.max(1, num(2));
			float shield = num(3);
			float f = Mth.clamp(hp / max, 0, 1);
			int by = top + 40;
			g.text(font, "TRUCK", x + 5, by, 0xFFD8DEE4, true);
			int bx = x + 40;
			int bw = pw - 46;
			g.fill(bx, by, bx + bw, by + 8, 0xFF301018);
			int col = f > 0.5F ? 0xFF5BE37D : f > 0.25F ? 0xFFFFB040 : ((now / 250) % 2 == 0 ? 0xFFFF4040 : 0xFFB02020);
			g.fill(bx, by, bx + (int) (bw * f), by + 8, col);
			if (shield > 0) {
				g.fill(bx, by, bx + (int) (bw * Mth.clamp(shield / max, 0, 1)), by + 3, 0xFF60C0FF);
			}
			String hpText = (int) hp + " / " + (int) max;
			g.centeredText(font, hpText, bx + bw / 2, by, 0xFFFFFFFF);
			// What it's doing.
			int status = (int) num(4);
			String s;
			int sc;
			switch (status) {
				case 0 -> {
					s = "▶ MOVING";
					sc = 0xFF5BE37D;
				}
				case 1 -> {
					s = "■ STOPPED: GET CLOSER!";
					sc = 0xFFFFE040;
				}
				case 2 -> {
					s = "⚠ CONTESTED: CLEAR ENEMIES!";
					sc = (now / 300) % 2 == 0 ? 0xFFFF5050 : 0xFFFF9090;
				}
				case 3 -> {
					s = "■ THE ROAD IS BLOCKED";
					sc = 0xFFFF9A3D;
				}
				case 4 -> {
					s = "✔ AT THE DEPOT";
					sc = 0xFF7CF59A;
				}
				case 5 -> {
					s = "LOADING UP";
					sc = 0xFFFFC040;
				}
				case 6 -> {
					s = "✖ WRECKED!";
					sc = 0xFFFF4040;
				}
				case 7 -> {
					s = "★ DELIVERED!";
					sc = 0xFFFFD040;
				}
				default -> {
					s = "JOB FAILED";
					sc = 0xFFFF4040;
				}
			}
			g.text(font, font.plainSubstrByWidth(s, pw - 10), x + 5, top + 52, sc, true);
			int oy = top + 63;
			if (!hud.objective().isEmpty()) {
				var lines = font.split(net.minecraft.network.chat.Component.literal(hud.objective()), pw - 10);
				int extra = Math.max(0, lines.size() - 1) * 10 + (num(5) >= 0 ? 6 : 0);
				g.fill(x, top + ph, x + pw, top + ph + extra, 0xE0101418);
				for (var line : lines) {
					g.text(font, line, x + 5, oy, 0xFFFFE8A0, true);
					oy += 10;
				}
				if (num(5) >= 0) {
					g.fill(x + 5, oy, x + pw - 5, oy + 4, 0xFF303030);
					g.fill(x + 5, oy, x + 5 + (int) ((pw - 10) * Mth.clamp(num(5), 0, 1)), oy + 4, 0xFFFFC040);
					oy += 6;
				}
			}
			// The team.
			int ty = Math.max(top + ph, oy) + 3;
			if (hud.team().size() > 1) {
				for (String line : hud.team()) {
					String[] p = line.split("[|]", -1);
					if (p.length < 5) {
						continue;
					}
					boolean down = p[3].equals("1");
					float php = Mth.clamp(Float.parseFloat(p[1]) / Math.max(1, Float.parseFloat(p[2])), 0, 1);
					g.fill(x, ty, x + 130, ty + 14, 0xC0101418);
					g.text(font, font.plainSubstrByWidth(p[0], 62), x + 4, ty + 3, down ? 0xFFFF6060 : 0xFFFFFFFF, true);
					if (down) {
						g.text(font, "DOWN", x + 92, ty + 3, (now / 300) % 2 == 0 ? 0xFFFF3030 : 0xFFA02020, true);
					} else {
						int c = php > 0.5F ? 0xFF5BE37D : php > 0.25F ? 0xFFFFB040 : 0xFFFF4040;
						g.fill(x + 70, ty + 5, x + 126, ty + 9, 0xFF301018);
						g.fill(x + 70, ty + 5, x + 70 + (int) (56 * php), ty + 9, c);
					}
					ty += 16;
				}
			}
			pointer(g, mc, font, w, h);
			// The countdown at the depot, big over the hotbar.
			int secs = (int) num(10);
			if (secs >= 0 && (status == 4 || status == 5)) {
				String t = status == 4 ? "Rolling out in " + secs + "s" : "Rolling out in " + secs + "s";
				int tw = font.width(t);
				g.fill(w / 2 - tw / 2 - 8, h - 78, w / 2 + tw / 2 + 8, h - 62, 0xE0101418);
				g.outline(w / 2 - tw / 2 - 8, h - 78, tw + 16, 16, 0xFFFFC040);
				g.centeredText(font, t, w / 2, h - 74, 0xFFFFFFFF);
				if (pending != null && mc.gui.screen() == null) {
					String hint = "Press J to pick your " + (pending.kind().equals("role") ? "job" : "upgrade");
					int hw = font.width(hint);
					g.fill(w / 2 - hw / 2 - 8, h - 96, w / 2 + hw / 2 + 8, h - 82, 0xE0101418);
					g.outline(w / 2 - hw / 2 - 8, h - 96, hw + 16, 14, (now / 400) % 2 == 0 ? 0xFFFFC040 : 0xFFFF3050);
					g.centeredText(font, hint, w / 2, h - 93, 0xFFFFFFFF);
				}
			}
		}

		/** Where's the truck? A marker across the top of the screen when it's a way off. */
		private static void pointer(GuiGraphicsExtractor g, Minecraft mc, Font font, int w, int h) {
			Vec3 truck = new Vec3(num(6), num(7) + 1.5, num(8));
			Vec3 eye = mc.player.getEyePosition();
			double dist = eye.distanceTo(truck);
			if (dist < 14 || mc.player.getVehicle() != null && dist < 20) {
				return;
			}
			double want = Math.toDegrees(Math.atan2(-(truck.x - eye.x), truck.z - eye.z));
			double rel = Mth.wrapDegrees(want - mc.player.getYRot());
			String label = "TRUCK " + (int) dist + "m";
			int y = 4 + 176 > w / 2 - 92 ? h / 2 - 40 : 44;
			int col = (System.currentTimeMillis() / 400) % 2 == 0 ? 0xFFFFC040 : 0xFFFF7050;
			if (Math.abs(rel) <= 50) {
				int px = w / 2 + (int) (rel / 50.0 * (w / 2 - 60));
				String t = "▼ " + label;
				int tw = font.width(t);
				g.fill(px - tw / 2 - 4, y - 3, px + tw / 2 + 4, y + 10, 0xC0101418);
				g.centeredText(font, t, px, y, col);
			} else {
				String t = rel < 0 ? "◀ " + label : label + " ▶";
				int tw = font.width(t);
				int px = rel < 0 ? 184 : w - tw - 12;
				g.fill(px - 4, y - 3, px + tw + 4, y + 10, 0xC0101418);
				g.text(font, t, px, y, col, true);
			}
		}
	}

	static List<String> names() {
		return List.of(NAMES);
	}
}
