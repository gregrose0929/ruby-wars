package dev.rubymod.game;

import java.util.List;

import dev.rubymod.registry.ModItems;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Rock Collection: sixteen minerals, each filled in (in the Field Manual) the first time it's in your
 * inventory in Ruby Wars. It's just for fun and stays across seasons. Also: Geodes from mining stone by hand.
 */
public final class Minerals {
	private Minerals() {
	}

	/** One mineral and where it comes from. */
	public record Mineral(Item item, String where) {
		public String id() {
			return BuiltInRegistries.ITEM.getKey(item).toString();
		}
	}

	public static final List<Mineral> ALL = List.of(
			new Mineral(Items.COAL, "Coal ore, almost everywhere"),
			new Mineral(Items.RAW_COPPER, "Copper ore, often near dripstone"),
			new Mineral(Items.RAW_IRON, "Iron ore, in hills and caves"),
			new Mineral(Items.RAW_GOLD, "Gold ore, deep down or in badlands"),
			new Mineral(Items.REDSTONE, "Redstone ore, deep underground"),
			new Mineral(Items.LAPIS_LAZULI, "Lapis ore, deep underground"),
			new Mineral(Items.DIAMOND, "Diamond ore, far below Y 0"),
			new Mineral(Items.EMERALD, "Emerald ore, in the mountains"),
			new Mineral(ModItems.RUBY, "Ruby Ore, below Y 0"),
			new Mineral(Items.AMETHYST_SHARD, "Amethyst caves, or inside a Geode"),
			new Mineral(Items.QUARTZ, "The Nether, or inside a Geode"),
			new Mineral(Items.FLINT, "Digging gravel"),
			new Mineral(Items.OBSIDIAN, "Where water meets lava"),
			new Mineral(Items.GLOWSTONE_DUST, "The Nether's glowing ceilings"),
			new Mineral(Items.ANCIENT_DEBRIS, "Deep in the Nether"),
			new Mineral(ModItems.GEODE, "Drills and mining, now and then"));

	/** Every couple of seconds: anything new in this player's inventory goes in their collection. */
	public static void check(ServerPlayer player) {
		if (!Hub.inRubyWarsWorld(player) || player.isSpectator()) {
			return;
		}
		Stats.Record record = null;
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			for (Mineral m : ALL) {
				if (stack.is(m.item())) {
					if (record == null) {
						record = Stats.get(player);
					}
					if (record.rocks == null) {
						record.rocks = new java.util.HashSet<>();
					}
					if (record.rocks.add(m.id())) {
						Stats.markDirty();
						Alerts.toast(player, m.id(), "New for your Rock Collection",
								stack.getHoverName().getString() + " (" + record.rocks.size() + " of " + ALL.size() + "). See the Field Manual.", Alerts.GREEN);
					}
				}
			}
		}
	}

	/** The ids this player has found. */
	public static java.util.Set<String> found(ServerPlayer player) {
		Stats.Record record = Stats.get(player);
		return record.rocks == null ? java.util.Set.of() : record.rocks;
	}

	/** Mining stone by hand: now and then a Geode (more often deep down). */
	public static void mined(ServerLevel level, ServerPlayer player, net.minecraft.core.BlockPos pos, BlockState state) {
		if (!Hub.inRubyWarsWorld(player) || player.isCreative() || !state.is(BlockTags.BASE_STONE_OVERWORLD)) {
			return;
		}
		if (level.getRandom().nextInt(pos.getY() < 0 ? 250 : 400) == 0) {
			net.minecraft.world.level.block.Block.popResource(level, pos, new ItemStack(ModItems.GEODE));
		}
	}
}
