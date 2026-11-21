package dev.rubymod.industry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.SeasonData;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** What each player's machines have made this season (shown on My Base). */
public final class Production {
	private Production() {
	}

	public static void add(UUID owner, ItemStack stack) {
		if (owner == null || stack.isEmpty() || Grid.special(owner)) {
			return;
		}
		SeasonData.PlayerRecord record = SeasonData.get().players.get(owner.toString());
		if (record == null) {
			return;
		}
		if (record.made == null) {
			record.made = new java.util.HashMap<>();
		}
		record.made.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
		SeasonData.markDirty();
	}

	/** Item id and count, most first. */
	public static List<Map.Entry<String, Long>> top(UUID owner, int max) {
		SeasonData.PlayerRecord record = SeasonData.get().players.get(owner.toString());
		if (record == null || record.made == null) {
			return List.of();
		}
		List<Map.Entry<String, Long>> out = new ArrayList<>(record.made.entrySet());
		out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		return out.subList(0, Math.min(max, out.size()));
	}
}
