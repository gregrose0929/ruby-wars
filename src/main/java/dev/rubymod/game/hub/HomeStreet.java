package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Home Street: a row of little houses north-west of the plaza. Right-click a red mailbox to make that house yours (one
 * each), then fill it with furniture from Fern's Furniture Shop on Main Street: hold a piece and right-click the floor to
 * put it down, right-click it to turn it, crouch and right-click (empty hand) to pick it up again. Paint pots repaint the
 * walls, floor samples change the floor. Anyone can pop round to visit. Kept with the world.
 */
public final class HomeStreet {
	private HomeStreet() {
	}

	/** Home Street (south) and Main Street (north), one island. */
	static final int[][] CIRCLES = {{-62, -52, 28}, {-62, -95, 28}, {-46, -108, 16}};
	static final int MX = -62;
	static final int MZ = -72;
	static final Vec3 LAND = new Vec3(-61.5, FLOOR + 1, -42.5);
	private static final String FURN = "rubymod_furn";
	private static final int MAX_PIECES = 150;
	private static final int[] HOUSE_X = {-85, -75, -57, -47};

	// ----- Furniture -----

	private record Part(int dx, int dy, int dz, BlockState state) {
	}

	/** A piece of furniture: what the shop calls it, its icon, and its blocks for a facing (offsets from where it's put). */
	private record Furn(String id, String name, Item icon, Function<Direction, List<Part>> parts) {
	}

	private static final List<Furn> FURNITURE = new ArrayList<>();
	private static final Map<String, Furn> BY_ID = new HashMap<>();

	private static void furn(String id, String name, Item icon, Function<Direction, List<Part>> parts) {
		Furn f = new Furn(id, name, icon, parts);
		FURNITURE.add(f);
		BY_ID.put(id, f);
	}

	private static Function<Direction, List<Part>> one(BlockState s) {
		return f -> List.of(new Part(0, 0, 0, s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.setValue(BlockStateProperties.HORIZONTAL_FACING, f) : s));
	}

	/** Faces you (like a chest or an oven does when you put it down). */
	private static Function<Direction, List<Part>> facingYou(BlockState s) {
		return f -> List.of(new Part(0, 0, 0, s.setValue(BlockStateProperties.HORIZONTAL_FACING, f.getOpposite())));
	}

	private static Function<Direction, List<Part>> stack(BlockState... s) {
		return f -> {
			List<Part> l = new ArrayList<>();
			for (int i = 0; i < s.length; i++) {
				l.add(new Part(0, i, 0, s[i]));
			}
			return l;
		};
	}

	private static Function<Direction, List<Part>> couch(BlockState s) {
		return f -> {
			Direction side = f.getClockWise();
			BlockState st = s.setValue(StairBlock.FACING, f);
			return List.of(new Part(0, 0, 0, st), new Part(side.getStepX(), 0, side.getStepZ(), st));
		};
	}

	private static Function<Direction, List<Part>> bed(DyeColor c) {
		return f -> {
			BlockState b = Blocks.BED.pick(c).defaultBlockState().setValue(BedBlock.FACING, f);
			return List.of(new Part(0, 0, 0, b.setValue(BedBlock.PART, BedPart.FOOT)), new Part(f.getStepX(), 0, f.getStepZ(), b.setValue(BedBlock.PART, BedPart.HEAD)));
		};
	}

