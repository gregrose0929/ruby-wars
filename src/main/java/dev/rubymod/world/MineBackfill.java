package dev.rubymod.world;

import dev.rubymod.block.MineBlockEntity;
import dev.rubymod.registry.ModBlocks;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Mines put down before they could hide (plain blocks, with no record of whose base they're in) get one the first time
 * their land loads, so they're buried out of sight from enemies like new ones.
 */
public final class MineBackfill {
	private MineBackfill() {
	}

	private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (level.dimension() == Level.OVERWORLD && !generated && QUEUE.size() < 20000) {
				QUEUE.add(chunk.getPos().pack());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerLevel level = server.overworld();
			for (int i = 0; i < 8 && !QUEUE.isEmpty(); i++) {
				long key = QUEUE.removeFirstLong();
				LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
				if (chunk != null) {
					fill(chunk);
				}
			}
		});
	}

	private static boolean isMine(BlockState s) {
		return s.is(ModBlocks.LANDMINE) || s.is(ModBlocks.AT_MINE);
	}

	/** Give every mine in this chunk its record (if it hasn't one). How many needed one. */
	public static int fill(LevelChunk chunk) {
		int n = 0;
		LevelChunkSection[] sections = chunk.getSections();
		int x0 = chunk.getPos().getMinBlockX();
		int z0 = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.maybeHas(MineBackfill::isMine)) {
				continue;
			}
			int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						if (isMine(s.getBlockState(x, y, z))) {
							BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
							if (chunk.getBlockEntity(p, LevelChunk.EntityCreationType.CHECK) == null
									&& chunk.getBlockEntity(p, LevelChunk.EntityCreationType.IMMEDIATE) instanceof MineBlockEntity mine) {
								mine.claimFromPosition();
								n++;
							}
						}
					}
				}
			}
		}
		return n;
	}
}
