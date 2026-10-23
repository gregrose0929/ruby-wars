package dev.rubymod.world;

import java.util.stream.Stream;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Biomes for the Ruby Wars map, from {@link WarzoneTerrain}: they decide the trees, plants and
 * grass colour of each part of the island (the land's shape comes from the terrain itself).
 */
public class WarzoneBiomeSource extends BiomeSource implements BiomeResolver {
	public static final MapCodec<WarzoneBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			RegistryOps.retrieveElement(Biomes.OCEAN),
			RegistryOps.retrieveElement(Biomes.BEACH),
			RegistryOps.retrieveElement(Biomes.SNOWY_BEACH),
			RegistryOps.retrieveElement(Biomes.RIVER),
			RegistryOps.retrieveElement(Biomes.PLAINS),
			RegistryOps.retrieveElement(Biomes.FOREST),
			RegistryOps.retrieveElement(Biomes.TAIGA),
			RegistryOps.retrieveElement(Biomes.DESERT),
			RegistryOps.retrieveElement(Biomes.SNOWY_TAIGA),
			RegistryOps.retrieveElement(Biomes.SNOWY_PLAINS),
			RegistryOps.retrieveElement(Biomes.WINDSWEPT_HILLS)
	).apply(i, (ocean, beach, snowyBeach, river, plains, forest, taiga, desert, snowyTaiga, snowyPlains, hills) -> new WarzoneBiomeSource(
			new Holder[] {ocean, beach, snowyBeach, river, plains, forest, taiga, desert, snowyTaiga, snowyPlains, hills})));

	private static final int OCEAN = 0;
	private static final int BEACH = 1;
	private static final int SNOWY_BEACH = 2;
	private static final int RIVER = 3;
	private static final int PLAINS = 4;
	private static final int FOREST = 5;
	private static final int TAIGA = 6;
	private static final int DESERT = 7;
	private static final int SNOWY_TAIGA = 8;
	private static final int SNOWY_PLAINS = 9;
	private static final int HILLS = 10;

	private final Holder<Biome>[] biomes;

	@SuppressWarnings("unchecked")
	private WarzoneBiomeSource(Holder[] biomes) {
		this.biomes = (Holder<Biome>[]) biomes;
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.of(biomes);
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		return this;
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
		WarzoneTerrain.Column c = WarzoneTerrain.column(quartX * 4 + 2, quartZ * 4 + 2);
		boolean snow = c.theme() == WarzoneTerrain.THEME_SNOW;
		int b = switch (c.zone()) {
			case OCEAN -> OCEAN;
			case BEACH -> snow ? SNOWY_BEACH : c.theme() == WarzoneTerrain.THEME_DESERT ? DESERT : BEACH;
			case RIVER -> RIVER;
			case CRATER -> PLAINS;
			case MOUNTAIN -> snow ? SNOWY_TAIGA : HILLS;
			case HOME -> switch (c.theme()) {
				case WarzoneTerrain.THEME_PINE -> TAIGA;
				case WarzoneTerrain.THEME_DESERT -> DESERT;
				case WarzoneTerrain.THEME_SNOW -> SNOWY_PLAINS;
				default -> PLAINS;
			};
			case LAND -> switch (c.theme()) {
				case WarzoneTerrain.THEME_PINE -> TAIGA;
				case WarzoneTerrain.THEME_DESERT -> DESERT;
				case WarzoneTerrain.THEME_SNOW -> SNOWY_TAIGA;
				default -> FOREST;
			};
		};
		return biomes[b];
	}
}
