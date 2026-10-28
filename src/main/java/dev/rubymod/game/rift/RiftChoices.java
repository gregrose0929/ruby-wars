package dev.rubymod.game.rift;

import net.minecraft.server.level.ServerPlayer;

/** What a player chose on a Rift screen (or a Rift ability key). */
public final class RiftChoices {
	private RiftChoices() {
	}

	public static void handle(ServerPlayer player, String kind, String id) {
		if (kind.equals("codex")) {
			RiftCodex.send(player);
			return;
		}
		if (!Rift.playing(player)) {
			return;
		}
		switch (kind) {
			case "vote" -> {
				try {
					Rift.vote(player, Integer.parseInt(id));
				} catch (NumberFormatException ignored) {
					// not a room
				}
			}
			case "pick" -> Rift.pick(player, id);
			case "buy" -> Rift.buy(player, id);
			case "event" -> Rift.event(player, id);
			case "ability" -> RelicEffects.ability(player, id);
			case "loadout", "unlock", "heat", "daily", "pick_ability" -> RiftMeta.choose(player, kind.equals("pick_ability") ? "ability" : kind, id);
			default -> {
			}
		}
	}
}
