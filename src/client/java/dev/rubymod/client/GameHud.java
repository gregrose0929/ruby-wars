package dev.rubymod.client;

import dev.rubymod.game.Phase;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Everything Ruby Wars draws on screen: the phase banner and timer, Heart HP,
 * ammo counter, reload bar, hit markers, and the radar panel.
 */
public class GameHud implements HudElement {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int RED = 0xFFFF4040;
	private static final int GOLD = 0xFFFFC040;
	private static final int PANEL = 0x90000000;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();

		drawPhaseBanner(g, font, w);
		drawHeart(g, font);
		drawAmmo(g, font, player, w, h);
		drawReload(g, w, h);
		drawHitMarker(g, w, h);
		drawDamageDirection(g, player, w, h);
		drawRadar(g, font, w);
		drawKillFeed(g, font, w);
		drawHitConfirm(g, font, w, h);

		if (ClientState.hud != null && ClientState.hud.eliminated()) {
			g.centeredText(font, Component.literal("ELIMINATED - spectating"), w / 2, h - 60, RED);
		}
	}

	/** "HIT - Tank 40%" / "DESTROYED - Tank" under the crosshair after a warhead lands. */
	private void drawHitConfirm(GuiGraphicsExtractor g, Font font, int w, int h) {
		var hit = ClientState.hitConfirm;
		if (hit == null || System.currentTimeMillis() - ClientState.hitConfirmAt > 2500) {
			return;
		}
		String text = hit.destroyed() ? "DESTROYED - " + hit.target() : "HIT - " + hit.target() + "  " + hit.percentLeft() + "%";
		int col = hit.destroyed() ? GOLD : RED;
		int width = font.width(text) + 10;
		g.fill(w / 2 - width / 2, h / 2 + 50, w / 2 + width / 2, h / 2 + 63, PANEL);
		g.centeredText(font, text, w / 2, h / 2 + 53, col);
	}

	private void drawPhaseBanner(GuiGraphicsExtractor g, Font font, int w) {
		if (ClientState.hud == null) {
			return;
		}
		Phase phase = ClientState.phase();
		StringBuilder text = new StringBuilder(phase.title().toUpperCase());
		if (phase == Phase.BUILD && ClientState.hud.day() > 0) {
			text.append("  Day ").append(ClientState.hud.day()).append(" of 14");
		}
		long seconds = ClientState.secondsLeft();
		if (seconds >= 0) {
			text.append("  ").append(formatTime(seconds));
			if (ClientState.hud.paused()) {
				text.append(" (paused)");
			}
		}
		if (phase.isBattle()) {
			text.append("  |  ").append(ClientState.hud.alive()).append(" alive");
		}
		String s = text.toString();
		int width = font.width(s) + 12;
		int color = phase.pvp() ? RED : phase.isBattle() ? GOLD : WHITE;
		g.fill(w / 2 - width / 2, 2, w / 2 + width / 2, 15, PANEL);
		g.centeredText(font, s, w / 2, 5, color);
	}

	private void drawHeart(GuiGraphicsExtractor g, Font font) {
		if (ClientState.hud == null || ClientState.phase() == Phase.LOBBY) {
			return;
		}
		int hp = ClientState.hud.heartHp();
		int max = Math.max(1, ClientState.hud.heartMaxHp());
		String label = hp < 0 ? (ClientState.phase().isBattle() ? "Heart: DESTROYED" : "Heart: not placed") : "Heart " + hp + "/" + max;
		g.fill(4, 4, 96, 22, PANEL);
		g.text(font, label, 8, 6, hp < 0 ? RED : WHITE);
		if (hp >= 0) {
			int barWidth = (int) (84.0 * hp / max);
			g.fill(8, 16, 92, 19, 0xFF400000);
			g.fill(8, 16, 8 + barWidth, 19, RED);
		}
	}

	private void drawAmmo(GuiGraphicsExtractor g, Font font, Player player, int w, int h) {
		ItemStack held = player.getMainHandItem();
		if (!(held.getItem() instanceof GunItem gun) || player.getVehicle() instanceof dev.rubymod.entity.AircraftEntity) {
			return;
		}
		GunStats stats = gun.stats(held);
		int ammo = GunItem.ammo(held);
		int reserve = player.getInventory().countItem(stats.ammo().item());
		String mag = ammo + " / " + stats.magazine();
		String res = player.isCreative() ? "inf" : String.valueOf(reserve);
		int x = w - 100;
		int y = h - 40;
		g.fill(x - 4, y - 4, w - 4, y + 22, PANEL);
		g.text(font, held.getHoverName(), x, y, GRAY);
		g.text(font, mag, x, y + 11, ammo == 0 ? RED : WHITE);
		g.text(font, "[" + res + "]", x + font.width(mag) + 6, y + 11, GRAY);
	}

	private void drawReload(GuiGraphicsExtractor g, int w, int h) {
		if (ClientState.reloadTicks <= 0) {
			return;
		}
		long totalMs = ClientState.reloadTicks * 50L;
		long elapsed = System.currentTimeMillis() - ClientState.reloadStartedAt;
		if (elapsed > totalMs + 500) {
			ClientState.reloadTicks = 0;
			return;
		}
		float progress = Math.min(1F, (float) elapsed / totalMs);
		int barW = 60;
		int x = w / 2 - barW / 2;
		int y = h / 2 + 12;
		g.fill(x, y, x + barW, y + 3, 0xA0000000);
		g.fill(x, y, x + (int) (barW * progress), y + 3, GOLD);
	}

	private void drawHitMarker(GuiGraphicsExtractor g, int w, int h) {
		long age = System.currentTimeMillis() - ClientState.hitMarkerAt;
		if (age > 250) {
			return;
		}
		int color = ClientState.hitKill ? RED : ClientState.hitHeadshot ? GOLD : WHITE;
		int cx = w / 2;
		int cy = h / 2;
		for (int i = 3; i <= 6; i++) {
			g.fill(cx - i, cy - i, cx - i + 1, cy - i + 1, color);
			g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color);
			g.fill(cx - i, cy + i, cx - i + 1, cy + i + 1, color);
			g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
		}
	}

	/** A red marker on a ring around the crosshair, pointing toward whatever just hurt you. */
	private void drawDamageDirection(GuiGraphicsExtractor g, Player player, int w, int h) {
		long age = System.currentTimeMillis() - ClientState.damagedAt;
		if (age > 1500) {
			return;
		}
		double dx = ClientState.damageX - player.getX();
		double dz = ClientState.damageZ - player.getZ();
		if (dx * dx + dz * dz < 1.0) {
			return;
		}
		// Angle of the source relative to where the player is facing (0 = straight ahead).
		double worldAngle = Math.toDegrees(Math.atan2(-dx, dz));
		double relative = Math.toRadians(worldAngle - player.getYRot());
		int radius = 40;
		int cx = w / 2 + (int) Math.round(Math.sin(relative) * radius);
		int cy = h / 2 - (int) Math.round(Math.cos(relative) * radius);
		int alpha = (int) (255 * (1.0 - age / 1500.0));
		int color = (alpha << 24) | 0xFF3030;
		g.fill(cx - 3, cy - 3, cx + 3, cy + 3, color);
		g.fill(cx - 1, cy - 5, cx + 1, cy + 5, color);
		g.fill(cx - 5, cy - 1, cx + 5, cy + 1, color);
	}

	private void drawRadar(GuiGraphicsExtractor g, Font font, int w) {
		if (ClientState.radar == null || System.currentTimeMillis() - ClientState.radarReceivedAt > 3000) {
			return;
		}
		var radar = ClientState.radar;
		int x = w - 110;
		int y = 60;
		g.fill(x - 4, y - 4, w - 4, y + 34, PANEL);
		g.text(font, "RADAR", x, y, GOLD);
		String contacts = radar.contacts() == 0 ? "No contacts" : radar.contacts() + " contact" + (radar.contacts() == 1 ? "" : "s");
		g.text(font, contacts, x, y + 10, radar.contacts() > 0 ? RED : GRAY);
		if (radar.nearest() >= 0) {
			g.text(font, "Nearest " + radar.nearest() + "m " + compass(radar.bearing()), x, y + 20, WHITE);
		}
		boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
		if (radar.missileAlert() && blink) {
			g.centeredText(font, Component.literal("!! INCOMING MISSILE !!"), w / 2, 40, RED);
		}
		if (radar.intruder() && blink) {
			g.centeredText(font, Component.literal("INTRUDER IN YOUR BASE"), w / 2, 52, GOLD);
		}
	}

	private void drawKillFeed(GuiGraphicsExtractor g, Font font, int w) {
		long now = System.currentTimeMillis();
		ClientState.KILLS.removeIf(k -> now - k.at() > 7000);
		int y = 104;
		for (ClientState.Kill kill : ClientState.KILLS) {
			var info = kill.info();
			String text = info.killer() + "  [" + info.weapon() + (info.headshot() ? " - HEADSHOT" : "") + "]  " + info.victim();
			int width = font.width(text);
			g.fill(w - width - 10, y - 2, w - 4, y + 9, PANEL);
			g.text(font, text, w - width - 7, y, info.headshot() ? GOLD : WHITE);
			y += 12;
		}
	}

	private static String compass(int bearing) {
		String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		return names[Math.floorMod(Math.round(bearing / 45.0F), 8)];
	}

	private static String formatTime(long seconds) {
		long d = seconds / 86400;
		long hrs = (seconds % 86400) / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		if (d > 0) {
			return d + "d " + hrs + "h";
		}
		if (hrs > 0) {
			return hrs + "h " + m + "m";
		}
		return String.format("%d:%02d", m, s);
	}
}
