package dev.rubymod;

import java.util.function.Function;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public class ModBlocks {
	public static final Block RUBY_BLOCK = register("ruby_block", Block::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.FIRE)
					.strength(5.0f, 6.0f)
					.requiresCorrectToolForDrops()
					.sound(SoundType.METAL));

	public static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, RubyMod.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, RubyMod.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(itemKey)));
		return block;
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS)
				.register(tab -> tab.accept(RUBY_BLOCK.asItem()));
	}
}
