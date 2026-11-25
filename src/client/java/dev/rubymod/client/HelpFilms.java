package dev.rubymod.client;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import dev.rubymod.RubyMod;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The help menu's films (How It Works): a short film of each Armory item in use, made by the test client ("clips"
 * mode) on a stage in the sky dressed as a place (a base with its wall and Heart, a firing range, a river, a runway, a
 * workshop), with trees round the edge. A film camera (an invisible armor stand that lives only on the client, so it
 * glides smoothly, and can follow a vehicle or a flyer) watches an actor (a mannequin with your look that copies you
 * every tick: the game doesn't draw you while the camera is elsewhere). Items whose point is what you see (a scope, the
 * Rangefinder, the goggles, the Monitor, the drones, the Prospector Pick) cut to your own eyes, with the screen as you'd
 * see it. A frame is taken every tick (20 a second); ./gradlew makeFilms makes them into videos (MakeFilms).
 *
 * Every film is real gameplay: placing, using, firing and riding go through the game itself; what the camera can't
 * follow by itself (a vehicle's driving, a walk, a mine meant for players) is moved along by the film.
 *
 * RUBYMOD_CLIPS (a comma list of film ids or sections) films just those.
 */
final class HelpFilms {
	private HelpFilms() {
	}

	// ----- State -----

	private static String filming;
	private static int frame;
	private static int frameTicks;
	private static int every = 1;
	/** At most this many frames a film. */
	private static final int MAX_FRAMES = 420;
	private static BlockPos base;
	private static int actorId = -1;
	private static Mannequin actor;
	private static MinecraftServer srv;
	/** Walking (or flying) by hand: this much a tick, and whether to turn to face that way. */
	private static Vec3 walking;
	private static boolean walkFacing = true;
	private static final List<Mover> MOVERS = new ArrayList<>();
	private static final List<String> INDEX = new ArrayList<>();
	private static final String ONLY = System.getenv("RUBYMOD_CLIPS");

	/** Something moved along by hand each tick (a vehicle being driven): speed, heading and climb. */
	private static final class Mover {
		final Entity entity;
		double speed;
		double max;
		double accel;
		float yaw;
		float turn;
		double climb;
		int climbAfter;
		int left;
		int age;
		/** A monster walking (by itself, with its own legs) to here. */
		Vec3 dest;

		Mover(Entity entity, double speed, float yaw, int left) {
			this.entity = entity;
			this.speed = speed;
			this.max = speed;
			this.yaw = yaw;
			this.left = left;
		}
	}

	// ----- The camera -----

	/**
	 * Where the camera is: its eyes at {@code eye}, looking at {@code look} (offsets from the stage's middle); or, if
	 * {@code follow}, gliding after the actor (or what they're riding), {@code eye} away from it ({@code chase}: turned
	 * with its heading, so it stays behind).
	 */
	private record Cam(Vec3 eye, Vec3 look, boolean follow, boolean chase) {
	}

	private static Cam at(double ex, double ey, double ez, double lx, double ly, double lz) {
		return new Cam(new Vec3(ex, ey, ez), new Vec3(lx, ly, lz), false, false);
	}

	private static final Cam SIDE = at(4.5, 2.3, 0.6, -0.5, 0.9, 3.5);
	private static final Cam CLOSE = at(3.6, 2.2, -0.6, -0.3, 1.0, 2.2);
	private static final Cam WIDE = at(6.5, 3.6, -2.0, 0, 1.0, 6);
	private static final Cam SHOOT = at(3.6, 2.5, -2.8, -0.5, 1.4, 8);
	private static final Cam UP = at(8, 2.5, -5, 0, 9, 8);
	private static final Cam FOLLOW = new Cam(new Vec3(7, 3.2, -4), Vec3.ZERO, true, false);
	private static final Cam CHASE = new Cam(new Vec3(3.5, 3.5, -9), Vec3.ZERO, true, true);
	private static final Cam FLY = new Cam(new Vec3(12, 5, -12), Vec3.ZERO, true, false);

	private static Cam cam = SIDE;
	/** The film camera (client only), and where its eyes are now. */
	private static ArmorStand camera;
	private static int cameraCount;
	private static Vec3 camEye;

	private static void placeCamera(Minecraft mc) {
		if (camera == null || base == null) {
			return;
		}
		Vec3 middle = Vec3.atBottomCenterOf(base.above());
		Vec3 eye;
		Vec3 look;
		if (cam.follow()) {
			Entity t = mc.level.getEntity(actorId);
			if (t != null && t.getVehicle() != null) {
				t = t.getVehicle();
			}
			Vec3 target = t == null ? middle : t.position().add(0, Math.min(2.0, t.getBbHeight() * 0.6), 0);
			Vec3 off = cam.eye();
			if (cam.chase() && t != null) {
				double r = Math.toRadians(t.getYRot());
				double cos = Math.cos(r);
				double sin = Math.sin(r);
				off = new Vec3(off.x * cos - off.z * sin, off.y, off.x * sin + off.z * cos);
			}
			Vec3 want = target.add(off);
			eye = camEye == null ? want : camEye.lerp(want, 0.2);
			look = target;
		} else {
			eye = middle.add(cam.eye());
			look = middle.add(cam.look());
		}
		camEye = eye;
		Vec3 to = look.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
		camera.setOldPosAndRot();
		camera.setPos(eye.x, eye.y - camera.getEyeHeight(), eye.z);
		camera.setYRot(yaw);
		camera.setXRot(pitch);
		camera.setYHeadRot(yaw);
		camera.yHeadRotO = camera.yHeadRot;
	}

	/** Switch to another camera mid-film. */
	private static void camera(Cam c) {
		then(0, mc -> {
			cam = c;
			camEye = null;
			placeCamera(mc);
			if (camera != null) {
				camera.setOldPosAndRot();
			}
		});
	}

	/** Cut to your own eyes (the screen as you'd see it, the HUD and all), or back to the film camera. */
	private static void yourEyes(boolean on) {
		then(0, mc -> {
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			if (on) {
				mc.setCameraEntity(mc.player);
				if (mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			} else {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
				if (camera != null) {
					mc.setCameraEntity(camera);
				}
			}
		});
	}

	// ----- The per-tick work -----

	static void tick(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null && base != null) {
			server.execute(HelpFilms::serverTick);
		}
		if (camera != null && mc.level != null) {
			placeCamera(mc);
		}
		if (filming != null && frameTicks++ % every == 0 && frame < MAX_FRAMES) {
			AutoTest.shot(mc, "clip-" + filming + "-" + String.format(java.util.Locale.ROOT, "%03d", frame++));
		}
	}

	private static void serverTick() {
		if (srv == null) {
			return;
		}
		ServerPlayer p = AutoTest.me(srv);
		if (walking != null) {
			float yaw = walkFacing && walking.horizontalDistanceSqr() > 1e-6 ? (float) Math.toDegrees(Math.atan2(-walking.x, walking.z)) : p.getYRot();
			p.teleportTo(p.level(), p.getX() + walking.x, p.getY() + walking.y, p.getZ() + walking.z, Set.of(), yaw, p.getXRot(), false);
		}
		Mannequin a = actor;
		if (a != null && a.isAlive()) {
			if (!a.isPassenger()) {
				// A step behind you, so your own shots and throws don't hit it.
				double r = Math.toRadians(p.getYRot());
				a.snapTo(p.getX() + Math.sin(r) * 0.7, p.getY(), p.getZ() - Math.cos(r) * 0.7, p.getYRot(), p.getXRot());
				a.setYHeadRot(p.getYHeadRot());
				a.setYBodyRot(p.getYRot());
			}
			for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
					EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				ItemStack have = p.getItemBySlot(slot);
				if (!ItemStack.matches(have, a.getItemBySlot(slot))) {
					a.setItemSlot(slot, have.copy());
				}
			}
			a.setPose(p.isShiftKeyDown() ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
		}
		for (Iterator<Mover> it = MOVERS.iterator(); it.hasNext();) {
			Mover m = it.next();
			if (!m.entity.isAlive() || m.left-- <= 0) {
				it.remove();
				continue;
			}
			m.age++;
			if (m.dest != null && m.entity instanceof net.minecraft.world.entity.Mob mob) {
				if (m.age % 10 == 1) {
					mob.getNavigation().moveTo(m.dest.x, m.dest.y, m.dest.z, 1.0);
				}
				continue;
			}
			m.speed = Math.min(m.max, m.speed + m.accel);
			m.yaw += m.turn;
			double rad = Math.toRadians(m.yaw);
			double dx = -Math.sin(rad) * m.speed;
			double dz = Math.cos(rad) * m.speed;
			double dy = m.age > m.climbAfter ? m.climb : 0;
			Entity e = m.entity;
			e.snapTo(e.getX() + dx, e.getY() + dy, e.getZ() + dz, m.yaw, e.getXRot());
			e.setYRot(m.yaw);
			if (e instanceof LivingEntity living) {
				living.setYHeadRot(m.yaw);
				living.setYBodyRot(m.yaw);
			}
			e.setDeltaMovement(dx, dy, dz);
		}
	}

	// ----- Building blocks -----

	static Item item(String id) {
		return BuiltInRegistries.ITEM.getValue(id.contains(":") ? Identifier.parse(id) : RubyMod.id(id));
	}

	static Block block(String id) {
		return BuiltInRegistries.BLOCK.getValue(id.contains(":") ? Identifier.parse(id) : RubyMod.id(id));
	}

	static ItemStack stack(String id, int count) {
		return new ItemStack(item(id), count);
	}

	private static BlockPos at(int x, int y, int z) {
		return base.offset(x, y, z);
	}

	private static void put(ServerLevel level, int x, int y, int z, BlockState state) {
		level.setBlockAndUpdate(at(x, y, z), state);
	}

	/** A block, turned to face {@code facing} if it can. */
	private static BlockState facing(String id, Direction facing) {
		BlockState s = block(id).defaultBlockState();
		if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) {
			s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
		} else if (s.hasProperty(BlockStateProperties.FACING)) {
			s = s.setValue(BlockStateProperties.FACING, facing);
		}
		return s;
	}

	/** A box of one block (inclusive corners). */
	private static void box(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					put(level, x, y, z, state);
				}
			}
		}
	}

	/** Films whose stage is your base (defenses, traps, locks and the like only work in one). */
	private static final Set<String> IN_BASE = Set.of("sentry_turret", "tesla_coil", "mortar", "missile_silo", "flak_cannon", "sam_site",
			"point_defense", "missile_interceptor", "shield_generator", "radar_station", "landmine", "claymore", "at_mine", "holo_camo", "blast_door",
			"keycard_door", "keycard_reader", "security_camera", "laser_tripwire", "seismic_sensor", "alarm_block", "guard_contract",
			"at_guard_contract", "ruby_drill", "blueprint", "armory", "heart_upgrade", "blueprint_watchtower", "blueprint_bunker",
			"blueprint_gatehouse", "blueprint_wall_section");
	private static String staging;
	/** Where the Heart stands in a base film (on show, by the wall). */
	private static final int[] HEART = {-7, 1, 12};

	/** Your Heart (so the stage is your base), or no base at all (so blasts break things). */
	private static void heart(ServerPlayer p, boolean wanted) {
		BlockPos h = at(HEART[0], HEART[1], HEART[2]);
		var data = dev.rubymod.game.SeasonData.get();
		var claim = data.claimOf(p.getUUID());
		if (!wanted) {
			if (claim != null) {
				data.claims.remove(claim.owner);
				dev.rubymod.game.SeasonData.markDirty();
			}
			return;
		}
		p.level().setBlockAndUpdate(h, dev.rubymod.registry.ModBlocks.RUBY_HEART.defaultBlockState());
		if (claim == null || claim.destroyed || !claim.pos().equals(h)) {
			if (claim != null) {
				data.claims.remove(claim.owner);
			}
			dev.rubymod.game.Claims.onHeartPlaced(p.level(), p, h);
		}
		claim = data.claimOf(p.getUUID());
		if (claim != null && claim.tier != 1) {
			claim.tier = 1;
			claim.hp = claim.maxHp();
		}
	}

	/** A vehicle (or other ride) put down by the stage at (x, z), facing south. */
	private static void ride(ServerPlayer p, String id, double x, double z) {
		var type = BuiltInRegistries.ENTITY_TYPE.getValue(RubyMod.id(id));
		Entity e = type.create(p.level(), EntitySpawnReason.COMMAND);
		if (e != null) {
			e.snapTo(base.getX() + 0.5 + x, base.getY() + 1, base.getZ() + 0.5 + z, 0, 0);
			p.level().addFreshEntity(e);
		}
	}

	/** A machine or defense of yours, put down by the stage (it's yours, as if you'd placed it). */
	private static void owned(ServerPlayer p, int x, int y, int z, String id, Direction facing) {
		put(p.level(), x, y, z, facing(id, facing));
		var be = p.level().getBlockEntity(at(x, y, z));
		if (be instanceof dev.rubymod.industry.MachineBlockEntity m) {
			m.setOwner(p.getUUID());
		}
	}

	private static void time(MinecraftServer server, String when) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set " + when);
	}

	private static void war() {
		dev.rubymod.game.SeasonData.get().phase = dev.rubymod.game.Phase.WAR;
	}

	private static final Direction S = Direction.SOUTH;
	private static final Direction N = Direction.NORTH;

	// ----- Sets -----

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	/** A tree: a trunk, and a round crown of leaves. */
	private static void tree(ServerLevel l, int x, int z, int height) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = height - 2; dy <= height + 1; dy++) {
					int r = Math.abs(dx) + Math.abs(dz);
					if (r <= (dy > height ? 1 : dy == height - 2 ? 3 : 4) && !(Math.abs(dx) == 2 && Math.abs(dz) == 2)) {
						put(l, x + dx, dy, z + dz, leaves);
					}
				}
			}
		}
		box(l, x, 1, z, x, height, z, Blocks.OAK_LOG.defaultBlockState());
	}

	/** Trees round the edge, flowers and grass about: a place, not a test floor. The middle's left clear. */
	private static void scenery(ServerLevel l) {
		int[][] trees = {{-18, 4, 6}, {-17, 16, 5}, {-18, 28, 6}, {-16, 38, 5}, {18, 6, 5}, {17, 18, 6}, {18, 30, 5}, {16, 40, 6}, {-8, 42, 5},
				{5, 43, 6}, {-2, 42, 5}};
		for (int[] t : trees) {
			tree(l, t[0], t[1], t[2]);
		}
		BlockState[] small = {Blocks.SHORT_GRASS.defaultBlockState(), Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(),
				Blocks.SHORT_GRASS.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState()};
		for (int x = -19; x <= 19; x++) {
			for (int z = -13; z <= 43; z++) {
				int h = (x * 73856093) ^ (z * 19349663);
				boolean middle = Math.abs(x) <= 9 && z >= -3 && z <= 22;
				if (!middle && Math.floorMod(h, 7) == 0 && l.getBlockState(at(x, 1, z)).isAir() && l.getBlockState(at(x, 0, z)).is(Blocks.GRASS_BLOCK)) {
					put(l, x, 1, z, small[Math.floorMod(h >> 3, small.length)]);
				}
			}
		}
	}

	/** Your base: a wall across the back with a gateway and battlements, a tower at each end, lamps, and the Heart. */
	private static void baseSet(ServerLevel l) {
		BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
		box(l, -12, 1, 18, 12, 3, 18, wall);
		box(l, -1, 1, 18, 1, 3, 18, AIR);
		for (int x = -12; x <= 12; x += 2) {
			if (Math.abs(x) > 1) {
				put(l, x, 4, 18, wall);
			}
		}
		box(l, -14, 1, 17, -12, 6, 19, wall);
		box(l, 12, 1, 17, 14, 6, 19, wall);
		for (int x : new int[] {-3, 3}) {
			box(l, x, 1, 16, x, 2, 16, Blocks.SPRUCE_FENCE.defaultBlockState());
			put(l, x, 3, 16, Blocks.LANTERN.defaultBlockState());
		}
		box(l, -9, 0, 10, -5, 0, 14, Blocks.POLISHED_ANDESITE.defaultBlockState());
		box(l, -8, 1, 15, -6, 1, 15, block("sandbags").defaultBlockState());
	}

	/** A firing range: a wall of targets, hay bales either side, and a sandbag line to shoot over. */
	private static void rangeSet(ServerLevel l) {
		box(l, -3, 1, 12, 3, 3, 12, Blocks.TARGET.defaultBlockState());
		box(l, -5, 1, 12, -4, 2, 12, Blocks.HAY_BLOCK.defaultBlockState());
		box(l, 4, 1, 12, 5, 2, 12, Blocks.HAY_BLOCK.defaultBlockState());
		box(l, -3, 0, 2, 3, 0, 11, Blocks.COARSE_DIRT.defaultBlockState());
		box(l, -2, 1, 2, 2, 1, 2, block("sandbags").defaultBlockState());
		box(l, 0, 1, 2, 0, 1, 2, AIR);
	}

	/** A river with sandy banks, reeds and a little dock. */
	private static void riverSet(ServerLevel l) {
		box(l, -10, -3, 3, 10, -3, 44, Blocks.SAND.defaultBlockState());
		box(l, -9, -2, 3, 9, 0, 44, Blocks.WATER.defaultBlockState());
		box(l, -11, -2, 3, -10, 0, 44, Blocks.SAND.defaultBlockState());
		box(l, 10, -2, 3, 11, 0, 44, Blocks.SAND.defaultBlockState());
		for (int z = 6; z <= 40; z += 5) {
			box(l, -11, 1, z, -11, 2, z, Blocks.SUGAR_CANE.defaultBlockState());
			box(l, 11, 1, z + 2, 11, 2, z + 2, Blocks.SUGAR_CANE.defaultBlockState());
		}
		box(l, -2, 0, 1, 2, 0, 2, Blocks.SPRUCE_PLANKS.defaultBlockState());
	}

	/** A runway: a long grey strip with a dashed centre line and lights either side. */
	private static void runwaySet(ServerLevel l) {
		box(l, -4, 0, -2, 4, 0, 44, block("minecraft:gray_concrete").defaultBlockState());
		for (int z = 0; z <= 44; z += 4) {
			box(l, 0, 0, z, 0, 0, z + 1, block("minecraft:white_concrete").defaultBlockState());
		}
		for (int z = -2; z <= 44; z += 6) {
			put(l, -5, 0, z, Blocks.SEA_LANTERN.defaultBlockState());
			put(l, 5, 0, z, Blocks.SEA_LANTERN.defaultBlockState());
		}
	}

	/** A workshop: a stone floor, lamps on posts, and a solar panel for power. */
	private static void workshopSet(ServerPlayer p) {
		ServerLevel l = p.level();
		box(l, -6, 0, 0, 6, 0, 9, Blocks.SMOOTH_STONE.defaultBlockState());
		for (int x : new int[] {-6, 6}) {
			for (int z : new int[] {0, 9}) {
				box(l, x, 1, z, x, 2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
				put(l, x, 3, z, Blocks.LANTERN.defaultBlockState());
			}
		}
		owned(p, -5, 1, 5, "solar_panel", S);
		owned(p, -5, 1, 7, "solar_panel", S);
	}

	// ----- Steps (each one a step of the test client's script) -----

	private static void then(int delay, Consumer<Minecraft> action) {
		AutoTest.then(delay, action);
	}

	private static void wait(int ticks) {
		then(ticks, mc -> {
		});
	}

	private static void server(Consumer<ServerPlayer> action) {
		then(0, mc -> AutoTest.onServer(mc, s -> action.accept(AutoTest.me(s))));
	}

	private static void holding(String id, int count) {
		server(p -> {
			p.getInventory().setItem(0, id == null ? ItemStack.EMPTY : stack(id, count));
			p.getInventory().setSelectedSlot(0);
		});
		then(2, mc -> mc.player.getInventory().setSelectedSlot(0));
	}

	private static void holding(String id) {
		holding(id, 1);
	}

	private static void offhand(String id, int count) {
		server(p -> p.setItemSlot(EquipmentSlot.OFFHAND, stack(id, count)));
	}

	private static void wear(EquipmentSlot slot, String id) {
		server(p -> p.setItemSlot(slot, stack(id, 1)));
	}

	/** Stand here (offsets from the middle), facing south. */
	private static void stand(double x, double z) {
		server(p -> p.teleportTo(p.level(), base.getX() + 0.5 + x, base.getY() + 1, base.getZ() + 0.5 + z, Set.of(), 0, 15, false));
	}

	/** Turn to look at this spot (offsets from the stage's middle). */
	private static void face(double x, double y, double z) {
		then(0, mc -> {
			Vec3 target = Vec3.atBottomCenterOf(base).add(x, y, z);
			Vec3 to = target.subtract(mc.player.getEyePosition());
			float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
			float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
			AutoTest.onServer(mc, s -> {
				ServerPlayer p = AutoTest.me(s);
				p.teleportTo(p.level(), p.getX(), p.getY(), p.getZ(), Set.of(), yaw, pitch, false);
			});
		});
	}

	/** Walk this way for a while (south by default), turning to face it. */
	private static void walk(int ticks, double dx, double dy, double dz) {
		then(0, mc -> {
			walking = new Vec3(dx, dy, dz);
			walkFacing = true;
		});
		then(ticks, mc -> walking = null);
	}

	private static void walk(int ticks) {
		walk(ticks, 0, 0, 0.2);
	}

	/** Step back (keep facing the way you are). */
	private static void back(int ticks) {
		then(0, mc -> {
			walking = new Vec3(0, 0, -0.18);
			walkFacing = false;
		});
		then(ticks, mc -> walking = null);
	}

	private static void swing() {
		Mannequin a = actor;
		if (a != null && srv != null) {
			srv.execute(() -> a.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true));
		}
	}

	/** Right-click a block's face (as if you'd clicked it). */
	private static void click(int x, int y, int z, Direction face) {
		then(0, mc -> {
			BlockPos pos = at(x, y, z);
			var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5)), face, pos, false);
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
			swing();
		});
	}

	/** Put what you're holding down on the floor here. */
	private static void clickFloor(int x, int z) {
		click(x, 0, z, Direction.UP);
	}

	private static void place(String id, int x, int z) {
		holding(id, 16);
		wait(4);
		clickFloor(x, z);
	}

	/** Right-click with what you're holding (in the air). */
	private static void use() {
		then(0, mc -> {
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			swing();
		});
	}

	/** Hold right-click for a while, then let go. */
	private static void holdUse(int ticks) {
		then(0, mc -> {
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			mc.options.keyUse.setDown(true);
		});
		then(ticks, mc -> mc.options.keyUse.setDown(false));
	}

	private static void sneak(boolean on) {
		then(0, mc -> {
			mc.player.setShiftKeyDown(on);
			AutoTest.onServer(mc, s -> AutoTest.me(s).setShiftKeyDown(on));
		});
	}

	/** Pull the trigger, {@code shots} times, {@code gap} ticks apart (guns and the Rocket Launcher). */
	private static void fire(int shots, int gap) {
		for (int i = 0; i < shots; i++) {
			then(i == 0 ? 0 : gap, mc -> AutoTest.onServer(mc, s -> {
				ServerPlayer p = AutoTest.me(s);
				ItemStack held = p.getMainHandItem();
				if (held.getItem() instanceof dev.rubymod.gun.GunItem gun) {
					dev.rubymod.gun.Gunfire.tryFire(p.level(), p, held, gun);
				} else if (held.getItem() instanceof dev.rubymod.item.RocketLauncherItem) {
					dev.rubymod.item.RocketLauncherItem.fire(p, held);
				}
			}));
		}
	}

	/** The nearest entity (on the client) that passes {@code which}. */
	private static Entity nearest(Minecraft mc, Predicate<Entity> which) {
		Entity best = null;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e != mc.player && which.test(e) && (best == null || e.distanceToSqr(mc.player) < best.distanceToSqr(mc.player))) {
				best = e;
			}
		}
		return best;
	}

	private static boolean isRide(Entity e) {
		return e instanceof dev.rubymod.entity.VehicleEntity || e instanceof dev.rubymod.entity.AircraftEntity
				|| e instanceof dev.rubymod.entity.HeavyAAGunEntity;
	}

	/** Right-click an entity (a vehicle, say). */
	private static void interact(Predicate<Entity> which) {
		then(0, mc -> {
			Entity e = nearest(mc, which);
			if (e != null) {
				mc.gameMode.interact(mc.player, e, new net.minecraft.world.phys.EntityHitResult(e, e.getBoundingBox().getCenter()), InteractionHand.MAIN_HAND);
				swing();
			}
		});
	}

	/** Hit the nearest monster. */
	private static void attack() {
		then(0, mc -> {
			Entity e = nearest(mc, x -> x instanceof net.minecraft.world.entity.monster.Monster);
			if (e != null) {
				mc.gameMode.attack(mc.player, e);
				swing();
			}
		});
	}

	/** Dig at a block with what you're holding (in survival, the cracks spread till it breaks). */
	private static void dig(int x, int y, int z, int ticks) {
		then(0, mc -> {
			mc.gameMode.startDestroyBlock(at(x, y, z), Direction.NORTH);
			swing();
		});
		for (int i = 0; i < ticks; i++) {
			int n = i;
			then(1, mc -> {
				mc.gameMode.continueDestroyBlock(at(x, y, z), Direction.NORTH);
				if (n % 5 == 0) {
					swing();
				}
			});
		}
	}

	/** Get in the nearest vehicle (the actor does: the camera sees it). */
	private static void getIn() {
		server(p -> {
			Mannequin a = actor;
			if (a == null) {
				return;
			}
			Entity best = null;
			for (Entity e : p.level().getEntities((Entity) null, a.getBoundingBox().inflate(8), HelpFilms::isRide)) {
				if (best == null || e.distanceToSqr(a) < best.distanceToSqr(a)) {
					best = e;
				}
			}
			if (best != null) {
				a.startRiding(best, true, false);
			}
		});
	}

	/** Drive what the actor's in: speed (a tick), turning (degrees a tick), speeding up, and climbing after a while. */
	private static void drive(int ticks, double speed, float turn, double accel, double climb, int climbAfter) {
		server(p -> {
			Mannequin a = actor;
			Entity v = a == null ? null : a.getVehicle();
			if (v != null) {
				Mover m = new Mover(v, accel > 0 ? 0.02 : speed, v.getYRot(), ticks);
				m.max = speed;
				m.accel = accel;
				m.turn = turn;
				m.climb = climb;
				m.climbAfter = climbAfter;
				MOVERS.add(m);
			}
		});
		wait(ticks);
	}

	/** A Husk (a monster that doesn't burn in the sun) walking this way by itself: from (x, z), at this heading, this far. */
	private static void monster(double x, double z, float yaw, double speed, int ticks) {
		server(p -> {
			var husk = EntityTypes.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
			if (husk == null) {
				return;
			}
			husk.addTag("rubymod_horde"); // (monsters are kept off the island otherwise)
			husk.setPersistenceRequired();
			husk.snapTo(base.getX() + 0.5 + x, base.getY() + 1, base.getZ() + 0.5 + z, yaw, 0);
			p.level().addFreshEntity(husk);
			Mover m = new Mover(husk, speed, yaw, ticks);
			double rad = Math.toRadians(yaw);
			m.dest = speed > 0 ? husk.position().add(-Math.sin(rad) * speed * ticks, 0, Math.cos(rad) * speed * ticks) : husk.position();
			MOVERS.add(m);
		});
	}

	/** A target drone circling overhead (air defenses practise on them anywhere). */
	private static void drone(double x, double y, double z, double radius) {
		server(p -> dev.rubymod.game.TrainingGround.drone(p.level(), Vec3.atBottomCenterOf(base).add(x, y, z), radius));
	}

	/** A shell lobbed in at the base from outside (by nobody), for the air defenses and the Shield to stop. */
	private static void incoming(double fromX, double fromZ, double toX, double toZ) {
		server(p -> {
			Vec3 from = Vec3.atBottomCenterOf(base).add(fromX, 1, fromZ);
			Vec3 to = Vec3.atBottomCenterOf(base).add(toX, 0, toZ);
			p.level().addFreshEntity(dev.rubymod.entity.MortarShellEntity.launch(p.level(), null, from, to));
		});
	}

	/** The trap at (x, z) goes off (as it would for an enemy stepping on it: the film's monster isn't one it waits for). */
	private static void boom(int x, int z) {
		server(p -> {
			p.level().setBlockAndUpdate(at(x, 1, z), AIR);
			dev.rubymod.game.Explosions.explode(p.level(), null, base.getX() + x + 0.5, base.getY() + 1.2, base.getZ() + z + 0.5, 2.5F, false);
		});
	}

	private static void closeScreen() {
		then(0, mc -> mc.gui.setScreen(null));
	}

	/** Stop watching through a camera or flying a drone (the crouch key), and back to the film camera. */
	private static void stopWatching() {
		then(0, mc -> mc.options.keyShift.setDown(true));
		wait(8);
		then(0, mc -> {
			mc.options.keyShift.setDown(false);
			if (DronePilot.flying()) {
				DronePilot.stop(mc);
			}
		});
		yourEyes(false);
	}

	// ----- A film -----

	private interface Build extends Consumer<ServerPlayer> {
	}

	/**
	 * One film: a fresh stage ({@code build} adds what's on it), the camera {@code c}, a frame every {@code every} ticks
	 * (1: real time), and {@code script} the steps.
	 */
	private static void film(String id, String section, int every, Cam c, Build build, Runnable script) {
		if (ONLY != null && java.util.Arrays.stream(ONLY.split(",")).noneMatch(s -> s.equals(id) || s.equals(section))) {
			return;
		}
		then(0, mc -> staging = id);
		stage(build, c);
		then(0, mc -> {
			filming = id;
			frame = 0;
			frameTicks = 0;
			HelpFilms.every = every;
		});
		script.run();
		then(0, mc -> {
			AutoTest.log("clip {}: {} frames", filming, frame);
			INDEX.add("film " + id + " " + section);
			filming = null;
			walking = null;
			mc.options.keyUse.setDown(false);
			mc.player.setShiftKeyDown(false);
		});
	}

	/** This item has the same film as another (ammo has its gun's). */
	private static void alias(String id, String film, String section) {
		if (ONLY == null || java.util.Arrays.stream(ONLY.split(",")).anyMatch(s -> s.equals(id) || s.equals(section) || s.equals(film))) {
			then(0, mc -> INDEX.add("alias " + id + " " + film + " " + section));
		}
	}

	private static void stage(Build build, Cam c) {
		then(5, mc -> {
			actor = null;
			actorId = -1;
			walking = null;
			if (DronePilot.flying()) {
				DronePilot.stop(mc);
			}
			mc.options.keyShift.setDown(false);
			mc.setCameraEntity(mc.player);
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
			if (camera != null) {
				mc.level.removeEntity(camera.getId(), Entity.RemovalReason.DISCARDED);
				camera = null;
			}
			if (mc.gui.screen() != null) {
				mc.gui.setScreen(null);
			}
			cam = c;
			camEye = null;
		});
		then(1, mc -> AutoTest.onServer(mc, server -> {
			srv = server;
			ServerPlayer p = AutoTest.me(server);
			ServerLevel level = p.level();
			if (base == null) {
				base = p.blockPosition().atY(Math.min(level.getMaxY() - 40, p.getBlockY() + 60));
			}
			MOVERS.clear();
			dev.rubymod.game.SeasonData.get().phase = dev.rubymod.game.Phase.LOBBY;
			time(server, "noon");
			for (Entity e : level.getEntities((Entity) null, new AABB(base).inflate(90), e -> !(e instanceof Player))) {
				e.discard();
			}
			for (int x = -24; x <= 24; x++) {
				for (int z = -16; z <= 48; z++) {
					for (int y = -3; y <= 26; y++) {
						BlockState s = AIR;
						if (Math.abs(x) <= 20 && z >= -14 && z <= 44) {
							s = y == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : y == -1 ? Blocks.DIRT.defaultBlockState()
									: y < -1 ? Blocks.STONE.defaultBlockState() : AIR;
						}
						if (!level.getBlockState(at(x, y, z)).equals(s)) {
							level.setBlock(at(x, y, z), s, 2);
						}
					}
				}
			}
			p.setGameMode(GameType.CREATIVE);
			p.getInventory().clearContent();
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.BODY && slot != EquipmentSlot.SADDLE) {
					p.setItemSlot(slot, ItemStack.EMPTY);
				}
			}
			p.removeAllEffects();
			p.setShiftKeyDown(false);
			p.teleportTo(level, base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, Set.of(), 0, 15, false);
			heart(p, IN_BASE.contains(staging));
			if (IN_BASE.contains(staging)) {
				baseSet(level);
			}
			build.accept(p);
			scenery(level);
			Mannequin a = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
			a.snapTo(p.getX(), p.getY(), p.getZ(), 0, 15);
			a.getEntityData().set(dev.rubymod.mixin.MannequinAccessor.rubymod$profile(),
					net.minecraft.world.item.component.ResolvableProfile.createResolved(p.getGameProfile()));
			((dev.rubymod.mixin.MannequinAccessor) a).rubymod$hideDescription(true);
			a.setNoGravity(true);
			a.setPermanentlyInvulnerable(true);
			level.addFreshEntity(a);
			actor = a;
			actorId = a.getId();
		}));
		then(5, mc -> AutoTest.hold(m -> base != null && actorId >= 0 && m.level.getEntity(actorId) != null
				&& m.player.distanceToSqr(Vec3.atBottomCenterOf(base.above())) < 2
				&& m.level.getBlockState(base).is(Blocks.GRASS_BLOCK), 400));
		then(30, mc -> {
			// The film camera: a stand that lives only here (so it can glide every tick).
			ArmorStand stand = EntityTypes.ARMOR_STAND.create(mc.level, EntitySpawnReason.COMMAND);
			stand.setInvisible(true);
			stand.setNoGravity(true);
			stand.setSilent(true);
			stand.setId(Integer.MAX_VALUE - 4000 - (++cameraCount % 1000)); // (client-only: an id the server will never use)
			mc.level.addEntity(stand);
			camera = stand;
			placeCamera(mc);
			stand.setOldPosAndRot();
			mc.setCameraEntity(stand);
			mc.player.setYRot(0);
			mc.player.setXRot(15);
		});
		wait(8);
	}

	// ----- Every film -----

	static void script() {
		then(5, mc -> {
			AutoTest.command(mc, "time set noon");
			AutoTest.command(mc, "weather clear");
			AutoTest.command(mc, "gamerule doDaylightCycle false");
			AutoTest.command(mc, "gamerule doMobSpawning false");
			AutoTest.command(mc, "difficulty easy");
			BattleSky.off = true; // Open War's red sky would tint the films that need Open War
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		});
		wait(100);

		yourBase();
		guns();
		weapons();
		defenses();
		vehicles();
		industry();
		building();
		security();
		gear();

		then(10, mc -> {
			mc.setCameraEntity(mc.player);
			if (mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
			try {
				Files.write(mc.gameDirectory.toPath().resolve("screenshots").resolve("clips-index.txt"), INDEX);
			} catch (Exception e) {
				RubyMod.LOGGER.error("Couldn't write the clips index", e);
			}
			mc.stop();
		});
	}

	/** Use it, and look at its screen for a moment. */
	private static void openIt(int x, int y, int z) {
		holding(null);
		wait(4);
		face(x + 0.5, y + 0.5, z + 0.5);
		click(x, y, z, N);
		wait(30);
		closeScreen();
		wait(4);
	}

	private static void yourBase() {
		String sec = "Your base";
		// The Ruby Heart: put it down, and the land round it is yours (its edge shows).
		film("ruby_heart", sec, 1, at(3.5, 3, -1.5, 0, 0.8, 2.5), p -> heart(p, false), () -> {
			holding("ruby_heart");
			wait(10);
			clickFloor(0, 3);
			wait(70);
		});
		// The Heart upgrade (bought at the Armory): a bigger base, more health.
		film("heart_upgrade", sec, 1, at(0, 44, -30, -7, 0, 12), p -> {
		}, () -> {
			wait(30);
			server(p -> dev.rubymod.game.Claims.upgrade(p));
			wait(40);
			server(p -> dev.rubymod.game.Claims.upgrade(p));
			wait(40);
		});
		film("armory", sec, 1, SIDE, p -> {
		}, () -> {
			place("armory", 0, 3);
			wait(12);
			openIt(0, 1, 3);
		});
	}

	// ----- Guns -----

	private static void shoot(String gun, int shots, int gap, boolean scope) {
		holding(gun);
		wait(6);
		face(0, 2, 12.5);
		wait(4);
		if (scope) {
			// Down the scope: as you'd see it.
			yourEyes(true);
			then(0, mc -> {
				mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
				mc.options.keyUse.setDown(true);
			});
			wait(14);
		}
		fire(shots, gap);
		wait(14);
		if (scope) {
			then(0, mc -> mc.options.keyUse.setDown(false));
			yourEyes(false);
			wait(10);
		}
	}

	private static void guns() {
		String sec = "Guns";
		Build range = p -> rangeSet(p.level());
		film("service_pistol", sec, 1, SHOOT, range, () -> shoot("service_pistol", 6, 6, false));
		film("arr_rifle", sec, 1, SHOOT, range, () -> shoot("arr_rifle", 20, 2, false));
		film("combat_shotgun", sec, 1, SHOOT, range, () -> shoot("combat_shotgun", 3, 16, false));
		film("longshot_sniper", sec, 1, SHOOT, range, () -> shoot("longshot_sniper", 2, 24, true));
		film("ruby_blaster", sec, 1, SHOOT, range, () -> shoot("ruby_blaster", 14, 3, false));
		film("flamethrower", sec, 1, SIDE, p -> {
			box(p.level(), -1, 1, 5, 1, 1, 6, Blocks.HAY_BLOCK.defaultBlockState());
			put(p.level(), 0, 2, 5, Blocks.OAK_PLANKS.defaultBlockState());
		}, () -> {
			holding("flamethrower");
			wait(6);
			face(0, 1.3, 5.5);
			holdUse(40);
			wait(20);
		});
		String att = "Attachments";
		for (String a : new String[] {"extended_mag", "red_dot_sight", "vertical_grip", "suppressor"}) {
			film(a, att, 1, SHOOT, range, () -> {
				offhand("arr_rifle", 1);
				holding(a);
				wait(12);
				use();
				wait(10);
				// The rifle (with it on) into your hand.
				server(p -> {
					ItemStack gun = p.getItemBySlot(EquipmentSlot.OFFHAND).copy();
					p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
					p.getInventory().setItem(0, gun);
				});
				wait(6);
				face(0, 2, 12.5);
				wait(4);
				fire(10, 3);
				wait(12);
			});
		}
		String ammo = "For guns";
		alias("light_ammo", "service_pistol", ammo);
		alias("heavy_ammo", "arr_rifle", ammo);
		alias("shotgun_shell", "combat_shotgun", ammo);
		alias("sniper_round", "longshot_sniper", ammo);
		alias("ruby_cell", "ruby_blaster", ammo);
	}

	// ----- Rockets, grenades, launchers, charges, artillery -----

	private static void weapons() {
		String sec = "Grenades";
		for (String g : new String[] {"frag_grenade", "smoke_grenade", "fire_bomb"}) {
			film(g, sec, 1, WIDE, p -> {
			}, () -> {
				holding(g, 4);
				wait(6);
				face(0, 2, 7);
				holdUse(5);
				wait(110);
			});
		}
		film("air_strike_flare", "Artillery and strikes", 2, at(13, 12, -10, 0, 2, 12), p -> {
		}, () -> {
			holding("air_strike_flare");
			wait(6);
			face(0, 4, 10);
			holdUse(8);
			wait(300);
		});

		sec = "Launchers";
		film("rocket_launcher", sec, 1, SHOOT, p -> box(p.level(), -2, 1, 13, 2, 3, 13, Blocks.STONE_BRICKS.defaultBlockState()), () -> {
			holding("rocket_launcher");
			wait(6);
			face(0, 2, 13);
			wait(4);
			fire(1, 0);
			wait(40);
		});
		film("lancer", sec, 1, WIDE, p -> ride(p, "jeep", 0, 13), () -> {
			wait(4);
			holding("lancer");
			wait(4);
			face(0, 1.5, 13.5);
			wait(10);
			server(p -> {
				Entity target = null;
				for (Entity e : p.level().getEntities((Entity) null, new AABB(at(0, 1, 13)).inflate(4), HelpFilms::isRide)) {
					target = e;
				}
				if (p.getMainHandItem().getItem() instanceof dev.rubymod.item.LockOnLauncherItem l) {
					l.fire(p, p.getMainHandItem(), target);
				}
			});
			wait(50);
		});
		film("skyhammer", sec, 1, UP, p -> {
		}, () -> {
			drone(0, 16, 10, 6);
			wait(20);
			holding("skyhammer");
			wait(4);
			face(0, 16, 10);
			server(p -> {
				Entity target = null;
				for (Entity e : p.level().getEntities((Entity) null, new AABB(at(0, 16, 10)).inflate(20),
						e -> e instanceof dev.rubymod.entity.AircraftEntity)) {
					target = e;
				}
				if (p.getMainHandItem().getItem() instanceof dev.rubymod.item.LockOnLauncherItem l) {
					l.fire(p, p.getMainHandItem(), target);
				}
			});
			wait(50);
		});
		alias("rocket", "rocket_launcher", "Rockets and shells");
		alias("aa_missile", "skyhammer", "Rockets and shells");
		alias("at_missile", "lancer", "Rockets and shells");

		sec = "Breaking in";
		film("breaching_charge", sec, 1, WIDE, p -> {
			box(p.level(), -2, 1, 3, 2, 3, 3, Blocks.STONE_BRICKS.defaultBlockState());
			war();
		}, () -> {
			holding("breaching_charge");
			wait(6);
			click(0, 1, 3, N);
			wait(4);
			back(14);
			wait(135);
		});
		film("c4", sec, 1, WIDE, p -> box(p.level(), -2, 1, 6, 2, 3, 6, Blocks.STONE_BRICKS.defaultBlockState()), () -> {
			holding("c4", 2);
			wait(6);
			face(0, 2, 6);
			use();
			wait(14);
			back(12);
			sneak(true);
			wait(2);
			use();
			wait(2);
			sneak(false);
			wait(34);
		});

		sec = "Artillery and strikes";
		film("mortar", sec, 1, at(9, 7, -4, 0, 2, 9), p -> {
			owned(p, 2, 1, 2, "mortar", S);
			war(); // heavy weapons fire in Open War
		}, () -> {
			holding("rangefinder");
			wait(6);
			face(0, 0, 17);
			// Through the Rangefinder: as you'd see it.
			yourEyes(true);
			holdUse(26);
			server(p -> dev.rubymod.item.RangefinderItem.mark(p, at(0, 0, 17)));
			yourEyes(false);
			wait(4);
			holding("mortar_shell", 3);
			wait(6);
			click(2, 1, 2, N);
			wait(70);
		});
		alias("rangefinder", "mortar", sec);
		alias("mortar_shell", "mortar", "Rockets and shells");
		film("missile_silo", sec, 1, at(7, 3, -3, 1, 5, 4), p -> {
			owned(p, 2, 1, 3, "missile_silo", S);
			war();
		}, () -> {
			server(p -> dev.rubymod.item.RangefinderItem.mark(p, at(0, 0, 17)));
			holding("cruise_missile");
			wait(6);
			click(2, 1, 3, N);
			wait(100);
		});
		alias("cruise_missile", "missile_silo", sec);
	}

	// ----- Walls, traps, turrets, air defenses -----

	/** Build a little wall of it: three along, two high. */
	private static void wallOf(String id) {
		holding(id, 16);
		wait(6);
		for (int x = -1; x <= 1; x++) {
			clickFloor(x, 3);
			wait(5);
		}
		for (int x = -1; x <= 1; x++) {
			click(x, 1, 3, Direction.UP);
			wait(5);
		}
		wait(16);
	}

	private static void defenses() {
		String sec = "Walls";
		for (String w : new String[] {"sandbags", "concrete_wall", "reinforced_concrete", "reinforced_glass"}) {
			film(w, sec, 1, CLOSE, p -> {
			}, () -> wallOf(w));
		}
		film("blast_door", sec, 1, SIDE, p -> {
			box(p.level(), -3, 1, 4, 3, 3, 4, Blocks.STONE_BRICKS.defaultBlockState());
			box(p.level(), 0, 1, 4, 0, 2, 4, AIR);
		}, () -> {
			holding("blast_door");
			wait(6);
			walk(6);
			clickFloor(0, 4);
			wait(10);
			holding(null);
			click(0, 1, 4, N);
			wait(8);
			walk(20);
			wait(8);
		});
		film("barbed_wire", sec, 1, SIDE, p -> {
		}, () -> {
			holding("barbed_wire", 8);
			wait(6);
			for (int x = -1; x <= 1; x++) {
				clickFloor(x, 3);
				wait(4);
			}
			wait(6);
			walk(10);
			walk(30, 0, 0, 0.05);
		});
		sec = "Hiding";
		film("holo_camo", sec, 1, SIDE, p -> {
			box(p.level(), -3, 1, 4, 3, 3, 4, Blocks.STONE_BRICKS.defaultBlockState());
			box(p.level(), 0, 1, 4, 0, 2, 4, AIR);
		}, () -> {
			walk(6);
			holding("holo_camo", 8);
			wait(4);
			clickFloor(0, 4);
			wait(4);
			click(0, 1, 4, Direction.UP);
			wait(6);
			holding("minecraft:stone_bricks", 8);
			wait(4);
			click(0, 1, 4, N);
			wait(4);
			click(0, 2, 4, N);
			wait(10);
			walk(22);
			wait(6);
		});
		film("camo_net", sec, 1, WIDE, p -> {
			ride(p, "jeep", 0, 5);
			for (int[] c : new int[][] {{-2, 3}, {2, 3}, {-2, 7}, {2, 7}}) {
				box(p.level(), c[0], 1, c[1], c[0], 2, c[1], Blocks.OAK_FENCE.defaultBlockState());
			}
		}, () -> {
			holding("camo_net", 16);
			wait(6);
			walk(10);
			for (int x = -2; x <= 2; x++) {
				click(x, 2, 3, Direction.UP);
				wait(3);
			}
			for (int x = -2; x <= 2; x++) {
				click(x, 2, 5, Direction.UP);
				wait(3);
			}
			wait(16);
		});

		sec = "Traps";
		film("landmine", sec, 1, WIDE, p -> {
		}, () -> {
			holding("landmine");
			wait(6);
			clickFloor(0, 4);
			wait(6);
			back(5);
			monster(0, 9, 180, 0.1, 120);
			wait(46);
			boom(0, 4);
			wait(24);
		});
		film("claymore", sec, 1, WIDE, p -> {
		}, () -> {
			holding("claymore");
			wait(6);
			clickFloor(0, 3);
			wait(6);
			back(5);
			monster(0, 9, 180, 0.1, 120);
			wait(50);
			boom(0, 3);
			wait(24);
		});
		film("at_mine", sec, 1, WIDE, p -> {
		}, () -> {
			holding("at_mine");
			wait(6);
			clickFloor(0, 5);
			wait(6);
			holding("jeep");
			wait(4);
			stand(3, 0);
			wait(4);
			clickFloor(3, 2);
			wait(6);
			getIn();
			wait(4);
			server(p -> {
				Entity v = actor == null ? null : actor.getVehicle();
				if (v != null) {
					v.snapTo(base.getX() + 0.5, v.getY(), base.getZ() + 0.5, 0, 0);
					MOVERS.add(new Mover(v, 0.25, 0, 60));
				}
			});
			wait(20);
			server(p -> {
				p.level().setBlockAndUpdate(at(0, 1, 5), AIR);
				dev.rubymod.game.Explosions.explode(p.level(), null, base.getX() + 0.5, base.getY() + 1, base.getZ() + 5.5, 3.0F, false);
			});
			wait(40);
		});

		sec = "Turrets";
		film("sentry_turret", sec, 1, SIDE, p -> {
		}, () -> {
			place("sentry_turret", 2, 4);
			wait(8);
			holding("heavy_ammo", 32);
			wait(4);
			click(2, 1, 4, N);
			wait(6);
			holding("practice_dummy");
			wait(4);
			clickFloor(0, 3);
			wait(60);
		});
		alias("practice_dummy", "sentry_turret", sec);
		film("tesla_coil", sec, 1, SIDE, p -> owned(p, -4, 1, 6, "solar_panel", S), () -> {
			place("tesla_coil", 2, 5);
			wait(8);
			holding("practice_dummy");
			wait(4);
			clickFloor(0, 3);
			wait(60);
		});

		sec = "Guns you man";
		film("heavy_aa_gun", sec, 1, UP, p -> {
		}, () -> {
			drone(0, 18, 12, 8);
			place("heavy_aa_gun", 0, 3);
			wait(8);
			walk(8);
			getIn();
			wait(40);
		});
		film("railgun", sec, 1, WIDE, p -> {
		}, () -> {
			place("railgun", 0, 3);
			wait(8);
			walk(8);
			getIn();
			wait(30);
		});

		sec = "Automatic";
		film("flak_cannon", sec, 1, UP, p -> {
		}, () -> {
			drone(0, 22, 12, 10);
			place("flak_cannon", 2, 3);
			wait(6);
			holding("heavy_ammo", 32);
			wait(4);
			click(2, 1, 3, N);
			wait(100);
		});
		film("sam_site", sec, 1, UP, p -> {
		}, () -> {
			drone(0, 26, 14, 12);
			place("sam_site", 2, 3);
			wait(6);
			holding("ruby", 32);
			wait(4);
			click(2, 1, 3, N);
			wait(100);
		});
		film("point_defense", sec, 1, WIDE, p -> owned(p, -4, 1, 2, "solar_panel", S), () -> {
			place("point_defense", 2, 3);
			wait(10);
			incoming(0, 40, 0, 4);
			wait(70);
		});
		film("missile_interceptor", sec, 1, WIDE, p -> dev.rubymod.game.SeasonData.get().phase = dev.rubymod.game.Phase.LOCKDOWN, () -> {
			place("missile_interceptor", 2, 3);
			wait(6);
			holding("ruby", 32);
			wait(4);
			click(2, 1, 3, N);
			wait(6);
			incoming(0, 40, 0, 4);
			wait(70);
		});
		film("shield_generator", sec, 1, WIDE, p -> owned(p, -4, 1, 2, "solar_panel", S), () -> {
			place("shield_generator", 0, 3);
			wait(10);
			incoming(0, 40, 0, 4);
			wait(70);
		});
		film("radar_station", "Early warning", 1, SIDE, p -> {
		}, () -> {
			place("radar_station", 0, 3);
			wait(10);
			monster(0, 14, 180, 0.1, 80);
			wait(40);
		});
	}

	// ----- Vehicles -----

	private static void landVehicle(String id, double speed, float turn) {
		place(id, 0, 4);
		wait(8);
		walk(10);
		getIn();
		wait(8);
		drive(80, speed, turn, 0.01, 0, 0);
	}

	private static void boat(String id) {
		holding(id);
		wait(6);
		walk(5);
		face(0, 0.5, 5);
		wait(2);
		use();
		wait(8);
		getIn();
		wait(6);
		drive(90, 0.3, 0.6F, 0.01, 0, 0);
	}

	private static void aircraft(String id, double speed, int climbAfter, double climb) {
		place(id, 0, 3);
		wait(10);
		walk(6);
		getIn();
		wait(8);
		drive(110, speed, 0, 0.012, climb, climbAfter);
	}

	private static void vehicles() {
		String sec = "On land";
		Build road = p -> box(p.level(), -2, 0, 2, 2, 0, 44, Blocks.DIRT_PATH.defaultBlockState());
		film("jeep", sec, 1, FOLLOW, road, () -> landVehicle("jeep", 0.35, 0.8F));
		film("tank", sec, 1, FOLLOW, road, () -> {
			landVehicle("tank", 0.15, 0);
			server(p -> {
				Entity v = actor == null ? null : actor.getVehicle();
				if (v instanceof dev.rubymod.entity.VehicleEntity tank) {
					tank.fireCannon(p);
				}
			});
			wait(20);
		});
		film("dirt_bike", sec, 1, FOLLOW, road, () -> landVehicle("dirt_bike", 0.45, 1.0F));
		film("quad", sec, 1, FOLLOW, road, () -> landVehicle("quad", 0.4, 0.9F));
		film("tunnel_borer", sec, 1, at(7, 4, 2, 0, 1.5, 9), p -> box(p.level(), -4, 1, 8, 4, 6, 16, Blocks.STONE.defaultBlockState()), () -> {
			place("tunnel_borer", 0, 4);
			wait(8);
			walk(10);
			getIn();
			wait(8);
			// Its drill grinds a 3 x 3 tunnel as it goes (the film moves it along).
			for (int i = 0; i < 20; i++) {
				int z = 8 + i / 2;
				then(4, mc -> AutoTest.onServer(mc, s -> {
					ServerLevel level = AutoTest.me(s).level();
					for (int x = -1; x <= 1; x++) {
						for (int y = 1; y <= 3; y++) {
							if (!level.getBlockState(at(x, y, z)).isAir()) {
								level.destroyBlock(at(x, y, z), false);
							}
						}
					}
					Entity v = actor == null ? null : actor.getVehicle();
					if (v != null) {
						v.snapTo(v.getX(), v.getY(), Math.min(v.getZ() + 0.25, base.getZ() + z - 0.5), 0, 0);
					}
				}));
			}
			wait(10);
		});
		sec = "On water";
		Build river = p -> riverSet(p.level());
		film("patrol_boat", sec, 1, FOLLOW, river, () -> boat("patrol_boat"));
		film("gunboat", sec, 1, FOLLOW, river, () -> boat("gunboat"));
		film("landing_craft", sec, 1, FOLLOW, river, () -> boat("landing_craft"));
		sec = "Aircraft";
		Build runway = p -> runwaySet(p.level());
		film("fighter_jet", sec, 1, FLY, runway, () -> aircraft("fighter_jet", 0.8, 22, 0.25));
		film("attack_jet", sec, 1, FLY, runway, () -> aircraft("attack_jet", 0.7, 26, 0.2));
		film("bomber", sec, 1, FLY, runway, () -> aircraft("bomber", 0.6, 32, 0.15));
		film("attack_heli", sec, 1, FLY, p -> box(p.level(), -3, 0, 1, 3, 0, 6, block("minecraft:gray_concrete").defaultBlockState()), () -> {
			place("attack_heli", 0, 3);
			wait(10);
			walk(6);
			getIn();
			wait(8);
			// Straight up, then away.
			server(p -> {
				Entity v = actor == null ? null : actor.getVehicle();
				if (v != null) {
					Mover m = new Mover(v, 0, 0, 30);
					m.climb = 0.25;
					MOVERS.add(m);
				}
			});
			wait(30);
			drive(60, 0.4, 0.5F, 0.02, 0.02, 0);
		});
		film("airfield_pad", "Airfield", 1, SIDE, runway, () -> {
			place("airfield_pad", 0, 3);
			wait(30);
		});
	}

	// ----- Power, rubies, belts -----

	private static void industry() {
		String sec = "Power";
		Build shop = HelpFilms::workshopSet;
		film("combustion_generator", sec, 1, SIDE, shop, () -> {
			place("combustion_generator", 0, 3);
			wait(6);
			server(p -> dev.rubymod.industry.Belts.insert(p.level(), at(0, 1, 3), Direction.UP, stack("minecraft:coal", 16)));
			wait(10);
			openIt(0, 1, 3);
			wait(20);
		});
		for (String m : new String[] {"solar_panel", "battery"}) {
			film(m, sec, 1, SIDE, shop, () -> {
				place(m, 0, 3);
				wait(14);
				openIt(0, 1, 3);
			});
		}
		sec = "Making rubies";
		film("ruby_drill", sec, 1, SIDE, shop, () -> {
			place("ruby_drill", 0, 3);
			wait(60);
			holding(null);
			sneak(true);
			wait(2);
			click(0, 1, 3, N);
			wait(4);
			sneak(false);
			wait(20);
		});
		for (String m : new String[] {"ore_refinery", "trade_depot", "vault", "ammo_factory"}) {
			film(m, sec, 1, SIDE, shop, () -> {
				place(m, 0, 3);
				wait(12);
				openIt(0, 1, 3);
			});
		}
		sec = "Mining and making";
		film("prospector_pick", sec, 1, SIDE, p -> put(p.level(), 4, -1, 6, dev.rubymod.registry.ModBlocks.RUBY_ORE.defaultBlockState()), () -> {
			holding("prospector_pick");
			wait(8);
			// The sparkles point the way: through your own eyes.
			face(2, 0.5, 4);
			yourEyes(true);
			use();
			wait(30);
			use();
			wait(30);
			yourEyes(false);
			wait(6);
		});
		film("assembler", sec, 1, SIDE, shop, () -> {
			place("assembler", 0, 3);
			wait(12);
			openIt(0, 1, 3);
		});
		film("ore_silo", sec, 1, SIDE, shop, () -> {
			place("ore_silo", 0, 3);
			wait(8);
			holding("minecraft:iron_ore", 64);
			wait(4);
			click(0, 1, 3, N);
			wait(8);
			click(0, 1, 3, N);
			wait(20);
		});

		sec = "Moving things";
		Consumer<ServerPlayer> chest = p -> {
			workshopSet(p);
			put(p.level(), 0, 1, 3, Blocks.CHEST.defaultBlockState());
			var c = dev.rubymod.industry.Belts.containerAt(p.level(), at(0, 1, 3));
			if (c != null) {
				for (int i = 0; i < 6; i++) {
					c.setItem(i, stack(i % 2 == 0 ? "minecraft:iron_ingot" : "minecraft:gold_ingot", 16));
				}
			}
		};
		film("conveyor", sec, 1, SIDE, shop, () -> {
			stand(-2, 0);
			holding("conveyor", 16);
			wait(6);
			for (int z = 2; z <= 5; z++) {
				clickFloor(0, z);
				wait(4);
			}
			server(p -> {
				for (int i = 0; i < 3; i++) {
					ItemEntity drop = new ItemEntity(p.level(), base.getX() + 0.5, base.getY() + 1.3, base.getZ() + 2.5, stack("minecraft:iron_ingot", 1));
					drop.setDeltaMovement(Vec3.ZERO);
					drop.setPickUpDelay(400);
					p.level().addFreshEntity(drop);
				}
			});
			wait(50);
		});
		film("belt_loader", sec, 1, SIDE, chest::accept, () -> {
			stand(-2, 1);
			holding("belt_loader");
			wait(6);
			clickFloor(0, 4);
			wait(4);
			holding("conveyor", 16);
			wait(4);
			for (int z = 5; z <= 6; z++) {
				clickFloor(0, z);
				wait(4);
			}
			wait(50);
		});
		film("belt_sorter", sec, 1, WIDE, p -> {
			chest.accept(p);
			put(p.level(), 0, 1, 4, facing("belt_loader", S));
			put(p.level(), 0, 1, 5, facing("conveyor", S));
			put(p.level(), 1, 1, 6, facing("conveyor", Direction.EAST));
			put(p.level(), 2, 1, 6, facing("conveyor", Direction.EAST));
			put(p.level(), 0, 1, 7, facing("conveyor", S));
		}, () -> {
			stand(-2, 2);
			holding("belt_sorter");
			wait(6);
			clickFloor(0, 6);
			wait(4);
			holding("minecraft:gold_ingot");
			wait(4);
			click(0, 1, 6, N);
			wait(60);
		});
		film("belt_splitter", sec, 1, WIDE, p -> {
			chest.accept(p);
			put(p.level(), 0, 1, 4, facing("belt_loader", S));
			put(p.level(), 0, 1, 5, facing("conveyor", S));
			put(p.level(), 1, 1, 6, facing("conveyor", Direction.EAST));
			put(p.level(), -1, 1, 6, facing("conveyor", Direction.WEST));
		}, () -> {
			stand(-2, 2);
			holding("belt_splitter");
			wait(6);
			clickFloor(0, 6);
			wait(60);
		});
		film("belt_ramp", sec, 1, SIDE, p -> {
			chest.accept(p);
			put(p.level(), 0, 1, 4, facing("belt_loader", S));
			put(p.level(), 0, 1, 5, facing("conveyor", S));
			box(p.level(), -1, 1, 7, 1, 1, 9, Blocks.STONE_BRICKS.defaultBlockState());
			put(p.level(), 0, 2, 7, facing("conveyor", S));
			put(p.level(), 0, 2, 8, facing("conveyor", S));
		}, () -> {
			stand(-2, 2);
			holding("belt_ramp", 4);
			wait(6);
			clickFloor(0, 6);
			wait(60);
		});
		film("belt_tunnel", sec, 1, WIDE, p -> {
			chest.accept(p);
			put(p.level(), 0, 1, 4, facing("belt_loader", S));
			box(p.level(), -2, 1, 7, 2, 3, 7, Blocks.STONE_BRICKS.defaultBlockState());
			put(p.level(), 0, 1, 10, facing("conveyor", S));
			put(p.level(), 0, 1, 11, facing("conveyor", S));
		}, () -> {
			stand(-2, 2);
			holding("belt_tunnel", 2);
			wait(6);
			clickFloor(0, 5);
			wait(6);
			walk(20);
			clickFloor(0, 9);
			wait(60);
		});
		film("ruby_wrench", sec, 1, CLOSE, p -> {
			workshopSet(p);
			put(p.level(), 0, 1, 3, facing("conveyor", S));
			put(p.level(), 0, 1, 4, facing("conveyor", S));
		}, () -> {
			holding("ruby_wrench");
			wait(8);
			click(0, 1, 3, Direction.UP);
			wait(10);
			click(0, 1, 3, Direction.UP);
			wait(10);
			sneak(true);
			wait(2);
			click(0, 1, 4, Direction.UP);
			wait(4);
			sneak(false);
			wait(16);
		});
		film("elevator", sec, 1, WIDE, p -> {
			box(p.level(), -1, 1, 3, 1, 5, 5, Blocks.STONE_BRICKS.defaultBlockState());
			box(p.level(), -1, 1, 3, 1, 5, 3, AIR);
			put(p.level(), 0, 0, 3, block("elevator").defaultBlockState());
			put(p.level(), 0, 5, 4, block("elevator").defaultBlockState());
		}, () -> {
			walk(15);
			wait(10);
			server(p -> dev.rubymod.game.Elevators.ride(p, true));
			wait(30);
			server(p -> dev.rubymod.game.Elevators.ride(p, false));
			wait(20);
		});
	}

	// ----- Building tools, copying, ready-made plans, moving parts, lights -----

	/** A Constructor (powered, with a chest of what's needed beside it) building a Blueprint. */
	private static void construct(ServerPlayer p, ItemStack blueprint) {
		owned(p, 5, 1, 2, "constructor", S);
		owned(p, 8, 1, 0, "solar_panel", S);
		owned(p, 9, 1, 0, "solar_panel", S);
		put(p.level(), 6, 1, 2, Blocks.CHEST.defaultBlockState());
		put(p.level(), 6, 1, 1, Blocks.CHEST.defaultBlockState());
		var data = blueprint.get(dev.rubymod.registry.ModComponents.BLUEPRINT);
		java.util.Map<Item, Integer> need = new java.util.LinkedHashMap<>();
		if (data != null) {
			for (int c : data.cells()) {
				if (c >= 0 && c < data.palette().size()) {
					Item it = data.palette().get(c).getBlock().asItem();
					if (it != net.minecraft.world.item.Items.AIR) {
						need.merge(it, 1, Integer::sum);
					}
				}
			}
		}
		need.replaceAll((k, v) -> v + 16);
		int slot = 0;
		for (var e : need.entrySet()) {
			int left = e.getValue();
			while (left > 0 && slot < 54) {
				var c = dev.rubymod.industry.Belts.containerAt(p.level(), at(6, 1, slot < 27 ? 2 : 1));
				if (c == null) {
					break;
				}
				int n = Math.min(64, left);
				c.setItem(slot % 27, new ItemStack(e.getKey(), n));
				left -= n;
				slot++;
			}
		}
	}

	private static void building() {
		String sec = "Building tools";
		film("architect_wand", sec, 1, WIDE, p -> {
		}, () -> {
			offhand("minecraft:stone_bricks", 64);
			holding("architect_wand");
			wait(8);
			clickFloor(-3, 4);
			wait(8);
			clickFloor(3, 4);
			wait(30);
		});
		film("remodeler", sec, 1, SIDE, p -> box(p.level(), -2, 1, 4, 2, 3, 4, Blocks.COBBLESTONE.defaultBlockState()), () -> {
			offhand("minecraft:stone_bricks", 64);
			holding("remodeler");
			wait(10);
			click(0, 2, 4, N);
			wait(30);
		});
		film("builders_wand", sec, 1, WIDE, p -> put(p.level(), 0, 1, 2, Blocks.STONE_BRICKS.defaultBlockState()), () -> {
			offhand("minecraft:stone_bricks", 64);
			holding("builders_wand");
			wait(10);
			click(0, 1, 2, Direction.UP);
			wait(22);
			sneak(true);
			wait(2);
			click(0, 1, 2, Direction.UP);
			wait(4);
			sneak(false);
			wait(24);
		});
		film("excavator", sec, 1, SIDE, p -> box(p.level(), -3, 1, 3, 3, 4, 6, Blocks.STONE.defaultBlockState()), () -> {
			server(p -> p.setGameMode(GameType.SURVIVAL));
			holding("excavator");
			wait(8);
			face(0, 2, 3);
			dig(0, 2, 3, 26);
			wait(6);
			dig(0, 2, 4, 26);
			wait(10);
		});
		sec = "Copying buildings";
		film("blueprint", sec, 3, WIDE, p -> {
			box(p.level(), -1, 1, 4, 1, 3, 6, Blocks.OAK_PLANKS.defaultBlockState());
			box(p.level(), 0, 1, 4, 0, 2, 4, AIR);
			owned(p, 5, 1, 3, "constructor", S);
			put(p.level(), 6, 1, 3, Blocks.CHEST.defaultBlockState());
			var c = dev.rubymod.industry.Belts.containerAt(p.level(), at(6, 1, 3));
			if (c != null) {
				for (int i = 0; i < 4; i++) {
					c.setItem(i, stack("minecraft:oak_planks", 64));
				}
			}
			owned(p, 7, 1, 1, "solar_panel", S);
		}, () -> {
			holding("blueprint");
			wait(6);
			walk(4);
			click(-1, 1, 4, N);
			wait(6);
			click(1, 3, 6, N);
			wait(14);
			// The hut's knocked down... and the Constructor builds it again from the copy.
			server(p -> box(p.level(), -1, 1, 4, 1, 3, 6, AIR));
			wait(10);
			server(p -> dev.rubymod.industry.Belts.insert(p.level(), at(5, 1, 3), Direction.UP, p.getMainHandItem().copy()));
			wait(220);
		});
		alias("constructor", "blueprint", sec);
		sec = "Ready-made plans";
		for (var design : dev.rubymod.industry.ReadyBlueprints.ALL) {
			film("blueprint_" + design.id(), sec, 8, at(15, 9, -5, 3, 3, 6), p -> construct(p, dev.rubymod.industry.ReadyBlueprints.stack(design)), () -> {
				server(p -> dev.rubymod.industry.Belts.insert(p.level(), at(5, 1, 2), Direction.UP, dev.rubymod.industry.ReadyBlueprints.stack(design)));
				wait(1200);
			});
		}
		sec = "Moving parts";
		film("ruby_piston", sec, 1, SIDE, p -> {
			put(p.level(), -2, 1, 5, block("ruby_piston").defaultBlockState().setValue(dev.rubymod.block.RubyMoverBlock.FACING, Direction.EAST)
					.setValue(dev.rubymod.block.RubyMoverBlock.DISTANCE, 2));
			box(p.level(), -1, 1, 5, -1, 3, 5, Blocks.IRON_BLOCK.defaultBlockState());
			put(p.level(), -3, 1, 5, Blocks.LEVER.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
					.setValue(net.minecraft.world.level.block.LeverBlock.FACING, Direction.WEST));
		}, () -> {
			stand(-3, 2);
			holding(null);
			wait(8);
			click(-3, 1, 5, N);
			wait(40);
			click(-3, 1, 5, N);
			wait(30);
		});
		film("ruby_bearing", sec, 1, SIDE, p -> {
			put(p.level(), 0, 1, 5, block("ruby_bearing").defaultBlockState().setValue(dev.rubymod.block.RubyMoverBlock.FACING, Direction.UP));
			box(p.level(), 0, 2, 5, 3, 2, 5, Blocks.OAK_PLANKS.defaultBlockState());
			put(p.level(), -1, 1, 5, Blocks.LEVER.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
					.setValue(net.minecraft.world.level.block.LeverBlock.FACING, Direction.WEST));
		}, () -> {
			stand(-1, 2);
			holding(null);
			wait(8);
			click(-1, 1, 5, N);
			wait(60);
		});
		film("floodlight", "Lights", 1, WIDE, p -> time(p.level().getServer(), "midnight"), () -> {
			place("floodlight", 0, 3);
			wait(30);
		});
	}

	// ----- Doors, cameras, alarms -----

	private static void security() {
		String sec = "Locked doors";
		film("keycard_door", sec, 1, SIDE, p -> {
			box(p.level(), -4, 1, 4, 4, 3, 4, Blocks.STONE_BRICKS.defaultBlockState());
			var door = block("keycard_door").defaultBlockState()
					.setValue(net.minecraft.world.level.block.DoorBlock.FACING, S);
			p.level().setBlock(at(0, 1, 4), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
					net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 3);
			p.level().setBlock(at(0, 2, 4), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
					net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
		}, () -> {
			holding("keycard");
			wait(6);
			use();
			wait(8);
			walk(12);
			click(0, 1, 4, N);
			wait(8);
			walk(22);
			wait(8);
		});
		alias("keycard", "keycard_door", sec);
		film("keycard_reader", sec, 1, SIDE, p -> {
			put(p.level(), -2, 1, 5, block("ruby_piston").defaultBlockState().setValue(dev.rubymod.block.RubyMoverBlock.FACING, Direction.EAST)
					.setValue(dev.rubymod.block.RubyMoverBlock.DISTANCE, 2));
			box(p.level(), -1, 1, 5, -1, 3, 5, Blocks.IRON_BLOCK.defaultBlockState());
		}, () -> {
			stand(-2, 2);
			holding("keycard_reader");
			wait(6);
			click(-2, 1, 5, Direction.UP);
			wait(8);
			holding("keycard");
			wait(4);
			use();
			wait(6);
			click(-2, 2, 5, N);
			wait(50);
		});
		sec = "Watching and alarms";
		film("security_camera", sec, 1, SIDE, p -> box(p.level(), 2, 1, 5, 2, 3, 5, Blocks.STONE_BRICKS.defaultBlockState()), () -> {
			holding("security_camera");
			wait(6);
			walk(8);
			click(2, 3, 5, N);
			wait(6);
			back(8);
			place("monitor", -1, 3);
			wait(6);
			holding(null);
			// Through the camera, as you'd see it on the Monitor.
			then(0, mc -> {
				if (mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			click(-1, 1, 3, N);
			wait(40);
			stopWatching();
			wait(14);
		});
		alias("monitor", "security_camera", sec);
		film("laser_tripwire", sec, 1, WIDE, p -> {
			box(p.level(), 4, 1, 6, 4, 2, 6, Blocks.STONE_BRICKS.defaultBlockState());
			put(p.level(), -5, 1, 6, Blocks.REDSTONE_LAMP.defaultBlockState());
		}, () -> {
			stand(-3, 3);
			holding("laser_tripwire");
			wait(6);
			click(-5, 1, 6, Direction.EAST);
			wait(10);
			back(8);
			monster(0, 14, 180, 0.12, 110);
			wait(80);
		});
		film("seismic_sensor", sec, 1, SIDE, p -> {
		}, () -> {
			place("seismic_sensor", 0, 3);
			wait(30);
		});
		film("alarm_block", sec, 1, WIDE, p -> {
		}, () -> {
			place("alarm_block", 0, 3);
			wait(8);
			monster(0, 16, 180, 0.12, 110);
			wait(80);
		});
	}

	// ----- Gear, guards, drones, paint -----

	private static void gear() {
		String sec = "Carrying and talking";
		for (String b : new String[] {"field_pack", "heavy_rucksack"}) {
			film(b, sec, 1, SIDE, p -> {
			}, () -> {
				holding(b);
				wait(10);
				yourEyes(true);
				use();
				wait(26);
				closeScreen();
				yourEyes(false);
				wait(8);
			});
		}
		film("field_radio", sec, 1, CLOSE, p -> {
		}, () -> {
			holding("field_radio");
			wait(10);
			use();
			wait(14);
			use();
			wait(14);
		});
		sec = "Getting around";
		film("jetpack", sec, 1, FOLLOW, p -> {
		}, () -> {
			wear(EquipmentSlot.CHEST, "jetpack");
			wait(10);
			walk(30, 0, 0.3, 0.05);
			walk(30, 0, 0.02, 0.25);
			walk(20, 0, -0.25, 0.1);
		});
		film("grappling_hook", sec, 1, at(9, 4, -2, 0, 5, 7), p -> box(p.level(), -1, 1, 8, 1, 9, 9, Blocks.STONE_BRICKS.defaultBlockState()), () -> {
			holding("grappling_hook");
			wait(6);
			face(0, 9, 8);
			use();
			wait(4);
			walk(22, 0, 0.36, 0.3);
			wait(20);
		});
		film("night_goggles", sec, 1, SIDE, p -> {
			time(p.level().getServer(), "midnight");
			box(p.level(), -3, 1, 8, 3, 3, 8, Blocks.STONE_BRICKS.defaultBlockState());
		}, () -> {
			holding("night_goggles");
			wait(10);
			use();
			wear(EquipmentSlot.HEAD, "night_goggles");
			wait(14);
			// What they do: as you'd see it.
			face(0, 1.6, 8);
			yourEyes(true);
			wait(40);
			yourEyes(false);
			wait(6);
		});
		sec = "Ruby armor and tools";
		film("ruby_helmet", sec, 1, CLOSE, p -> {
		}, () -> {
			for (String piece : new String[] {"ruby_helmet", "ruby_chestplate", "ruby_leggings", "ruby_boots"}) {
				holding(piece);
				wait(6);
				use();
				wait(8);
			}
			wear(EquipmentSlot.HEAD, "ruby_helmet");
			wear(EquipmentSlot.CHEST, "ruby_chestplate");
			wear(EquipmentSlot.LEGS, "ruby_leggings");
			wear(EquipmentSlot.FEET, "ruby_boots");
			wait(20);
		});
		alias("ruby_chestplate", "ruby_helmet", sec);
		alias("ruby_leggings", "ruby_helmet", sec);
		alias("ruby_boots", "ruby_helmet", sec);
		film("ruby_sword", sec, 1, SIDE, p -> {
		}, () -> {
			monster(0, 2.5, 180, 0, 0);
			holding("ruby_sword");
			wait(10);
			face(0, 1.5, 2.5);
			for (int i = 0; i < 4; i++) {
				attack();
				wait(12);
			}
			wait(10);
		});
		film("ruby_pickaxe", sec, 1, SIDE, p -> box(p.level(), -1, 1, 2, 1, 2, 2, Blocks.STONE.defaultBlockState()), () -> {
			server(p -> p.setGameMode(GameType.SURVIVAL));
			holding("ruby_pickaxe");
			wait(8);
			face(0, 1.5, 2.5);
			dig(0, 1, 2, 14);
			dig(0, 2, 2, 14);
			dig(1, 1, 2, 14);
			wait(10);
		});
		sec = "Guards";
		for (String g : new String[] {"guard_contract", "at_guard_contract"}) {
			film(g, sec, 1, WIDE, p -> {
			}, () -> {
				holding(g);
				wait(6);
				clickFloor(0, 3);
				wait(12);
				monster(0, 15, 180, 0.06, 120);
				wait(80);
			});
		}
		sec = "Drones";
		film("recon_drone", sec, 1, UP, p -> {
		}, () -> {
			holding("recon_drone");
			wait(8);
			face(0, 8, 6);
			use();
			wait(100);
		});
		film("scout_drone", sec, 1, UP, p -> {
		}, () -> {
			holding("scout_drone");
			wait(8);
			// Flying it: what the drone sees.
			then(0, mc -> {
				if (mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			use();
			wait(40);
			stopWatching();
			wait(20);
		});
		sec = "Paint";
		film("paint_kit", sec, 1, CLOSE, p -> ride(p, "jeep", 0, 3.5), () -> {
			wait(6);
			holding("paint_kit");
			wait(6);
			for (int i = 0; i < 3; i++) {
				interact(HelpFilms::isRide);
				wait(16);
			}
		});
	}
}
