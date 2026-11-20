package dev.rubymod.industry;

import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A Belt Tunnel entrance's load: a few groups of things on their way to the exit, each arriving after the time a belt
 * would take to carry it there. It takes things only from behind (a belt, a machine, a Belt Loader) and only while it
 * has an exit and room, so a blocked exit backs the line up rather than losing anything. Nothing can be taken back out.
 */
public class BeltTunnelBlockEntity extends BlockEntity implements WorldlyContainer {
	private static final int SLOTS = 6;
	private static final int[] ALL = {0, 1, 2, 3, 4, 5};
	private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
	/** Game time each slot's group reaches the exit. */
	private final long[] arrives = new long[SLOTS];

	public BeltTunnelBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.BELT_TUNNEL, pos, state);
	}

	private Direction facing() {
		return getBlockState().getValue(BeltTunnelBlock.FACING);
	}

	private boolean entrance() {
		return !getBlockState().getValue(BeltTunnelBlock.EXIT);
	}

	/** How many things are inside (for the look-at panel). */
	public int carrying() {
		int n = 0;
		for (ItemStack s : items) {
			n += s.getCount();
		}
		return n;
	}

	public static void tick(Level level, BlockPos pos, BlockState state, BeltTunnelBlockEntity be) {
		if (level.getGameTime() % 5 != Math.floorMod(pos.asLong(), 5) || be.isEmpty()) {
			return;
		}
		BlockPos exit = BeltTunnelBlock.exitFor(level, pos, state);
		if (exit == null) {
			return; // waits (the exit's gone, or it's somewhere nobody is)
		}
		Direction facing = state.getValue(BeltTunnelBlock.FACING);
		BlockPos out = exit.relative(facing);
		if (!level.isLoaded(out)) {
			return;
		}
		long now = level.getGameTime();
		for (int i = 0; i < SLOTS; i++) {
			ItemStack s = be.items.get(i);
			if (s.isEmpty() || be.arrives[i] > now) {
				continue;
			}
			BlockState front = level.getBlockState(out);
			if (front.getBlock() instanceof ConveyorBlock && ConveyorBlock.leadsAway(front, facing)) {
				if (ConveyorBlock.busy(level, out)) {
					return;
				}
				ItemEntity drop = new ItemEntity(level, out.getX() + 0.5, out.getY() + 0.3, out.getZ() + 0.5, s.split(Math.min(8, s.getCount())));
				drop.setDeltaMovement(Vec3.ZERO);
				drop.setPickUpDelay(20);
				level.addFreshEntity(drop);
			} else if (Belts.containerAt(level, out) != null) {
				ItemStack rest = Belts.insert(level, out, facing.getOpposite(), s);
				if (rest.getCount() == s.getCount()) {
					return; // full: wait
				}
				be.items.set(i, rest);
			} else {
				return; // nothing in front of the exit to take it: wait
			}
			be.setChanged();
			return; // one group at a time
		}
	}

	@Override
	public int getContainerSize() {
		return SLOTS;
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack s : items) {
			if (!s.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		return items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack taken = ContainerHelper.removeItem(items, slot, amount);
		setChanged();
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ContainerHelper.takeItem(items, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (items.get(slot).isEmpty() && !stack.isEmpty() && level != null) {
			// The trip takes as long as a belt would (two blocks a second), at least half a second.
			BlockPos exit = BeltTunnelBlock.exitFor(level, worldPosition, getBlockState());
			int blocks = exit == null ? 1 : Math.max(1, worldPosition.distManhattan(exit));
			arrives[slot] = level.getGameTime() + Math.max(10, blocks * 10L);
		}
		items.set(slot, stack);
		setChanged();
	}

	/** Only an entrance with an exit takes things in. */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return entrance() && level != null && BeltTunnelBlock.exitFor(level, worldPosition, getBlockState()) != null;
	}

	@Override
	public boolean stillValid(Player player) {
		return false;
	}

	@Override
	public void clearContent() {
		items.clear();
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		return side == facing().getOpposite() && entrance() ? ALL : new int[0];
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		return side == facing().getOpposite() && canPlaceItem(slot, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return false;
	}

	/** Broken: whatever was inside drops out. */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		if (level != null) {
			Containers.dropContents(level, pos, items);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		items.clear();
		ContainerHelper.loadAllItems(input, items);
		// Back after a reload: whatever's inside is ready to come out.
		java.util.Arrays.fill(arrives, 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, items);
	}
}
