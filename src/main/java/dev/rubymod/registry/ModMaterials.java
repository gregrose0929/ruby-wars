package dev.rubymod.registry;

import java.util.EnumMap;
import java.util.Map;

import dev.rubymod.RubyMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;

/** Ruby armor and tools sit between iron and diamond. */
public class ModMaterials {
	public static final TagKey<Item> RUBY_REPAIR = TagKey.create(Registries.ITEM, RubyMod.id("ruby_repair_materials"));

	public static final ToolMaterial RUBY_TOOL = new ToolMaterial(BlockTags.INCORRECT_FOR_IRON_TOOL, 900, 7.0F, 2.5F, 14, RUBY_REPAIR);

	public static final ArmorMaterial RUBY_ARMOR = new ArmorMaterial(
			25, defense(2, 6, 7, 2), 12, SoundEvents.ARMOR_EQUIP_DIAMOND, 1.0F, 0.0F, RUBY_REPAIR,
			ResourceKey.create(EquipmentAssets.ROOT_ID, RubyMod.id("ruby")));

	private static Map<ArmorType, Integer> defense(int boots, int legs, int chest, int helmet) {
		Map<ArmorType, Integer> map = new EnumMap<>(ArmorType.class);
		map.put(ArmorType.BOOTS, boots);
		map.put(ArmorType.LEGGINGS, legs);
		map.put(ArmorType.CHESTPLATE, chest);
		map.put(ArmorType.HELMET, helmet);
		map.put(ArmorType.BODY, 8);
		return map;
	}
}
