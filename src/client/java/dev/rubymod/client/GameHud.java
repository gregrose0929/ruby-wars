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
		HudScale.push(g);
		try {
			draw(g, deltaTracker);
		} finally {
			HudScale.pop(g);
		}
	}

	/** Drawn at the HUD's size (see HudScale). */
	private void draw(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null) {
			return;
		}
		Font font = mc.font;
		int w = HudScale.width(g);
		int h = HudScale.height(g);

		drawLowHealth(g, player, w, h);
		// A menu open (the Armory, a machine...): the HUD panels step aside so nothing pokes out around it.
		if (menuOpen()) {
			return;
		}
		// The top centre stacks downward (phase, respawns, raid); the top-left column steps under it if they'd meet.
		topBottom = 0;
		topLeft = w;
		drawPhaseBanner(g, font, w);
		drawRespawnStatus(g, font, w);
		var plan = ClientState.borderPlan;
		if (plan != null && plan.show() && ClientState.inRubyWarsWorld() && !player.isSpectator()) {
			double half = plan.size() / 2.0;
			double dx = Math.max(Math.abs(player.getX() - plan.x()) - half, 0) * Math.signum(plan.x() - player.getX());
			double dz = Math.max(Math.abs(player.getZ() - plan.z()) - half, 0) * Math.signum(plan.z() - player.getZ());
			if (dx != 0 || dz != 0) {
				int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
				String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
				String way = names[Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(dz, dx)) / 45.0F), 8)];
				topBanner(g, font, "Outside the final circle: " + dist + " m " + way + " (your map)", w, 0xFFFFB040);
			}
		}
		var event = ClientState.event;
		if (event != null && !event.text().isEmpty() && System.currentTimeMillis() - ClientState.eventAt < 2500) {
			// A line or two, each kept clear of the minimap.
			for (String line : event.text().split("\n")) {
				topBanner(g, font, dev.rubymod.client.ui.Ui.fit(line, w - 190), w, event.color());
			}
		}
		drawCorner(g, font, w);
		drawAmmo(g, font, player, w, h);
		drawReload(g, w, h);
		drawHitMarker(g, w, h);
		drawDamageDirection(g, player, w, h);
		drawRadar(g, font, w);
		if (ClientSettings.killFeed) {
			drawKillFeed(g, font, w);
		}
		drawHitConfirm(g, font, w, h);
		drawDeathCard(g, font, w, h);
		long combat = ClientState.combatUntil - System.currentTimeMillis();
		if (combat > 0) {
			banner(g, font, "IN COMBAT " + (combat / 1000 + 1) + "s", w / 2, h - 76, w, RED);
		}

		// Only in the Ruby Wars world, during the battle, while watching: not in the hub or another game, and not after the season.
		if (ClientState.hud != null && ClientState.hud.eliminated() && ClientState.inRubyWarsWorld() && ClientState.phase().isBattle() && player.isSpectator()) {
			String a = "KNOCKED OUT - watching the battle";
			String b = "Click: next player   Right-click: back   Crouch: fly free   H: party games in the hub";
			int tw = Math.min(w - 8, Math.max(font.width(a), font.width(b)) + 16);
			panel(g, w / 2 - tw / 2, h - 92, w / 2 + tw / 2, h - 62);
			g.centeredText(font, Component.literal(dev.rubymod.client.ui.Ui.fit(a, tw - 16)), w / 2, h - 87, RED);
			g.centeredText(font, Component.literal(dev.rubymod.client.ui.Ui.fit(b, tw - 16)), w / 2, h - 76, 0xFFCCCCCC);
		}
	}

	/** Bottom of the top-centre stack this frame (phase banner, respawns, raid), and its left edge. */
	private static int topBottom;
	private static int topLeft;

	/** Where the next thing at the top centre can go (the look-at panel uses it too). */
	public static int topCentreBottom() {
		return topBottom;
	}

	/** One line in a panel, centred on {@code cx}: 16 high, the text 4 down and 8 in, cut to fit the screen. Returns its left edge. */
	private static int banner(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int w, int color) {
		String shown = dev.rubymod.client.ui.Ui.fit(text, w - 24);
		int tw = font.width(shown) + 16;
		int x = cx - tw / 2;
		panel(g, x, y, x + tw, y + 16);
		g.text(font, shown, x + 8, y + 4, color, true);
		return x;
	}

	/** A banner under whatever's already at the top centre. */
	private static void topBanner(GuiGraphicsExtractor g, Font font, String text, int w, int color) {
		int y = topBottom == 0 ? 2 : topBottom + 2;
		topLeft = Math.min(topLeft, banner(g, font, text, w / 2, y, w, color));
		topBottom = y + 16;
	}

	/** A screen is open other than chat (which leaves the HUD showing). */
	public static boolean menuOpen() {
		var screen = Minecraft.getInstance().gui.screen();
		return screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen);
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
		int y = Math.max(34, topBottom + 4);
		banner(g, font, deathLine(info), w / 2, y, w, RED);
		if (Killcam.active()) {
			g.centeredText(font, "KILLCAM", w / 2, y + 19, GOLD);
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
		banner(g, font, text, w / 2, h / 2 + 48, w, hit.destroyed() ? GOLD : RED);
	}

	private void drawPhaseBanner(GuiGraphicsExtractor g, Font font, int w) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		// Only in the Ruby Wars world: everywhere else a game's own bar (or a duel's score) sits up there.
		if (ClientState.hud == null || !ClientState.inRubyWarsWorld() || mc.level != null && mc.level.dimension() == dev.rubymod.game.Duels.ARENA) {
			return;
		}
		Phase phase = ClientState.phase();
		StringBuilder text = new StringBuilder(phase.title().toUpperCase());
		if (phase == Phase.BUILD && ClientState.hud.day() > 0) {
			text.append("  Day ").append(ClientState.hud.day());
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
		int color = phase.pvp() ? RED : phase.isBattle() ? GOLD : WHITE;
		topBanner(g, font, text.toString(), w, color);
	}

	/** Under the phase banner on Battle Day: whether dying sends you back to a Heart, or knocks you out. */
	private void drawRespawnStatus(GuiGraphicsExtractor g, Font font, int w) {
		var hud = ClientState.hud;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (hud == null || !ClientState.inRubyWarsWorld() || mc.level != null && mc.level.dimension() == dev.rubymod.game.Duels.ARENA) {
			return;
		}
		String text;
		int color;
		switch (hud.respawn()) {
			case dev.rubymod.net.Payloads.HudSync.RESPAWN_HEART -> {
				text = "Respawns: on (your Heart)";
				color = 0xFF5BD27A;
			}
			case dev.rubymod.net.Payloads.HudSync.RESPAWN_MATE -> {
				text = "Respawns: on (squadmate's Heart)";
				color = 0xFF5BD27A;
			}
			case dev.rubymod.net.Payloads.HudSync.LAST_LIFE -> {
				text = "LAST LIFE: your Heart is gone";
				color = RED;
			}
			case dev.rubymod.net.Payloads.HudSync.NO_RESPAWNS -> {
				text = "No respawns: dying knocks you out";
				color = RED;
			}
			default -> {
				return;
			}
		}
		topBanner(g, font, text, w, color);
	}

	private static String num(long n) {
		return String.format(java.util.Locale.ROOT, "%,d", n);
	}

	/**
	 * Top left, kept small: one slim panel with your Heart (a bar and its HP) over your rubies (bank, what you carry)
	 * and power (what it's making or using); then the guide's next step on one line. Nothing runs into the minimap or
	 * the banner at the top centre (on a narrow screen it starts under the banner).
	 */
	private void drawCorner(GuiGraphicsExtractor g, Font font, int w) {
		if (!ClientState.inRubyWarsWorld()) {
			return;
		}
		Phase phase = ClientState.phase();
		var hud = ClientState.hud;
		boolean heart = ClientSettings.heartPanel && hud != null && phase != Phase.LOBBY;
		var e = ClientState.economy;
		boolean bank = ClientSettings.bankPanel && e != null
				&& !(e.bank() == 0 && e.charge() == 0 && e.produced() == 0 && e.capacity() <= dev.rubymod.industry.Grid.BASE_CAPACITY);
		int maxW = Math.max(90, w - dev.rubymod.client.map.Minimap.SIZE - 16 - 4);

		int hp = heart ? hud.heartHp() : 0;
		int max = heart ? Math.max(1, hud.heartMaxHp()) : 1;
		String hpText = hp < 0 ? (phase.isBattle() ? "Heart destroyed" : "No Heart yet") : num(hp);
		int need = heart ? 22 + (hp < 0 ? 0 : 44 + 5) + font.width(hpText) + 6 : 0;
		String bankText = "";
		String carriedText = "";
		String rate = "";
		if (bank) {
			bankText = num(e.bank());
			var me = Minecraft.getInstance().player;
			int carried = me == null ? 0 : me.getInventory().countItem(dev.rubymod.registry.ModItems.RUBY);
			carriedText = carried > 0 ? "+" + num(carried) : "";
			long net = e.produced() - e.consumed();
			rate = (net >= 0 ? "+" : "") + num(net) + "/s";
			need = Math.max(need, 21 + font.width(bankText) + (carriedText.isEmpty() ? 0 : 4 + font.width(carriedText)) + 6);
			need = Math.max(need, 21 + 44 + 5 + font.width(rate) + 6);
		}
		int colW = Math.min(maxW, Math.max(104, need));
		int y = 4;
		if (topBottom > 0 && 4 + colW + 4 > topLeft) {
			y = topBottom + 3;
		}
		if (heart || bank) {
			int rows = (heart ? 1 : 0) + (bank ? 2 : 0);
			int h = 6 + rows * 11;
			panel(g, 4, y, 4 + colW, y + h);
			int right = 4 + colW - 6;
			int ry = y + 5;
			if (heart) {
				dev.rubymod.client.ui.Ui.icon(g, dev.rubymod.client.ui.Ui.Icon.HEART, 9, ry - 1);
				if (hp < 0) {
					g.text(font, dev.rubymod.client.ui.Ui.fit(hpText, right - 21), 21, ry, RED, true);
				} else {
					float frac = hp / (float) max;
					g.text(font, hpText, right - font.width(hpText), ry, frac < 0.35F ? RED : 0xFFC8CDD6, true);
					dev.rubymod.client.ui.Ui.bar(g, 21, ry + 1, right - font.width(hpText) - 5 - 21, 6,
							dev.rubymod.client.ui.Ui.glide("heart", frac), RED);
				}
				ry += 11;
			}
			if (bank) {
				g.pose().pushMatrix();
				g.pose().translate(9, ry - 1);
				g.pose().scale(0.5625F, 0.5625F);
				g.item(new ItemStack(dev.rubymod.registry.ModItems.RUBY), 0, 0);
				g.pose().popMatrix();
				g.text(font, bankText, 21, ry, 0xFFFF6A84, true);
				if (!carriedText.isEmpty()) {
					g.text(font, carriedText, 21 + font.width(bankText) + 4, ry, 0xFFB98C96, true);
				}
				ry += 11;
				// Power: how full your grid (and its Battery Banks) is, and what it's gaining (green) or losing (red).
				float full = (float) Math.min(1.0, e.charge() / (double) Math.max(1, e.capacity()));
				boolean gaining = e.produced() >= e.consumed();
				dev.rubymod.client.ui.Ui.icon(g, dev.rubymod.client.ui.Ui.Icon.BOLT, 9, ry - 1);
				g.text(font, rate, right - font.width(rate), ry, gaining ? 0xFF5BD27A : RED, true);
				dev.rubymod.client.ui.Ui.bar(g, 21, ry + 1, right - font.width(rate) - 5 - 21, 6,
						dev.rubymod.client.ui.Ui.glide("charge", full), 0xFFF5C542);
			}
			y += h + 2;
		}
		drawGuide(g, font, y, colW, maxW);
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
	 * The guide's next step (see Guide), in a panel under the Heart and
	 * bank panels, while building in the Ruby Wars world.
	 */
	private static int guideStep = -1;
	private static long guideChangedAt;

	/** The guide step showing in the corner, or -1 (not building, guide off, a how-to card in the way). */
	private static int guideShown() {
		Phase phase = ClientState.phase();
		if (phase != Phase.LOBBY && phase != Phase.BUILD || "off".equals(ClientSettings.guide)
				|| HowToCard.covers(Minecraft.getInstance().getWindow().getGuiScaledWidth())) {
			return -1;
		}
		return ClientState.research == null ? -1 : Guide.current();
	}

	/** The guide's one-line form: "Next: ..." and the step's count ("" if none). */
	private static String[] guideLine(int step) {
		Guide.Step s = Guide.steps().get(step);
		int[] progress = Guide.progress(s);
		return new String[] {"Next: " + s.title(), progress != null && progress[1] > 1 ? progress[0] + "/" + progress[1] : ""};
	}

	/** Where the Guide line was last drawn (left, top, right, bottom; all 0 when it isn't), so the look panel can keep clear. */
	public static int guideL;
	public static int guideT;
	public static int guideR;
	public static int guideB;

	private void drawGuide(GuiGraphicsExtractor g, Font font, int y, int colW, int maxW) {
		guideL = guideT = guideR = guideB = 0;
		int step = guideShown();
		if (step < 0) {
			return;
		}
		if (step != guideStep) {
			guideStep = step;
			guideChangedAt = System.currentTimeMillis();
		}
		Guide.Step s = Guide.steps().get(step);
		int[] progress = Guide.progress(s);
		boolean counted = progress != null && progress[1] > 1;
		String count = counted ? progress[0] + "/" + progress[1] : "";
		// One line unless Settings asks for the full card (J has the whole guide); a new step glows gold for a moment.
		boolean open = "full".equals(ClientSettings.guide);
		float fresh = Math.max(0.0F, 1.0F - (System.currentTimeMillis() - guideChangedAt) / 4000.0F);
		// A gold stripe down the left (inside the frame), the text 8 past it.
		int x = 4;
		int tx = x + 9;
		if (!open) {
			// One line: the next step, and its count if it has one.
			String text = guideLine(step)[0];
			int pw = Math.min(maxW, Math.max(colW, 9 + font.width(text) + (counted ? 8 + font.width(count) : 0) + 7));
			int right = x + pw - 7;
			panel(g, x, y, x + pw, y + 17);
			guideL = x;
			guideT = y;
			guideR = x + pw;
			guideB = y + 17;
			g.fill(x + 2, y + 2, x + 4, y + 15, GOLD);
			g.text(font, dev.rubymod.client.ui.Ui.fit(text, right - tx - (counted ? font.width(count) + 8 : 0)), tx, y + 5,
					dev.rubymod.client.ui.Ui.mix(WHITE, GOLD, fresh), true);
			if (counted) {
				g.text(font, count, right - font.width(count), y + 5, GOLD, true);
			}
			return;
		}
		int pw = colW;
		int right = x + pw - 7;
		int inner = right - tx;
		var hint = font.split(Component.literal(s.hint()), inner);
		int lines = Math.min(6, hint.size());
		int ly = y + 5;
		int height = 5 + 11 + 12 + lines * 10 + (counted ? 7 : 0) + 2 + 9 + 4;
		panel(g, x, y, x + pw, y + height);
		guideL = x;
		guideT = y;
		guideR = x + pw;
		guideB = y + height;
		g.fill(x + 2, y + 2, x + 4, y + height - 2, GOLD);
		g.text(font, dev.rubymod.client.ui.Ui.fit(Guide.CHAPTERS.get(Guide.chapter()).title() + ": step " + (step + 1) + " of " + Guide.steps().stream().filter(st -> !st.optional()).count(), inner),
				tx, ly, GOLD, true);
		ly += 11;
		g.text(font, dev.rubymod.client.ui.Ui.fit(s.title(), inner - (counted ? font.width(count) + 8 : 0)), tx, ly, WHITE, true);
		if (counted) {
			g.text(font, count, right - font.width(count), ly, GOLD, true);
		}
		ly += 12;
		for (int i = 0; i < lines; i++) {
			g.text(font, hint.get(i), tx, ly, 0xFFB4BAC4, false);
			ly += 10;
		}
		if (counted) {
			dev.rubymod.client.ui.Ui.bar(g, tx, ly + 1, inner, 4, Math.min(1.0F, progress[0] / (float) progress[1]), GOLD);
			ly += 7;
		}
		g.text(font, "J for the full guide", tx, ly + 2, 0xFF7A808C, false);
	}

	private void drawAmmo(GuiGraphicsExtractor g, Font font, Player player, int w, int h) {
		ItemStack held = player.getMainHandItem();
		// Aircraft and tanks have their own displays down there.
		if (player.getVehicle() instanceof dev.rubymod.entity.AircraftEntity || player.getVehicle() instanceof dev.rubymod.entity.VehicleEntity v && v.isTank()) {
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
		// On a narrow screen the corner is where the hotbar and food bar are: the panel goes above them.
		int y = x < w / 2 + 95 ? h - ph - 54 : h - ph - 4;
		panel(g, x, y, x + pw, y + ph);
		// The weapon's whole name (smaller letters if it's long) rather than "Rocket Launc".
		dev.rubymod.client.ui.Ui.shrinkText(g, held.getHoverName().getString(), x + 7, y + 5, pw - 18 - font.width(mode), 0xFFAAB0BA);
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
		int x = w - 112;
		int y = dev.rubymod.client.map.Minimap.SIZE + 27;
		panel(g, x - 7, y - 5, w - 4, y + 32);
		g.text(font, "RADAR", x, y, GOLD);
		String contacts = radar.contacts() == 0 ? "No contacts" : radar.contacts() + " contact" + (radar.contacts() == 1 ? "" : "s");
		g.text(font, contacts, x, y + 10, radar.contacts() > 0 ? RED : GRAY);
		if (radar.nearest() >= 0) {
			g.text(font, "Nearest " + radar.nearest() + "m " + compass(radar.bearing()), x, y + 20, WHITE);
		}
		boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
		int ay = Math.max(40, topBottom + 22);
		if (radar.missileAlert() && blink) {
			g.centeredText(font, Component.literal("!! INCOMING MISSILE !!"), w / 2, ay, RED);
		}
		if (radar.intruder() && blink) {
			g.centeredText(font, Component.literal("INTRUDER IN YOUR BASE"), w / 2, ay + 12, GOLD);
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
			int x = w - width - 11;
			panel(g, x - 7, y - 4, w - 4, y + 12);
			int killerColor = info.killer().equals(me) ? GOLD : 0xFFFF7A7A;
			int victimColor = info.victim().equals(me) ? GOLD : 0xFF9CC9FF;
			g.text(font, info.killer(), x, y, killerColor);
			int mx = x + font.width(info.killer());
			if (!icon.isEmpty()) {
				g.pose().pushMatrix();
				g.pose().translate(mx + 8, y - 1);
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
			y += 18;
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

	public static String formatTime(long seconds) {
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
