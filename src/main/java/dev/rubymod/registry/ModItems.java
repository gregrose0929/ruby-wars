package dev.rubymod.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import dev.rubymod.RubyMod;
import dev.rubymod.gun.AmmoType;
import dev.rubymod.gun.Attachment;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;
import dev.rubymod.gun.GunStats.Special;
import dev.rubymod.item.AttachmentItem;
import dev.rubymod.item.BreachingChargeItem;
import dev.rubymod.item.BuildersWandItem;
import dev.rubymod.item.ExcavatorItem;
import dev.rubymod.item.RangefinderItem;
import dev.rubymod.item.RocketLauncherItem;
import dev.rubymod.item.ThrowableWeaponItem;
import dev.rubymod.item.VehicleItem;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.equipment.ArmorType;

public class ModItems {
	/** Every item in creative-tab order. */
	public static final List<Item> ALL = new ArrayList<>();

	public static final Item RUBY = register("ruby", Item::new, new Item.Properties());

	// --- Ammo ---
	public static final Item LIGHT_AMMO = register("light_ammo", Item::new, new Item.Properties());
	public static final Item HEAVY_AMMO = register("heavy_ammo", Item::new, new Item.Properties());
	public static final Item SHOTGUN_SHELL = register("shotgun_shell", Item::new, new Item.Properties());
	public static final Item SNIPER_ROUND = register("sniper_round", Item::new, new Item.Properties());
	public static final Item RUBY_CELL = register("ruby_cell", Item::new, new Item.Properties());

	// --- Guns ---
	//                                                                    dmg  rate mag reload hip  aim  range pel ammo          auto  head  speed recoil zoom special
	public static final Item SERVICE_PISTOL = gun("service_pistol", new GunStats(5.0F, 6, 12, 30, 2.0F, 0.6F, 60, 1, AmmoType.LIGHT, false, 1.5F, 0.8F, 2.0F, 0.85F, Special.NONE));
	public static final Item COMBAT_SHOTGUN = gun("combat_shotgun", new GunStats(3.0F, 16, 6, 50, 7.0F, 5.0F, 20, 8, AmmoType.SHELLS, false, 1.25F, 0.7F, 5.0F, 0.9F, Special.BREACH));
	public static final Item ARR_RIFLE = gun("arr_rifle", new GunStats(5.0F, 3, 30, 40, 2.5F, 0.8F, 90, 1, AmmoType.HEAVY, true, 1.75F, 0.65F, 1.0F, 0.55F, Special.NONE));
	public static final Item LONGSHOT_SNIPER = gun("longshot_sniper", new GunStats(16.0F, 30, 5, 60, 6.0F, 0.0F, 512, 1, AmmoType.SNIPER, false, 2.0F, 0.35F, 6.0F, 0.2F, Special.GLINT));
	public static final Item RUBY_BLASTER = gun("ruby_blaster", new GunStats(7.0F, 5, 20, 40, 1.5F, 1.0F, 70, 1, AmmoType.RUBY_CELL, true, 1.5F, 0.7F, 1.2F, 0.8F, Special.BOUNCE));

