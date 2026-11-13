package dev.rubymod.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Which instructions this player wants to see, remembered on their own PC (config/rubywars_help.txt). Each mode's
 * instructions (a game's how-to card, the tank's and aircraft's control hints) show by themselves the first time ever,
 * then stay hidden unless the player turns them on with the help key (N). Whichever way they leave it, it stays.
 */
public final class HelpPrefs {
	private static final Set<String> SEEN = new LinkedHashSet<>();
	private static final Set<String> SHOWN = new LinkedHashSet<>();
	private static boolean loaded;

	private HelpPrefs() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("rubywars_help.txt");
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			if (Files.exists(file())) {
				for (String line : Files.readAllLines(file())) {
					if (line.startsWith("seen:")) {
						SEEN.add(line.substring(5));
					} else if (line.startsWith("shown:")) {
						SHOWN.add(line.substring(6));
					}
				}
			}
		} catch (IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the help settings", e);
		}
	}

	private static void save() {
		List<String> lines = new ArrayList<>();
		SEEN.forEach(k -> lines.add("seen:" + k));
		SHOWN.forEach(k -> lines.add("shown:" + k));
		try {
			Files.createDirectories(file().getParent());
			Files.write(file(), lines);
		} catch (IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the help settings", e);
		}
	}

	/**
	 * Should this mode's instructions be up? Yes the first time ever (and that counts as having seen them), or when the
	 * player has turned them on.
	 */
	public static boolean wanted(String key) {
		load();
		if (SHOWN.contains(key)) {
			return true;
		}
		if (SEEN.add(key)) {
			save();
			return true;
		}
		return false;
	}

	/** Is this mode's help turned on (without counting a first look)? */
	public static boolean on(String key) {
		load();
		return SHOWN.contains(key);
	}

	/** The help key: turn this mode's instructions on or off, for good. Returns the new setting. */
	public static boolean toggle(String key, boolean showingNow) {
		load();
		SEEN.add(key);
		boolean on = !showingNow;
		if (on) {
			SHOWN.add(key);
		} else {
			SHOWN.remove(key);
		}
		save();
		return on;
	}
}
