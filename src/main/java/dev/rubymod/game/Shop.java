package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.rubymod.gun.GunItem;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

/**
 * The Armory: a chest-style menu where every slot is something to buy.
 * It's all server-side, so it needs no custom screen on the client.
 */
public final class Shop {
	private static final int SLOTS = 54;

	/**
	 * One thing for sale.
	 *
	 * @param slot   where it sits in the 9x6 grid
	 * @param price  cost in rubies (overridable in config)
	 * @param stock  what the buyer gets
	 * @param action special purchase (like a Heart upgrade); returns an error message or null
	 */
	private record Entry(int page, int slot, int price, Supplier<ItemStack> stock, String name, Function<ServerPlayer, String> action, String section) {
	}

	/** A card on a page, for the screen: which slot, and the section it's under. */
	public record Card(int slot, String section) {
	}

	/** The Armory's tabs, each a kind of thing (built with the catalogue below). */
	private static final List<String> PAGE_NAMES = new ArrayList<>();
	private static int page;
	private static int nextSlot;
	private static String section = "";

	private static List<Entry> entries;

	private Shop() {
	}

	private static List<Entry> entries() {
		if (entries == null) {
			entries = new ArrayList<>();
			PAGE_NAMES.clear();
			page = -1;
			cat("Your Heart");
			section("Upgrade your Heart");
			entries.add(new Entry(page, nextSlot++, 0, () -> named(Items.NETHER_STAR, "Upgrade Ruby Heart"), "heart_upgrade", Claims::upgrade, section));
			section("Another Armory");
			item(ModBlocks.ARMORY, 1, 4);

			cat("Guns");
			section("Guns");
			gun(ModItems.SERVICE_PISTOL, 2);
			gun(ModItems.ARR_RIFLE, 15);
			gun(ModItems.COMBAT_SHOTGUN, 12);
			gun(ModItems.LONGSHOT_SNIPER, 25);
			gun(ModItems.RUBY_BLASTER, 30);
			item(ModItems.FLAMETHROWER, 1, 25);
			section("Attachments");
			item(ModItems.EXTENDED_MAG, 1, 6);
			item(ModItems.RED_DOT_SIGHT, 1, 5);
			item(ModItems.VERTICAL_GRIP, 1, 5);
			item(ModItems.SUPPRESSOR, 1, 8);

			cat("Ammo");
			section("For guns");
			item(ModItems.LIGHT_AMMO, 32, 1);
			item(ModItems.HEAVY_AMMO, 32, 2);
			item(ModItems.SHOTGUN_SHELL, 16, 1);
			item(ModItems.SNIPER_ROUND, 10, 2);
			item(ModItems.RUBY_CELL, 20, 2);
			section("Rockets and shells");
			item(ModItems.ROCKET, 4, 4);
			item(ModItems.AA_MISSILE, 2, 8);
			item(ModItems.AT_MISSILE, 2, 10);
			item(ModItems.MORTAR_SHELL, 3, 6);

			cat("Explosives");
			section("Grenades");
			item(ModItems.FRAG_GRENADE, 4, 4);
			item(ModItems.SMOKE_GRENADE, 4, 3);
			item(ModItems.FIRE_BOMB, 3, 5);
			section("Launchers");
			item(ModItems.ROCKET_LAUNCHER, 1, 35);
			item(ModItems.LANCER, 1, 40);
			item(ModItems.SKYHAMMER, 1, 35);
			section("Breaking in");
			item(ModItems.BREACHING_CHARGE, 1, 20);
			item(ModItems.C4, 2, 10);
			section("Artillery and strikes");
			item(ModBlocks.MORTAR, 1, 40);
			item(ModItems.RANGEFINDER, 1, 3);
			item(ModBlocks.MISSILE_SILO, 1, 80);
			item(ModItems.CRUISE_MISSILE, 1, 60);
			item(ModItems.AIR_STRIKE_FLARE, 1, 60);

			cat("Defenses");
			section("Walls");
			item(ModBlocks.SANDBAGS, 16, 1);
			item(ModBlocks.CONCRETE_WALL, 8, 1);
			item(ModBlocks.REINFORCED_CONCRETE, 2, 1);
			item(ModBlocks.REINFORCED_GLASS, 8, 3);
			item(ModBlocks.BLAST_DOOR, 1, 4);
			item(ModBlocks.BARBED_WIRE, 8, 1);
			section("Hiding");
			item(ModBlocks.HOLO_CAMO, 8, 4);
			item(ModBlocks.CAMO_NET, 16, 1);
			section("Traps");
			item(ModBlocks.LANDMINE, 1, 2);
			item(ModBlocks.CLAYMORE, 1, 2);
			item(ModBlocks.AT_MINE, 1, 6);
			section("Turrets");
			item(ModBlocks.SENTRY_TURRET, 1, 15);
			item(ModBlocks.TESLA_COIL, 1, 35);

			cat("Air Defense");
			section("Guns you man");
			item(ModItems.HEAVY_AA_GUN, 1, 70);
			item(ModItems.RAILGUN, 1, 90);
			section("Automatic");
			item(ModBlocks.FLAK_CANNON, 1, 45);
			item(ModBlocks.SAM_SITE, 1, 90);
			item(ModBlocks.POINT_DEFENSE, 1, 45);
			item(ModBlocks.MISSILE_INTERCEPTOR, 1, 30);
			item(ModBlocks.SHIELD_GENERATOR, 1, 60);
			section("Early warning");
			item(ModBlocks.RADAR_STATION, 1, 12);

			cat("Vehicles");
			section("On land");
			item(ModItems.JEEP, 1, 40);
			item(ModItems.TANK, 1, 100);
			item(ModItems.DIRT_BIKE, 1, 20);
			item(ModItems.QUAD, 1, 28);
			item(ModItems.TUNNEL_BORER, 1, 60);
			section("On water");
			item(ModItems.PATROL_BOAT, 1, 25);
			item(ModItems.GUNBOAT, 1, 70);
			item(ModItems.LANDING_CRAFT, 1, 55);

			cat("Aircraft");
			section("Aircraft");
			item(ModItems.FIGHTER_JET, 1, 150);
			item(ModItems.ATTACK_JET, 1, 170);
			item(ModItems.BOMBER, 1, 220);
			item(ModItems.ATTACK_HELI, 1, 160);
			section("Airfield");
			item(ModBlocks.AIRFIELD_PAD, 1, 30);

			cat("Machines");
			section("Power");
			item(ModBlocks.COMBUSTION_GENERATOR, 1, 12);
			item(ModBlocks.SOLAR_PANEL, 1, 10);
			item(ModBlocks.BATTERY, 1, 15);
			section("Making rubies");
			item(ModBlocks.RUBY_DRILL, 1, 25);
			item(ModBlocks.ORE_REFINERY, 1, 20);
			item(ModBlocks.TRADE_DEPOT, 1, 15);
			item(ModBlocks.VAULT, 1, 20);
			item(ModBlocks.AMMO_FACTORY, 1, 18);
			section("Moving things");
			item(ModBlocks.CONVEYOR, 16, 2);
			item(ModBlocks.BELT_LOADER, 1, 3);
			item(ModBlocks.BELT_SORTER, 1, 3);
			item(ModBlocks.ELEVATOR, 2, 2);

			cat("Building");
			section("Building tools");
			item(ModItems.ARCHITECT_WAND, 1, 10);
			item(ModItems.REMODELER, 1, 8);
			item(ModItems.BUILDERS_WAND, 1, 6);
			item(ModItems.EXCAVATOR, 1, 8);
			section("Copying buildings");
			item(ModItems.BLUEPRINT, 1, 2);
			item(ModBlocks.CONSTRUCTOR, 1, 30);
			section("Moving parts");
			item(ModBlocks.RUBY_BEARING, 1, 12);
			item(ModBlocks.RUBY_PISTON, 1, 10);
			section("Lights");
			item(ModBlocks.FLOODLIGHT, 2, 1);

			cat("Security");
			section("Locked doors");
			item(ModBlocks.KEYCARD_DOOR, 1, 6);
			item(ModBlocks.KEYCARD_READER, 1, 5);
			item(ModItems.KEYCARD, 1, 2);
			section("Watching and alarms");
			item(ModBlocks.SECURITY_CAMERA, 1, 5);
			item(ModBlocks.MONITOR, 1, 8);
			item(ModBlocks.LASER_TRIPWIRE, 1, 6);
			item(ModBlocks.SEISMIC_SENSOR, 1, 6);
			item(ModBlocks.ALARM_BLOCK, 1, 1);

			cat("Gear");
			section("Carrying and talking");
			item(ModItems.FIELD_PACK, 1, 8);
			item(ModItems.HEAVY_RUCKSACK, 1, 20);
			item(ModItems.FIELD_RADIO, 1, 4);
			section("Getting around");
			item(ModItems.JETPACK, 1, 30);
			item(ModItems.GRAPPLING_HOOK, 1, 15);
			item(ModItems.NIGHT_GOGGLES, 1, 12);
			section("Ruby armor and tools");
			item(ModItems.RUBY_HELMET, 1, 4);
			item(ModItems.RUBY_CHESTPLATE, 1, 8);
			item(ModItems.RUBY_LEGGINGS, 1, 7);
			item(ModItems.RUBY_BOOTS, 1, 3);
			item(ModItems.RUBY_SWORD, 1, 3);
			item(ModItems.RUBY_PICKAXE, 1, 4);

			cat("Support");
			section("Guards");
			item(ModItems.GUARD_CONTRACT, 1, 25);
			item(ModItems.AT_GUARD_CONTRACT, 1, 40);
			section("Drones");
			item(ModItems.RECON_DRONE, 1, 12);
			item(ModItems.SCOUT_DRONE, 1, 20);
			section("Paint");
			item(ModItems.PAINT_KIT, 1, 3);
		}
		return entries;
	}

