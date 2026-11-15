package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Spooky Hollow's Haunted Mansion: a big two-storey house you ride through in a two-seat doom buggy that turns to face
 * each scene. The portrait hall (faces burst out of the walls), the ballroom (ghosts waltzing round under a swaying
 * chandelier), up a creaky ramp past bats to the library (books flying in circles, a skeleton in the shelves), the
 * attic (a spider in the dark), then the floor gives way and you drop into the crypt (a skeleton sits up in its
 * coffin), and out through the graveyard as skeletons burst from their graves and lightning cracks.
 */
final class Spooky {
	private Spooky() {
	}

	static final int CX = 44;
	static final int CZ = 40;
	static final Vec3 LAND = new Vec3(28.5, FLOOR + 1, 44.5);
	/** x, z, height above the floor. */
	private static final double[][] TRACK = {{29.5, 40.5, 0}, {32, 40.5, 0}, {34.5, 40.5, 0}, {39.5, 40.5, 0}, {44.5, 40.5, 0}, {47.5, 40.5, 0},
		{49.5, 36.5, 0}, {52.5, 37.5, 0}, {53.5, 40.5, 0}, {53.5, 44, 1.5}, {52, 47, 3.5}, {48, 47.5, 5.5}, {46, 45, 7}, {46, 40.5, 7}, {46, 36, 7},
		{45.5, 34.5, 7}, {43, 34, 7}, {39, 33.5, 7}, {36, 35, 7}, {35.5, 38.5, 7}, {36, 41, 7}, {37, 43.5, 7}, {40, 45, 7}, {41, 47, 7},
		{38.5, 47, 7}, {37.5, 45.6, 7}, {37.5, 45.5, 0}, {39, 44, 0}, {41, 46, 0}, {41, 48, 0}, {41, 51, 0}, {37, 52, 0}, {33.5, 49.5, 0},
		{30.5, 45, 0}, {29.5, 41.5, 0}};

	private static TrackRide ride;
	private static List<Vec3> path = List.of();

	/** A scary thing: hidden at {@code hide}, it jumps to {@code show} when a car passes, and goes back after a while. */
	private record Prop(UUID id, Vec3 hide, Vec3 show) {
	}

	private static final List<Prop> PROPS = new ArrayList<>();
	private static final List<int[]> RETURNS = new ArrayList<>();
	/** Things that move all the time: the waltzing ghosts and the flying books. */
	private static final List<UUID> DANCERS = new ArrayList<>();
	private static final List<UUID> BOOKS = new ArrayList<>();
	private static final List<UUID> CHANDELIER = new ArrayList<>();
	private static final Vec3 BALLROOM = new Vec3(49.8, FLOOR + 1, 40.5);
	private static final Vec3 LIBRARY = new Vec3(39.0, FLOOR + 8, 36.5);
	private static int tick;

