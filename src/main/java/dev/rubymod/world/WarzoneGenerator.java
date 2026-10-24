package dev.rubymod.world;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.serialization.MapCodec;

import dev.rubymod.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * The Ruby Wars map generator (world type "Ruby Wars Map"): builds the island from
 * {@link WarzoneTerrain}, column by column. Ores, trees and plants come from the biomes;
 * no villages or other structures, so the map stays the same for everyone.
 */
public class WarzoneGenerator extends ChunkGenerator {
	public static final MapCodec<WarzoneGenerator> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(i -> i.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
			com.mojang.serialization.Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(g -> g.scale)
	).apply(i, WarzoneGenerator::new));

	/** 1 for the Ruby Wars Map; smaller for a shrunk copy (Ruby Rush). */
	private final double scale;

	public WarzoneGenerator(BiomeSource biomeSource, double scale) {
		super(biomeSource);
		this.scale = scale;
	}

	private WarzoneTerrain.Column column(int x, int z) {
		return WarzoneTerrain.column(x, z, scale);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
		return ChunkGeneratorStructureState.createForFlat(randomState, seed, getOrigin(randomState), biomeSource, Stream.empty());
	}

	/** The block at height y in a column (water and air included). */
	private BlockState block(int x, int y, int z, WarzoneTerrain.Column c) {
		int h = c.height();
		if (y <= -64) {
			return Blocks.BEDROCK.defaultBlockState();
		}
		if (y > h) {
			return y <= WarzoneTerrain.SEA ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}
		if (y < 0) {
			return Blocks.DEEPSLATE.defaultBlockState();
		}
		int depth = h - y;
		if (c.zone() == WarzoneTerrain.Zone.CRATER && WarzoneTerrain.craterRuby((int) Math.floor(x / scale), y, (int) Math.floor(z / scale), h)) {
			return ModBlocks.RUBY_ORE.defaultBlockState();
		}
		if (depth > 4) {
			return Blocks.STONE.defaultBlockState();
		}
		return switch (c.zone()) {
			case OCEAN, BEACH -> depth < 3 ? (c.theme() == WarzoneTerrain.THEME_DESERT ? Blocks.RED_SAND : Blocks.SAND).defaultBlockState()
					: Blocks.SANDSTONE.defaultBlockState();
			case RIVER -> WarzoneTerrain.hash3(x, 0, z) < 0.4 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
			case CRATER -> depth == 0 ? (WarzoneTerrain.hash3(x, 1, z) < 0.25 ? Blocks.GRAVEL : h > 72 ? Blocks.STONE : Blocks.COARSE_DIRT).defaultBlockState()
					: Blocks.DIRT.defaultBlockState();
			case MOUNTAIN -> {
				if (h > 122 && depth < 2) {
					yield Blocks.SNOW_BLOCK.defaultBlockState();
				}
				if (h < 100 && depth == 0 && WarzoneTerrain.hash3(x, 2, z) < 0.6) {
					yield Blocks.GRASS_BLOCK.defaultBlockState();
				}
				yield (WarzoneTerrain.hash3(x, y, z) < 0.3 ? Blocks.ANDESITE : Blocks.STONE).defaultBlockState();
			}
			default -> switch (c.theme()) {
				case WarzoneTerrain.THEME_DESERT -> depth < 3 ? Blocks.RED_SAND.defaultBlockState() : Blocks.TERRACOTTA.defaultBlockState();
				case WarzoneTerrain.THEME_PINE -> depth == 0 && WarzoneTerrain.hash3(x, 3, z) < 0.3 ? Blocks.PODZOL.defaultBlockState()
						: depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
				default -> depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
			};
		};
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
			BiomeManager biomeManager, WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		ChunkPos cp = chunk.getPos();
		int minY = chunk.getMinY();
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int x = cp.getMinBlockX() + lx;
				int z = cp.getMinBlockZ() + lz;
				WarzoneTerrain.Column c = column(x, z);
				int top = Math.max(c.height(), WarzoneTerrain.SEA);
				for (int y = minY; y <= top; y++) {
					BlockState state = block(x, y, z, c);
					if (state.isAir()) {
						continue;
					}
					chunk.setBlockState(pos.set(lx, y, lz), state);
					oceanFloor.update(lx, y, lz, state);
					worldSurface.update(lx, y, lz, state);
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		// Animals at world creation, like vanilla (cows, sheep, pigs...).
		ChunkPos center = region.getCenter();
		BlockPos source = center.getWorldPosition().atY(region.getMaxY());
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
		NaturalSpawner.spawnMobsForChunkGeneration(region, source, center, random);
	}

	@Override
	public int getGenDepth() {
		return 384;
	}

	@Override
	public int getSeaLevel() {
		return WarzoneTerrain.SEA;
	}

	@Override
	public int getMinY() {
		return -64;
	}

	@Override
	public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
		return column(0, 0).height() + 1;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		WarzoneTerrain.Column c = column(x, z);
		if (c.height() < WarzoneTerrain.SEA && type.isOpaque().test(Blocks.WATER.defaultBlockState())) {
			return WarzoneTerrain.SEA + 1;
		}
		return c.height() + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		WarzoneTerrain.Column c = column(x, z);
		BlockState[] states = new BlockState[heightAccessor.getHeight()];
		for (int i = 0; i < states.length; i++) {
			states[i] = block(x, heightAccessor.getMinY() + i, z, c);
		}
		return new NoiseColumn(heightAccessor.getMinY(), states);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos pos, SamplerContext samplerContext) {
		WarzoneTerrain.Column c = column(pos.getX(), pos.getZ());
		result.add("Ruby Wars map: " + c.zone() + ", corner " + c.theme());
	}
}
