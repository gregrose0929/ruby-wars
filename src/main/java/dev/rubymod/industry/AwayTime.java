package dev.rubymod.industry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What your machines did while nobody was near them. Machines catch up one by one as their chunks load, so this
 * gathers it all up and says it once ("While you were away") instead of once per machine, and keeps the latest
 * for My Base. Not saved.
 */
public final class AwayTime {
	/** Said once nothing more has come in for this long (machines nearby load within a tick or two of each other). */
	private static final int QUIET_TICKS = 40;
	/** How long My Base shows it (real time). */
	private static final long SHOW_MS = 30 * 60 * 1000L;

	private static final class Tally {
		final Map<Item, Long> made = new LinkedHashMap<>();
		long milliRubies;
		long lastAdd;
		long madeAt;
		boolean told;
	}

	/** Still coming in. */
	private static final Map<UUID, Tally> PENDING = new HashMap<>();
	/** The latest one, for My Base. */
	private static final Map<UUID, Tally> LAST = new HashMap<>();
	private static long now;

	private AwayTime() {
	}

	private static Tally tally(UUID owner) {
		Tally t = PENDING.computeIfAbsent(owner, k -> new Tally());
		t.lastAdd = now;
		return t;
	}

	/** A machine made this while you were away. */
	public static void made(UUID owner, ItemStack stack) {
		if (owner == null || stack.isEmpty() || Grid.special(owner)) {
			return;
		}
		tally(owner).made.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
	}

	/** A Trade Depot sold things for this while you were away. */
	public static void sold(UUID owner, long milliRubies) {
		if (owner == null || milliRubies <= 0 || Grid.special(owner)) {
			return;
		}
		tally(owner).milliRubies += milliRubies;
	}

	/** Every tick: say what's settled, to its owner if they're on. */
	public static void tick(MinecraftServer server) {
		now++;
		if (!PENDING.isEmpty()) {
			var it = PENDING.entrySet().iterator();
			while (it.hasNext()) {
				var e = it.next();
				if (now - e.getValue().lastAdd >= QUIET_TICKS) {
					it.remove();
					Tally t = e.getValue();
					t.madeAt = System.currentTimeMillis();
					LAST.put(e.getKey(), t);
				}
			}
		}
		if (now % 20 == 0) {
			for (var e : LAST.entrySet()) {
				Tally t = e.getValue();
				if (!t.told && server.getPlayerList().getPlayer(e.getKey()) instanceof ServerPlayer player) {
					t.told = true;
					dev.rubymod.game.Alerts.toast(player, "rubymod:ruby_drill", "While you were away", summary(t, 2), dev.rubymod.game.Alerts.GREEN);
				}
			}
		}
	}

	/** "Your machines made 41 Raw Iron, 12 Coal and more, and earned 6 rubies". */
	private static String summary(Tally t, int most) {
		List<Map.Entry<Item, Long>> list = new ArrayList<>(t.made.entrySet());
		list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		Map<Item, Integer> top = new LinkedHashMap<>();
		for (var e : list.subList(0, Math.min(most, list.size()))) {
			top.put(e.getKey(), (int) Math.min(Integer.MAX_VALUE, e.getValue()));
		}
		String made = top.isEmpty() ? "" : dev.rubymod.item.GeodeItem.summary(top);
		if (list.size() > most) {
			made = made.replace(" and ", ", ") + " and more";
		}
		long rubies = t.milliRubies / 1000;
		String sold = rubies > 0 ? "earned " + rubies + (rubies == 1 ? " ruby" : " rubies") : "";
		if (made.isEmpty()) {
			return sold.isEmpty() ? "Your machines kept busy" : "Your Trade Depot " + sold;
		}
		return "Your machines made " + made + (sold.isEmpty() ? "" : ", and " + sold);
	}

	/** The whole thing as text (for tests and the log). */
	public static String latest(UUID owner) {
		Tally t = LAST.get(owner);
		return t == null ? "" : summary(t, 99);
	}

	/** Is something still being gathered up for this player? */
	public static boolean gathering(UUID owner) {
		return PENDING.containsKey(owner);
	}

	/** For My Base: "#away|item|count" (most first; Trade Depot earnings as rubies); nothing once it's half an hour old. */
	public static List<String> rows(UUID owner) {
		Tally t = LAST.get(owner);
		List<String> rows = new ArrayList<>();
		if (t == null || System.currentTimeMillis() - t.madeAt > SHOW_MS) {
			return rows;
		}
		// Rubies a Trade Depot earned count with any a drill dug up.
		Map<Item, Long> all = new LinkedHashMap<>(t.made);
		if (t.milliRubies >= 1000) {
			all.merge(dev.rubymod.registry.ModItems.RUBY, t.milliRubies / 1000, Long::sum);
		}
		List<Map.Entry<Item, Long>> list = new ArrayList<>(all.entrySet());
		list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		for (var e : list.subList(0, Math.min(8, list.size()))) {
			rows.add("#away|" + BuiltInRegistries.ITEM.getKey(e.getKey()) + "|" + e.getValue());
		}
		return rows;
	}

	public static void reset() {
		PENDING.clear();
		LAST.clear();
	}
}
