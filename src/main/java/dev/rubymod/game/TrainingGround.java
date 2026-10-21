package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.TargetDummyEntity;
import dev.rubymod.entity.VehicleEntity;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the training ground around a point (players face south, +Z):
 *
 * <pre>
 *   x: -64            -12   0   12                 64
 *      GUN RANGE           SPAWN       VEHICLE YARD         z -10..90
 *      (lanes, targets      pad        (jeeps, tanks,
 *       at 10/25/50/80m,               bunker, targets)
 *       damage walls)
 *                          RUNWAY      APRON (3 aircraft)   z 100..350
 *                          (x -6..6)   BOMBING RANGE        z 190..340
 *                          target drones overhead
 * </pre>
 *
 * Everything is placed with ordinary commands so the layout is easy to tweak.
 */
public final class TrainingGround {
	private static final int MIN_X = -64;
	private static final int MAX_X = 64;
	private static final int MIN_Z = -16;
	private static final int MAX_Z = 712;
	private static final int HEIGHT = 30;

	private TrainingGround() {
	}

	public static void build(ServerLevel level, BlockPos origin) {
		MinecraftServer server = level.getServer();
		List<String> cmds = new ArrayList<>();
		int ox = origin.getX();
		int oy = origin.getY();
		int oz = origin.getZ();

		clearOldEntities(level, origin);
		// Load (generate) every chunk first: fill and summon only work in loaded chunks.
		int chunkX1 = (ox + MIN_X) >> 4;
		int chunkX2 = (ox + MAX_X) >> 4;
		int chunkZ1 = (oz + MIN_Z) >> 4;
		int chunkZ2 = (oz + MAX_Z) >> 4;
		for (int cx = chunkX1; cx <= chunkX2; cx++) {
			for (int cz = chunkZ1; cz <= chunkZ2; cz++) {
				level.setChunkForced(cx, cz, true);
				level.getChunk(cx, cz);
			}
		}

		// Flatten: clear the air, lay a grass floor on dirt.
		for (int z = MIN_Z; z <= MAX_Z; z += 8) {
			int z2 = Math.min(MAX_Z, z + 7);
			cmds.add(fill(MIN_X, 0, z, MAX_X, HEIGHT, z2, "air"));
			cmds.add(fill(MIN_X, -4, z, MAX_X, -2, z2, "dirt"));
			cmds.add(fill(MIN_X, -1, z, MAX_X, -1, z2, "grass_block"));
		}

		// Spawn pad.
		cmds.add(fill(-5, -1, -5, 5, -1, 5, "smooth_stone"));
		cmds.add(fill(-1, -1, -1, 1, -1, 1, "red_concrete"));
		label(cmds, 0, 3.2, 0, "RUBY WARS TRAINING", "red", 2.0);
		label(cmds, -8, 2, 0, "<- GUN RANGE", "white", 1.2);
		label(cmds, 8, 2, 0, "VEHICLE YARD ->", "white", 1.2);
		label(cmds, 0, 2, 7, "AIRFIELD (south)", "white", 1.2);
		label(cmds, 0, 2.4, -4, "Tip: /rubywars kit gives you everything, /rubywars rearm refills your aircraft", "gray", 0.6);

		buildGunRange(cmds);
		buildVehicleYard(cmds);
		buildAirfield(cmds);

		cmds.add("setworldspawn ~ ~ ~");
		CommandSourceStack source = server.createCommandSourceStack().withLevel(level).withPosition(Vec3.atBottomCenterOf(origin))
				.withSuppressedOutput();
		for (String cmd : cmds) {
			server.getCommands().performPrefixedCommand(source, cmd);
		}

		// Target drones (they come back 30 seconds after being shot down).
		drone(level, new Vec3(ox, oy + 50, oz + 560), 45);
		drone(level, new Vec3(ox - 20, oy + 80, oz + 620), 70);
		drone(level, new Vec3(ox - 45, oy + 45, oz + 380), 40);
		for (int cx = chunkX1; cx <= chunkX2; cx++) {
			for (int cz = chunkZ1; cz <= chunkZ2; cz++) {
				level.setChunkForced(cx, cz, false);
			}
		}
	}

	private record DroneRespawn(ServerLevel level, Vec3 center, double radius, long at) {
	}

	private static final List<DroneRespawn> RESPAWNS = new ArrayList<>();

	private static void drone(ServerLevel level, Vec3 center, double radius) {
		AircraftEntity drone = AircraftEntity.targetDrone(level, center, radius);
		drone.respawnAfterDeath();
		level.addFreshEntity(drone);
	}

