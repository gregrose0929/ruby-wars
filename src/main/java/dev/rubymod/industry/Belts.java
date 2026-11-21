package dev.rubymod.industry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

/**
 * Putting things into containers and taking them out again, for belts, Belt Loaders and Belt Sorters. Furnaces
 * (and smokers and blast furnaces) are sorted out whatever side you come from: things to cook go in the top slot,
 * fuel in the fuel slot, and only what's cooked comes out.
 */
public final class Belts {
	private Belts() {
	}

	/** The container at {@code pos} (both halves of a double chest), or null. */
	public static Container containerAt(Level level, BlockPos pos) {
		return HopperBlockEntity.getContainerAt(level, pos);
	}

	/** Put {@code stack} into the container at {@code pos}, coming in through {@code face}. Returns what didn't fit. */
	public static ItemStack insert(Level level, BlockPos pos, Direction face, ItemStack stack) {
		Container container = containerAt(level, pos);
		if (container == null || stack.isEmpty()) {
			return stack;
		}
		if (container instanceof AbstractFurnaceBlockEntity furnace) {
			int slot = cookable(level, furnace, stack) ? 0 : furnace.canPlaceItem(1, stack) ? 1 : -1;
			return slot < 0 ? stack : into(furnace, slot, stack);
		}
		return HopperBlockEntity.addItem(null, container, stack.copy(), face);
	}

	private static boolean cookable(Level level, AbstractFurnaceBlockEntity furnace, ItemStack stack) {
		if (!(level instanceof ServerLevel server)) {
			return false;
		}
		RecipeType<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> type = furnace instanceof BlastFurnaceBlockEntity ? RecipeType.BLASTING
				: furnace instanceof SmokerBlockEntity ? RecipeType.SMOKING : RecipeType.SMELTING;
		return server.recipeAccess().getRecipeFor(type, new SingleRecipeInput(stack), server).isPresent();
	}

	private static ItemStack into(Container container, int slot, ItemStack stack) {
		ItemStack rest = stack.copy();
		ItemStack there = container.getItem(slot);
		if (there.isEmpty()) {
			int n = Math.min(rest.getCount(), Math.min(rest.getMaxStackSize(), container.getMaxStackSize()));
			container.setItem(slot, rest.split(n));
		} else if (ItemStack.isSameItemSameComponents(there, rest)) {
			int n = Math.min(rest.getCount(), there.getMaxStackSize() - there.getCount());
			if (n > 0) {
				there.grow(n);
				rest.shrink(n);
			}
		} else {
			return rest;
		}
		container.setChanged();
		return rest;
	}

	/**
	 * Take up to {@code max} of the first thing that matches {@code filter} (any item when it's empty) out of the
	 * container at {@code pos}, through {@code face}. A machine gives only what it's made, a furnace only what it's
	 * cooked. Returns what was taken (empty if nothing).
	 */
	public static ItemStack extract(Level level, BlockPos pos, Direction face, ItemStack filter, int max) {
		Container container = containerAt(level, pos);
		if (container == null) {
			return ItemStack.EMPTY;
		}
		int[] slots;
		if (container instanceof AbstractFurnaceBlockEntity) {
			slots = new int[] {2};
		} else if (container instanceof WorldlyContainer worldly) {
			slots = worldly.getSlotsForFace(face);
		} else {
			slots = new int[container.getContainerSize()];
			for (int i = 0; i < slots.length; i++) {
				slots[i] = i;
			}
		}
		for (int slot : slots) {
			ItemStack there = container.getItem(slot);
			if (there.isEmpty() || !filter.isEmpty() && !ItemStack.isSameItem(there, filter)) {
				continue;
			}
			if (!(container instanceof AbstractFurnaceBlockEntity) && container instanceof WorldlyContainer worldly
					&& !worldly.canTakeItemThroughFace(slot, there, face)) {
				continue;
			}
			ItemStack taken = there.split(Math.min(max, there.getCount()));
			container.setChanged();
			return taken;
		}
		return ItemStack.EMPTY;
	}

	/** Where a belt line ends: the container (or machine) there, and the side things go in by. */
	public record End(BlockPos pos, Direction face) {
	}

	private static boolean splitRight;

	/**
	 * Follow the belt line starting at {@code start} the way {@code stack} would ride it (Sorters turn it off, Splitters
	 * take turns, ramps go up or down, Tunnels jump), to the chest or machine at its end. Null when it doesn't reach one
	 * (a dead end, a stopped belt, a loop, or too far). For machines catching up after time away, when nothing has been
	 * riding the belts.
	 */
	public static End endOf(Level level, BlockPos start, ItemStack stack) {
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		BlockPos at = start;
		for (int step = 0; step < 256; step++) {
			if (!seen.add(at) || !level.isLoaded(at)) {
				return null;
			}
			BlockState s = level.getBlockState(at);
			Direction out;
			BlockPos next;
			if (s.getBlock() instanceof BeltTunnelBlock) {
				Direction f = s.getValue(BeltTunnelBlock.FACING);
				if (!s.getValue(BeltTunnelBlock.EXIT)) {
					BlockPos exit = BeltTunnelBlock.exitFor(level, at, s);
					if (exit == null) {
						return null;
					}
					at = exit;
					continue;
				}
				out = f;
				next = at.relative(f);
			} else if (s.getBlock() instanceof ConveyorBlock) {
				if (s.getValue(ConveyorBlock.POWERED)) {
					return null;
				}
				Direction f = s.getValue(ConveyorBlock.FACING);
				out = f;
				if (s.getBlock() instanceof BeltSorterBlock) {
					if (level.getBlockEntity(at) instanceof FilterBlockEntity be && !be.filter().isEmpty() && matches(be.filter(), stack)) {
						out = BeltSorterBlock.side(s);
					}
				} else if (s.getBlock() instanceof BeltSplitterBlock) {
					Direction first = splitRight ? f.getClockWise() : f.getCounterClockWise();
					splitRight = !splitRight;
					out = outlet(level, at.relative(first)) ? first : first.getOpposite();
				}
				next = s.getBlock() instanceof BeltRampBlock && !s.getValue(BeltRampBlock.DOWN) ? at.relative(out).above() : at.relative(out);
			} else {
				return null;
			}
			BlockState ns = level.getBlockState(next);
			if (ns.getBlock() instanceof ConveyorBlock) {
				if (ns.getValue(ConveyorBlock.FACING) == out.getOpposite()) {
					return null; // the next piece runs back the other way
				}
				at = next;
				continue;
			}
			if (ns.getBlock() instanceof BeltTunnelBlock) {
				at = next;
				continue;
			}
			if (containerAt(level, next) != null) {
				return new End(next.immutable(), out.getOpposite());
			}
			// Off the end onto a down ramp below.
			BlockState below = level.getBlockState(next.below());
			if (ns.isAir() && below.getBlock() instanceof BeltRampBlock && below.getValue(BeltRampBlock.DOWN)) {
				at = next.below();
				continue;
			}
			return null;
		}
		return null;
	}

	private static boolean outlet(Level level, BlockPos pos) {
		return level.getBlockState(pos).getBlock() instanceof ConveyorBlock || level.getBlockState(pos).getBlock() instanceof BeltTunnelBlock
				|| containerAt(level, pos) != null;
	}

	/** Does this stack match a filter (an empty filter takes anything)? */
	public static boolean matches(ItemStack filter, ItemStack stack) {
		return filter.isEmpty() || ItemStack.isSameItem(filter, stack);
	}
}
