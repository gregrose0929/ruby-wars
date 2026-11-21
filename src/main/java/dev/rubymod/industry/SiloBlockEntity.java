package dev.rubymod.industry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * An Ore Silo: a huge store of one kind of thing (128 stacks). Belts, hoppers and machines put things in through
 * slot 0 (it always looks empty); slot 1 holds a stack ready to take out, refilled from the store. Broken, it keeps
 * what it holds on the item.
 */
public class SiloBlockEntity extends BlockEntity implements WorldlyContainer {
	public static final int STACKS = 128;
	private static final int[] SLOTS = {0, 1};

	/** What it holds (count 1), or empty. */
	private ItemStack type = ItemStack.EMPTY;
	/** How many, besides the ready stack. */
	private long stored;
	private ItemStack in = ItemStack.EMPTY;
	private ItemStack out = ItemStack.EMPTY;
	private UUID owner;
	/** The item players last saw in its window (it's sent again when this changes). */
	private net.minecraft.world.item.Item shown = net.minecraft.world.item.Items.AIR;

	public SiloBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.SILO, pos, state);
	}

	public UUID owner() {
		return owner;
	}

	public void setOwner(UUID owner) {
		this.owner = owner;
		setChanged();
	}

	public ItemStack type() {
		settle();
		return type;
	}

	/** Everything it holds. */
	public long total() {
		settle();
		return stored + out.getCount();
	}

	public long capacity() {
		return type.isEmpty() ? 64L * STACKS : (long) type.getMaxStackSize() * STACKS;
	}

	/** Room for {@code stack} (it must be the same thing, or the silo empty)? */
	public boolean accepts(ItemStack stack) {
		settle();
		if (stack.isEmpty() || stack.has(DataComponents.CONTAINER) && !stack.get(DataComponents.CONTAINER).equals(ItemContainerContents.EMPTY)) {
			return false;
		}
		if (!type.isEmpty() && !ItemStack.isSameItemSameComponents(type, stack)) {
			return false;
		}
		long room = (type.isEmpty() ? (long) stack.getMaxStackSize() * STACKS : capacity()) - stored - out.getCount();
		return room >= stack.getCount();
	}

	/** Put as much of {@code stack} in as fits; it shrinks by what went in. Returns how many went in. */
	public int put(ItemStack stack) {
		settle();
		if (stack.isEmpty() || !type.isEmpty() && !ItemStack.isSameItemSameComponents(type, stack)) {
			return 0;
		}
		if (type.isEmpty()) {
			type = stack.copyWithCount(1);
		}
		long room = capacity() - stored - out.getCount();
		int n = (int) Math.min(room, stack.getCount());
		if (n <= 0) {
			return 0;
		}
		stored += n;
		stack.shrink(n);
		setChanged();
		return n;
	}

	/** Take up to {@code max} out. */
	public ItemStack take(int max) {
		settle();
		if (type.isEmpty() || max <= 0) {
			return ItemStack.EMPTY;
		}
		int n = (int) Math.min(max, stored + out.getCount());
		ItemStack taken = type.copyWithCount(n);
		int fromOut = Math.min(n, out.getCount());
		out.shrink(fromOut);
		stored -= n - fromOut;
		setChanged();
		return taken;
	}

	/** Bring the store, the in-slot and the ready stack back in line (others may have changed the stacks directly). */
	private void settle() {
		if (!in.isEmpty()) {
			if (type.isEmpty()) {
				type = in.copyWithCount(1);
			}
			if (ItemStack.isSameItemSameComponents(type, in)) {
				stored += in.getCount();
			} else if (level != null && !level.isClientSide()) {
				net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, in);
			}
			in = ItemStack.EMPTY;
		}
		if (!out.isEmpty() && !type.isEmpty() && !ItemStack.isSameItemSameComponents(type, out)) {
			// Something else was put in the ready slot: keep it in the world rather than lose it.
			if (level != null && !level.isClientSide()) {
				net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, out);
			}
			out = ItemStack.EMPTY;
		}
		if (!type.isEmpty()) {
			int max = type.getMaxStackSize();
			if (out.isEmpty() && stored > 0) {
				int n = (int) Math.min(max, stored);
				out = type.copyWithCount(n);
				stored -= n;
			} else if (!out.isEmpty() && out.getCount() < max && stored > 0) {
				int n = (int) Math.min(max - out.getCount(), stored);
				out.grow(n);
				stored -= n;
			}
		}
		if (stored <= 0 && out.isEmpty()) {
			stored = 0;
			type = ItemStack.EMPTY;
		}
	}

	@Override
	public void setChanged() {
		settle();
		super.setChanged();
		if (level instanceof ServerLevel server) {
			Silos.report(server, this);
			syncWindow();
		}
	}

	/** Tell players what's in it (drawn in its window) when that changes. */
	private void syncWindow() {
		net.minecraft.world.item.Item now = type.isEmpty() ? net.minecraft.world.item.Items.AIR : type.getItem();
		if (now != shown && level != null && !level.isClientSide()) {
			shown = now;
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
		}
	}

	/** What's in it, for its window (client side). */
	public ItemStack shownType() {
		return type;
	}

	@Override
	public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
		return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	// ----- Container (belts, hoppers, machines) -----

	@Override
	public int[] getSlotsForFace(Direction side) {
		return SLOTS;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == 0 && accepts(stack);
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		return canPlaceItem(slot, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return slot == 1;
	}

	@Override
	public int getContainerSize() {
		return 2;
	}

	@Override
	public boolean isEmpty() {
		return total() == 0;
	}

	@Override
	public ItemStack getItem(int slot) {
		settle();
		return slot == 0 ? in : slot == 1 ? out : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		if (slot != 1) {
			return ItemStack.EMPTY;
		}
		settle();
		ItemStack taken = out.split(count);
		// Not refilled yet: a hopper that can't take it puts the count back on this same stack.
		super.setChanged();
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		if (slot != 1) {
			return ItemStack.EMPTY;
		}
		ItemStack taken = out;
		out = ItemStack.EMPTY;
		return taken;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (slot == 0) {
			in = stack;
		} else if (slot == 1) {
			out = stack;
		}
		setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return net.minecraft.world.Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void clearContent() {
		in = ItemStack.EMPTY;
		out = ItemStack.EMPTY;
		stored = 0;
		type = ItemStack.EMPTY;
	}

	// ----- On the item (broken and placed again) -----

	/** What it holds, as stacks (at most {@link #STACKS}). */
	public List<ItemStack> asStacks() {
		settle();
		List<ItemStack> stacks = new ArrayList<>();
		long left = stored + out.getCount();
		while (left > 0 && !type.isEmpty() && stacks.size() < STACKS) {
			int n = (int) Math.min(type.getMaxStackSize(), left);
			stacks.add(type.copyWithCount(n));
			left -= n;
		}
		return stacks;
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		ItemContainerContents contents = components.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
		clearContent();
		contents.nonEmptyItemCopyStream().forEach(this::put);
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		List<ItemStack> stacks = asStacks();
		if (!stacks.isEmpty()) {
			components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		type = input.read("type", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		stored = input.getLongOr("stored", 0L);
		out = input.read("ready", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		in = ItemStack.EMPTY;
		owner = input.getString("owner").map(UUID::fromString).orElse(null);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (!type.isEmpty()) {
			output.store("type", ItemStack.CODEC, type);
		}
		output.putLong("stored", stored + in.getCount());
		if (!out.isEmpty()) {
			output.store("ready", ItemStack.CODEC, out);
		}
		if (owner != null) {
			output.putString("owner", owner.toString());
		}
	}

	/** Every couple of seconds: on its owner's My Base list. */
	public static void serverTick(Level level, BlockPos pos, BlockState state, SiloBlockEntity be) {
		if (level instanceof ServerLevel server && server.getGameTime() % 40 == Math.floorMod(pos.asLong(), 40)) {
			be.settle();
			Silos.report(server, be);
			be.syncWindow();
		}
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server) {
			Silos.removed(server, pos);
		}
	}

	/** Each owner's silos: what's in them, by item. */
	public static final class Silos {
		private record Entry(UUID owner, String item, long count) {
		}

		private static final Map<String, Entry> ALL = new LinkedHashMap<>();

		private static String key(ServerLevel level, BlockPos pos) {
			return dev.rubymod.game.Rules.dim(level) + "|" + pos.asLong();
		}

		static void report(ServerLevel level, SiloBlockEntity be) {
			if (be.owner == null || be.isRemoved()) {
				return;
			}
			String item = be.type.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(be.type.getItem()).toString();
			ALL.put(key(level, be.worldPosition), new Entry(be.owner, item, be.stored + be.out.getCount()));
		}

		static void removed(ServerLevel level, BlockPos pos) {
			ALL.remove(key(level, pos));
		}

		public static void reset() {
			ALL.clear();
		}

		/** Item id and count across a player's silos, most first. */
		public static List<Map.Entry<String, Long>> totals(UUID owner) {
			Map<String, Long> sum = new LinkedHashMap<>();
			for (Entry e : ALL.values()) {
				if (e.owner.equals(owner) && !e.item.isEmpty() && e.count > 0) {
					sum.merge(e.item, e.count, Long::sum);
				}
			}
			List<Map.Entry<String, Long>> out = new ArrayList<>(sum.entrySet());
			out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
			return out;
		}
	}
}
