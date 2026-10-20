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
	private record Entry(int slot, int price, Supplier<ItemStack> stock, String name, Function<ServerPlayer, String> action) {
	}

	private static List<Entry> entries;

	private Shop() {
	}

	private static List<Entry> entries() {
		if (entries == null) {
			entries = new ArrayList<>();
			// Row 1: guns and grenades
			gun(0, ModItems.SERVICE_PISTOL, 2);
			gun(1, ModItems.VIPER_SMG, 8);
			gun(2, ModItems.COMBAT_SHOTGUN, 12);
			gun(3, ModItems.ARR_RIFLE, 15);
			gun(4, ModItems.LONGSHOT_SNIPER, 25);
			gun(5, ModItems.BULLDOG_LMG, 25);
			gun(6, ModItems.RUBY_BLASTER, 30);
			item(7, ModItems.FRAG_GRENADE, 4, 4);
			item(8, ModItems.SMOKE_GRENADE, 4, 3);
			// Row 2: ammo and raid gear
			item(9, ModItems.LIGHT_AMMO, 32, 1);
			item(10, ModItems.HEAVY_AMMO, 32, 2);
			item(11, ModItems.SHOTGUN_SHELL, 16, 1);
			item(12, ModItems.SNIPER_ROUND, 10, 2);
			item(13, ModItems.RUBY_CELL, 20, 2);
			item(14, ModItems.ROCKET_LAUNCHER, 1, 35);
			item(15, ModItems.ROCKET, 4, 4);
			item(16, ModItems.RANGEFINDER, 1, 3);
			item(17, ModItems.BREACHING_CHARGE, 1, 20);
			// Row 3: heavy weapons and electronics
			item(18, ModBlocks.MORTAR, 1, 40);
			item(19, ModItems.MORTAR_SHELL, 3, 6);
			item(20, ModBlocks.MISSILE_SILO, 1, 80);
			item(21, ModItems.CRUISE_MISSILE, 1, 60);
			item(22, ModBlocks.RADAR_STATION, 1, 12);
			item(23, ModBlocks.MISSILE_INTERCEPTOR, 1, 30);
			item(24, ModBlocks.SENTRY_TURRET, 1, 15);
			item(25, ModBlocks.ALARM_BLOCK, 1, 1);
			item(26, ModBlocks.ARMORY, 1, 4);
			// Row 4: fortifications and traps
			item(27, ModBlocks.SANDBAGS, 16, 1);
			item(28, ModBlocks.CONCRETE_WALL, 8, 1);
			item(29, ModBlocks.REINFORCED_CONCRETE, 2, 1);
			item(30, ModBlocks.BLAST_DOOR, 1, 4);
			item(31, ModBlocks.LANDMINE, 1, 2);
			item(32, ModBlocks.CLAYMORE, 1, 2);
			item(33, ModBlocks.BARBED_WIRE, 8, 1);
			item(34, ModItems.JEEP, 1, 40);
			item(35, ModItems.TANK, 1, 100);
			// Row 5: tools and upgrades
			item(36, ModItems.BUILDERS_WAND, 1, 6);
			item(37, ModItems.EXCAVATOR, 1, 8);
			item(45, ModItems.RUBY_HELMET, 1, 4);
			item(46, ModItems.RUBY_CHESTPLATE, 1, 8);
			item(47, ModItems.RUBY_LEGGINGS, 1, 7);
			item(48, ModItems.RUBY_BOOTS, 1, 3);
			item(50, ModItems.RUBY_SWORD, 1, 3);
			item(51, ModItems.RUBY_PICKAXE, 1, 4);
			entries.add(new Entry(40, 0, () -> named(Items.NETHER_STAR, "Upgrade Ruby Heart"), "heart_upgrade", Claims::upgrade));
		}
		return entries;
	}

	private static void gun(int slot, Item gun, int price) {
		int magazine = ((GunItem) gun).stats().magazine();
		entries.add(new Entry(slot, price, () -> {
			ItemStack stack = new ItemStack(gun);
			stack.set(ModComponents.AMMO, magazine);
			return stack;
		}, id(gun), null));
	}

	private static void item(int slot, ItemLike item, int count, int price) {
		entries.add(new Entry(slot, price, () -> new ItemStack(item, count), id(item.asItem()), null));
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
		fill(container, player);
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ShopMenu(id, inventory, container),
				Component.literal("Armory  -  " + countRubies(player) + " rubies")));
	}

	private static void fill(Container container, ServerPlayer player) {
		for (Entry entry : entries()) {
			ItemStack display = entry.stock().get();
			int price = price(entry, player);
			List<Component> lore = new ArrayList<>();
			if (price < 0) {
				lore.add(Component.literal("Max tier").withStyle(ChatFormatting.GRAY));
			} else {
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

	private static void buy(ServerPlayer player, int slot, Container container) {
		for (Entry entry : entries()) {
			if (entry.slot() != slot) {
				continue;
			}
			int price = price(entry, player);
			if (price < 0) {
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
			}
			fill(container, player);
			return;
		}
	}

	public static int countRubies(Player player) {
		return player.getInventory().countItem(ModItems.RUBY);
	}

	/** Removes rubies from the player's inventory. Creative players buy for free. */
	public static boolean takeRubies(ServerPlayer player, int amount) {
		if (player.isCreative()) {
			return true;
		}
		if (countRubies(player) < amount) {
			return false;
		}
		Inventory inventory = player.getInventory();
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
	private static class ShopMenu extends ChestMenu {
		private final Container shop;

		ShopMenu(int id, Inventory inventory, Container shop) {
			super(MenuType.GENERIC_9x6, id, inventory, shop, 6);
			this.shop = shop;
		}

		@Override
		public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
			if (slotIndex >= 0 && slotIndex < SLOTS && player instanceof ServerPlayer serverPlayer) {
				buy(serverPlayer, slotIndex, shop);
			}
			// Nothing in the shop can be picked up or moved.
			sendAllDataToRemote();
		}

		@Override
		public ItemStack quickMoveStack(Player player, int slotIndex) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}
}
