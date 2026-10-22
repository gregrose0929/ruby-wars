package dev.rubymod.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Combat tags: hurting or being hurt by another player tags you both for 15
 * seconds (config: combatTagSeconds). Logging out while tagged when PvP is on
 * counts as dying: your gear goes to a War Grave and your death counts.
 */
public final class CombatTag {
	private static final Map<UUID, Long> TAGGED = new HashMap<>();

	private CombatTag() {
	}

	public static void tag(ServerPlayer a, ServerPlayer b) {
		if (a == b || !Rules.phase().pvp() || a.isCreative() || b.isCreative() || Duels.inDuel(a) || Duels.inDuel(b)) {
			return;
		}
		long until = System.currentTimeMillis() + GameConfig.get().combatTagSeconds * 1000L;
		for (ServerPlayer p : new ServerPlayer[] {a, b}) {
			Long old = TAGGED.put(p.getUUID(), until);
			Net.send(p, new MorePayloads.CombatTagged(GameConfig.get().combatTagSeconds));
			if (old == null || old < System.currentTimeMillis()) {
				p.sendOverlayMessage(Component.literal("In combat: don't log out for " + GameConfig.get().combatTagSeconds + "s")
						.withStyle(ChatFormatting.RED));
			}
		}
	}

	public static boolean tagged(UUID player) {
		Long until = TAGGED.get(player);
		return until != null && until > System.currentTimeMillis();
	}

	/** On disconnect: a tagged player who logs out mid-fight dies where they stand. */
	public static void onLeave(MinecraftServer server, ServerPlayer player) {
		// Not when the server itself is shutting down (everyone disconnects then).
		if (server.isRunning() && tagged(player.getUUID()) && Rules.phase().pvp() && player.isAlive() && !player.isCreative()) {
			Alerts.broadcast(server, Component.literal(player.getName().getString() + " logged out in combat and was killed.")
					.withStyle(ChatFormatting.RED));
			player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
		}
		TAGGED.remove(player.getUUID());
	}
}
