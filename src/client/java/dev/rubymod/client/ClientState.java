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
		return level != null && level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
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
	/** Today's bounties (see Bounties.sync). */
	public static java.util.List<String> bounties = java.util.List.of();
	/** The raid on your side, if any, and when we last heard. */
	public static dev.rubymod.net.MorePayloads.RaidStatus raid;
	public static long raidAt;
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
