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

	/** What a drill looks for (its Focus button). */
	public static final int FOCUS_ALL = 0;
	public static final int FOCUS_METALS = 1;
	public static final int FOCUS_GEMS = 2;
	public static final String[] FOCUS_NAMES = {"Everything", "Metals", "Gems"};

	/** Ruby chance per haul by tier, before depth, focus and the ground. */
	public static final float[] RUBY_CHANCE = {0.015F, 0.02F, 0.03F, 0.04F, 0.055F};

	/** One haul. Deeper drills (below Y 0) find twice the rubies. */
	public static ItemStack drill(RandomSource random, int tier, boolean deep) {
		return drill(random, tier, deep, FOCUS_ALL, DrillSite.PLAIN);
	}

	/** The ruby chance for a drill (shown on its screen). */
	public static float rubyChance(int tier, boolean deep, int focus, DrillSite site) {
		return RUBY_CHANCE[tier - 1] * (deep ? 2.0F : 1.0F) * (focus == FOCUS_GEMS ? 1.3F : focus == FOCUS_METALS ? 0.7F : 1.0F)
				* (1.0F + 0.5F * site.strength(ModItems.RUBY));
	}

	/**
	 * One haul. Deeper drills (below Y 0) find twice the rubies; the focus brings more metals or gems (and less of
	 * the rest); ore in the ground around the drill (and some biomes) brings more of that. Now and then a Geode.
	 */
	public static ItemStack drill(RandomSource random, int tier, boolean deep, int focus, DrillSite site) {
		if (random.nextFloat() < rubyChance(tier, deep, focus, site)) {
			return new ItemStack(ModItems.RUBY);
		}
		if (random.nextFloat() < (deep ? 0.003F : 0.0015F)) {
			return new ItemStack(ModItems.GEODE);
		}
		Item[] finds = {Items.DIAMOND, Items.RAW_GOLD, Items.RAW_IRON, Items.RAW_COPPER, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.EMERALD};
		float[] weight = {0.01F + 0.004F * tier, 0.05F + 0.001F * tier, 0.14F - 0.005F * tier, 0.13F, 0.13F, 0.06F, 0.04F, 0.0F};
		float total = 0.44F; // plain rock
		for (int i = 0; i < finds.length; i++) {
			Item item = finds[i];
			boolean metal = item == Items.RAW_GOLD || item == Items.RAW_IRON || item == Items.RAW_COPPER;
			boolean gem = item == Items.DIAMOND || item == Items.EMERALD || item == Items.REDSTONE || item == Items.LAPIS_LAZULI;
			if (focus == FOCUS_METALS) {
				weight[i] *= metal ? 1.6F : 0.4F;
			} else if (focus == FOCUS_GEMS) {
				weight[i] *= gem ? 1.6F : 0.4F;
			}
			float s = site.strength(item);
			weight[i] = item == Items.EMERALD ? weight[i] + 0.006F * s * (focus == FOCUS_GEMS ? 1.6F : focus == FOCUS_METALS ? 0.4F : 1.0F)
					: weight[i] * (1.0F + s);
			total += weight[i];
		}
		float roll = random.nextFloat() * total;
		for (int i = 0; i < finds.length; i++) {
			if (roll < weight[i]) {
				Item item = finds[i];
				return new ItemStack(item, item == Items.REDSTONE || item == Items.LAPIS_LAZULI ? 2 : 1);
			}
			roll -= weight[i];
		}
		return new ItemStack(deep ? Items.COBBLED_DEEPSLATE : Items.COBBLESTONE);
	}

	/** Plain rock a drill can leave behind (its Stone button). */
	public static boolean isRock(ItemStack stack) {
		return stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE);
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

	/** Ore (doubled, as above), or anything a furnace can cook: sand to glass, logs to charcoal, food. */
	public static ItemStack refine(net.minecraft.world.level.Level level, ItemStack input, int tier) {
		ItemStack ore = refine(input, tier);
		if (!ore.isEmpty() || input.isEmpty() || !(level instanceof net.minecraft.server.level.ServerLevel server)) {
			return ore;
		}
		var recipe = server.recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING,
				new net.minecraft.world.item.crafting.SingleRecipeInput(input), server);
		return recipe.map(r -> r.value().assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(input))).orElse(ItemStack.EMPTY);
	}

	/** Coal or charcoal: with sand in the Refinery's second slot, it makes gunpowder. */
	public static boolean isGunpowderFuel(ItemStack stack) {
		return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
	}

	public static boolean isSand(ItemStack stack) {
		return stack.is(Items.SAND) || stack.is(Items.RED_SAND);
	}

	/** Gunpowder from one coal and one sand. */
	public static ItemStack gunpowder(int tier) {
		return new ItemStack(Items.GUNPOWDER, tier >= 4 ? 3 : 2);
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

	// ----- Assembler (a null extra: one ingredient) -----

	public static final List<AmmoRecipe> ASSEMBLY = List.of(
			new AmmoRecipe("Iron Block", Items.IRON_INGOT, 9, null, 0, Items.IRON_BLOCK, 1),
			new AmmoRecipe("Gold Block", Items.GOLD_INGOT, 9, null, 0, Items.GOLD_BLOCK, 1),
			new AmmoRecipe("Copper Block", Items.COPPER_INGOT, 9, null, 0, net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("copper_block")), 1),
			new AmmoRecipe("Coal Block", Items.COAL, 9, null, 0, Items.COAL_BLOCK, 1),
			new AmmoRecipe("Redstone Block", Items.REDSTONE, 9, null, 0, Items.REDSTONE_BLOCK, 1),
			new AmmoRecipe("Lapis Block", Items.LAPIS_LAZULI, 9, null, 0, Items.LAPIS_BLOCK, 1),
			new AmmoRecipe("Diamond Block", Items.DIAMOND, 9, null, 0, Items.DIAMOND_BLOCK, 1),
			new AmmoRecipe("Ruby Block", ModItems.RUBY, 9, null, 0, ModBlocks.RUBY_BLOCK.asItem(), 1),
			new AmmoRecipe("Torches", Items.COAL, 1, Items.STICK, 1, Items.TORCH, 4),
			new AmmoRecipe("Rails", Items.IRON_INGOT, 6, Items.STICK, 1, Items.RAIL, 16),
			new AmmoRecipe("Iron Bars", Items.IRON_INGOT, 6, null, 0, Items.IRON_BARS, 16),
			new AmmoRecipe("Glass Panes", Items.GLASS, 6, null, 0, Items.GLASS_PANE, 16),
			new AmmoRecipe("Stone Bricks", Items.STONE, 4, null, 0, Items.STONE_BRICKS, 4),
			new AmmoRecipe("TNT", Items.GUNPOWDER, 5, Items.SAND, 4, Items.TNT, 1),
			new AmmoRecipe("Bread", Items.WHEAT, 3, null, 0, Items.BREAD, 1));

	/** The recipe list a machine picks from. */
	public static List<AmmoRecipe> recipes(MachineKind kind) {
		return kind == MachineKind.ASSEMBLER ? ASSEMBLY : AMMO;
	}

	public static AmmoRecipe recipe(MachineKind kind, int index) {
		List<AmmoRecipe> list = recipes(kind);
		return list.get(Math.floorMod(index, list.size()));
	}

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
		// Storage blocks: nine of what they're made of.
		PRICES.put(Items.COAL_BLOCK, 360);
		PRICES.put(Items.RAW_COPPER_BLOCK, 270);
		PRICES.put(Items.RAW_IRON_BLOCK, 630);
		PRICES.put(Items.IRON_BLOCK, 1260);
		PRICES.put(Items.RAW_GOLD_BLOCK, 1080);
		PRICES.put(Items.GOLD_BLOCK, 2250);
		PRICES.put(Items.REDSTONE_BLOCK, 270);
		PRICES.put(Items.LAPIS_BLOCK, 360);
		PRICES.put(Items.DIAMOND_BLOCK, 13500);
		PRICES.put(Items.EMERALD_BLOCK, 7200);
		PRICES.put(Items.NETHERITE_INGOT, 18000);
		PRICES.put(Items.QUARTZ, 40);
		PRICES.put(Items.AMETHYST_SHARD, 30);
		PRICES.put(Items.STONE, 3);
		PRICES.put(Items.DEEPSLATE, 3);
		PRICES.put(Items.OAK_PLANKS, 4);
		PRICES.put(Items.SPRUCE_PLANKS, 4);
		PRICES.put(Items.BIRCH_PLANKS, 4);
	}

	/** Anything not on the list still sells: a little for everyday things, more for rarer ones. */
	private static int fallback(ItemStack stack) {
		net.minecraft.world.item.Rarity rarity = stack.getRarity();
		return switch (rarity) {
			case UNCOMMON -> 40;
			case RARE -> 150;
			case EPIC -> 600;
			default -> 3;
		};
	}

	/** Thousandths of a ruby for one item at the Trade Depot (Mk I). It buys anything. */
	public static int price(ItemStack stack) {
		if (stack.is(ModItems.RUBY)) {
			return 1000;
		}
		if (stack.is(net.minecraft.tags.ItemTags.LOGS)) {
			return 15;
		}
		if (stack.isEmpty()) {
			return 0;
		}
		Integer listed = PRICES.get(stack.getItem());
		return listed != null ? listed : fallback(stack);
	}

	/** Better depots get better prices: +6% per tier above Mk I. */
	public static int price(ItemStack stack, int tier) {
		if (stack.is(ModItems.RUBY)) {
			return 1000; // Rubies are always worth a ruby.
		}
		double rate = dev.rubymod.game.GameConfig.get().tradeDepotPriceMultiplier;
		int base = price(stack);
		// Everything sells for something (at least a thousandth of a ruby).
		return base <= 0 ? 0 : Math.max(1, (int) Math.round(base * rate * (1.0 + 0.06 * (tier - 1))));
	}
}
