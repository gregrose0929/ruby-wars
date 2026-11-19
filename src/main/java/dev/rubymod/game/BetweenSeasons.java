package dev.rubymod.game;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Between seasons (the last one's over, or the next hasn't started): a card when you come into Ruby Wars saying
 * what's going on and what to play meanwhile, so nobody wonders why nothing's happening.
 */
public final class BetweenSeasons {
	/** Who gets the card, and on which server tick (a moment after arriving, and after "What's new"). */
	private static final Map<UUID, Integer> WAITING = new HashMap<>();

	private BetweenSeasons() {
	}

	public static boolean between() {
		Phase phase = SeasonData.get().phase;
		return phase == Phase.ENDED || phase == Phase.LOBBY;
	}

	/** Arrived in Ruby Wars (joined there, or came in from the hub). */
	public static void arrived(ServerPlayer player, int delayTicks) {
		if (between()) {
			WAITING.put(player.getUUID(), player.level().getServer().getTickCount() + delayTicks);
		}
	}

	public static void tick(MinecraftServer server) {
		if (WAITING.isEmpty()) {
			return;
		}
		var it = WAITING.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (server.getTickCount() < e.getValue()) {
				continue;
			}
			it.remove();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && between() && Hub.where(p) == Hub.Where.RUBY_WARS) {
				Net.send(p, new MorePayloads.HowTo("Between seasons", lines(), 20));
			}
		}
	}

	private static List<String> lines() {
		SeasonData data = SeasonData.get();
		String meanwhile = "minecraft:minecart|Meanwhile: press H for party games, the Coaster Park, Horde and more.";
		if (data.phase == Phase.ENDED) {
			return List.of("minecraft:nether_star|Season " + data.season + " is over" + (data.winner.isEmpty() ? "." : ": " + data.winner + " won!")
					+ " /rubywars recap shows the wrap-up.",
					"minecraft:clock|The next one starts when the admin says so.", meanwhile);
		}
		return List.of("rubymod:ruby_heart|Season " + data.season + " starts when the admin says so. You'll get your Ruby Heart then.", meanwhile);
	}
}
