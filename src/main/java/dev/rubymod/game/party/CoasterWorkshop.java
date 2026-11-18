package dev.rubymod.game.party;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.mixin.TextDisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Coaster Workshop: Ruby Coaster Park, one amusement park everyone builds together. Ten spots line its main street
 * (five each side), and each player gets one of their own, kept for good: build a roller coaster there piece by piece,
 * change any piece later, name it, and ride it (or anyone else's) in a three-car train, for one lap or as many as you
 * like. No rounds, no score: just build and ride.
 */
public class CoasterWorkshop extends PartyGame {
	/** Ten spots: 0 to 4 on the east side of the main street (tracks heading east), 5 to 9 on the west side. */
	private static final int PLOTS = 10;
	private static final int PER_ROW = 5;
	/** Each spot: from {@link #BEHIND} behind the station to {@link #AHEAD} in front, {@link #SIDE} either side, {@link #HIGH} up. */
	private static final int BEHIND = 24;
	private static final int AHEAD = 100;
	private static final int SIDE = 50;
	private static final int HIGH = 70;
	private static final int SPACING = SIDE * 2 + 10;
	/** How far each station is from the middle of the main street. */
	private static final int WALK = BEHIND + 9;
	/** The main street: this far each side of the middle. */
	private static final int STREET = 5;
	private static final int MAX_PIECES = 100;
	/** The whole park (half its size each way). */
	private static final int HALF_X = WALK + AHEAD + 4;
	private static final int HALF_Z = PER_ROW * SPACING / 2 + 30;
	/** The entrance plaza, at the south end. */
	private static final int PLAZA_Z = -HALF_Z + 14;
	private static final double STEP = 0.25;

	private enum Piece {
		STRAIGHT("Straight", Items.RAIL, ChatFormatting.WHITE, true), LEFT("Turn Left", Items.SPECTRAL_ARROW, ChatFormatting.YELLOW, true),
		RIGHT("Turn Right", Items.ARROW, ChatFormatting.YELLOW, true), UP("Hill Up", Items.FEATHER, ChatFormatting.AQUA, true),
		DOWN("Drop Down", Items.ANVIL, ChatFormatting.AQUA, true), LOOP("Loop!", Items.SLIME_BALL, ChatFormatting.GREEN, true),
		LAUNCH("Launch", Items.FIREWORK_ROCKET, ChatFormatting.RED, true), BIG_DROP("Big Drop", Items.POINTED_DRIPSTONE, ChatFormatting.AQUA, true),
		HELIX("Helix", Items.NAUTILUS_SHELL, ChatFormatting.GREEN, true), TUNNEL("Tunnel", Items.COBBLESTONE, ChatFormatting.GRAY, true),
		FINISH("Finish the Circuit", Items.COMPASS, ChatFormatting.GOLD, false),
		BANK_LEFT("Banked Left", Items.PRISMARINE_SHARD, ChatFormatting.YELLOW, true), BANK_RIGHT("Banked Right", Items.AMETHYST_SHARD, ChatFormatting.YELLOW, true),
		HOPS("Bunny Hops", Items.RABBIT_FOOT, ChatFormatting.AQUA, true), CORKSCREW("Corkscrew!", Items.BREEZE_ROD, ChatFormatting.GREEN, true),
		SPLASH("Splashdown", Items.WATER_BUCKET, ChatFormatting.BLUE, true);

		final String label;
		final Item icon;
		final ChatFormatting color;
		/** Comes in a big version too. */
		final boolean sizes;

		Piece(String label, Item icon, ChatFormatting color, boolean sizes) {
			this.label = label;
			this.icon = icon;
			this.color = color;
			this.sizes = sizes;
		}
	}

	/** A piece as laid: which, and whether it's the big version. */
	private record Placed(Piece piece, boolean big) {
		String label() {
			return piece.label.replace("!", "") + (big ? " (big)" : "");
		}

		String save() {
			return piece.name() + (big ? "+" : "");
		}
	}

	// The hotbar: each page has up to six things of its own, then Undo, Next page and Ride.
	private static final String UNDO = "Undo (crouch: start again)";
	private static final String RIDE = "Ride!";
	private static final String NEXT = "Next page >>";
	private static final String PAINT = "Track Colour";
	private static final String SIZE = "Size: ";
	private static final String LAPS = "Laps: ";
	private static final String PICK = "Pick a Piece";
	private static final String MODE = "Edit mode: ";
	private static final String NAME = "Name Your Coaster";
	private static final String COPY = "Copy a Coaster";
	private static final String HOME = "Back to My Station";
	private static final int PAGES = 4;
	private static final Piece[][] PAGE_PIECES = {
		{Piece.STRAIGHT, Piece.LEFT, Piece.RIGHT, Piece.UP, Piece.DOWN, Piece.LOOP},
		{Piece.LAUNCH, Piece.BIG_DROP, Piece.HELIX, Piece.TUNNEL, Piece.CORKSCREW, Piece.HOPS},
		{Piece.BANK_LEFT, Piece.BANK_RIGHT, Piece.SPLASH, Piece.FINISH},
		{}};
	private static final String[] PAGE_NAMES = {"Basics", "Thrills", "More", "Tools"};
	private static final DyeColor[][] COLOURS = {{DyeColor.RED, DyeColor.YELLOW}, {DyeColor.BLUE, DyeColor.WHITE}, {DyeColor.LIME, DyeColor.GREEN},
		{DyeColor.PURPLE, DyeColor.PINK}, {DyeColor.ORANGE, DyeColor.BLACK}, {null, DyeColor.WHITE}};
	private static final String[] COLOUR_NAMES = {"Red", "Blue", "Green", "Purple", "Orange", "Rainbow"};
	private static final DyeColor[] SPOT_COLOURS = {DyeColor.RED, DyeColor.BLUE, DyeColor.LIME, DyeColor.YELLOW, DyeColor.PURPLE, DyeColor.ORANGE,
		DyeColor.CYAN, DyeColor.MAGENTA, DyeColor.LIGHT_BLUE, DyeColor.PINK};
	private static final String[] CAR_COLOURS = {"red", "blue", "green", "yellow", "red", "blue", "green", "yellow", "red", "blue"};

	/** Everyone's coasters by name: their spot, pieces, and how it's dressed. */
	private static final class Coaster {
		int plot;
		int colour;
		/** Laps a ride goes round (0: keep going till you get off). */
		int laps = 1;
		/** What it's called ("" for "So-and-so's Coaster"). */
		String name = "";
		List<Placed> pieces = new ArrayList<>();
		final List<UUID> shown = new ArrayList<>();
		final List<UUID> rails = new ArrayList<>();
		/** The ride, worked out from the pieces: points STEP apart, which way is up, launches, tunnels, pools. */
		List<Vec3> path = List.of();
		List<Vec3> ups = List.of();
		boolean[] launch = new boolean[0];
		boolean[] tunnel = new boolean[0];
		boolean[] splash = new boolean[0];
		/** Which piece each point belongs to (-1: the station). */
		int[] pieceAt = new int[0];
		boolean circuit;
		/** Ends exactly where it starts, so a train can go round again. */
		boolean loopable;
	}

	private final Map<String, Coaster> coasters = new LinkedHashMap<>();

	/** A train going round: three cars on whose coaster, who's in each, how far it's gone. */
	private static final class Ride {
		final Coaster coaster;
		final UUID[] riders = new UUID[CARS];
		final UUID[] seats = new UUID[CARS];
		final UUID[] cars = new UUID[CARS];
		/** How far the front car has gone, all laps together (the others follow GAP behind each other). */
		double d = (CARS - 1) * GAP;
		double v = 0.2;
		/** Laps to go round (0: till everyone gets off). */
		int laps = 1;
		int lap = 1;
		/** Ticks left waiting in the station for friends to hop on. */
		int boarding = BOARDING;
		boolean launched;
		boolean braked;
		boolean splashing;
		int windCool;
		int wheeCool;

		Ride(Coaster coaster) {
			this.coaster = coaster;
		}

		int free() {
			for (int k = 0; k < CARS; k++) {
				if (riders[k] == null) {
					return k;
				}
			}
			return -1;
		}
	}

	private static final int CARS = 3;
	private static final double GAP = 2.0;
	private static final int BOARDING = 50;

	/** Rider to their train. */
	private final Map<UUID, Ride> rides = new HashMap<>();
	private final List<Ride> trains = new ArrayList<>();

	/** What each builder's up to: hotbar page, size, the piece they've picked, their hologram. */
	private static final class Builder {
		int page = 1;
		boolean big;
		int selected = -1;
		boolean insert;
		boolean naming;
		String copyFrom;
		int copyUntil;
		String previewKey = "";
		final List<UUID> ghost = new ArrayList<>();
	}

	private final Map<UUID, Builder> builders = new HashMap<>();
	private final UUID[] gates = new UUID[PLOTS];
	private UUID board;
	private boolean builtThisRun;
	private boolean loaded;
	private boolean night;
	private static boolean chatHooked;

	public CoasterWorkshop() {
		super("coasters", "Coaster Workshop", "Ruby Coaster Park: everyone adds a roller coaster of their own to one big park. Build it piece by piece "
				+ "(change any piece whenever you like), name it, then ride it, and everyone else's too. Your coaster's kept for next time.",
				"minecraft:minecart", 1, false, true, false);
		if (!chatHooked) {
			chatHooked = true;
			net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
				Builder b = builders.get(sender.getUUID());
				if (b == null || !b.naming || PartyGames.of(sender) != this) {
					return true;
				}
				String text = message.signedContent();
				sender.level().getServer().execute(() -> named(sender, text));
				return false;
			});
		}
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-110000, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return Math.max(HALF_X, HALF_Z) + 10;
	}

	@Override
	protected String arenaKey() {
		// v3: the park, ten spots along a main street.
		return id + "-v3";
	}

	@Override
	protected String roundStatus() {
		int n = activeCount();
		return n + (n == 1 ? " builder" : " builders");
	}

	// ----- Where the spots are -----

	private static boolean east(int plot) {
		return plot < PER_ROW;
	}

	/** The station's block: the track starts beside it and heads away from the main street. */
	private BlockPos station(int plot) {
		BlockPos c = center();
		int k = plot % PER_ROW;
		return new BlockPos(c.getX() + (east(plot) ? WALK : -WALK), c.getY(), c.getZ() + (k - PER_ROW / 2) * SPACING);
	}

	/** Which way the track leaves the station (degrees: 0 east, 180 west). */
	private static double yawOf(int plot) {
		return east(plot) ? 0 : 180;
	}

	/** Minecraft's facing for looking along the track from the station. */
	private static float lookOf(int plot) {
		return east(plot) ? 270 : 90;
	}

	/** A point measured from the station: lx along the track, lz to its right (the station's own frame). */
	private Vec3 rel(int plot, double lx, double y, double lz) {
		BlockPos s = station(plot);
		double sx = east(plot) ? 1 : -1;
		return new Vec3(s.getX() + 0.5 + lx * sx, s.getY() + y, s.getZ() + 0.5 + lz * sx);
	}

	/** A block measured from the station, the same way. */
	private BlockPos relBlock(int plot, int dx, int dy, int dz) {
		BlockPos s = station(plot);
		return east(plot) ? s.offset(dx, dy, dz) : s.offset(-dx, dy, -dz);
	}

	/** A world point in the station's frame: along, and to the right. */
	private double[] local(int plot, Vec3 v) {
		BlockPos s = station(plot);
		double sx = east(plot) ? 1 : -1;
		return new double[] {(v.x - s.getX() - 0.5) * sx, (v.z - s.getZ() - 0.5) * sx};
	}

	// ----- Building the park -----

	@Override
	protected void build(ServerLevel level) {
		// The ground once (it's kept in the world); the dressing every time (quick: blocks already right are skipped).
		if (!dev.rubymod.game.Arenas.built(arenaKey())) {
			flatArena(level, HALF_X, HALF_Z, 8, Blocks.GRASS_BLOCK.defaultBlockState(), true);
		}
		later(() -> street(level));
		for (int i = 0; i < PLOTS; i++) {
			int plot = i;
			later(() -> spotBlocks(level, plot));
		}
		later(() -> plaza(level));
		later(() -> {
			load();
			for (Coaster c : coasters.values()) {
				redraw(level, c);
			}
			for (int i = 0; i < PLOTS; i++) {
				gate(level, i);
			}
			board(level);
		});
	}

	private BlockPos at(int x, int y, int z) {
		BlockPos c = center();
		return new BlockPos(c.getX() + x, c.getY() - 1 + y, c.getZ() + z);
	}

	/** The main street: a brick path down the middle, lamp posts and benches, trees and snack stands between the spots. */
	private void street(ServerLevel level) {
		BlockState brick = Blocks.BRICKS.defaultBlockState();
		BlockState edge = Blocks.POLISHED_ANDESITE.defaultBlockState();
		BlockState band = Blocks.SMOOTH_STONE.defaultBlockState();
		int z0 = PLAZA_Z + 8;
		int z1 = HALF_Z - 4;
		for (int z = z0; z <= z1; z++) {
			for (int x = -STREET; x <= STREET; x++) {
				setNow(level, at(x, 0, z), Math.abs(x) == STREET ? edge : z % 8 == 0 ? band : brick);
			}
		}
		for (int z = z0 + 4; z <= z1 - 2; z += 12) {
			if (nearGate(z)) {
				continue;
			}
			for (int x : new int[] {-STREET - 1, STREET + 1}) {
				for (int y = 1; y <= 3; y++) {
					setNow(level, at(x, y, z), Blocks.DARK_OAK_FENCE.defaultBlockState());
				}
				setNow(level, at(x, 4, z), Blocks.LANTERN.defaultBlockState());
				// A bench beside each lamp, facing the street.
				if (!nearGate(z + 3)) {
					setNow(level, at(x, 1, z + 3), Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, x > 0 ? Direction.EAST : Direction.WEST));
					setNow(level, at(x, 1, z + 4), Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, x > 0 ? Direction.EAST : Direction.WEST));
				}
			}
		}
		// Between the spots: trees on one side, snack stands on the other.
		String[] snacks = {"POPCORN", "ICE CREAM", "CANDY FLOSS", "LEMONADE", "HOT DOGS", "PRETZELS", "SLUSHIES", "DONUTS"};
		int n = 0;
		for (int k = 0; k < PER_ROW - 1; k++) {
			int z = (k - PER_ROW / 2) * SPACING + SPACING / 2;
			for (int side : new int[] {-1, 1}) {
				if ((k + (side > 0 ? 1 : 0)) % 2 == 0) {
					tree(level, side * (STREET + 4), z - 2);
					tree(level, side * (STREET + 4), z + 3);
				} else {
					stand(level, side, z, snacks[n++ % snacks.length]);
				}
			}
		}
	}

	private boolean nearGate(int z) {
		for (int k = 0; k < PER_ROW; k++) {
			if (Math.abs(z - (k - PER_ROW / 2) * SPACING) <= 5) {
				return true;
			}
		}
		return false;
	}

	private void tree(ServerLevel level, int x, int z) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int y = 4; y <= 6; y++) {
					if (dx * dx + dz * dz + (y - 5) * (y - 5) * 2 <= 6) {
						setNow(level, at(x + dx, y, z + dz), leaves);
					}
				}
			}
		}
		for (int y = 1; y <= 5; y++) {
			setNow(level, at(x, y, z), Blocks.OAK_LOG.defaultBlockState());
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					setNow(level, at(x + dx, 1, z + dz), (dx + dz) % 2 == 0 ? Blocks.POPPY.defaultBlockState() : Blocks.DANDELION.defaultBlockState());
				}
			}
		}
	}

	/** A little snack stand with a striped roof (just for looks). */
	private void stand(ServerLevel level, int side, int z, String what) {
		int x0 = side * (STREET + 3);
		int x1 = side * (STREET + 6);
		DyeColor stripe = what.hashCode() % 2 == 0 ? DyeColor.RED : DyeColor.LIGHT_BLUE;
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean front = x == x0;
				boolean corner = Math.abs(dz) == 2 && (x == x0 || x == x1);
				for (int y = 1; y <= 3; y++) {
					BlockState s = corner ? Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState()
							: front ? (y == 1 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState())
							: x == x1 || Math.abs(dz) == 2 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
					setNow(level, at(x, y, z + dz), s);
				}
				setNow(level, at(x, 4, z + dz), Blocks.WOOL.pick((dz & 1) == 0 ? stripe : DyeColor.WHITE).defaultBlockState());
			}
		}
		label(level, new Vec3(center().getX() + x0 + 0.5 - side * 0.6, center().getY() + 4.6, center().getZ() + z + 0.5),
				Component.literal(what).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), 1.0F);
	}

	/** One spot: its gate on the main street, a path to the station, a hedge round it, and the station. */
	private void spotBlocks(ServerLevel level, int plot) {
		DyeColor colour = SPOT_COLOURS[plot];
		BlockState hedge = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		// The hedge round the spot (a gap where the path comes in).
		for (int x = -BEHIND - 1; x <= AHEAD + 1; x++) {
			for (int z = -SIDE; z <= SIDE; z++) {
				boolean border = x == -BEHIND - 1 || x == AHEAD + 1 || Math.abs(z) == SIDE;
				if (!border || x == -BEHIND - 1 && z >= 1 && z <= 4) {
					continue;
				}
				setNow(level, relBlock(plot, x, 0, z), hedge);
			}
		}
		// The path from the main street to the platform.
		int streetEdge = WALK - STREET - 1;
		for (int x = -streetEdge; x <= -2; x++) {
			for (int z = 1; z <= 4; z++) {
				setNow(level, relBlock(plot, x, -1, z), z == 1 || z == 4 ? Blocks.POLISHED_ANDESITE.defaultBlockState()
						: Blocks.WOOL.pick(colour).defaultBlockState());
			}
		}
		// The gate: two posts and a crossbar in the spot's colour, where the path leaves the main street.
		int gx = -streetEdge;
		for (int y = 0; y <= 4; y++) {
			setNow(level, relBlock(plot, gx, y, 0), Blocks.CONCRETE.pick(colour).defaultBlockState());
			setNow(level, relBlock(plot, gx, y, 5), Blocks.CONCRETE.pick(colour).defaultBlockState());
		}
		for (int z = 0; z <= 5; z++) {
			setNow(level, relBlock(plot, gx, 5, z), z == 2 || z == 3 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		}
		// The station: a platform, posts and a striped roof.
		for (int x = -2; x <= 8; x++) {
			for (int z = -3; z <= 3; z++) {
				setNow(level, relBlock(plot, x, -1, z), Math.abs(z) <= 1 ? Blocks.POLISHED_BLACKSTONE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState());
				setNow(level, relBlock(plot, x, 5, z), Blocks.WOOL.pick((x & 1) == 0 ? colour : DyeColor.WHITE).defaultBlockState());
			}
		}
		for (int x : new int[] {-2, 8}) {
			for (int z : new int[] {-3, 3}) {
				for (int y = 0; y <= 4; y++) {
					setNow(level, relBlock(plot, x, y, z), Blocks.QUARTZ_PILLAR.defaultBlockState());
				}
			}
		}
	}

	/** The entrance: a big gate at the south end, a plaza with a fountain, and the park board. */
	private void plaza(ServerLevel level) {
		BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
		BlockState brick = Blocks.BRICKS.defaultBlockState();
		int zc = PLAZA_Z;
		for (int x = -14; x <= 14; x++) {
			for (int z = zc - 12; z <= zc + 8; z++) {
				double r = Math.hypot(x, z - zc);
				setNow(level, at(x, 0, z), r < 9 && ((int) r) % 3 == 0 ? brick : stone);
			}
		}
		// The fountain.
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				double r = Math.hypot(x, z);
				if (r <= 4.5) {
					setNow(level, at(x, 1, zc + z), r > 3.5 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
				}
			}
		}
		for (int y = 1; y <= 3; y++) {
			setNow(level, at(0, y, zc), Blocks.QUARTZ_PILLAR.defaultBlockState());
		}
		setNow(level, at(0, 4, zc), Blocks.WATER.defaultBlockState());
		// The gate.
		int gz = zc - 12;
		for (int x : new int[] {-9, 9}) {
			for (int y = 1; y <= 7; y++) {
				setNow(level, at(x, y, gz), Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
			}
			setNow(level, at(x, 8, gz), Blocks.GOLD_BLOCK.defaultBlockState());
		}
		for (int x = -8; x <= 8; x++) {
			setNow(level, at(x, 7, gz), Blocks.CONCRETE.pick((x & 1) == 0 ? DyeColor.WHITE : DyeColor.RED).defaultBlockState());
		}
		label(level, new Vec3(center().getX() + 0.5, center().getY() + 8.2, center().getZ() + gz + 0.5),
				Component.literal("RUBY COASTER PARK").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), 2.4F);
		// Flower beds along the plaza.
		for (int x : new int[] {-12, 12}) {
			for (int z = zc - 8; z <= zc + 4; z += 2) {
				setNow(level, at(x, 1, z), (z & 2) == 0 ? Blocks.RED_TULIP.defaultBlockState() : Blocks.ALLIUM.defaultBlockState());
			}
		}
	}

	private Display.TextDisplay label(ServerLevel level, Vec3 at, Component text, float scale) {
		Display.TextDisplay t = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (t == null) {
			return null;
		}
		t.snapTo(at.x, at.y, at.z, 0, 0);
		((TextDisplayAccessor) t).rubymod$setText(text);
		((DisplayAccessor) t).rubymod$setBillboard(Display.BillboardConstraints.CENTER);
		((DisplayAccessor) t).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
		t.addTag(tag());
		spawned.add(t.getUUID());
		level.addFreshEntity(t);
		return t;
	}

	private void setText(ServerLevel level, UUID id, Component text) {
		if (id != null && level.getEntity(id) instanceof Display.TextDisplay t) {
			((TextDisplayAccessor) t).rubymod$setText(text);
		}
	}

	private String ownerOf(Coaster c) {
		for (var e : coasters.entrySet()) {
			if (e.getValue() == c) {
				return e.getKey();
			}
		}
		return "";
	}

	private Coaster atPlot(int plot) {
		for (Coaster c : coasters.values()) {
			if (c.plot == plot) {
				return c;
			}
		}
		return null;
	}

	private String nameOf(Coaster c) {
		return c.name.isEmpty() ? ownerOf(c) + "'s Coaster" : c.name;
	}

	/** The words over a spot's gate: its coaster and builder, or "free spot". */
	private void gate(ServerLevel level, int plot) {
		Coaster c = atPlot(plot);
		Component text = c == null ? Component.literal("Spot " + (plot + 1) + "\n").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("Free spot!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
				: Component.literal(nameOf(c)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
						.append(Component.literal("\nby " + ownerOf(c)).withStyle(ChatFormatting.WHITE));
		if (gates[plot] != null && level.getEntity(gates[plot]) != null) {
			setText(level, gates[plot], text);
			return;
		}
		Display.TextDisplay t = label(level, rel(plot, -(WALK - STREET - 1), 7.0, 2.5), text, 1.6F);
		gates[plot] = t == null ? null : t.getUUID();
	}

	/** The park board at the entrance: every spot, its coaster and who built it. */
	private void board(ServerLevel level) {
		MutableComponent text = Component.literal("RUBY COASTER PARK").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
				.append(Component.literal("\nEveryone builds a coaster of their own.\nPress Ride! at any station to ride it.\n").withStyle(ChatFormatting.GRAY));
		for (int i = 0; i < PLOTS; i++) {
			Coaster c = atPlot(i);
			text.append(Component.literal("\n" + (i + 1) + ". " + (east(i) ? "(east) " : "(west) ")).withStyle(ChatFormatting.WHITE));
			text.append(c == null ? Component.literal("free spot").withStyle(ChatFormatting.GREEN)
					: Component.literal(nameOf(c)).withStyle(ChatFormatting.GOLD).append(Component.literal(" by " + ownerOf(c) + ", " + c.pieces.size()
							+ " pieces").withStyle(ChatFormatting.WHITE)));
		}
		if (board != null && level.getEntity(board) != null) {
			setText(level, board, text);
			return;
		}
		Display.TextDisplay t = label(level, new Vec3(center().getX() + 0.5, center().getY() + 6.2, center().getZ() + PLAZA_Z + 1.5), text, 1.5F);
		board = t == null ? null : t.getUUID();
	}

	// ----- Players -----

	@Override
	protected void startRound() {
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		Coaster c = mine(player);
		ServerLevel level = level();
		player.stopRiding();
		ready(player);
		player.setGameMode(GameType.ADVENTURE);
		clearPlayer(player);
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		Builder b = builders.computeIfAbsent(player.getUUID(), k -> new Builder());
		b.page = 1;
		b.selected = -1;
		b.naming = false;
		if (c == null) {
			teleport(player, center().getX() + 0.5, center().getY(), center().getZ() + PLAZA_Z + 10.5, 0);
			give(player, tool(Items.MINECART, RIDE, ChatFormatting.GOLD));
			Alerts.title(player, "RUBY COASTER PARK", "All ten spots are taken: ride everyone's coasters! (Press Ride! at a station)",
					ChatFormatting.GOLD);
			return;
		}
		if (c.shown.isEmpty() && level != null) {
			redraw(level, c);
		}
		Vec3 home = rel(c.plot, 3, 0, 2);
		teleport(player, home.x, home.y, home.z, lookOf(c.plot));
		tools(player, b, c);
		Alerts.title(player, "RUBY COASTER PARK", "This is your spot (number " + (c.plot + 1) + "): build your coaster here!", ChatFormatting.GOLD);
	}

	/** The hotbar for this builder's page. */
	private void tools(ServerPlayer player, Builder b, Coaster c) {
		player.getInventory().clearContent();
		int page = Math.max(1, Math.min(PAGES, b.page));
		for (Piece piece : PAGE_PIECES[page - 1]) {
			give(player, tool(piece.icon, piece.label, piece.color));
		}
		if (page == 3) {
			give(player, tool(Items.SLIME_BLOCK, SIZE + (b.big ? "BIG" : "Regular"), ChatFormatting.LIGHT_PURPLE),
					tool(Items.CLOCK, LAPS + lapsText(c.laps), ChatFormatting.GOLD));
		}
		if (page == 4) {
			give(player, tool(Items.SPYGLASS, PICK, ChatFormatting.AQUA), tool(Items.STRUCTURE_VOID, MODE + (b.insert ? "Insert" : "Replace"), ChatFormatting.AQUA),
					tool(Items.DYE.pick(DyeColor.MAGENTA), PAINT, ChatFormatting.LIGHT_PURPLE), tool(Items.NAME_TAG, NAME, ChatFormatting.GOLD),
					tool(Items.BOOK, COPY, ChatFormatting.GREEN), tool(Items.RECOVERY_COMPASS, HOME, ChatFormatting.WHITE));
		}
		give(player, tool(Items.BARRIER, UNDO, ChatFormatting.GRAY), tool(Items.PAPER, NEXT + " (" + page + "/" + PAGES + ": " + PAGE_NAMES[page % PAGES] + ")",
				ChatFormatting.AQUA), tool(Items.MINECART, RIDE, ChatFormatting.GOLD));
		player.getInventory().setSelectedSlot(Math.min(player.getInventory().getSelectedSlot(), 8));
		player.inventoryMenu.broadcastChanges();
	}

	private static String lapsText(int laps) {
		return laps <= 0 ? "Keep going" : String.valueOf(laps);
	}

	private static ItemStack tool(Item item, String name, ChatFormatting color) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
		return s;
	}

	/** This player's coaster (their own spot the first time, kept for good). Null when all ten spots are taken. */
	private Coaster mine(ServerPlayer p) {
		load();
		String name = p.getName().getString();
		Coaster c = coasters.get(name);
		if (c == null) {
			int plot = -1;
			for (int i = 0; i < PLOTS && plot < 0; i++) {
				if (atPlot(i) == null) {
					plot = i;
				}
			}
			if (plot < 0) {
				return null;
			}
			c = new Coaster();
			c.plot = plot;
			c.colour = plot % COLOURS.length;
			coasters.put(name, c);
			lay(c);
			save();
			ServerLevel level = level();
			if (level != null) {
				redraw(level, c);
				gate(level, plot);
				board(level);
			}
		}
		return c;
	}

	@Override
	public boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		String name = stack.getHoverName().getString();
		ServerLevel level = level();
		if (level == null || rides.containsKey(player.getUUID())) {
			return false;
		}
		if (player.getCooldowns().isOnCooldown(stack)) {
			return false;
		}
		player.getCooldowns().addCooldown(stack, 6);
		Coaster c = mine(player);
		Builder b = builders.computeIfAbsent(player.getUUID(), k -> new Builder());
		if (RIDE.equals(name)) {
			// At someone else's station: ride theirs.
			Coaster which = c;
			for (Coaster o : coasters.values()) {
				if (player.position().distanceTo(rel(o.plot, 3, 0, 0)) < 8) {
					which = o;
				}
			}
			if (which == null) {
				player.sendOverlayMessage(Component.literal("Go to a station on the main street, then press Ride!").withStyle(ChatFormatting.YELLOW));
				return false;
			}
			ride(player, which);
			return false;
		}
		if (c == null) {
			return false;
		}
		if (name.startsWith(NEXT)) {
			b.page = b.page % PAGES + 1;
			tools(player, b, c);
			player.sendOverlayMessage(Component.literal("Page " + b.page + ": " + PAGE_NAMES[b.page - 1]).withStyle(ChatFormatting.AQUA));
			return false;
		}
		if (PAINT.equals(name)) {
			c.colour = (c.colour + 1) % COLOURS.length;
			redraw(level, c);
			save();
			player.sendOverlayMessage(Component.literal("Track colour: " + COLOUR_NAMES[c.colour]).withStyle(ChatFormatting.LIGHT_PURPLE));
			return false;
		}
		if (name.startsWith(SIZE)) {
			b.big = !b.big;
			tools(player, b, c);
			player.sendOverlayMessage(Component.literal(b.big ? "BIG pieces: taller hills, wider turns, bigger loops!" : "Regular-size pieces.")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
			return false;
		}
		if (name.startsWith(LAPS)) {
			c.laps = c.laps == 0 ? 1 : c.laps >= 3 ? 0 : c.laps + 1;
			save();
			tools(player, b, c);
			player.sendOverlayMessage(Component.literal("Laps: " + lapsText(c.laps) + (c.loopable ? "" : "  (finish the circuit to go round more than once)"))
					.withStyle(ChatFormatting.GOLD));
			return false;
		}
		if (PICK.equals(name)) {
			int k = picked(player, c);
			if (k < 0) {
				b.selected = -1;
				player.sendOverlayMessage(Component.literal(k == -2 ? "That's the station: pick a piece of track."
						: "Back to building on the end of the track.").withStyle(ChatFormatting.AQUA));
			} else {
				b.selected = k;
				player.sendOverlayMessage(Component.literal("Picked piece " + (k + 1) + " of " + c.pieces.size() + ": " + c.pieces.get(k).label()
						+ ". Now a piece " + (b.insert ? "goes in before it" : "replaces it") + "; Undo takes it out.").withStyle(ChatFormatting.AQUA));
			}
			return false;
		}
		if (name.startsWith(MODE)) {
			b.insert = !b.insert;
			tools(player, b, c);
			player.sendOverlayMessage(Component.literal(b.insert ? "Insert: a piece goes in before the one you've picked." : "Replace: a piece swaps for the one you've picked.")
					.withStyle(ChatFormatting.AQUA));
			return false;
		}
		if (NAME.equals(name)) {
			b.naming = true;
			player.sendSystemMessage(Component.literal("Type your coaster's name in chat (or \"cancel\").").withStyle(ChatFormatting.GOLD));
			return false;
		}
		if (COPY.equals(name)) {
			copy(player, b, c);
			return false;
		}
		if (HOME.equals(name)) {
			Vec3 home = rel(c.plot, 3, 0, 2);
			teleport(player, home.x, home.y, home.z, lookOf(c.plot));
			return false;
		}
		for (Piece piece : Piece.values()) {
			if (piece.label.equals(name)) {
				Placed placed = new Placed(piece, b.big && piece.sizes);
				Edit e = edit(c, b, placed);
				String why = e.why;
				if (why == null) {
					c.pieces = e.list;
					lay(c);
					why = check(c, e.at);
					if (why != null) {
						c.pieces = e.before;
						lay(c);
					}
				}
				if (why != null) {
					player.sendOverlayMessage(Component.literal(why).withStyle(ChatFormatting.YELLOW));
					return false;
				}
				if (b.selected >= 0) {
					b.selected = e.at;
					b.insert = false;
					tools(player, b, c);
				}
				changed(level, c);
				level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 0.4F, 1.6F);
				player.sendOverlayMessage(Component.literal(placed.label() + (e.verb) + " (piece " + (e.at + 1) + " of " + c.pieces.size() + ")"
						+ (c.circuit ? "  It's a full circuit!" : "")).withStyle(ChatFormatting.GREEN));
				return false;
			}
		}
		if (UNDO.equals(name)) {
			if (b.selected >= 0 && b.selected < c.pieces.size()) {
				List<Placed> before = c.pieces;
				List<Placed> list = new ArrayList<>(before);
				Placed gone = list.remove(b.selected);
				c.pieces = list;
				lay(c);
				String why = check(c, b.selected);
				if (why != null) {
					c.pieces = before;
					lay(c);
					player.sendOverlayMessage(Component.literal("Can't take that one out: " + why).withStyle(ChatFormatting.YELLOW));
					return false;
				}
				player.sendOverlayMessage(Component.literal("Took out " + gone.label() + ".").withStyle(ChatFormatting.YELLOW));
				if (b.selected >= c.pieces.size()) {
					b.selected = -1;
				}
			} else if (player.isShiftKeyDown()) {
				c.pieces = new ArrayList<>();
				b.selected = -1;
				player.sendOverlayMessage(Component.literal("Cleared: start again!").withStyle(ChatFormatting.YELLOW));
			} else if (!c.pieces.isEmpty()) {
				c.pieces = new ArrayList<>(c.pieces.subList(0, c.pieces.size() - 1));
				player.sendOverlayMessage(Component.literal("Took the last piece back.").withStyle(ChatFormatting.YELLOW));
			}
			lay(c);
			changed(level, c);
			return false;
		}
		return true;
	}

	/** After the track changed: draw it, keep it, update the signs. */
	private void changed(ServerLevel level, Coaster c) {
		redraw(level, c);
		save();
		gate(level, c.plot);
		board(level);
	}

	/** What a piece would do: the new list, where it goes, and why not (or null). */
	private record Edit(List<Placed> before, List<Placed> list, int at, String verb, String why) {
	}

	private Edit edit(Coaster c, Builder b, Placed placed) {
		List<Placed> before = c.pieces;
		List<Placed> list = new ArrayList<>(before);
		int sel = b.selected >= 0 && b.selected < list.size() ? b.selected : -1;
		if (sel < 0) {
			if (list.size() >= MAX_PIECES) {
				return new Edit(before, list, list.size(), "", "That's as long as a coaster can be! Undo some, or Ride!");
			}
			if (!list.isEmpty() && list.get(list.size() - 1).piece == Piece.FINISH) {
				return new Edit(before, list, list.size(), "", "It's finished! Ride it, or pick a piece to change (page 4).");
			}
			list.add(placed);
			return new Edit(before, list, list.size() - 1, " added", null);
		}
		if (b.insert) {
			if (list.size() >= MAX_PIECES) {
				return new Edit(before, list, sel, "", "That's as long as a coaster can be!");
			}
			list.add(sel, placed);
		} else {
			list.set(sel, placed);
		}
		if (placed.piece == Piece.FINISH && sel != list.size() - 1) {
			return new Edit(before, list, sel, "", "Finish the Circuit has to be the last piece.");
		}
		return new Edit(before, list, sel, b.insert ? " put in" : " swapped in", null);
	}

	/** Is the track as laid all right? (On the spot, under the sky limit, not running into itself from piece {@code from} on.) */
	private String check(Coaster c, int from) {
		if (!fits(c)) {
			return "That would go off the edge of your spot! Try turning.";
		}
		int start = c.pieceAt.length;
		for (int i = 0; i < c.pieceAt.length; i++) {
			if (c.pieceAt[i] >= from) {
				start = i;
				break;
			}
		}
		return crashes(c, start) ? "That would crash into your track! Try another way." : null;
	}

	/** The piece the player's looking at on their own track: its number; -1 for none, -2 for the station. */
	private int picked(ServerPlayer p, Coaster c) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1.0F);
		double best = Double.MAX_VALUE;
		int which = -1;
		for (int i = 0; i < c.path.size(); i += 2) {
			Vec3 q = c.path.get(i).subtract(eye);
			double t = q.dot(look);
			if (t < 0 || t > 90) {
				continue;
			}
			double off = q.subtract(look.scale(t)).length();
			if (off < 1.6 + t * 0.03 && off < best) {
				best = off;
				which = c.pieceAt[i] < 0 ? -2 : c.pieceAt[i];
			}
		}
		return which;
	}

	/** Copying a coaster: stand at its station, click twice. */
	private void copy(ServerPlayer player, Builder b, Coaster mine) {
		Coaster from = null;
		String owner = null;
		for (var e : coasters.entrySet()) {
			if (e.getValue() != mine && player.position().distanceTo(rel(e.getValue().plot, 3, 0, 0)) < 10) {
				from = e.getValue();
				owner = e.getKey();
			}
		}
		if (from == null) {
			player.sendOverlayMessage(Component.literal("Stand at someone else's station to copy their coaster.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (!owner.equals(b.copyFrom) || roundTicks > b.copyUntil) {
			b.copyFrom = owner;
			b.copyUntil = roundTicks + 200;
			player.sendSystemMessage(Component.literal("Copy " + nameOf(from) + " onto your spot? Your own track would be replaced. Click again to copy.")
					.withStyle(ChatFormatting.GOLD));
			return;
		}
		b.copyFrom = null;
		List<Placed> before = mine.pieces;
		mine.pieces = new ArrayList<>(from.pieces);
		lay(mine);
		String why = check(mine, 0);
		if (why != null) {
			mine.pieces = before;
			lay(mine);
			player.sendOverlayMessage(Component.literal("Couldn't copy it: " + why).withStyle(ChatFormatting.YELLOW));
			return;
		}
		mine.colour = from.colour;
		mine.laps = from.laps;
		b.selected = -1;
		changed(level(), mine);
		player.sendSystemMessage(Component.literal("Copied " + nameOf(from) + " onto your spot! Change it however you like.").withStyle(ChatFormatting.GREEN));
	}

	/** The name typed in chat after Name Your Coaster. */
	private void named(ServerPlayer p, String text) {
		Builder b = builders.get(p.getUUID());
		if (b == null) {
			return;
		}
		b.naming = false;
		Coaster c = coasters.get(p.getName().getString());
		String name = text.replaceAll("[|\\n\\r,]", " ").trim();
		if (c == null || name.isEmpty() || name.equalsIgnoreCase("cancel")) {
			p.sendSystemMessage(Component.literal("Name left as it was.").withStyle(ChatFormatting.GRAY));
			return;
		}
		c.name = name.length() > 28 ? name.substring(0, 28) : name;
		ServerLevel level = level();
		if (level != null) {
			changed(level, c);
		}
		p.sendSystemMessage(Component.literal("Your coaster is called " + c.name + "!").withStyle(ChatFormatting.GOLD));
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return !allowsItemUse(player, held);
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	// ----- The track -----

	/** Points as the pieces lay them out, with which piece each came from. */
	private static final class Laying {
		final List<Vec3> pts = new ArrayList<>();
		final List<Vec3> up = new ArrayList<>();
		final List<Boolean> boost = new ArrayList<>();
		final List<Integer> owner = new ArrayList<>();
		final Set<Integer> tunnel = new HashSet<>();
		final Set<Integer> splash = new HashSet<>();
		int piece = -1;

		void add(Vec3 p, Vec3 u, boolean b) {
			pts.add(p);
			up.add(u);
			boost.add(b);
			owner.add(piece);
		}
	}

	private static final Vec3 UP = new Vec3(0, 1, 0);

	/** Work the pieces out into a path. */
	private void lay(Coaster c) {
		BlockPos s = station(c.plot);
		double ground = s.getY() + 0.6;
		Laying L = new Laying();
		double yaw0 = yawOf(c.plot);
		double yaw = yaw0;
		Vec3 at = rel(c.plot, -2, 0.6, 0);
		// The station: straight along the platform.
		for (int i = 0; i < 9; i++) {
			L.add(at, UP, false);
			at = at.add(dir(yaw));
		}
		for (int pi = 0; pi < c.pieces.size(); pi++) {
			Placed pl = c.pieces.get(pi);
			Piece p = pl.piece;
			boolean big = pl.big;
			L.piece = pi;
			switch (p) {
				case STRAIGHT, LAUNCH -> {
					int n = p == Piece.LAUNCH ? (big ? 12 : 8) : (big ? 10 : 5);
					for (int i = 0; i < n; i++) {
						L.add(at, UP, p == Piece.LAUNCH);
						at = at.add(dir(yaw));
					}
				}
				case LEFT, RIGHT, BANK_LEFT, BANK_RIGHT -> {
					boolean bank = p == Piece.BANK_LEFT || p == Piece.BANK_RIGHT;
					double r = bank ? (big ? 9 : 6) : (big ? 11 : 7);
					double sign = p == Piece.LEFT || p == Piece.BANK_LEFT ? -1 : 1;
					Vec3 centre = at.add(side(yaw).scale(r * sign));
					int n = bank ? (big ? 16 : 12) : (big ? 13 : 9);
					for (int i = 0; i < n; i++) {
						double a = Math.toRadians(yaw + sign * i * 90.0 / n);
						Vec3 q = centre.subtract(side(Math.toDegrees(a)).scale(r * sign));
						q = new Vec3(q.x, at.y, q.z);
						L.add(q, bank ? banked(q, centre, 45, i / (double) n) : UP, false);
					}
					yaw += sign * 90;
					Vec3 centre2 = centre.subtract(side(yaw).scale(r * sign));
					at = new Vec3(centre2.x, at.y, centre2.z);
				}
				case UP, DOWN -> {
					double rise = p == Piece.UP ? (big ? 8 : 4) : -Math.min(big ? 10 : 5, at.y - ground);
					int len = big ? 10 : 7;
					for (int i = 0; i < len; i++) {
						double t = i / (double) len;
						double h = rise * (0.5 - 0.5 * Math.cos(Math.PI * t));
						L.add(at.add(dir(yaw).scale(i)).add(0, h, 0), UP, false);
					}
					at = at.add(dir(yaw).scale(len)).add(0, rise, 0);
				}
				case BIG_DROP -> {
					double fall = -Math.min(big ? 20 : 12, at.y - ground);
					double rise = fall > -3 ? (big ? 14 : 8) : fall;
					int n = big ? 15 : 11;
					for (int i = 0; i < n; i++) {
						double t = i / (double) n;
						double h = rise * (0.5 - 0.5 * Math.cos(Math.PI * t));
						L.add(at.add(dir(yaw).scale(i * 1.1)).add(0, h, 0), UP, false);
					}
					at = at.add(dir(yaw).scale(n * 1.1)).add(0, rise, 0);
				}
				case HELIX -> {
					// A full circle to the right, going down (or up, if it's low already), leaning in.
					double r = big ? 7 : 5;
					double h = big ? 8 : 5;
					double drop = at.y - ground > h ? -h : h;
					Vec3 centre = at.add(side(yaw).scale(r));
					int n = big ? 32 : 24;
					for (int i = 0; i < n; i++) {
						double a = Math.toRadians(yaw + i * 360.0 / n);
						Vec3 q = centre.subtract(side(Math.toDegrees(a)).scale(r));
						q = new Vec3(q.x, at.y + drop * i / n, q.z);
						L.add(q, banked(q, centre, 30, i / (double) n), false);
					}
					at = at.add(0, drop, 0).add(dir(yaw).scale(0.5));
				}
				case HOPS -> {
					// Three quick little hills: up out of your seat over each one.
					double h = big ? 3.5 : 2.2;
					int per = big ? 8 : 6;
					for (int i = 0; i < per * 3; i++) {
						L.add(at.add(dir(yaw).scale(i)).add(0, h * Math.pow(Math.sin(Math.PI * i / per), 2), 0), UP, false);
					}
					at = at.add(dir(yaw).scale(per * 3));
				}
				case CORKSCREW -> {
					// Once right round, upside down in the middle, while going forward.
					double r = big ? 2.4 : 1.8;
					double len = big ? 20 : 14;
					Vec3 f = dir(yaw);
					Vec3 sd = side(yaw);
					int n = big ? 36 : 28;
					for (int i = 0; i < n; i++) {
						double t = i / (double) n;
						double th = 2 * Math.PI * t * t * (3 - 2 * t);
						Vec3 axis = at.add(f.scale(len * t)).add(0, r, 0);
						Vec3 q = axis.add(0, -r * Math.cos(th), 0).add(sd.scale(r * Math.sin(th)));
						L.add(q, axis.subtract(q).normalize(), false);
					}
					at = at.add(f.scale(len));
				}
				case SPLASH -> {
					// Down to the ground, through a pool, and out again.
					double above = at.y - (ground - 0.6);
					int down = above > 0.3 ? 6 : 0;
					for (int i = 0; i < down; i++) {
						double t = i / (double) down;
						L.add(at.add(dir(yaw).scale(i)).add(0, -above * (0.5 - 0.5 * Math.cos(Math.PI * t)), 0), UP, false);
					}
					at = at.add(dir(yaw).scale(down)).add(0, -above, 0);
					int pool = big ? 14 : 9;
					for (int i = 0; i < pool; i++) {
						L.add(at, UP, false);
						L.splash.add(L.pts.size() - 1);
						at = at.add(dir(yaw));
					}
					for (int i = 0; i < 4; i++) {
						L.add(at.add(dir(yaw).scale(i)).add(0, 0.6 * (0.5 - 0.5 * Math.cos(Math.PI * i / 4)), 0), UP, false);
					}
					at = at.add(dir(yaw).scale(4)).add(0, 0.6, 0);
				}
				case LOOP -> {
					double r = big ? 6.5 : 4.5;
					double shift = big ? 2.2 : 1.8;
					Vec3 centre = at.add(0, r, 0);
					Vec3 f = dir(yaw);
					Vec3 sd = side(yaw);
					int n = big ? 40 : 32;
					for (int i = 0; i < n; i++) {
						double t = i * Math.PI * 2 / n;
						Vec3 off = sd.scale(shift * t / (Math.PI * 2));
						Vec3 q = centre.add(f.scale(r * Math.sin(t))).add(0, -r * Math.cos(t), 0).add(off);
						L.add(q, centre.add(off).subtract(q).normalize(), false);
					}
					at = at.add(sd.scale(shift));
				}
				case TUNNEL -> {
					int n = big ? 14 : 8;
					for (int i = 0; i < n; i++) {
						L.add(at, UP, false);
						L.tunnel.add(L.pts.size() - 1);
						at = at.add(dir(yaw));
					}
				}
				case FINISH -> {
					// A smooth curve back round to the start of the station.
					Vec3 end = L.pts.get(0);
					double len = Math.min(10, Math.max(5, at.distanceTo(end) * 0.45));
					Vec3 p1 = at.add(dir(yaw).scale(len));
					Vec3 p2 = end.subtract(dir(yaw0).scale(len));
					// Keep the curve's pulls inside the spot.
					double[] lp = local(c.plot, p1);
					double lx = Math.max(-BEHIND + 2, Math.min(AHEAD - 2, lp[0]));
					double lz = Math.max(-SIDE + 3, Math.min(SIDE - 3, lp[1]));
					p1 = rel(c.plot, lx, p1.y - s.getY(), lz);
					for (int i = 0; i < 30; i++) {
						double t = i / 30.0;
						double u = 1 - t;
						Vec3 q = at.scale(u * u * u).add(p1.scale(3 * u * u * t)).add(p2.scale(3 * u * t * t)).add(end.scale(t * t * t));
						L.add(q, UP, false);
					}
					at = end;
				}
				default -> {
				}
			}
		}
		L.piece = c.pieces.size() - 1;
		L.add(at, UP, false);
		// Back at the start? Then it's a full circuit.
		Vec3 start = L.pts.get(0);
		boolean finished = !c.pieces.isEmpty() && c.pieces.get(c.pieces.size() - 1).piece == Piece.FINISH;
		c.circuit = c.pieces.size() > 3 && at.distanceTo(start) < 3.5 || finished;
		// Points STEP apart (straight lines between: the pieces are already finely laid out).
		List<Vec3> path = new ArrayList<>();
		List<Vec3> ups = new ArrayList<>();
		List<Boolean> la = new ArrayList<>();
		List<Boolean> tl = new ArrayList<>();
		List<Boolean> sp = new ArrayList<>();
		List<Integer> ow = new ArrayList<>();
		for (int i = 0; i + 1 < L.pts.size(); i++) {
			Vec3 a = L.pts.get(i);
			Vec3 b = L.pts.get(i + 1);
			int k = Math.max(1, (int) Math.round(a.distanceTo(b) / STEP));
			for (int j = 0; j < k; j++) {
				path.add(a.lerp(b, j / (double) k));
				ups.add(L.up.get(i).lerp(L.up.get(i + 1), j / (double) k).normalize());
				la.add(L.boost.get(i));
				tl.add(L.tunnel.contains(i));
				sp.add(L.splash.contains(i));
				ow.add(L.owner.get(i));
			}
		}
		path.add(L.pts.get(L.pts.size() - 1));
		ups.add(L.up.get(L.up.size() - 1));
		la.add(false);
		tl.add(false);
		sp.add(false);
		ow.add(L.owner.get(L.owner.size() - 1));
		// Round off the corners where the pieces meet (and between their points): a smoother ride.
		path = smooth(path, 6, false);
		ups = smooth(ups, 6, true);
		c.path = path;
		c.ups = ups;
		c.launch = bools(la);
		c.tunnel = bools(tl);
		c.splash = bools(sp);
		c.pieceAt = new int[ow.size()];
		for (int i = 0; i < ow.size(); i++) {
			c.pieceAt[i] = ow.get(i);
		}
		c.loopable = c.circuit && path.size() > 50 && path.get(path.size() - 1).distanceTo(path.get(0)) < 1.0;
	}

	private static boolean[] bools(List<Boolean> list) {
		boolean[] out = new boolean[list.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = list.get(i);
		}
		return out;
	}

	/** Which way is up on a banked turn: leaning toward {@code centre}, easing in and out over the turn (t 0 to 1). */
	private static Vec3 banked(Vec3 q, Vec3 centre, double degrees, double t) {
		double ease = Math.min(1, Math.min(t, 1 - t) * 4);
		double b = Math.toRadians(degrees) * ease * ease * (3 - 2 * ease);
		Vec3 in = new Vec3(centre.x - q.x, 0, centre.z - q.z);
		in = in.lengthSqr() < 1e-6 ? Vec3.ZERO : in.normalize();
		return new Vec3(0, Math.cos(b), 0).add(in.scale(Math.sin(b)));
	}

	/** A running average (twice) over {@code w} points each way; the ends stay put. */
	private static List<Vec3> smooth(List<Vec3> in, int w, boolean unit) {
		List<Vec3> out = new ArrayList<>(in);
		for (int pass = 0; pass < 2; pass++) {
			List<Vec3> src = new ArrayList<>(out);
			for (int i = 1; i < src.size() - 1; i++) {
				int h = Math.min(w, Math.min(i, src.size() - 1 - i));
				double x = 0;
				double y = 0;
				double z = 0;
				for (int k = i - h; k <= i + h; k++) {
					Vec3 v = src.get(k);
					x += v.x;
					y += v.y;
					z += v.z;
				}
				Vec3 m = new Vec3(x, y, z).scale(1.0 / (2 * h + 1));
				out.set(i, unit && m.lengthSqr() > 1e-6 ? m.normalize() : m);
			}
		}
		return out;
	}

	private static Vec3 dir(double yaw) {
		double r = Math.toRadians(yaw);
		return new Vec3(Math.cos(r), 0, Math.sin(r));
	}

	/** To the right of heading {@code yaw}. */
	private static Vec3 side(double yaw) {
		double r = Math.toRadians(yaw);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/**
	 * Does the track run into itself anywhere from point {@code start} on? (A piece doesn't count against itself or the
	 * two pieces either side of it (a loop comes out beside where it went in); finishing the circuit may come back alongside the station.)
	 */
	private boolean crashes(Coaster c, int start) {
		int n = c.path.size();
		int finish = n;
		if (!c.pieces.isEmpty() && c.pieces.get(c.pieces.size() - 1).piece == Piece.FINISH) {
			for (int i = 0; i < n; i++) {
				if (c.pieceAt[i] == c.pieces.size() - 1) {
					finish = i;
					break;
				}
			}
		}
		for (int i = Math.max(0, start); i < n; i++) {
			boolean inFinish = i >= finish;
			if (inFinish && i > n - 40) {
				continue;
			}
			Vec3 q = c.path.get(i);
			for (int j = inFinish ? 60 : 0; j < i - 16; j++) {
				if (Math.abs(c.pieceAt[i] - c.pieceAt[j]) <= 2) {
					continue;
				}
				Vec3 o = c.path.get(j);
				double dx = q.x - o.x;
				double dz = q.z - o.z;
				if (Math.abs(q.y - o.y) < 3.2 && dx * dx + dz * dz < 4.84) {
					return true;
				}
			}
		}
		return false;
	}

	/** Does the coaster stay on its spot (and below the sky limit)? */
	private boolean fits(Coaster c) {
		BlockPos s = station(c.plot);
		for (Vec3 p : c.path) {
			double[] l = local(c.plot, p);
			if (l[0] < -BEHIND || l[0] > AHEAD || Math.abs(l[1]) > SIDE - 1 || p.y > s.getY() + HIGH) {
				return false;
			}
		}
		return true;
	}

	private Vec3[] frame(Coaster c, int i) {
		int n = c.path.size();
		Vec3 fwd = c.path.get(Math.min(n - 1, i + 1)).subtract(c.path.get(Math.max(0, i - 1)));
		fwd = fwd.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : fwd.normalize();
		Vec3 up = c.ups.get(Math.min(n - 1, i));
		up = up.subtract(fwd.scale(fwd.dot(up)));
		up = up.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : up.normalize();
		return new Vec3[] {fwd, up, up.cross(fwd).normalize()};
	}

	private void clearShown(ServerLevel level, Coaster c) {
		if (level == null || c == null) {
			return;
		}
		for (UUID id : c.shown) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
			spawned.remove(id);
		}
		c.shown.clear();
		c.rails.clear();
	}

	/** Draw the coaster: rails on a spine, ties, supports, tunnels and pools, and the station sign. */
	private void redraw(ServerLevel level, Coaster c) {
		clearShown(level, c);
		if (c.path.isEmpty()) {
			lay(c);
		}
		DyeColor[] rainbow = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE, DyeColor.PURPLE};
		DyeColor[] look = COLOURS[Math.floorMod(c.colour, COLOURS.length)];
		BlockState rail = Blocks.CONCRETE.pick(look[0] == null ? DyeColor.RED : look[0]).defaultBlockState();
		BlockState spine = Blocks.CONCRETE.pick(look[1]).defaultBlockState();
		BlockState rock = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState tie = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
		BlockState steel = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
		BlockState chain = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		BlockState magnet = Blocks.GOLD_BLOCK.defaultBlockState();
		BlockState footing = Blocks.POLISHED_ANDESITE.defaultBlockState();
		BlockState water = Blocks.STAINED_GLASS.pick(DyeColor.BLUE).defaultBlockState();
		int ground = station(c.plot).getY();
		int seg = (int) Math.round(1.5 / STEP);
		for (int i = 0; i + seg < c.path.size(); i += seg) {
			Vec3 a = c.path.get(i);
			Vec3 b = c.path.get(i + seg);
			Vec3[] fa = frame(c, i);
			Vec3[] fb = frame(c, i + seg);
			if (look[0] == null) {
				rail = Blocks.CONCRETE.pick(rainbow[(i / seg) % rainbow.length]).defaultBlockState();
			}
			for (int s : new int[] {-1, 1}) {
				Display.BlockDisplay r = beam(level, c, a.add(fa[2].scale(0.45 * s)), b.add(fb[2].scale(0.45 * s)), 0.15F, 0.15F, rail);
				if (r != null) {
					c.rails.add(r.getUUID());
					if (night) {
						((DisplayAccessor) r).rubymod$setBrightness(new Brightness(15, 15));
					}
				}
			}
			// A tunnel: stone walls and a roof (high above your head).
			if (i < c.tunnel.length && c.tunnel[i]) {
				Vec3 sd = new Vec3(fa[2].x, 0, fa[2].z).normalize().scale(2.4);
				for (int k : new int[] {-1, 1}) {
					beam(level, c, a.add(sd.scale(k)).add(0, -0.4, 0), b.add(sd.scale(k)).add(0, -0.4, 0), 1.0F, 1.0F, rock);
					beam(level, c, a.add(sd.scale(k)).add(0, 1.6, 0), b.add(sd.scale(k)).add(0, 1.6, 0), 1.0F, 1.0F, rock);
				}
				beam(level, c, a.add(0, 4.2, 0), b.add(0, 4.2, 0), 1.0F, 1.0F, rock);
			}
			// A pool: water to splash through, in a stone rim.
			if (i < c.splash.length && c.splash[i]) {
				Vec3 sd = new Vec3(fa[2].x, 0, fa[2].z).normalize();
				Vec3 wa = new Vec3(a.x, ground + 0.5, a.z);
				Vec3 wb = new Vec3(b.x, ground + 0.5, b.z);
				beam(level, c, wa, wb, 5.0F, 0.1F, water);
				for (int k : new int[] {-1, 1}) {
					beam(level, c, wa.add(sd.scale(2.7 * k)).add(0, -0.15, 0), wb.add(sd.scale(2.7 * k)).add(0, -0.15, 0), 0.4F, 0.8F, rock);
				}
			}
			beam(level, c, a.add(fa[1].scale(-0.35)), b.add(fb[1].scale(-0.35)), 0.3F, 0.3F, spine);
			boolean boosting = i < c.launch.length && c.launch[i];
			// Climbing: the chain lift, a dark strip up the middle.
			if (!boosting && b.y - a.y > 0.25 && fa[1].y > 0.6) {
				beam(level, c, a.add(fa[1].scale(-0.17)), b.add(fb[1].scale(-0.17)), 0.12F, 0.12F, chain);
			}
			Vec3 mid = a.lerp(b, 0.5).add(fa[1].scale(-0.15));
			// Ties (gold launch magnets on a launch).
			float tw = boosting ? 0.16F : 0.1F;
			beam(level, c, mid.add(fa[2].scale(-0.5)), mid.add(fa[2].scale(0.5)), tw, tw, boosting ? magnet : tie);
			// Supports under raised track (not in loops), on a stone footing.
			if (i % (seg * 3) == 0 && fa[1].y > 0.6 && a.y > ground + 1.4) {
				beam(level, c, a.add(fa[1].scale(-0.5)), new Vec3(a.x, ground, a.z), 0.3F, 0.3F, steel);
				beam(level, c, new Vec3(a.x, ground - 0.05, a.z), new Vec3(a.x, ground + 0.35, a.z), 0.8F, 0.8F, footing);
			}
		}
		// The end of the track: a glowing marker where the next piece goes.
		Vec3 end = c.path.get(c.path.size() - 1);
		beam(level, c, end.add(0, -0.3, 0), end.add(0, 0.6, 0), 0.5F, 0.5F, c.circuit ? Blocks.EMERALD_BLOCK.defaultBlockState()
				: Blocks.REDSTONE_BLOCK.defaultBlockState());
		// The station sign.
		Display.TextDisplay t = label(level, rel(c.plot, 3, 6.6, 0), Component.literal(nameOf(c)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("\nby " + ownerOf(c) + "\n" + c.pieces.size() + " pieces" + (c.circuit ? ", full circuit!" : "")
						+ (c.loopable && c.laps != 1 ? ", " + (c.laps == 0 ? "round and round" : c.laps + " laps") : "")
						+ "\nPress Ride! here to ride it").withStyle(ChatFormatting.WHITE)), 1.0F);
		if (t != null) {
			spawned.remove(t.getUUID());
			c.shown.add(t.getUUID());
			spawned.add(t.getUUID());
		}
	}

	private void add(ServerLevel level, Coaster c, Entity e) {
		e.addTag(tag());
		spawned.add(e.getUUID());
		level.addFreshEntity(e);
		c.shown.add(e.getUUID());
	}

	/** A box stretched from a to b, w wide and h high. */
	private Display.BlockDisplay beam(ServerLevel level, Coaster c, Vec3 a, Vec3 b, float w, float h, BlockState state) {
		Display.BlockDisplay d = makeBeam(level, a, b, w, h, state);
		if (d != null) {
			add(level, c, d);
		}
		return d;
	}

	private static Display.BlockDisplay makeBeam(ServerLevel level, Vec3 a, Vec3 b, float w, float h, BlockState state) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		Vec3 dv = b.subtract(a);
		float len = (float) dv.length();
		Quaternionf rot = new Quaternionf().rotateY((float) Math.atan2(dv.x, dv.z)).rotateX((float) -Math.asin(Math.max(-1, Math.min(1, dv.y / Math.max(1e-6, len)))));
		Vector3f off = rot.transform(new Vector3f(-w / 2, -h / 2, 0));
		d.snapTo(a.x, a.y, a.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(off, rot, new Vector3f(w, h, len), new Quaternionf()));
		((DisplayAccessor) d).rubymod$setViewRange(4.0F);
		return d;
	}

	// ----- The hologram: what a piece would look like, before it goes in -----

	private void preview(ServerLevel level, ServerPlayer p, Builder b, Coaster c) {
		String held = p.getMainHandItem().getHoverName().getString();
		Piece piece = null;
		for (Piece q : Piece.values()) {
			if (q.label.equals(held)) {
				piece = q;
			}
		}
		if (b.selected >= c.pieces.size()) {
			b.selected = -1;
		}
		boolean showing = piece != null || b.selected >= 0;
		String key = !showing ? "" : held + "|" + b.big + "|" + b.selected + "|" + b.insert + "|" + c.pieces.hashCode();
		if (key.equals(b.previewKey)) {
			return;
		}
		b.previewKey = key;
		clearGhost(level, b);
		if (!showing) {
			return;
		}
		Coaster tmp = new Coaster();
		tmp.plot = c.plot;
		int at;
		String why;
		String verb;
		int glow;
		BlockState look;
		if (piece != null) {
			Placed placed = new Placed(piece, b.big && piece.sizes);
			Edit e = edit(c, b, placed);
			tmp.pieces = e.list;
			lay(tmp);
			at = e.at;
			why = e.why != null ? e.why : check(tmp, at);
			verb = b.selected < 0 ? "add it" : b.insert ? "put it in" : "swap it in";
			glow = why == null ? 0x5AA8F0 : 0xFF5555;
			look = Blocks.STAINED_GLASS.pick(why == null ? DyeColor.LIGHT_BLUE : DyeColor.RED).defaultBlockState();
			String head = "Piece " + (at + 1) + " of " + tmp.pieces.size() + ": " + placed.label();
			hologram(level, b, tmp, at, look, glow, Component.literal(head).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
					.append(why == null ? Component.literal("\nRight-click to " + verb).withStyle(ChatFormatting.AQUA)
							: Component.literal("\n" + why).withStyle(ChatFormatting.RED)));
		} else {
			// Just the piece they've picked, in yellow.
			hologram(level, b, c, b.selected, Blocks.STAINED_GLASS.pick(DyeColor.YELLOW).defaultBlockState(), 0xFFDD33,
					Component.literal("Piece " + (b.selected + 1) + " of " + c.pieces.size() + ": " + c.pieces.get(b.selected).label())
							.withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
							.append(Component.literal("\nA piece tool " + (b.insert ? "puts one in before it" : "swaps it") + "; Undo takes it out")
									.withStyle(ChatFormatting.WHITE)));
		}
	}

	private void hologram(ServerLevel level, Builder b, Coaster c, int piece, BlockState look, int glow, Component text) {
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < c.pieceAt.length; i++) {
			if (c.pieceAt[i] == piece) {
				idx.add(i);
			}
		}
		if (idx.isEmpty()) {
			// Nothing laid (it didn't go anywhere): mark the end of the track.
			idx.add(c.path.size() - 1);
		}
		Vec3 top = null;
		for (int k = 0; k + 1 < idx.size(); k += 6) {
			int i = idx.get(k);
			int j = idx.get(Math.min(idx.size() - 1, k + 6));
			Vec3 a = c.path.get(i);
			Vec3 e = c.path.get(j);
			Display.BlockDisplay d = makeBeam(level, a, e, 0.9F, 0.25F, look);
			if (d != null) {
				d.setGlowingTag(true);
				((DisplayAccessor) d).rubymod$setGlowColorOverride(glow);
				((DisplayAccessor) d).rubymod$setBrightness(new Brightness(15, 15));
				ghost(level, b, d);
			}
			if (top == null || a.y > top.y) {
				top = a;
			}
		}
		Vec3 mid = c.path.get(idx.get(idx.size() / 2));
		Vec3 at = new Vec3(mid.x, Math.max(mid.y, top == null ? mid.y : top.y) + 2.6, mid.z);
		Display.TextDisplay t = label(level, at, text, 1.0F);
		if (t != null) {
			b.ghost.add(t.getUUID());
		}
	}

	private void ghost(ServerLevel level, Builder b, Entity e) {
		e.addTag(tag());
		spawned.add(e.getUUID());
		level.addFreshEntity(e);
		b.ghost.add(e.getUUID());
	}

	private void clearGhost(ServerLevel level, Builder b) {
		for (UUID id : b.ghost) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
			spawned.remove(id);
		}
		b.ghost.clear();
	}

	// ----- Riding -----

	private void ride(ServerPlayer p, Coaster c) {
		ServerLevel level = level();
		if (c.pieces.isEmpty()) {
			p.sendOverlayMessage(Component.literal("Build some track first! Right-click the pieces in your hotbar.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		Builder b = builders.get(p.getUUID());
		if (b != null) {
			clearGhost(level, b);
			b.previewKey = "riding";
		}
		// A train still waiting in this station? Hop on with your friends.
		Ride r = null;
		for (Ride t : trains) {
			if (t.coaster == c && t.boarding > 0 && t.free() >= 0) {
				r = t;
			}
		}
		if (r == null) {
			r = new Ride(c);
			r.laps = c.loopable ? c.laps : 1;
			if (!makeTrain(level, r)) {
				return;
			}
			trains.add(r);
		}
		int k = r.free();
		Entity seat = level.getEntity(r.seats[k]);
		if (seat == null) {
			return;
		}
		p.stopRiding();
		p.startRiding(seat, true, true);
		r.riders[k] = p.getUUID();
		rides.put(p.getUUID(), r);
		Alerts.title(p, nameOf(c), "by " + ownerOf(c) + (r.laps == 0 ? "  (round and round: crouch to get off)" : r.laps > 1 ? "  (" + r.laps + " laps)" : ""),
				ChatFormatting.GOLD);
		p.sendOverlayMessage(Component.literal(k == 0 ? "Front seat! Hold on (crouch to get off)" : "All aboard! (crouch to get off)").withStyle(ChatFormatting.GOLD));
		level.playSound(null, p.blockPosition(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 0.8F, 1.2F);
	}

	/** A train of three cars in the station, the front one at the head of the platform. */
	private boolean makeTrain(ServerLevel level, Ride r) {
		String colour = CAR_COLOURS[r.coaster.plot % CAR_COLOURS.length];
		for (int k = 0; k < CARS; k++) {
			Display.BlockDisplay seat = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			Display.ItemDisplay car = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (seat == null || car == null) {
				end(level, r);
				return false;
			}
			Vec3 at = r.coaster.path.get((int) Math.min(r.coaster.path.size() - 1, Math.max(0, r.d - k * GAP) / STEP));
			seat.snapTo(at.x, at.y + 0.55, at.z, 0, 0);
			((BlockDisplayAccessor) seat).rubymod$setBlockState(Blocks.AIR.defaultBlockState());
			((DisplayAccessor) seat).rubymod$setPosRotInterpolationDuration(2);
			seat.addTag(tag());
			spawned.add(seat.getUUID());
			level.addFreshEntity(seat);
			car.snapTo(at.x, at.y + 0.55, at.z, 0, 0);
			ItemStack look = new ItemStack(Items.STICK);
			look.set(DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_coaster_solo_" + colour));
			((ItemDisplayAccessor) car).rubymod$setItemStack(look);
			car.addTag(tag());
			spawned.add(car.getUUID());
			level.addFreshEntity(car);
			car.startRiding(seat, true, true);
			r.seats[k] = seat.getUUID();
			r.cars[k] = car.getUUID();
		}
		place(level, r);
		level.playSound(null, BlockPos.containing(rel(r.coaster.plot, 3, 1, 0)), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.6F, 1.6F);
		return true;
	}

	private void tell(Ride r, Component text) {
		for (UUID id : r.riders) {
			ServerPlayer p = id == null ? null : PartyGames.player(server, id);
			if (p != null) {
				p.sendOverlayMessage(text);
			}
		}
	}

	/** Length of one time round (or the whole track). */
	private static double length(Coaster c) {
		return (c.path.size() - 1) * STEP;
	}

	/** Where distance {@code d} is on the track: round again for a lapping train. */
	private static double along(Ride r, double d) {
		double len = length(r.coaster);
		return r.laps != 1 && r.coaster.loopable ? Math.floorMod((long) Math.floor(d * 1000), (long) Math.floor(len * 1000)) / 1000.0
				: Math.max(0, Math.min(d, len));
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (level == null) {
			return;
		}
		// Night lights: the rails glow after dark.
		if (roundTicks % 40 == 0 && level.isDarkOutside() != night) {
			night = level.isDarkOutside();
			for (Coaster c : coasters.values()) {
				for (UUID id : c.rails) {
					if (level.getEntity(id) instanceof Display d) {
						((DisplayAccessor) d).rubymod$setBrightness(night ? new Brightness(15, 15) : null);
					}
				}
			}
		}
		// Builders: the hologram of what they're holding, and what they've picked.
		if (roundTicks % 4 == 0) {
			for (ServerPlayer p : online()) {
				Builder b = builders.get(p.getUUID());
				Coaster c = coasters.get(p.getName().getString());
				if (b == null || c == null || rides.containsKey(p.getUUID())) {
					continue;
				}
				preview(level, p, b, c);
				if (b.selected >= 0 && roundTicks % 40 == 0 && p.getMainHandItem().getHoverName().getString().equals(UNDO)) {
					p.sendOverlayMessage(Component.literal("Undo takes out piece " + (b.selected + 1) + " (Pick a Piece at the sky to stop editing)")
							.withStyle(ChatFormatting.AQUA));
				}
			}
		}
		for (var it = trains.iterator(); it.hasNext();) {
			Ride r = it.next();
			Coaster c = r.coaster;
			int n = c.path.size();
			double len = length(c);
			boolean done = r.laps > 0 && r.d >= r.laps * len;
			// Who's still aboard? (Crouching gets you off: back to the station.)
			ServerPlayer front = null;
			List<ServerPlayer> aboard = new ArrayList<>();
			for (int k = 0; k < CARS; k++) {
				if (r.riders[k] == null) {
					continue;
				}
				ServerPlayer p = PartyGames.player(server, r.riders[k]);
				Entity seat = level.getEntity(r.seats[k]);
				if (p == null || seat == null || p.getVehicle() != seat) {
					if (rides.get(r.riders[k]) == r) {
						rides.remove(r.riders[k]);
					}
					r.riders[k] = null;
					if (p != null && p.level() == level) {
						offTrain(p, c, k, false);
					}
					continue;
				}
				aboard.add(p);
				if (front == null) {
					front = p;
				}
			}
			if (front == null || done) {
				end(level, r);
				it.remove();
				for (int k = 0; k < CARS; k++) {
					ServerPlayer p = r.riders[k] == null ? null : PartyGames.player(server, r.riders[k]);
					if (p != null) {
						rides.remove(p.getUUID());
						if (p.level() == level) {
							offTrain(p, c, k, true);
						}
					}
				}
				continue;
			}
			// Waiting in the station for friends.
			if (r.boarding > 0) {
				r.boarding--;
				if (r.boarding % 20 == 0 && r.boarding > 0) {
					tell(r, Component.literal("Leaving in " + (r.boarding / 20) + "...  (friends: press Ride! here to hop on)").withStyle(ChatFormatting.GOLD));
				}
				if (r.boarding == 0) {
					tell(r, Component.literal("Here we go!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
					level.playSound(null, BlockPos.containing(rel(c.plot, 3, 1, 0)), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.8F, 1.2F);
				}
				continue;
			}
			// Another lap?
			int lap = (int) (r.d / len) + 1;
			if (lap != r.lap) {
				r.lap = lap;
				tell(r, Component.literal(r.laps == 0 ? "Round again! Lap " + lap + "  (crouch to get off)" : "Lap " + lap + " of " + r.laps + "!")
						.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
				level.playSound(null, front, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.6F, 1.6F);
			}
			// Speed: the middle of the train goes up and down; the front sets off launches, pools and the brakes.
			int lead = (int) Math.min(n - 1, along(r, r.d) / STEP);
			int i = (int) Math.min(n - 1, along(r, r.d - GAP) / STEP);
			int j = Math.min(n - 1, i + 4);
			double dy = c.path.get(j).y - c.path.get(i).y;
			boolean lift = false;
			if (c.launch[lead]) {
				if (!r.launched) {
					r.launched = true;
					level.playSound(null, front, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 0.6F);
					level.playSound(null, front, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.7F, 0.8F);
				}
				r.v = Math.min(1.5, r.v + 0.06);
			} else {
				r.launched = false;
				double v2 = r.v * r.v - 2 * 0.03 * dy * r.v - 0.0004;
				r.v = Math.sqrt(Math.max(0, v2));
				// A chain lift takes you up anything you're too slow for.
				lift = r.v < 0.2 && dy > 0.05;
			}
			// Splashdown: a big splash going in, and the water slows you a little.
			if (c.splash[lead]) {
				if (!r.splashing) {
					r.splashing = true;
					Vec3 sp = c.path.get(lead);
					level.sendParticles(ParticleTypes.SPLASH, sp.x, sp.y + 0.6, sp.z, 160, 1.6, 0.4, 1.6, 0.4);
					level.sendParticles(ParticleTypes.FALLING_WATER, sp.x, sp.y + 1.5, sp.z, 60, 1.8, 0.8, 1.8, 0.1);
					level.playSound(null, front, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 1.2F, 0.9F);
					level.playSound(null, front, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.0F, 0.7F);
					tell(r, Component.literal("SPLASH!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
				}
				r.v *= 0.97;
				for (ServerPlayer p : aboard) {
					level.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 0.2, p.getZ(), 6, 0.5, 0.1, 0.5, 0.2);
				}
			} else {
				r.splashing = false;
			}
			r.v = Math.max(0.2, Math.min(1.6, r.v));
			// The brakes, coming into the station at the end of the ride (or the end of the line).
			double left = r.laps > 0 ? r.laps * len - r.d : Double.MAX_VALUE;
			if (left < 8 && r.v > 0.12 + left * 0.05) {
				r.v = 0.12 + left * 0.05;
				if (!r.braked) {
					r.braked = true;
					level.playSound(null, front, SoundEvents.PISTON_CONTRACT, SoundSource.PLAYERS, 0.7F, 0.6F);
					level.playSound(null, front, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4F, 1.6F);
				}
			}
			// Sounds: the chain lift's clack, rattling along, the wind at speed.
			if (lift && roundTicks % 4 == 0) {
				level.playSound(null, front, SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.35F, 1.9F);
				level.playSound(null, front, SoundEvents.CHAIN_STEP, SoundSource.PLAYERS, 0.4F, 0.8F);
			}
			if (r.v > 0.6 && roundTicks % 10 == 0) {
				level.playSound(null, front, SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, (float) (0.2 + 0.3 * r.v), (float) (0.9 + 0.4 * r.v));
			}
			if (--r.windCool <= 0 && r.v > 1.1) {
				r.windCool = 70;
				level.playSound(null, front, SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.35F, 1.3F);
			}
			if (--r.wheeCool <= 0 && r.v > 1.0 && dy < -0.5) {
				r.wheeCool = 120;
				String[] shout = {"WHEEEE!", "AAAAH!", "WOOHOO!", "YEEHAW!"};
				tell(r, Component.literal(shout[level.getRandom().nextInt(shout.length)]).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
			}
			for (ServerPlayer p : aboard) {
				if (r.v > 1.0 && roundTicks % 6 == 0) {
					p.addEffect(new MobEffectInstance(MobEffects.SPEED, 14, 3, false, false));
				}
				if (c.launch[lead] && roundTicks % 3 == 0) {
					level.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY(), p.getZ(), 2, 0.3, 0.1, 0.3, 0.02);
				}
			}
			r.d += r.v;
			place(level, r);
		}
	}

	/** Back on the station platform (side by side, if you rode together). */
	private void offTrain(ServerPlayer p, Coaster c, int k, boolean done) {
		Vec3 home = rel(c.plot, 3 - k, 0, 2);
		teleport(p, home.x, home.y, home.z, lookOf(c.plot));
		p.removeEffect(MobEffects.SPEED);
		Builder b = builders.get(p.getUUID());
		if (b != null) {
			b.previewKey = "";
		}
		if (done) {
			Alerts.title(p, c.circuit ? "WHAT A RIDE!" : "END OF THE LINE!", c.circuit ? "" : "Tip: bring the track back round to the station",
					ChatFormatting.GOLD);
		}
	}

	private void place(ServerLevel level, Ride r) {
		for (int k = 0; k < CARS; k++) {
			placeCar(level, r.coaster, r.seats[k], r.cars[k], along(r, Math.max(0, r.d - k * GAP)));
		}
	}

	/**
	 * A seat to the track, and its car turned to it: tipped up and down only a little, and leaning into banked turns
	 * (it rides round you: you stay upright).
	 */
	private void placeCar(ServerLevel level, Coaster c, UUID seatId, UUID carId, double d) {
		int n = c.path.size();
		double f = Math.min(n - 1.001, d / STEP);
		int i = (int) Math.floor(f);
		Vec3 p = c.path.get(i).lerp(c.path.get(i + 1), f - i);
		Vec3[] fr = frame(c, i);
		Vec3 sp = p.add(fr[1].scale(0.55));
		Entity seat = seatId == null ? null : level.getEntity(seatId);
		if (seat == null) {
			return;
		}
		seat.teleportTo(sp.x, sp.y, sp.z);
		Vec3 h = new Vec3(fr[0].x, 0, fr[0].z).subtract(new Vec3(fr[1].x, 0, fr[1].z).scale(fr[0].y));
		float yaw = h.lengthSqr() < 1e-6 ? seat.getYRot() : (float) Math.toDegrees(Math.atan2(-h.x, h.z));
		seat.setYRot(yaw);
		for (Entity rider : seat.getPassengers()) {
			rider.resetFallDistance();
		}
		if (level.getEntity(carId) instanceof Display disp) {
			double yr = Math.toRadians(yaw);
			Vec3 hd = new Vec3(-Math.sin(yr), 0, Math.cos(yr));
			double pitch = Math.max(-0.35, Math.min(0.35, Math.asin(Math.max(-1, Math.min(1, fr[0].y)))));
			Vec3 fc = hd.scale(Math.cos(pitch)).add(0, Math.sin(pitch), 0);
			Vec3 b0 = new Vec3(0, 1, 0).subtract(fc.scale(fc.y)).normalize();
			Vec3 r0 = b0.cross(fc);
			Vec3 uu = fr[1].subtract(fc.scale(fc.dot(fr[1])));
			double roll = Math.atan2(uu.dot(r0), uu.dot(b0));
			// Lean with the track up to 40 degrees, and back upright when it goes upside down.
			double lim = 0.7;
			double rc = Math.abs(roll) <= lim ? roll
					: Math.copySign(lim, roll) * Math.cos((Math.abs(roll) - lim) * Math.PI / (2 * (Math.PI - lim)));
			Vec3 carUp = b0.scale(Math.cos(rc)).add(r0.scale(Math.sin(rc)));
			Vec3 right = carUp.cross(fc);
			Quaternionf q = new Quaternionf().setFromNormalized(new org.joml.Matrix3f((float) right.x, (float) right.y, (float) right.z,
					(float) carUp.x, (float) carUp.y, (float) carUp.z, (float) fc.x, (float) fc.y, (float) fc.z));
			Vector3f off = new Vector3f((float) carUp.x, (float) carUp.y, (float) carUp.z).mul(0.9F)
					.sub(new Vector3f((float) fr[1].x, (float) fr[1].y, (float) fr[1].z).mul(0.55F));
			((DisplayAccessor) disp).rubymod$setTransformationInterpolationDelay(0);
			((DisplayAccessor) disp).rubymod$setTransformationInterpolationDuration(2);
			((DisplayAccessor) disp).rubymod$setTransformation(new Transformation(off, new Quaternionf(q).rotateY((float) Math.PI),
					new Vector3f(1.8F, 1.8F, 1.8F), new Quaternionf()));
		}
	}

	private void end(ServerLevel level, Ride r) {
		for (UUID[] ids : new UUID[][] {r.cars, r.seats}) {
			for (UUID id : ids) {
				Entity e = id == null ? null : level.getEntity(id);
				if (e != null) {
					e.discard();
				}
				spawned.remove(id);
			}
		}
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		// Their car carries on empty (the train's tick notices they're gone); their hologram goes.
		rides.remove(id);
		Builder b = builders.remove(id);
		if (b != null && level() != null) {
			clearGhost(level(), b);
		}
	}

	// ----- Keeping the coasters -----

	private Path file() {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("coasters.txt");
	}

	/** Saved as owner|spot|pieces|colour|laps|name (pieces like LOOP, or LOOP+ for a big one). */
	private void load() {
		if (loaded || server == null) {
			return;
		}
		loaded = true;
		try {
			if (!Files.exists(file())) {
				return;
			}
			for (String line : Files.readAllLines(file())) {
				String[] f = line.split("\\|", -1);
				if (f.length < 3) {
					continue;
				}
				Coaster c = new Coaster();
				c.plot = Math.max(0, Math.min(PLOTS - 1, Integer.parseInt(f[1].trim())));
				if (atPlot(c.plot) != null) {
					continue;
				}
				if (f.length > 3 && !f[3].isBlank()) {
					c.colour = Integer.parseInt(f[3].trim());
				}
				if (f.length > 4 && !f[4].isBlank()) {
					c.laps = Integer.parseInt(f[4].trim());
				}
				if (f.length > 5) {
					c.name = f[5].trim();
				}
				for (String p : f[2].split(",")) {
					String t = p.trim();
					if (t.isEmpty()) {
						continue;
					}
					boolean big = t.endsWith("+");
					try {
						c.pieces.add(new Placed(Piece.valueOf(big ? t.substring(0, t.length() - 1) : t), big));
					} catch (IllegalArgumentException e) {
						// A piece from a newer version: skipped.
					}
				}
				lay(c);
				coasters.put(f[0], c);
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the saved coasters", e);
		}
	}

	private void save() {
		StringBuilder b = new StringBuilder();
		for (var e : coasters.entrySet()) {
			Coaster c = e.getValue();
			b.append(e.getKey()).append('|').append(c.plot).append('|');
			for (Placed p : c.pieces) {
				b.append(p.save()).append(',');
			}
			b.append('|').append(c.colour).append('|').append(c.laps).append('|').append(c.name).append('\n');
		}
		try {
			Files.createDirectories(file().getParent());
			dev.rubymod.game.SafeFiles.write(file(), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the coasters", e);
		}
	}

	// ----- For admins: who has which spot -----

	/** /rubywars coasters: every spot and who has it. */
	public List<String> spots() {
		load();
		List<String> out = new ArrayList<>();
		for (int i = 0; i < PLOTS; i++) {
			Coaster c = atPlot(i);
			out.add("Spot " + (i + 1) + ": " + (c == null ? "free" : nameOf(c) + " (" + ownerOf(c) + ", " + c.pieces.size() + " pieces)"));
		}
		return out;
	}

	/** /rubywars coasters free <name>: give that player's spot up (their coaster goes). True if they had one. */
	public boolean free(String owner) {
		load();
		String key = null;
		for (String k : coasters.keySet()) {
			if (k.equalsIgnoreCase(owner)) {
				key = k;
			}
		}
		if (key == null) {
			return false;
		}
		Coaster c = coasters.remove(key);
		save();
		ServerLevel level = level();
		if (level != null) {
			clearShown(level, c);
			gate(level, c.plot);
			board(level);
		}
		return true;
	}

	// ----- Built once a run, kept between visits -----

	@Override
	public void queueBuild() {
		if (builtThisRun) {
			return;
		}
		builtThisRun = true;
		super.queueBuild();
	}

	@Override
	protected void clearArea() {
		if (builtThisRun && !coasters.isEmpty() && level() != null) {
			return;
		}
		super.clearArea();
		java.util.Arrays.fill(gates, null);
		board = null;
	}

	@Override
	public void tick() {
		if (server != null && phase == Phase.LOBBY && !building() && timer > 1) {
			timer = 1;
		}
		super.tick();
	}

	@Override
	public void reset() {
		super.reset();
		builtThisRun = false;
		loaded = false;
		coasters.clear();
		rides.clear();
		trains.clear();
		builders.clear();
		java.util.Arrays.fill(gates, null);
		board = null;
	}

	// ----- For autotests -----

	/** This player's coaster: pieces, and how far its track reaches from the station (ahead, side, up). */
	public String testInfo(ServerPlayer p) {
		Coaster c = mine(p);
		if (c == null) {
			return "no spot";
		}
		BlockPos s = station(c.plot);
		double ahead = 0;
		double behind = 0;
		double side = 0;
		double up = 0;
		for (Vec3 v : c.path) {
			double[] l = local(c.plot, v);
			ahead = Math.max(ahead, l[0]);
			behind = Math.min(behind, l[0]);
			side = Math.max(side, Math.abs(l[1]));
			up = Math.max(up, v.y - s.getY());
		}
		return "spot " + (c.plot + 1) + ", " + c.pieces.size() + " pieces, reaches " + Math.round(ahead) + " ahead, " + Math.round(-behind) + " behind, "
				+ Math.round(side) + " to the side, " + Math.round(up) + " up" + (c.loopable ? ", loops" : "") + ", laps " + c.laps + ", called " + nameOf(c);
	}

	/** Start this player's coaster again from these pieces (by name, + for big); what happened to each. */
	public String testBuild(ServerPlayer p, String... names) {
		Coaster c = mine(p);
		c.pieces = new ArrayList<>();
		lay(c);
		Builder b = builders.computeIfAbsent(p.getUUID(), k -> new Builder());
		b.selected = -1;
		StringBuilder out = new StringBuilder();
		for (String name : names) {
			boolean big = name.endsWith("+");
			out.append(name).append(testPiece(c, b, new Placed(Piece.valueOf(big ? name.substring(0, name.length() - 1) : name), big))).append("; ");
		}
		changed(level(), c);
		return out + testInfo(p);
	}

	private String testPiece(Coaster c, Builder b, Placed placed) {
		Edit e = edit(c, b, placed);
		String why = e.why;
		if (why == null) {
			c.pieces = e.list;
			lay(c);
			why = check(c, e.at);
			if (why != null) {
				c.pieces = e.before;
				lay(c);
			}
		}
		return why == null ? " ok" : " NO: " + why;
	}

	/** Edit piece {@code index}: replace it, put one in before it, or ("REMOVE") take it out. */
	public String testEdit(ServerPlayer p, int index, boolean insert, String name) {
		Coaster c = mine(p);
		Builder b = builders.computeIfAbsent(p.getUUID(), k -> new Builder());
		b.selected = index;
		b.insert = insert;
		String r;
		if (name.equals("REMOVE")) {
			List<Placed> before = c.pieces;
			List<Placed> list = new ArrayList<>(before);
			list.remove(index);
			c.pieces = list;
			lay(c);
			String why = check(c, index);
			if (why != null) {
				c.pieces = before;
				lay(c);
			}
			r = why == null ? " ok" : " NO: " + why;
		} else {
			boolean big = name.endsWith("+");
			r = testPiece(c, b, new Placed(Piece.valueOf(big ? name.substring(0, name.length() - 1) : name), big));
		}
		b.selected = -1;
		b.insert = false;
		changed(level(), c);
		return (insert ? "insert " : "edit ") + (index + 1) + " " + name + r + "; now " + c.pieces.stream().map(Placed::save).toList();
	}

	/** Pick piece {@code index} (as if looked at), for the hologram. */
	public void testSelect(ServerPlayer p, int index) {
		builders.computeIfAbsent(p.getUUID(), k -> new Builder()).selected = index;
	}

	public void testLapsAndName(ServerPlayer p, int laps, String name) {
		Coaster c = mine(p);
		c.laps = laps;
		if (name != null) {
			c.name = name;
		}
		changed(level(), c);
	}

	/** The rides going: lap and distance of each. */
	public String testTrains() {
		StringBuilder b = new StringBuilder();
		for (Ride r : trains) {
			b.append("lap ").append(r.lap).append(" of ").append(r.laps).append(" at ").append(Math.round(r.d)).append(" (track ")
					.append(Math.round(length(r.coaster))).append(") ");
		}
		return b.isEmpty() ? "no trains" : b.toString();
	}

	/** Give up every spot but this player's (test players get a new name each run). */
	public void testFreeOthers(ServerPlayer p) {
		for (String k : new ArrayList<>(coasters.keySet())) {
			if (!k.equals(p.getName().getString())) {
				free(k);
			}
		}
	}

	/** As if Name Your Coaster was pressed. */
	public void testStartNaming(ServerPlayer p) {
		builders.computeIfAbsent(p.getUUID(), k -> new Builder()).naming = true;
	}

	/** As if holding piece tool {@code label}. */
	public void testHold(ServerPlayer p, String label) {
		for (Piece q : Piece.values()) {
			if (q.label.equals(label)) {
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool(q.icon, q.label, q.color));
			}
		}
	}

	public BlockPos testCenter() {
		return center();
	}

	public BlockPos testStation(ServerPlayer p) {
		return station(mine(p).plot);
	}
}
