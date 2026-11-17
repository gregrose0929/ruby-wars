package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.rubymod.mixin.DisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * The Park Museum, at the top of Main Street. Bring Professor Pebble what you've found and it goes on show with your name
 * on it: fish you've caught swim in the museum tank, butterflies you've caught flutter in the glass butterfly house, and
 * fossils you've dug up on Fossil Beach (next to Splash Island: ask the professor for a shovel) stand on the plinths.
 */
final class Museum {
	private Museum() {
	}

	private static final String FOSSIL = "rubymod_fossil";
	private static final String SHOVEL = "Fossil Shovel";

	private record Fossil(String id, String name, Item item) {
	}

	private static final Fossil[] FOSSILS = {new Fossil("ammonite", "Ammonite", Items.NAUTILUS_SHELL), new Fossil("trex", "T. rex Bone", Items.BONE),
		new Fossil("skull", "Raptor Skull", Items.SKELETON_SKULL), new Fossil("egg", "Dinosaur Egg", Items.SNIFFER_EGG),
		new Fossil("tooth", "Shark Tooth", Items.FLINT), new Fossil("trilobite", "Trilobite", Items.TURTLE_SCUTE),
		new Fossil("plate", "Stegosaurus Plate", Items.ARMADILLO_SCUTE), new Fossil("feather", "Fossil Feather", Items.FEATHER),
		new Fossil("amber", "Amber (with a bug inside!)", Items.HONEYCOMB), new Fossil("horn", "Triceratops Horn", Items.GOAT_HORN),
		new Fossil("fern", "Fossil Fern", Items.FERN)};

	// The building: x -56..-40, z -120..-94; the door's in the west wall (facing Main Street).
	private static final int X1 = -56;
	private static final int X2 = -40;
	private static final int Z1 = -120;
	private static final int Z2 = -94;
	/** The tank (south room, east side) and the butterfly house (south room, west side). */
	private static final int TX1 = -45;
	private static final int TX2 = -42;
	private static final int TZ1 = -101;
	private static final int TZ2 = -96;
	private static final int BX1 = -54;
	private static final int BX2 = -52;
	private static final int BZ1 = -101;
	private static final int BZ2 = -96;
	/** Fossil Beach: a little sandy island joined onto Splash Island's south-east corner. */
	static final int[][] BEACH = {{100, -130, 11}};

	/** Who gave what: "fish:Minnow" → "Gran". */
	private static final Map<String, String> GIVEN = new LinkedHashMap<>();
	private static final List<UUID> TANK = new ArrayList<>();
	private static final Map<UUID, Vec3> FLUTTER = new HashMap<>();
	private static final Map<String, UUID> BOARDS = new HashMap<>();
	private static final Map<String, UUID> PLINTH = new HashMap<>();
	private static final List<BlockPos> DIG_SPOTS = new ArrayList<>();
	private static final List<BlockPos> DIG_AT = new ArrayList<>();
	private static final Map<BlockPos, Integer> DUG = new HashMap<>();
	private static final Map<UUID, Long> LAST_DIG = new HashMap<>();
	private static boolean loaded;
	private static int tick;

	static void reset() {
		TANK.clear();
		FLUTTER.clear();
		BOARDS.clear();
		PLINTH.clear();
		DIG_SPOTS.clear();
		DIG_AT.clear();
		DUG.clear();
	}

	static void build(ServerLevel level) {
		reset();
		load(level);
		building(level);
		beach(level);
		refresh(level);
	}

