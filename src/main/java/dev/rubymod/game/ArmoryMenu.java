package dev.rubymod.game;

import dev.rubymod.registry.ModMenus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Armory's menu: 54 display slots (the shop page; 45-53 are navigation and
 * info) and no player inventory. Clicking an item buys it (server side, see
 * Shop.buy); menu buttons 100+ jump to a page. Drawn by ArmoryScreen.
 */
public class ArmoryMenu extends AbstractContainerMenu {
	public static final int SLOTS = 54;
	public static final int GRID_X = 8;
	public static final int GRID_Y = 30;
	public static final int CELL = 22;
	private final Container shop;
	/** The page showing (a data slot, so turning a page just updates the open screen). */
	private final net.minecraft.world.inventory.DataSlot page = net.minecraft.world.inventory.DataSlot.standalone();

	/** Client side. */
	public ArmoryMenu(int id, Inventory inventory, Integer page) {
		this(id, inventory, new SimpleContainer(SLOTS), page);
	}

	public ArmoryMenu(int id, Inventory inventory, Container shop, int page) {
		super(ModMenus.ARMORY, id);
		this.shop = shop;
		this.page.set(page);
		addDataSlot(this.page);
		for (int i = 0; i < SLOTS; i++) {
			int x = i < 45 ? GRID_X + (i % 9) * CELL + 2 : -10000;
			int y = i < 45 ? GRID_Y + (i / 9) * CELL + 2 : -10000;
			addSlot(new Slot(shop, i, x, y) {
				@Override
				public boolean mayPickup(Player player) {
					return false;
				}

				@Override
				public boolean mayPlace(ItemStack stack) {
					return false;
				}
			});
		}
	}

	public int page() {
		return page.get();
	}

	/** Show another page in this same screen. */
	void turnTo(ServerPlayer player, int to) {
		if (to < 0 || to >= Shop.pageCount()) {
			return;
		}
		page.set(to);
		Shop.refill(shop, player, to);
		broadcastChanges();
	}

	public ItemStack display(int slot) {
		return shop.getItem(slot);
	}

	@Override
	public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
		if (slotIndex >= 0 && slotIndex < SLOTS && player instanceof ServerPlayer sp) {
			Shop.click(sp, slotIndex, shop, page.get());
		}
		sendAllDataToRemote();
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (id >= 100 && id < 100 + Shop.pageCount() && player instanceof ServerPlayer sp) {
			turnTo(sp, id - 100);
			return true;
		}
		return false;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
