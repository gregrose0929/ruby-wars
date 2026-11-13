package dev.rubymod.client;

import java.util.HashMap;
import java.util.Map;

/**
 * The control hints in a tank, a jeep or an aircraft. The very first time you ever get in one they're up for that ride
 * (so a new player knows the keys); after that only when turned on with the help key (N), which is remembered.
 */
public final class VehicleHelp {
	/** First-ever rides: which vehicle (by entity id) the first look is for. */
	private static final Map<String, Integer> FIRST = new HashMap<>();

	private VehicleHelp() {
	}

	/** Should this vehicle's hints be up right now? {@code ride} tells one ride from the next. */
	public static boolean show(String key, int ride) {
		if (HelpPrefs.on(key)) {
			return true;
		}
		Integer first = FIRST.get(key);
		if (first != null) {
			return first == ride;
		}
		if (HelpPrefs.wanted(key)) {
			FIRST.put(key, ride);
			return true;
		}
		return false;
	}

	static boolean firstLook(String key) {
		return FIRST.containsKey(key) && FIRST.get(key) != -1;
	}

	static void endFirstLook(String key) {
		FIRST.put(key, -1);
	}
}
