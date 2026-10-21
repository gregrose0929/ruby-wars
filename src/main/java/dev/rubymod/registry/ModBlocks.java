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
			props().mapColor(MapColor.METAL).strength(4.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block RADAR_STATION = register("radar_station",
			p -> new DefenseBlock(DefenseBlock.Kind.RADAR, Shapes.block(), p),
			props().mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block ALARM_BLOCK = register("alarm_block",
			p -> new DefenseBlock(DefenseBlock.Kind.ALARM, Shapes.block(), p),
			props().mapColor(MapColor.COLOR_RED).strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block FLAK_CANNON = register("flak_cannon",
			p -> new DefenseBlock(DefenseBlock.Kind.FLAK, Shapes.block(), p),
			props().mapColor(MapColor.METAL).strength(4.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block SAM_SITE = register("sam_site",
			p -> new DefenseBlock(DefenseBlock.Kind.SAM, Shapes.block(), p),
			props().mapColor(MapColor.METAL).strength(5.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block AIRFIELD_PAD = register("airfield_pad",
			p -> new DefenseBlock(DefenseBlock.Kind.AIRFIELD_PAD, Block.box(0, 0, 0, 16, 2, 16), p),
			props().mapColor(MapColor.COLOR_YELLOW).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block AT_MINE = register("at_mine", dev.rubymod.block.AtMineBlock::new,
			props().mapColor(MapColor.COLOR_GREEN).strength(0.5F).sound(SoundType.METAL).noOcclusion());
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
			props().mapColor(MapColor.METAL).strength(6.0F, 6.0F).sound(SoundType.METAL).noOcclusion());

	// --- Vehicle models (never placed; the vehicle renderers draw these block models) ---
	public static final Block JEEP_MODEL = registerModelOnly("jeep_model");
	public static final Block TANK_MODEL = registerModelOnly("tank_model");
	public static final Block TANK_TURRET_MODEL = registerModelOnly("tank_turret_model");
	public static final Block TANK_BARREL_MODEL = registerModelOnly("tank_barrel_model");
	public static final Block JET_MODEL = registerModelOnly("jet_model");
	public static final Block ATTACK_JET_MODEL = registerModelOnly("attack_jet_model");
	public static final Block BOMBER_MODEL = registerModelOnly("bomber_model");
	public static final Block HELI_MODEL = registerModelOnly("heli_model");
	public static final Block ROCKET_MODEL = registerModelOnly("rocket_model");
	public static final Block TANK_SHELL_MODEL = registerModelOnly("tank_shell_model");
	public static final Block MORTAR_SHELL_MODEL = registerModelOnly("mortar_shell_model");
	public static final Block CRUISE_MISSILE_MODEL = registerModelOnly("cruise_missile_model");
	public static final Block BOMB_MODEL = registerModelOnly("bomb_model");

	/** Bulletproof window: see-through, shrugs off small arms (~30 rifle rounds) and takes two blasts. */
	public static final Block REINFORCED_GLASS = register("reinforced_glass", net.minecraft.world.level.block.TransparentBlock::new,
			props().mapColor(MapColor.NONE).strength(8.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.GLASS).noOcclusion());
	public static final Block CONVEYOR = register("conveyor", dev.rubymod.industry.ConveyorBlock::new,
			props().mapColor(MapColor.COLOR_BLACK).strength(1.5F, 4.0F).sound(SoundType.METAL).noOcclusion());
	/** A bright base light. */
	public static final Block FLOODLIGHT = register("floodlight", p -> new dev.rubymod.block.ShapedBlock(Block.box(3, 0, 3, 13, 14, 13), p),
			props().mapColor(MapColor.METAL).strength(2.0F, 4.0F).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 15));

	// --- Industry (see dev.rubymod.industry) ---
	private static final java.util.Set<Block> MACHINES = new java.util.LinkedHashSet<>();
	public static final Block COMBUSTION_GENERATOR = machine("combustion_generator", dev.rubymod.industry.MachineKind.COMBUSTION_GENERATOR, Shapes.block());
	public static final Block SOLAR_PANEL = machine("solar_panel", dev.rubymod.industry.MachineKind.SOLAR_PANEL, Block.box(0, 0, 0, 16, 6, 16));
	public static final Block BATTERY = machine("battery", dev.rubymod.industry.MachineKind.BATTERY, Shapes.block());
	public static final Block RUBY_DRILL = machine("ruby_drill", dev.rubymod.industry.MachineKind.RUBY_DRILL, Shapes.block());
	public static final Block ORE_REFINERY = machine("ore_refinery", dev.rubymod.industry.MachineKind.ORE_REFINERY, Shapes.block());
	public static final Block AMMO_FACTORY = machine("ammo_factory", dev.rubymod.industry.MachineKind.AMMO_FACTORY, Shapes.block());
	public static final Block TRADE_DEPOT = machine("trade_depot", dev.rubymod.industry.MachineKind.TRADE_DEPOT, Shapes.block());
	public static final Block VAULT = machine("vault", dev.rubymod.industry.MachineKind.VAULT, Shapes.block());
	public static final Block SHIELD_GENERATOR = machine("shield_generator", dev.rubymod.industry.MachineKind.SHIELD_GENERATOR, Shapes.block());
	public static final Block POINT_DEFENSE = machine("point_defense", dev.rubymod.industry.MachineKind.POINT_DEFENSE, Shapes.block());
	public static final Block CONSTRUCTOR = machine("constructor", dev.rubymod.industry.MachineKind.CONSTRUCTOR, Shapes.block());

	private static Block machine(String name, dev.rubymod.industry.MachineKind kind, net.minecraft.world.phys.shapes.VoxelShape shape) {
		float strength = kind == dev.rubymod.industry.MachineKind.VAULT ? 25.0F : 5.0F;
		Block block = register(name, p -> new dev.rubymod.industry.MachineBlock(kind, shape, p),
				props().mapColor(MapColor.METAL).strength(strength, 8.0F).sound(SoundType.METAL).noOcclusion().noLootTable()
						.requiresCorrectToolForDrops(), dev.rubymod.industry.MachineItem::new);
		MACHINES.add(block);
		return block;
	}

	public static java.util.Set<Block> machines() {
		return MACHINES;
	}

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
