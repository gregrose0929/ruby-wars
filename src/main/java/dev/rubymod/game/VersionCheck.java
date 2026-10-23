package dev.rubymod.game;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.RubyMod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Update notice: each client reports its build on join; a few seconds later, anyone whose build
 * doesn't match the server's (or who didn't report one: an older modpack) gets a clickable link to
 * the newest modpack.
 */
public final class VersionCheck {
	public static final String DOWNLOAD_URL = "https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack";
	private static final int DELAY_TICKS = 100;
	private static final Map<UUID, String> REPORTED = new HashMap<>();
	private static final Map<UUID, Integer> PENDING = new HashMap<>();

	private VersionCheck() {
	}

	public static void onJoin(ServerPlayer player) {
		PENDING.put(player.getUUID(), player.level().getServer().getTickCount() + DELAY_TICKS);
	}

	public static void onHello(ServerPlayer player, String build) {
		REPORTED.put(player.getUUID(), build);
	}

	public static void onLeave(ServerPlayer player) {
		REPORTED.remove(player.getUUID());
		PENDING.remove(player.getUUID());
	}

	public static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		int now = server.getTickCount();
		PENDING.entrySet().removeIf(e -> {
			if (e.getValue() > now) {
				return false;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player != null && !RubyMod.BUILD_ID.equals(REPORTED.get(e.getKey()))) {
				warn(player);
			}
			return true;
		});
	}

	private static void warn(ServerPlayer player) {
		RubyMod.LOGGER.info("{} has a different Ruby Wars build ({}; server {})", player.getName().getString(),
				REPORTED.getOrDefault(player.getUUID(), "old modpack"), RubyMod.BUILD_ID);
		Component link = Component.literal("[Click here to download the newest version]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
				.withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(DOWNLOAD_URL)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(DOWNLOAD_URL))));
		player.sendSystemMessage(Component.literal("Your Ruby Wars is a different version from this server. ").withStyle(ChatFormatting.GOLD)
				.append(link)
				.append(Component.literal(" Then double-click the downloaded file, play the newest Ruby Wars in the Modrinth App, and rejoin.")
						.withStyle(ChatFormatting.GOLD)));
		Alerts.title(player, "UPDATE", "A newer Ruby Wars is out: see chat", ChatFormatting.GOLD);
	}
}
