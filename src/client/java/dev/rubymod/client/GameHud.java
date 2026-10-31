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
	private static final int WHITE = 0xFFF2EEE8;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int RED = 0xFFE5484D;
	private static final int GOLD = 0xFFF5C542;
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

		drawLowHealth(g, player, w, h);
		drawPhaseBanner(g, font, w);
		drawHeart(g, font);
		drawEconomy(g, font);
		drawGuide(g, font);
		drawAmmo(g, font, player, w, h);
		drawReload(g, w, h);
		drawHitMarker(g, w, h);
		drawDamageDirection(g, player, w, h);
		drawRadar(g, font, w);
		drawKillFeed(g, font, w);
		drawHitConfirm(g, font, w, h);
		drawDeathCard(g, font, w, h);
		var raid = ClientState.raid;
		if (raid != null && raid.raiders() > 0 && System.currentTimeMillis() - ClientState.raidAt < 2500) {
			String text = "RAID  " + raid.raiders() + " raider" + (raid.raiders() == 1 ? "" : "s") + " left  " + formatTime(raid.seconds())
					+ (raid.looted() > 0 ? "  -" + raid.looted() + " rubies" : "");
			int tw = font.width(text) + 14;
			panel(g, w / 2 - tw / 2, 18, w / 2 + tw / 2, 31);
			boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
			g.centeredText(font, text, w / 2, 21, blink ? RED : 0xFFFF8080);
		}
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

	/** "Killed by Sam with the AR-R Rifle from 34m" (or what got you, if nobody did). */
	public static String deathLine(dev.rubymod.net.MorePayloads.DeathInfo info) {
		if (info.killer().isEmpty()) {
			return info.weapon();
		}
		return "Killed by " + info.killer() + (info.weapon().isEmpty() ? "" : " with " + info.weapon())
				+ (info.distance() > 0 ? " from " + info.distance() + "m" : "") + (info.headshot() ? " (headshot)" : "");
	}

	/** The death card when there's no death screen (duels respawn you without one). */
	private void drawDeathCard(GuiGraphicsExtractor g, Font font, int w, int h) {
		var info = ClientState.deathInfo;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (info == null || mc.gui.screen() != null || System.currentTimeMillis() - ClientState.deathInfoAt > 3500) {
			return;
		}
		String line = deathLine(info);
		int width = font.width(line) + 16;
		panel(g, w / 2 - width / 2, 34, w / 2 + width / 2, 49);
		g.centeredText(font, line, w / 2, 38, RED);
		if (Killcam.active()) {
			g.centeredText(font, "KILLCAM", w / 2, 52, GOLD);
		}
	}

	/** "HIT - Tank 40%" / "DESTROYED - Tank" under the crosshair after a warhead lands. */
	private void drawHitConfirm(GuiGraphicsExtractor g, Font font, int w, int h) {
		var hit = ClientState.hitConfirm;
		// (The Rift shows damage numbers instead.)
		if (hit == null || System.currentTimeMillis() - ClientState.hitConfirmAt > 2500 || dev.rubymod.client.rift.RiftClient.active()) {
			return;
		}
		String text = hit.destroyed() ? (hit.percentLeft() < 0 ? "KILLED - " : "DESTROYED - ") + hit.target()
				: "HIT - " + hit.target() + "  " + hit.percentLeft() + "%";
		int col = hit.destroyed() ? GOLD : RED;
		int width = font.width(text) + 10;
		panel(g, w / 2 - width / 2, h / 2 + 50, w / 2 + width / 2, h / 2 + 63);
		g.centeredText(font, text, w / 2, h / 2 + 53, col);
	}

	private void drawPhaseBanner(GuiGraphicsExtractor g, Font font, int w) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		// In a duel the score bar sits up there instead.
		if (ClientState.hud == null || mc.level != null && mc.level.dimension() == dev.rubymod.game.Duels.ARENA) {
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
		if (ClientState.hud == null || ClientState.phase() == Phase.LOBBY || !ClientState.inRubyWarsWorld()) {
			return;
		}
		int hp = ClientState.hud.heartHp();
		int max = Math.max(1, ClientState.hud.heartMaxHp());
		String label = hp < 0 ? (ClientState.phase().isBattle() ? "Heart destroyed" : "Heart not placed") : "Heart " + hp + " / " + max;
		panel(g, 4, 4, 100, 24);
		dev.rubymod.client.ui.Ui.icon(g, dev.rubymod.client.ui.Ui.Icon.HEART, 8, 7);
		g.text(font, label, 20, 8, hp < 0 ? RED : WHITE, false);
		if (hp >= 0) {
			dev.rubymod.client.ui.Ui.bar(g, 8, 17, 88, 4, dev.rubymod.client.ui.Ui.glide("heart", hp / (float) max), RED);
		}
	}

	/** Bank and power, under the Heart panel, once you have either. */
	private void drawEconomy(GuiGraphicsExtractor g, Font font) {
		var e = ClientState.economy;
		// Only in the Ruby Wars world (the hub and the other modes don't need it).
		if (e == null || !ClientState.inRubyWarsWorld()
				|| e.bank() == 0 && e.charge() == 0 && e.produced() == 0 && e.capacity() <= dev.rubymod.industry.Grid.BASE_CAPACITY) {
			return;
		}
		int y = ClientState.hud != null && ClientState.phase() != Phase.LOBBY && ClientState.inRubyWarsWorld() ? 24 : 4;
		panel(g, 4, y, 100, y + 30);
		g.item(new ItemStack(dev.rubymod.registry.ModItems.RUBY), 7, y + 3);
		g.text(font, String.format(java.util.Locale.ROOT, "%,d", e.bank()), 26, y + 7, 0xFFFF6A84, false);
		long cap = Math.max(1, e.capacity());
		dev.rubymod.client.ui.Ui.icon(g, dev.rubymod.client.ui.Ui.Icon.BOLT, 7, y + 18);
		dev.rubymod.client.ui.Ui.bar(g, 18, y + 20, 78, 5, (float) Math.min(1.0, e.charge() / (double) cap), 0xFFF5C542);
		long net = e.produced() - e.consumed();
		String rate = (net >= 0 ? "+" : "") + net + "/s";
		g.text(font, rate, 96 - font.width(rate), y + 7, net >= 0 ? 0xFF5BD27A : RED, false);
	}

	/**
	 * The ammo panel (bottom right): gun name and fire mode, a big round count (amber when low,
	 * flashing red when empty), reserve ammo, a row of rounds, and fitted attachments. Launchers
	 * show their rockets or missiles. A reload prompt appears under the crosshair when you're low.
	 */
	/** Badly hurt (under 40% health): the screen edges bleed red, pulsing faster as you get closer to death. */
	private void drawLowHealth(GuiGraphicsExtractor g, Player player, int w, int h) {
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		float health = player.getHealth() / Math.max(1.0F, player.getMaxHealth());
		if (health >= 0.4F || player.isDeadOrDying()) {
			return;
		}
		float severity = 1.0F - health / 0.4F;
		double pulse = 0.75 + 0.25 * Math.sin(System.currentTimeMillis() / (220.0 - 120.0 * severity));
		int alpha = (int) (Math.min(1.0F, 0.45F + severity * 0.55F) * pulse * 235);
		int edge = (alpha << 24) | 0x8A0000;
		int clear = 0x008A0000;
		int band = Math.max(30, Math.min(w, h) / 3);
		g.fillGradient(0, 0, w, band, edge, clear);
		g.fillGradient(0, h - band, w, h, clear, edge);
		// Sides: thin vertical bars stepping inward, since gradients only run top to bottom.
		for (int i = 0; i < band; i += 2) {
			double f = 1.0 - i / (double) band;
			int a = (int) (alpha * f * f);
			int c = (a << 24) | 0x8A0000;
			g.fill(i, 0, i + 2, h, c);
			g.fill(w - i - 2, 0, w - i, h, c);
		}
	}

	/**
	 * The guide's next step (see Guide) and today's bounties, in one panel under the Heart and
	 * bank panels, while building in the Ruby Wars world.
	 */
	private void drawGuide(GuiGraphicsExtractor g, Font font) {
		Phase phase = ClientState.phase();
		if (!ClientState.inRubyWarsWorld() || phase != Phase.LOBBY && phase != Phase.BUILD) {
			return;
		}
		int step = ClientState.research == null ? -1 : Guide.current();
		java.util.List<String> bounties = phase == Phase.BUILD ? ClientState.bounties : java.util.List.of();
		if (step < 0 && bounties.isEmpty()) {
			return;
		}
		int y = ClientState.hud != null && phase != Phase.LOBBY ? 24 : 4;
		var e = ClientState.economy;
		if (!(e == null || e.bank() == 0 && e.charge() == 0 && e.produced() == 0 && e.capacity() <= dev.rubymod.industry.Grid.BASE_CAPACITY)) {
			y += 34;
		}
		Guide.Step s = step < 0 ? null : Guide.STEPS.get(step);
		int[] progress = s == null ? null : Guide.progress(s);
		var hint = s == null ? java.util.List.<net.minecraft.util.FormattedCharSequence>of() : font.split(Component.literal(s.hint()), 180);
		int lines = Math.min(3, hint.size());
		boolean bar = progress != null && progress[1] > 1;
		int height = (s == null ? 0 : 26 + lines * 9 + (bar ? 5 : 0)) + (bounties.isEmpty() ? 0 : 13 + bounties.size() * 13) + (s == null ? 2 : 11);
		panel(g, 4, y, 190, y + height + 2);
		g.fill(6, y + 2, 8, y + height, GOLD);
		int ly = y + 4;
		if (s != null) {
			g.text(font, "Next step, " + (step + 1) + " of " + Guide.STEPS.stream().filter(st -> !st.optional()).count(), 12, ly, GOLD, false);
			String count = bar ? progress[0] + "/" + progress[1] : "";
			g.text(font, font.plainSubstrByWidth(s.title(), 172 - font.width(count)), 12, ly + 11, WHITE, false);
			if (bar) {
				g.text(font, count, 186 - font.width(count), ly + 11, GOLD, false);
			}
			ly += 23;
			for (int i = 0; i < lines; i++) {
				g.text(font, hint.get(i), 12, ly, 0xFFAAB0BA, false);
				ly += 9;
			}
			if (bar) {
				dev.rubymod.client.ui.Ui.bar(g, 12, ly + 1, 174, 4, Math.min(1.0F, progress[0] / (float) progress[1]), GOLD);
				ly += 6;
			}
		}
		// Today's bounties: small extra jobs for rubies.
		if (!bounties.isEmpty()) {
			if (s != null) {
				g.fill(10, ly + 2, 186, ly + 3, 0x40FFFFFF);
				ly += 5;
			}
			g.text(font, "Today's bounties", 12, ly, GOLD, false);
			ly += 11;
			for (String line : bounties) {
				String[] p = line.split("[|]");
				if (p.length < 5) {
					continue;
				}
				boolean done = Boolean.parseBoolean(p[4]);
				int got = Integer.parseInt(p[1]);
				int target = Math.max(1, Integer.parseInt(p[2]));
				String what = p[0].replace(" on monsters", "").replace(" of any kind", "").replace(" or deepslate", "");
				String reward = "+" + p[3];
				g.text(font, font.plainSubstrByWidth(what + "  " + got + "/" + target, 172 - font.width(reward)), 10, ly, done ? 0xFF5BE37D : WHITE, false);
				g.text(font, reward, 186 - font.width(reward), ly, done ? 0xFF5BE37D : 0xFFFF5A82, false);
				g.fill(10, ly + 9, 186, ly + 10, 0xFF2A2F35);
				g.fill(10, ly + 9, 10 + Math.round(176 * Math.min(1.0F, got / (float) target)), ly + 10, done ? 0xFF5BE37D : GOLD);
				ly += 13;
			}
		}
		if (s != null) {
			g.text(font, "J for the full guide", 12, ly + 1, 0xFF6E7480, false);
		}
	}

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
		boolean creative = player.isCreative() || dev.rubymod.client.rift.RiftClient.active();
		int pw = 124;
		int ph = 44;
		int x = w - pw - 4;
		int y = h - ph - 4;
		panel(g, x, y, x + pw, y + ph);
		String name = font.plainSubstrByWidth(held.getHoverName().getString(), pw - 18 - font.width(mode));
		g.text(font, name, x + 7, y + 5, 0xFFAAB0BA, false);
		g.text(font, mode, x + pw - 7 - font.width(mode), y + 5, 0xFF6E7480, false);
		boolean low = ammo == 0 || magazine > 1 && ammo <= magazine / 4;
		boolean blink = (System.currentTimeMillis() / 300) % 2 == 0;
		int color = ammo == 0 ? (blink ? RED : 0xFF7A1F2A) : low ? GOLD : WHITE;
		String count = String.valueOf(ammo);
		g.pose().pushMatrix();
		g.pose().translate(x + 7, y + 15);
		g.pose().scale(2.0F, 2.0F);
		g.text(font, count, 1, 1, 0xFF0A0B0F, false);
		g.text(font, count, 0, 0, color, false);
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
			g.fill(pxx, y + 35, pxx + 2, y + 39, i < filled ? (low ? GOLD : 0xFFF2EEE8) : 0xFF2A2F36);
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
			String prompt = !creative && reserve == 0 ? "No ammo" : "Reload (" + RubyModClient.reloadKey.getTranslatedKeyMessage().getString() + ")";
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

	/** The HUD's panels: the mod's stone panel (see Ui). */
	private static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		dev.rubymod.client.ui.Ui.panel(g, x0, y0, x1 - x0, y1 - y0);
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
