package dev.rubymod.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import dev.rubymod.RubyMod;
import dev.rubymod.block.ArmoryBlock;
import dev.rubymod.block.BarbedWireBlock;
import dev.rubymod.block.BlastDoorBlock;
import dev.rubymod.block.ClaymoreBlock;
import dev.rubymod.block.DefenseBlock;
import dev.rubymod.block.LandmineBlock;
import dev.rubymod.block.LauncherBlock;
import dev.rubymod.block.RubyHeartBlock;
import dev.rubymod.item.HeartItem;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.Shapes;

public class ModBlocks {
	/** Every block that has an item, in creative-tab order. */
	public static final List<Block> WITH_ITEMS = new ArrayList<>();

	// --- Rubies ---
	public static final Block RUBY_BLOCK = register("ruby_block", Block::new,
			props().mapColor(MapColor.FIRE).strength(5.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.METAL));
	public static final Block RUBY_ORE = register("ruby_ore", p -> new DropExperienceBlock(UniformInt.of(3, 7), p),
			props().mapColor(MapColor.STONE).strength(3.0F, 3.0F).requiresCorrectToolForDrops());
	public static final Block DEEPSLATE_RUBY_ORE = register("deepslate_ruby_ore", p -> new DropExperienceBlock(UniformInt.of(3, 7), p),
			props().mapColor(MapColor.DEEPSLATE).strength(4.5F, 3.0F).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE));

	// --- Base ---
	public static final Block RUBY_HEART = register("ruby_heart", RubyHeartBlock::new,
			props().mapColor(MapColor.FIRE).strength(3.0F, 3_600_000.0F).sound(SoundType.AMETHYST).lightLevel(s -> 12)
					.pushReaction(PushReaction.IMMOVEABLE),
			HeartItem::new);
	public static final Block ARMORY = register("armory", ArmoryBlock::new,
			props().mapColor(MapColor.METAL).strength(3.5F, 6.0F).sound(SoundType.METAL));

	// --- Fortifications ---
	public static final Block SANDBAGS = register("sandbags", Block::new,
			props().mapColor(MapColor.SAND).strength(1.5F, 4.0F).sound(SoundType.WOOL));
	public static final Block CONCRETE_WALL = register("concrete_wall", Block::new,
			props().mapColor(MapColor.STONE).strength(4.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.STONE));
	public static final Block REINFORCED_CONCRETE = register("reinforced_concrete", Block::new,
			props().mapColor(MapColor.STONE).strength(10.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.STONE));
	public static final Block BLAST_DOOR = register("blast_door", BlastDoorBlock::new,
			props().mapColor(MapColor.METAL).strength(8.0F, 6.0F).requiresCorrectToolForDrops().noOcclusion().pushReaction(PushReaction.IMMOVEABLE),
			DoubleHighBlockItem::new);

	// --- Defenses ---
	public static final Block SENTRY_TURRET = register("sentry_turret",
			p -> new DefenseBlock(DefenseBlock.Kind.SENTRY_TURRET, Block.box(2, 0, 2, 14, 14, 14), p),
			props().mapColor(MapColor.METAL).strength(4.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block MISSILE_INTERCEPTOR = register("missile_interceptor",
			p -> new DefenseBlock(DefenseBlock.Kind.INTERCEPTOR, Shapes.block(), p),
			props().mapColor(MapColor.METAL).strength(4.0F, 6.0F).sound(SoundType.METAL));
	public static final Block RADAR_STATION = register("radar_station",
			p -> new DefenseBlock(DefenseBlock.Kind.RADAR, Shapes.block(), p),
			props().mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL));
	public static final Block ALARM_BLOCK = register("alarm_block",
			p -> new DefenseBlock(DefenseBlock.Kind.ALARM, Shapes.block(), p),
			props().mapColor(MapColor.COLOR_RED).strength(2.0F, 6.0F).sound(SoundType.METAL));
	public static final Block CLAYMORE = register("claymore", ClaymoreBlock::new,
			props().mapColor(MapColor.COLOR_GREEN).strength(0.5F).sound(SoundType.METAL).noOcclusion());
	public static final Block LANDMINE = register("landmine", LandmineBlock::new,
			props().mapColor(MapColor.COLOR_GRAY).strength(0.5F).sound(SoundType.METAL).noOcclusion());
	public static final Block BARBED_WIRE = register("barbed_wire", BarbedWireBlock::new,
			props().mapColor(MapColor.METAL).strength(1.0F).sound(SoundType.CHAIN).noOcclusion().noCollision());

	// --- Heavy weapons ---
	public static final Block MORTAR = register("mortar",
			p -> new LauncherBlock(LauncherBlock.Kind.MORTAR, Block.box(2, 0, 2, 14, 12, 14), p),
			props().mapColor(MapColor.COLOR_GREEN).strength(4.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block MISSILE_SILO = register("missile_silo",
			p -> new LauncherBlock(LauncherBlock.Kind.MISSILE_SILO, Shapes.block(), p),
			props().mapColor(MapColor.METAL).strength(6.0F, 6.0F).sound(SoundType.METAL));

	// --- Vehicle models (never placed; the vehicle renderers draw these block models) ---
	public static final Block JEEP_MODEL = registerModelOnly("jeep_model");
	public static final Block TANK_MODEL = registerModelOnly("tank_model");

	private static Block registerModelOnly(String name) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, RubyMod.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, new Block(props().noOcclusion().noLootTable().setId(key)));
	}

	private static BlockBehaviour.Properties props() {
		return BlockBehaviour.Properties.of();
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		return register(name, factory, properties, BlockItem::new);
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties,
			BiFunction<Block, Item.Properties, Item> itemFactory) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, RubyMod.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, RubyMod.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey, itemFactory.apply(block, new Item.Properties().useBlockDescriptionPrefix().setId(itemKey)));
		WITH_ITEMS.add(block);
		return block;
	}

	public static void initialize() {
	}
}
