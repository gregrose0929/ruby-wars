package dev.rubymod.industry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.rubymod.game.SeasonData;
import dev.rubymod.game.Rules;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Blueprints: copy a structure (up to 24x24x24) with a Blueprint, put it in a
 * Constructor, fill the chests next to the Constructor with the blocks, and it
 * builds the structure in front of itself, block by block, bottom up, rotated
 * to face the way the Constructor faces. It never removes anything already
 * standing, and only builds where its owner may build.
 */
public final class Blueprints {
	public static final int MAX_SIZE = 24;

	/** A captured structure: size, block palette, and one palette index per cell (-1 = air). */
	public record Data(int sx, int sy, int sz, List<BlockState> palette, List<Integer> cells) {
		public static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("sx").forGetter(Data::sx),
				Codec.INT.fieldOf("sy").forGetter(Data::sy),
				Codec.INT.fieldOf("sz").forGetter(Data::sz),
				BlockState.CODEC.listOf().fieldOf("palette").forGetter(Data::palette),
				Codec.INT.listOf().fieldOf("cells").forGetter(Data::cells)).apply(i, Data::new));
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

		public int blocks() {
			int n = 0;
			for (int c : cells) {
				if (c >= 0) {
					n++;
				}
			}
			return n;
		}

		public BlockState at(int index) {
			int c = cells.get(index);
			return c < 0 ? null : palette.get(c);
		}

		/** Cell index -> (x, y, z), bottom layer first so things are built from the ground up. */
		public BlockPos local(int index) {
			int y = index / (sx * sz);
			int rest = index % (sx * sz);
			return new BlockPos(rest % sx, y, rest / sx);
		}

		/** Blocks needed, by item. */
		public Map<Item, Integer> bill() {
			Map<Item, Integer> bill = new HashMap<>();
			for (int i = 0; i < cells.size(); i++) {
				BlockState s = at(i);
				if (s != null) {
					bill.merge(itemFor(s), 1, Integer::sum);
				}
			}
			return bill;
		}
	}

	private Blueprints() {
	}

	/** Copy the region between two corners. Returns null if it's too big. */
	public static Data capture(Level level, BlockPos a, BlockPos b) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		int sx = max.getX() - min.getX() + 1;
		int sy = max.getY() - min.getY() + 1;
		int sz = max.getZ() - min.getZ() + 1;
		if (sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) {
			return null;
		}
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> index = new HashMap<>();
		List<Integer> cells = new ArrayList<>(sx * sy * sz);
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					BlockState s = level.getBlockState(min.offset(x, y, z));
					if (!copyable(s)) {
						cells.add(-1);
						continue;
					}
					cells.add(index.computeIfAbsent(s, k -> {
						palette.add(k);
						return palette.size() - 1;
					}));
				}
			}
		}
		return new Data(sx, sy, sz, palette, cells);
	}

	/** Air, fluids, Hearts, machines and other block entities aren't copied. */
	private static boolean copyable(BlockState s) {
		if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity() || s.is(ModBlocks.RUBY_HEART)) {
			return false;
		}
		// Only the bottom of two-block things (doors, tall plants); the top comes with it.
		return !s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
	}

	public static Item itemFor(BlockState s) {
		return s.getBlock().asItem();
	}

	private static Rotation rotation(Direction facing) {
		return switch (facing) {
			case WEST -> Rotation.CLOCKWISE_90;
			case NORTH -> Rotation.CLOCKWISE_180;
			case EAST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/** Where cell (x, y, z) goes for a Constructor at {@code pos} facing {@code facing}: starting two blocks in front of it. */
	public static BlockPos target(BlockPos pos, Direction facing, BlockPos local) {
		return pos.relative(facing, 2).offset(local.rotate(rotation(facing)));
	}

	// ----- Constructor -----

	public static void tickConstructor(ServerLevel level, MachineBlockEntity be) {
		ItemStack paper = be.getItem(0);
		Data data = paper.get(ModComponents.BLUEPRINT);
		if (data == null) {
			be.setProgress(0);
			be.setStatus(MachineBlockEntity.NO_INPUT);
			return;
		}
		Direction facing = be.getBlockState().getValue(MachineBlock.FACING);
		if (be.ticks() % 20 == 0) {
			outline(level, be.getBlockPos(), facing, data);
		}
		if (be.progress() >= data.cells().size()) {
			be.setStatus(MachineBlockEntity.IDLE);
			return;
		}
		if (be.ticks() % be.kind().cycle(be.tier()) != 0) {
			return;
		}
		// Skip cells that are air, already built, or blocked.
		int index = be.progress();
		while (index < data.cells().size()) {
			BlockState want = data.at(index);
			BlockPos at = target(be.getBlockPos(), facing, data.local(index));
			if (want != null && level.getBlockState(at).canBeReplaced() && mayBuild(level, be, at)) {
				break;
			}
			index++;
		}
		be.setProgress(index);
		if (index >= data.cells().size()) {
			level.playSound(null, be.getBlockPos(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.8F, 1.2F);
			return;
		}
		BlockState want = data.at(index).rotate(rotation(facing));
		Item item = itemFor(want);
		if (!Grid.consume(be.owner(), be.kind().draw(be.tier()) * 10L)) {
			be.setStatus(MachineBlockEntity.NO_POWER);
			return;
		}
		if (!take(level, be.getBlockPos(), item)) {
			be.setStatus(MachineBlockEntity.NO_INPUT);
			be.setMissing(BuiltInRegistries.ITEM.getId(item) + 1);
			return;
		}
		be.setMissing(0);
		be.setStatus(MachineBlockEntity.WORKING);
		BlockPos at = target(be.getBlockPos(), facing, data.local(index));
		level.setBlock(at, want, 3);
		if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			level.setBlock(at.above(), want.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
		}
		level.playSound(null, at, want.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
		Vec3 c = Vec3.atCenterOf(at);
		level.sendParticles(new DustParticleOptions(0xE0115F, 1.0F), c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 0);
		be.setProgress(index + 1);
	}

	private static boolean mayBuild(ServerLevel level, MachineBlockEntity be, BlockPos at) {
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), at.getX(), at.getZ());
		return claim == null || claim.canUse(be.owner());
	}

	/** Take one of {@code item} from a container next to the Constructor. */
	private static boolean take(ServerLevel level, BlockPos pos, Item item) {
		for (Direction dir : Direction.values()) {
			BlockEntity neighbor = level.getBlockEntity(pos.relative(dir));
			if (neighbor instanceof Container container && !(neighbor instanceof MachineBlockEntity)) {
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack s = container.getItem(i);
					if (s.is(item)) {
						s.shrink(1);
						container.setChanged();
						return true;
					}
				}
			}
		}
		return false;
	}

	/** A dotted ruby outline of where the structure will go. */
	private static void outline(ServerLevel level, BlockPos pos, Direction facing, Data data) {
		BlockPos a = target(pos, facing, BlockPos.ZERO);
		BlockPos b = target(pos, facing, new BlockPos(data.sx() - 1, data.sy() - 1, data.sz() - 1));
		double x0 = Math.min(a.getX(), b.getX());
		double y0 = Math.min(a.getY(), b.getY());
		double z0 = Math.min(a.getZ(), b.getZ());
		double x1 = Math.max(a.getX(), b.getX()) + 1;
		double y1 = Math.max(a.getY(), b.getY()) + 1;
		double z1 = Math.max(a.getZ(), b.getZ()) + 1;
		DustParticleOptions dust = new DustParticleOptions(0xFF3070, 0.8F);
		for (double[] e : new double[][] {
				{x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
				{x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1},
				{x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1}}) {
			double len = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
			for (double t = 0; t <= len; t += 0.5) {
				double f = len == 0 ? 0 : t / len;
				level.sendParticles(dust, true, false, e[0] + (e[3] - e[0]) * f, e[1] + (e[4] - e[1]) * f, e[2] + (e[5] - e[2]) * f, 1, 0, 0, 0, 0);
			}
		}
	}

	static void load(MachineBlockEntity be, ValueInput input) {
	}

	static void save(MachineBlockEntity be, ValueOutput output) {
	}

	/** True if this block is a usable source of blocks for building. */
	public static boolean isAir(BlockState s) {
		return s.isAir() || s.is(Blocks.CAVE_AIR);
	}
}
