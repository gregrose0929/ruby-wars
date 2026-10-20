package dev.rubymod.client;

import dev.rubymod.game.Phase;
import dev.rubymod.net.Payloads;

/** What the server last told this client, for the HUD. */
public final class ClientState {
	public static Payloads.HudSync hud;
	/** System time (ms) when the last HudSync arrived, to count the timer down smoothly between packets. */
	public static long hudReceivedAt;

	public static Payloads.Radar radar;
	public static long radarReceivedAt;

	public static long reloadStartedAt;
	public static int reloadTicks;

	public static long hitMarkerAt;
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
