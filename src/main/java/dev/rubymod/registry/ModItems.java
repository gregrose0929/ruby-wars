package dev.rubymod.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import dev.rubymod.RubyMod;
import dev.rubymod.gun.AmmoType;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;
import dev.rubymod.gun.GunStats.Special;
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
	public static final Item SERVICE_PISTOL = gun("service_pistol", new GunStats(5.0F, 6, 12, 30, 2.0F, 0.6F, 60, 1, AmmoType.LIGHT, false, 1.5F, 1.0F, 2.0F, 0.85F, Special.NONE));
	public static final Item VIPER_SMG = gun("viper_smg", new GunStats(3.0F, 2, 30, 35, 4.0F, 2.5F, 40, 1, AmmoType.LIGHT, true, 1.5F, 1.0F, 0.8F, 0.9F, Special.NONE));
	public static final Item COMBAT_SHOTGUN = gun("combat_shotgun", new GunStats(3.0F, 16, 6, 50, 7.0F, 5.0F, 20, 8, AmmoType.SHELLS, false, 1.25F, 0.95F, 5.0F, 0.9F, Special.BREACH));
	public static final Item ARR_RIFLE = gun("arr_rifle", new GunStats(5.0F, 3, 30, 40, 2.5F, 0.8F, 90, 1, AmmoType.HEAVY, true, 1.75F, 0.95F, 1.0F, 0.7F, Special.NONE));
	public static final Item LONGSHOT_SNIPER = gun("longshot_sniper", new GunStats(16.0F, 30, 5, 60, 6.0F, 0.0F, 200, 1, AmmoType.SNIPER, false, 2.0F, 0.9F, 6.0F, 0.2F, Special.GLINT));
	public static final Item BULLDOG_LMG = gun("bulldog_lmg", new GunStats(4.0F, 2, 100, 100, 5.0F, 3.0F, 80, 1, AmmoType.HEAVY, true, 1.5F, 0.6F, 0.7F, 0.8F, Special.NONE));
	public static final Item RUBY_BLASTER = gun("ruby_blaster", new GunStats(7.0F, 5, 20, 40, 1.5F, 1.0F, 70, 1, AmmoType.RUBY_CELL, true, 1.5F, 1.0F, 1.2F, 0.8F, Special.BOUNCE));

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
	public static final Item BREACHING_CHARGE = register("breaching_charge", BreachingChargeItem::new, new Item.Properties().stacksTo(8).rarity(Rarity.RARE));

	// --- Vehicles ---
	public static final Item JEEP = register("jeep", p -> new VehicleItem(() -> ModEntities.JEEP, p), new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
	public static final Item TANK = register("tank", p -> new VehicleItem(() -> ModEntities.TANK, p), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

	// --- Tools ---
	public static final Item BUILDERS_WAND = register("builders_wand", BuildersWandItem::new, new Item.Properties().durability(1024));
	public static final Item EXCAVATOR = register("excavator", ExcavatorItem::new, new Item.Properties().pickaxe(ToolMaterial.IRON, 1.0F, -3.0F).durability(750));

	private static Item gun(String name, GunStats stats) {
		return register(name, p -> new GunItem(stats, p), new Item.Properties()
				.stacksTo(1)
				.component(ModComponents.AMMO, stats.magazine())
				.component(DataComponents.USE_EFFECTS, new UseEffects(true, false, stats.moveSpeed())));
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