	// --- Throwables and raid gear ---
	public static final Item FRAG_GRENADE = register("frag_grenade", p -> new ThrowableWeaponItem(ThrowableWeaponItem.Kind.FRAG, p), new Item.Properties().stacksTo(16));
	public static final Item SMOKE_GRENADE = register("smoke_grenade", p -> new ThrowableWeaponItem(ThrowableWeaponItem.Kind.SMOKE, p), new Item.Properties().stacksTo(16));
	public static final Item AIR_STRIKE_FLARE = register("air_strike_flare", p -> new ThrowableWeaponItem(ThrowableWeaponItem.Kind.AIR_STRIKE, p),
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item ROCKET_LAUNCHER = register("rocket_launcher", RocketLauncherItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
	public static final Item ROCKET = register("rocket", Item::new, new Item.Properties().stacksTo(16));
	public static final Item MORTAR_SHELL = register("mortar_shell", Item::new, new Item.Properties().stacksTo(16));
	public static final Item CRUISE_MISSILE = register("cruise_missile", Item::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item RANGEFINDER = register("rangefinder", RangefinderItem::new, new Item.Properties().stacksTo(1));
	public static final Item HEAVY_AA_GUN = register("heavy_aa_gun", p -> new VehicleItem(() -> ModEntities.HEAVY_AA_GUN, p),
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item RAILGUN = register("railgun", p -> new VehicleItem(() -> ModEntities.RAILGUN, p),
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item ARCHITECT_WAND = register("architect_wand", dev.rubymod.item.ArchitectWandItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
	public static final Item REMODELER = register("remodeler", dev.rubymod.item.RemodelerItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
	public static final Item BLUEPRINT = register("blueprint", dev.rubymod.item.BlueprintItem::new, new Item.Properties().stacksTo(1));
	public static final Item GUARD_CONTRACT = register("guard_contract", p -> new dev.rubymod.item.GuardContractItem(0, p),
			new Item.Properties().stacksTo(4).rarity(Rarity.UNCOMMON));
	public static final Item AT_GUARD_CONTRACT = register("at_guard_contract", p -> new dev.rubymod.item.GuardContractItem(1, p),
			new Item.Properties().stacksTo(4).rarity(Rarity.RARE));
	public static final Item RECON_DRONE = register("recon_drone", dev.rubymod.item.ReconDroneItem::new, new Item.Properties().stacksTo(4));
	public static final Item PAINT_KIT = register("paint_kit", dev.rubymod.item.PaintKitItem::new, new Item.Properties().stacksTo(1));
	public static final Item FIELD_RADIO = register("field_radio", dev.rubymod.item.FieldRadioItem::new, new Item.Properties().stacksTo(1));
	public static final Item FIELD_PACK = register("field_pack", p -> new dev.rubymod.item.BackpackItem(3, p), new Item.Properties().stacksTo(1));
	public static final Item HEAVY_RUCKSACK = register("heavy_rucksack", p -> new dev.rubymod.item.BackpackItem(6, p),
			new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
	public static final Item SKYHAMMER = register("skyhammer",
			p -> new dev.rubymod.item.LockOnLauncherItem(dev.rubymod.item.LockOnLauncherItem.Kind.ANTI_AIR, p),
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item LANCER = register("lancer",
			p -> new dev.rubymod.item.LockOnLauncherItem(dev.rubymod.item.LockOnLauncherItem.Kind.ANTI_TANK, p),
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item AA_MISSILE = register("aa_missile", Item::new, new Item.Properties().stacksTo(8));
	public static final Item AT_MISSILE = register("at_missile", Item::new, new Item.Properties().stacksTo(8));
	public static final Item C4 = register("c4", dev.rubymod.item.C4Item::new, new Item.Properties().stacksTo(8).rarity(Rarity.RARE));
	public static final Item BREACHING_CHARGE = register("breaching_charge", BreachingChargeItem::new, new Item.Properties().stacksTo(8).rarity(Rarity.RARE));

	// --- Gun attachments ---
	public static final Item EXTENDED_MAG = register("extended_mag", p -> new AttachmentItem(Attachment.EXTENDED_MAG, p), new Item.Properties().stacksTo(16));
	public static final Item RED_DOT_SIGHT = register("red_dot_sight", p -> new AttachmentItem(Attachment.RED_DOT, p), new Item.Properties().stacksTo(16));
	public static final Item VERTICAL_GRIP = register("vertical_grip", p -> new AttachmentItem(Attachment.GRIP, p), new Item.Properties().stacksTo(16));
	public static final Item SUPPRESSOR = register("suppressor", p -> new AttachmentItem(Attachment.SUPPRESSOR, p), new Item.Properties().stacksTo(16));

	// --- Vehicles ---
	public static final Item JEEP = register("jeep", p -> new VehicleItem(() -> ModEntities.JEEP, p), new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
	public static final Item TANK = register("tank", p -> new VehicleItem(() -> ModEntities.TANK, p), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item FIGHTER_JET = register("fighter_jet", p -> new VehicleItem(() -> ModEntities.FIGHTER_JET, p), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item ATTACK_JET = register("attack_jet", p -> new VehicleItem(() -> ModEntities.ATTACK_JET, p), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item ATTACK_HELI = register("attack_heli", p -> new VehicleItem(() -> ModEntities.ATTACK_HELI, p), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item BOMBER = register("bomber", p -> new VehicleItem(() -> ModEntities.BOMBER, p), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

	// --- Ruby gear (between iron and diamond) ---
	public static final Item RUBY_HELMET = register("ruby_helmet", Item::new,
			new Item.Properties().humanoidArmor(ModMaterials.RUBY_ARMOR, ArmorType.HELMET));
	public static final Item RUBY_CHESTPLATE = register("ruby_chestplate", Item::new,
			new Item.Properties().humanoidArmor(ModMaterials.RUBY_ARMOR, ArmorType.CHESTPLATE));
	public static final Item RUBY_LEGGINGS = register("ruby_leggings", Item::new,
			new Item.Properties().humanoidArmor(ModMaterials.RUBY_ARMOR, ArmorType.LEGGINGS));
	public static final Item RUBY_BOOTS = register("ruby_boots", Item::new,
			new Item.Properties().humanoidArmor(ModMaterials.RUBY_ARMOR, ArmorType.BOOTS));
	public static final Item RUBY_SWORD = register("ruby_sword", Item::new,
			new Item.Properties().sword(ModMaterials.RUBY_TOOL, 3.0F, -2.4F));
	public static final Item RUBY_PICKAXE = register("ruby_pickaxe", Item::new,
			new Item.Properties().pickaxe(ModMaterials.RUBY_TOOL, 1.0F, -2.8F));

	// --- Tools ---
	public static final Item BUILDERS_WAND = register("builders_wand", BuildersWandItem::new, new Item.Properties().durability(1024));
	public static final Item EXCAVATOR = register("excavator", ExcavatorItem::new, new Item.Properties().pickaxe(ToolMaterial.IRON, 1.0F, -3.0F).durability(750));

	private static Item gun(String name, GunStats stats) {
		return register(name, p -> new GunItem(stats, p), new Item.Properties()
				.stacksTo(1)
				.component(ModComponents.AMMO, stats.magazine())
				.component(DataComponents.USE_EFFECTS, new UseEffects(false, false, stats.moveSpeed())));
	}

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, RubyMod.id(name));
		Item item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
		ALL.add(item);
		return item;
	}

	public static void initialize() {
	}
}
