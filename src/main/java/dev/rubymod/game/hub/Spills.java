package dev.rubymod.game.hub;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * No waterfalls off the islands: once the park's built (and someone's in the hub, so it's ticking), every bit of water
 * pouring over an edge is found (a chunk at a time, so nothing's held up). A little stray spring is taken away altogether; a pool that's leaking over its edge
 * gets the leak stopped up with a stone. Either way the water falling down below goes too.
 */
final class Spills {
	private Spills() {
	}

	private static final int R = 11; // chunks out from the middle (the hub is 175 blocks out)
	private static final int PER_TICK = 4;
	private static int next = Integer.MAX_VALUE;
	private static int removed;
	private static int plugged;

	static void start() {
		next = 0;
		removed = 0;
		plugged = 0;
	}

	static void tick(ServerLevel level) {
		int side = R * 2 + 1;
		if (next >= side * side) {
			return;
		}
		for (int k = 0; k < PER_TICK && next < side * side; k++, next++) {
			int cx = next % side - R;
			int cz = next / side - R;
			chunk(level, level.getChunk(cx, cz)); // (loads it if need be: the whole park gets looked over)
		}
		if (next >= side * side && removed + plugged > 0) {
			dev.rubymod.RubyMod.LOGGER.info("Spills: {} stray waterfalls taken away, {} leaking pools stopped up", removed, plugged);
		}
	}

	private static void chunk(ServerLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = sections.length - 1; i >= 0; i--) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.maybeHas(b -> b.is(Blocks.WATER))) {
				continue;
			}
			int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
			int x0 = chunk.getPos().getMinBlockX();
			int z0 = chunk.getPos().getMinBlockZ();
			for (int y = 15; y >= 0; y--) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						if (s.getBlockState(x, y, z).is(Blocks.WATER)) {
							BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
							if (lip(level, p)) {
								fix(level, p);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * For the self-test, high in the empty sky at the hub's corner: a stray spring pouring off a ledge is taken away, and
	 * a pool leaking over its edge is stopped up (and keeps its water). Tidied up after. Whether both worked.
	 */
	static boolean selfTest(ServerLevel level, java.util.Map<String, Boolean> out) {
		int y = HubPark.FLOOR + 60;
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		// A little ledge with a spring at its end, the water running off and down.
		BlockPos spring = new BlockPos(150, y, 150);
		level.setBlock(spring.below(), stone, 18);
		level.setBlock(spring, water, 18);
		BlockPos over = spring.east();
		level.setBlock(over, water.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1), 18);
		for (int k = 1; k <= 5; k++) {
			level.setBlock(over.below(k), water.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8), 18);
		}
		// A pool (four by four) with no wall on one side, spilling.
		BlockPos pool = new BlockPos(150, y, 156);
		for (int x = 0; x < 4; x++) {
			for (int z = 0; z < 4; z++) {
				level.setBlock(pool.offset(x, -1, z), stone, 18);
				level.setBlock(pool.offset(x, 0, z), water, 18);
			}
		}
		BlockPos lip = pool.offset(4, 0, 0);
		level.setBlock(lip, water.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1), 18);
		for (int k = 1; k <= 5; k++) {
			level.setBlock(lip.below(k), water.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8), 18);
		}
		int before = removed + plugged;
		chunk(level, level.getChunk(spring.getX() >> 4, spring.getZ() >> 4));
		boolean springGone = level.getBlockState(spring).isAir() && level.getBlockState(over).isAir() && level.getBlockState(over.below(3)).isAir();
		boolean poolKept = level.getBlockState(pool.offset(1, 0, 1)).is(Blocks.WATER) && !level.getBlockState(lip.below(3)).is(Blocks.WATER)
				&& !level.getBlockState(lip).is(Blocks.WATER);
		out.put("a stray waterfall off a ledge is taken away", springGone);
		out.put("a pool spilling over its edge is stopped up, and keeps its water", poolKept);
		dev.rubymod.RubyMod.LOGGER.info("[park] spills test: {} fixed", removed + plugged - before);
		for (int x = -1; x <= 6; x++) {
			for (int z = -1; z <= 10; z++) {
				for (int dy = -6; dy <= 1; dy++) {
					level.setBlock(new BlockPos(150 + x, y + dy, 150 + z), air, 18);
				}
			}
		}
		return springGone && poolKept;
	}

	/** Water that isn't falling itself, with falling water (or nothing) right under it: where a waterfall starts. */
	private static boolean lip(ServerLevel level, BlockPos p) {
		if (falling(level.getFluidState(p))) {
			return false;
		}
		BlockPos d = p.below();
		BlockState under = level.getBlockState(d);
		return under.is(Blocks.WATER) && falling(level.getFluidState(d)) || under.isAir() && !level.getFluidState(p).isSource();
	}

	private static boolean falling(FluidState f) {
		return !f.isEmpty() && f.hasProperty(FlowingFluid.FALLING) && f.getValue(FlowingFluid.FALLING);
	}

	private static void fix(ServerLevel level, BlockPos lip) {
		// What feeds it: the water joined to it, at its height and above.
		Set<BlockPos> body = new HashSet<>();
		int sources = 0;
		ArrayDeque<BlockPos> todo = new ArrayDeque<>();
		todo.add(lip);
		body.add(lip);
		while (!todo.isEmpty() && body.size() < 3000) {
			BlockPos q = todo.poll();
			if (level.getFluidState(q).isSource()) {
				sources++;
			}
			for (Direction dir : Direction.values()) {
				BlockPos n = q.relative(dir);
				if (n.getY() >= lip.getY() && !body.contains(n) && level.getBlockState(n).is(Blocks.WATER)) {
					body.add(n);
					todo.add(n);
				}
			}
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		if (sources <= 3 && body.size() < 200) {
			// A stray spring: gone.
			for (BlockPos q : body) {
				level.setBlock(q, air, 18);
			}
			removed++;
			dev.rubymod.RubyMod.LOGGER.info("Spills: took away a stray waterfall at {} {} {} ({} water)", lip.getX(), lip.getY(), lip.getZ(), body.size());
		} else {
			// A pool spilling over: a stone where it gets out.
			BlockPos plug = level.getFluidState(lip).isSource() ? lip.below() : lip;
			level.setBlock(plug, Blocks.STONE.defaultBlockState(), 18);
			plugged++;
			dev.rubymod.RubyMod.LOGGER.info("Spills: stopped a pool leaking at {} {} {}", plug.getX(), plug.getY(), plug.getZ());
		}
		// The water already on its way down, and wherever it ran to.
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> down = new ArrayDeque<>();
		down.add(lip.below());
		int n = 0;
		while (!down.isEmpty() && n < 6000) {
			BlockPos q = down.poll();
			if (!seen.add(q) || !level.getBlockState(q).is(Blocks.WATER) || level.getFluidState(q).isSource()) {
				continue;
			}
			level.setBlock(q, air, 18);
			n++;
			for (Direction dir : Direction.values()) {
				if (dir != Direction.UP) {
					down.add(q.relative(dir));
				}
			}
		}
	}
}
