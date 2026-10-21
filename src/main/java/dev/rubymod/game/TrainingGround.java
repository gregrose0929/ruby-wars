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
	private static final int MAX_Z = 352;
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

		// Target drones circling over the far end of the runway.
		level.addFreshEntity(AircraftEntity.targetDrone(level, new Vec3(ox, oy + 45, oz + 260), 45));
		level.addFreshEntity(AircraftEntity.targetDrone(level, new Vec3(ox - 20, oy + 70, oz + 300), 70));
		for (int cx = chunkX1; cx <= chunkX2; cx++) {
			for (int cz = chunkZ1; cz <= chunkZ2; cz++) {
				level.setChunkForced(cx, cz, false);
			}
		}
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
	}

	private static void buildAirfield(List<String> cmds) {
		// Runway: 13 wide, 250 long, with a dashed center line and threshold bars.
		cmds.add(fill(-7, -1, 100, 7, -1, 350, "gray_concrete"));
		for (int z = 106; z < 346; z += 8) {
			cmds.add(fill(0, -1, z, 0, -1, z + 3, "white_concrete"));
		}
		for (int x = -6; x <= 6; x += 2) {
			cmds.add(fill(x, -1, 101, x, -1, 104, "white_concrete"));
			cmds.add(fill(x, -1, 346, x, -1, 349, "white_concrete"));
		}
		// Edge lights.
		for (int z = 100; z <= 350; z += 10) {
			cmds.add("setblock ~-8 ~-1 ~" + z + " sea_lantern");
			cmds.add("setblock ~8 ~-1 ~" + z + " sea_lantern");
		}
		label(cmds, 0, 3.4, 98, "RUNWAY 18", "gold", 2.0);
		label(cmds, 0, 2.6, 98, "Taxi on, full throttle (W), at ROTATE pull up (look up). R: weapon, Space: flares, Sneak: eject", "gray", 0.6);

		// Apron and taxiway to the runway threshold.
		cmds.add(fill(8, -1, 100, 50, -1, 140, "light_gray_concrete"));
		cmds.add(fill(8, -1, 104, 50, -1, 104, "yellow_concrete"));
		label(cmds, 30, 3.4, 98, "APRON", "gold", 1.6);
		cmds.add("summon rubymod:fighter_jet ~16 ~ ~112 {Rotation:[90f,0f]}");
		cmds.add("summon rubymod:attack_jet ~27 ~ ~112 {Rotation:[90f,0f]}");
		cmds.add("summon rubymod:bomber ~41 ~ ~114 {Rotation:[90f,0f]}");
		cmds.add("summon rubymod:fighter_jet ~16 ~ ~128 {Rotation:[90f,0f]}");
		label(cmds, 16, 3.6, 108, "FIGHTER - cannon, air-to-air missiles", "white", 0.7);
		label(cmds, 27, 4.0, 107, "ATTACK JET - cannon, guided missiles, bombs", "white", 0.7);
		label(cmds, 41, 4.6, 108, "BOMBER - 16 bombs (CCIP pipper)", "white", 0.7);

		// Bombing range east of the runway: ground targets inside a ring.
		cmds.add(fill(20, -1, 200, 60, -1, 330, "coarse_dirt"));
		for (int i = 0; i < 48; i++) {
			double a = i * Math.PI * 2 / 48;
			int x = 40 + (int) Math.round(Math.cos(a) * 18);
			int z = 265 + (int) Math.round(Math.sin(a) * 18);
			cmds.add("setblock ~" + x + " ~-1 ~" + z + " red_concrete");
		}
		cmds.add("summon rubymod:tank ~36 ~ ~258 {Rotation:[30f,0f]}");
		cmds.add("summon rubymod:tank ~46 ~ ~272 {Rotation:[200f,0f]}");
		cmds.add("summon rubymod:jeep ~32 ~ ~272 {Rotation:[120f,0f]}");
		cmds.add("summon rubymod:jeep ~48 ~ ~256 {Rotation:[300f,0f]}");
		cmds.add(fill(36, 0, 296, 44, 3, 304, "cobblestone"));
		cmds.add(fill(37, 0, 297, 43, 2, 303, "air"));
		for (int x = 30; x <= 50; x += 5) {
			cmds.add("summon rubymod:target_dummy ~" + x + " ~ ~265 {Rotation:[0f,0f],ShowArms:1b}");
		}
		label(cmds, 40, 8, 265, "BOMBING RANGE", "red", 3.0);
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
