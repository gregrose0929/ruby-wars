package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Fun Park: a carnival island. An entrance arch and a midway strung with bunting; a Ferris wheel, a carousel,
 * bumper cars, a duck-shooting booth with prizes, a bouncy castle and snack stands. Every ride and game has a host.
 */
final class FunPark {
	private FunPark() {
	}

	static final int FX = 56;
	static final int FZ = 2;
	/** The big Fun Park: the old Fun Park, the old Thrill Zone, and the land between and beyond (x, z, radius). */
	static final int[][] CIRCLES = {{56, 2, 20}, {78, 16, 22}, {84, 40, 24}, {106, 4, 24}, {110, 26, 16}, {84, 0, 14}, {50, -20, 9}, {98, -21, 9}, {72, -19, 7}, {50, 75, 14}};
	/** Roughly the middle, and how far it reaches (for "is anyone near"). */
	static final int MX = 84;
	static final int MZ = 16;

	// ----- Building -----

	static void build(ServerLevel level) {
		// The old coaster station by the plaza goes; the Fun Park and the Thrill Zone become one big island.
		HubPark.clear(level, 33, FLOOR, -5, 48, FLOOR + 7, 5);
		HubPark.blob(level, CIRCLES, FLOOR);
		// The midway: a sandy path from the arch to the bouncy castle.
		HubPark.box(level, 46, FLOOR, 1, 66, FLOOR, 3, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
		for (int x = 47; x <= 66; x += 3) {
			HubPark.set(level, x, FLOOR, 2, Blocks.CUT_SANDSTONE.defaultBlockState());
		}
		arch(level);
		gameHosts(level);
		bunting(level);
		galleryBooth(level);
		wheel(level);
		carousel(level);
		HubPark.at(-19, 10, () -> castle(level));
		stand(level, 55, -3, DyeColor.RED, "POPCORN", Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState());
		stand(level, 56, 5, DyeColor.PINK, "CANDY FLOSS", Blocks.WOOL.pick(DyeColor.PINK).defaultBlockState());
		HubPark.travelPad(level, 42, FLOOR, 6, "funpark");
		for (int[] l : new int[][] {{40, 8}, {72, -6}, {72, 9}, {48, -13}}) {
			HubPark.lamp(level, l[0], FLOOR + 1, l[1]);
		}
	}

	private static void arch(ServerLevel level) {
		for (int z : new int[] {-1, 5}) {
			for (int y = FLOOR + 1; y <= FLOOR + 6; y++) {
				HubPark.set(level, 48, y, z, Blocks.CONCRETE.pick(y % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
			HubPark.set(level, 48, FLOOR + 7, z, Blocks.GOLD_BLOCK.defaultBlockState());
		}
		for (int z = -1; z <= 5; z++) {
			HubPark.set(level, 48, FLOOR + 6, z, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
			HubPark.set(level, 49, FLOOR + 6, z, z % 2 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.ORANGE).defaultBlockState());
			HubPark.set(level, 47, FLOOR + 6, z, z % 2 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.ORANGE).defaultBlockState());
		}
		HubPark.label(level, 48.5, FLOOR + 8.4, 2.5, "★ FUN PARK ★", ChatFormatting.GOLD, 2.6F);
	}

	/** By the gate: hosts for the two party games that aren't battles (they take you straight there). */
	private static void gameHosts(ServerLevel level) {
		HubHosts.add(level, new Vec3(52.5, FLOOR + 1, -0.5), 0, VillagerProfession.MASON, VillagerType.PLAINS,
				new HubHosts.Host("workshop", "Bolt", "Coaster Workshop", 0xFFB040, "minecraft:rail",
						"Ruby Coaster Park needs more rides! Everyone gets their own spot to build a roller coaster, piece by piece. Then ride them all!",
						"Coaster Workshop: right-click Bolt to build a coaster!", HubHosts.play("Build a coaster!"), (p, key) -> goPlay(p, "coasters")));
		HubHosts.add(level, new Vec3(52.5, FLOOR + 1, 4.5), 180, VillagerProfession.LIBRARIAN, VillagerType.SAVANNA,
				new HubHosts.Host("partyboard", "Dizzy", "Party Board", 0x60D0FF, "minecraft:slime_ball",
						"A board game for everyone: roll the dice, collect coins and stars, and play a quick minigame after every round!",
						"Party Board: right-click Dizzy to play!", HubHosts.play("Play Party Board!"), (p, key) -> goPlay(p, "board")));
	}

	private static void goPlay(ServerPlayer p, String game) {
		String error = dev.rubymod.game.Hub.go(p, "game:" + game);
		if (error != null) {
			p.sendOverlayMessage(Component.literal(error).withStyle(ChatFormatting.RED));
		}
	}

	/** Strings of little flags along both sides of the midway. */
	private static void bunting(ServerLevel level) {
		DyeColor[] colors = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIGHT_BLUE, DyeColor.LIME, DyeColor.MAGENTA, DyeColor.ORANGE};
		for (int side : new int[] {-1, 5}) {
			int[] posts = {50, 56, 62, 66};
			for (int x : posts) {
				HubPark.box(level, x, FLOOR + 1, side, x, FLOOR + 4, side, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
				HubPark.set(level, x, FLOOR + 5, side, Blocks.LANTERN.defaultBlockState());
			}
			for (int k = 0; k + 1 < posts.length; k++) {
				Vec3 a = new Vec3(posts[k] + 0.5, FLOOR + 4.8, side + 0.5);
				Vec3 b = new Vec3(posts[k + 1] + 0.5, FLOOR + 4.8, side + 0.5);
				int n = (int) (b.x - a.x);
				for (int i = 1; i < n; i++) {
					double t = i / (double) n;
					Vec3 p = a.lerp(b, t).add(0, -0.8 * Math.sin(Math.PI * t) - 0.25, 0);
					HubPark.piece(level, p, Blocks.WOOL.pick(colors[(i + k) % colors.length]).defaultBlockState(), new Vector3f(0.45F, 0.5F, 0.06F),
							new Quaternionf().rotateZ((float) Math.PI / 4));
				}
			}
		}
	}

	/** A striped tent: walls in two colours, a stepped roof, a flag on top. {@code open}: the south side is open. */
	static void tent(ServerLevel level, int x1, int z1, int x2, int z2, int wall, DyeColor a, DyeColor b, boolean open) {
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
				boolean front = z == z2 && x != x1 && x != x2;
				for (int y = FLOOR + 1; y <= FLOOR + wall; y++) {
					if (edge && !(open && front)) {
						HubPark.set(level, x, y, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? a : b).defaultBlockState());
					}
				}
			}
		}
		int lvl = 0;
		for (int i = 0;; i++) {
			int ax = x1 - 1 + i;
			int bx = x2 + 1 - i;
			int az = z1 - 1 + i;
			int bz = z2 + 1 - i;
			if (ax > bx || az > bz) {
				break;
			}
			for (int x = ax; x <= bx; x++) {
				for (int z = az; z <= bz; z++) {
					if (x == ax || x == bx || z == az || z == bz) {
						HubPark.set(level, x, FLOOR + wall + 1 + lvl, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? a : b).defaultBlockState());
					}
				}
			}
			lvl++;
		}
		int cx = (x1 + x2) / 2;
		int cz = (z1 + z2) / 2;
		HubPark.box(level, cx, FLOOR + wall + 1 + lvl, cz, cx, FLOOR + wall + 2 + lvl, cz, Blocks.OAK_FENCE.defaultBlockState());
		HubPark.set(level, cx, FLOOR + wall + 3 + lvl, cz, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState());
	}

	static void stand(ServerLevel level, int x, int z, DyeColor color, String name, BlockState treat) {
		HubPark.box(level, x, FLOOR + 1, z, x + 2, FLOOR + 1, z, Blocks.STRIPPED_BIRCH_LOG.defaultBlockState());
		for (int dx : new int[] {0, 2}) {
			HubPark.box(level, x + dx, FLOOR + 2, z, x + dx, FLOOR + 3, z, Blocks.OAK_FENCE.defaultBlockState());
		}
		for (int dx = -1; dx <= 3; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				HubPark.set(level, x + dx, FLOOR + 4, z + dz, Blocks.WOOL.pick((dx + 10) % 2 == 0 ? color : DyeColor.WHITE).defaultBlockState());
			}
		}
		HubPark.set(level, x + 1, FLOOR + 2, z, treat);
		HubPark.label(level, x + 1.5, FLOOR + 5.4, z + 0.5, name, ChatFormatting.YELLOW, 0.8F);
	}

	// ----- The duck-shooting booth -----

	private static final int DUCK_ROWS = 3;
	private static final int DUCKS_PER_ROW = 4;
	private static final double DUCK_X1 = 45.8;
	private static final double DUCK_X2 = 52.2;
	private static final List<UUID> DUCKS = new ArrayList<>();
	private static final int[] DUCK_DOWN = new int[DUCK_ROWS * DUCKS_PER_ROW];
	private static final Map<UUID, Integer> SCORES = new HashMap<>();
	private static int roundEnds = -1;
	private static final AABB BOOTH_FRONT = new AABB(43, FLOOR, -5, 55, FLOOR + 4, 1);

	private static void galleryBooth(ServerLevel level) {
		tent(level, 44, -11, 54, -4, 4, DyeColor.RED, DyeColor.WHITE, true);
		// Inside: a sky-blue back wall with clouds, wave boards in front of the bottom row, a counter at the front.
		HubPark.box(level, 45, FLOOR + 1, -10, 53, FLOOR + 4, -10, Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState());
		for (int x : new int[] {46, 50, 52}) {
			HubPark.set(level, x, FLOOR + 4, -10, Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState());
		}
		HubPark.box(level, 45, FLOOR + 1, -7, 53, FLOOR + 1, -7, Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState());
		HubPark.box(level, 45, FLOOR + 1, -4, 53, FLOOR + 1, -4, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(
				net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X));
		HubPark.label(level, 49.5, FLOOR + 9.4, -7, "DUCK SHOOT", ChatFormatting.AQUA, 1.6F);
		DUCKS.clear();
		for (int r = 0; r < DUCK_ROWS; r++) {
			for (int i = 0; i < DUCKS_PER_ROW; i++) {
				Display.ItemDisplay d = HubPark.model(level, duckSpot(r, i, 0), 180, "duck", 0.9F, 0);
				if (d != null) {
					((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
					((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
					DUCKS.add(d.getUUID());
				}
			}
		}
		HubHosts.add(level, new Vec3(54.5, FLOOR + 1, -2.5), 135, VillagerProfession.FLETCHER, VillagerType.PLAINS,
				new HubHosts.Host("ducks", "Dot", "Duck Shoot", 0x40C8FF, "minecraft:snowball",
						"Throw snowballs at the ducks sliding across the booth! You've got 30 seconds. Hit 5 or more and you win a Rubber Duck.",
						"Duck Shoot: right-click Dot to play!", HubHosts.play("Play! (30 seconds)"), (p, key) -> startRound(p)));
	}

	/** Where duck {@code i} in row {@code r} is at time {@code t}: rows slide alternate ways, wrapping round. */
	private static Vec3 duckSpot(int r, int i, int t) {
		double span = DUCK_X2 - DUCK_X1;
		double speed = 0.045 + r * 0.015;
		double off = (i * span / DUCKS_PER_ROW + t * speed) % span;
		double x = r % 2 == 0 ? DUCK_X1 + off : DUCK_X2 - off;
		return new Vec3(x, FLOOR + 1.85 + r * 1.0, -7.6 - r * 0.8);
	}

	private static void startRound(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (roundEnds < 0) {
			roundEnds = tick + 30 * 20;
			SCORES.clear();
			HubPark.say(level, new Vec3(49.5, FLOOR, -4), "Duck Shoot! 30 seconds: GO!");
		}
		SCORES.putIfAbsent(p.getUUID(), 0);
		HubGames.give(p, HubGames.toy(Items.SNOWBALL, 16, "Gallery Snowball", ChatFormatting.AQUA));
		Alerts.title(p, "SHOOT!", "Throw snowballs (right-click) at the ducks", ChatFormatting.AQUA);
		if (!BOOTH_FRONT.contains(p.position())) {
			p.teleportTo(49.5, FLOOR + 1, -2.5);
			p.setYRot(180);
		}
	}

	private static void tickBooth(ServerLevel level) {
		for (int k = 0; k < DUCKS.size(); k++) {
			Entity d = level.getEntity(DUCKS.get(k));
			if (d == null) {
				continue;
			}
			Vec3 at = duckSpot(k / DUCKS_PER_ROW, k % DUCKS_PER_ROW, tick);
			Vec3 old = d.position();
			if (old.distanceTo(at) > 1) {
				// Wrapping round: hop straight across (no sliding back through the others).
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(0);
			} else {
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
			}
			if (DUCK_DOWN[k] > 0) {
				DUCK_DOWN[k]--;
			}
			d.teleportTo(at.x, at.y, at.z);
			d.setXRot(DUCK_DOWN[k] > 0 ? 80 : 0);
		}
		// Snowballs against the ducks.
		for (Snowball ball : level.getEntities(EntityTypes.SNOWBALL, new AABB(43, FLOOR, -12, 56, FLOOR + 8, 2), s -> s.isAlive())) {
			Vec3 a = ball.position();
			Vec3 b = a.add(ball.getDeltaMovement().scale(1.2));
			for (int k = 0; k < DUCKS.size(); k++) {
				Entity d = level.getEntity(DUCKS.get(k));
				if (d == null || DUCK_DOWN[k] > 0) {
					continue;
				}
				AABB box = new AABB(d.getX() - 0.4, d.getY() - 0.45, d.getZ() - 0.4, d.getX() + 0.4, d.getY() + 0.45, d.getZ() + 0.4);
				if (box.contains(a) || box.clip(a, b).isPresent()) {
					DUCK_DOWN[k] = 40;
					ball.discard();
					level.playSound(null, d.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.2F, 1.5F);
					level.playSound(null, d.blockPosition(), SoundEvents.CHICKEN_HURT_BABY.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
					level.sendParticles(ParticleTypes.CRIT, d.getX(), d.getY(), d.getZ(), 14, 0.3, 0.3, 0.2, 0.2);
					if (roundEnds >= 0 && ball.getOwner() instanceof ServerPlayer shooter && SCORES.containsKey(shooter.getUUID())) {
						int score = SCORES.merge(shooter.getUUID(), 1, Integer::sum);
						shooter.sendOverlayMessage(Component.literal("Quack! " + score + (score == 5 ? "  (that's a prize!)" : "")).withStyle(ChatFormatting.AQUA));
					}
					break;
				}
			}
		}
		if (roundEnds >= 0) {
			int left = roundEnds - tick;
			if (left > 0 && left % 200 == 0) {
				for (UUID id : SCORES.keySet()) {
					ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
					if (p != null) {
						p.sendOverlayMessage(Component.literal(left / 20 + " seconds left!").withStyle(ChatFormatting.AQUA));
					}
				}
			}
			if (left <= 0) {
				endRound(level);
			}
		}
	}

	private static void endRound(ServerLevel level) {
		roundEnds = -1;
		StringBuilder line = new StringBuilder();
		SCORES.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).forEach(e -> {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p != null) {
				line.append(line.isEmpty() ? "" : ", ").append(p.getName().getString()).append(" ").append(e.getValue());
			}
		});
		for (var e : SCORES.entrySet()) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				continue;
			}
			HubGames.takeAll(p, "Gallery Snowball");
			Records.submit(level, "Most ducks (Duck Shoot)", p.getName().getString(), e.getValue(), false, String::valueOf);
			if (e.getValue() >= 5) {
				Prizes.award(level, p, "duck", "a rubber duck");
				Alerts.title(p, "YOU WIN!", e.getValue() + " ducks! You won a rubber duck!", ChatFormatting.GOLD);
				HubPark.burst(level, p.position().add(0, 3, 0));
			} else {
				Alerts.title(p, "TIME!", "Ducks hit: " + line, ChatFormatting.AQUA);
			}
		}
		SCORES.clear();
	}

	// ----- The Ferris wheel -----

	private static final Vec3 WHEEL = new Vec3(62.5, FLOOR + 12.0, -8.5);
	private static final double RADIUS = 9.5;
	private static final int GONDOLAS = 10;
	private static final double SPIN = Math.PI * 2 / 1300;
	private static final Vec3 WHEEL_EXIT = new Vec3(62.5, FLOOR + 1, -4.5);
	/** The turning parts: a display at the hub and its shape at angle 0 (rotated round the hub as it turns). */
	private record Spinner(UUID id, Vector3f offset, Quaternionf rot, Vector3f scale) {
	}

	private static final List<Spinner> SPINNERS = new ArrayList<>();
	private static final UUID[] CABINS = new UUID[GONDOLAS];
	private static final UUID[] CABIN_SEATS = new UUID[GONDOLAS * 2];
	private static double angle;
	/** Who's waiting for a cabin (each entry rides together: one or two people). */
	private static final List<List<UUID>> WHEEL_QUEUE = new ArrayList<>();
	/** Riders, and how far round they've been. */
	private static final Map<UUID, Double> WHEEL_RIDERS = new HashMap<>();

	private static void wheel(ServerLevel level) {
		SPINNERS.clear();
		WHEEL_QUEUE.clear();
		WHEEL_RIDERS.clear();
		angle = 0;
		BlockState white = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
		// The legs: an A-frame each side, and the axle.
		for (double z : new double[] {-10.6, -6.4}) {
			for (double x : new double[] {55.5, 69.5}) {
				HubPark.beam(level, new Vec3(x, FLOOR + 1, WHEEL.z + (z - WHEEL.z) * 1.3), new Vec3(WHEEL.x, WHEEL.y, z), 0.35F, 0.35F, white);
			}
		}
		HubPark.beam(level, new Vec3(WHEEL.x, WHEEL.y, -11.2), new Vec3(WHEEL.x, WHEEL.y, -5.8), 0.6F, 0.6F, Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState());
		// The wheel: two rims (front and back), spokes, and lights round the edge.
		int segs = 20;
		for (double z : new double[] {-9.6, -7.4}) {
			for (int i = 0; i < segs; i++) {
				double a1 = i * Math.PI * 2 / segs;
				double a2 = (i + 1) * Math.PI * 2 / segs;
				spinner(level, new Vec3(Math.cos(a1) * RADIUS, Math.sin(a1) * RADIUS, z - WHEEL.z), new Vec3(Math.cos(a2) * RADIUS, Math.sin(a2) * RADIUS, z - WHEEL.z),
						0.28F, Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
			}
			for (int i = 0; i < GONDOLAS; i++) {
				double a = i * Math.PI * 2 / GONDOLAS;
				spinner(level, new Vec3(0, 0, z - WHEEL.z), new Vec3(Math.cos(a) * RADIUS, Math.sin(a) * RADIUS, z - WHEEL.z), 0.16F, white);
			}
		}
		for (int i = 0; i < segs; i++) {
			double a = (i + 0.5) * Math.PI * 2 / segs;
			for (double z : new double[] {-9.75, -7.25}) {
				spinner(level, new Vec3(Math.cos(a) * (RADIUS + 0.05), Math.sin(a) * (RADIUS + 0.05), z - WHEEL.z - 0.1),
						new Vec3(Math.cos(a) * (RADIUS + 0.05), Math.sin(a) * (RADIUS + 0.05), z - WHEEL.z + 0.1), 0.25F,
						(i % 2 == 0 ? Blocks.SEA_LANTERN : Blocks.SHROOMLIGHT).defaultBlockState());
			}
		}
		String[] colors = {"red", "blue", "yellow", "green", "red", "blue", "yellow", "green", "red", "blue"};
		for (int i = 0; i < GONDOLAS; i++) {
			Display.ItemDisplay g = HubPark.tempModel(level, cabinSpot(i), 0, "gondola_" + colors[i % colors.length], 1.8F, 0);
			CABINS[i] = g == null ? null : g.getUUID();
			for (int k = 0; k < 2; k++) {
				Display.BlockDisplay s = HubPark.seat(level, cabinSpot(i).add(0, -0.6, 0));
				CABIN_SEATS[i * 2 + k] = s == null ? null : s.getUUID();
			}
		}
		HubPark.box(level, 61, FLOOR, -10, 64, FLOOR, -7, Blocks.POLISHED_ANDESITE.defaultBlockState());
		HubPark.label(level, WHEEL.x, WHEEL.y + RADIUS + 2.4, WHEEL.z, "BIG WHEEL", ChatFormatting.RED, 2.0F);
		HubHosts.add(level, new Vec3(59.5, FLOOR + 1, -3.5), 180, VillagerProfession.CARTOGRAPHER, VillagerType.TAIGA,
				new HubHosts.Host("wheel", "Wally", "Big Wheel", 0xFFD040, "minecraft:spyglass",
						"A slow ride round the Big Wheel, with the best view of the whole park. Your cabin stops at the bottom for you. "
								+ "Crouch to get off early.",
						"Big Wheel: right-click Wally to ride!", HubHosts.rideButtons("Ride the Big Wheel!"), (p, key) -> {
							List<ServerPlayer> g = HubHosts.group(p, key);
							for (int i = 0; i < g.size(); i += 2) {
								List<UUID> pair = new ArrayList<>();
								pair.add(g.get(i).getUUID());
								if (i + 1 < g.size()) {
									pair.add(g.get(i + 1).getUUID());
								}
								WHEEL_QUEUE.add(pair);
							}
							for (ServerPlayer q : g) {
								q.sendOverlayMessage(Component.literal("Your cabin's coming round. Hop in at the bottom!").withStyle(ChatFormatting.YELLOW));
							}
						}));
	}

	private static void spinner(ServerLevel level, Vec3 a, Vec3 b, float thick, BlockState state) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return;
		}
		Vec3 dir = b.subtract(a);
		float len = (float) dir.length();
		Quaternionf rot = new Quaternionf().rotateY((float) Math.atan2(dir.x, dir.z)).rotateX((float) -Math.asin(Math.max(-1, Math.min(1, dir.y / len))));
		Vector3f off = rot.transform(new Vector3f(-thick / 2, -thick / 2, 0)).add((float) a.x, (float) a.y, (float) a.z);
		Vector3f scale = new Vector3f(thick, thick, len);
		d.snapTo(WHEEL.x, WHEEL.y, WHEEL.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		((DisplayAccessor) d).rubymod$setViewRange(6.0F);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(off, rot, scale, new Quaternionf()));
		level.addFreshEntity(d);
		HubPark.keep(d);
		SPINNERS.add(new Spinner(d.getUUID(), off, rot, scale));
	}

	private static Vec3 cabinSpot(int i) {
		double a = angle + i * Math.PI * 2 / GONDOLAS;
		return WHEEL.add(Math.cos(a) * RADIUS, Math.sin(a) * RADIUS - 1.35, -0.0);
	}

	private static void tickWheel(ServerLevel level) {
		angle += SPIN;
		if (tick % 10 == 0) {
			Quaternionf turn = new Quaternionf().rotateZ((float) (angle + SPIN * 10));
			for (Spinner s : SPINNERS) {
				if (level.getEntity(s.id()) instanceof Display d) {
					((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
					((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(10);
					((DisplayAccessor) d).rubymod$setTransformation(new Transformation(turn.transform(new Vector3f(s.offset())), new Quaternionf(turn).mul(s.rot()),
							s.scale(), new Quaternionf()));
				}
			}
		}
		for (int i = 0; i < GONDOLAS; i++) {
			Vec3 at = cabinSpot(i);
			Entity g = CABINS[i] == null ? null : level.getEntity(CABINS[i]);
			if (g != null) {
				g.teleportTo(at.x, at.y, at.z);
			}
			double a = ((angle + i * Math.PI * 2 / GONDOLAS) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
			boolean bottom = Math.abs(a - Math.PI * 1.5) < 0.05;
			boolean empty = true;
			Entity[] seats = new Entity[2];
			for (int k = 0; k < 2; k++) {
				Entity s = CABIN_SEATS[i * 2 + k] == null ? null : level.getEntity(CABIN_SEATS[i * 2 + k]);
				seats[k] = s;
				if (s == null) {
					continue;
				}
				s.teleportTo(at.x + (k == 0 ? -0.3 : 0.3), at.y - 0.6, at.z);
				// Riders who've been all the way round get off at the bottom.
				for (Entity rider : new ArrayList<>(s.getPassengers())) {
					empty = false;
					rider.resetFallDistance();
					if (rider instanceof ServerPlayer p) {
						double done = WHEEL_RIDERS.merge(p.getUUID(), SPIN, Double::sum);
						if (bottom && done > Math.PI * 2 - 0.2) {
							letOff(p, WHEEL_EXIT, "Wasn't that a view? Talk to Wally to go again.");
						}
					}
				}
			}
			// The next one or two waiting get in when an empty cabin comes to the bottom.
			if (bottom && empty && seats[0] != null && seats[1] != null && !WHEEL_QUEUE.isEmpty()) {
				List<UUID> pair = WHEEL_QUEUE.remove(0);
				int k = 0;
				for (UUID id : pair) {
					ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
					if (p != null && p.level() == level && p.position().distanceTo(WHEEL) < 20 && !p.isPassenger()) {
						p.startRiding(seats[k++], true, true);
						WHEEL_RIDERS.put(p.getUUID(), 0.0);
						p.sendOverlayMessage(Component.literal("Up we go! Crouch to get off early.").withStyle(ChatFormatting.YELLOW));
						level.playSound(null, p.blockPosition(), SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.PLAYERS, 0.7F, 1.2F);
					}
				}
			}
		}
		// Crouched off mid-way: down to the ground safely.
		for (Iterator<Map.Entry<UUID, Double>> it = WHEEL_RIDERS.entrySet().iterator(); it.hasNext();) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(it.next().getKey());
			if (p == null || !onAny(p, CABIN_SEATS)) {
				it.remove();
				if (p != null && p.level() == level && p.getY() > FLOOR + 2.5) {
					letOff(p, WHEEL_EXIT, "Back on the ground. Talk to Wally to ride again.");
				}
			}
		}
	}

	// ----- The carousel -----

	private static final Vec3 CAROUSEL = new Vec3(63.5, FLOOR + 1, 10.5);
	private static final int HORSES = 10;
	private static final double HORSE_R = 4.7;
	private static final double CAROUSEL_SPIN = Math.PI * 2 / 360;
	private static final Vec3 CAROUSEL_EXIT = new Vec3(54.5, FLOOR + 1, 9.5);
	private static final UUID[] HORSE = new UUID[HORSES];
	private static final UUID[] HORSE_SEATS = new UUID[HORSES];
	private static double turn;
	private static final Map<UUID, Integer> CAROUSEL_RIDERS = new HashMap<>();
	/** The carousel's tune (note block pitches; 0 is a rest). */
	private static final float[] TUNE = {1.0F, 1.26F, 1.5F, 1.26F, 1.0F, 0, 1.5F, 1.68F, 2.0F, 1.68F, 1.5F, 0, 1.33F, 1.5F, 1.68F, 1.5F, 1.33F, 1.26F, 1.0F, 0};

	private static void carousel(ServerLevel level) {
		CAROUSEL_RIDERS.clear();
		int cx = 63;
		int cz = 10;
		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r <= 6.8) {
					HubPark.set(level, cx + dx, FLOOR, cz + dz, r > 6.1 ? Blocks.GOLD_BLOCK.defaultBlockState()
							: (Math.floor(r) % 2 == 0 ? Blocks.SMOOTH_QUARTZ : Blocks.POLISHED_DIORITE).defaultBlockState());
				}
				if (r <= 8.0) {
					// A cone roof in red and white segments, with a yellow valance and lights round its rim.
					int sector = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 4));
					int h = FLOOR + 7 + (int) Math.floor((8.0 - r) / 1.5);
					HubPark.set(level, cx + dx, h, cz + dz, Blocks.WOOL.pick(sector % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
					if (r > 7.1) {
						HubPark.set(level, cx + dx, FLOOR + 5, cz + dz, (dx + dz) % 3 == 0 ? Blocks.SEA_LANTERN.defaultBlockState()
								: Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState());
					}
				}
			}
		}
		HubPark.box(level, cx, FLOOR + 1, cz, cx, FLOOR + 7, cz, Blocks.GOLD_BLOCK.defaultBlockState());
		HubPark.set(level, cx, FLOOR + 13, cz, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState());
		HubPark.label(level, CAROUSEL.x, FLOOR + 14.6, CAROUSEL.z, "CAROUSEL", ChatFormatting.LIGHT_PURPLE, 2.2F);
		String[] coats = {"white", "brown", "black", "gold"};
		for (int i = 0; i < HORSES; i++) {
			Display.ItemDisplay h = HubPark.tempModel(level, horseSpot(i), 0, "horse_" + coats[i % coats.length], 2.6F, 0);
			HORSE[i] = h == null ? null : h.getUUID();
			Display.BlockDisplay s = HubPark.seat(level, horseSpot(i));
			HORSE_SEATS[i] = s == null ? null : s.getUUID();
		}
		HubHosts.add(level, new Vec3(54.5, FLOOR + 1, 12.5), 270, VillagerProfession.SHEPHERD, VillagerType.SNOW,
				new HubHosts.Host("carousel", "Cora", "Carousel", 0xFF7CC0, "minecraft:saddle",
						"Round and round on a painted horse, up and down to the music! Rides last about 40 seconds. Crouch to get off.",
						"Carousel: right-click Cora to ride!", HubHosts.rideButtons("Ride a horse!"), (p, key) -> {
							for (ServerPlayer q : HubHosts.group(p, key)) {
								rideHorse(q);
							}
						}));
	}

	private static Vec3 horseSpot(int i) {
		double a = turn + i * Math.PI * 2 / HORSES;
		double bob = 0.35 * Math.sin(turn * 6 + i * Math.PI / 2);
		return CAROUSEL.add(Math.cos(a) * HORSE_R, 1.3 + bob, Math.sin(a) * HORSE_R);
	}

	private static void rideHorse(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (p.isPassenger()) {
			return;
		}
		for (int i = 0; i < HORSES; i++) {
			Entity s = HORSE_SEATS[i] == null ? null : level.getEntity(HORSE_SEATS[i]);
			if (s != null && !s.isVehicle()) {
				p.startRiding(s, true, true);
				HubRides.faceSeat(p, s);
				CAROUSEL_RIDERS.put(p.getUUID(), 0);
				p.sendOverlayMessage(Component.literal("Giddy-up! Crouch to get off.").withStyle(ChatFormatting.LIGHT_PURPLE));
				return;
			}
		}
		p.sendOverlayMessage(Component.literal("All the horses are taken! Try again in a moment.").withStyle(ChatFormatting.YELLOW));
	}

	private static void tickCarousel(ServerLevel level) {
		turn += CAROUSEL_SPIN;
		for (int i = 0; i < HORSES; i++) {
			Vec3 at = horseSpot(i);
			double a = turn + i * Math.PI * 2 / HORSES;
			float yaw = (float) Math.toDegrees(Math.atan2(Math.sin(a), Math.cos(a))) + 180;
			Entity h = HORSE[i] == null ? null : level.getEntity(HORSE[i]);
			if (h != null) {
				h.teleportTo(at.x, at.y, at.z);
				h.setYRot(yaw);
			}
			Entity s = HORSE_SEATS[i] == null ? null : level.getEntity(HORSE_SEATS[i]);
			if (s != null) {
				s.teleportTo(at.x, at.y + 0.4, at.z);
				s.setYRot(yaw);
			}
		}
		for (Iterator<Map.Entry<UUID, Integer>> it = CAROUSEL_RIDERS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p == null || !onAny(p, HORSE_SEATS)) {
				it.remove();
				if (p != null && p.level() == level) {
					letOff(p, CAROUSEL_EXIT, "Talk to Cora to ride again!");
				}
				continue;
			}
			e.setValue(e.getValue() + 1);
			if (e.getValue() > 40 * 20) {
				it.remove();
				letOff(p, CAROUSEL_EXIT, "Whoa there! Talk to Cora to ride again.");
			}
		}
		// (The carousel's tune is gone: music only on the dance floor.)
	}

	// ----- The bouncy castle -----

	private static void castle(ServerLevel level) {
		DyeColor[] floor = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIGHT_BLUE, DyeColor.LIME};
		for (int x = 66; x <= 72; x++) {
			for (int z = -2; z <= 6; z++) {
				boolean edge = x == 66 || x == 72 || z == -2 || z == 6;
				boolean door = x == 66 && z >= 1 && z <= 3;
				if (!edge) {
					HubPark.set(level, x, FLOOR, z, Blocks.WOOL.pick(floor[(x + z) % floor.length]).defaultBlockState());
					HubPark.bouncy(new BlockPos(x, FLOOR, z));
				} else if (!door) {
					HubPark.set(level, x, FLOOR, z, Blocks.WOOL.pick(DyeColor.BLUE).defaultBlockState());
					HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 2, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.RED : DyeColor.BLUE).defaultBlockState());
				} else {
					HubPark.set(level, x, FLOOR, z, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
					HubPark.bouncy(new BlockPos(x, FLOOR, z));
				}
			}
		}
		for (int x : new int[] {66, 72}) {
			for (int z : new int[] {-2, 6}) {
				HubPark.box(level, x, FLOOR + 3, z, x, FLOOR + 4, z, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState());
				HubPark.set(level, x, FLOOR + 5, z, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
				HubPark.set(level, x, FLOOR + 6, z, Blocks.OAK_FENCE.defaultBlockState());
				HubPark.piece(level, new Vec3(x + 0.85, FLOOR + 6.7, z + 0.5), Blocks.WOOL.pick(DyeColor.LIME).defaultBlockState(), new Vector3f(0.7F, 0.45F, 0.05F),
						new Quaternionf());
			}
		}
		HubPark.label(level, 69.5, FLOOR + 7.5, 2.5, "BOUNCY CASTLE", ChatFormatting.YELLOW, 1.6F);
		HubPark.label(level, 65.5, FLOOR + 3.2, 2.5, "Hop in and bounce!", ChatFormatting.WHITE, 0.8F);
	}

	// ----- Shared -----

	private static boolean onAny(ServerPlayer p, UUID[] seats) {
		Entity v = p.getVehicle();
		if (v == null) {
			return false;
		}
		for (UUID id : seats) {
			if (v.getUUID().equals(id)) {
				return true;
			}
		}
		return false;
	}

	static void letOff(ServerPlayer p, Vec3 at, String message) {
		if (p.isPassenger()) {
			p.stopRiding();
		}
		p.teleportTo(at.x, at.y, at.z);
		p.resetFallDistance();
		HubPark.landed(p);
		p.sendOverlayMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	private static int tick;

	static void tick(ServerLevel level) {
		tick++;
		tickBooth(level);
		tickWheel(level);
		tickCarousel(level);
	}

	static void reset() {
		WHEEL_QUEUE.clear();
		WHEEL_RIDERS.clear();
		CAROUSEL_RIDERS.clear();
		SCORES.clear();
		roundEnds = -1;
	}
}
