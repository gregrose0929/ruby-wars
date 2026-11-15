package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Props;
import dev.rubymod.mixin.TextDisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;

/**
 * The Prize Shelf on the plaza: prizes won tonight (teddy bears from the carnival games, rubber ducks from the Duck
 * Shoot) go up on the shelf with the winner's name, instead of into your pockets. Cleared when the server restarts.
 */
final class Prizes {
	private Prizes() {
	}

	private static final int SPOTS = 16;
	private static final Map<String, Integer> COUNT = new LinkedHashMap<>();
	private static final List<String> SHOWN = new ArrayList<>();
	private static final List<UUID> MODELS = new ArrayList<>();
	private static UUID board;

	static void build(ServerLevel level) {
		MODELS.clear();
		// A shelf unit: two shelves of dark oak between posts, east of the spawn.
		for (int x = 10; x <= 13; x++) {
			HubPark.set(level, x, FLOOR + 1, 13, Blocks.DARK_OAK_SLAB.defaultBlockState());
			HubPark.set(level, x, FLOOR + 2, 13, Blocks.DARK_OAK_SLAB.defaultBlockState()
					.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
			HubPark.set(level, x, FLOOR + 1, 14, Blocks.DARK_OAK_PLANKS.defaultBlockState());
			HubPark.set(level, x, FLOOR + 2, 14, Blocks.DARK_OAK_PLANKS.defaultBlockState());
			HubPark.set(level, x, FLOOR + 3, 14, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
		}
		Display.TextDisplay t = Props.text(level, new Vec3(11.5, FLOOR + 4.4, 13.2), Component.literal("PRIZE SHELF"), 0.6F, HubPark.TAG);
		HubPark.keep(t);
		board = t == null ? null : t.getUUID();
		for (int i = 0; i < SHOWN.size(); i++) {
			place(level, i);
		}
		text(level);
	}

	private static Vec3 spot(int i) {
		int row = i / 8;
		return new Vec3(10.25 + (i % 8) * 0.5, FLOOR + 1.52 + row * 1.0, 13.45);
	}

	private static void place(ServerLevel level, int i) {
		Display.ItemDisplay m = HubPark.model(level, spot(i), 0, SHOWN.get(i), 0.45F, 0.22F);
		MODELS.add(m == null ? null : m.getUUID());
	}

	/** Somebody won a prize: up it goes on the shelf. */
	static void award(ServerLevel level, ServerPlayer p, String model, String what) {
		COUNT.merge(p.getName().getString(), 1, Integer::sum);
		if (SHOWN.size() >= SPOTS) {
			SHOWN.remove(0);
			HubPark.discard(level, MODELS.remove(0));
			for (int i = 0; i < MODELS.size(); i++) {
				Display.ItemDisplay d = MODELS.get(i) == null ? null : (Display.ItemDisplay) level.getEntity(MODELS.get(i));
				if (d != null) {
					Vec3 s = spot(i);
					d.teleportTo(s.x, s.y, s.z);
				}
			}
		}
		SHOWN.add(model);
		place(level, SHOWN.size() - 1);
		text(level);
		p.sendSystemMessage(Component.literal("★ You won " + what + "! It's on the Prize Shelf on the plaza.").withStyle(ChatFormatting.GOLD));
	}

	private static void text(ServerLevel level) {
		if (board == null || !(level.getEntity(board) instanceof Display.TextDisplay t)) {
			return;
		}
		MutableComponent text = Component.literal("★ PRIZE SHELF ★").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		if (COUNT.isEmpty()) {
			text.append(Component.literal("\nWin the carnival games\nto put a prize up here!").withStyle(ChatFormatting.WHITE));
		}
		for (var e : COUNT.entrySet()) {
			text.append(Component.literal("\n" + e.getKey() + ": " + e.getValue() + (e.getValue() == 1 ? " prize" : " prizes")).withStyle(ChatFormatting.WHITE));
		}
		((TextDisplayAccessor) t).rubymod$setText(text);
	}
}