	static void build(ServerLevel level) {
		PROPS.clear();
		RETURNS.clear();
		DANCERS.clear();
		BOOKS.clear();
		CHANDELIER.clear();
		HubPark.island(level, CX, CZ, 16, FLOOR);
		HubPark.clear(level, CX - 16, FLOOR + 1, CZ - 16, CX + 16, FLOOR + 30, CZ + 16);
		for (int x = -17; x <= 17; x++) {
			for (int z = -17; z <= 17; z++) {
				BlockPos top = new BlockPos(CX + x, FLOOR, CZ + z);
				if (level.getBlockState(top).is(Blocks.GRASS_BLOCK)) {
					level.setBlock(top, ((x * 7 + z * 13) & 3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.PODZOL.defaultBlockState(), 2);
				}
				if (level.getBlockState(top.above()).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
					level.setBlock(top.above(), Blocks.IRON_BARS.defaultBlockState(), 2);
				}
			}
		}
		mansion(level);
		graveyard(level);
		// The station: a covered platform at the gates.
		HubPark.box(level, 28, FLOOR, 38, 30, FLOOR, 43, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		for (int z = 37; z <= 44; z++) {
			for (int x = 27; x <= 31; x++) {
				HubPark.set(level, x, FLOOR + 5, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.PURPLE : DyeColor.BLACK).defaultBlockState());
			}
		}
		for (int[] post : new int[][] {{27, 37}, {31, 37}, {27, 44}, {31, 44}}) {
			HubPark.box(level, post[0], FLOOR + 1, post[1], post[0], FLOOR + 4, post[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		HubPark.label(level, CX + 0.5, FLOOR + 27, CZ + 0.5, "THE HAUNTED MANSION", ChatFormatting.LIGHT_PURPLE, 2.6F);
		HubPark.travelPad(level, 46, FLOOR, 27, "spooky");
		// The ride.
		List<Vec3> pts = new ArrayList<>();
		for (double[] c : TRACK) {
			pts.add(new Vec3(c[0], FLOOR + 1 + c[2], c[1]));
		}
		path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		int dropTop = nearest3(37.5, FLOOR + 8, 45.6);
		int dropBottom = nearest3(37.5, FLOOR + 1, 45.5);
		for (int i = 0; i < path.size(); i++) {
			speed[i] = i > dropTop && i < dropBottom ? 0.7 : 0.13;
		}
		ride = new TrackRide("ghost", path, speed, "doom_buggy", 2.0F, new Vec3(30.5, FLOOR + 1, 44.5), "Mortimer");
		ride.sideBySide = true;
		ride.upright = true;
		// The ramp up through the ballroom: a creaky wooden deck under the track.
		for (int i = 0; i < path.size(); i++) {
			Vec3 p = path.get(i);
			if (p.y > FLOOR + 1.6 && p.x > 44.2) {
				int fy = (int) Math.floor(p.y) - 1;
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						HubPark.ifAir(level, (int) Math.floor(p.x) + dx, fy, (int) Math.floor(p.z) + dz, Blocks.DARK_OAK_PLANKS.defaultBlockState());
					}
				}
			}
		}
		BlockState rail = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
		int seg = (int) Math.round(2.0 / TrackRide.STEP);
		for (int i = 0; i + seg < path.size(); i += seg) {
			Vec3 a = path.get(i);
			Vec3 b = path.get(i + seg);
			if (Math.abs(a.y - b.y) > 2) {
				continue;
			}
			Vec3 dir = b.subtract(a).normalize();
			Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize();
			for (int s : new int[] {-1, 1}) {
				HubPark.beam(level, a.add(side.scale(0.5 * s)).add(0, 0.04, 0), b.add(side.scale(0.5 * s)).add(0, 0.04, 0), 0.1F, 0.08F, rail);
			}
		}
		scenes(level);
		// Soft light all along the ride, so you can see where you're going (and what's coming for you).
		for (int i = 0; i < path.size(); i += 16) {
			BlockPos lp = BlockPos.containing(path.get(i).add(0, 3.2, 0));
			if (level.getBlockState(lp).isAir()) {
				level.setBlock(lp, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 11), 2);
			}
		}
		HubHosts.add(level, new Vec3(30.5, FLOOR + 1, 37.5), 270, VillagerProfession.LEATHERWORKER, VillagerType.SWAMP,
				new HubHosts.Host("ghost", "Mortimer", "Haunted Mansion", 0xB070FF, "minecraft:carved_pumpkin",
						"Ride a doom buggy through the Haunted Mansion: the portrait hall, the ghosts' ballroom, the library, the attic... "
								+ "and what's beneath. Two to a buggy. About two minutes. Only a little bit scary!",
						"Haunted Mansion: right-click Mortimer... if you dare!", HubHosts.rideButtons("Enter the mansion..."),
						(p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	private static int nearest3(double x, double y, double z) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = path.get(i).distanceToSqr(x, y, z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	// ----- The house -----

	private static void mansion(ServerLevel level) {
		BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		BlockState brick = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		HubPark.box(level, 33, FLOOR, 31, 55, FLOOR, 49, plank);
		// Outer walls: brick below, dark wood above, posts every few blocks, tall windows.
		for (int x = 33; x <= 55; x++) {
			for (int z = 31; z <= 49; z++) {
				boolean edge = x == 33 || x == 55 || z == 31 || z == 49;
				if (!edge) {
					continue;
				}
				boolean post = (x == 33 || x == 55) && (z - 31) % 6 == 0 || (z == 31 || z == 49) && (x - 33) % 5 == 0 || (x == 33 || x == 55) && (z == 31 || z == 49);
				for (int y = FLOOR + 1; y <= FLOOR + 13; y++) {
					int h = y - FLOOR;
					boolean window = !post && (h == 10 || h == 11 || h == 4) && ((x + z) % 3 == 0);
					BlockState s = post ? log : h <= 3 ? brick : h == 7 ? log : plank;
					if (window) {
						s = Blocks.STAINED_GLASS.pick(h == 4 ? DyeColor.ORANGE : DyeColor.PURPLE).defaultBlockState();
					}
					HubPark.set(level, x, y, z, s);
				}
			}
		}
		// Doors: the front (west, onto the station), and the back (south, into the graveyard).
		HubPark.box(level, 33, FLOOR + 1, 39, 33, FLOOR + 3, 41, air);
		HubPark.box(level, 40, FLOOR + 1, 49, 42, FLOOR + 3, 49, air);
		// Floors and ceilings: the upper floor over the west half; the ballroom (east) goes all the way up.
		HubPark.box(level, 34, FLOOR + 7, 32, 43, FLOOR + 7, 48, plank);
		HubPark.box(level, 34, FLOOR + 14, 32, 54, FLOOR + 14, 48, plank);
		// Ground floor walls: the portrait hall (z 39-41) between a closed room and the crypt.
		HubPark.box(level, 34, FLOOR + 1, 38, 44, FLOOR + 6, 38, brick);
		HubPark.box(level, 34, FLOOR + 1, 42, 44, FLOOR + 6, 42, brick);
		HubPark.box(level, 44, FLOOR + 1, 32, 44, FLOOR + 13, 48, plank);
		HubPark.box(level, 44, FLOOR + 1, 39, 44, FLOOR + 3, 41, air);
		HubPark.box(level, 44, FLOOR + 8, 33, 44, FLOOR + 10, 35, air);
		// Upstairs: the library (north) and the attic (south), a doorway between; a hole in the attic floor.
		HubPark.box(level, 34, FLOOR + 8, 41, 43, FLOOR + 13, 41, plank);
		HubPark.box(level, 35, FLOOR + 8, 41, 37, FLOOR + 10, 41, air);
		HubPark.box(level, 36, FLOOR + 7, 44, 39, FLOOR + 7, 47, air);
		// Portraits in gold frames down the hall.
		for (int x = 35; x <= 43; x += 3) {
			for (int z : new int[] {38, 42}) {
				HubPark.set(level, x, FLOOR + 2, z, Blocks.GOLD_BLOCK.defaultBlockState());
				HubPark.set(level, x, FLOOR + 3, z, Blocks.GOLD_BLOCK.defaultBlockState());
				HubPark.piece(level, new Vec3(x + 0.5, FLOOR + 3.0, z == 38 ? 38.96 : 42.04), Blocks.CONCRETE.pick(x % 2 == 0 ? DyeColor.BROWN : DyeColor.GREEN)
						.defaultBlockState(), new org.joml.Vector3f(0.7F, 1.6F, 0.04F), new org.joml.Quaternionf());
			}
		}
		// The library: bookshelves all round.
		for (int x = 34; x <= 43; x++) {
			for (int y = FLOOR + 8; y <= FLOOR + 12; y++) {
				HubPark.set(level, x, y, 32, Blocks.BOOKSHELF.defaultBlockState());
			}
		}
		for (int z = 33; z <= 40; z++) {
			for (int y = FLOOR + 8; y <= FLOOR + 12; y++) {
				HubPark.set(level, 34, y, z, Blocks.BOOKSHELF.defaultBlockState());
			}
		}
		// The attic: cobwebs and old trunks. The crypt: coffins and candles.
		for (int[] c : new int[][] {{34, 48}, {43, 42}, {43, 48}, {34, 42}}) {
			HubPark.set(level, c[0], FLOOR + 12, c[1], Blocks.COBWEB.defaultBlockState());
			HubPark.set(level, c[0], FLOOR + 8, c[1], Blocks.BARREL.defaultBlockState());
		}
		for (int[] c : new int[][] {{35, 47}, {35, 43}, {43, 44}}) {
			HubPark.set(level, c[0], FLOOR + 1, c[1], Blocks.DARK_OAK_PLANKS.defaultBlockState());
			HubPark.set(level, c[0], FLOOR + 2, c[1], Blocks.CANDLE.defaultBlockState());
		}
		// Ballroom: a checkered floor, a pipe organ against the back wall.
		for (int x = 45; x <= 54; x++) {
			for (int z = 32; z <= 48; z++) {
				HubPark.set(level, x, FLOOR, z, ((x + z) & 1) == 0 ? Blocks.POLISHED_BLACKSTONE.defaultBlockState() : Blocks.SMOOTH_QUARTZ.defaultBlockState());
			}
		}
		for (int x = 47; x <= 52; x++) {
			HubPark.box(level, x, FLOOR + 1, 32, x, FLOOR + 3 + Math.abs(x - 49) % 3 + (x % 2) * 2, 32, Blocks.GOLD_BLOCK.defaultBlockState());
		}
		// Soul lanterns here and there: just enough light to see by.
		for (int[] l : new int[][] {{36, 39}, {42, 41}, {45, 33}, {54, 47}, {54, 33}, {42, 40}, {35, 46}}) {
			HubPark.set(level, l[0], FLOOR + 1, l[1], Blocks.SOUL_LANTERN.defaultBlockState());
		}
		// The roof: a steep gable, a corner tower with a spire, a chimney.
		for (int i = 0; i < 9; i++) {
			HubPark.box(level, 32, FLOOR + 14 + i, 30 + i, 56, FLOOR + 14 + i, 50 - i, Blocks.DEEPSLATE_TILES.defaultBlockState());
		}
		HubPark.box(level, 32, FLOOR + 1, 30, 35, FLOOR + 22, 33, brick);
		for (int i = 0; i < 4; i++) {
			HubPark.box(level, 32 + i / 2, FLOOR + 23 + i, 30 + i / 2, 35 - i / 2, FLOOR + 23 + i, 33 - i / 2, Blocks.DEEPSLATE_TILES.defaultBlockState());
		}
		HubPark.set(level, 33, FLOOR + 27, 31, Blocks.END_ROD.defaultBlockState());
		HubPark.set(level, 33, FLOOR + 18, 30, Blocks.STAINED_GLASS.pick(DyeColor.ORANGE).defaultBlockState());
		HubPark.box(level, 52, FLOOR + 14, 45, 53, FLOOR + 24, 46, brick);
		// Just enough (hidden) light inside to see the ghosts by.
		for (int[] l : new int[][] {{36, 2, 40}, {40, 2, 40}, {43, 2, 40}, {48, 3, 36}, {52, 3, 40}, {48, 3, 45}, {50, 9, 40}, {47, 10, 36}, {38, 9, 35},
			{41, 9, 37}, {36, 9, 38}, {39, 9, 44}, {41, 9, 47}, {38, 2, 44}, {40, 2, 47}}) {
			BlockPos lp = new BlockPos(l[0], FLOOR + l[1], l[2]);
			if (level.getBlockState(lp).isAir()) {
				level.setBlock(lp, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 12), 2);
			}
		}
		// The porch.
		for (int z : new int[] {37, 43}) {
			HubPark.box(level, 31, FLOOR + 1, z, 31, FLOOR + 5, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		HubPark.set(level, 32, FLOOR + 1, 38, Blocks.JACK_O_LANTERN.defaultBlockState());
		HubPark.set(level, 32, FLOOR + 1, 42, Blocks.JACK_O_LANTERN.defaultBlockState());
	}

	private static void graveyard(ServerLevel level) {
		for (int[] g : new int[][] {{37, 53}, {45, 53}, {48, 51}, {35, 51}, {44, 55}}) {
			HubPark.set(level, g[0], FLOOR + 1, g[1], Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState());
		}
		for (int[] t : new int[][] {{55, 32}, {30, 33}, {54, 50}}) {
			HubPark.box(level, t[0], FLOOR + 1, t[1], t[0], FLOOR + 6, t[1], Blocks.DARK_OAK_LOG.defaultBlockState());
			HubPark.set(level, t[0] + 1, FLOOR + 5, t[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			HubPark.set(level, t[0] - 1, FLOOR + 6, t[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		HubPark.set(level, 41, FLOOR + 1, 54, Blocks.JACK_O_LANTERN.defaultBlockState());
	}

	// ----- The scenes -----

	private static void scenes(ServerLevel level) {
		// 1. At the door: lightning, and a welcome.
		ride.events.put(nearest3(32, FLOOR + 1, 40.5), (lvl, car) -> {
			bolt(lvl, new Vec3(30.5, FLOOR + 1, 34.5));
			ride.tell(lvl, car, "Welcome... we've been expecting you...");
			ride.face(lvl, car, new Vec3(34, FLOOR + 3, 40.5), 20);
		});
		// 2-3. Portrait hall: faces burst out of the walls.
		scare(level, 37, FLOOR + 1, 40.5, "ghost", new Vec3(37.5, FLOOR + 2.4, 35.5), new Vec3(37.5, FLOOR + 2.2, 39.3), 1.1F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(37.5, FLOOR + 2, 39), ModSounds.PARK_GHOST, SoundSource.AMBIENT, 1.4F, 1.1F);
		});
		scare(level, 41.5, FLOOR + 1, 40.5, "ghost", new Vec3(41.5, FLOOR - 2.2, 41.4), new Vec3(41.5, FLOOR + 2.0, 41.4), 1.1F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(41.5, FLOOR + 2, 41.4), ModSounds.PARK_BOO, SoundSource.AMBIENT, 1.2F, 1.0F);
		});
		// 4. The ballroom: ghosts waltzing round under the chandelier.
		ride.events.put(nearest3(46.5, FLOOR + 1, 40.5), (lvl, car) -> {
			ride.face(lvl, car, BALLROOM.add(0, 1.5, 0), 70);
			ride.tell(lvl, car, "The ghosts are having a party...");
			for (int i = 0; i < 6; i++) {
				lvl.playSound(null, BlockPos.containing(BALLROOM), SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.AMBIENT, 0.8F, 0.6F + i * 0.1F);
			}
		});
		for (int i = 0; i < 6; i++) {
			Display.ItemDisplay g = HubPark.model(level, BALLROOM.add(2.5, 1, 0), 0, "ghost", 1.3F, 0);
			if (g != null) {
				((DisplayAccessor) g).rubymod$setBrightness(new net.minecraft.util.Brightness(13, 13));
				((DisplayAccessor) g).rubymod$setPosRotInterpolationDuration(3);
				DANCERS.add(g.getUUID());
			}
		}
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			Display.BlockDisplay c = HubPark.piece(level, BALLROOM.add(Math.cos(a) * 1.4, 10, Math.sin(a) * 1.4),
					(i % 2 == 0 ? Blocks.GOLD_BLOCK : Blocks.SHROOMLIGHT).defaultBlockState(), new org.joml.Vector3f(0.4F, 0.3F, 0.4F), new org.joml.Quaternionf());
			if (c != null) {
				((DisplayAccessor) c).rubymod$setPosRotInterpolationDuration(4);
				CHANDELIER.add(c.getUUID());
			}
		}
		// 5. The ramp: bats.
		scare(level, 52, FLOOR + 4.5, 47, "bat", new Vec3(48, FLOOR + 13.6, 44), new Vec3(50.5, FLOOR + 6, 46.5), 0.9F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(50, FLOOR + 6, 46), SoundEvents.BAT_TAKEOFF, SoundSource.AMBIENT, 1.4F, 1.0F);
		});
		// 6. The balcony: look down at the dancers.
		ride.events.put(nearest3(46, FLOOR + 8, 43), (lvl, car) -> ride.face(lvl, car, BALLROOM.add(0, 1, 0), 24));
		// 7. The library: books flying in circles.
		ride.events.put(nearest3(43, FLOOR + 8, 34), (lvl, car) -> {
			ride.face(lvl, car, LIBRARY.add(0, 1, 0), 40);
			ride.tell(lvl, car, "Shhh... this is a library!");
			lvl.playSound(null, BlockPos.containing(LIBRARY), SoundEvents.BOOK_PAGE_TURN, SoundSource.AMBIENT, 1.5F, 0.8F);
		});
		for (int i = 0; i < 10; i++) {
			Display.ItemDisplay b = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (b != null) {
				b.snapTo(LIBRARY.x, LIBRARY.y + 1, LIBRARY.z, 0, 0);
				((dev.rubymod.mixin.ItemDisplayAccessor) b).rubymod$setItemStack(new ItemStack(i % 3 == 0 ? Items.ENCHANTED_BOOK : Items.BOOK));
				((DisplayAccessor) b).rubymod$setPosRotInterpolationDuration(3);
				((DisplayAccessor) b).rubymod$setBrightness(new net.minecraft.util.Brightness(12, 12));
				HubPark.keep(b);
				level.addFreshEntity(b);
				BOOKS.add(b.getUUID());
			}
		}
		// 8. A skeleton bursts out of the shelves.
		scare(level, 36, FLOOR + 8, 35.5, "skeleton", new Vec3(35.5, FLOOR + 3.5, 36.5), new Vec3(35.5, FLOOR + 8, 37.3), 1.5F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(35.5, FLOOR + 9, 37), ModSounds.PARK_BOO, SoundSource.AMBIENT, 1.4F, 1.0F);
			lvl.playSound(null, BlockPos.containing(35.5, FLOOR + 9, 37), SoundEvents.SKELETON_AMBIENT, SoundSource.AMBIENT, 1.2F, 0.7F);
		});
		// 9. The attic: the lights go out and a spider drops.
		scare(level, 38.5, FLOOR + 8, 44, "spider", new Vec3(39.5, FLOOR + 15.6, 45.5), new Vec3(39.5, FLOOR + 9.2, 45.5), 1.3F, (lvl, car) -> {
			for (ServerPlayer p : ride.riders(lvl, car)) {
				p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, false, false));
			}
			lvl.playSound(null, BlockPos.containing(39.5, FLOOR + 9, 45.5), SoundEvents.SPIDER_AMBIENT, SoundSource.AMBIENT, 1.5F, 0.6F);
		});
		// 10. The floor gives way!
		ride.events.put(nearest3(37.5, FLOOR + 8, 45.6), (lvl, car) -> {
			for (ServerPlayer p : ride.riders(lvl, car)) {
				p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 25, 0, false, false));
			}
			ride.tell(lvl, car, "The floor's giving way... AAAH!");
			lvl.playSound(null, BlockPos.containing(37.5, FLOOR + 6, 45.5), SoundEvents.WOOD_BREAK, SoundSource.AMBIENT, 1.5F, 0.6F);
			lvl.playSound(null, BlockPos.containing(37.5, FLOOR + 6, 45.5), ModSounds.PARK_BOO, SoundSource.AMBIENT, 1.2F, 0.7F);
		});
		// 11. The crypt: a skeleton sits up in its coffin.
		scare(level, 39.5, FLOOR + 1, 44.5, "skeleton", new Vec3(41.5, FLOOR - 2.5, 46.0), new Vec3(41.5, FLOOR + 0.6, 46.0), 1.4F, (lvl, car) -> {
			ride.face(lvl, car, new Vec3(41.5, FLOOR + 2, 46.0), 10);
			lvl.playSound(null, BlockPos.containing(41.5, FLOOR + 1, 46), SoundEvents.SKELETON_AMBIENT, SoundSource.AMBIENT, 1.4F, 0.6F);
		});
		// 12. The graveyard: skeletons burst from their graves; lightning.
		BiConsumer<ServerLevel, TrackRide.Car> graves = (lvl, car) -> {
			bolt(lvl, new Vec3(46.5, FLOOR + 1, 54.5));
			ride.tell(lvl, car, "You escaped the Haunted Mansion! ...or did you?");
		};
		ride.events.put(nearest3(39, FLOOR + 1, 51.5), graves);
		scare(level, 40, FLOOR + 1, 51, "skeleton", new Vec3(37.5, FLOOR - 2.5, 53.5), new Vec3(37.5, FLOOR + 1, 53.5), 1.4F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(37.5, FLOOR + 1, 53.5), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.AMBIENT, 1.4F, 0.6F);
		});
		scare(level, 36, FLOOR + 1, 52, "skeleton", new Vec3(34.5, FLOOR - 2.5, 51.0), new Vec3(34.5, FLOOR + 1, 51.0), 1.4F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(34.5, FLOOR + 1, 51), SoundEvents.SKELETON_AMBIENT, SoundSource.AMBIENT, 1.4F, 0.8F);
		});
	}

	private static void bolt(ServerLevel level, Vec3 at) {
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
		if (bolt != null) {
			bolt.snapTo(at.x, at.y, at.z, 0, 0);
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
	}

	private static void scare(ServerLevel level, double x, double y, double z, String model, Vec3 hide, Vec3 show0, float size,
			BiConsumer<ServerLevel, TrackRide.Car> also) {
		Vec3 show = clear(show0, 1.9 + size / 2);
		// ...but not into a wall: back towards where it was until it's in the room.
		for (int k = 0; k < 20 && !level.getBlockState(BlockPos.containing(show.x, show.y + size / 2, show.z)).isAir()
				&& !level.getBlockState(BlockPos.containing(show.x, show.y + size / 2, show.z)).is(Blocks.LIGHT); k++) {
			show = show.lerp(show0, 0.15);
		}
		Display.ItemDisplay d = HubPark.model(level, hide, 90, model, size, 0);
		if (d == null) {
			return;
		}
		((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(12, 12));
		Prop prop = new Prop(d.getUUID(), hide, show);
		PROPS.add(prop);
		int index = PROPS.size() - 1;
		ride.events.put(nearest3(x, y, z), (lvl, car) -> {
			Entity e = lvl.getEntity(prop.id());
			if (e != null) {
				((DisplayAccessor) e).rubymod$setPosRotInterpolationDuration(3);
				e.teleportTo(prop.show().x, prop.show().y, prop.show().z);
				Vec3 look = car.pos.subtract(prop.show());
				e.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)) + 180);
				RETURNS.add(new int[] {index, tick + 50});
				ride.face(lvl, car, prop.show().add(0, 0.8, 0), 14);
			}
			lvl.sendParticles(ParticleTypes.SOUL, prop.show().x, prop.show().y + 1, prop.show().z, 10, 0.3, 0.5, 0.3, 0.02);
			also.accept(lvl, car);
		});
	}

	/** Where a scare jumps to, moved out (if need be) so it's well clear of the riders' heads. */
	private static Vec3 clear(Vec3 show, double gap) {
		Vec3 near = null;
		double best = Double.MAX_VALUE;
		for (Vec3 p : path) {
			if (Math.abs(p.y - show.y) > 3.5) {
				continue;
			}
			double d = Math.hypot(p.x - show.x, p.z - show.z);
			if (d < best) {
				best = d;
				near = p;
			}
		}
		if (near == null || best >= gap) {
			return show;
		}
		Vec3 out = new Vec3(show.x - near.x, 0, show.z - near.z);
		if (out.lengthSqr() < 1e-4) {
			out = new Vec3(1, 0, 0);
		}
		out = out.normalize().scale(gap);
		return new Vec3(near.x + out.x, show.y, near.z + out.z);
	}

	static void tick(ServerLevel level) {
		tick++;
		if (ride == null) {
			return;
		}
		ride.tick(level);
		for (var it = RETURNS.iterator(); it.hasNext();) {
			int[] r = it.next();
			if (tick >= r[1]) {
				it.remove();
				Prop prop = PROPS.get(r[0]);
				Entity e = level.getEntity(prop.id());
				if (e != null) {
					((DisplayAccessor) e).rubymod$setPosRotInterpolationDuration(8);
					e.teleportTo(prop.hide().x, prop.hide().y, prop.hide().z);
				}
			}
		}
		// The waltz, the flying books and the swaying chandelier.
		if (tick % 3 == 0) {
			for (int i = 0; i < DANCERS.size(); i++) {
				Entity g = level.getEntity(DANCERS.get(i));
				if (g != null) {
					double a = tick * 0.03 + i * Math.PI / 3;
					double r = 2.6 + 0.6 * Math.sin(tick * 0.05 + i);
					g.teleportTo(BALLROOM.x + Math.cos(a) * r, BALLROOM.y + 1.2 + 0.4 * Math.sin(tick * 0.08 + i), BALLROOM.z + Math.sin(a) * r);
					g.setYRot((float) Math.toDegrees(a) + tick * 4);
				}
			}
			for (int i = 0; i < BOOKS.size(); i++) {
				Entity b = level.getEntity(BOOKS.get(i));
				if (b != null) {
					double a = -tick * 0.05 + i * Math.PI / 5;
					double r = 1.6 + (i % 3) * 0.6;
					b.teleportTo(LIBRARY.x + Math.cos(a) * r, LIBRARY.y + 1.2 + (i % 4) * 0.6 + 0.3 * Math.sin(tick * 0.1 + i), LIBRARY.z + Math.sin(a) * r);
					b.setYRot((float) Math.toDegrees(a));
				}
			}
			double sway = Math.sin(tick * 0.04) * 0.4;
			for (int i = 0; i < CHANDELIER.size(); i++) {
				Entity c = level.getEntity(CHANDELIER.get(i));
				if (c != null) {
					double a = i * Math.PI / 4 + tick * 0.01;
					c.teleportTo(BALLROOM.x + sway + Math.cos(a) * 1.4, BALLROOM.y + 10, BALLROOM.z + Math.sin(a) * 1.4);
				}
			}
		}
		// Flickering lights in the hall.
		if (tick % 37 == 0) {
			BlockPos l = new BlockPos(42, FLOOR + 1, 40);
			level.setBlock(l, level.getBlockState(l).isAir() ? Blocks.SOUL_LANTERN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		}
		if (tick % 8 == 0) {
			var random = level.getRandom();
			level.sendParticles(ParticleTypes.WHITE_ASH, CX + (random.nextDouble() - 0.5) * 30, FLOOR + 1.5, CZ + (random.nextDouble() - 0.5) * 30, 6, 1.5,
					0.3, 1.5, 0.01);
		}
	}

	static void reset() {
		if (ride != null) {
			ride.reset();
		}
		PROPS.clear();
		RETURNS.clear();
		DANCERS.clear();
		BOOKS.clear();
		CHANDELIER.clear();
	}
}
