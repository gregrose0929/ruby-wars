package dev.rubymod.registry;

import java.util.Set;

import dev.rubymod.RubyMod;
import dev.rubymod.block.DefenseBlockEntity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class ModBlockEntities {
	public static final BlockEntityType<DefenseBlockEntity> DEFENSE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			RubyMod.id("defense"),
			new BlockEntityType<>(DefenseBlockEntity::new, Set.of(
					ModBlocks.SENTRY_TURRET, ModBlocks.MISSILE_INTERCEPTOR, ModBlocks.RADAR_STATION, ModBlocks.ALARM_BLOCK, ModBlocks.CLAYMORE,
					ModBlocks.FLAK_CANNON, ModBlocks.SAM_SITE, ModBlocks.AIRFIELD_PAD)));

	public static final BlockEntityType<dev.rubymod.industry.MachineBlockEntity> MACHINE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			RubyMod.id("machine"),
			new BlockEntityType<>(dev.rubymod.industry.MachineBlockEntity::new, ModBlocks.machines()));

	public static final BlockEntityType<dev.rubymod.block.GraveBlockEntity> GRAVE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			RubyMod.id("war_grave"),
			new BlockEntityType<>(dev.rubymod.block.GraveBlockEntity::new, Set.of(ModBlocks.WAR_GRAVE)));

	public static final BlockEntityType<dev.rubymod.block.HoloBlockEntity> HOLO = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			RubyMod.id("holo_camo"),
			new BlockEntityType<>(dev.rubymod.block.HoloBlockEntity::new, Set.of(ModBlocks.HOLO_CAMO)));

	public static void initialize() {
	}
}
