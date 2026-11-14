package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Thrill Zone: the Drop Tower (up slowly... a pause... DROP), the swinging Pirate Ship, the spinning Teacups, and
 * the hot-air balloon tour over the whole park. The carnival booths here are in Booths.
 */
final class Thrill {
	private Thrill() {
	}

	static final int CX = 84;
	static final int CZ = 40;
	static final Vec3 LAND = new Vec3(83.5, FLOOR + 1, 38.5);
	private static int tick;

	static void build(ServerLevel level) {
		HubPark.island(level, CX, CZ, 22, FLOOR);
		HubPark.clear(level, CX - 22, FLOOR + 13, CZ - 22, CX + 22, FLOOR + 34, CZ + 22);
		// Paths between everything.
		HubPark.box(level, CX - 14, FLOOR, CZ - 1, CX + 16, FLOOR, CZ + 1, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
		HubPark.box(level, CX - 1, FLOOR, CZ - 12, CX + 1, FLOOR, CZ + 10, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
		HubPark.label(level, CX + 0.5, FLOOR + 33, CZ + 0.5, "THRILL ZONE", ChatFormatting.GOLD, 3.0F);
		HubPark.travelPad(level, CX + 3, FLOOR, CZ + 3, "thrill");
		dropTower(level);
		pirateShip(level);
		teacups(level);
		balloon(level);
		for (int[] l : new int[][] {{CX - 4, CZ - 4}, {CX + 5, CZ - 4}, {CX - 5, CZ + 5}, {CX + 10, CZ + 4}}) {
			HubPark.lamp(level, l[0], FLOOR + 1, l[1]);
		}
	}

	// ----- The Drop Tower -----

	private static final Vec3 TOWER = new Vec3(90.0, FLOOR + 1, 31.0);
	private static final Vec3 TOWER_EXIT = new Vec3(90.5, FLOOR + 1, 35.5);
	private static final double TOP = FLOOR + 27;
	private static UUID ring;
	private static final UUID[] DROP_SEATS = new UUID[4];
	private static final float[] DROP_YAW = {270, 0, 90, 180};
	private static final double[][] DROP_SIDE = {{1.75, 0}, {0, 1.75}, {-1.75, 0}, {0, -1.75}};
	private enum Drop {
		IDLE, BOARD, UP, HOLD, FALL
	}

	private static Drop drop = Drop.IDLE;
	private static int dropTimer;
	private static double dropY = FLOOR + 1;
	private static double dropV;

	private static void dropTower(ServerLevel level) {
		drop = Drop.IDLE;
		dropY = TOWER.y;
		for (int y = FLOOR + 1; y <= FLOOR + 30; y++) {
			BlockState s = Blocks.CONCRETE.pick(((y - FLOOR) / 3) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState();
			HubPark.box(level, 89, y, 30, 90, y, 31, s);
		}
		HubPark.box(level, 88, FLOOR + 31, 29, 91, FLOOR + 31, 32, Blocks.GOLD_BLOCK.defaultBlockState());
		HubPark.box(level, 89, FLOOR + 32, 30, 90, FLOOR + 32, 31, Blocks.SEA_LANTERN.defaultBlockState());
		HubPark.box(level, 87, FLOOR, 28, 92, FLOOR, 33, Blocks.POLISHED_ANDESITE.defaultBlockState());
		HubPark.label(level, TOWER.x, FLOOR + 34.5, TOWER.z, "DROP TOWER", ChatFormatting.RED, 2.0F);
		Display.ItemDisplay r = HubPark.tempModel(level, TOWER, 0, "drop_seats", 4.4F, 2.2F);
		ring = r == null ? null : r.getUUID();
		for (int i = 0; i < 4; i++) {
			Display.BlockDisplay s = HubPark.seat(level, TOWER);
			DROP_SEATS[i] = s == null ? null : s.getUUID();
		}
		placeDrop(level);
		HubHosts.add(level, new Vec3(93.5, FLOOR + 1, 35.5), 135, VillagerProfession.ARMORER, VillagerType.SAVANNA,
				new HubHosts.Host("drop", "Tess", "Drop Tower", 0xFF4050, "minecraft:feather",
						"Up, up, up to the very top of the tower... a little wait... then DROP! Four seats.",
						"Drop Tower: right-click Tess... if you're brave!", HubHosts.rideButtons("Ride the Drop Tower!"), (p, key) -> {
							if (drop != Drop.IDLE && drop != Drop.BOARD) {
								p.sendOverlayMessage(Component.literal("It's up there right now! Wait for it to come down.").withStyle(ChatFormatting.YELLOW));
								return;
							}
							for (ServerPlayer q : HubHosts.group(p, key)) {
								seatOn(q, DROP_SEATS, "Hold on tight...");
							}
							if (drop == Drop.IDLE && anyone(level, DROP_SEATS)) {
								drop = Drop.BOARD;
								dropTimer = 80;
							}
						}));
	}

	private static void tickDrop(ServerLevel level) {
		List<ServerPlayer> riders = riders(level, DROP_SEATS);
		switch (drop) {
			case IDLE -> {
			}
			case BOARD -> {
				if (riders.isEmpty()) {
					drop = Drop.IDLE;
				} else if (--dropTimer <= 0) {
					drop = Drop.UP;
					tell(riders, "Going up...");
				}
			}
			case UP -> {
				dropY += 0.12;
				if (tick % 8 == 0) {
					level.playSound(null, BlockPos.containing(TOWER.x, dropY, TOWER.z), SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.8F, 0.6F);
				}
				if (dropY >= TOP) {
					dropY = TOP;
					drop = Drop.HOLD;
					dropTimer = 40 + level.getRandom().nextInt(60);
					tell(riders, "What a view... wait... what's that click?");
				}
			}
			case HOLD -> {
				if (tick % 10 == 0) {
					level.playSound(null, BlockPos.containing(TOWER.x, dropY, TOWER.z), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.8F, 0.6F);
				}
				if (--dropTimer <= 0) {
					drop = Drop.FALL;
					dropV = 0;
					level.playSound(null, BlockPos.containing(TOWER.x, dropY, TOWER.z), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 1.0F, 1.4F);
					for (ServerPlayer p : riders) {
						Alerts.title(p, "AAAAAH!", "", ChatFormatting.RED);
					}
				}
			}
			case FALL -> {
				if (dropY > FLOOR + 8) {
					dropV = Math.min(1.3, dropV + 0.09);
				} else {
					dropV = Math.max(0.12, dropV * 0.8);
				}
				dropY -= dropV;
				if (dropY <= TOWER.y) {
					dropY = TOWER.y;
					drop = Drop.IDLE;
					level.playSound(null, BlockPos.containing(TOWER), SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.0F, 0.6F);
					for (ServerPlayer p : riders) {
						FunPark.letOff(p, TOWER_EXIT, "Phew! Talk to Tess to go again.");
					}
				}
			}
		}
		// Anyone who hops off up there floats down to the exit.
		placeDrop(level);
	}

	private static void placeDrop(ServerLevel level) {
		Entity r = ring == null ? null : level.getEntity(ring);
		if (r != null) {
			r.teleportTo(TOWER.x, dropY, TOWER.z);
		}
		for (int i = 0; i < 4; i++) {
			Entity s = DROP_SEATS[i] == null ? null : level.getEntity(DROP_SEATS[i]);
			if (s != null) {
				s.teleportTo(TOWER.x + DROP_SIDE[i][0], dropY + 0.55, TOWER.z + DROP_SIDE[i][1]);
				s.setYRot(DROP_YAW[i]);
				for (Entity rider : s.getPassengers()) {
					rider.resetFallDistance();
				}
			}
		}
	}

	// ----- The Pirate Ship -----

	private static final Vec3 PIVOT = new Vec3(76.0, FLOOR + 12, 31.0);
	private static final double ARM = 9.5;
	private static final float SHIP = 4.5F;
	private static final Vec3 SHIP_EXIT = new Vec3(76.5, FLOOR + 1, 36.5);
	private static UUID ship;
	private static final List<UUID> ARMS = new ArrayList<>();
	private static final UUID[] SHIP_SEATS = new UUID[6];
	private static final double[][] SHIP_SPOTS = {{-4, -0.8}, {0, -0.8}, {4, -0.8}, {-4, 0.8}, {0, 0.8}, {4, 0.8}};
	private static int shipT = -1;
	private static double swing;

	private static void pirateShip(ServerLevel level) {
		shipT = -1;
		swing = 0;
		ARMS.clear();
		BlockState wood = Blocks.CONCRETE.pick(DyeColor.BROWN).defaultBlockState();
		for (double z : new double[] {27.0, 35.0}) {
			double toward = z < 31 ? 28.6 : 33.4;
			HubPark.beam(level, new Vec3(70.5, FLOOR + 1, z), new Vec3(PIVOT.x, PIVOT.y, toward), 0.45F, 0.45F, wood);
			HubPark.beam(level, new Vec3(81.5, FLOOR + 1, z), new Vec3(PIVOT.x, PIVOT.y, toward), 0.45F, 0.45F, wood);
		}
		HubPark.beam(level, new Vec3(PIVOT.x, PIVOT.y, 28.4), new Vec3(PIVOT.x, PIVOT.y, 33.6), 0.5F, 0.5F, Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState());
		Display.ItemDisplay m = HubPark.model(level, PIVOT, 0, "pirate_ship", SHIP, 0);
		if (m != null) {
			ship = m.getUUID();
			((DisplayAccessor) m).rubymod$setViewRange(10F);
		}
		for (double z : new double[] {29.4, 32.6}) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d != null) {
				d.snapTo(PIVOT.x, PIVOT.y, z, 0, 0);
				((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
				level.addFreshEntity(d);
				HubPark.keep(d);
				ARMS.add(d.getUUID());
			}
		}
		for (int i = 0; i < SHIP_SEATS.length; i++) {
			Display.BlockDisplay s = HubPark.seat(level, PIVOT.add(0, -ARM, 0));
			SHIP_SEATS[i] = s == null ? null : s.getUUID();
		}
		placeShip(level, 0);
		HubPark.box(level, 69, FLOOR, 29, 83, FLOOR, 33, Blocks.SAND.defaultBlockState());
		HubPark.label(level, PIVOT.x, PIVOT.y + 3, PIVOT.z, "PIRATE SHIP", ChatFormatting.GOLD, 2.0F);
		HubHosts.add(level, new Vec3(79.5, FLOOR + 1, 36.5), 180, VillagerProfession.FISHERMAN, VillagerType.TAIGA,
				new HubHosts.Host("ship", "Captain Polly", "Pirate Ship", 0xFFAA40, "minecraft:oak_boat",
						"All aboard the Pirate Ship! It swings higher... and higher... and HIGHER! Room for six.",
						"Pirate Ship: right-click Captain Polly to sail!", HubHosts.rideButtons("Set sail!"), (p, key) -> {
							if (shipT > 120) {
								p.sendOverlayMessage(Component.literal("The ship's already sailing! Wait for the next voyage.").withStyle(ChatFormatting.YELLOW));
								return;
							}
							for (ServerPlayer q : HubHosts.group(p, key)) {
								seatOn(q, SHIP_SEATS, "Yo ho ho! Hold on!");
							}
							if (shipT < 0 && anyone(level, SHIP_SEATS)) {
								shipT = 0;
							}
						}));
	}

	private static void tickShip(ServerLevel level) {
		if (shipT >= 0) {
			shipT++;
			double amp;
			if (shipT < 60) {
				amp = 0;
			} else if (shipT < 300) {
				amp = (shipT - 60) / 240.0 * 70;
			} else if (shipT < 520) {
				amp = 70;
			} else {
				amp = Math.max(0, (680 - shipT) / 160.0 * 70);
			}
			swing = Math.toRadians(amp * Math.sin((shipT - 60) * Math.PI * 2 / 110));
			if (shipT > 60 && Math.abs(Math.sin((shipT - 60) * Math.PI * 2 / 110)) > 0.995 && amp > 40) {
				level.playSound(null, BlockPos.containing(PIVOT), SoundEvents.WOOD_STEP, SoundSource.BLOCKS, 1.0F, 0.6F);
			}
			if (shipT >= 680) {
				for (ServerPlayer p : riders(level, SHIP_SEATS)) {
					FunPark.letOff(p, SHIP_EXIT, "Land ho! Talk to Captain Polly to sail again.");
				}
				shipT = -1;
				swing = 0;
			}
			if (shipT == 60) {
				tell(riders(level, SHIP_SEATS), "Anchors aweigh!");
			}
		}
		placeShip(level, tick % 2 == 0 ? 2 : -1);
	}

	private static void placeShip(ServerLevel level, int duration) {
		Quaternionf turn = new Quaternionf().rotateZ((float) swing);
		if (duration >= 0) {
			if (ship != null && level.getEntity(ship) instanceof Display d) {
				Vector3f t = turn.transform(new Vector3f(0, (float) -ARM, 0));
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(duration);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(t, new Quaternionf(turn).rotateY((float) Math.PI / 2),
						new Vector3f(SHIP, SHIP, SHIP), new Quaternionf()));
			}
			for (UUID id : ARMS) {
				if (level.getEntity(id) instanceof Display d) {
					Vector3f off = turn.transform(new Vector3f(-0.15F, (float) -ARM + 1.2F, -0.15F));
					((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
					((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(duration);
					((DisplayAccessor) d).rubymod$setTransformation(new Transformation(off, new Quaternionf(turn), new Vector3f(0.3F, (float) ARM - 1.2F, 0.3F),
							new Quaternionf()));
				}
			}
		}
		for (int i = 0; i < SHIP_SEATS.length; i++) {
			Entity s = SHIP_SEATS[i] == null ? null : level.getEntity(SHIP_SEATS[i]);
			if (s != null) {
				Vector3f local = turn.transform(new Vector3f((float) SHIP_SPOTS[i][0], (float) (-ARM + 0.15), (float) SHIP_SPOTS[i][1]));
				s.teleportTo(PIVOT.x + local.x, PIVOT.y + local.y, PIVOT.z + local.z);
				s.setYRot(SHIP_SPOTS[i][1] < 0 ? 0 : 180);
				for (Entity rider : s.getPassengers()) {
					rider.resetFallDistance();
				}
			}
		}
	}

	// ----- The Teacups -----

	private static final Vec3 CUPS = new Vec3(72.5, FLOOR + 1, 46.5);
	private static final Vec3 CUPS_EXIT = new Vec3(65.5, FLOOR + 1, 46.5);
	private static final int CUP_COUNT = 6;
	private static final UUID[] CUP = new UUID[CUP_COUNT];
	private static final UUID[] CUP_SEATS = new UUID[CUP_COUNT * 2];
	private static final double[] CUP_SPIN = new double[CUP_COUNT];
	private static double cupTurn;
	private static final Map<UUID, Integer> CUP_RIDERS = new HashMap<>();

	private static void teacups(ServerLevel level) {
		CUP_RIDERS.clear();
		DyeColor[] floor = {DyeColor.PINK, DyeColor.WHITE, DyeColor.LIGHT_BLUE, DyeColor.WHITE};
		for (int dx = -7; dx <= 7; dx++) {
			for (int dz = -7; dz <= 7; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r <= 6.6) {
					int sector = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 4));
					HubPark.set(level, 72 + dx, FLOOR, 46 + dz, r > 6 ? Blocks.GOLD_BLOCK.defaultBlockState()
							: Blocks.CONCRETE.pick(floor[sector % floor.length]).defaultBlockState());
				}
			}
		}
		// A giant teapot in the middle.
		HubPark.box(level, 71, FLOOR + 1, 45, 73, FLOOR + 2, 47, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		HubPark.set(level, 72, FLOOR + 3, 46, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		HubPark.set(level, 72, FLOOR + 4, 46, Blocks.CONCRETE.pick(DyeColor.PINK).defaultBlockState());
		HubPark.set(level, 74, FLOOR + 2, 46, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		HubPark.set(level, 75, FLOOR + 3, 46, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		HubPark.set(level, 70, FLOOR + 2, 46, Blocks.CONCRETE.pick(DyeColor.PINK).defaultBlockState());
		HubPark.label(level, CUPS.x, FLOOR + 6, CUPS.z, "TEACUPS", ChatFormatting.LIGHT_PURPLE, 1.8F);
		String[] colors = {"pink", "blue", "yellow", "green", "purple", "red"};
		for (int i = 0; i < CUP_COUNT; i++) {
			Display.ItemDisplay c = HubPark.tempModel(level, cupSpot(i), 0, "teacup_" + colors[i], 2.4F, 1.2F);
			CUP[i] = c == null ? null : c.getUUID();
			CUP_SPIN[i] = i * 60;
			for (int k = 0; k < 2; k++) {
				Display.BlockDisplay s = HubPark.seat(level, cupSpot(i));
				CUP_SEATS[i * 2 + k] = s == null ? null : s.getUUID();
			}
		}
		HubHosts.add(level, new Vec3(66.5, FLOOR + 1, 42.5), 225, VillagerProfession.SHEPHERD, VillagerType.PLAINS,
				new HubHosts.Host("cups", "Mabel", "Teacups", 0xFF7CC0, "minecraft:flower_pot",
						"Spin round and round in a giant teacup! Two to a cup. It lasts about 30 seconds (crouch to get off).",
						"Teacups: right-click Mabel for a spin!", HubHosts.rideButtons("Spin in a teacup!"), (p, key) -> {
							for (ServerPlayer q : HubHosts.group(p, key)) {
								if (seatOn(q, CUP_SEATS, "Wheee! Round and round...")) {
									CUP_RIDERS.put(q.getUUID(), 0);
								}
							}
						}));
	}

	private static Vec3 cupSpot(int i) {
		double a = cupTurn + i * Math.PI * 2 / CUP_COUNT;
		return CUPS.add(Math.cos(a) * 4.0, 0, Math.sin(a) * 4.0);
	}

	private static void tickCups(ServerLevel level) {
		boolean busy = !CUP_RIDERS.isEmpty();
		cupTurn += busy ? 0.02 : 0.006;
		for (int i = 0; i < CUP_COUNT; i++) {
			CUP_SPIN[i] += busy ? 7 + i : 1.5;
			Vec3 at = cupSpot(i);
			Entity c = CUP[i] == null ? null : level.getEntity(CUP[i]);
			if (c != null) {
				c.teleportTo(at.x, at.y, at.z);
				c.setYRot((float) CUP_SPIN[i]);
			}
			for (int k = 0; k < 2; k++) {
				Entity s = CUP_SEATS[i * 2 + k] == null ? null : level.getEntity(CUP_SEATS[i * 2 + k]);
				if (s != null) {
					double a = Math.toRadians(CUP_SPIN[i]) + k * Math.PI;
					s.teleportTo(at.x + Math.cos(a) * 0.45, at.y + 0.45, at.z + Math.sin(a) * 0.45);
					s.setYRot((float) CUP_SPIN[i] + k * 180);
				}
			}
		}
		for (Iterator<Map.Entry<UUID, Integer>> it = CUP_RIDERS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p == null || !on(p, CUP_SEATS)) {
				it.remove();
				if (p != null && p.level() == level) {
					FunPark.letOff(p, CUPS_EXIT, "Dizzy? Talk to Mabel to spin again!");
				}
				continue;
			}
			e.setValue(e.getValue() + 1);
			if (e.getValue() > 30 * 20) {
				it.remove();
				FunPark.letOff(p, CUPS_EXIT, "Whoa, dizzy! Talk to Mabel to spin again.");
			}
		}
	}

	// ----- The balloon tour -----

	private static TrackRide balloon;
	private static final Vec3 BALLOON = new Vec3(100.5, FLOOR + 1, 38.5);
	/** Up from the Thrill Zone, over every island in turn, and home. x, z, height. */
	private static final double[][] TOUR = {{100.5, 38.5, 1}, {100, 40, 6}, {96, 44, 14}, {84, 46, 22}, {64, 44, 27}, {44, 40, 29}, {20, 50, 29},
		{0, 56, 29}, {-24, 52, 29}, {-44, 44, 29}, {-70, 26, 29}, {-88, 6, 30}, {-70, -6, 30}, {-46, -30, 30}, {-22, -58, 31}, {6, -63, 30},
		{30, -56, 30}, {44, -44, 30}, {52, -20, 28}, {56, 2, 28}, {72, 20, 24}, {90, 30, 14}, {98, 36, 6}, {100.5, 38.5, 1}};

	private static void balloon(ServerLevel level) {
		List<Vec3> pts = new ArrayList<>();
		for (double[] c : TOUR) {
			pts.add(new Vec3(c[0], FLOOR + c[2], c[1]));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		for (int i = 0; i < path.size(); i++) {
			double fromEnd = Math.min(i, path.size() - 1 - i) * TrackRide.STEP;
			speed[i] = fromEnd < 12 ? 0.12 : 0.32;
		}
		balloon = new TrackRide("balloon", path, speed, "balloon", 3.0F, new Vec3(97.5, FLOOR + 1, 38.5), "Barnaby");
		balloon.seatsPer = 4;
		balloon.seatHeight = 0.35;
		balloon.modelUp = 4.5;
		balloon.upright = true;
		HubPark.box(level, 98, FLOOR, 36, 103, FLOOR, 41, Blocks.STRIPPED_OAK_WOOD.defaultBlockState());
		HubPark.label(level, BALLOON.x, FLOOR + 4, BALLOON.z + 3, "BALLOON TOURS", ChatFormatting.YELLOW, 1.4F);
		HubHosts.add(level, new Vec3(97.5, FLOOR + 1, 36.5), 90, VillagerProfession.CARTOGRAPHER, VillagerType.DESERT,
				new HubHosts.Host("balloon", "Barnaby", "Balloon Tours", 0xFFD040, "minecraft:map",
						"A slow, peaceful hot-air balloon ride high over every island in the park. About a minute and a half. "
								+ "Room for four. Lovely views, no scares!",
						"Balloon Tours: right-click Barnaby for a ride!", HubHosts.rideButtons("Take the balloon tour!"),
						(p, key) -> balloon.board(p, HubHosts.group(p, key))));
		balloon.events.put(path.size() / 4, (lvl, car) -> balloon.tell(lvl, car, "Down there: Spooky Hollow and the Fishing Village"));
		balloon.events.put(path.size() / 2, (lvl, car) -> balloon.tell(lvl, car, "There's the Speedway, and the Stage over that way"));
		balloon.events.put(path.size() * 3 / 4, (lvl, car) -> balloon.tell(lvl, car, "Flume Falls, the Splash Tower, and the Fun Park ahead"));
	}

	// ----- Shared -----

	/** Sit someone in the first free seat of {@code seats}. True if they got one. */
	private static boolean seatOn(ServerPlayer p, UUID[] seats, String message) {
		ServerLevel level = (ServerLevel) p.level();
		if (p.isPassenger()) {
			return false;
		}
		for (UUID id : seats) {
			Entity s = id == null ? null : level.getEntity(id);
			if (s != null && !s.isVehicle()) {
				p.startRiding(s, true, true);
				p.sendOverlayMessage(Component.literal(message + " (crouch to get off)").withStyle(ChatFormatting.YELLOW));
				return true;
			}
		}
		p.sendOverlayMessage(Component.literal("It's full! Try again in a moment.").withStyle(ChatFormatting.YELLOW));
		return false;
	}

	private static boolean on(ServerPlayer p, UUID[] seats) {
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

	private static List<ServerPlayer> riders(ServerLevel level, UUID[] seats) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : seats) {
			Entity s = id == null ? null : level.getEntity(id);
			if (s != null) {
				for (Entity r : s.getPassengers()) {
					if (r instanceof ServerPlayer p) {
						out.add(p);
					}
				}
			}
		}
		return out;
	}

	private static boolean anyone(ServerLevel level, UUID[] seats) {
		return !riders(level, seats).isEmpty();
	}

	private static void tell(List<ServerPlayer> ps, String text) {
		for (ServerPlayer p : ps) {
			p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
		}
	}

	/** Riders who hop off a ride high up float down safely to its exit. */
	private static final Map<UUID, Vec3> ON = new HashMap<>();

	private static void strays(ServerLevel level) {
		Map<UUID, Vec3> now = new HashMap<>();
		for (ServerPlayer p : riders(level, DROP_SEATS)) {
			now.put(p.getUUID(), TOWER_EXIT);
		}
		for (ServerPlayer p : riders(level, SHIP_SEATS)) {
			now.put(p.getUUID(), SHIP_EXIT);
		}
		for (var e : ON.entrySet()) {
			if (!now.containsKey(e.getKey())) {
				ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
				if (p != null && p.level() == level && !p.isPassenger() && p.getY() > FLOOR + 2.5) {
					FunPark.letOff(p, e.getValue(), "You hopped off! Back on the ground.");
				}
			}
		}
		ON.clear();
		ON.putAll(now);
	}

	static void tick(ServerLevel level) {
		tick++;
		tickDrop(level);
		tickShip(level);
		tickCups(level);
		strays(level);
		if (balloon != null) {
			balloon.tick(level);
			if (tick % 4 == 0) {
				for (TrackRide.Car c : balloon.cars) {
					level.sendParticles(ParticleTypes.FLAME, c.pos.x, c.pos.y + 1.6, c.pos.z, 2, 0.05, 0.1, 0.05, 0.01);
				}
			}
		}
	}

	/** A balloon's out over the park (it keeps going wherever its riders are). */
	static boolean busy() {
		return balloon != null && !balloon.cars.isEmpty();
	}

	static void reset() {
		drop = Drop.IDLE;
		shipT = -1;
		CUP_RIDERS.clear();
		ON.clear();
		if (balloon != null) {
			balloon.reset();
		}
	}
}