	/** A training drone was shot down: bring it back in 30 seconds. */
	public static void queueRespawn(ServerLevel level, Vec3 center, double radius) {
		RESPAWNS.add(new DroneRespawn(level, center, radius, level.getGameTime() + 600));
	}

	/** Every second. */
	public static void tick() {
		RESPAWNS.removeIf(r -> {
			if (r.level().getGameTime() < r.at()) {
				return false;
			}
			drone(r.level(), r.center(), r.radius());
			return true;
		});
	}

	/** Remove targets, parked vehicles, and drones from an earlier build. */
	private static void clearOldEntities(ServerLevel level, BlockPos origin) {
		AABB area = new AABB(origin.getX() + MIN_X - 40, origin.getY() - 10, origin.getZ() + MIN_Z - 40,
				origin.getX() + MAX_X + 40, origin.getY() + 200, origin.getZ() + MAX_Z + 120);
		for (Entity e : level.getEntities((Entity) null, area,
				e -> (e instanceof TargetDummyEntity || e instanceof VehicleEntity || e instanceof AircraftEntity || e instanceof net.minecraft.world.entity.Display.TextDisplay)
						&& e.getPassengers().stream().noneMatch(p -> p instanceof Player))) {
			e.discard();
		}
	}

	private static void buildGunRange(List<String> cmds) {
		// Firing line with gear chests.
		cmds.add(fill(-58, -1, -6, -14, -1, -1, "smooth_stone"));
		cmds.add(fill(-58, 0, -1, -14, 0, -1, "sandstone_wall"));
		cmds.add(fill(-58, 0, 0, -14, 0, 0, "air"));
		label(cmds, -36, 3.4, -4, "GUN RANGE", "gold", 2.0);
		label(cmds, -36, 2.6, -4, "Chests: guns, ammo, attachments and explosives. Shoot targets and walls.", "gray", 0.6);
		chest(cmds, -30, -4, "Guns", List.of("rubymod:service_pistol", "rubymod:arr_rifle", "rubymod:combat_shotgun", "rubymod:longshot_sniper",
				"rubymod:ruby_blaster", "rubymod:rocket_launcher", "rubymod:rangefinder"));
		chest(cmds, -34, -4, "Ammo", List.of("rubymod:light_ammo*64", "rubymod:light_ammo*64", "rubymod:heavy_ammo*64", "rubymod:heavy_ammo*64",
				"rubymod:shotgun_shell*64", "rubymod:sniper_round*64", "rubymod:ruby_cell*64", "rubymod:rocket*16"));
		chest(cmds, -38, -4, "Attachments", List.of("rubymod:extended_mag*4", "rubymod:red_dot_sight*4", "rubymod:vertical_grip*4",
				"rubymod:suppressor*4", "rubymod:frag_grenade*16", "rubymod:smoke_grenade*16", "rubymod:breaching_charge*8",
				"rubymod:air_strike_flare*4", "rubymod:mortar_shell*16"));
		chest(cmds, -42, -4, "Launchers", List.of("rubymod:skyhammer", "rubymod:aa_missile*8", "rubymod:lancer", "rubymod:at_missile*8",
				"rubymod:c4*8", "rubymod:at_mine*8"));

		// Lanes with targets at four distances.
		int[] distances = {10, 25, 50, 80};
		for (int d : distances) {
			cmds.add(fill(-50, -1, d - 1, -18, -1, d + 1, "coarse_dirt"));
			for (int x = -48; x <= -20; x += 7) {
				cmds.add("summon rubymod:target_dummy ~" + x + " ~ ~" + d + " {Rotation:[180f,0f],ShowArms:1b}");
			}
			label(cmds, -15, 2.2, d, d + " m", "yellow", 1.5);
		}
		// Lane dividers.
		for (int x = -52; x <= -16; x += 36) {
			cmds.add(fill(x, 0, 2, x, 0, 88, "oak_fence"));
		}
		cmds.add(fill(-52, 0, 88, -16, 6, 89, "hay_block")); // Backstop.

		// Block damage test walls: softest to toughest.
		String[][] walls = {{"dirt", "Dirt"}, {"oak_planks", "Planks"}, {"cobblestone", "Cobble"}, {"stone_bricks", "Stone Brick"},
				{"rubymod:concrete_wall", "Concrete"}, {"rubymod:reinforced_concrete", "Reinforced"}};
		int x = -63;
		for (String[] wall : walls) {
			cmds.add(fill(x, 0, 20, x + 1, 3, 20, wall[0]));
			label(cmds, x + 1, 4.6, 20, wall[1], "white", 0.8);
			x += 2;
		}
		label(cmds, -57, 5.6, 20, "BLOCK DAMAGE WALLS", "gold", 1.0);
		cmds.add(fill(-63, -1, 0, -53, -1, 6, "smooth_stone"));
	}

