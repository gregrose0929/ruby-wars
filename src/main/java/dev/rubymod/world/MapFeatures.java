package dev.rubymod.world;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.RubyMod;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Things to find on the Ruby Wars map: a village in each corner (Minecraft's own village
 * houses, in that corner's style: plains, taiga, desert, snowy) and crumbled ruins
 * scattered over the land (broken houses, half-fallen towers, pillars, arches), some with
 * a chest. Like the land itself, every corner gets the same layout turned 90 degrees, so
 * nobody's corner is better. Everything is placed while chunks generate, each chunk
 * building just its own part, so it's the same however the map is explored.
 */
public final class MapFeatures {
	private static final String[][] CENTERS = {
			{"plains/town_centers/plains_fountain_01"}, {"taiga/town_centers/taiga_meeting_point_1"},
			{"desert/town_centers/desert_meeting_point_1"}, {"snowy/town_centers/snowy_meeting_point_1"}};
	private static final String[][] HOUSES = {
			{"plains/houses/plains_small_house_1", "plains/houses/plains_medium_house_1", "plains/houses/plains_big_house_1",
					"plains/houses/plains_library_1", "plains/houses/plains_small_house_5", "plains/houses/plains_weaponsmith_1",
					"plains/houses/plains_butcher_shop_1", "plains/houses/plains_temple_3", "plains/houses/plains_small_farm_1",
					"plains/houses/plains_large_farm_1", "plains/houses/plains_medium_house_2", "plains/houses/plains_animal_pen_1"},
			{"taiga/houses/taiga_small_house_1", "taiga/houses/taiga_medium_house_1", "taiga/houses/taiga_library_1",
					"taiga/houses/taiga_small_house_3", "taiga/houses/taiga_weaponsmith_1", "taiga/houses/taiga_medium_house_3",
					"taiga/houses/taiga_temple_1", "taiga/houses/taiga_armorer_house_1", "taiga/houses/taiga_large_farm_1",
					"taiga/houses/taiga_small_farm_1", "taiga/houses/taiga_medium_house_2", "taiga/houses/taiga_animal_pen_1"},
			{"desert/houses/desert_small_house_1", "desert/houses/desert_medium_house_1", "desert/houses/desert_library_1",
					"desert/houses/desert_small_house_3", "desert/houses/desert_weaponsmith_1", "desert/houses/desert_temple_1",
					"desert/houses/desert_medium_house_2", "desert/houses/desert_armorer_1", "desert/houses/desert_farm_1",
					"desert/houses/desert_large_farm_1", "desert/houses/desert_small_house_5", "desert/houses/desert_animal_pen_1"},
			{"snowy/houses/snowy_small_house_1", "snowy/houses/snowy_medium_house_1", "snowy/houses/snowy_library_1",
					"snowy/houses/snowy_small_house_3", "snowy/houses/snowy_weapon_smith_1", "snowy/houses/snowy_temple_1",
					"snowy/houses/snowy_medium_house_2", "snowy/houses/snowy_armorer_house_1", "snowy/houses/snowy_farm_1",
					"snowy/houses/snowy_farm_2", "snowy/houses/snowy_small_house_5", "snowy/houses/snowy_animal_pen_1"}};

	/** A village house (or its centre), ready to place: template, where, how turned, and its box in the world. */
	private record Piece(StructureTemplate template, BlockPos pos, Rotation rotation, BoundingBox box, int theme, long seed) {
	}

	/** A path from a village's centre out to one of its houses (a villager waits partway along). */
	private record Path(int x1, int z1, int x2, int z2) {
	}

	/** A ruin: where, which kind, and the corner's look. */
	public record Ruin(int x, int z, int kind, int theme, long seed) {
	}

	private static List<Piece> pieces;
	private static List<Path> paths;
	private static List<int[]> villages;
	private static final List<Ruin> RUINS = new ArrayList<>();
	private static boolean sitesDone;
	private static int[] villageSite0;

	private MapFeatures() {
	}

	// ----- Where things go (pure math: the same every world) -----

	/** Corner 0's point turned into corner k (the land is the same, turned 90 degrees per corner). */
	private static int[] turn(int x, int z, int k) {
		int cx = x;
		int cz = z;
		for (int i = 0; i < k; i++) {
			int t = cx;
			cx = -cz;
			cz = t;
		}
		return new int[] {cx, cz};
	}

	private static double hash(int a, int b, int c) {
		return WarzoneTerrain.hash3(a, b, c);
	}

	/** Lowest and highest ground in a square (sampled), or null if it's water, a river or mountain. */
	private static int[] ground(int x, int z, int half, int step) {
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		for (int dx = -half; dx <= half; dx += step) {
			for (int dz = -half; dz <= half; dz += step) {
				WarzoneTerrain.Column c = WarzoneTerrain.column(x + dx, z + dz);
				if (c.zone() != WarzoneTerrain.Zone.LAND && c.zone() != WarzoneTerrain.Zone.HOME || c.height() <= WarzoneTerrain.SEA + 1) {
					return null;
				}
				lo = Math.min(lo, c.height());
				hi = Math.max(hi, c.height());
			}
		}
		return new int[] {lo, hi};
	}

	private static synchronized void sites() {
		if (sitesDone) {
			return;
		}
		// The village: the flattest open ground between the home meadow and the coast, in corner 0.
		int bestScore = Integer.MAX_VALUE;
		int[] best = null;
		double homeX = 470 / Math.sqrt(2);
		for (int x = 120; x <= 640; x += 8) {
			for (int z = 120; z <= 640; z += 8) {
				double r = Math.hypot(x, z);
				if (r < 330 || r > 640 || Math.hypot(x - homeX, z - homeX) < 165 || Math.min(x, z) < 70) {
					continue;
				}
				int[] g = ground(x, z, 34, 6);
				if (g == null) {
					continue;
				}
				int score = (g[1] - g[0]) * 100 + (int) (hash(x, 9, z) * 50);
				if (score < bestScore) {
					bestScore = score;
					best = new int[] {x, z};
				}
			}
		}
		villageSite0 = best;
		// Ruins: here and there over the land (not in the home meadows' middle, not by the village).
		List<Ruin> ruins0 = new ArrayList<>();
		for (int x = 0; x <= 760; x += 52) {
			for (int z = 0; z <= 760; z += 52) {
				int rx = x + (int) (hash(x, 1, z) * 40) - 20;
				int rz = z + (int) (hash(x, 2, z) * 40) - 20;
				double r = Math.hypot(rx, rz);
				if (rx < 12 || rz < 12 || r < 165 || r > 700 || hash(x, 3, z) > 0.32) {
					continue;
				}
				if (Math.hypot(rx - homeX, rz - homeX) < 70 || best != null && Math.hypot(rx - best[0], rz - best[1]) < 80) {
					continue;
				}
				int[] g = ground(rx, rz, 7, 7);
				if (g == null || g[1] - g[0] > 5) {
					continue;
				}
				ruins0.add(new Ruin(rx, rz, (int) (hash(x, 4, z) * 4), 0, (long) (hash(x, 5, z) * Long.MAX_VALUE)));
			}
		}
		for (int k = 0; k < 4; k++) {
			for (Ruin r : ruins0) {
				int[] p = turn(r.x(), r.z(), k);
				RUINS.add(new Ruin(p[0], p[1], r.kind(), k, r.seed()));
			}
		}
		sitesDone = true;
		RubyMod.LOGGER.info("Ruby Wars map: villages around {} (and turned), {} ruins", best == null ? "nowhere" : best[0] + "," + best[1], RUINS.size());
	}

	/** Where the four villages' centres are (for tests and the curious). */
	public static List<int[]> villageCenters() {
		sites();
		List<int[]> out = new ArrayList<>();
		if (villageSite0 != null) {
			for (int k = 0; k < 4; k++) {
				out.add(turn(villageSite0[0], villageSite0[1], k));
			}
		}
		return out;
	}

	public static List<Ruin> ruins() {
		sites();
		return RUINS;
	}

	// ----- The villages' houses (needs the game's structure templates) -----

	private static synchronized void plan(StructureTemplateManager templates) {
		if (pieces != null) {
			return;
		}
		sites();
		List<Piece> out = new ArrayList<>();
		List<Path> roads = new ArrayList<>();
		List<int[]> centers = new ArrayList<>();
		if (villageSite0 != null) {
			for (int k = 0; k < 4; k++) {
				int[] c = turn(villageSite0[0], villageSite0[1], k);
				centers.add(new int[] {c[0], c[1], k});
				List<BoundingBox> taken = new ArrayList<>();
				Piece center = piece(templates, "village/" + CENTERS[k][0], c[0], c[1], Rotation.NONE, k, 1);
				if (center != null) {
					out.add(center);
					taken.add(center.box());
				}
				String[] houses = HOUSES[k];
				int placed = 0;
				for (int i = 0; i < houses.length; i++) {
					// Two rings of houses round the centre; the same angles in every corner (turned).
					double ring = i < 6 ? 19 : 33;
					double a = Math.toRadians((i < 6 ? i * 60 : (i - 6) * 60 + 30) + 90 * k + 12);
					for (int attempt = 0; attempt < 4; attempt++) {
						int hx = c[0] + (int) Math.round(Math.cos(a) * (ring + attempt * 5));
						int hz = c[1] + (int) Math.round(Math.sin(a) * (ring + attempt * 5));
						Rotation rot = Rotation.values()[(i + k) % 4];
						Piece p = piece(templates, "village/" + houses[i], hx, hz, rot, k, 100 + i);
						if (p == null) {
							break;
						}
						BoundingBox grown = p.box().inflatedBy(2);
						if (taken.stream().anyMatch(grown::intersects) || ground(hx, hz, 4, 4) == null) {
							continue;
						}
						out.add(p);
						taken.add(p.box());
						roads.add(new Path(c[0], c[1], hx, hz));
						placed++;
						break;
					}
				}
				RubyMod.LOGGER.info("Village {} at {},{}: {} houses", k, c[0], c[1], placed);
			}
		}
		paths = roads;
		villages = centers;
		pieces = out;
	}

	private static Piece piece(StructureTemplateManager templates, String name, int cx, int cz, Rotation rot, int theme, int salt) {
		StructureTemplate t = templates.get(Identifier.withDefaultNamespace(name)).orElse(null);
		if (t == null) {
			RubyMod.LOGGER.warn("No village template {}", name);
			return null;
		}
		StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rot);
		BoundingBox local = t.getBoundingBox(settings, BlockPos.ZERO);
		int ox = cx - (local.minX() + local.maxX()) / 2;
		int oz = cz - (local.minZ() + local.maxZ()) / 2;
		BoundingBox flat = t.getBoundingBox(settings, new BlockPos(ox, 0, oz));
		// Sit it on the highest ground under it; lower ground gets a foundation.
		int top = Integer.MIN_VALUE;
		for (int x = flat.minX(); x <= flat.maxX(); x++) {
			for (int z = flat.minZ(); z <= flat.maxZ(); z++) {
				top = Math.max(top, WarzoneTerrain.column(x, z).height());
			}
		}
		BlockPos pos = new BlockPos(ox, top - local.minY(), oz);
		return new Piece(t, pos, rot, t.getBoundingBox(settings, pos), theme, (long) cx * 341873128712L + cz * 132897987541L + salt);
	}

	// ----- Building (one chunk at a time) -----

	/** Called as each chunk of the Ruby Wars map is decorated: its share of the villages and ruins. */
	public static void decorate(WorldGenLevel level, ChunkAccess chunk) {
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		int maxX = minX + 15;
		int maxZ = minZ + 15;
		sites();
		if (villageSite0 != null && nearVillage(minX + 8, minZ + 8)) {
			plan(level.getLevel().getServer().getStructureTemplateManager());
			BoundingBox chunkBox = new BoundingBox(minX, level.getMinY(), minZ, maxX, level.getMaxY(), maxZ);
			for (Piece p : pieces) {
				if (p.box().intersects(chunkBox)) {
					placePiece(level, p, chunkBox);
				}
			}
			roads(level, minX, minZ, maxX, maxZ);
			villagers(level, minX, minZ, maxX, maxZ);
		}
		for (Ruin r : RUINS) {
			if (r.x() + 12 >= minX && r.x() - 12 <= maxX && r.z() + 12 >= minZ && r.z() - 12 <= maxZ) {
				Ruins.build(level, r, minX, minZ, maxX, maxZ);
			}
		}
		// The snowy corner's clearings missed the usual snowfall (that comes with the trees): add it.
		if (clearing(chunk.getPos()) && WarzoneTerrain.column(minX + 8, minZ + 8).theme() == WarzoneTerrain.THEME_SNOW) {
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			BlockState snow = Blocks.SNOW.defaultBlockState();
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
					if (level.getBlockState(m.set(x, y, z)).isAir() && snow.canSurvive(level, m)) {
						level.setBlock(m, snow, Block.UPDATE_CLIENTS);
					}
				}
			}
		}
	}

	/** Chunks kept clear of trees: a village's, or one a ruin stands in. */
	public static boolean clearing(net.minecraft.world.level.ChunkPos pos) {
		int x = pos.getMinBlockX() + 8;
		int z = pos.getMinBlockZ() + 8;
		for (int[] c : villageCenters()) {
			if (Math.abs(c[0] - x) < 56 && Math.abs(c[1] - z) < 56) {
				return true;
			}
		}
		for (Ruin r : ruins()) {
			if (Math.abs(r.x() - x) < 15 && Math.abs(r.z() - z) < 15) {
				return true;
			}
		}
		return false;
	}

	private static boolean nearVillage(int x, int z) {
		for (int[] c : villageCenters()) {
			if (Math.abs(c[0] - x) < 80 && Math.abs(c[1] - z) < 80) {
				return true;
			}
		}
		return false;
	}

	private static BlockState foundation(int theme) {
		return (theme == WarzoneTerrain.THEME_DESERT ? Blocks.SANDSTONE : Blocks.COBBLESTONE).defaultBlockState();
	}

	private static void placePiece(WorldGenLevel level, Piece p, BoundingBox chunkBox) {
		BoundingBox b = p.box();
		int floor = p.box().minY();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = Math.max(b.minX(), chunkBox.minX()); x <= Math.min(b.maxX(), chunkBox.maxX()); x++) {
			for (int z = Math.max(b.minZ(), chunkBox.minZ()); z <= Math.min(b.maxZ(), chunkBox.maxZ()); z++) {
				int ground = WarzoneTerrain.column(x, z).height();
				// A foundation down to the ground, and the space clear (trees and all) above.
				for (int y = ground + 1; y < floor; y++) {
					level.setBlock(m.set(x, y, z), foundation(p.theme()), Block.UPDATE_CLIENTS);
				}
				for (int y = floor; y <= b.maxY() + 6; y++) {
					if (!level.getBlockState(m.set(x, y, z)).isAir()) {
						level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					}
				}
			}
		}
		StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(p.rotation()).setBoundingBox(chunkBox).setIgnoreEntities(true)
				.addProcessor(JigsawReplacementProcessor.INSTANCE);
		p.template().placeInWorld(level, p.pos(), p.pos(), settings, RandomSource.create(p.seed()), Block.UPDATE_CLIENTS);
	}

	private static boolean inAPiece(int x, int z) {
		for (Piece p : pieces) {
			BoundingBox b = p.box();
			if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
				return true;
			}
		}
		return false;
	}

	/** Dirt paths from each village's centre to its houses. */
	private static void roads(WorldGenLevel level, int minX, int minZ, int maxX, int maxZ) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				boolean on = false;
				for (Path p : paths) {
					if (distance(x, z, p) < 1.6) {
						on = true;
						break;
					}
				}
				if (!on || inAPiece(x, z)) {
					continue;
				}
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
				BlockState top = level.getBlockState(m.set(x, y, z));
				if (top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.DIRT) || top.is(Blocks.PODZOL) || top.is(Blocks.COARSE_DIRT) || top.is(Blocks.SAND)
						|| top.is(Blocks.RED_SAND) || top.is(Blocks.SNOW_BLOCK)) {
					level.setBlock(m, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_CLIENTS);
					if (!level.getBlockState(m.set(x, y + 1, z)).isAir()) {
						level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					}
				}
			}
		}
	}

	private static double distance(int x, int z, Path p) {
		double dx = p.x2() - p.x1();
		double dz = p.z2() - p.z1();
		double len2 = dx * dx + dz * dz;
		double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - p.x1()) * dx + (z - p.z1()) * dz) / len2));
		return Math.hypot(x - (p.x1() + t * dx), z - (p.z1() + t * dz));
	}

	/** Villagers out on the paths, and an iron golem by each village's centre. */
	private static void villagers(WorldGenLevel level, int minX, int minZ, int maxX, int maxZ) {
		for (Path p : paths) {
			double t = 0.45;
			int x = (int) Math.round(p.x1() + (p.x2() - p.x1()) * t);
			int z = (int) Math.round(p.z1() + (p.z2() - p.z1()) * t);
			spawn(level, EntityTypes.VILLAGER, x, z, minX, minZ, maxX, maxZ);
		}
		for (int[] c : villages) {
			spawn(level, EntityTypes.IRON_GOLEM, c[0] + 6, c[1] + 6, minX, minZ, maxX, maxZ);
		}
	}

	private static void spawn(WorldGenLevel level, net.minecraft.world.entity.EntityType<?> type, int x, int z, int minX, int minZ, int maxX, int maxZ) {
		if (x < minX || x > maxX || z < minZ || z > maxZ || inAPiece(x, z)) {
			return;
		}
		Entity e = type.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
		if (e != null) {
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
			e.snapTo(x + 0.5, y, z + 0.5, (float) (hash(x, 7, z) * 360), 0);
			if (e instanceof net.minecraft.world.entity.Mob mob) {
				mob.setPersistenceRequired();
			}
			level.addFreshEntity(e);
		}
	}

	/** A loot chest (dungeon loot) at pos. */
	static void chest(WorldGenLevel level, BlockPos pos, long seed) {
		level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
		net.minecraft.world.RandomizableContainer.setBlockEntityLootTable(level, RandomSource.create(seed), pos, BuiltInLootTables.SIMPLE_DUNGEON);
	}
}
