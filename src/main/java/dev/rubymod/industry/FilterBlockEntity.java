package dev.rubymod.industry;

import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * What a Belt Loader or Belt Sorter is set to: one item (or nothing, meaning anything). A Belt Loader also moves
 * things along every half second: out of the container behind it, onto the belt (or into the container) in front.
 */
public class FilterBlockEntity extends BlockEntity {
	private ItemStack filter = ItemStack.EMPTY;

	public FilterBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.FILTER, pos, state);
	}

	public ItemStack filter() {
		return filter;
	}

	public void setFilter(ItemStack stack) {
		filter = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
		}
	}

	/** "Gold Ore", or "anything". */
	public String filterName() {
		return filter.isEmpty() ? "anything" : filter.getHoverName().getString();
	}

	/** The Belt Loader's work: up to eight of one thing, every half second. */
	public static void loaderTick(Level level, BlockPos pos, BlockState state, FilterBlockEntity be) {
		if (level.getGameTime() % 10 != Math.floorMod(pos.asLong(), 10)) {
			return;
		}
		var facing = state.getValue(BeltLoaderBlock.FACING);
		BlockPos from = pos.relative(facing.getOpposite());
		BlockPos to = pos.relative(facing);
		BlockState front = level.getBlockState(to);
		boolean belt = front.getBlock() instanceof ConveyorBlock && ConveyorBlock.leadsAway(front, facing);
		boolean container = !belt && Belts.containerAt(level, to) != null;
		if (!belt && !container) {
			return;
		}
		if (belt && ConveyorBlock.busy(level, to)) {
			// Wait for the belt to clear a little, so nothing piles up.
			return;
		}
		ItemStack taken = Belts.extract(level, from, facing, be.filter, 8);
		if (taken.isEmpty()) {
			return;
		}
		var claim = dev.rubymod.game.SeasonData.get().claimAt(dev.rubymod.game.Rules.dim(level), pos);
		if (claim != null && level.getServer() != null) {
			dev.rubymod.game.Goals.add(level.getServer(), java.util.UUID.fromString(claim.owner), "loader100", taken.getCount());
		}
		if (belt) {
			ItemEntity drop = new ItemEntity(level, to.getX() + 0.5, to.getY() + 0.3, to.getZ() + 0.5, taken);
			drop.setDeltaMovement(Vec3.ZERO);
			drop.setPickUpDelay(20);
			level.addFreshEntity(drop);
			return;
		}
		ItemStack rest = Belts.insert(level, to, facing.getOpposite(), taken);
		if (!rest.isEmpty()) {
			// Didn't all fit: back where it came from.
			ItemStack back = Belts.insert(level, from, facing, rest);
			if (!back.isEmpty()) {
				net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, back);
			}
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		filter = input.read("filter", ItemStack.CODEC).orElse(ItemStack.EMPTY);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (!filter.isEmpty()) {
			output.store("filter", ItemStack.CODEC, filter);
		}
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}
}
