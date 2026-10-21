package dev.rubymod.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/** Messages, titles, and HUD alerts sent to players. */
public final class Alerts {
	private static final Map<UUID, Long> LAST_INTRUDER = new HashMap<>();

	private Alerts() {
	}

	public static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	public static void broadcastTitle(MinecraftServer server, String title, String subtitle, ChatFormatting color) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			title(player, title, subtitle, color);
		}
	}

	public static void title(ServerPlayer player, String title, String subtitle, ChatFormatting color) {
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 60, 20));
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
				Component.literal(title).withStyle(color, ChatFormatting.BOLD)));
	}

	public static void ping(ServerPlayer player, float pitch) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, pitch);
	}

	public static void intruder(ServerLevel level, SeasonData.Claim claim, Player intruder, BlockPos alarm) {
		UUID owner = UUID.fromString(claim.owner);
		java.util.List<UUID> side = new java.util.ArrayList<>(Squads.mates(owner));
		side.add(owner);
		for (UUID id : side) {
			LAST_INTRUDER.put(id, level.getGameTime());
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				String base = id.equals(owner) ? "your base" : claim.ownerName + "'s base";
				p.sendSystemMessage(Component.literal("ALARM: " + intruder.getName().getString() + " is in " + base + " near "
						+ alarm.getX() + ", " + alarm.getY() + ", " + alarm.getZ()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
				ping(p, 0.5F);
			}
		}
		// The alarm also spots the intruder for the defenders' map for a few seconds.
		Waypoints.spot(intruder, owner, 6);
	}

	public static void sendRadar(ServerPlayer owner, int contacts, int nearest, int bearing, boolean missile) {
		long last = LAST_INTRUDER.getOrDefault(owner.getUUID(), -1000L);
		boolean intruder = owner.level().getGameTime() - last < 200;
		Net.send(owner, new Payloads.Radar(contacts, nearest, bearing, missile, intruder));
		if (missile && owner.tickCount % 40 < 20) {
			ping(owner, 2.0F);
		}
	}
}
