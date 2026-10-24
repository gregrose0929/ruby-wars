package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.rubymod.gun.GunItem;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/**
 * The Horde Armory: what the team can buy with the shared team bank (rubies from kills
 * and cleared waves). Only Ruby Wars gear that makes sense against a horde: guns,
 * turrets, mines, walls, power, guards, vehicles. Better things unlock as the waves go
 * on, so every game builds up from a few sandbags to a fortress.
 */
public final class HordeShop {
	public enum Category {
		WEAPONS("Weapons"), AMMO("Ammo & Gear"), BUILD("Build"), DEFENSES("Defenses"), POWER("Power"), SUPPORT("Support");

		public final String title;

		Category(String title) {
			this.title = title;
		}
	}

	/** One thing for sale: costs {@code price} rubies, unlocks once {@code wave} waves are cleared. */
	public record Entry(String id, Category category, String name, String icon, int price, int wave, String about, Consumer<ServerPlayer> give) {
	}

	public static final List<Entry> CATALOG = List.of(
			// --- Weapons ---
			new Entry("rifle", Category.WEAPONS, "AR-R Rifle", "rubymod:arr_rifle", 50, 0, "Automatic rifle (Heavy Ammo)",
					p -> give(p, gun(ModItems.ARR_RIFLE), stack(ModItems.HEAVY_AMMO, 64))),
			new Entry("shotgun", Category.WEAPONS, "Combat Shotgun", "rubymod:combat_shotgun", 50, 0, "Huge damage up close (Shotgun Shells)",
					p -> give(p, gun(ModItems.COMBAT_SHOTGUN), stack(ModItems.SHOTGUN_SHELL, 32))),
			new Entry("sword", Category.WEAPONS, "Ruby Sword", "rubymod:ruby_sword", 20, 0, "For when they get too close",
					p -> give(p, stack(ModItems.RUBY_SWORD, 1))),
			new Entry("grenades", Category.WEAPONS, "4 Frag Grenades", "rubymod:frag_grenade", 30, 0, "Throw into a crowd",
					p -> give(p, stack(ModItems.FRAG_GRENADE, 4))),
			new Entry("sniper", Category.WEAPONS, "Longshot Sniper", "rubymod:longshot_sniper", 90, 2, "Picks off enemies far away (Sniper Rounds)",
					p -> give(p, gun(ModItems.LONGSHOT_SNIPER), stack(ModItems.SNIPER_ROUND, 16))),
			new Entry("c4", Category.WEAPONS, "3 C4 Charges", "rubymod:c4", 60, 3, "Stick them on the path; sneak + right-click to blow",
					p -> give(p, stack(ModItems.C4, 3))),
			new Entry("rockets", Category.WEAPONS, "Rocket Launcher", "rubymod:rocket_launcher", 140, 4, "Blows up crowds and Brutes (Rockets)",
					p -> give(p, stack(ModItems.ROCKET_LAUNCHER, 1), stack(ModItems.ROCKET, 6))),
			new Entry("blaster", Category.WEAPONS, "Ruby Blaster", "rubymod:ruby_blaster", 180, 6, "Bouncing energy shots (Ruby Cells)",
					p -> give(p, gun(ModItems.RUBY_BLASTER), stack(ModItems.RUBY_CELL, 64))),
			new Entry("skyhammer", Category.WEAPONS, "Skyhammer", "rubymod:skyhammer", 150, 6, "Lock-on missiles for the enemy gunship",
					p -> give(p, stack(ModItems.SKYHAMMER, 1), stack(ModItems.AA_MISSILE, 4))),
			new Entry("lancer", Category.WEAPONS, "Lancer", "rubymod:lancer", 220, 7, "Guided missiles for Brutes and Juggernauts",
					p -> give(p, stack(ModItems.LANCER, 1), stack(ModItems.AT_MISSILE, 4))),
			new Entry("airstrike", Category.WEAPONS, "Air Strike Flare", "rubymod:air_strike_flare", 250, 8, "Throw it: a jet carpet-bombs the spot",
					p -> give(p, stack(ModItems.AIR_STRIKE_FLARE, 1))),
			// --- Ammo & gear ---
			new Entry("light", Category.AMMO, "64 Light Ammo", "rubymod:light_ammo", 10, 0, "For the Service Pistol", p -> give(p, stack(ModItems.LIGHT_AMMO, 64))),
			new Entry("heavy", Category.AMMO, "64 Heavy Ammo", "rubymod:heavy_ammo", 15, 0, "For the rifle, sentry turrets and flak",
					p -> give(p, stack(ModItems.HEAVY_AMMO, 64))),
			new Entry("shells", Category.AMMO, "32 Shotgun Shells", "rubymod:shotgun_shell", 12, 0, "For the Combat Shotgun",
					p -> give(p, stack(ModItems.SHOTGUN_SHELL, 32))),
			new Entry("apples", Category.AMMO, "3 Golden Apples", "minecraft:golden_apple", 25, 0, "Eat one to heal fast", p -> give(p, stack(Items.GOLDEN_APPLE, 3))),
			new Entry("snipe_ammo", Category.AMMO, "16 Sniper Rounds", "rubymod:sniper_round", 15, 2, "For the Longshot", p -> give(p, stack(ModItems.SNIPER_ROUND, 16))),
			new Entry("ruby_armor", Category.AMMO, "Ruby Armor", "rubymod:ruby_chestplate", 150, 3, "A full set, tougher than iron", HordeShop::rubyArmor),
			new Entry("rocket_ammo", Category.AMMO, "6 Rockets", "rubymod:rocket", 40, 4, "For the Rocket Launcher", p -> give(p, stack(ModItems.ROCKET, 6))),
			new Entry("rangefinder", Category.AMMO, "Rangefinder", "rubymod:rangefinder", 20, 4, "Marks a spot for the mortar", p -> give(p, stack(ModItems.RANGEFINDER, 1))),
			new Entry("mortar_shells", Category.AMMO, "8 Mortar Shells", "rubymod:mortar_shell", 40, 4, "For the Mortar", p -> give(p, stack(ModItems.MORTAR_SHELL, 8))),
			new Entry("jetpack", Category.AMMO, "Jetpack", "rubymod:jetpack", 200, 5, "Wear it, hold Space in the air to fly", p -> give(p, stack(ModItems.JETPACK, 1))),
			new Entry("cells", Category.AMMO, "64 Ruby Cells", "rubymod:ruby_cell", 20, 6, "For the Ruby Blaster", p -> give(p, stack(ModItems.RUBY_CELL, 64))),
			new Entry("aa_missiles", Category.AMMO, "4 AA Missiles", "rubymod:aa_missile", 40, 6, "For the Skyhammer", p -> give(p, stack(ModItems.AA_MISSILE, 4))),
			new Entry("at_missiles", Category.AMMO, "4 AT Missiles", "rubymod:at_missile", 50, 7, "For the Lancer", p -> give(p, stack(ModItems.AT_MISSILE, 4))),
			// --- Build ---
			new Entry("sandbags", Category.BUILD, "32 Sandbags", "rubymod:sandbags", 15, 0, "Quick, cheap cover", p -> give(p, stack(ModBlocks.SANDBAGS, 32))),
			new Entry("planks", Category.BUILD, "64 Planks", "minecraft:oak_planks", 10, 0, "Towers, floors and walkways", p -> give(p, stack(Items.OAK_PLANKS, 64))),
			new Entry("ladders", Category.BUILD, "16 Ladders", "minecraft:ladder", 8, 0, "Up onto your walls", p -> give(p, stack(Items.LADDER, 16))),
			new Entry("walls", Category.BUILD, "32 Concrete Walls", "rubymod:concrete_wall", 40, 1, "Strong walls: monsters take a while to smash them",
					p -> give(p, stack(ModBlocks.CONCRETE_WALL, 32))),
			new Entry("wire", Category.BUILD, "16 Barbed Wire", "rubymod:barbed_wire", 30, 1, "Slows and cuts monsters walking through",
					p -> give(p, stack(ModBlocks.BARBED_WIRE, 16))),
			new Entry("floodlights", Category.BUILD, "2 Floodlights", "rubymod:floodlight", 20, 1, "Light up the night", p -> give(p, stack(ModBlocks.FLOODLIGHT, 2))),
			new Entry("glass", Category.BUILD, "16 Reinforced Glass", "rubymod:reinforced_glass", 30, 2, "Tough windows to shoot through... and see through",
					p -> give(p, stack(ModBlocks.REINFORCED_GLASS, 16))),
			new Entry("door", Category.BUILD, "Blast Door", "rubymod:blast_door", 50, 3, "A steel door in your wall (anyone on the team can open it)",
					p -> give(p, stack(ModBlocks.BLAST_DOOR, 1))),
			new Entry("reinforced", Category.BUILD, "16 Reinforced Concrete", "rubymod:reinforced_concrete", 70, 4, "The toughest wall there is",
					p -> give(p, stack(ModBlocks.REINFORCED_CONCRETE, 16))),
			// --- Defenses ---
			new Entry("turret", Category.DEFENSES, "Sentry Turret", "rubymod:sentry_turret", 80, 0, "Shoots enemies by itself; load it with Heavy Ammo",
					p -> give(p, stack(ModBlocks.SENTRY_TURRET, 1), stack(ModItems.HEAVY_AMMO, 64))),
			new Entry("landmines", Category.DEFENSES, "4 Landmines", "rubymod:landmine", 40, 2, "Blow up when a monster steps on one",
					p -> give(p, stack(ModBlocks.LANDMINE, 4))),
			new Entry("claymores", Category.DEFENSES, "3 Claymores", "rubymod:claymore", 45, 2, "Face them at the path: they blast whatever walks in front",
					p -> give(p, stack(ModBlocks.CLAYMORE, 3))),
			new Entry("tesla", Category.DEFENSES, "Tesla Coil", "rubymod:tesla_coil", 150, 3, "Zaps nearby enemies with lightning (needs power)",
					p -> give(p, stack(ModBlocks.TESLA_COIL, 1))),
			new Entry("mortar", Category.DEFENSES, "Mortar", "rubymod:mortar", 120, 4, "Artillery: mark a spot 40+ blocks away with a Rangefinder",
					p -> give(p, stack(ModBlocks.MORTAR, 1), stack(ModItems.MORTAR_SHELL, 8), stack(ModItems.RANGEFINDER, 1))),
			new Entry("at_mines", Category.DEFENSES, "2 AT Mines", "rubymod:at_mine", 60, 5, "A huge blast for Brutes", p -> give(p, stack(ModBlocks.AT_MINE, 2))),
			new Entry("flak", Category.DEFENSES, "Flak Cannon", "rubymod:flak_cannon", 160, 6, "Shoots down the gunship; load with Heavy Ammo",
					p -> give(p, stack(ModBlocks.FLAK_CANNON, 1), stack(ModItems.HEAVY_AMMO, 64))),
			new Entry("sam", Category.DEFENSES, "SAM Site", "rubymod:sam_site", 200, 6, "Missiles at the gunship; recharge with Rubies",
					p -> give(p, stack(ModBlocks.SAM_SITE, 1), stack(ModItems.RUBY, 4))),
			new Entry("point_defense", Category.DEFENSES, "Point Defense Gun", "rubymod:point_defense", 180, 6, "Shreds incoming rockets (needs power)",
					p -> give(p, stack(ModBlocks.POINT_DEFENSE, 1))),
			new Entry("shield", Category.DEFENSES, "Shield Generator", "rubymod:shield_generator", 250, 8, "A dome that stops rockets and bombs (needs power)",
					p -> give(p, stack(ModBlocks.SHIELD_GENERATOR, 1))),
			// --- Power (one grid for the whole team) ---
			new Entry("solar", Category.POWER, "Solar Panel", "rubymod:solar_panel", 60, 0, "Free power while the sun's up", p -> give(p, stack(ModBlocks.SOLAR_PANEL, 1))),
			new Entry("generator", Category.POWER, "Combustion Generator", "rubymod:combustion_generator", 50, 2, "Burns coal for power (comes with 32 coal)",
					p -> give(p, stack(ModBlocks.COMBUSTION_GENERATOR, 1), stack(Items.COAL, 32))),
			new Entry("coal", Category.POWER, "32 Coal", "minecraft:coal", 12, 2, "Fuel for the generator", p -> give(p, stack(Items.COAL, 32))),
			new Entry("battery", Category.POWER, "Battery Bank", "rubymod:battery", 80, 3, "Stores more power for the night", p -> give(p, stack(ModBlocks.BATTERY, 1))),
			new Entry("rubies", Category.POWER, "4 Rubies", "rubymod:ruby", 30, 6, "Recharge SAM Sites", p -> give(p, stack(ModItems.RUBY, 4))),
			// --- Support ---
			new Entry("repair", Category.SUPPORT, "Repair the Core", "rubymod:ruby_block", 60, 0, "Heals the Ruby Core by 40%", p -> Horde.repairCore(p, 0.4)),
			new Entry("guard", Category.SUPPORT, "Hire a Rifleman", "rubymod:guard_contract", 120, 3, "A soldier who fights for the team (right-click the ground)",
					p -> give(p, stack(ModItems.GUARD_CONTRACT, 1))),
			new Entry("core_up", Category.SUPPORT, "Reinforce the Core", "minecraft:beacon", 150, 4, "+100 Core health, for good (up to 3 times)",
					p -> Horde.reinforceCore(p)),
			new Entry("jeep", Category.SUPPORT, "Jeep", "rubymod:jeep", 150, 5, "Get around the battlefield fast", p -> give(p, stack(ModItems.JEEP, 1))),
			new Entry("at_guard", Category.SUPPORT, "Hire an AT Gunner", "rubymod:at_guard_contract", 160, 6, "A soldier with rockets, for the big ones",
					p -> give(p, stack(ModItems.AT_GUARD_CONTRACT, 1))),
			new Entry("tank", Category.SUPPORT, "Tank", "rubymod:tank", 400, 9, "The biggest gun there is", p -> give(p, stack(ModItems.TANK, 1))));

