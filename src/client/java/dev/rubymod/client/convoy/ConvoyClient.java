package dev.rubymod.client.convoy;

import java.util.List;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import dev.rubymod.client.ui.Ui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
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

	/** When the objective last changed (it slides in fresh). */
	private static String lastObjective = "";
	private static long objectiveAt;

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
			int pw = 168;
			int x = 4;
			// On narrow screens, below the boss bars instead of beside them.
			int top = 4 + pw > w / 2 - 92 ? 44 : 4;
			int stage = Mth.clamp((int) num(11), 0, 4);
			int ph = 54;
			Ui.panel(g, x, top, pw, ph);
			Ui.emblem(g, "convoy", x + 5, top + 4, 16);
			Ui.text(g, hud.header(), x + 24, top + 8, pw - 30, Ui.TEXT);
			// The road: five stages, the truck on it, the flag at the end.
			int rx = x + 7;
			int ry = top + 24;
			int rw = pw - 26 - spareWidth();
			for (int i = 0; i < 5; i++) {
				int sx = rx + (int) (rw * STARTS[i]);
				int ex = i == 4 ? rx + rw : rx + (int) (rw * STARTS[i + 1]);
				g.fill(sx, ry, ex - 1, ry + 3, i < stage ? COLORS[i] : i == stage ? Ui.darken(COLORS[i], 0.8F) : 0xFF2A2E36);
			}
			Ui.icon(g, Ui.Icon.FLAG, rx + rw + 2, ry - 5);
			// Spare trucks, as little trucks.
			int spares = Math.min(5, (int) num(9));
			for (int i = 0; i < spares; i++) {
				Ui.icon(g, Ui.Icon.TRUCK, x + pw - 12 - i * 10, ry - 3);
			}
			float progress = Ui.glide("convoy:road", Mth.clamp(num(0), 0, 1));
			Ui.icon(g, Ui.Icon.TRUCK, rx + (int) (rw * progress) - 4, ry - 3);
			// The truck's armor (and shield).
			float hp = num(1);
			float max = Math.max(1, num(2));
			float f = Ui.glide("convoy:hp", Mth.clamp(hp / max, 0, 1));
			int by = top + 34;
			Ui.icon(g, Ui.Icon.WRENCH, x + 6, by - 1);
			int col = f > 0.5F ? Ui.GREEN : f > 0.25F ? Ui.AMBER : Ui.mix(Ui.RED, 0xFFFF9090, Ui.pulse(500));
			Ui.bar(g, x + 18, by, pw - 60, 7, f, col);
			if (num(3) > 0) {
				g.fill(x + 19, by + 1, x + 19 + Math.round((pw - 62) * Mth.clamp(num(3) / max, 0, 1)), by + 3, Ui.BLUE);
			}
			String hpText = String.valueOf((int) hp);
			Ui.text(g, hpText, x + pw - 6 - font.width(hpText), by, Ui.DIM);
			// What it's doing.
			int status = (int) num(4);
			Ui.Icon icon;
			String text;
			int color;
			switch (status) {
				case 0 -> {
					icon = Ui.Icon.RIGHT;
					text = "Moving";
					color = Ui.GREEN;
				}
				case 1 -> {
					icon = Ui.Icon.WARN;
					text = "Stopped: get closer to it";
					color = Ui.AMBER;
				}
				case 2 -> {
					icon = Ui.Icon.WARN;
					text = "Enemies at the truck!";
					color = Ui.mix(Ui.RED, 0xFFFFA0A0, Ui.pulse(600));
				}
				case 3 -> {
					icon = Ui.Icon.CROSS;
					text = "The road is blocked";
					color = Ui.AMBER;
				}
				case 4 -> {
					icon = Ui.Icon.CHECK;
					text = "At the depot";
					color = Ui.GREEN;
				}
				case 5 -> {
					icon = Ui.Icon.CLOCK;
					text = "Getting ready";
					color = Ui.GOLD;
				}
				case 6 -> {
					icon = Ui.Icon.SKULL;
					text = "Wrecked!";
					color = Ui.RED;
				}
				case 7 -> {
					icon = Ui.Icon.STAR;
					text = "Delivered!";
					color = Ui.GOLD;
				}
				default -> {
					icon = Ui.Icon.CROSS;
					text = "Job failed";
					color = Ui.RED;
				}
			}
			Ui.icon(g, icon, x + 6, top + 43);
			Ui.text(g, text, x + 18, top + 44, pw - 24, color);
			int y = top + ph + 3;
			// What to do now: its own box, sliding in when it changes.
			if (!hud.objective().equals(lastObjective)) {
				lastObjective = hud.objective();
				objectiveAt = System.currentTimeMillis();
			}
			if (!hud.objective().isEmpty()) {
				float in = Ui.ease(objectiveAt, 260);
				int lines = Ui.lines(hud.objective(), pw - 16);
				int oh = 8 + lines * 10 + (num(5) >= 0 ? 7 : 0);
				int ox = x - Math.round((1 - in) * 20);
				Ui.panel(g, ox, y, pw, oh, in);
				g.fill(ox + 2, y + 2, ox + 4, y + oh - 2, Ui.GOLD);
				int ty = Ui.wrap(g, hud.objective(), ox + 9, y + 5, pw - 16, Ui.alpha(Ui.TEXT, in), y + oh);
				if (num(5) >= 0) {
					Ui.bar(g, ox + 9, ty, pw - 18, 4, Ui.glide("convoy:obj", Mth.clamp(num(5), 0, 1)), Ui.GOLD);
				}
				y += oh + 3;
			}
			// The team.
			if (hud.team().size() > 1) {
				int th = 6 + hud.team().size() * 12;
				Ui.panel(g, x, y, pw, th);
				int ty = y + 4;
				for (String line : hud.team()) {
					String[] p = line.split("[|]", -1);
					if (p.length < 5) {
						continue;
					}
					boolean down = p[3].equals("1");
					float php = Mth.clamp(Float.parseFloat(p[1]) / Math.max(1, Float.parseFloat(p[2])), 0, 1);
					Ui.text(g, p[0], x + 7, ty + 1, 70, down ? Ui.RED : Ui.TEXT);
					if (down) {
						Ui.icon(g, Ui.Icon.SKULL, x + 82, ty);
						Ui.text(g, "Down", x + 94, ty + 1, Ui.RED);
					} else {
						Ui.bar(g, x + 82, ty + 2, pw - 90, 5, php, php > 0.5F ? Ui.GREEN : php > 0.25F ? Ui.AMBER : Ui.RED);
					}
					ty += 12;
				}
			}
			pointer(g, mc, font, w, h);
			// The countdown at the depot, over the hotbar.
			int secs = (int) num(10);
			if (secs >= 0 && (status == 4 || status == 5)) {
				String t = "Rolling out in " + secs;
				int tw = font.width(t) + 24;
				Ui.panel(g, w / 2 - tw / 2, h - 82, tw, 18);
				Ui.icon(g, Ui.Icon.CLOCK, w / 2 - tw / 2 + 6, h - 78);
				Ui.text(g, t, w / 2 - tw / 2 + 18, h - 77, Ui.TEXT);
				if (pending != null && mc.gui.screen() == null) {
					String hint = "Press J to pick your " + (pending.kind().equals("role") ? "job" : "upgrade");
					int hw = font.width(hint) + 14;
					Ui.panel(g, w / 2 - hw / 2, h - 102, hw, 18);
					g.fill(w / 2 - hw / 2 + 2, h - 100, w / 2 - hw / 2 + 4, h - 86, Ui.mix(Ui.GOLD, Ui.RUBY, Ui.pulse(900)));
					Ui.text(g, hint, w / 2 - hw / 2 + 9, h - 97, Ui.TEXT);
				}
			}
		}

		private static int spareWidth() {
			return Math.min(5, (int) num(9)) * 10 + 4;
		}

		/** Where's the truck? A marker near the top of the screen when it's a way off. */
		private static void pointer(GuiGraphicsExtractor g, Minecraft mc, Font font, int w, int h) {
			Vec3 truck = new Vec3(num(6), num(7) + 1.5, num(8));
			Vec3 eye = mc.player.getEyePosition();
			double dist = eye.distanceTo(truck);
			if (dist < 14 || mc.player.getVehicle() != null && dist < 20) {
				return;
			}
			double want = Math.toDegrees(Math.atan2(-(truck.x - eye.x), truck.z - eye.z));
			double rel = Mth.wrapDegrees(want - mc.player.getYRot());
			String label = "Truck " + (int) dist + "m";
			int y = 4 + 154 > w / 2 - 92 ? h / 2 - 40 : 44;
			int tw = font.width(label) + 20;
			int px;
			Ui.Icon icon;
			if (Math.abs(rel) <= 50) {
				px = w / 2 + (int) (rel / 50.0 * (w / 2 - 70)) - tw / 2;
				icon = Ui.Icon.DOWN;
			} else if (rel < 0) {
				px = 160;
				icon = Ui.Icon.LEFT;
			} else {
				px = w - tw - 8;
				icon = Ui.Icon.RIGHT;
			}
			Ui.panel(g, px, y, tw, 16);
			Ui.icon(g, icon, px + 4, y + 4);
			Ui.text(g, label, px + 15, y + 4, Ui.mix(Ui.GOLD, Ui.TEXT, Ui.pulse(1200)));
		}
	}
}