	private static void buildVehicleYard(List<String> cmds) {
		cmds.add(fill(14, -1, -6, 60, -1, 90, "coarse_dirt"));
		cmds.add(fill(14, -1, -6, 60, -1, 6, "gray_concrete"));
		label(cmds, 37, 3.4, -4, "VEHICLE YARD", "gold", 2.0);
		label(cmds, 37, 2.6, -4, "Right-click to drive. Tank: left-click fires where the turret points.", "gray", 0.6);
		cmds.add("summon rubymod:jeep ~18 ~ ~2 {Rotation:[0f,0f]}");
		cmds.add("summon rubymod:jeep ~25 ~ ~2 {Rotation:[0f,0f]}");
		cmds.add("summon rubymod:tank ~35 ~ ~2 {Rotation:[0f,0f]}");
		cmds.add("summon rubymod:tank ~45 ~ ~2 {Rotation:[0f,0f]}");
		// Obstacles: sandbag lines, a ramp, a bunker to shell.
		cmds.add(fill(18, 0, 20, 30, 0, 20, "rubymod:sandbags"));
		cmds.add(fill(38, 0, 26, 52, 0, 26, "rubymod:sandbags"));
		for (int i = 0; i < 4; i++) {
			cmds.add(fill(20, i, 40 + i * 2, 26, i, 41 + i * 2, "smooth_stone_slab"));
			cmds.add(fill(20, i, 41 + i * 2, 26, i, 41 + i * 2, "smooth_stone"));
		}
		cmds.add(fill(40, 0, 60, 50, 4, 68, "cobblestone"));
		cmds.add(fill(41, 0, 61, 49, 3, 67, "air"));
		label(cmds, 45, 6.5, 60, "BUNKER", "white", 1.0);
		for (int x = 18; x <= 54; x += 9) {
			cmds.add("summon rubymod:target_dummy ~" + x + " ~ ~80 {Rotation:[180f,0f],ShowArms:1b}");
		}
		label(cmds, 37, 3, 80, "TARGETS", "white", 1.0);
		// Anti-tank mine lane: drive a tank over it.
		cmds.add(fill(54, -1, 30, 58, -1, 70, "gravel"));
		for (int z = 40; z <= 64; z += 12) {
			cmds.add("setblock ~56 ~ ~" + z + " rubymod:at_mine");
		}
		label(cmds, 56, 2.5, 32, "AT MINE LANE", "red", 1.0);
	}

