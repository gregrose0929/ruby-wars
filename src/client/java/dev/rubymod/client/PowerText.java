package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.rubymod.industry.MachineBlockEntity;
import dev.rubymod.industry.MachineKind;

/** Plain-words power sums for the machine screens and the My Base page: how long it lasts, what makes and uses it. */
public final class PowerText {
	private PowerText() {
	}

	/** "Fills up in about 4 min", "Runs out in about 2 min", "Full", "Steady". Rates are per second. */
	public static String forecast(long charge, long capacity, long made, long used) {
		long net = made - used;
		if (net > 0) {
			return charge >= capacity ? "Full" : "Fills up in " + duration((capacity - charge) / (double) net);
		}
		if (net < 0) {
			return charge <= 0 ? "Empty: machines wait for power" : "Runs out in " + duration(charge / (double) -net);
		}
		return used == 0 ? "Nothing is using power right now" : "Steady: using all it makes";
	}

	/** How long the stored power keeps things going after dark (solar panels rest), or "" when it doesn't matter. */
	public static String night(long charge, long made, long used) {
		long solar = 0;
		for (ClientState.BaseMachine m : ClientState.base) {
			if (m.shared() && m.kind() == MachineKind.SOLAR_PANEL && m.status() == MachineBlockEntity.WORKING) {
				solar += m.kind().output(m.tier()) * 20L;
			}
		}
		if (solar == 0) {
			return "";
		}
		long nightNet = made - solar - used;
		if (nightNet >= 0) {
			return "At night: still enough without the sun";
		}
		return charge <= 0 ? "At night: drills stop (nothing stored)" : "At night: stored power lasts " + duration(charge / (double) -nightNet);
	}

	/** About how long, kindly rounded. */
	public static String duration(double seconds) {
		if (seconds < 60) {
			return "under a minute";
		}
		if (seconds < 3600) {
			int m = (int) Math.round(seconds / 60);
			return "about " + m + (m == 1 ? " minute" : " minutes");
		}
		double h = seconds / 3600;
		return h < 10 ? String.format(Locale.ROOT, "about %.1f hours", h) : "many hours";
	}

	/** Per kind, what your base's machines make and use right now, biggest first: "2 Solar Panels: +40/s". */
	public static List<String> breakdown() {
		long[] made = new long[MachineKind.values().length];
		long[] used = new long[made.length];
		int[] count = new int[made.length];
		for (ClientState.BaseMachine m : ClientState.base) {
			if (!m.shared()) {
				continue;
			}
			int k = m.kind().ordinal();
			count[k]++;
			if (m.status() == MachineBlockEntity.WORKING) {
				made[k] += m.kind().output(m.tier()) * 20L;
				used[k] += m.kind().draw(m.tier()) * 20L;
			}
		}
		List<long[]> rows = new ArrayList<>();
		for (int k = 0; k < made.length; k++) {
			if (made[k] > 0 || used[k] > 0) {
				rows.add(new long[] {k, made[k] - used[k]});
			}
		}
		rows.sort((a, b) -> Long.compare(Math.abs(b[1]), Math.abs(a[1])));
		List<String> out = new ArrayList<>();
		for (long[] r : rows) {
			MachineKind kind = MachineKind.values()[(int) r[0]];
			int n = count[(int) r[0]];
			String name = n == 1 ? kind.title : n + " " + plural(kind.title);
			out.add(name + ": " + (r[1] >= 0 ? "+" : "") + String.format(Locale.ROOT, "%,d", r[1]) + "/s");
		}
		return out;
	}

	private static String plural(String title) {
		return title.endsWith("y") && !title.endsWith("ey") ? title.substring(0, title.length() - 1) + "ies" : title + "s";
	}
}
