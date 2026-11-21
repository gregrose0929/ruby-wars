package dev.rubymod.industry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Rules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every machine's latest state, kept as machines tick (once a second), for the "My base" page and the map's
 * stopped-machine dots. A machine in a chunk nobody is near keeps its last report. Not saved: after a restart
 * machines show up again as their chunks load.
 */
public final class BaseMachines {
	/** One machine: where it is, what it is, how it's doing, and whose power it runs on. */
	public record Entry(String dim, BlockPos pos, MachineKind kind, int tier, int status, UUID owner, UUID grid, long loads,
			long rubies) {
		/** Stopped for a reason worth fixing (not resting at night, or a Refinery waiting for ore). */
		public boolean stopped() {
			return stoppedFor(kind, status);
		}
	}

	private static final Map<String, Entry> ALL = new HashMap<>();
	/** When each grid last got an "out of power" note (real time), so it's said once in a while, not every tick. */
	private static final Map<UUID, Long> NOTED = new HashMap<>();
	private static final long NOTE_EVERY_MS = 20 * 60 * 1000L;

	private BaseMachines() {
	}

	public static boolean stoppedFor(MachineKind kind, int status) {
		return switch (status) {
			case MachineBlockEntity.NO_POWER -> true;
			case MachineBlockEntity.OUTPUT_FULL -> !kind.generator();
			case MachineBlockEntity.NO_INPUT -> kind == MachineKind.COMBUSTION_GENERATOR;
			default -> false;
		};
	}

	private static String key(String dim, BlockPos pos) {
		return dim + "|" + pos.asLong();
	}

	public static void report(ServerLevel level, MachineBlockEntity be) {
		UUID owner = be.owner();
		if (owner == null || Grid.special(owner)) {
			return; // the training ground's machines belong to nobody; Horde and Rift machines aren't anyone's base
		}
		String dim = Rules.dim(level);
		ALL.put(key(dim, be.getBlockPos()), new Entry(dim, be.getBlockPos().immutable(), be.kind(), be.tier(), be.status(), owner, be.power(),
				be.loads(), be.rubiesFound()));
	}

	public static void removed(ServerLevel level, BlockPos pos) {
		ALL.remove(key(Rules.dim(level), pos));
	}

	/** A player's machines, wherever they are. Drills first. */
	public static List<Entry> forPlayer(UUID player) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : ALL.values()) {
			if (e.owner().equals(player)) {
				out.add(e);
			}
		}
		out.sort((a, b) -> a.kind() != b.kind() ? Integer.compare(order(a.kind()), order(b.kind()))
				: Long.compare(a.pos().asLong(), b.pos().asLong()));
		return out;
	}

	private static int order(MachineKind kind) {
		return switch (kind) {
			case RUBY_DRILL -> 0;
			case SOLAR_PANEL -> 1;
			case COMBUSTION_GENERATOR -> 2;
			case BATTERY -> 3;
			default -> 4 + kind.ordinal();
		};
	}

	/**
	 * A drill ran out of power. If its base uses everything it makes, say so (once in a while) to whoever is
	 * online on that grid: kindly, with what would help.
	 */
	public static void outOfPower(ServerLevel level, MachineBlockEntity be) {
		UUID grid = be.power();
		if (grid == null || Grid.special(grid)) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = NOTED.get(grid);
		if (last != null && now - last < NOTE_EVERY_MS) {
			return;
		}
		NOTED.put(grid, now);
		MinecraftServer server = level.getServer();
		boolean night = level.getOverworldClockTime() % 24000 >= 12500;
		String body = night && Grid.producedPerSecond(grid) == 0
				? "Solar panels rest at night: a Battery Bank stores daytime power, or burn coal in a generator"
				: "They use everything your base makes: a Combustion Generator or more solar panels will help";
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.getUUID().equals(be.owner())) {
				dev.rubymod.game.Alerts.toast(player, "rubymod:ruby_drill", "Your drills are out of power", body, dev.rubymod.game.Alerts.GOLD);
			}
		}
	}

	public static void reset() {
		ALL.clear();
		NOTED.clear();
	}

	/** One line per machine for the client: dim|x|y|z|kind|tier|status|owner name|loads|rubies|shares my power. */
	public static List<String> rows(MinecraftServer server, UUID player) {
		UUID home = player;
		List<String> rows = new ArrayList<>();
		for (Entry e : forPlayer(player)) {
			var record = dev.rubymod.game.SeasonData.get().players.get(e.owner().toString());
			String name = record == null || record.name.isEmpty() ? "?" : record.name;
			rows.add(e.dim() + "|" + e.pos().getX() + "|" + e.pos().getY() + "|" + e.pos().getZ() + "|" + e.kind().ordinal() + "|" + e.tier()
					+ "|" + e.status() + "|" + name.replace("|", "") + "|" + e.loads() + "|" + e.rubies() + "|" + (e.grid().equals(home) ? 1 : 0));
			if (rows.size() >= 120) {
				break;
			}
		}
		return rows;
	}

	/**
	 * For My Base and the Rock Collection: the machine rows, then "#made|item|count" (this season, most first),
	 * "#silo|item|count" (in your silos) and "#rocks|id,id,..." (minerals found).
	 */
	public static List<String> rows(MinecraftServer server, ServerPlayer player) {
		List<String> rows = rows(server, player.getUUID());
		for (var e : Production.top(player.getUUID(), 8)) {
			rows.add("#made|" + e.getKey() + "|" + e.getValue());
		}
		var silos = SiloBlockEntity.Silos.totals(player.getUUID());
		for (var e : silos.subList(0, Math.min(8, silos.size()))) {
			rows.add("#silo|" + e.getKey() + "|" + e.getValue());
		}
		rows.add("#rocks|" + String.join(",", dev.rubymod.game.Minerals.found(player)));
		return rows;
	}
}
