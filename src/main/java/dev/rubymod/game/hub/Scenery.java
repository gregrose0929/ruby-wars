package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.game.Props;
import dev.rubymod.mixin.DisplayAccessor;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Making the park prettier: drifting clouds and little floating islets in the sky between the islands, string lights
 * and floodlights so it glows at night, a welcome sign where you land on each island, and dressing for the plainer
 * spots (the Speedway's grandstand and flags, flower beds and benches round the Fun Park and the Thrill Zone).
 */
final class Scenery {
	private Scenery() {
	}

	private record Cloud(UUID id, double speed) {
	}

	private static final List<Cloud> CLOUDS = new ArrayList<>();

	static void build(ServerLevel level) {
		clouds(level);
		islets(level);
		signs(level);
		speedway(level);
		funPark(level);
		thrillZone(level);
		golf(level);
		spooky(level);
		glow(level);
	}

	/**
	 * Soft light over every island at night: invisible light blocks a few blocks up, spread out, so paths and rides are
	 * gently lit after dark (dimmer at Spooky Hollow, to keep it spooky).
	 */
	private static void glow(ServerLevel level) {
		int[][] islands = {{0, 0, 23, 11}, {56, 2, 17, 12}, {44, -44, 11, 12}, {0, 56, 14, 12}, {-44, 44, 16, 12}, {-46, -34, 12, 11}, {-41, 6, 9, 11},
			{44, 40, 13, 6}, {6, -63, 14, 12}, {-88, 6, 22, 12}, {84, 40, 22, 12}};
		for (int[] is : islands) {
			BlockState light = Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, is[3]);
			for (int x = -is[2]; x <= is[2]; x += 6) {
				for (int z = -is[2]; z <= is[2]; z += 6) {
					if (x * x + z * z > is[2] * is[2]) {
						continue;
					}
					for (int y = FLOOR + 3; y <= FLOOR + 6; y++) {
						BlockPos p = new BlockPos(is[0] + x, y, is[1] + z);
						if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isAir()) {
							level.setBlock(p, light, 2);
							break;
						}
					}
				}
			}
		}
	}

	// ----- The sky -----

	private static void clouds(ServerLevel level) {
		CLOUDS.clear();
		var random = new java.util.Random(4);
		String[] shapes = {"cloud_a", "cloud_b", "cloud_c"};
		for (int i = 0; i < 18; i++) {
			double a = random.nextDouble() * Math.PI * 2;
			double r = 45 + random.nextDouble() * 80;
			boolean high = i % 2 == 0;
			double y = high ? FLOOR + 42 + random.nextDouble() * 12 : FLOOR - 26 - random.nextDouble() * 14;
			Vec3 at = new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
			Display.ItemDisplay d = HubPark.model(level, at, random.nextFloat() * 360, shapes[i % shapes.length], 8 + random.nextFloat() * 8, 0);
			if (d != null) {
				// Lit by the sky (bright by day, dim at night), never by lamps.
				((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(0, 15));
				((DisplayAccessor) d).rubymod$setViewRange(12F);
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(40);
				CLOUDS.add(new Cloud(d.getUUID(), 0.015 + random.nextDouble() * 0.02));
			}
		}
	}

	/** Little floating islets with a tree on top, below the park, filling the sky between the big islands. */
	private static void islets(ServerLevel level) {
		int[][] spots = {{36, 24, -9}, {-64, -14, -8}, {24, -82, -10}, {66, -24, -9}, {60, 72, -8}, {-66, 64, -10}, {-10, -106, -9}, {112, 8, -8},
			{-114, 40, -9}, {-22, 84, -10}, {-30, -60, -12}, {100, -20, -11}};
		var random = new java.util.Random(9);
		for (int[] s : spots) {
			int top = FLOOR + s[2];
			int r = 3 + random.nextInt(2);
			HubPark.island(level, s[0], s[1], r, top);
			// The island builder puts a hedge round the rim; on these little ones, flowers instead.
			for (int dx = -r - 1; dx <= r + 1; dx++) {
				for (int dz = -r - 1; dz <= r + 1; dz++) {
					BlockPos p = new BlockPos(s[0] + dx, top + 1, s[1] + dz);
					if (level.getBlockState(p).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
						level.setBlock(p, random.nextBoolean() ? Blocks.POPPY.defaultBlockState() : Blocks.AZURE_BLUET.defaultBlockState(), 2);
					}
				}
			}
			tree(level, s[0], top + 1, s[1], random.nextBoolean());
		}
	}

	private static void tree(ServerLevel level, int x, int y, int z, boolean cherry) {
		BlockState log = cherry ? Blocks.CHERRY_LOG.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState();
		BlockState leaves = (cherry ? Blocks.CHERRY_LEAVES : Blocks.OAK_LEAVES).defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		HubPark.box(level, x, y, z, x, y + 3, z, log);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 3; dy <= 5; dy++) {
					if (Math.abs(dx) + Math.abs(dz) + (dy == 5 ? 2 : 0) <= 3 && !(dx == 0 && dz == 0 && dy < 4)) {
						HubPark.set(level, x + dx, y + dy, z + dz, leaves);
					}
				}
			}
		}
	}

	static void tick(ServerLevel level, int tick) {
		if (tick % 40 != 0) {
			return;
		}
		for (Cloud c : CLOUDS) {
			Entity e = level.getEntity(c.id());
			if (e == null) {
				continue;
			}
			double x = e.getX() + c.speed() * 40;
			if (x > 140) {
				((DisplayAccessor) e).rubymod$setPosRotInterpolationDuration(0);
				x = -140;
			} else {
				((DisplayAccessor) e).rubymod$setPosRotInterpolationDuration(40);
			}
			e.teleportTo(x, e.getY(), e.getZ());
		}
	}

	// ----- Lights -----

	/** A string of glowing bulbs from a to b, sagging in the middle. */
	static void lights(ServerLevel level, Vec3 a, Vec3 b, DyeColor[] colors, int offset) {
		int steps = Math.max(2, (int) (a.distanceTo(b) / 1.1));
		for (int i = 1; i < steps; i++) {
			double t = i / (double) steps;
			Vec3 p = a.lerp(b, t).add(0, -0.9 * Math.sin(Math.PI * t), 0);
			Display.BlockDisplay d = HubPark.piece(level, p, Blocks.STAINED_GLASS.pick(colors[(i + offset) % colors.length]).defaultBlockState(),
					new Vector3f(0.2F, 0.28F, 0.2F), new Quaternionf());
			if (d != null) {
				((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
			}
		}
	}

	private static final DyeColor[] PARTY = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE, DyeColor.MAGENTA, DyeColor.ORANGE};
	private static final DyeColor[] WARM = {DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.WHITE};

	/** A pole with a lantern on top (for string lights to hang from). */
	private static Vec3 pole(ServerLevel level, int x, int z, int height) {
		HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + height, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		HubPark.set(level, x, FLOOR + height + 1, z, Blocks.LANTERN.defaultBlockState());
		return new Vec3(x + 0.5, FLOOR + height + 0.8, z + 0.5);
	}

	/** A floodlight tower: a steel mast with a bank of lights at the top. */
	private static void floodlight(ServerLevel level, int x, int z) {
		HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 8, z, Blocks.IRON_BARS.defaultBlockState());
		HubPark.set(level, x, FLOOR + 9, z, Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState());
		HubPark.box(level, x - 1, FLOOR + 10, z, x + 1, FLOOR + 10, z, Blocks.SEA_LANTERN.defaultBlockState());
		HubPark.box(level, x, FLOOR + 10, z - 1, x, FLOOR + 10, z + 1, Blocks.SEA_LANTERN.defaultBlockState());
	}

	// ----- Welcome signs -----

	private static final String[][] SIGNS = {
		{"Fun Park", "46.5", "5.5", "Ruby Rocket coaster (Rita)", "Big Wheel (Wally)", "Carousel (Cora)", "Bumper Cars (Buzz)", "Duck Shoot (Dot)",
			"Bouncy Castle"},
		{"Splash Tower", "37.5", "-40.5", "Lulu takes you to the top", "Tube slide (blue pad)", "Zip line (gold pad)", "Diving board and pool"},
		{"Fishing Village", "6.5", "46.5", "Fishing (Captain Finn)", "Swan boats", "Swings and the splash pool", "The Big Catch Board"},
		{"Mini Golf", "-39.5", "57.5", "Four crazy holes (Gus)", "The Windmill, the Castle,", "the Volcano, Pirate Cove"},
		{"The Stage", "-36.5", "-35.5", "Pick a song (DJ Remy)", "The dance floor", "The photo spot"},
		{"Sky Parkour", "-21.5", "-36.5", "Jump across the sky (Pip)", "Gold blocks are checkpoints"},
		{"Spooky Hollow", "32.5", "44.5", "The Ghost Train (Mortimer)", "...if you dare!"},
		{"Flume Falls", "-4.5", "-60.5", "The Log Flume (Flo)", "The Splash Zone"},
		{"Speedway", "-90.5", "2.5", "Go-kart races (Rex)", "Tonight's records"},
		{"Thrill Zone", "83.5", "38.5", "Drop Tower (Tess)", "Pirate Ship (Captain Polly)", "Teacups (Mabel)", "Balloon Tours (Barnaby)",
			"Carnival games and prizes!"},
	};

	private static void signs(ServerLevel level) {
		for (String[] s : SIGNS) {
			double x = Double.parseDouble(s[1]);
			double z = Double.parseDouble(s[2]);
			MutableComponent text = Component.literal("Welcome to " + s[0] + "!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
			for (int i = 3; i < s.length; i++) {
				text.append(Component.literal("\n• " + s[i]).withStyle(ChatFormatting.WHITE));
			}
			text.append(Component.literal("\nRight-click the people to play!").withStyle(ChatFormatting.GRAY));
			double y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
			HubPark.keep(Props.text(level, new Vec3(x + 1.6, Math.max(FLOOR + 1, Math.min(y, FLOOR + 6)) + 2.3, z + 1.6), text, 0.5F, HubPark.TAG));
		}
	}

	// ----- Dressing -----

	private static void speedway(ServerLevel level) {
		int cx = Speedway.CX;
		int cz = Speedway.CZ;
		// The grandstand: colourful seat rows under a striped roof.
		for (int row = 0; row < 3; row++) {
			for (int x = cx - 3; x <= cx + 3; x++) {
				HubPark.set(level, x, FLOOR + 1 + row, cz - 4 + row, Blocks.WOOL.pick(row == 0 ? DyeColor.RED : row == 1 ? DyeColor.WHITE : DyeColor.BLUE).defaultBlockState());
			}
		}
		for (int x : new int[] {cx - 4, cx + 4}) {
			HubPark.box(level, x, FLOOR + 1, cz - 5, x, FLOOR + 6, cz - 5, Blocks.IRON_BARS.defaultBlockState());
		}
		for (int x = cx - 4; x <= cx + 4; x++) {
			for (int z = cz - 5; z <= cz - 1; z++) {
				HubPark.set(level, x, FLOOR + 6, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
		}
		// Flags round the outside of the track, and floodlights.
		int flags = 0;
		for (int i = 0; i < 28 && flags < 14; i++) {
			double a = i * Math.PI * 2 / 28;
			int x = cx + (int) Math.round(Math.cos(a) * 20.5);
			int z = cz + (int) Math.round(Math.sin(a) * 20.5);
			if (!level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.GRASS_BLOCK) || !level.getBlockState(new BlockPos(x, FLOOR + 1, z)).isAir()
					&& !level.getBlockState(new BlockPos(x, FLOOR + 1, z)).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
				continue;
			}
			HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 4, z, Blocks.IRON_BARS.defaultBlockState());
			for (int k = 0; k < 4; k++) {
				HubPark.piece(level, new Vec3(x + 0.5 + 0.2 + (k % 2) * 0.35, FLOOR + 4.55 + (k / 2) * 0.3, z + 0.5),
						Blocks.WOOL.pick(((k + k / 2) % 2 == 0) ? DyeColor.BLACK : DyeColor.WHITE).defaultBlockState(), new Vector3f(0.35F, 0.3F, 0.04F),
						new Quaternionf());
			}
			flags++;
		}
		for (int[] f : new int[][] {{cx - 15, cz - 15}, {cx + 15, cz - 15}, {cx - 15, cz + 15}, {cx + 15, cz + 15}}) {
			if (level.getBlockState(new BlockPos(f[0], FLOOR, f[1])).is(Blocks.GRASS_BLOCK)) {
				floodlight(level, f[0], f[1]);
			}
		}
	}

	private static void bench(ServerLevel level, int x, int z, Direction facing) {
		Direction side = facing.getClockWise();
		for (int k = 0; k <= 1; k++) {
			HubPark.set(level, x + side.getStepX() * k, FLOOR + 1, z + side.getStepZ() * k,
					Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing.getOpposite()));
		}
	}

	private static void flowerBed(ServerLevel level, int x, int z) {
		BlockState[] flowers = {Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(),
			Blocks.ALLIUM.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(), Blocks.PINK_TULIP.defaultBlockState()};
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				boolean rim = dx != 0 || dz != 0;
				HubPark.set(level, x + dx, FLOOR, z + dz, Blocks.GRASS_BLOCK.defaultBlockState());
				HubPark.set(level, x + dx, FLOOR + 1, z + dz, flowers[Math.floorMod(x * 3 + z * 5 + dx * 7 + dz * 11, flowers.length)]);
			}
		}
		HubPark.set(level, x, FLOOR + 1, z, Blocks.FLOWERING_AZALEA.defaultBlockState());
	}

	private static void funPark(ServerLevel level) {
		// String lights over the midway, from post to post.
		Vec3[] north = {new Vec3(50.5, FLOOR + 4.8, -0.5), new Vec3(56.5, FLOOR + 4.8, -0.5), new Vec3(62.5, FLOOR + 4.8, -0.5), new Vec3(66.5, FLOOR + 4.8, -0.5)};
		Vec3[] south = {new Vec3(50.5, FLOOR + 4.8, 5.5), new Vec3(56.5, FLOOR + 4.8, 5.5), new Vec3(62.5, FLOOR + 4.8, 5.5), new Vec3(66.5, FLOOR + 4.8, 5.5)};
		for (int i = 0; i < north.length; i++) {
			lights(level, north[i], south[i], PARTY, i);
		}
		bench(level, 58, 6, Direction.NORTH);
		bench(level, 52, -2, Direction.SOUTH);
		flowerBed(level, 60, -14);
		flowerBed(level, 59, 16);
	}

	private static void thrillZone(ServerLevel level) {
		int cx = Thrill.CX;
		int cz = Thrill.CZ;
		Vec3 a = pole(level, cx - 5, cz - 2, 4);
		Vec3 b = pole(level, cx + 6, cz - 3, 4);
		Vec3 c = pole(level, cx + 6, cz + 4, 4);
		Vec3 d = pole(level, cx - 5, cz + 4, 4);
		lights(level, a, b, PARTY, 0);
		lights(level, b, c, PARTY, 1);
		lights(level, c, d, PARTY, 2);
		lights(level, d, a, PARTY, 3);
		Vec3 e = pole(level, cx + 2, cz + 10, 4);
		Vec3 f = pole(level, cx + 13, cz + 10, 4);
		lights(level, e, f, WARM, 0);
		FunPark.stand(level, 93, 45, DyeColor.ORANGE, "HOT DOGS", Blocks.WOOL.pick(DyeColor.BROWN).defaultBlockState());
		flowerBed(level, cx + 12, cz - 10);
		flowerBed(level, cx + 15, cz + 8);
		bench(level, cx - 3, cz + 3, Direction.EAST);
		bench(level, cx + 4, cz - 3, Direction.SOUTH);
	}

	private static void golf(ServerLevel level) {
		for (int[] l : new int[][] {{-58, 46}, {-30, 47}, {-46, 30}, {-52, 57}}) {
			if (level.getBlockState(new BlockPos(l[0], FLOOR, l[1])).is(Blocks.GRASS_BLOCK) && level.getBlockState(new BlockPos(l[0], FLOOR + 1, l[1])).isAir()) {
				HubPark.lamp(level, l[0], FLOOR + 1, l[1]);
			}
		}
	}

	private static void spooky(ServerLevel level) {
		// Soul lanterns on the fence posts round the edge, for a ghostly glow at night.
		int n = 0;
		for (int i = 0; i < 24; i++) {
			double a = i * Math.PI * 2 / 24;
			int x = Spooky.CX + (int) Math.round(Math.cos(a) * 12.6);
			int z = Spooky.CZ + (int) Math.round(Math.sin(a) * 12.6);
			BlockPos bars = new BlockPos(x, FLOOR + 1, z);
			if (level.getBlockState(bars).is(Blocks.IRON_BARS) && level.getBlockState(bars.above()).isAir() && n++ % 2 == 0) {
				level.setBlock(bars.above(), Blocks.SOUL_LANTERN.defaultBlockState(), 2);
			}
		}
	}
}
