package dev.rubymod.industry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModItems;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** What the machines take and make, and what the Trade Depot pays. */
public final class Recipes {
	private Recipes() {
	}

	// ----- Fuel (Combustion Generator), in burn ticks -----

	public static int burnTicks(ItemStack stack) {
		Item item = stack.getItem();
		if (item == Items.COAL || item == Items.CHARCOAL) {
			return 1600;
		}
		if (item == Items.COAL_BLOCK) {
			return 16000;
		}
		if (item == Items.LAVA_BUCKET) {
			return 20000;
		}
		if (item == Items.BLAZE_ROD) {
			return 2400;
		}
		if (item == Items.DRIED_KELP_BLOCK) {
			return 4000;
		}
		if (stack.is(net.minecraft.tags.ItemTags.LOGS)) {
			return 300;
		}
		if (stack.is(net.minecraft.tags.ItemTags.PLANKS)) {
			return 300;
		}
		if (item == Items.STICK) {
			return 100;
		}
		return 0;
	}

	// ----- Ruby Drill -----

	/** One haul. Deeper drills (below Y 0) find twice the rubies. */
	public static ItemStack drill(RandomSource random, int tier, boolean deep) {
		float rubyChance = new float[] {0.015F, 0.02F, 0.03F, 0.04F, 0.055F}[tier - 1] * (deep ? 2.0F : 1.0F);
		float roll = random.nextFloat();
		if (roll < rubyChance) {
			return new ItemStack(ModItems.RUBY);
		}
		roll = random.nextFloat();
		if (roll < 0.01F + 0.004F * tier) {
			return new ItemStack(Items.DIAMOND);
		}
		if (roll < 0.06F + 0.005F * tier) {
			return new ItemStack(Items.RAW_GOLD);
		}
		if (roll < 0.20F) {
			return new ItemStack(Items.RAW_IRON);
		}
		if (roll < 0.33F) {
			return new ItemStack(Items.RAW_COPPER);
		}
		if (roll < 0.46F) {
			return new ItemStack(Items.COAL);
		}
		if (roll < 0.52F) {
			return new ItemStack(Items.REDSTONE, 2);
		}
		if (roll < 0.56F) {
			return new ItemStack(Items.LAPIS_LAZULI, 2);
		}
		return new ItemStack(deep ? Items.COBBLED_DEEPSLATE : Items.COBBLESTONE);
	}

	// ----- Ore Refinery -----

	/** Refined output for an input, or EMPTY if it can't be refined. */
	public static ItemStack refine(ItemStack input, int tier) {
		int n = tier >= 4 ? 3 : 2;
		Item item = input.getItem();
		if (item == Items.RAW_IRON || item == Items.IRON_ORE || item == Items.DEEPSLATE_IRON_ORE) {
			return new ItemStack(Items.IRON_INGOT, n);
		}
		if (item == Items.RAW_COPPER || item == Items.COPPER_ORE || item == Items.DEEPSLATE_COPPER_ORE) {
			return new ItemStack(Items.COPPER_INGOT, n);
		}
		if (item == Items.RAW_GOLD || item == Items.GOLD_ORE || item == Items.DEEPSLATE_GOLD_ORE || item == Items.NETHER_GOLD_ORE) {
			return new ItemStack(Items.GOLD_INGOT, n);
		}
		if (item == ModBlocks.RUBY_ORE.asItem() || item == ModBlocks.DEEPSLATE_RUBY_ORE.asItem()) {
			return new ItemStack(ModItems.RUBY, n);
		}
		if (item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE) {
			return new ItemStack(Items.GRAVEL);
		}
		if (item == Items.GRAVEL) {
			return new ItemStack(Items.SAND);
		}
		if (item == Items.ANCIENT_DEBRIS) {
			return new ItemStack(Items.NETHERITE_SCRAP, n - 1);
		}
		return ItemStack.EMPTY;
	}

	// ----- Ammo Factory -----

	public record AmmoRecipe(String name, Item metal, int metalCount, Item extra, int extraCount, Item result, int count) {
	}

	public static final List<AmmoRecipe> AMMO = List.of(
			new AmmoRecipe("Light Ammo", Items.COPPER_INGOT, 1, Items.GUNPOWDER, 1, ModItems.LIGHT_AMMO, 24),
			new AmmoRecipe("Heavy Ammo", Items.IRON_INGOT, 1, Items.GUNPOWDER, 1, ModItems.HEAVY_AMMO, 24),
			new AmmoRecipe("Shotgun Shells", Items.IRON_INGOT, 1, Items.GUNPOWDER, 1, ModItems.SHOTGUN_SHELL, 12),
			new AmmoRecipe("Sniper Rounds", Items.GOLD_INGOT, 1, Items.GUNPOWDER, 2, ModItems.SNIPER_ROUND, 8),
			new AmmoRecipe("Rockets", Items.IRON_INGOT, 3, Items.GUNPOWDER, 4, ModItems.ROCKET, 2),
			new AmmoRecipe("Mortar Shells", Items.IRON_INGOT, 2, Items.GUNPOWDER, 3, ModItems.MORTAR_SHELL, 2));

	// ----- Trade Depot prices, in thousandths of a ruby per item -----

	private static final Map<Item, Integer> PRICES = new LinkedHashMap<>();

	static {
		PRICES.put(Items.COBBLESTONE, 2);
		PRICES.put(Items.COBBLED_DEEPSLATE, 2);
		PRICES.put(Items.DIRT, 1);
		PRICES.put(Items.GRAVEL, 3);
		PRICES.put(Items.SAND, 4);
		PRICES.put(Items.COAL, 40);
		PRICES.put(Items.CHARCOAL, 30);
		PRICES.put(Items.RAW_COPPER, 30);
		PRICES.put(Items.COPPER_INGOT, 60);
		PRICES.put(Items.RAW_IRON, 70);
		PRICES.put(Items.IRON_INGOT, 140);
		PRICES.put(Items.RAW_GOLD, 120);
		PRICES.put(Items.GOLD_INGOT, 250);
		PRICES.put(Items.REDSTONE, 30);
		PRICES.put(Items.LAPIS_LAZULI, 40);
		PRICES.put(Items.DIAMOND, 1500);
		PRICES.put(Items.EMERALD, 800);
		PRICES.put(Items.NETHERITE_SCRAP, 4000);
		PRICES.put(Items.WHEAT, 15);
		PRICES.put(Items.CARROT, 10);
		PRICES.put(Items.POTATO, 10);
		PRICES.put(Items.BREAD, 50);
		PRICES.put(Items.ROTTEN_FLESH, 5);
		PRICES.put(Items.BONE, 10);
		PRICES.put(Items.STRING, 10);
		PRICES.put(Items.GUNPOWDER, 40);
		PRICES.put(Items.LEATHER, 30);
		PRICES.put(Items.OAK_LOG, 15);
		PRICES.put(Items.SPRUCE_LOG, 15);
		PRICES.put(Items.BIRCH_LOG, 15);
	}

	/** Thousandths of a ruby for one item at the Trade Depot (Mk I), 0 if it doesn't buy it. */
	public static int price(ItemStack stack) {
		if (stack.is(ModItems.RUBY)) {
			return 1000;
		}
		if (stack.is(net.minecraft.tags.ItemTags.LOGS)) {
			return 15;
		}
		return PRICES.getOrDefault(stack.getItem(), 0);
	}

	/** Better depots get better prices: +6% per tier above Mk I. */
	public static int price(ItemStack stack, int tier) {
		return Math.round(price(stack) * (1.0F + 0.06F * (tier - 1)));
	}
}
