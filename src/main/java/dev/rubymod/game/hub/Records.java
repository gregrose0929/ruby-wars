package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

import dev.rubymod.game.Props;
import dev.rubymod.mixin.TextDisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;

/**
 * Tonight's records: the best go-kart lap, golf round, duck shoot and so on since the server last started. Shown on
 * boards on the plaza and at the Speedway. Nothing is saved: tomorrow's a fresh start.
 */
final class Records {
	private Records() {
	}

	private record Best(String who, int value, boolean lowerWins, IntFunction<String> show) {
	}

	private static final Map<String, Best> BEST = new LinkedHashMap<>();
	private static final List<UUID> BOARDS = new ArrayList<>();

	static void build(ServerLevel level) {
		BOARDS.clear();
		for (Vec3 at : new Vec3[] {new Vec3(-7.5, FLOOR + 6.4, 11.5), new Vec3(Speedway.CX - 0.5, FLOOR + 5.5, Speedway.CZ - 5.5)}) {
			Display.TextDisplay t = Props.text(level, at, Component.literal("TONIGHT'S RECORDS"), 0.6F, HubPark.TAG);
			HubPark.keep(t);
			if (t != null) {
				BOARDS.add(t.getUUID());
			}
		}
		board(level);
	}

	/** A score: kept (and announced) if it's the best tonight. */
	static void submit(ServerLevel level, String what, String who, int value, boolean lowerWins, IntFunction<String> show) {
		Best old = BEST.get(what);
		if (old != null && (lowerWins ? value >= old.value() : value <= old.value())) {
			return;
		}
		BEST.put(what, new Best(who, value, lowerWins, show));
		for (ServerPlayer p : level.players()) {
			p.sendSystemMessage(Component.literal("★ New record tonight! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
					.append(Component.literal(what + ": " + who + " (" + show.apply(value) + ")").withStyle(ChatFormatting.YELLOW)));
		}
		board(level);
	}

	private static void board(ServerLevel level) {
		MutableComponent text = Component.literal("★ TONIGHT'S RECORDS ★").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		if (BEST.isEmpty()) {
			text.append(Component.literal("\nNone yet! Race, putt, shoot\nand play to set one.").withStyle(ChatFormatting.WHITE));
		}
		for (var e : BEST.entrySet()) {
			text.append(Component.literal("\n" + e.getKey() + ": ").withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(e.getValue().who() + " (" + e.getValue().show().apply(e.getValue().value()) + ")").withStyle(ChatFormatting.WHITE));
		}
		for (UUID id : BOARDS) {
			if (level.getEntity(id) instanceof Display.TextDisplay t) {
				((TextDisplayAccessor) t).rubymod$setText(text);
			}
		}
	}
}
