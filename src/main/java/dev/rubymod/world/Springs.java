package dev.rubymod.world;

import dev.rubymod.game.SeasonData;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.data.worldgen.placement.MiscOverworldPlacements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * No random waterfalls in the Ruby Wars world. Minecraft drops little water springs into hillsides and cave walls,
 * each pouring out a waterfall: new land doesn't get them any more, and the ones already out there are stopped up with
 * the rock round them as their land loads (never inside a base's claim, so nothing a player made is touched).
 */
public final class Springs {
	private Springs() {
	}

	private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
	private static int stopped;

	public static void register() {
		BiomeModifications.create(dev.rubymod.RubyMod.id("no_water_springs")).add(ModificationPhase.REMOVALS, BiomeSelectors.foundInOverworld(),
				context -> context.getGenerationSettings().removeFeature(MiscOverworldPlacements.SPRING_WATER));
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (level.dimension() == Level.OVERWORLD && QUEUE.size() < 20000) {
				QUEUE.add(chunk.getPos().pack());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (QUEUE.isEmpty()) {
				return;
			}
			ServerLevel level = server.overworld();
			for (int i = 0; i < 6 && !QUEUE.isEmpty(); i++) {
				long key = QUEUE.removeFirstLong();
				LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
				if (chunk != null) {
					stopped += clean(level, chunk);
				}
			}
			if (QUEUE.isEmpty() && stopped > 0) {
				dev.rubymod.RubyMod.LOGGER.info("Springs: {} hillside waterfalls stopped up", stopped);
				stopped = 0;
			}
		});
	}

	/** Stop up every spring in this chunk (outside claims). How many. */
	public static int clean(ServerLevel level, LevelChunk chunk) {
		int n = 0;
		LevelChunkSection[] sections = chunk.getSections();
		int x0 = chunk.getPos().getMinBlockX();
		int z0 = chunk.getPos().getMinBlockZ();
		if (nearClaim(x0 + 8, z0 + 8, 16)) {
			return 0;
		}
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.maybeHas(b -> b.is(Blocks.WATER))) {
				continue;
			}
			int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						BlockState b = s.getBlockState(x, y, z);
						if (!b.is(Blocks.WATER) || !b.getFluidState().isSource()) {
							continue;
						}
						BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
						BlockState rock = spring(level, p);
						if (rock != null) {
							level.setBlock(p, rock, 3);
							n++;
						}
					}
				}
			}
		}
		return n;
	}

	/**
	 * Is this water a spring (one source set into natural rock: rock over and under it and on three sides, the water
	 * pouring out of the fourth)? The rock to stop it up with if so, otherwise null.
	 */
	private static BlockState spring(ServerLevel level, BlockPos p) {
		BlockState above = loaded(level, p.above());
		BlockState below = loaded(level, p.below());
		if (!natural(above) || !natural(below)) {
			return null;
		}
		int rock = 0;
		int open = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockState s = loaded(level, p.relative(d));
			if (s == null) {
				return null;
			}
			if (natural(s)) {
				rock++;
			} else if (s.isAir() || s.is(Blocks.WATER) && !s.getFluidState().isSource()) {
				open++;
			} else {
				return null;
			}
		}
		return rock >= 3 && open >= 1 ? above : null;
	}

	private static BlockState loaded(ServerLevel level, BlockPos p) {
		LevelChunk c = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
		return c == null ? null : c.getBlockState(p);
	}

	/** What a spring sits in: the ground's own stone, dirt, snow and ice. */
	private static boolean natural(BlockState s) {
		return s != null && (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.CALCITE)
				|| s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.COARSE_DIRT));
	}

	private static boolean nearClaim(int x, int z, int extra) {
		for (SeasonData.Claim c : SeasonData.get().claims.values()) {
			if (c.destroyed) {
				continue;
			}
			int r = c.radius() + extra;
			if (Math.abs(c.pos().getX() - x) <= r && Math.abs(c.pos().getZ() - z) <= r) {
				return true;
			}
		}
		return false;
	}
}
