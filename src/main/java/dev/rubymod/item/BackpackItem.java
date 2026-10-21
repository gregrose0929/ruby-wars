package dev.rubymod.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * Backpacks: right-click to open. A Field Pack holds 3 rows, a Heavy Rucksack 6.
 * Contents stay in the item (and drop into your War Grave with it). Packs can't
 * go inside packs.
 */
public class BackpackItem extends Item {
	private final int rows;

	public BackpackItem(int rows, Properties properties) {
		super(properties);
		this.rows = rows;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer sp) {
			ItemStack pack = player.getItemInHand(hand);
			int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : 40;
			sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new Menu(id, inv, pack, rows, slot), pack.getHoverName()));
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.8F, 1.0F);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
		long used = contents.nonEmptyItemCopyStream().count();
		builder.accept(Component.literal(used + " / " + rows * 9 + " slots used").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Right-click to open").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** A chest screen backed by the pack; writes back on every change. */
	public static class Menu extends ChestMenu {
		private final ItemStack pack;
		private final int packSlot;
		private final SimpleContainer box;

		Menu(int id, Inventory inventory, ItemStack pack, int rows, int packSlot) {
			this(id, inventory, pack, rows, packSlot, new SimpleContainer(rows * 9));
		}

		private Menu(int id, Inventory inventory, ItemStack pack, int rows, int packSlot, SimpleContainer box) {
			super(rows == 6 ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3, id, inventory, box, rows);
			this.pack = pack;
			this.packSlot = packSlot;
			this.box = box;
			NonNullList<ItemStack> items = NonNullList.withSize(rows * 9, ItemStack.EMPTY);
			pack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
			for (int i = 0; i < items.size(); i++) {
				box.setItem(i, items.get(i));
			}
		}

		private void save() {
			List<ItemStack> items = new ArrayList<>();
			for (int i = 0; i < box.getContainerSize(); i++) {
				items.add(box.getItem(i).copy());
			}
			pack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
		}

		@Override
		public void clicked(int slotId, int button, ContainerInput type, Player player) {
			// The open pack stays put, and packs never go into packs.
			if (slotId >= 0 && slotId < slots.size()) {
				Slot slot = slots.get(slotId);
				if (slot.container == player.getInventory() && slot.getContainerSlot() == packSlot) {
					return;
				}
				boolean intoPack = slot.container == box;
				if (intoPack && getCarried().getItem() instanceof BackpackItem) {
					return;
				}
				if (!intoPack && type == ContainerInput.QUICK_MOVE && slot.getItem().getItem() instanceof BackpackItem) {
					return;
				}
			}
			if (type == ContainerInput.SWAP && (button == packSlot || player.getInventory().getItem(button).getItem() instanceof BackpackItem)) {
				return;
			}
			super.clicked(slotId, button, type, player);
			save();
		}

		@Override
		public boolean stillValid(Player player) {
			return player.getInventory().getItem(packSlot) == pack;
		}

		@Override
		public void removed(Player player) {
			super.removed(player);
			save();
		}
	}
}
