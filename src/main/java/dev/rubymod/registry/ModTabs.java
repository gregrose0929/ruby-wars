package dev.rubymod.registry;

import dev.rubymod.RubyMod;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** The "Ruby Wars" creative tab with everything in the mod. */
public class ModTabs {
	public static final CreativeModeTab RUBY_WARS = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, RubyMod.id("ruby_wars"),
			FabricCreativeModeTab.builder()
					.title(Component.translatable("itemGroup.rubymod.ruby_wars"))
					.icon(() -> new ItemStack(ModItems.ARR_RIFLE))
					.displayItems((params, output) -> {
						for (Item item : ModItems.ALL) {
							output.accept(item);
						}
						for (Block block : ModBlocks.WITH_ITEMS) {
							output.accept(block);
						}
					})
					.build());

	public static void initialize() {
	}
}