	private static void buildAirfield(List<String> cmds) {
		// Runway: 13 wide, 600 long, with a dashed center line and threshold bars.
		for (int z = 100; z < 700; z += 150) {
			cmds.add(fill(-7, -1, z, 7, -1, z + 149, "gray_concrete"));
		}
		for (int z = 106; z < 696; z += 8) {
			cmds.add(fill(0, -1, z, 0, -1, z + 3, "white_concrete"));
		}
		for (int x = -6; x <= 6; x += 2) {
			cmds.add(fill(x, -1, 101, x, -1, 104, "white_concrete"));
			cmds.add(fill(x, -1, 695, x, -1, 698, "white_concrete"));
		}
		// Edge lights.
		for (int z = 100; z <= 700; z += 12) {
			cmds.add("setblock ~-8 ~-1 ~" + z + " sea_lantern");
			cmds.add("setblock ~8 ~-1 ~" + z + " sea_lantern");
		}
		label(cmds, 0, 3.4, 98, "RUNWAY 18", "gold", 2.0);
		label(cmds, 0, 2.6, 98, "Taxi on, full throttle (W), at ROTATE look up. G: gear, T: designate, V: weapon cam, R: weapon, Space: flares", "gray", 0.6);

		// Apron with every aircraft, and an Airfield Pad that rearms them.
		cmds.add(fill(8, -1, 100, 60, -1, 150, "light_gray_concrete"));
		cmds.add(fill(8, -1, 104, 60, -1, 104, "yellow_concrete"));
		label(cmds, 34, 3.4, 98, "APRON", "gold", 1.6);
		cmds.add("summon rubymod:fighter_jet ~16 ~ ~114 {Rotation:[90f,0f]}");
		cmds.add("summon rubymod:attack_jet ~28 ~ ~114 {Rotation:[90f,0f]}");
		cmds.add("summon rubymod:bomber ~44 ~ ~118 {Rotation:[90f,0f]}");
		cmds.add("summon rubymod:attack_heli ~16 ~ ~136 {Rotation:[90f,0f]}");
		cmds.add("summon rubymod:fighter_jet ~30 ~ ~138 {Rotation:[90f,0f]}");
		cmds.add("setblock ~56 ~-1 ~140 rubymod:airfield_pad");
		label(cmds, 16, 4.2, 108, "FIGHTER - cannon, air-to-air missiles", "white", 0.7);
		label(cmds, 28, 4.6, 107, "ATTACK JET - cannon, guided missiles, bombs", "white", 0.7);
		label(cmds, 44, 5.4, 108, "BOMBER - 16 guided bombs", "white", 0.7);
		label(cmds, 16, 5.0, 130, "HELICOPTER - no runway needed", "white", 0.7);
		label(cmds, 56, 2.2, 140, "AIRFIELD PAD - park here to rearm", "yellow", 0.7);

		// Bombing range east of the runway: ground targets inside a ring.
		cmds.add(fill(20, -1, 300, 64, -1, 460, "coarse_dirt"));
		for (int i = 0; i < 48; i++) {
			double a = i * Math.PI * 2 / 48;
			int x = 42 + (int) Math.round(Math.cos(a) * 18);
			int z = 380 + (int) Math.round(Math.sin(a) * 18);
			cmds.add("setblock ~" + x + " ~-1 ~" + z + " red_concrete");
		}
		cmds.add("summon rubymod:tank ~38 ~ ~372 {Rotation:[30f,0f]}");
		cmds.add("summon rubymod:tank ~48 ~ ~388 {Rotation:[200f,0f]}");
		cmds.add("summon rubymod:jeep ~34 ~ ~388 {Rotation:[120f,0f]}");
		cmds.add("summon rubymod:jeep ~50 ~ ~370 {Rotation:[300f,0f]}");
		cmds.add(fill(38, 0, 416, 46, 3, 424, "cobblestone"));
		cmds.add(fill(39, 0, 417, 45, 2, 423, "air"));
		for (int x = 32; x <= 52; x += 5) {
			cmds.add("summon rubymod:target_dummy ~" + x + " ~ ~380 {Rotation:[0f,0f],ShowArms:1b}");
		}
		label(cmds, 42, 8, 380, "BOMBING RANGE", "red", 3.0);

		// Air defense range west of the runway: a Flak Cannon and a SAM Site guarding a drone.
		cmds.add(fill(-60, -1, 340, -30, -1, 420, "coarse_dirt"));
		cmds.add("setblock ~-45 ~ ~370 rubymod:flak_cannon{ammo:512}");
		cmds.add("setblock ~-45 ~ ~390 rubymod:sam_site{charges:3}");
		label(cmds, -45, 4, 380, "AIR DEFENSE RANGE", "red", 2.0);
		label(cmds, -45, 3.2, 380, "Fly within 180 blocks to practice dodging: Space drops flares", "gray", 0.6);
	}

	// ----- Command helpers (all coordinates relative to the origin) -----

	private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		return "fill ~" + x1 + " ~" + y1 + " ~" + z1 + " ~" + x2 + " ~" + y2 + " ~" + z2 + " " + block;
	}

	private static void label(List<String> cmds, double x, double y, double z, String text, String color, double scale) {
		cmds.add("summon text_display ~" + x + " ~" + y + " ~" + z + " {billboard:\"center\",text:{text:\"" + text + "\",color:\"" + color
				+ "\",bold:true},background:1342177280,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:["
				+ scale + "f," + scale + "f," + scale + "f]}}");
	}

	private static void chest(List<String> cmds, int x, int z, String name, List<String> items) {
		StringBuilder nbt = new StringBuilder("{Items:[");
		for (int i = 0; i < items.size(); i++) {
			String[] parts = items.get(i).split("\\*");
			int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
			if (i > 0) {
				nbt.append(',');
			}
			nbt.append("{Slot:").append(i).append("b,id:\"").append(parts[0]).append("\",count:").append(count).append('}');
		}
		nbt.append("]}");
		cmds.add("setblock ~" + x + " ~ ~" + z + " chest[facing=south]" + nbt);
		label(cmds, x + 0.5, 1.6, z + 0.5, name, "aqua", 0.8);
	}
}