	private static Function<Direction, List<Part>> rug(DyeColor c) {
		return f -> {
			List<Part> l = new ArrayList<>();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					l.add(new Part(dx, 0, dz, Blocks.CARPET.pick(c).defaultBlockState()));
				}
			}
			return l;
		};
	}

	static {
		furn("chair", "Wooden Chair", Items.OAK_STAIRS, one(Blocks.OAK_STAIRS.defaultBlockState()));
		furn("armchair_red", "Red Armchair", Items.WOOL_STAIRS.pick(DyeColor.RED), one(Blocks.WOOL_STAIRS.pick(DyeColor.RED).defaultBlockState()));
		furn("couch_blue", "Blue Sofa", Items.WOOL_STAIRS.pick(DyeColor.BLUE), couch(Blocks.WOOL_STAIRS.pick(DyeColor.BLUE).defaultBlockState()));
		furn("couch_pink", "Pink Sofa", Items.WOOL_STAIRS.pick(DyeColor.PINK), couch(Blocks.WOOL_STAIRS.pick(DyeColor.PINK).defaultBlockState()));
		furn("couch_green", "Green Sofa", Items.WOOL_STAIRS.pick(DyeColor.GREEN), couch(Blocks.WOOL_STAIRS.pick(DyeColor.GREEN).defaultBlockState()));
		furn("table", "Little Table", Items.OAK_FENCE, stack(Blocks.OAK_FENCE.defaultBlockState(), Blocks.OAK_PRESSURE_PLATE.defaultBlockState()));
		furn("bed_red", "Red Bed", Items.BED.pick(DyeColor.RED), bed(DyeColor.RED));
		furn("bed_blue", "Blue Bed", Items.BED.pick(DyeColor.LIGHT_BLUE), bed(DyeColor.LIGHT_BLUE));
		furn("bed_pink", "Pink Bed", Items.BED.pick(DyeColor.PINK), bed(DyeColor.PINK));
		furn("bookshelf", "Bookshelf", Items.BOOKSHELF, one(Blocks.BOOKSHELF.defaultBlockState()));
		furn("lamp", "Floor Lamp", Items.LANTERN, stack(Blocks.SPRUCE_FENCE.defaultBlockState(), Blocks.LANTERN.defaultBlockState()));
		furn("ceiling", "Ceiling Light", Items.SEA_LANTERN, f -> List.of(new Part(0, 0, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true))));
		furn("pot_poppy", "Pot of Poppies", Items.POPPY, one(Blocks.POTTED_POPPY.defaultBlockState()));
		furn("pot_tulip", "Pot of Tulips", Items.PINK_TULIP, one(Blocks.POTTED_PINK_TULIP.defaultBlockState()));
		furn("pot_cactus", "Little Cactus", Items.CACTUS, one(Blocks.POTTED_CACTUS.defaultBlockState()));
		furn("bush", "Flowery Bush", Items.FLOWERING_AZALEA, one(Blocks.FLOWERING_AZALEA.defaultBlockState()));
		furn("vase", "Big Vase", Items.DECORATED_POT, one(Blocks.DECORATED_POT.defaultBlockState()));
		furn("rug_red", "Red Rug", Items.CARPET.pick(DyeColor.RED), rug(DyeColor.RED));
		furn("rug_blue", "Blue Rug", Items.CARPET.pick(DyeColor.LIGHT_BLUE), rug(DyeColor.LIGHT_BLUE));
		furn("rug_purple", "Purple Rug", Items.CARPET.pick(DyeColor.PURPLE), rug(DyeColor.PURPLE));
		furn("oven", "Oven", Items.SMOKER, facingYou(Blocks.SMOKER.defaultBlockState()));
		furn("sink", "Kitchen Sink", Items.CAULDRON, one(Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3)));
		furn("counter", "Kitchen Counter", Items.BARREL, one(Blocks.BARREL.defaultBlockState()));
		furn("toybox", "Toy Box", Items.CHEST, facingYou(Blocks.CHEST.defaultBlockState()));
		furn("piano", "Piano (it plays!)", Items.NOTE_BLOCK, one(Blocks.NOTE_BLOCK.defaultBlockState()));
		furn("jukebox", "Record Player", Items.JUKEBOX, one(Blocks.JUKEBOX.defaultBlockState()));
		furn("cake", "Cake", Items.CAKE, one(Blocks.CAKE.defaultBlockState()));
		furn("fridge", "Fridge", Items.IRON_BLOCK, stack(Blocks.IRON_BLOCK.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState()));
		furn("tv", "Telly", Items.COAL_BLOCK,
				f -> List.of(new Part(0, 0, 0, Blocks.SPRUCE_SLAB.defaultBlockState()), new Part(0, 1, 0, Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState())));
	}

	/** Wall paints and floors, shown on the shop's sample walls. */
	private static final DyeColor[] PAINTS = {DyeColor.WHITE, DyeColor.PINK, DyeColor.LIGHT_BLUE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.ORANGE,
		DyeColor.PURPLE, DyeColor.RED};
	private static final String[] PAINT_NAMES = {"White", "Pink", "Sky Blue", "Sunny Yellow", "Lime Green", "Orange", "Purple", "Red"};
	private static final BlockState[] FLOORS = {Blocks.OAK_PLANKS.defaultBlockState(), Blocks.BIRCH_PLANKS.defaultBlockState(),
		Blocks.CHERRY_PLANKS.defaultBlockState(), Blocks.DARK_OAK_PLANKS.defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState(),
		Blocks.QUARTZ_BLOCK.defaultBlockState()};
	private static final String[] FLOOR_NAMES = {"Oak Floor", "Birch Floor", "Cherry Floor", "Dark Wood Floor", "Blue Tiles", "Checked Tiles"};

	// ----- The houses -----

	private record Placed(String id, int dx, int dy, int dz, Direction f) {
	}

	private static final class Home {
		String owner;
		int wall;
		int floor;
		final List<Placed> furniture = new ArrayList<>();
	}

	/** A house: its corner (x0, z0, 9x9 outside, 7x7 inside), and whether it's on the north side (door to the south). */
	private record House(int index, int x0, int z0, boolean north) {
		int doorZ() {
			return north ? z0 + 8 : z0;
		}

		int outZ() {
			return north ? z0 + 9 : z0 - 1;
		}

		BlockPos mailbox() {
			return new BlockPos(x0 + 8, FLOOR + 2, outZ());
		}

		/** Some houses have an upstairs (just to look at: the rooms you decorate are downstairs). */
		int upstairs() {
			return index == 1 || index == 4 || index == 6 ? 4 : 0;
		}

		boolean inside(BlockPos p) {
			return p.getX() > x0 && p.getX() < x0 + 8 && p.getZ() > z0 && p.getZ() < z0 + 8 && p.getY() > FLOOR && p.getY() <= FLOOR + 4;
		}

		/** Inside or one of its walls / its floor (for paint and floors). */
		boolean shell(BlockPos p) {
			return p.getX() >= x0 && p.getX() <= x0 + 8 && p.getZ() >= z0 && p.getZ() <= z0 + 8 && p.getY() >= FLOOR && p.getY() <= FLOOR + 5;
		}

		BlockPos origin() {
			return new BlockPos(x0, FLOOR, z0);
		}
	}

	private static final List<House> HOUSES = new ArrayList<>();
	private static final Home[] HOMES = new Home[8];
	private static final UUID[] LABELS = new UUID[8];
	private static final Map<BlockPos, String> SHOP = new HashMap<>();
	private static final Map<UUID, Long> MOVE_OUT = new HashMap<>();
	private static boolean loaded;

	static {
		for (int i = 0; i < 4; i++) {
			HOUSES.add(new House(i, HOUSE_X[i], -67, true));
		}
		for (int i = 0; i < 4; i++) {
			HOUSES.add(new House(4 + i, HOUSE_X[i], -45, false));
		}
	}

	static void build(ServerLevel level) {
		SHOP.clear();
		MOVE_OUT.clear();
		load(level);
		HubPark.blob(level, CIRCLES, FLOOR);
		street(level);
		square(level);
		bunting(level);
		for (House h : HOUSES) {
			house(level, h);
		}
		shop(level);
		MainStreet.build(level);
		Museum.build(level);
		HubPark.travelPad(level, -62, FLOOR, -42, "home");
		HubPark.label(level, -61.5, FLOOR + 7, -38.5, "HOME STREET", ChatFormatting.GOLD, 2.6F);
		HubPark.label(level, -61.5, FLOOR + 5.6, -38.5, "Pick a house with a red mailbox, and make it yours!", ChatFormatting.WHITE, 0.8F);
	}

	private static void street(ServerLevel level) {
		BlockState road = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		BlockState walk = Blocks.SMOOTH_STONE.defaultBlockState();
		// Home Street runs east-west; Main Street runs north from its middle.
		for (int x = -88; x <= -36; x++) {
			for (int z = -57; z <= -47; z++) {
				boolean pave = z == -57 || z == -56 || z == -48 || z == -47;
				boolean line = z == -52 && Math.floorMod(x, 4) < 2;
				HubPark.set(level, x, FLOOR, z, pave ? walk : line ? Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState() : road);
			}
		}
		for (int z = -120; z <= -58; z++) {
			for (int x = -67; x <= -57; x++) {
				boolean pave = x <= -66 || x >= -58;
				boolean line = x == -62 && Math.floorMod(z, 4) < 2;
				HubPark.set(level, x, FLOOR, z, pave ? walk : line ? Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState() : road);
			}
		}
		// Street lamps and trees along the pavements.
		for (int x = -86; x <= -38; x += 8) {
			if (x < -67 || x > -57) {
				lamp(level, x, -57);
				lamp(level, x, -47);
			}
		}
		for (int z = -116; z <= -64; z += 9) {
			lamp(level, -67, z);
			lamp(level, -57, z);
		}
		// The little green by the travel pad: benches and flowers.
		for (int x = -66; x <= -58; x++) {
			for (int z = -45; z <= -37; z++) {
				if (Math.abs(x + 62) > 1 || z < -43 || z > -41) {
					HubPark.set(level, x, FLOOR + 1, z, (x + z) % 3 == 0 ? Blocks.POPPY.defaultBlockState() : (x + z) % 3 == 1 ? Blocks.DANDELION.defaultBlockState()
							: Blocks.AIR.defaultBlockState());
				}
			}
		}
		HubPark.set(level, -65, FLOOR + 1, -39, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
		HubPark.set(level, -59, FLOOR + 1, -39, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
	}

	/** The town square, south of the green: a fountain, a clock tower, benches, trees in planters, flower beds. */
	private static void square(ServerLevel level) {
		int x1 = -71;
		int x2 = -53;
		int z1 = -36;
		int z2 = -24;
		int cx = -62;
		int cz = -30;
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				boolean border = x == x1 || x == x2 || z == z1 || z == z2;
				double d = Math.hypot(x - cx, z - cz);
				BlockState pave = border ? Blocks.POLISHED_ANDESITE.defaultBlockState() : d < 5.5 && d > 4.4 ? Blocks.BRICKS.defaultBlockState()
						: (x + z) % 2 == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
				HubPark.set(level, x, FLOOR, z, pave);
				for (int y = 1; y <= 12; y++) {
					HubPark.set(level, x, FLOOR + y, z, Blocks.AIR.defaultBlockState());
				}
			}
		}
		// The fountain: a round basin, a pillar, a bowl at the top that spills over into the basin.
		for (int x = cx - 3; x <= cx + 3; x++) {
			for (int z = cz - 3; z <= cz + 3; z++) {
				double d = Math.hypot(x - cx, z - cz);
				if (d > 3.4) {
					continue;
				}
				boolean rim = d > 2.5;
				HubPark.set(level, x, FLOOR, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
				HubPark.set(level, x, FLOOR + 1, z, rim ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : Blocks.WATER.defaultBlockState());
			}
		}
		HubPark.box(level, cx, FLOOR + 1, cz, cx, FLOOR + 3, cz, Blocks.QUARTZ_PILLAR.defaultBlockState());
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				HubPark.set(level, cx + dx, FLOOR + 4, cz + dz, dx == 0 && dz == 0 ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.SMOOTH_QUARTZ.defaultBlockState());
			}
		}
		HubPark.set(level, cx, FLOOR + 5, cz, Blocks.WATER.defaultBlockState());
		// The clock tower, in the north-west corner: brick, a clock face on each side, a pointed roof and a bell.
		int tx = x1 + 2;
		int tz = z2 - 2;
		for (int y = 1; y <= 13; y++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
					BlockState b = corner ? Blocks.STONE_BRICKS.defaultBlockState() : y == 10 ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.BRICKS.defaultBlockState();
					HubPark.set(level, tx + dx, FLOOR + y, tz + dz, b);
				}
			}
		}
		HubPark.set(level, tx, FLOOR + 14, tz, Blocks.BELL.defaultBlockState());
		for (int k = 0; k <= 2; k++) {
			int r = 2 - k;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
						HubPark.set(level, tx + dx, FLOOR + 15 + k, tz + dz, Blocks.DEEPSLATE_TILES.defaultBlockState());
					}
				}
			}
		}
		HubPark.set(level, tx, FLOOR + 18, tz, Blocks.GOLD_BLOCK.defaultBlockState());
		for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			BlockPos face = new BlockPos(tx + d.getStepX() * 2, FLOOR + 10, tz + d.getStepZ() * 2);
			net.minecraft.world.entity.decoration.GlowItemFrame f = new net.minecraft.world.entity.decoration.GlowItemFrame(level, face, d);
			f.setItem(new net.minecraft.world.item.ItemStack(Items.CLOCK), false);
			f.setInvisible(true);
			((dev.rubymod.mixin.ItemFrameAccessor) f).rubymod$setFixed(true);
			f.setPermanentlyInvulnerable(true);
			HubPark.keep(f);
			level.addFreshEntity(f);
		}
		// Benches facing the fountain, trees in brick planters, flower beds, lamps at the corners.
		int[][] benches = {{cx - 6, cz, 0}, {cx + 6, cz, 1}, {cx, cz - 5, 2}, {cx, cz + 5, 3}};
		Direction[] face = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};
		for (int[] b : benches) {
			for (int k = -1; k <= 1; k++) {
				boolean alongZ = b[2] < 2;
				HubPark.set(level, b[0] + (alongZ ? 0 : k), FLOOR + 1, b[1] + (alongZ ? k : 0), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, face[b[2]]));
			}
		}
		for (int[] t : new int[][] {{x2 - 2, z1 + 2}, {x2 - 2, z2 - 2}, {x1 + 2, z1 + 2}}) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					HubPark.set(level, t[0] + dx, FLOOR + 1, t[1] + dz, dx == 0 && dz == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.BRICK_SLAB.defaultBlockState());
				}
			}
			Dressing.tree(level, t[0], FLOOR + 2, t[1], t[0] < cx ? 2 : 0);
		}
		for (int x = x1 + 5; x <= x2 - 5; x++) {
			for (int z : new int[] {z1 + 1, z2 - 1}) {
				if (Math.abs(x - cx) > 1) {
					HubPark.set(level, x, FLOOR, z, Blocks.GRASS_BLOCK.defaultBlockState());
					HubPark.set(level, x, FLOOR + 1, z, (x % 3 == 0 ? Blocks.RED_TULIP : x % 3 == 1 ? Blocks.ALLIUM : Blocks.OXEYE_DAISY).defaultBlockState());
				}
			}
		}
		for (int[] l : new int[][] {{x1 + 1, z1 + 1}, {x2 - 1, z2 - 1}, {x1 + 5, z2 - 1}}) {
			lamp(level, l[0], l[1]);
		}
		HubPark.label(level, cx + 0.5, FLOOR + 7.2, cz + 0.5, "TOWN SQUARE", ChatFormatting.GOLD, 1.4F);
	}

	/** Strings of little flags across the streets, from lamp post to lamp post. */
	private static void bunting(ServerLevel level) {
		List<Vec3[]> strings = new ArrayList<>();
		for (int z = -116; z <= -64; z += 9) {
			strings.add(new Vec3[] {new Vec3(-66.5, FLOOR + 4.7, z + 0.5), new Vec3(-56.5, FLOOR + 4.7, z + 0.5)});
		}
		for (int x = -86; x <= -38; x += 8) {
			if (x < -67 || x > -57) {
				strings.add(new Vec3[] {new Vec3(x + 0.5, FLOOR + 4.7, -56.5), new Vec3(x + 0.5, FLOOR + 4.7, -46.5)});
			}
		}
		DyeColor[] flags = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIGHT_BLUE, DyeColor.LIME, DyeColor.PINK, DyeColor.ORANGE};
		int n = 0;
		for (Vec3[] s : strings) {
			Vec3 a = s[0];
			Vec3 b = s[1];
			int steps = 12;
			Vec3 last = a;
			boolean alongX = Math.abs(b.x - a.x) > Math.abs(b.z - a.z);
			for (int i = 1; i <= steps; i++) {
				double t = i / (double) steps;
				Vec3 p = a.lerp(b, t).add(0, -Math.sin(t * Math.PI) * 1.1, 0);
				HubPark.beam(level, last, p, 0.04F, 0.04F, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
				if (i < steps) {
					org.joml.Quaternionf rot = alongX ? new org.joml.Quaternionf().rotateZ((float) Math.PI / 4) : new org.joml.Quaternionf().rotateX((float) Math.PI / 4);
					org.joml.Vector3f size = alongX ? new org.joml.Vector3f(0.36F, 0.36F, 0.03F) : new org.joml.Vector3f(0.03F, 0.36F, 0.36F);
					HubPark.piece(level, p.add(0, -0.26, 0), Blocks.WOOL.pick(flags[n++ % flags.length]).defaultBlockState(), size, rot);
				}
				last = p;
			}
		}
	}

	/** A street lamp: a dark post with a little arm and a lantern hanging from it. */
	private static void lamp(ServerLevel level, int x, int z) {
		HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 3, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		HubPark.set(level, x, FLOOR + 4, z, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
		HubPark.set(level, x, FLOOR + 5, z, Blocks.LANTERN.defaultBlockState());
	}

	private static final BlockState[] ROOFS = {Blocks.BRICK_STAIRS.defaultBlockState(), Blocks.DARK_OAK_STAIRS.defaultBlockState(),
		Blocks.SPRUCE_STAIRS.defaultBlockState(), Blocks.CHERRY_STAIRS.defaultBlockState(), Blocks.PRISMARINE_BRICK_STAIRS.defaultBlockState(),
		Blocks.MUD_BRICK_STAIRS.defaultBlockState(), Blocks.CONCRETE_STAIRS.pick(DyeColor.RED).defaultBlockState(),
		Blocks.CONCRETE_STAIRS.pick(DyeColor.BLUE).defaultBlockState()};
	private static final BlockState[] RIDGES = {Blocks.BRICKS.defaultBlockState(), Blocks.DARK_OAK_PLANKS.defaultBlockState(),
		Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.CHERRY_PLANKS.defaultBlockState(), Blocks.PRISMARINE_BRICKS.defaultBlockState(),
		Blocks.MUD_BRICKS.defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState()};

	private static Home home(int i) {
		if (HOMES[i] == null) {
			HOMES[i] = new Home();
			HOMES[i].wall = (i * 3) % PAINTS.length;
		}
		return HOMES[i];
	}

	private static void house(ServerLevel level, House h) {
		Home home = home(h.index());
		int x0 = h.x0();
		int z0 = h.z0();
		int top = h.upstairs();
		HubPark.box(level, x0 - 1, FLOOR + 1, z0 - 1, x0 + 9, FLOOR + 17, z0 + 9, Blocks.AIR.defaultBlockState());
		HubPark.box(level, x0, FLOOR, z0, x0 + 8, FLOOR, z0 + 8, Blocks.STONE_BRICKS.defaultBlockState());
		walls(level, h, home);
		floor(level, h, home);
		// The ceiling, and a gable roof over it.
		HubPark.box(level, x0, FLOOR + 5, z0, x0 + 8, FLOOR + 5, z0 + 8, Blocks.SPRUCE_PLANKS.defaultBlockState());
		// A warm light in the middle of the ceiling: windows glow at night.
		HubPark.set(level, x0 + 4, FLOOR + 5, z0 + 4, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
		if (top > 0) {
			HubPark.box(level, x0, FLOOR + 5 + top, z0, x0 + 8, FLOOR + 5 + top, z0 + 8, Blocks.SPRUCE_PLANKS.defaultBlockState());
			HubPark.set(level, x0 + 4, FLOOR + 6, z0 + 4, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
		}
		BlockState stair = ROOFS[h.index() % ROOFS.length];
		for (int k = 0; k <= 4; k++) {
			for (int x = x0 - 1; x <= x0 + 9; x++) {
				if (k == 4) {
					HubPark.set(level, x, FLOOR + 6 + top + k - 1, z0 + 4, RIDGES[h.index() % RIDGES.length]);
					continue;
				}
				HubPark.set(level, x, FLOOR + 5 + top + k, z0 - 1 + k, stair.setValue(StairBlock.FACING, Direction.SOUTH));
				HubPark.set(level, x, FLOOR + 5 + top + k, z0 + 9 - k, stair.setValue(StairBlock.FACING, Direction.NORTH));
			}
			// The gable ends, filled in.
			if (k > 0 && k < 4) {
				for (int z = z0 + k; z <= z0 + 8 - k; z++) {
					HubPark.set(level, x0, FLOOR + 5 + top + k, z, RIDGES[h.index() % RIDGES.length]);
					HubPark.set(level, x0 + 8, FLOOR + 5 + top + k, z, RIDGES[h.index() % RIDGES.length]);
				}
			}
		}
		// A brick chimney, with a wisp of smoke.
		int cz = h.north() ? z0 + 2 : z0 + 6;
		HubPark.box(level, x0 + 6, FLOOR + 6 + top, cz, x0 + 6, FLOOR + 11 + top, cz, Blocks.BRICKS.defaultBlockState());
		HubPark.set(level, x0 + 6, FLOOR + 12 + top, cz, Blocks.CAMPFIRE.defaultBlockState());
		// Shutters either side of the front windows.
		Direction outward = h.north() ? Direction.SOUTH : Direction.NORTH;
		BlockState shutter = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, true)
				.setValue(net.minecraft.world.level.block.TrapDoorBlock.FACING, outward);
		for (int sx : new int[] {x0 + 1, x0 + 3, x0 + 5, x0 + 7}) {
			for (int y = FLOOR + 2; y <= FLOOR + 3; y++) {
				HubPark.set(level, sx, y, h.outZ(), shutter);
			}
			if (top > 0) {
				for (int y = FLOOR + 6; y <= FLOOR + 7; y++) {
					HubPark.set(level, sx, y, h.outZ(), shutter);
				}
			}
		}
		// The front door.
		Direction out = h.north() ? Direction.SOUTH : Direction.NORTH;
		BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, out.getOpposite());
		HubPark.set(level, x0 + 4, FLOOR + 1, h.doorZ(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		HubPark.set(level, x0 + 4, FLOOR + 2, h.doorZ(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		// A little porch roof over the door, with a lantern.
		for (int x = x0 + 3; x <= x0 + 5; x++) {
			HubPark.set(level, x, FLOOR + 4, h.outZ(), stair.setValue(StairBlock.FACING, out.getOpposite())
					.setValue(StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
		}
		HubPark.set(level, x0 + 3, FLOOR + 3, h.outZ(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		// The garden path, flowers, and the mailbox.
		int dz = h.north() ? 1 : -1;
		for (int k = 1; k <= 2; k++) {
			int z = h.doorZ() + dz * k;
			HubPark.set(level, x0 + 4, FLOOR, z, Blocks.GRAVEL.defaultBlockState());
			for (int x = x0; x <= x0 + 8 && k == 1; x++) {
				if (Math.abs(x - x0 - 4) > 1) {
					HubPark.set(level, x, FLOOR + 1, z, Math.floorMod(x, 2) == 0 ? Blocks.POPPY.defaultBlockState() : Blocks.AZURE_BLUET.defaultBlockState());
				}
			}
		}
		BlockPos mb = h.mailbox();
		HubPark.set(level, mb.getX(), FLOOR + 1, mb.getZ(), Blocks.OAK_FENCE.defaultBlockState());
		HubPark.set(level, mb.getX(), mb.getY(), mb.getZ(), Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
		nameLabel(level, h);
		for (Placed f : home.furniture) {
			put(level, h, f);
		}
	}

	private static void walls(ServerLevel level, House h, Home home) {
		BlockState wall = Blocks.CONCRETE.pick(PAINTS[home.wall]).defaultBlockState();
		BlockState trim = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
		int x0 = h.x0();
		int z0 = h.z0();
		for (int x = x0; x <= x0 + 8; x++) {
			for (int z = z0; z <= z0 + 8; z++) {
				boolean edgeX = x == x0 || x == x0 + 8;
				boolean edgeZ = z == z0 || z == z0 + 8;
				if (!edgeX && !edgeZ) {
					continue;
				}
				for (int y = FLOOR + 1; y <= FLOOR + 4 + h.upstairs(); y++) {
					if (x == x0 + 4 && z == h.doorZ() && y <= FLOOR + 2) {
						continue;
					}
					boolean corner = edgeX && edgeZ;
					int along = edgeZ ? x - x0 : z - z0;
					boolean window = !corner && (y == FLOOR + 2 || y == FLOOR + 3 || h.upstairs() > 0 && (y == FLOOR + 6 || y == FLOOR + 7))
							&& (along == 2 || along == 6 || y > FLOOR + 5 && along == 4);
					HubPark.set(level, x, y, z, corner ? trim : window ? Blocks.GLASS_PANE.defaultBlockState() : wall);
				}
			}
		}
		// Panes join up to whatever's beside them.
		for (int x = x0; x <= x0 + 8; x++) {
			for (int z = z0; z <= z0 + 8; z++) {
				for (int y = FLOOR + 2; y <= FLOOR + 7; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = level.getBlockState(p);
					if (s.is(Blocks.GLASS_PANE)) {
						level.setBlock(p, Block.updateFromNeighbourShapes(s, level, p), 2);
					}
				}
			}
		}
	}

	private static void floor(ServerLevel level, House h, Home home) {
		for (int x = h.x0() + 1; x <= h.x0() + 7; x++) {
			for (int z = h.z0() + 1; z <= h.z0() + 7; z++) {
				BlockState f = FLOORS[home.floor];
				if (home.floor == FLOORS.length - 1 && (x + z) % 2 == 0) {
					f = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
				}
				HubPark.set(level, x, FLOOR, z, f);
			}
		}
	}

	private static void nameLabel(ServerLevel level, House h) {
		if (LABELS[h.index()] != null) {
			Entity old = level.getEntity(LABELS[h.index()]);
			if (old != null) {
				old.discard();
			}
		}
		Home home = home(h.index());
		double z = h.north() ? h.z0() + 9.1 : h.z0() - 0.1;
		Display.TextDisplay d = home.owner == null
				? HubPark.label(level, h.x0() + 4.5, FLOOR + 3.6, z, "FOR SALE\nright-click the red mailbox!", ChatFormatting.GREEN, 0.8F)
				: HubPark.label(level, h.x0() + 4.5, FLOOR + 3.6, z, home.owner + "'s House", ChatFormatting.GOLD, 1.0F);
		LABELS[h.index()] = d == null ? null : d.getUUID();
	}

	// ----- The furniture shop -----

	/** Fern's Furniture Shop, on Main Street's east side: every piece out on show; right-click one to take it. */
	private static void shop(ServerLevel level) {
		int x1 = -56;
		int x2 = -38;
		int z1 = -90;
		int z2 = -70;
		BlockState wall = Blocks.STRIPPED_BIRCH_LOG.defaultBlockState();
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
				HubPark.set(level, x, FLOOR, z, (x + z) % 2 == 0 ? Blocks.BIRCH_PLANKS.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
				for (int y = 1; y <= 6; y++) {
					boolean front = x == x1;
					boolean door = front && y <= 3 && z >= -81 && z <= -79;
					boolean transom = front && y == 4 && z >= -81 && z <= -79;
					boolean window = front && y >= 2 && y <= 4 && (z >= -88 && z <= -84 || z >= -76 && z <= -72);
					boolean column = front && (z == z1 || z == z2 || z == -82 || z == -78 || z == -89 || z == -71 || z == -83 || z == -77);
					HubPark.set(level, x, FLOOR + y, z, !edge || door ? Blocks.AIR.defaultBlockState() : window || transom ? Blocks.GLASS_PANE.defaultBlockState()
							: column ? Blocks.QUARTZ_PILLAR.defaultBlockState() : front ? Blocks.CONCRETE.pick(DyeColor.CYAN).defaultBlockState() : wall);
				}
				HubPark.set(level, x, FLOOR + 7, z, edge ? Blocks.STRIPPED_OAK_LOG.defaultBlockState() : Blocks.BIRCH_PLANKS.defaultBlockState());
			}
		}
		for (int x = x1 + 3; x < x2; x += 4) {
			for (int z = z1 + 3; z < z2; z += 4) {
				HubPark.set(level, x, FLOOR + 6, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			}
		}
		// Display rows: every piece of furniture out on show (beds and sofas take two spots).
		int col = 0;
		int row = 0;
		int[] cols = {-52, -50, -48, -46, -44, -42};
		int[] rows = {-87, -84, -81, -78, -75, -72};
		for (Furn f : FURNITURE) {
			boolean wide = f.id().startsWith("bed") || f.id().startsWith("couch");
			if (wide && col == cols.length - 1) {
				col = 0;
				row++;
			}
			if (row >= rows.length) {
				break;
			}
			int x = cols[col];
			int z = rows[row];
			BlockPos at = new BlockPos(x, FLOOR + 1, z);
			if (f.id().equals("ceiling")) {
				at = new BlockPos(x, FLOOR + 3, z);
				HubPark.box(level, x, FLOOR + 4, z, x, FLOOR + 6, z, Blocks.IRON_CHAIN.defaultBlockState());
			}
			for (Part pt : f.parts().apply(f.id().startsWith("bed") ? Direction.NORTH : Direction.SOUTH)) {
				BlockPos q = at.offset(pt.dx(), pt.dy(), pt.dz());
				level.setBlock(q, pt.state(), 18);
				SHOP.put(q, f.id());
			}
			double lx = x + (wide ? 0.0 : 0.5);
			if (f.parts().apply(Direction.SOUTH).size() == 2 && wide) {
				lx = x + (f.id().startsWith("couch") ? 0.0 : 0.5);
			}
			HubPark.label(level, lx, FLOOR + (f.id().equals("ceiling") ? 2.4 : f.id().equals("lamp") || f.id().equals("fridge") || f.id().equals("tv") || f.id().equals("table") ? 3.4 : 2.6),
					z + 1.05, f.name(), ChatFormatting.WHITE, 0.45F);
			col += wide ? 2 : 1;
			if (col >= cols.length) {
				col = 0;
				row++;
			}
		}
		// The paint wall (east) and the floor samples (north).
		for (int k = 0; k < PAINTS.length; k++) {
			int z = -87 + k * 2;
			BlockPos p = new BlockPos(x2 - 1, FLOOR + 1, z);
			HubPark.box(level, p.getX(), p.getY(), z, p.getX(), p.getY() + 1, z, Blocks.CONCRETE.pick(PAINTS[k]).defaultBlockState());
			SHOP.put(p, "paint:" + k);
			SHOP.put(p.above(), "paint:" + k);
		}
		HubPark.label(level, x2 - 1.5, FLOOR + 4.6, -79.5, "WALL PAINT\nright-click a colour", ChatFormatting.LIGHT_PURPLE, 0.6F);
		for (int k = 0; k < FLOORS.length; k++) {
			int x = -54 + k * 2;
			HubPark.set(level, x, FLOOR + 1, z1 + 1, FLOORS[k]);
			SHOP.put(new BlockPos(x, FLOOR + 1, z1 + 1), "floor:" + k);
		}
		HubPark.label(level, -48.5, FLOOR + 2.6, z1 + 1.8, "FLOORS: right-click one", ChatFormatting.AQUA, 0.6F);
		// The shop front: panes joined up, a moulding, a sign board, a striped awning, planters.
		for (int z = z1; z <= z2; z++) {
			for (int y = 1; y <= 6; y++) {
				BlockPos p = new BlockPos(x1, FLOOR + y, z);
				BlockState st = level.getBlockState(p);
				if (st.is(Blocks.GLASS_PANE)) {
					level.setBlock(p, Block.updateFromNeighbourShapes(st, level, p), 2);
				}
			}
			HubPark.set(level, x1 - 1, FLOOR + 7, z, Blocks.SMOOTH_QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST)
					.setValue(StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
			boolean awning = z >= -88 && z <= -72;
			if (awning) {
				HubPark.set(level, x1 - 1, FLOOR + 5, z, Blocks.WOOL_STAIRS.pick(Math.floorMod(z, 2) == 0 ? DyeColor.WHITE : DyeColor.CYAN).defaultBlockState()
						.setValue(StairBlock.FACING, Direction.EAST));
			}
			if (z >= -88 && z <= -84 || z >= -76 && z <= -72) {
				HubPark.ifAir(level, x1 - 1, FLOOR + 1, z, Math.floorMod(z, 2) == 0 ? Blocks.FLOWERING_AZALEA.defaultBlockState() : Blocks.AZALEA.defaultBlockState());
			}
		}
		for (int z = -86; z <= -74; z++) {
			for (int y = 8; y <= 10; y++) {
				boolean rim = y == 10 || z == -86 || z == -74;
				HubPark.set(level, x1, FLOOR + y, z, rim ? Blocks.STRIPPED_OAK_LOG.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState());
			}
		}
		HubPark.label(level, x1 - 0.7, FLOOR + 8.7, -79.5, "FERN'S FURNITURE", ChatFormatting.GOLD, 1.6F);
		HubHosts.add(level, new Vec3(x1 + 2.5, FLOOR + 1, -83.5), -90, VillagerProfession.CARTOGRAPHER, VillagerType.PLAINS,
				new HubHosts.Host("furniture", "Fern", "Furniture Shop", 0xC08040, "minecraft:oak_stairs",
						"Everything in the shop is free! Right-click a piece to take it, then right-click the floor in your house to put it down. "
								+ "Right-click it again to turn it round; crouch and right-click with an empty hand to pick it up. "
								+ "Paint pots change your walls, floor samples change your floor. No house yet? Right-click a red mailbox on Home Street!",
						"Fern's Furniture: right-click anything to take it!", p -> List.of("home|Take me to my house", "clear|Put my furniture away"), (p, key) -> {
							House h = mine(p);
							if (h == null) {
								p.sendSystemMessage(Component.literal("You haven't got a house yet: right-click a red mailbox on Home Street!").withStyle(ChatFormatting.YELLOW));
								return;
							}
							if (key.equals("home")) {
								p.teleportTo(h.x0() + 4.5, FLOOR + 1, h.north() ? h.z0() + 10.5 : h.z0() - 1.5);
							} else if (key.equals("clear")) {
								toss(p);
								p.sendSystemMessage(Component.literal("Your furniture tokens are tidied away (your house keeps what's in it).").withStyle(ChatFormatting.GRAY));
							}
						}));
	}

	/** Takes their furniture and paint tokens out of their pockets. */
	private static void toss(ServerPlayer p) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (token(p.getInventory().getItem(i)) != null) {
				p.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
		p.inventoryMenu.broadcastChanges();
	}

	private static ItemStack tokenStack(String id, Item icon, String name, ChatFormatting color) {
		ItemStack s = HubGames.toy(icon, 1, name, color);
		CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.putString(FURN, id);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	private static String token(ItemStack s) {
		var data = s.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag t = data.copyTag();
		return t.contains(FURN) ? t.getStringOr(FURN, null) : null;
	}

	// ----- Clicks -----

	private static House mine(ServerPlayer p) {
		String name = p.getName().getString();
		for (House h : HOUSES) {
			if (name.equals(home(h.index()).owner)) {
				return h;
			}
		}
		return null;
	}

	private static House at(BlockPos pos) {
		for (House h : HOUSES) {
			if (h.shell(pos)) {
				return h;
			}
		}
		return null;
	}

	/** Right-clicking a block in the hub (null: not Home Street's business). */
	public static InteractionResult useBlock(ServerPlayer p, InteractionHand hand, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		if (Math.abs(pos.getX() - MX) > 40 || Math.abs(pos.getZ() - MZ) > 60) {
			return null;
		}
		ServerLevel level = (ServerLevel) p.level();
		ItemStack held = p.getItemInHand(hand);
		String tok = token(held);
		InteractionResult tub = MainStreet.useBlock(p, pos);
		if (tub != null) {
			return tub;
		}
		String shop = SHOP.get(pos);
		if (shop != null) {
			takeFromShop(p, shop);
			return InteractionResult.SUCCESS;
		}
		for (House h : HOUSES) {
			if (pos.equals(h.mailbox())) {
				mailbox(p, h);
				return InteractionResult.SUCCESS;
			}
		}
		House h = at(pos);
		House mine = mine(p);
		if (tok != null) {
			if (h == null || h != mine) {
				p.sendOverlayMessage(Component.literal(mine == null ? "Get a house first: right-click a red mailbox on Home Street!"
						: "That goes in your own house!").withStyle(ChatFormatting.YELLOW));
				return InteractionResult.FAIL;
			}
			if (tok.startsWith("paint:")) {
				home(h.index()).wall = Integer.parseInt(tok.substring(6)) % PAINTS.length;
				walls(level, h, home(h.index()));
				level.playSound(null, pos, SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 1.0F, 1.0F);
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, h.x0() + 4.5, FLOOR + 3, h.z0() + 4.5, 30, 3, 1.5, 3, 0);
				save(level);
				return InteractionResult.SUCCESS;
			}
			if (tok.startsWith("floor:")) {
				home(h.index()).floor = Integer.parseInt(tok.substring(6)) % FLOORS.length;
				floor(level, h, home(h.index()));
				level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 1.0F, 1.0F);
				save(level);
				return InteractionResult.SUCCESS;
			}
			Furn f = BY_ID.get(tok);
			if (f != null) {
				BlockPos target = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
				place(p, h, f, target);
			}
			return InteractionResult.SUCCESS;
		}
		if (h == null || !h.inside(pos)) {
			return null;
		}
		Placed f = furnitureAt(h, pos);
		if (f == null) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		boolean owner = h == mine;
		if (owner && p.isShiftKeyDown() && held.isEmpty()) {
			pickUp(level, h, f);
			p.sendOverlayMessage(Component.literal("Picked up the " + BY_ID.get(f.id()).name() + ". (Fern's shop has another any time.)").withStyle(ChatFormatting.GRAY));
			level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.0F);
			return InteractionResult.SUCCESS;
		}
		if (state.getBlock() instanceof StairBlock) {
			return null; // sit down
		}
		if (state.is(Blocks.CAKE) || state.is(Blocks.NOTE_BLOCK)) {
			return null; // have a slice; play a note
		}
		if (owner && held.isEmpty()) {
			turn(level, h, f);
			return InteractionResult.SUCCESS;
		}
		if (state.getBlock() instanceof BedBlock) {
			p.sendOverlayMessage(Component.literal("What a comfy bed! (No sleeping at the park, though: there's too much to do!)").withStyle(ChatFormatting.LIGHT_PURPLE));
			level.sendParticles(ParticleTypes.HEART, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 3, 0.3, 0.2, 0.3, 0);
		}
		return InteractionResult.SUCCESS;
	}

	private static void takeFromShop(ServerPlayer p, String id) {
		ItemStack s;
		if (id.startsWith("paint:")) {
			int k = Integer.parseInt(id.substring(6));
			s = tokenStack(id, Items.DYE.pick(PAINTS[k]), PAINT_NAMES[k] + " Wall Paint", ChatFormatting.LIGHT_PURPLE);
		} else if (id.startsWith("floor:")) {
			int k = Integer.parseInt(id.substring(6));
			s = tokenStack(id, FLOORS[k].getBlock().asItem(), FLOOR_NAMES[k], ChatFormatting.AQUA);
		} else {
			Furn f = BY_ID.get(id);
			s = tokenStack(id, f.icon(), f.name(), ChatFormatting.GOLD);
		}
		HubGames.give(p, s);
		p.sendOverlayMessage(Component.literal("Got it! Right-click in your house to put it down.").withStyle(ChatFormatting.GREEN));
		p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.2F);
	}

	private static void mailbox(ServerPlayer p, House h) {
		ServerLevel level = (ServerLevel) p.level();
		Home home = home(h.index());
		String name = p.getName().getString();
		if (home.owner == null) {
			House mine = mine(p);
			if (mine != null) {
				p.sendSystemMessage(Component.literal("You've already got a house! (Crouch and right-click your own mailbox if you'd like to move.)").withStyle(ChatFormatting.YELLOW));
				return;
			}
			home.owner = name;
			nameLabel(level, h);
			save(level);
			dev.rubymod.game.Alerts.title(p, "Welcome home!", "Get furniture from Fern's shop on Main Street", ChatFormatting.GOLD);
			level.playSound(null, h.mailbox(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.2F);
			HubPark.burst(level, Vec3.atCenterOf(h.mailbox()).add(0, 3, 0));
			return;
		}
		if (home.owner.equals(name)) {
			if (p.isShiftKeyDown()) {
				Long asked = MOVE_OUT.get(p.getUUID());
				long now = level.getGameTime();
				if (asked != null && now - asked < 100) {
					MOVE_OUT.remove(p.getUUID());
					for (Placed f : new ArrayList<>(home.furniture)) {
						pickUp(level, h, f);
					}
					home.owner = null;
					home.floor = 0;
					nameLabel(level, h);
					save(level);
					p.sendSystemMessage(Component.literal("You've moved out. Pick any house with a red mailbox to move in again!").withStyle(ChatFormatting.YELLOW));
				} else {
					MOVE_OUT.put(p.getUUID(), now);
					p.sendSystemMessage(Component.literal("Move out? Your furniture goes back to the shop. Crouch and right-click the mailbox again to say yes.")
							.withStyle(ChatFormatting.YELLOW));
				}
				return;
			}
			p.sendOverlayMessage(Component.literal("Your house! " + home.furniture.size() + " things inside.").withStyle(ChatFormatting.GOLD));
			return;
		}
		// Someone else's: ding dong!
		level.playSound(null, h.mailbox(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0F, 1.2F);
		level.playSound(null, h.mailbox(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
		p.sendOverlayMessage(Component.literal("Ding dong! This is " + home.owner + "'s house. Come on in!").withStyle(ChatFormatting.GOLD));
		ServerPlayer owner = level.getServer().getPlayerList().getPlayerByName(home.owner);
		if (owner != null && owner != p) {
			owner.sendSystemMessage(Component.literal("Ding dong! " + name + " rang your doorbell on Home Street.").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}

	private static Placed furnitureAt(House h, BlockPos pos) {
		for (Placed f : home(h.index()).furniture) {
			for (BlockPos q : spots(h, f)) {
				if (q.equals(pos)) {
					return f;
				}
			}
		}
		return null;
	}

	private static List<BlockPos> spots(House h, Placed f) {
		List<BlockPos> out = new ArrayList<>();
		Furn furn = BY_ID.get(f.id());
		if (furn == null) {
			return out;
		}
		BlockPos o = h.origin().offset(f.dx(), f.dy(), f.dz());
		for (Part p : furn.parts().apply(f.f())) {
			out.add(o.offset(p.dx(), p.dy(), p.dz()));
		}
		return out;
	}

	private static boolean fits(ServerLevel level, House h, Furn furn, BlockPos o, Direction f, Placed ignore) {
		for (Part p : furn.parts().apply(f)) {
			BlockPos q = o.offset(p.dx(), p.dy(), p.dz());
			if (!h.inside(q)) {
				return false;
			}
			BlockState here = level.getBlockState(q);
			boolean mine = ignore != null && spots(h, ignore).contains(q);
			if (!here.isAir() && !mine) {
				return false;
			}
		}
		return true;
	}

	private static void place(ServerPlayer p, House h, Furn furn, BlockPos target) {
		ServerLevel level = (ServerLevel) p.level();
		Home home = home(h.index());
		if (home.furniture.size() >= MAX_PIECES) {
			p.sendOverlayMessage(Component.literal("Your house is full up! Pick something up to make room.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		Direction f = p.getDirection();
		BlockPos o = target;
		if (furn.id().equals("ceiling")) {
			// Up to the ceiling, however high you clicked.
			while (h.inside(o.above()) && level.getBlockState(o.above()).isAir()) {
				o = o.above();
			}
		} else if (furn.id().startsWith("rug")) {
			// Rugs go on the floor.
			while (h.inside(o.below()) && level.getBlockState(o.below()).isAir()) {
				o = o.below();
			}
		}
		if (!fits(level, h, furn, o, f, null)) {
			p.sendOverlayMessage(Component.literal("It doesn't fit there!").withStyle(ChatFormatting.RED));
			return;
		}
		for (Part part : furn.parts().apply(f)) {
			BlockPos q = o.offset(part.dx(), part.dy(), part.dz());
			if (!part.state().canSurvive(level, q) && part.dy() == 0) {
				p.sendOverlayMessage(Component.literal("That needs something under it!").withStyle(ChatFormatting.RED));
				return;
			}
		}
		Placed placed = new Placed(furn.id(), o.getX() - h.x0(), o.getY() - FLOOR, o.getZ() - h.z0(), f);
		home.furniture.add(placed);
		put(level, h, placed);
		level.playSound(null, o, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, o.getX() + 0.5, o.getY() + 0.8, o.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0);
		save(level);
	}

	private static void put(ServerLevel level, House h, Placed f) {
		Furn furn = BY_ID.get(f.id());
		if (furn == null) {
			return;
		}
		BlockPos o = h.origin().offset(f.dx(), f.dy(), f.dz());
		for (Part p : furn.parts().apply(f.f())) {
			level.setBlock(o.offset(p.dx(), p.dy(), p.dz()), p.state(), 18);
		}
	}

	private static void pickUp(ServerLevel level, House h, Placed f) {
		Home home = home(h.index());
		home.furniture.remove(f);
		for (BlockPos q : spots(h, f)) {
			level.setBlock(q, Blocks.AIR.defaultBlockState(), 18);
		}
		// Anything that was sitting on it comes off too.
		for (Placed other : new ArrayList<>(home.furniture)) {
			for (BlockPos q : spots(h, other)) {
				if (!level.getBlockState(q).canSurvive(level, q)) {
					pickUp(level, h, other);
					break;
				}
			}
		}
		save(level);
	}

	private static void turn(ServerLevel level, House h, Placed f) {
		Furn furn = BY_ID.get(f.id());
		Home home = home(h.index());
		BlockPos o = h.origin().offset(f.dx(), f.dy(), f.dz());
		Direction d = f.f();
		for (int k = 0; k < 3; k++) {
			d = d.getClockWise();
			if (fits(level, h, furn, o, d, f)) {
				for (BlockPos q : spots(h, f)) {
					level.setBlock(q, Blocks.AIR.defaultBlockState(), 18);
				}
				Placed turned = new Placed(f.id(), f.dx(), f.dy(), f.dz(), d);
				home.furniture.set(home.furniture.indexOf(f), turned);
				put(level, h, turned);
				level.playSound(null, o, SoundEvents.WOOD_HIT, SoundSource.PLAYERS, 0.8F, 1.2F);
				save(level);
				return;
			}
		}
	}

	// ----- Saving -----

	private static Path file(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("homes.txt");
	}

	private static void load(ServerLevel level) {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file(level);
			if (!Files.exists(f)) {
				return;
			}
			for (String line : Files.readAllLines(f)) {
				String[] s = line.split("\\|", -1);
				if (s.length < 5) {
					continue;
				}
				int i = Integer.parseInt(s[0]);
				if (i < 0 || i >= HOMES.length) {
					continue;
				}
				Home h = home(i);
				h.owner = s[1].isEmpty() ? null : s[1];
				h.wall = Math.floorMod(Integer.parseInt(s[2]), PAINTS.length);
				h.floor = Math.floorMod(Integer.parseInt(s[3]), FLOORS.length);
				for (String piece : s[4].split(";")) {
					String[] q = piece.split(",");
					if (q.length == 5 && BY_ID.containsKey(q[0])) {
						Direction d = Direction.byName(q[4]);
						h.furniture.add(new Placed(q[0], Integer.parseInt(q[1]), Integer.parseInt(q[2]), Integer.parseInt(q[3]), d == null ? Direction.NORTH : d));
					}
				}
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read Home Street", e);
		}
	}

	private static void save(ServerLevel level) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < HOMES.length; i++) {
			Home h = home(i);
			b.append(i).append('|').append(h.owner == null ? "" : h.owner).append('|').append(h.wall).append('|').append(h.floor).append('|');
			for (int k = 0; k < h.furniture.size(); k++) {
				Placed f = h.furniture.get(k);
				b.append(k == 0 ? "" : ";").append(f.id()).append(',').append(f.dx()).append(',').append(f.dy()).append(',').append(f.dz()).append(',')
						.append(f.f().getSerializedName());
			}
			b.append('\n');
		}
		try {
			Files.createDirectories(file(level).getParent());
			Files.writeString(file(level), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save Home Street", e);
		}
	}

	/** Who lives on Home Street (for the guide). */
	static int residents() {
		int n = 0;
		for (Home h : HOMES) {
			if (h != null && h.owner != null) {
				n++;
			}
		}
		return n;
	}
}
