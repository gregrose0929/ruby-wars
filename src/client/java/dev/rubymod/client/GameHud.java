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
		drawEconomy(g, font);
		drawAmmo(g, font, player, w, h);
		drawReload(g, w, h);
		drawHitMarker(g, w, h);
		drawDamageDirection(g, player, w, h);
		drawRadar(g, font, w);
		drawKillFeed(g, font, w);
		drawHitConfirm(g, font, w, h);
		long combat = ClientState.combatUntil - System.currentTimeMillis();
		if (combat > 0) {
			String text = "IN COMBAT " + (combat / 1000 + 1) + "s";
			int tw = font.width(text) + 10;
			panel(g, w / 2 - tw / 2, h - 72, w / 2 + tw / 2, h - 60);
			g.centeredText(font, text, w / 2, h - 70, RED);
		}

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
		panel(g, w / 2 - width / 2, h / 2 + 50, w / 2 + width / 2, h / 2 + 63);
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
		panel(g, w / 2 - width / 2, 2, w / 2 + width / 2, 15);
		g.centeredText(font, s, w / 2, 5, color);
	}

	private void drawHeart(GuiGraphicsExtractor g, Font font) {
		if (ClientState.hud == null || ClientState.phase() == Phase.LOBBY) {
			return;
		}
		int hp = ClientState.hud.heartHp();
		int max = Math.max(1, ClientState.hud.heartMaxHp());
		String label = hp < 0 ? (ClientState.phase().isBattle() ? "Heart: DESTROYED" : "Heart: not placed") : "Heart " + hp + "/" + max;
		panel(g, 4, 4, 96, 22);
		g.text(font, label, 8, 6, hp < 0 ? RED : WHITE);
		if (hp >= 0) {
			int barWidth = (int) (84.0 * hp / max);
			g.fill(8, 16, 92, 19, 0xFF400000);
			g.fill(8, 16, 8 + barWidth, 19, RED);
		}
	}

	/** Bank and power, under the Heart panel, once you have either. */
	private void drawEconomy(GuiGraphicsExtractor g, Font font) {
		var e = ClientState.economy;
		if (e == null || e.bank() == 0 && e.charge() == 0 && e.produced() == 0 && e.capacity() <= dev.rubymod.industry.Grid.BASE_CAPACITY) {
			return;
		}
		int y = ClientState.hud != null && ClientState.phase() != Phase.LOBBY ? 24 : 4;
		panel(g, 4, y, 96, y + 30);
		g.item(new ItemStack(dev.rubymod.registry.ModItems.RUBY), 6, y + 1);
		g.text(font, String.format(java.util.Locale.ROOT, "%,d", e.bank()), 24, y + 5, 0xFFFF5A82);
		long cap = Math.max(1, e.capacity());
		int fill = (int) (84 * Math.min(1.0, e.charge() / (double) cap));
		g.fill(8, y + 20, 92, y + 26, 0xFF2A2410);
		g.fillGradient(8, y + 20, 8 + fill, y + 26, 0xFFFFE066, 0xFFE09A1A);
		long net = e.produced() - e.consumed();
		String rate = (net >= 0 ? "+" : "") + net + "/s";
		g.text(font, rate, 92 - font.width(rate), y + 10, net >= 0 ? 0xFF5BE37D : RED);
	}

	/**
	 * The ammo panel (bottom right): gun name and fire mode, a big round count (amber when low,
	 * flashing red when empty), reserve ammo, a row of rounds, and fitted attachments. Launchers
	 * show their rockets or missiles. A reload prompt appears under the crosshair when you're low.
	 */
	private void drawAmmo(GuiGraphicsExtractor g, Font font, Player player, int w, int h) {
		ItemStack held = player.getMainHandItem();
		if (player.getVehicle() instanceof dev.rubymod.entity.AircraftEntity) {
			return;
		}
		int ammo;
		int magazine;
		int reserve;
		String mode;
		java.util.List<String> attachments = java.util.List.of();
		if (held.getItem() instanceof GunItem gun) {
			GunStats stats = gun.stats(held);
			ammo = GunItem.ammo(held);
			magazine = stats.magazine();
			reserve = player.getInventory().countItem(stats.ammo().item());
			mode = stats.automatic() ? "AUTO" : stats.pellets() > 1 ? "PUMP" : stats.scoped() ? "BOLT" : "SEMI";
			attachments = held.getOrDefault(dev.rubymod.registry.ModComponents.ATTACHMENTS, java.util.List.<String>of());
		} else if (held.getItem() instanceof dev.rubymod.item.RocketLauncherItem || held.getItem() instanceof dev.rubymod.item.LockOnLauncherItem) {
			var rocket = held.getItem() instanceof dev.rubymod.item.RocketLauncherItem ? dev.rubymod.registry.ModItems.ROCKET
					: held.is(dev.rubymod.registry.ModItems.SKYHAMMER) ? dev.rubymod.registry.ModItems.AA_MISSILE : dev.rubymod.registry.ModItems.AT_MISSILE;
			boolean loaded = player.getCooldowns().getCooldownPercent(held, 0.0F) <= 0.0F;
			ammo = loaded ? 1 : 0;
			magazine = 1;
			reserve = player.getInventory().countItem(rocket);
			mode = held.getItem() instanceof dev.rubymod.item.LockOnLauncherItem ? "LOCK-ON" : "SINGLE";
		} else {
			return;
		}
		boolean creative = player.isCreative();
		int pw = 124;
		int ph = 44;
		int x = w - pw - 4;
		int y = h - ph - 4;
		g.fillGradient(x, y, x + pw, y + ph, 0xC0181C22, 0xC00E1013);
		g.fill(x, y, x + 2, y + ph, 0xFFD0154A);
		String name = font.plainSubstrByWidth(held.getHoverName().getString().toUpperCase(java.util.Locale.ROOT), pw - 16 - font.width(mode));
		g.text(font, name, x + 7, y + 4, 0xFF8B93A1, false);
		g.text(font, mode, x + pw - 5 - font.width(mode), y + 4, 0xFF5A6370, false);
		boolean low = ammo == 0 || magazine > 1 && ammo <= magazine / 4;
		boolean blink = (System.currentTimeMillis() / 300) % 2 == 0;
		int color = ammo == 0 ? (blink ? RED : 0xFF7A1F2A) : low ? GOLD : WHITE;
		String count = String.valueOf(ammo);
		g.pose().pushMatrix();
		g.pose().translate(x + 7, y + 15);
		g.pose().scale(2.0F, 2.0F);
		g.text(font, count, 0, 0, color, true);
		g.pose().popMatrix();
		int rx = x + 9 + font.width(count) * 2;
		g.text(font, "/ " + (creative ? "∞" : reserve), rx, y + 22, 0xFF8B93A1, false);
		// Rounds left as pips (one per round up to 30, scaled for bigger mags).
		int pips = Math.min(30, magazine);
		int filled = Math.round(pips * (ammo / (float) Math.max(1, magazine)));
		int px = x + 7;
		for (int i = 0; i < pips; i++) {
			int pxx = px + i * 4;
			if (pxx > x + pw - 6) {
				break;
			}
			g.fill(pxx, y + 36, pxx + 2, y + 40, i < filled ? (low ? GOLD : 0xFFDDE2E8) : 0xFF2A2F36);
		}
		// Attachments
		int ax = x + pw - 6;
		for (String a : attachments) {
			var item = switch (a) {
				case "EXTENDED_MAG" -> dev.rubymod.registry.ModItems.EXTENDED_MAG;
				case "RED_DOT" -> dev.rubymod.registry.ModItems.RED_DOT_SIGHT;
				case "GRIP" -> dev.rubymod.registry.ModItems.VERTICAL_GRIP;
				default -> dev.rubymod.registry.ModItems.SUPPRESSOR;
			};
			ax -= 10;
			g.pose().pushMatrix();
			g.pose().translate(ax, y + 15);
			g.pose().scale(0.6F, 0.6F);
			g.item(new ItemStack(item), 0, 0);
			g.pose().popMatrix();
		}
		// Prompt under the crosshair.
		if (ClientState.reloadTicks <= 0 && low && magazine > 1) {
			String prompt = !creative && reserve == 0 ? "NO AMMO" : "RELOAD [" + RubyModClient.reloadKey.getTranslatedKeyMessage().getString() + "]";
			g.centeredText(font, prompt, w / 2, h / 2 + 22, !creative && reserve == 0 ? RED : GOLD);
		}
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
		int y = dev.rubymod.client.map.Minimap.SIZE + 26;
		panel(g, x - 4, y - 4, w - 4, y + 34);
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

	private static final java.util.Map<String, ItemStack> WEAPON_ICONS = new java.util.HashMap<>();

	/** The item whose name matches a kill feed weapon ("AR-R Rifle"), for its icon; empty if none. */
	private static ItemStack weaponIcon(String weapon) {
		return WEAPON_ICONS.computeIfAbsent(weapon, name -> {
			for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				ItemStack stack = new ItemStack(item);
				if (stack.getHoverName().getString().equals(name)) {
					return stack;
				}
			}
			return ItemStack.EMPTY;
		});
	}

	/** Kill feed (top right, under the minimap): killer, the weapon's icon, victim; headshots in gold. */
	private void drawKillFeed(GuiGraphicsExtractor g, Font font, int w) {
		long now = System.currentTimeMillis();
		ClientState.KILLS.removeIf(k -> now - k.at() > 7000);
		int y = dev.rubymod.client.map.Minimap.SIZE + 66;
		String me = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getName().getString();
		for (ClientState.Kill kill : ClientState.KILLS) {
			var info = kill.info();
			ItemStack icon = weaponIcon(info.weapon());
			String middle = icon.isEmpty() ? "  [" + info.weapon() + "]  " : "        ";
			String tag = info.headshot() ? " HS" : "";
			int width = font.width(info.killer()) + font.width(middle) + font.width(tag) + font.width(info.victim());
			int x = w - width - 10;
			panel(g, x - 3, y - 2, w - 4, y + 10);
			int killerColor = info.killer().equals(me) ? GOLD : 0xFFFF7A7A;
			int victimColor = info.victim().equals(me) ? GOLD : 0xFF9CC9FF;
			g.text(font, info.killer(), x, y, killerColor);
			int mx = x + font.width(info.killer());
			if (!icon.isEmpty()) {
				g.pose().pushMatrix();
				g.pose().translate(mx + 8, y - 2);
				g.pose().scale(0.75F, 0.75F);
				g.item(icon, 0, 0);
				g.pose().popMatrix();
			} else {
				g.text(font, middle, mx, y, GRAY);
			}
			mx += font.width(middle);
			if (!tag.isEmpty()) {
				g.text(font, tag, mx - 6, y, GOLD);
				mx += font.width(tag);
			}
			g.text(font, info.victim(), mx, y, victimColor);
			y += 13;
		}
	}

	/** The HUD's panel style: a dark gradient with a ruby accent on the left. */
	private static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		g.fillGradient(x0, y0, x1, y1, 0xC0181C22, 0xC00E1013);
		g.fill(x0, y0, x0 + 2, y1, 0xFFD0154A);
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