	private static void building(ServerLevel level) {
		BlockState wall = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
		BlockState pillar = Blocks.QUARTZ_PILLAR.defaultBlockState();
		for (int x = X1; x <= X2; x++) {
			for (int z = Z1; z <= Z2; z++) {
				boolean edge = x == X1 || x == X2 || z == Z1 || z == Z2;
				HubPark.set(level, x, FLOOR, z, (x + z) % 2 == 0 ? Blocks.POLISHED_DIORITE.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState());
				for (int y = 1; y <= 7; y++) {
					boolean door = x == X1 && y <= 4 && z >= -108 && z <= -106;
					boolean window = edge && !door && y >= 3 && y <= 5 && (x == X1 || x == X2) && Math.floorMod(z, 4) == 0;
					BlockState s = !edge || door ? Blocks.AIR.defaultBlockState() : window ? Blocks.GLASS.defaultBlockState()
							: (x == X1 || x == X2) && Math.floorMod(z, 4) == 2 ? pillar : wall;
					HubPark.set(level, x, FLOOR + y, z, s);
				}
				HubPark.set(level, x, FLOOR + 8, z, edge ? Blocks.CUT_SANDSTONE.defaultBlockState() : Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0
						? Blocks.GLOWSTONE.defaultBlockState() : Blocks.SMOOTH_QUARTZ.defaultBlockState());
			}
		}
		// The rooms: a fossil hall to the north, the entrance hall, and the aquarium and butterfly house to the south.
		for (int x = X1 + 1; x < X2; x++) {
			for (int y = 1; y <= 7; y++) {
				boolean arch = Math.abs(x + 48) <= 1 && y <= 4;
				HubPark.set(level, x, FLOOR + y, -111, arch ? Blocks.AIR.defaultBlockState() : wall);
				HubPark.set(level, x, FLOOR + y, -103, arch ? Blocks.AIR.defaultBlockState() : wall);
			}
		}
		// Steps and columns at the door.
		for (int z = -109; z <= -105; z++) {
			HubPark.set(level, X1 - 1, FLOOR, z, Blocks.QUARTZ_BLOCK.defaultBlockState());
		}
		for (int z : new int[] {-110, -104}) {
			HubPark.box(level, X1 - 1, FLOOR + 1, z, X1 - 1, FLOOR + 7, z, pillar);
		}
		// A classical front: a beam across the columns and a triangular pediment over it.
		for (int z = -111; z <= -103; z++) {
			HubPark.set(level, X1 - 1, FLOOR + 8, z, Blocks.CUT_SANDSTONE.defaultBlockState());
		}
		for (int k = 0; k <= 4; k++) {
			for (int z = -111 + k; z <= -103 - k; z++) {
				boolean left = z == -111 + k;
				boolean right = z == -103 - k;
				BlockState b = k == 4 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState()
						: left ? Blocks.SMOOTH_SANDSTONE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.SOUTH)
								: right ? Blocks.SMOOTH_SANDSTONE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.NORTH)
										: Blocks.SMOOTH_SANDSTONE.defaultBlockState();
				HubPark.set(level, X1 - 1, FLOOR + 9 + k, z, b);
			}
		}
		HubPark.set(level, X1 - 1, FLOOR + 10, -107, Blocks.GOLD_BLOCK.defaultBlockState());
		for (int z = -110; z <= -104; z++) {
			HubPark.set(level, X1 - 2, FLOOR, z, Blocks.SMOOTH_QUARTZ_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.EAST));
		}
		HubPark.label(level, X1 - 1.7, FLOOR + 7.0, -106.5, "PARK MUSEUM", ChatFormatting.GOLD, 1.6F);
		HubPark.label(level, X1 - 1.7, FLOOR + 6.3, -106.5, "Everything here was found by someone in the family!", ChatFormatting.WHITE, 0.55F);
		// The tank.
		for (int x = TX1 - 1; x <= TX2 + 1; x++) {
			for (int z = TZ1 - 1; z <= TZ2 + 1; z++) {
				boolean edge = x == TX1 - 1 || x == TX2 + 1 || z == TZ1 - 1 || z == TZ2 + 1;
				HubPark.set(level, x, FLOOR, z, Blocks.SAND.defaultBlockState());
				for (int y = 1; y <= 4; y++) {
					HubPark.set(level, x, FLOOR + y, z, edge ? Blocks.GLASS.defaultBlockState() : Blocks.WATER.defaultBlockState());
				}
				HubPark.set(level, x, FLOOR + 5, z, Blocks.GLASS.defaultBlockState());
			}
		}
		HubPark.set(level, TX1, FLOOR + 1, TZ1, Blocks.TALL_SEAGRASS.defaultBlockState());
		HubPark.set(level, TX2, FLOOR + 1, TZ2, Blocks.BRAIN_CORAL.defaultBlockState());
		HubPark.set(level, TX1 + 2, FLOOR + 1, TZ2, Blocks.SEA_PICKLE.defaultBlockState());
		HubPark.set(level, TX2 - 1, FLOOR + 1, TZ1, Blocks.FIRE_CORAL.defaultBlockState());
		// The butterfly house: glass all round, flowers inside.
		for (int x = BX1 - 1; x <= BX2 + 1; x++) {
			for (int z = BZ1 - 1; z <= BZ2 + 1; z++) {
				boolean edge = x == BX1 - 1 || x == BX2 + 1 || z == BZ1 - 1 || z == BZ2 + 1;
				HubPark.set(level, x, FLOOR, z, Blocks.GRASS_BLOCK.defaultBlockState());
				for (int y = 1; y <= 5; y++) {
					HubPark.set(level, x, FLOOR + y, z, edge ? Blocks.GLASS.defaultBlockState() : y == 1 && (x + z) % 2 == 0
							? (x % 3 == 0 ? Blocks.ALLIUM.defaultBlockState() : Blocks.CORNFLOWER.defaultBlockState()) : Blocks.AIR.defaultBlockState());
				}
				HubPark.set(level, x, FLOOR + 6, z, Blocks.GLASS.defaultBlockState());
			}
		}
		// Fossil plinths round the fossil hall, and a big dinosaur skeleton in the middle.
		for (int k = 0; k < FOSSILS.length; k++) {
			BlockPos p = plinth(k);
			HubPark.set(level, p.getX(), FLOOR + 1, p.getZ(), Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
		}
		dino(level);
		// Glass cases over the fossil plinths.
		for (int k = 0; k < FOSSILS.length; k++) {
			BlockPos p = plinth(k);
			HubPark.set(level, p.getX(), FLOOR + 2, p.getZ(), Blocks.GLASS.defaultBlockState());
		}
		// A glass dome on the roof, with white ribs and a gold top.
		int dx0 = -48;
		int dz0 = -107;
		for (int dx = -7; dx <= 7; dx++) {
			for (int dz = -7; dz <= 7; dz++) {
				double flat = Math.hypot(dx, dz);
				if (flat <= 6.6 && flat > 5.4) {
					HubPark.set(level, dx0 + dx, FLOOR + 9, dz0 + dz, Blocks.CUT_SANDSTONE.defaultBlockState());
				}
				for (int dy = 0; dy <= 7; dy++) {
					double d = Math.sqrt(dx * dx + dy * dy * 1.1 + dz * dz);
					if (d <= 6.4 && d > 5.4) {
						boolean rib = dx == 0 || dz == 0;
						HubPark.set(level, dx0 + dx, FLOOR + 10 + dy, dz0 + dz, rib ? Blocks.SMOOTH_QUARTZ.defaultBlockState()
								: Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.LIGHT_BLUE).defaultBlockState());
					}
				}
			}
		}
		HubPark.set(level, dx0, FLOOR + 17, dz0, Blocks.GOLD_BLOCK.defaultBlockState());
		HubPark.set(level, dx0, FLOOR + 18, dz0, Blocks.END_ROD.defaultBlockState());
		// Banners down the front.
		for (int z : new int[] {-117, -114, -100, -97}) {
			HubPark.set(level, X1 - 1, FLOOR + 6, z, Blocks.WALL_BANNER.pick(z % 2 == 0 ? net.minecraft.world.item.DyeColor.RED : net.minecraft.world.item.DyeColor.BLUE)
					.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, net.minecraft.core.Direction.WEST));
		}
		for (int x = X1 + 3; x < X2; x += 5) {
			HubPark.set(level, x, FLOOR + 7, -107, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		}
		// The entrance hall: a red carpet from the door, plants in the corners, banners, a welcome sign.
		for (int x = X1 + 1; x <= -42; x++) {
			for (int z = -108; z <= -106; z++) {
				HubPark.set(level, x, FLOOR + 1, z, Blocks.CARPET.pick(z == -107 ? net.minecraft.world.item.DyeColor.RED : net.minecraft.world.item.DyeColor.YELLOW)
						.defaultBlockState());
			}
		}
		for (int[] c : new int[][] {{X1 + 1, -110}, {X1 + 1, -104}, {X2 - 1, -110}, {X2 - 1, -104}}) {
			HubPark.set(level, c[0], FLOOR + 1, c[1], Blocks.FLOWERING_AZALEA.defaultBlockState());
		}
		for (int x : new int[] {-54, -52, -44, -42}) {
			HubPark.set(level, x, FLOOR + 5, -110, Blocks.WALL_BANNER.pick(x % 4 == 0 ? net.minecraft.world.item.DyeColor.RED : net.minecraft.world.item.DyeColor.BLUE)
					.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, net.minecraft.core.Direction.SOUTH));
			HubPark.set(level, x, FLOOR + 5, -104, Blocks.WALL_BANNER.pick(x % 4 == 0 ? net.minecraft.world.item.DyeColor.BLUE : net.minecraft.world.item.DyeColor.RED)
					.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, net.minecraft.core.Direction.NORTH));
		}
		HubPark.label(level, -48.5, FLOOR + 6.2, -107.5, "WELCOME TO THE PARK MUSEUM\nFossils this way (north)  -  Fish and butterflies (south)", ChatFormatting.GOLD, 0.7F);
		HubHosts.add(level, new Vec3(-51.5, FLOOR + 1, -107.5), 90, VillagerProfession.LIBRARIAN, VillagerType.TAIGA,
				new HubHosts.Host("museum", "Professor Pebble", "Park Museum", 0xE0C080, "minecraft:bone",
						"Welcome to the Park Museum! Bring me what you find and I'll put it on show with your name on it. "
								+ "Fish you've caught go in the tank, butterflies you've caught go in the butterfly house, "
								+ "and fossils go on the plinths. Dig for fossils on Fossil Beach, next to Splash Island: I'll lend you a shovel!",
						"Museum: right-click Professor Pebble to give things!",
						p -> List.of("give|Give my finds to the museum", "shovel|Lend me a fossil shovel", "missing|What's still missing?"), (p, key) -> {
							switch (key) {
								case "give" -> donate(p);
								case "shovel" -> {
									HubGames.give(p, HubGames.toy(Items.WOODEN_SHOVEL, 1, SHOVEL, ChatFormatting.GOLD));
									p.sendSystemMessage(Component.literal("Here's a shovel! Fossil Beach is the sandy island next to Splash Island (fly to Splash Island). "
											+ "Right-click the speckled sand to dig.").withStyle(ChatFormatting.GOLD));
								}
								default -> missing(p);
							}
						}));
	}

	/** A plinth for fossil k: along the fossil hall's walls. */
	private static BlockPos plinth(int k) {
		if (k < 6) {
			return new BlockPos(X1 + 2 + k * 2 + (k >= 3 ? 2 : 0), FLOOR + 1, Z1 + 2);
		}
		int j = k - 6;
		return j < 3 ? new BlockPos(X1 + 2, FLOOR + 1, Z1 + 4 + j * 2) : new BlockPos(X2 - 2, FLOOR + 1, Z1 + 4 + (j - 3) * 2);
	}

	private static void dino(ServerLevel level) {
		BlockState bone = Blocks.BONE_BLOCK.defaultBlockState();
		int x = -48;
		int z = -115;
		// Legs, body, a long neck and a tail: a friendly long-neck dinosaur.
		for (int[] leg : new int[][] {{-2, -1}, {-2, 1}, {2, -1}, {2, 1}}) {
			HubPark.box(level, x + leg[0], FLOOR + 1, z + leg[1], x + leg[0], FLOOR + 2, z + leg[1], bone);
		}
		HubPark.box(level, x - 3, FLOOR + 3, z, x + 3, FLOOR + 3, z, bone);
		for (int r = -2; r <= 2; r += 2) {
			HubPark.box(level, x + r, FLOOR + 3, z - 1, x + r, FLOOR + 4, z + 1, bone);
		}
		for (int k = 1; k <= 3; k++) {
			HubPark.set(level, x + 3 + k, FLOOR + 3 + k, z, bone);
		}
		HubPark.box(level, x + 7, FLOOR + 6, z, x + 7, FLOOR + 6, z, Blocks.SKELETON_SKULL.defaultBlockState());
		for (int k = 1; k <= 4; k++) {
			HubPark.set(level, x - 3 - k, FLOOR + 3 - (k > 2 ? 1 : 0), z, bone);
		}
		HubPark.label(level, x + 0.5, FLOOR + 6.5, z + 0.5, "Long-neck Dinosaur (a model)", ChatFormatting.WHITE, 0.6F);
	}

	// ----- Fossil Beach -----

	private static void beach(ServerLevel level) {
		HubPark.blob(level, BEACH, FLOOR);
		int[] c = BEACH[0];
		var random = new java.util.Random(31);
		for (int x = c[0] - c[2] - 1; x <= c[0] + c[2] + 1; x++) {
			for (int z = c[1] - c[2] - 1; z <= c[1] + c[2] + 1; z++) {
				double d = Math.hypot(x - c[0], z - c[1]);
				if (d > c[2] - 0.4) {
					continue;
				}
				HubPark.set(level, x, FLOOR, z, Blocks.SAND.defaultBlockState());
				HubPark.set(level, x, FLOOR - 1, z, Blocks.SANDSTONE.defaultBlockState());
				BlockPos top = new BlockPos(x, FLOOR + 1, z);
				if (!level.getBlockState(top).isAir()) {
					level.setBlock(top, Blocks.AIR.defaultBlockState(), 2);
				}
				if (d < c[2] - 2 && random.nextInt(5) == 0) {
					DIG_SPOTS.add(new BlockPos(x, FLOOR, z));
				} else if (d >= c[2] - 1.5 && random.nextInt(4) == 0) {
					HubPark.set(level, x, FLOOR + 1, z, random.nextBoolean() ? Blocks.DEAD_BUSH.defaultBlockState() : Blocks.SHORT_DRY_GRASS.defaultBlockState());
				}
			}
		}
		// A path across to Splash Island.
		for (double t = 0; t <= 1.0; t += 0.05) {
			int x = (int) Math.round(92 + (85 - 92) * t);
			int z = (int) Math.round(-122 + (-114 - (-122)) * t);
			for (int w = -1; w <= 1; w++) {
				BlockPos p = new BlockPos(x + w, FLOOR, z);
				if (level.getBlockState(p).isAir() || level.getBlockState(p).is(Blocks.GRASS_BLOCK)) {
					level.setBlock(p, Blocks.SMOOTH_SANDSTONE.defaultBlockState(), 2);
				}
				BlockPos up = p.above();
				if (level.getBlockState(up).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
					level.setBlock(up, Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
		// A few dig spots are speckled at a time.
		java.util.Collections.shuffle(DIG_SPOTS, random);
		for (int i = 0; i < Math.min(6, DIG_SPOTS.size()); i++) {
			DIG_AT.add(DIG_SPOTS.get(i));
			level.setBlock(DIG_SPOTS.get(i), Blocks.SUSPICIOUS_SAND.defaultBlockState(), 2);
		}
		// Palm-ish trees, a sign, a bucket and spade.
		for (int[] t : new int[][] {{c[0] - 6, c[1] - 4}, {c[0] + 5, c[1] + 6}}) {
			HubPark.box(level, t[0], FLOOR + 1, t[1], t[0], FLOOR + 5, t[1], Blocks.JUNGLE_LOG.defaultBlockState());
			for (int[] l : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
				HubPark.set(level, t[0] + l[0], FLOOR + 6 - (Math.abs(l[0] + l[1]) == 2 ? 1 : 0), t[1] + l[1],
						Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
			}
			HubPark.set(level, t[0], FLOOR + 6, t[1], Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
		}
		// Beach umbrellas with towels, and a sandcastle.
		net.minecraft.world.item.DyeColor[][] stripes = {{net.minecraft.world.item.DyeColor.RED, net.minecraft.world.item.DyeColor.WHITE},
			{net.minecraft.world.item.DyeColor.LIGHT_BLUE, net.minecraft.world.item.DyeColor.YELLOW}};
		int[][] brollies = {{c[0] + 4, c[1] - 5}, {c[0] - 5, c[1] + 4}};
		for (int u = 0; u < brollies.length; u++) {
			int bx = brollies[u][0];
			int bz = brollies[u][1];
			HubPark.box(level, bx, FLOOR + 1, bz, bx, FLOOR + 3, bz, Blocks.BIRCH_FENCE.defaultBlockState());
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					boolean mid = dx == 0 && dz == 0;
					HubPark.set(level, bx + dx, FLOOR + 4, bz + dz, mid ? Blocks.WOOL.pick(stripes[u][0]).defaultBlockState()
							: Blocks.WOOL_SLAB.pick(stripes[u][Math.floorMod(dx + dz, 2)]).defaultBlockState());
				}
			}
			HubPark.set(level, bx, FLOOR + 5, bz, Blocks.CARPET.pick(stripes[u][1]).defaultBlockState());
			HubPark.set(level, bx + 1, FLOOR + 1, bz + 1, Blocks.CARPET.pick(stripes[u][1]).defaultBlockState());
			HubPark.set(level, bx + 1, FLOOR + 1, bz + 2, Blocks.CARPET.pick(stripes[u][1]).defaultBlockState());
		}
		int sx = c[0] - 2;
		int sz = c[1] + 6;
		HubPark.box(level, sx, FLOOR + 1, sz, sx + 2, FLOOR + 1, sz + 2, Blocks.SANDSTONE.defaultBlockState());
		for (int[] t : new int[][] {{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
			HubPark.set(level, sx + t[0], FLOOR + 2, sz + t[1], Blocks.SANDSTONE_WALL.defaultBlockState());
		}
		HubPark.set(level, sx + 1, FLOOR + 2, sz + 1, Blocks.CHISELED_SANDSTONE.defaultBlockState());
		HubPark.set(level, sx + 1, FLOOR + 3, sz + 1, Blocks.BANNER.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
		HubPark.label(level, c[0] + 0.5, FLOOR + 4.5, c[1] + 0.5, "FOSSIL BEACH", ChatFormatting.GOLD, 1.8F);
		HubPark.label(level, c[0] + 0.5, FLOOR + 3.6, c[1] + 0.5, "Dig the speckled sand with a Fossil Shovel\n(from Professor Pebble at the museum)",
				ChatFormatting.WHITE, 0.6F);
	}

	private static ItemStack fossilStack(Fossil f) {
		ItemStack s = HubGames.toy(f.item(), 1, f.name() + " (fossil)", ChatFormatting.GOLD);
		CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.putString(FOSSIL, f.id());
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	private static String fossilOf(ItemStack s) {
		var data = s.get(DataComponents.CUSTOM_DATA);
		return data == null ? null : data.copyTag().getStringOr(FOSSIL, null);
	}

	/** Right-clicking a block: digging on Fossil Beach. Null: not that. */
	static InteractionResult useBlock(ServerPlayer p, BlockPos pos) {
		if (!DIG_AT.contains(pos)) {
			return null;
		}
		ServerLevel level = (ServerLevel) p.level();
		ItemStack held = p.getMainHandItem();
		if (!(HubGames.isToy(held) && held.getHoverName().getString().equals(SHOVEL))) {
			p.sendOverlayMessage(Component.literal("Something's buried here! You need a Fossil Shovel: ask Professor Pebble at the museum.").withStyle(ChatFormatting.YELLOW));
			return InteractionResult.SUCCESS;
		}
		long now = level.getGameTime();
		Long last = LAST_DIG.get(p.getUUID());
		if (last != null && now - last < 6) {
			return InteractionResult.SUCCESS;
		}
		LAST_DIG.put(p.getUUID(), now);
		int n = DUG.merge(pos, 1, Integer::sum);
		level.playSound(null, pos, SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.0F, 0.9F + n * 0.1F);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 14,
				0.3, 0.1, 0.3, 0.1);
		if (n < 3) {
			p.sendOverlayMessage(Component.literal("Dig, dig" + "!".repeat(n) + " (keep going)").withStyle(ChatFormatting.YELLOW));
			return InteractionResult.SUCCESS;
		}
		DUG.remove(pos);
		DIG_AT.remove(pos);
		level.setBlock(pos, Blocks.SAND.defaultBlockState(), 2);
		// A new spot gets speckled somewhere else.
		List<BlockPos> free = new ArrayList<>(DIG_SPOTS);
		free.removeAll(DIG_AT);
		free.remove(pos);
		if (!free.isEmpty()) {
			BlockPos next = free.get(level.getRandom().nextInt(free.size()));
			DIG_AT.add(next);
			level.setBlock(next, Blocks.SUSPICIOUS_SAND.defaultBlockState(), 2);
		}
		// Mostly fossils you haven't found yet, sometimes a little treasure.
		List<Fossil> want = new ArrayList<>();
		for (Fossil f : FOSSILS) {
			if (!GIVEN.containsKey("fossil:" + f.id()) && !has(p, f.id())) {
				want.add(f);
			}
		}
		var random = level.getRandom();
		if (want.isEmpty() || random.nextInt(4) == 0) {
			Fossil f = want.isEmpty() || random.nextBoolean() ? FOSSILS[random.nextInt(FOSSILS.length)] : want.get(random.nextInt(want.size()));
			if (want.isEmpty() || has(p, f.id())) {
				p.sendOverlayMessage(Component.literal("You found an old shell! Pretty. (Try another spot for a fossil.)").withStyle(ChatFormatting.AQUA));
				return InteractionResult.SUCCESS;
			}
			want = List.of(f);
		}
		Fossil f = want.get(random.nextInt(want.size()));
		HubGames.give(p, fossilStack(f));
		dev.rubymod.game.Alerts.title(p, "You found a fossil!", f.name() + ": take it to the museum!", ChatFormatting.GOLD);
		level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.3F);
		HubPark.burst(level, Vec3.atCenterOf(pos).add(0, 1.5, 0));
		return InteractionResult.SUCCESS;
	}

	private static boolean has(ServerPlayer p, String id) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (id.equals(fossilOf(p.getInventory().getItem(i)))) {
				return true;
			}
		}
		return false;
	}

	// ----- Giving things -----

	private static void donate(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		String name = p.getName().getString();
		List<String> got = new ArrayList<>();
		for (String fish : Fishing.caughtBy(level, name)) {
			if (GIVEN.putIfAbsent("fish:" + fish, name) == null) {
				got.add(fish);
			}
		}
		for (String bug : Butterflies.caughtBy(level, name)) {
			if (GIVEN.putIfAbsent("bug:" + bug, name) == null) {
				got.add(bug + " butterfly");
			}
		}
		int kept = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			String id = fossilOf(p.getInventory().getItem(i));
			if (id == null) {
				continue;
			}
			for (Fossil f : FOSSILS) {
				if (f.id().equals(id)) {
					if (GIVEN.putIfAbsent("fossil:" + id, name) == null) {
						got.add(f.name());
						p.getInventory().setItem(i, ItemStack.EMPTY);
					} else {
						kept++;
					}
				}
			}
		}
		p.inventoryMenu.broadcastChanges();
		if (got.isEmpty()) {
			p.sendSystemMessage(Component.literal(kept > 0 ? "We've already got those fossils! Keep them as souvenirs. Try digging for a different one."
					: "Nothing new this time! Catch fish and butterflies, or dig up fossils on Fossil Beach, and bring them to me.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		save(level);
		refresh(level);
		dev.rubymod.game.Alerts.title(p, "Thank you!", got.size() == 1 ? got.get(0) + " is on show!" : got.size() + " new things are on show!", ChatFormatting.GOLD);
		p.sendSystemMessage(Component.literal("Now on show, found by " + name + ": " + String.join(", ", got)).withStyle(ChatFormatting.GOLD));
		level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
		HubPark.burst(level, p.position().add(0, 3, 0));
	}

	private static void missing(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		int fish = count("fish:");
		int bugs = count("bug:");
		int fossils = count("fossil:");
		p.sendSystemMessage(Component.literal("The museum has " + fish + " of " + Fishing.names().size() + " fish, " + bugs + " of " + Butterflies.names().size()
				+ " butterflies and " + fossils + " of " + FOSSILS.length + " fossils.").withStyle(ChatFormatting.GOLD));
		List<String> want = new ArrayList<>();
		for (Fossil f : FOSSILS) {
			if (!GIVEN.containsKey("fossil:" + f.id())) {
				want.add(f.name());
			}
		}
		if (!want.isEmpty()) {
			p.sendSystemMessage(Component.literal("Fossils still to find: " + String.join(", ", want)).withStyle(ChatFormatting.YELLOW));
		}
		level.playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	private static int count(String prefix) {
		int n = 0;
		for (String k : GIVEN.keySet()) {
			if (k.startsWith(prefix)) {
				n++;
			}
		}
		return n;
	}

	// ----- On show -----

	/** Put everything given on show (again): fish in the tank, butterflies in the house, fossils on the plinths, the boards. */
	private static void refresh(ServerLevel level) {
		for (UUID id : TANK) {
			HubPark.discard(level, id);
		}
		TANK.clear();
		for (UUID id : FLUTTER.keySet()) {
			HubPark.discard(level, id);
		}
		FLUTTER.clear();
		var random = level.getRandom();
		for (String fish : Fishing.names()) {
			if (!GIVEN.containsKey("fish:" + fish)) {
				continue;
			}
			EntityType<? extends Mob> type = Fishing.swims(fish);
			Mob m = type == null ? null : type.create(level, EntitySpawnReason.EVENT);
			if (m == null) {
				continue;
			}
			m.snapTo(TX1 + 0.5 + random.nextDouble() * (TX2 - TX1), FLOOR + 1.5 + random.nextDouble() * 2, TZ1 + 0.5 + random.nextDouble() * (TZ2 - TZ1),
					random.nextFloat() * 360, 0);
			m.setPersistenceRequired();
			m.setPermanentlyInvulnerable(true);
			HubPark.keep(m);
			level.addFreshEntity(m);
			TANK.add(m.getUUID());
		}
		for (String bug : Butterflies.names()) {
			if (!GIVEN.containsKey("bug:" + bug)) {
				continue;
			}
			Vec3 at = new Vec3(BX1 + 0.5 + random.nextDouble() * (BX2 - BX1), FLOOR + 2 + random.nextDouble() * 2, BZ1 + 0.5 + random.nextDouble() * (BZ2 - BZ1));
			Display.ItemDisplay d = HubPark.model(level, at, random.nextFloat() * 360, Butterflies.model(bug), 0.5F, 0);
			if (d != null) {
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(10);
				FLUTTER.put(d.getUUID(), at);
			}
		}
		for (int k = 0; k < FOSSILS.length; k++) {
			Fossil f = FOSSILS[k];
			String who = GIVEN.get("fossil:" + f.id());
			UUID old = PLINTH.remove(f.id());
			if (old != null) {
				HubPark.discard(level, old);
			}
			UUID oldLabel = PLINTH.remove(f.id() + ":label");
			if (oldLabel != null) {
				HubPark.discard(level, oldLabel);
			}
			BlockPos p = plinth(k);
			if (who != null) {
				Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
				if (d != null) {
					d.snapTo(p.getX() + 0.5, FLOOR + 2.5, p.getZ() + 0.5, 0, 0);
					((dev.rubymod.mixin.ItemDisplayAccessor) d).rubymod$setItemStack(new ItemStack(f.item()));
					((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.8F), new Quaternionf()));
					((DisplayAccessor) d).rubymod$setBillboard(Display.BillboardConstraints.VERTICAL);
					HubPark.keep(d);
					level.addFreshEntity(d);
					PLINTH.put(f.id(), d.getUUID());
				}
			}
			Display.TextDisplay label = HubPark.label(level, p.getX() + 0.5, FLOOR + 3.4, p.getZ() + 0.5, who == null ? "?\n(not found yet)" : f.name() + "\nfound by " + who,
					who == null ? ChatFormatting.GRAY : ChatFormatting.GOLD, 0.5F);
			if (label != null) {
				PLINTH.put(f.id() + ":label", label.getUUID());
			}
		}
		board(level, "fish", new Vec3(-44.5, FLOOR + 3, -102.6), "FISH: " + count("fish:") + " of " + Fishing.names().size(), "fish:", Fishing.names());
		board(level, "bug", new Vec3(-52.5, FLOOR + 3, -102.6), "BUTTERFLIES: " + count("bug:") + " of " + Butterflies.names().size(), "bug:",
				Butterflies.names());
	}

	private static void board(ServerLevel level, String key, Vec3 at, String title, String prefix, List<String> names) {
		UUID old = BOARDS.remove(key);
		if (old != null) {
			HubPark.discard(level, old);
		}
		StringBuilder b = new StringBuilder(title).append('\n');
		int shown = 0;
		for (String n : names) {
			String who = GIVEN.get(prefix + n);
			if (who != null) {
				b.append(n).append(" (").append(who).append(")\n");
				shown++;
			}
		}
		if (shown == 0) {
			b.append("Nothing yet: bring Professor Pebble your finds!");
		}
		Display.TextDisplay d = HubPark.label(level, at.x, at.y, at.z, b.toString().trim(), ChatFormatting.AQUA, 0.45F);
		if (d != null) {
			BOARDS.put(key, d.getUUID());
		}
	}

	static void tick(ServerLevel level, int t) {
		tick = t;
		if (tick % 10 != 0) {
			return;
		}
		// Butterflies flit about their house.
		var random = level.getRandom();
		for (Map.Entry<UUID, Vec3> e : FLUTTER.entrySet()) {
			Entity d = level.getEntity(e.getKey());
			if (d == null) {
				continue;
			}
			Vec3 to = new Vec3(BX1 + 0.3 + random.nextDouble() * (BX2 - BX1 + 0.4), FLOOR + 1.6 + random.nextDouble() * 3, BZ1 + 0.3 + random.nextDouble() * (BZ2 - BZ1 + 0.4));
			Vec3 dir = to.subtract(d.position());
			d.teleportTo(to.x, to.y, to.z);
			d.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
		}
		// Fish stay in the tank.
		if (tick % 100 == 0) {
			for (UUID id : TANK) {
				Entity e = level.getEntity(id);
				if (e != null && (e.getX() < TX1 - 0.5 || e.getX() > TX2 + 1.5 || e.getZ() < TZ1 - 0.5 || e.getZ() > TZ2 + 1.5 || e.getY() > FLOOR + 5)) {
					e.teleportTo((TX1 + TX2) / 2.0 + 0.5, FLOOR + 2, (TZ1 + TZ2) / 2.0 + 0.5);
				}
			}
		}
	}

	static boolean near(ServerLevel level) {
		for (ServerPlayer p : level.players()) {
			if (Math.hypot(p.getX() + 48, p.getZ() + 107) < 40 || Math.hypot(p.getX() - BEACH[0][0], p.getZ() - BEACH[0][1]) < 30) {
				return true;
			}
		}
		return false;
	}

	// ----- Saving -----

	private static Path file(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("museum.txt");
	}

	private static void load(ServerLevel level) {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file(level);
			if (Files.exists(f)) {
				for (String line : Files.readAllLines(f)) {
					int bar = line.lastIndexOf('|');
					if (bar > 0) {
						GIVEN.put(line.substring(0, bar), line.substring(bar + 1));
					}
				}
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the museum", e);
		}
	}

	private static void save(ServerLevel level) {
		StringBuilder b = new StringBuilder();
		for (var e : GIVEN.entrySet()) {
			b.append(e.getKey()).append('|').append(e.getValue()).append('\n');
		}
		try {
			Files.createDirectories(file(level).getParent());
			dev.rubymod.game.SafeFiles.write(file(level), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the museum", e);
		}
	}

	/** Someone's changed their name: the museum's labels say their new one. */
	static void rename(ServerLevel level, String before, String now) {
		load(level);
		boolean changed = false;
		for (var e : GIVEN.entrySet()) {
			if (before.equals(e.getValue())) {
				e.setValue(now);
				changed = true;
			}
		}
		if (changed) {
			save(level);
			if (!BOARDS.isEmpty() || !PLINTH.isEmpty()) {
				refresh(level);
			}
		}
	}
}