	private HordeShop() {
	}

	private static ItemStack stack(ItemLike item, int count) {
		return new ItemStack(item, count);
	}

	private static ItemStack gun(Item item) {
		ItemStack stack = new ItemStack(item);
		if (item instanceof GunItem g) {
			stack.set(ModComponents.AMMO, g.stats().magazine());
		}
		return stack;
	}

	private static void give(ServerPlayer player, ItemStack... stacks) {
		for (ItemStack stack : stacks) {
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
		player.inventoryMenu.broadcastChanges();
	}

	private static void rubyArmor(ServerPlayer player) {
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.RUBY_HELMET));
		if (player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.JETPACK)) {
			give(player, new ItemStack(ModItems.RUBY_CHESTPLATE));
		} else {
			player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.RUBY_CHESTPLATE));
		}
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.RUBY_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.RUBY_BOOTS));
	}

	public static Entry byId(String id) {
		for (Entry e : CATALOG) {
			if (e.id().equals(id)) {
				return e;
			}
		}
		return null;
	}

	/** Buy something from the team bank. Returns an error, or null. */
	public static String buy(ServerPlayer player, String id) {
		if (!Horde.playing(player)) {
			return "The Horde Armory is only for Co-op Horde.";
		}
		Entry entry = byId(id);
		if (entry == null) {
			return "That's not for sale.";
		}
		if (Horde.cleared() < entry.wave()) {
			return entry.name() + " unlocks after wave " + entry.wave() + ".";
		}
		String blocked = Horde.canBuy(entry.id());
		if (blocked != null) {
			return blocked;
		}
		if (!Horde.spend(entry.price())) {
			return "The team needs " + entry.price() + " rubies for that (it has " + Horde.bank() + ").";
		}
		entry.give().accept(player);
		player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.6F, 1.2F);
		Horde.tellAll(player.level().getServer(), Component.literal(player.getName().getString() + " bought " + entry.name() + " (-" + entry.price()
				+ " rubies, " + Horde.bank() + " left)").withStyle(ChatFormatting.AQUA));
		syncAll(player.level().getServer(), false);
		return null;
	}

	/** "id|category|name|icon|price|wave|about" per entry, for the shop screen. */
	public static List<String> lines() {
		List<String> lines = new ArrayList<>();
		for (Entry e : CATALOG) {
			lines.add(e.id() + "|" + e.category().name() + "|" + e.name() + "|" + e.icon() + "|" + e.price() + "|" + e.wave() + "|" + e.about());
		}
		return lines;
	}

	public static void open(ServerPlayer player) {
		Net.send(player, new MorePayloads.HordeShopSync(true, Horde.bank(), Horde.cleared(), lines()));
	}

	/** Refresh everyone's shop screen (the bank changed). */
	public static void syncAll(net.minecraft.server.MinecraftServer server, boolean open) {
		for (ServerPlayer p : Horde.onlinePlayers(server)) {
			Net.send(p, new MorePayloads.HordeShopSync(open, Horde.bank(), Horde.cleared(), lines()));
		}
	}
}
