package dev.rubymod.client;

import java.util.ArrayDeque;

import dev.rubymod.game.Phase;
import dev.rubymod.net.Payloads;

/** What the server last told this client, for the HUD. */
public final class ClientState {
	public static Payloads.HudSync hud;
	/** The Sky Race minimap (only while racing), and when it last came. */
	public static dev.rubymod.net.MorePayloads.RaceMap race;
	public static long raceAt;

	/** In the Ruby Wars world itself (not the hub, Horde or a party game), where Hearts, claims and waypoints mean something. */
	public static boolean inRubyWarsWorld() {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		// The server says where you are (a game can hold you somewhere while its arena is built); until it has, the world you're in.
		return level != null && level.dimension() == net.minecraft.world.level.Level.OVERWORLD && (hud == null || hud.inRubyWars());
	}
	/** System time (ms) when the last HudSync arrived, to count the timer down smoothly between packets. */
	public static long hudReceivedAt;

	public static Payloads.Radar radar;
	public static long radarReceivedAt;

	/** Map markers, squadmates and claims (see Waypoints). */
	public static dev.rubymod.net.MorePayloads.MapSync map;
	/** Latest look-at answer and when it arrived. */
	public static dev.rubymod.net.MorePayloads.LookInfo look;
	public static long lookAt;
	/** When the current combat tag runs out (ms). */
	public static long combatUntil;
	/** The raid on your side, if any, and when we last heard. */
	/** Battle Day's final circle, for the map and the "outside it" warning (null or not shown: nothing). */
	public static dev.rubymod.net.MorePayloads.BorderPlan borderPlan;

	/** The world event banner (empty: none), and when it last came. */
	public static dev.rubymod.net.MorePayloads.EventStatus event;
	public static long eventAt;
	/** The kill feed entry where you were the victim (for the death screen). */
	public static dev.rubymod.net.Payloads.KillFeed lastDeath;
	public static long lastDeathAt;
	/** Who got you last (death card); deathInfoAt is when. */
	public static dev.rubymod.net.MorePayloads.DeathInfo deathInfo;
	public static long deathInfoAt;
	/** Research points and unlocks. */
	public static dev.rubymod.net.MorePayloads.ResearchSync research;

	public static long reloadStartedAt;
	public static int reloadTicks;

	public record Kill(Payloads.KillFeed info, long at) {
	}

	/** Recent kills, newest first. */
	public static final ArrayDeque<Kill> KILLS = new ArrayDeque<>();

	public static float shake;
	public static int shakeTicks;

	public static long hitMarkerAt;
	/** Bank and power grid (see Payloads.Economy). */
	public static Payloads.Economy economy;
	/** Your base's machines (from BaseMachines.rows), refreshed every couple of seconds. */
	public static java.util.List<BaseMachine> base = java.util.List.of();

	public record BaseMachine(String dim, net.minecraft.core.BlockPos pos, dev.rubymod.industry.MachineKind kind, int tier, int status,
			String owner, long loads, long rubies, boolean shared) {
		public boolean stopped() {
			return dev.rubymod.industry.BaseMachines.stoppedFor(kind, status);
		}

		static BaseMachine parse(String row) {
			String[] p = row.split("\\|", -1);
			var kinds = dev.rubymod.industry.MachineKind.values();
			return new BaseMachine(p[0], new net.minecraft.core.BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])),
					kinds[Math.min(kinds.length - 1, Integer.parseInt(p[4]))], Integer.parseInt(p[5]), Integer.parseInt(p[6]), p[7],
					Long.parseLong(p[8]), Long.parseLong(p[9]), "1".equals(p[10]));
		}
	}

	/** What your machines made this season, and what's in your silos (item id, count; most first). */
	public static java.util.List<java.util.Map.Entry<String, Long>> made = java.util.List.of();
	public static java.util.List<java.util.Map.Entry<String, Long>> silos = java.util.List.of();
	/** What your machines made while you were away (the latest time; item id, count). */
	public static java.util.List<java.util.Map.Entry<String, Long>> away = java.util.List.of();
	/** Minerals found for the Rock Collection (item ids). */
	public static java.util.Set<String> rocks = java.util.Set.of();

	public static void base(java.util.List<String> rows) {
		var list = new java.util.ArrayList<BaseMachine>();
		var madeList = new java.util.ArrayList<java.util.Map.Entry<String, Long>>();
		var siloList = new java.util.ArrayList<java.util.Map.Entry<String, Long>>();
		var awayList = new java.util.ArrayList<java.util.Map.Entry<String, Long>>();
		for (String row : rows) {
			try {
				if (row.startsWith("#")) {
					String[] p = row.split("\\|", -1);
					switch (p[0]) {
						case "#made" -> madeList.add(java.util.Map.entry(p[1], Long.parseLong(p[2])));
						case "#silo" -> siloList.add(java.util.Map.entry(p[1], Long.parseLong(p[2])));
						case "#away" -> awayList.add(java.util.Map.entry(p[1], Long.parseLong(p[2])));
						case "#rocks" -> rocks = p[1].isEmpty() ? java.util.Set.of() : java.util.Set.of(p[1].split(","));
						default -> {
						}
					}
					continue;
				}
				list.add(BaseMachine.parse(row));
			} catch (RuntimeException e) {
				// a row from a newer or older server: skip it
			}
		}
		base = list;
		made = madeList;
		silos = siloList;
		away = awayList;
	}
	/** Last warhead hit on a vehicle or aircraft (shown under the crosshair). */
	public static Payloads.HitConfirm hitConfirm;
	public static long hitConfirmAt;
	public static long damagedAt;
	public static double damageX;
	public static double damageZ;
	public static boolean hitHeadshot;
	public static boolean hitKill;

	private ClientState() {
	}

	public static Phase phase() {
		if (hud == null || hud.phase() < 0 || hud.phase() >= Phase.values().length) {
			return Phase.LOBBY;
		}
		return Phase.values()[hud.phase()];
	}

	public static long secondsLeft() {
		if (hud == null || hud.secondsLeft() < 0) {
			return -1;
		}
		if (hud.paused()) {
			return hud.secondsLeft();
		}
		long elapsed = (System.currentTimeMillis() - hudReceivedAt) / 1000;
		return Math.max(0, hud.secondsLeft() - elapsed);
	}

	public static void reset() {
		hud = null;
		radar = null;
		reloadTicks = 0;
	}
}
