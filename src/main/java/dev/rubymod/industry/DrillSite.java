package dev.rubymod.industry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the ground under a Ruby Drill is rich in: real ore near it (a few blocks round and below), and the biome
 * (badlands lean to gold, mountains to emeralds, dripstone caves to copper, the deep dark to diamonds). A rich
 * mineral comes up more often (see {@link Recipes#drill}).
 */
public record DrillSite(Map<Item, Float> strength) {
	public static final DrillSite PLAIN = new DrillSite(Map.of());

	/** How far round the drill it looks, and how far down (and up). */
	private static final int RADIUS = 6;
	private static final int DOWN = 24;
	private static final int UP = 2;

	/** How many ore blocks nearby make a mineral as rich as it gets. */
	private static final Map<Item, Integer> FULL = Map.of(
			Items.COAL, 40, Items.RAW_IRON, 30, Items.RAW_COPPER, 40, Items.RAW_GOLD, 10, Items.REDSTONE, 20,
			Items.LAPIS_LAZULI, 10, Items.DIAMOND, 6, Items.EMERALD, 3);
	private static final int FULL_RUBY = 6;

	public float strength(Item item) {
		return strength.getOrDefault(item, 0.0F);
	}

	/** The richest minerals here (strongest first), at least half-rich. */
	public List<Item> rich() {
		List<Item> out = new ArrayList<>();
		strength.entrySet().stream().filter(e -> e.getValue() >= 0.5F).sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
				.forEach(e -> out.add(e.getKey()));
		return out;
	}

	/** "Rich in iron and gold here", or "Plain rock here". */
	public String describe() {
		List<Item> rich = rich();
		if (rich.isEmpty()) {
			return "Plain rock here";
		}
		String first = name(rich.get(0));
		return rich.size() == 1 ? "Rich in " + first + " here" : "Rich in " + first + " and " + name(rich.get(1)) + " here";
	}

	public static String name(Item item) {
		if (item == Items.RAW_IRON) {
			return "iron";
		}
		if (item == Items.RAW_COPPER) {
			return "copper";
		}
		if (item == Items.RAW_GOLD) {
			return "gold";
		}
		if (item == Items.LAPIS_LAZULI) {
			return "lapis";
		}
		if (item == ModItems.RUBY) {
			return "rubies";
		}
		if (item == Items.EMERALD) {
			return "emeralds";
		}
		if (item == Items.DIAMOND) {
			return "diamonds";
		}
		return item == Items.COAL ? "coal" : item == Items.REDSTONE ? "redstone" : "ore";
	}

	/** What an ore block gives in a drill, or null. */
	public static Item mineral(BlockState state) {
		if (state.is(net.minecraft.world.level.block.Blocks.COAL_ORE) || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_COAL_ORE)) {
			return Items.COAL;
		}
		if (state.is(BlockTags.IRON_ORES)) {
			return Items.RAW_IRON;
		}
		if (state.is(BlockTags.COPPER_ORES)) {
			return Items.RAW_COPPER;
		}
		if (state.is(BlockTags.GOLD_ORES)) {
			return Items.RAW_GOLD;
		}
		if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_ORE) || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_REDSTONE_ORE)) {
			return Items.REDSTONE;
		}
		if (state.is(net.minecraft.world.level.block.Blocks.LAPIS_ORE) || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_LAPIS_ORE)) {
			return Items.LAPIS_LAZULI;
		}
		if (state.is(net.minecraft.world.level.block.Blocks.DIAMOND_ORE) || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE)) {
			return Items.DIAMOND;
		}
		if (state.is(net.minecraft.world.level.block.Blocks.EMERALD_ORE) || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_EMERALD_ORE)) {
			return Items.EMERALD;
		}
		if (state.is(ModBlocks.RUBY_ORE) || state.is(ModBlocks.DEEPSLATE_RUBY_ORE)) {
			return ModItems.RUBY;
		}
		return null;
	}

	/** Look at the ground round a drill at {@code pos}. */
	public static DrillSite scan(ServerLevel level, BlockPos pos) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		int bottom = Math.max(level.getMinY(), pos.getY() - DOWN);
		for (BlockPos p : BlockPos.betweenClosed(pos.getX() - RADIUS, bottom, pos.getZ() - RADIUS, pos.getX() + RADIUS, pos.getY() + UP, pos.getZ() + RADIUS)) {
			if (!level.isLoaded(p)) {
				continue;
			}
			Item item = mineral(level.getBlockState(p));
			if (item != null) {
				counts.merge(item, 1, Integer::sum);
			}
		}
		Map<Item, Float> strength = new LinkedHashMap<>();
		counts.forEach((item, n) -> strength.put(item, Math.min(1.0F, n / (float) (item == ModItems.RUBY ? FULL_RUBY : FULL.getOrDefault(item, 20)))));
		var biome = level.getBiome(pos);
		if (biome.is(BiomeTags.IS_BADLANDS)) {
			strength.merge(Items.RAW_GOLD, 0.6F, Math::max);
		}
		if (biome.is(BiomeTags.IS_MOUNTAIN)) {
			strength.merge(Items.EMERALD, 0.6F, Math::max);
		}
		if (biome.is(Biomes.DRIPSTONE_CAVES)) {
			strength.merge(Items.RAW_COPPER, 0.6F, Math::max);
		}
		if (biome.is(Biomes.DEEP_DARK)) {
			strength.merge(Items.DIAMOND, 0.5F, Math::max);
		}
		return strength.isEmpty() ? PLAIN : new DrillSite(strength);
	}
}
