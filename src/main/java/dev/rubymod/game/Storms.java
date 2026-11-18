package dev.rubymod.game;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.world.WarzoneTerrain;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Battle Day storms: a sandstorm over the Red Desert or a blizzard over the Snow Plateau. Inside one you can't see far
 * (thick fog, blowing sand or snow) and aircraft can't go full speed. On Battle Day they take turns: twelve minutes of
 * storm, eight clear, then the other corner. (Shared by the server and the client: the server decides, the client is told.)
 */
public final class Storms {
	private Storms() {
	}

	public static final int NONE = -1;
	/** How far out from the middle a storm starts (the crater stays clear). */
	public static final double INNER = 250;
	/** How much of an aircraft's speed it keeps in a storm. */
	public static final double AIR_SPEED = 0.6;
	private static final long CYCLE = 20L * 60 * 20;
	private static final long STORMY = 20L * 60 * 12;

	/** The storm blowing now: WarzoneTerrain.THEME_DESERT, THEME_SNOW, or NONE. */
	private static volatile int theme = NONE;

	public static int theme() {
		return theme;
	}

	/** For autotests: this storm whatever the day (null: back to normal). */
	private static Integer forced;

	public static void force(Integer t) {
		forced = t;
	}

	/** (Client) what the server says is blowing. */
	public static void set(int t) {
		theme = t;
	}

	/** Is this spot (in the Ruby Wars world) inside the storm? */
	public static boolean inStorm(Level level, double x, double z) {
		int t = theme;
		return t != NONE && level != null && level.dimension() == Level.OVERWORLD && Math.hypot(x, z) > INNER && WarzoneTerrain.quadrant(x, z) == t;
	}

	/** Which storm should be blowing at this game time on Battle Day (NONE in the clear spells). */
	public static int scheduled(long gameTime) {
		long cycle = gameTime / CYCLE;
		if (gameTime % CYCLE >= STORMY) {
			return NONE;
		}
		return cycle % 2 == 0 ? WarzoneTerrain.THEME_DESERT : WarzoneTerrain.THEME_SNOW;
	}

	public static String name(int t) {
		return t == WarzoneTerrain.THEME_DESERT ? "a sandstorm over the Red Desert" : t == WarzoneTerrain.THEME_SNOW ? "a blizzard over the Snow Plateau" : "clear skies";
	}

	/** (Server) every second: start or end a storm, and keep everyone's client told. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		Level overworld = server.overworld();
		int want = forced != null ? forced : Rules.phase().isBattle() ? scheduled(overworld.getGameTime()) : NONE;
		boolean changed = want != theme;
		theme = want;
		if (changed || server.getTickCount() % 200 == 0) {
			MorePayloads.StormState state = new MorePayloads.StormState(want);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Net.send(p, state);
				if (changed && p.level() == overworld) {
					p.sendSystemMessage(Component.literal(want == NONE ? "The storm's blowing itself out." : "Storm warning: " + name(want)
							+ "! You can't see far in it, and aircraft can't fly full speed.").withStyle(ChatFormatting.GOLD));
				}
			}
		}
	}

	/** (Server) a player just joined: tell them the weather. */
	public static void onJoin(ServerPlayer p) {
		Net.send(p, new MorePayloads.StormState(theme));
	}
}
