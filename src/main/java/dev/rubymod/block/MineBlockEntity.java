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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A buried mine (Landmine, AT Mine): whose base it's in, and who can see it. To the base's owner, their squad and the
 * players they trust it's a mine on the ground, as it always was; to everyone else it's buried out of sight (no outline
 * to give it away either), until they step on it. A mine in nobody's base (a Horde outpost) is seen by everyone. The
 * list is refreshed every few seconds and sent to clients, like Holo Camouflage's.
 */
public class MineBlockEntity extends BlockEntity {
	private static final UUID NOBODY = new UUID(0, 0);
	private UUID owner = NOBODY;
	private Set<UUID> allowed = new HashSet<>();
	private boolean checked;

	public MineBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MINE, pos, state);
	}

	/** Can this entity (or whoever's driving it) see the mine? */
	public boolean visibleTo(Entity entity) {
		if (owner.equals(NOBODY) || entity == null) {
			return true;
		}
		Entity who = entity.getControllingPassenger() instanceof Player driver ? driver : entity;
		if (who instanceof Player p && (p.isCreative() || p.isSpectator())) {
			return true;
		}
		return who.getUUID().equals(owner) || allowed.contains(who.getUUID());
	}

	/** The base it's in, from where it is (once, when it's first ticked, or when it's put down). */
	public void claimFromPosition() {
		checked = true;
		if (level == null || level.isClientSide()) {
			return;
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(dev.rubymod.game.Rules.dim(level), worldPosition.getX(), worldPosition.getZ());
		UUID now = claim == null ? NOBODY : UUID.fromString(claim.owner);
		if (!now.equals(owner)) {
			owner = now;
			refreshAllowed();
			sync();
		}
	}

	private boolean refreshAllowed() {
		Set<UUID> now = new HashSet<>();
		if (!owner.equals(NOBODY)) {
			now.addAll(Squads.mates(owner));
			SeasonData.Claim claim = SeasonData.get().claimOf(owner);
			if (claim != null) {
				for (String trusted : claim.trusted) {
					now.add(UUID.fromString(trusted));
				}
			}
		}
		if (now.equals(allowed)) {
			return false;
		}
		allowed = now;
		return true;
	}

	/** The outline (what you can point at): none at all for someone the mine's hidden from. */
	public static net.minecraft.world.phys.shapes.VoxelShape shape(net.minecraft.world.phys.shapes.VoxelShape shape, net.minecraft.world.level.BlockGetter level,
			BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
		if (context instanceof net.minecraft.world.phys.shapes.EntityCollisionContext ec && ec.getEntity() != null
				&& level.getBlockEntity(pos) instanceof MineBlockEntity mine && !mine.visibleTo(ec.getEntity())) {
			return net.minecraft.world.phys.shapes.Shapes.empty();
		}
		return shape;
	}

	/** For a mine block's ticker. */
	@SuppressWarnings("unchecked")
	public static <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> ticker(Level level,
			net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
		return level.isClientSide() || type != ModBlockEntities.MINE ? null
				: (l, p, s, be) -> serverTick(l, p, s, (MineBlockEntity) be);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, MineBlockEntity mine) {
		if (!mine.checked) {
			mine.claimFromPosition();
		}
		if (level.getGameTime() % 100 == Math.floorMod(pos.asLong(), 100) && mine.refreshAllowed()) {
			mine.sync();
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
		owner = input.getString("owner").map(UUID::fromString).orElse(NOBODY);
		allowed = new HashSet<>(input.read("allowed", UUIDUtil.CODEC_SET).orElse(Set.of()));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("owner", owner.toString());
		output.store("allowed", UUIDUtil.CODEC_SET, allowed);
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
