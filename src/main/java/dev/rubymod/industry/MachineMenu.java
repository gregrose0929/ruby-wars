package dev.rubymod.industry;

import dev.rubymod.registry.ModMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The machine screen's server/client half: machine slots, the player's inventory, and live numbers. */
public class MachineMenu extends AbstractContainerMenu {
	public static final int DATA_SLOTS = 18;
	public static final int WIDTH = 176;
	public static final int HEIGHT = 190;
	public static final int INVENTORY_Y = 108;

	public static final int BUTTON_UPGRADE = 0;
	public static final int BUTTON_RECIPE = 1;
	public static final int BUTTON_WITHDRAW_1 = 2;
	public static final int BUTTON_WITHDRAW_16 = 3;
	public static final int BUTTON_WITHDRAW_64 = 4;

	/** Sent when the screen opens: which machine, and where. */
	public record Opening(BlockPos pos, int kind) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Opening> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, Opening::pos, ByteBufCodecs.VAR_INT, Opening::kind, Opening::new);
	}

	private final Container machine;
	private final ContainerData data;
	private final MachineKind kind;
	private final BlockPos pos;
	private final MachineBlockEntity blockEntity;

	/** Client side. */
	public MachineMenu(int id, Inventory inventory, Opening opening) {
		this(id, inventory, new SimpleContainer(Math.max(1, MachineKind.values()[opening.kind()].slots())), new SimpleContainerData(DATA_SLOTS),
				MachineKind.values()[opening.kind()], opening.pos());
	}

	private MachineMenu(int id, Inventory inventory, Container machine, ContainerData data, MachineKind kind, BlockPos pos) {
		this(id, inventory, machine, data, kind, pos, null);
	}

	/** Server side. */
	public MachineMenu(int id, Inventory inventory, MachineBlockEntity be, ContainerData data, MachineKind kind, BlockPos pos) {
		this(id, inventory, be, data, kind, pos, be);
	}

	private MachineMenu(int id, Inventory inventory, Container machine, ContainerData data, MachineKind kind, BlockPos pos, MachineBlockEntity be) {
		super(ModMenus.MACHINE, id);
		this.machine = machine;
		this.data = data;
		this.kind = kind;
		this.pos = pos;
		this.blockEntity = be;
		int[][] layout = layout(kind);
		for (int i = 0; i < kind.slots(); i++) {
			int index = i;
			addSlot(new Slot(machine, i, layout[i][0], layout[i][1]) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return index < kind.inputs && machine.canPlaceItem(index, stack);
				}
			});
		}
		addStandardInventorySlots(inventory, 8, INVENTORY_Y);
		addDataSlots(data);
	}

	/** Slot positions per machine (x, y inside the panel). */
	public static int[][] layout(MachineKind kind) {
		return switch (kind) {
			case COMBUSTION_GENERATOR -> new int[][] {{44, 52}};
			case RUBY_DRILL -> grid(98, 26, 3, 3);
			case ORE_REFINERY -> new int[][] {{44, 44}, {98, 44}, {116, 44}, {134, 44}};
			case AMMO_FACTORY -> new int[][] {{44, 32}, {44, 56}, {116, 44}, {134, 44}};
			case TRADE_DEPOT -> grid(26, 26, 3, 3);
			case VAULT, CONSTRUCTOR -> new int[][] {{26, 44}};
			default -> new int[0][];
		};
	}

	private static int[][] grid(int x, int y, int cols, int rows) {
		int[][] out = new int[cols * rows][];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				out[r * cols + c] = new int[] {x + c * 18, y + r * 18};
			}
		}
		return out;
	}

	public MachineKind kind() {
		return kind;
	}

	public BlockPos pos() {
		return pos;
	}

	public int get(int index) {
		return data.get(index);
	}

	/** Two 15-bit halves back into a number. */
	public long wide(int lo, int hi) {
		return (data.get(lo) & 0x7FFF) | ((long) (data.get(hi) & 0x7FFF) << 15);
	}

	public int tier() {
		return Math.max(1, data.get(0));
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (blockEntity == null || !(player instanceof ServerPlayer sp)) {
			return false;
		}
		switch (id) {
			case BUTTON_UPGRADE -> sp.sendOverlayMessage(net.minecraft.network.chat.Component.literal(blockEntity.upgrade(sp)));
			case BUTTON_RECIPE -> blockEntity.cycleRecipe();
			case BUTTON_WITHDRAW_1 -> blockEntity.withdraw(sp, 1);
			case BUTTON_WITHDRAW_16 -> blockEntity.withdraw(sp, 16);
			case BUTTON_WITHDRAW_64 -> blockEntity.withdraw(sp, 64);
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		int machineSlots = kind.slots();
		if (index < machineSlots) {
			if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			boolean moved = false;
			for (int i = 0; i < kind.inputs && !stack.isEmpty(); i++) {
				if (slots.get(i).mayPlace(stack) && moveItemStackTo(stack, i, i + 1, false)) {
					moved = true;
				}
			}
			if (!moved) {
				return ItemStack.EMPTY;
			}
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		return machine.stillValid(player);
	}
}
