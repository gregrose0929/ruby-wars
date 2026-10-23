package dev.rubymod.block;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.SeasonData;
import dev.rubymod.game.Squads;
import dev.rubymod.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Holo Camouflage block: what it looks like, who owns it, and who may pass through
 * (the owner, their squad and players they trust; refreshed every few seconds and sent
 * to clients so movement there agrees with the server).
 */
public class HoloBlockEntity extends BlockEntity {
	private UUID owner = new UUID(0, 0);
	private Set<UUID> allowed = new HashSet<>();
	private BlockState disguise;

	public HoloBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.HOLO, pos, state);
	}

	public BlockState disguise() {
		return disguise;
	}

	public void setOwner(UUID owner) {
		this.owner = owner;
		refreshAllowed();
		sync();
	}

	public void setDisguise(BlockState state) {
		disguise = state;
		sync();
	}

	public boolean lets(Entity entity) {
		return entity != null && (entity.getUUID().equals(owner) || allowed.contains(entity.getUUID()));
	}

	private boolean refreshAllowed() {
		Set<UUID> now = new HashSet<>(Squads.mates(owner));
		SeasonData.Claim claim = SeasonData.get().claimOf(owner);
		if (claim != null) {
			for (String trusted : claim.trusted) {
				now.add(UUID.fromString(trusted));
			}
		}
		if (now.equals(allowed)) {
			return false;
		}
		allowed = now;
		return true;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, HoloBlockEntity holo) {
		if (level.getGameTime() % 100 == pos.asLong() % 100 && holo.refreshAllowed()) {
			holo.sync();
		}
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		owner = input.getString("owner").map(UUID::fromString).orElse(new UUID(0, 0));
		allowed = new HashSet<>(input.read("allowed", UUIDUtil.CODEC_SET).orElse(Set.of()));
		disguise = input.read("disguise", BlockState.CODEC).orElse(null);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("owner", owner.toString());
		output.store("allowed", UUIDUtil.CODEC_SET, allowed);
		if (disguise != null) {
			output.store("disguise", BlockState.CODEC, disguise);
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
