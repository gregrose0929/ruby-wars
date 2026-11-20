package dev.rubymod.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * This player's Ruby Wars settings, on their own PC (config/rubywars_settings.properties): what the HUD shows, the
 * minimap's size, toasts, the look-at panel. Changed on the Settings screen (Ruby Wars menu, M).
 */
public final class ClientSettings {
	/** The guide panel: "full" (always open), "compact" (one line), or "off". */
	public static String guide = "compact";
	/** "small" (the default: one step smaller than the GUI scale) or "normal" (see HudScale). */
	public static String hudSize = "small";
	public static boolean minimap = true;
	public static boolean bigMinimap = false;
	public static boolean heartPanel = true;
	public static boolean bankPanel = true;
	public static boolean toasts = true;
	public static boolean lookInfo = true;
	public static boolean killFeed = true;

	private static boolean loaded;

	private ClientSettings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("rubywars_settings.properties");
	}

	public static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Properties p = new Properties();
		if (Files.exists(file())) {
			try (Reader r = Files.newBufferedReader(file())) {
				p.load(r);
			} catch (IOException e) {
				dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the Ruby Wars settings", e);
			}
		}
		guide = p.getProperty("guide", guide);
		hudSize = p.getProperty("hudSize", hudSize);
		minimap = bool(p, "minimap", minimap);
		bigMinimap = bool(p, "bigMinimap", bigMinimap);
		heartPanel = bool(p, "heartPanel", heartPanel);
		bankPanel = bool(p, "bankPanel", bankPanel);
		toasts = bool(p, "toasts", toasts);
		lookInfo = bool(p, "lookInfo", lookInfo);
		killFeed = bool(p, "killFeed", killFeed);
		dev.rubymod.client.map.Minimap.enabled = minimap;
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		String v = p.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v);
	}

	public static void save() {
		minimap = dev.rubymod.client.map.Minimap.enabled;
		Properties p = new Properties();
		p.setProperty("guide", guide);
		p.setProperty("hudSize", hudSize);
		p.setProperty("minimap", String.valueOf(minimap));
		p.setProperty("bigMinimap", String.valueOf(bigMinimap));
		p.setProperty("heartPanel", String.valueOf(heartPanel));
		p.setProperty("bankPanel", String.valueOf(bankPanel));
		p.setProperty("toasts", String.valueOf(toasts));
		p.setProperty("lookInfo", String.valueOf(lookInfo));
		p.setProperty("killFeed", String.valueOf(killFeed));
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file())) {
				p.store(w, "Ruby Wars settings (change them in game: M, then Settings)");
			}
		} catch (IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the Ruby Wars settings", e);
		}
	}
}
