package dev.rubymod;

import dev.rubymod.command.RubyWarsCommand;
import dev.rubymod.game.GameConfig;
import dev.rubymod.game.GameEvents;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModBlockEntities;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;
import dev.rubymod.registry.ModTabs;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.GenerationStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ruby Wars: a two-week build-then-battle season mod. */
public class RubyMod implements ModInitializer {
	public static final String MOD_ID = "rubymod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModComponents.initialize();
		ModItems.initialize();
		ModBlocks.initialize();
		ModBlockEntities.initialize();
		dev.rubymod.registry.ModMenus.initialize();
		ModEntities.initialize();
		ModTabs.initialize();
		Payloads.register();
		GameConfig.load();
		GameEvents.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> RubyWarsCommand.register(dispatcher));

		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.UNDERGROUND_ORES,
				ResourceKey.create(Registries.PLACED_FEATURE, id("ore_ruby")));

		LOGGER.info("Ruby Wars loaded!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