	/** A new tab. */
	private static void cat(String name) {
		page++;
		PAGE_NAMES.add(name);
		nextSlot = 0;
		section = "";
	}

	private static void section(String name) {
		section = name;
	}

	/** The cards on a page, in order, with their sections (for the screen). */
	public static List<Card> cards(int page) {
		List<Card> out = new ArrayList<>();
		for (Entry e : entries()) {
			if (e.page() == page) {
				out.add(new Card(e.slot(), e.section()));
			}
		}
		return out;
	}

	private static void gun(Item gun, int price) {
		int magazine = ((GunItem) gun).stats().magazine();
		entries.add(new Entry(page, nextSlot++, price, () -> {
			ItemStack stack = new ItemStack(gun);
			stack.set(ModComponents.AMMO, magazine);
			return stack;
		}, id(gun), null, section));
	}

	/** A line about the plain blocks that don't explain themselves (the Armory shows it). */
	private static final java.util.Map<String, String> ABOUT = java.util.Map.of(
			"rubymod:conveyor", "Carries items the way its yellow arrows point, into whatever's at the end: a chest, a furnace, a machine, the Trade Depot.",
			"rubymod:belt_loader", "Takes things out of the chest, furnace or machine behind it and onto the belt in front. Right-click it with an item to take only that.",
			"rubymod:belt_sorter", "A belt piece that turns one kind of item off to the side. Right-click it with an item to choose; empty hand swaps sides.",
			"rubymod:elevator", "Stand on it and jump to go up to the next one, crouch to go down.");

