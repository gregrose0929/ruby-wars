package dev.rubymod.block;

import java.util.List;
import java.util.UUID;

import dev.rubymod.game.GameConfig;
import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A War Grave holds everything a player was carrying when they died. Only they
 * can open it at first; after the lock runs out (config: graveLockSeconds) it's
 * anyone's to loot. It crumbles once it's empty.
 */
public class GraveBlockEntity extends BlockEntity implements Container {
	public static final int SIZE = 54;
	private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
	private UUID owner = new UUID(0, 0);
	private String ownerName = "";
	private long createdAt;

	public GraveBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.GRAVE, pos, state);
	}

	public void fill(ServerPlayer player, List<ItemStack> stacks) {
		owner = player.getUUID();
		ownerName = player.getName().getString();
		createdAt = level == null ? 0 : level.getGameTime();
		int i = 0;
		for (ItemStack stack : stacks) {
			if (!stack.isEmpty() && i < SIZE) {
				items.set(i++, stack);
			}
		}
		setChanged();
	}

	public UUID owner() {
		return owner;
	}

	public String ownerName() {
		return ownerName;
	}

	/** Seconds until anyone may loot it (0 = open to all). */
	public int lockedSeconds() {
		if (level == null) {
			return 0;
		}
		long lock = GameConfig.get().graveLockSeconds * 20L;
		return (int) Math.max(0, (createdAt + lock - level.getGameTime()) / 20);
	}

	public boolean canOpen(Player player) {
		return player.getUUID().equals(owner) || lockedSeconds() == 0 || player.isCreative();
	}

	/** Every few seconds: an old grave breaks open and spills what's left. */
	public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, GraveBlockEntity grave) {
		if (level.getGameTime() % 100 != 0) {
			return;
		}
		long age = level.getGameTime() - grave.createdAt;
		if (grave.isEmpty() || age > GameConfig.get().graveDecayMinutes * 1200L) {
			net.minecraft.world.Containers.dropContents(level, pos, grave);
			dev.rubymod.game.Waypoints.clearGrave(grave.owner);
			level.destroyBlock(pos, false);
		}
	}

	public Component title() {
		return Component.literal("War Grave: " + ownerName);
	}

	// ----- Container -----

	@Override
	public int getContainerSize() {
		return SIZE;
	}

	@Override
	public boolean isEmpty() {
		return items.stream().allMatch(ItemStack::isEmpty);
	}

	@Override
	public ItemStack getItem(int slot) {
		return items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		ItemStack r = ContainerHelper.removeItem(items, slot, count);
		setChanged();
		return r;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ContainerHelper.takeItem(items, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		items.set(slot, stack);
		setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	/** Graves only give: nothing can be put back in. */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return false;
	}

	@Override
	public void stopOpen(ContainerUser user) {
		if (isEmpty() && level != null && !level.isClientSide()) {
			dev.rubymod.game.Waypoints.clearGrave(owner);
			level.destroyBlock(worldPosition, false);
		}
	}

	@Override
	public void clearContent() {
		items.clear();
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		ContainerHelper.loadAllItems(input, items);
		owner = input.getString("owner").map(UUID::fromString).orElse(new UUID(0, 0));
		ownerName = input.getStringOr("owner_name", "");
		createdAt = input.getLongOr("created", 0L);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, items);
		output.putString("owner", owner.toString());
		output.putString("owner_name", ownerName);
		output.putLong("created", createdAt);
	}
}