	private static void item(ItemLike item, int count, int price) {
		entries.add(new Entry(page, nextSlot++, price, () -> new ItemStack(item, count), id(item.asItem()), null, section));
	}

	/** The research that still locks this entry for this player, or null. */
	private static Research.Node locked(Entry entry, ServerPlayer player) {
		if (entry.action() != null) {
			return null;
		}
		String path = entry.name().substring(entry.name().indexOf(':') + 1);
		return Research.unlocked(player, path) ? null : Research.lockFor(path);
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private static ItemStack named(Item item, String name) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)));
		return stack;
	}

	private static int price(Entry entry, ServerPlayer player) {
		if (entry.action() != null) {
			SeasonData.Claim claim = SeasonData.get().claimOf(player.getUUID());
			int[] costs = GameConfig.get().heartUpgradeCost;
			int tier = claim == null ? 1 : claim.tier;
			return tier <= costs.length ? costs[tier - 1] : -1;
		}
		return GameConfig.get().price(entry.name(), entry.price());
	}

	public static void open(ServerPlayer player, int page) {
		SimpleContainer container = new SimpleContainer(SLOTS);
		fill(container, player, Math.max(0, Math.min(pageCount() - 1, page)));
		entries();
		int shown = Math.max(0, Math.min(PAGE_NAMES.size() - 1, page));
		String title = "Armory: " + PAGE_NAMES.get(shown);
		player.openMenu(new net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider<Integer>() {
			@Override
			public Integer getScreenOpeningData(ServerPlayer p) {
				return page;
			}

			@Override
			public Component getDisplayName() {
				return Component.literal(title);
			}

			@Override
			public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, Inventory inventory, Player p) {
				return new ArmoryMenu(id, inventory, container, shown);
			}
		});
	}

	/** What upgrading the Heart gets you: this tier and the next. */
	private static void heartLore(List<Component> lore, ServerPlayer player) {
		SeasonData.Claim claim = SeasonData.get().claimOf(player.getUUID());
		GameConfig c = GameConfig.get();
		int tier = claim == null ? 1 : claim.tier;
		int[] drills = c.drillsPerHeartTier;
		lore.add(Component.literal("Your Heart: tier " + tier + " (claim " + c.heartRadius(tier) + " blocks out, " + c.heartHp(tier) + " health)")
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
		lore.add(Component.literal("Tier " + (tier + 1) + ": claim " + c.heartRadius(tier + 1) + " blocks out, " + c.heartHp(tier + 1) + " health"
				+ (drills != null && drills.length >= tier + 1 ? ", up to " + drills[Math.min(drills.length - 1, tier)] + " drills" : ""))
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GREEN)));
		lore.add(Component.literal("Heals your Heart too. Not on Battle Day.").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
	}

	private static void fill(Container container, ServerPlayer player, int page) {
		container.clearContent();
		for (Entry entry : entries()) {
			if (entry.page() != page) {
				continue;
			}
			ItemStack display = entry.stock().get();
			int price = price(entry, player);
			List<Component> lore = new ArrayList<>();
			Research.Node lock = locked(entry, player);
			if (lock != null) {
				lore.add(Component.literal("Unlock: the " + lock.title() + " upgrade (" + lock.cost() + " stars)")
						.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.DARK_AQUA)));
				lore.add(Component.literal("Press J to open your upgrades").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
				display.set(DataComponents.CUSTOM_NAME, Component.literal("[Locked] ").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.DARK_AQUA))
						.append(Component.literal(display.getHoverName().getString()).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.DARK_GRAY))));
				display.setCount(1);
			} else if (price < 0) {
				lore.add(Component.literal("Max tier").withStyle(ChatFormatting.GRAY));
			} else {
				if (entry.action() != null) {
					heartLore(lore, player);
				}
				String about = ABOUT.get(entry.name());
				if (about != null) {
					lore.add(Component.literal(about).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
				}
				lore.add(Component.literal("Cost: " + price + " rubies").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.RED)));
				lore.add(Component.literal("Click to buy").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
			}
			display.set(DataComponents.LORE, new ItemLore(lore));
			container.setItem(entry.slot(), display);
		}
		ItemStack info = named(Items.PAPER, "You have " + countRubies(player) + " rubies");
		info.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Mine Ruby Ore deep underground,").withStyle(ChatFormatting.GRAY),
				Component.literal("or loot supply drops.").withStyle(ChatFormatting.GRAY))));
		container.setItem(49, info);
	}

	private static void buy(ServerPlayer player, int slot, Container container, int page) {
		for (Entry entry : entries()) {
			if (entry.page() != page || entry.slot() != slot) {
				continue;
			}
			buyEntry(player, entry);
			fill(container, player, page);
			return;
		}
	}

	private static void buyEntry(ServerPlayer player, Entry entry) {
		{
			int price = price(entry, player);
			if (price < 0) {
				return;
			}
			Research.Node lock = locked(entry, player);
			if (lock != null) {
				player.sendSystemMessage(Component.literal("Locked: research " + lock.title() + " first (press J).").withStyle(ChatFormatting.DARK_AQUA), true);
				return;
			}
			if (entry.action() != null) {
				String result = entry.action().apply(player);
				player.sendSystemMessage(Component.literal(result), true);
			} else if (!takeRubies(player, price)) {
				player.sendSystemMessage(Component.literal("You need " + price + " rubies (you have " + countRubies(player) + ").")
						.withStyle(ChatFormatting.RED), true);
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8F, 1.0F);
				return;
			} else {
				ItemStack bought = entry.stock().get();
				if (!player.getInventory().add(bought)) {
					player.drop(bought, false, net.minecraft.util.Prediction.SERVER_ONLY);
				}
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.8F, 1.0F);
				Goals.add(player.level().getServer(), player.getUUID(), "armory", 1);
			}
		}
	}

	/** Rubies the player can spend: in their inventory plus in their bank (Vault). */
	public static int countRubies(Player player) {
		return player.getInventory().countItem(ModItems.RUBY) + (int) Math.min(Integer.MAX_VALUE / 2, dev.rubymod.industry.Grid.rubies(player.getUUID()));
	}

	/** Removes rubies from the player's inventory first, then their bank. Creative players buy for free. */
	public static boolean takeRubies(ServerPlayer player, int amount) {
		if (player.isCreative()) {
			return true;
		}
		if (countRubies(player) < amount) {
			return false;
		}
		Inventory inventory = player.getInventory();
		int carried = inventory.countItem(ModItems.RUBY);
		if (carried < amount) {
			dev.rubymod.industry.Grid.withdraw(player.getUUID(), amount - carried);
			amount = carried;
		}
		int left = amount;
		for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
			ItemStack s = inventory.getItem(i);
			if (s.is(ModItems.RUBY)) {
				int take = Math.min(left, s.getCount());
				s.shrink(take);
				left -= take;
			}
		}
		inventory.setChanged();
		return true;
	}

	/** A chest menu where clicking a slot buys it instead of picking it up. */
	/** A click in the Armory screen (see ArmoryMenu). */
	static void click(ServerPlayer player, int slot, Container container, int page) {
		buy(player, slot, container, page);
	}

	static void refill(Container container, ServerPlayer player, int page) {
		fill(container, player, page);
	}

	public static int pageCount() {
		entries();
		return PAGE_NAMES.size();
	}

	public static String pageName(int page) {
		entries();
		return page >= 0 && page < PAGE_NAMES.size() ? PAGE_NAMES.get(page) : "";
	}

	/** "Buy ammo": the ammo for the gun in {@code slot} on {@code page}, bought as if clicked. */
	static void buyAmmo(ServerPlayer player, Container container, int page, int slot) {
		for (Entry gun : entries()) {
			if (gun.page() != page || gun.slot() != slot || !(gun.stock().get().getItem() instanceof GunItem g)) {
				continue;
			}
			String want = id(g.stats().ammo().item());
			for (Entry ammo : entries()) {
				if (ammo.name().equals(want)) {
					buyEntry(player, ammo);
					fill(container, player, page);
					return;
				}
			}
		}
	}
}
