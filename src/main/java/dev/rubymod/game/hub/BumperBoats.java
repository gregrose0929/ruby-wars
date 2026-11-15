package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bumper cars, the smooth way: each car is really a boat on an ice rink (so your own game moves it, with no lag or
 * jitter), with a bumper car model riding along on top. Bonk your friends; the rink's walls bounce you back.
 */
final class BumperBoats {
	private BumperBoats() {
	}

	static final int X1 = 98;
	static final int X2 = 122;
	static final int Z1 = -10;
	static final int Z2 = 8;
	private static final AABB RINK = new AABB(X1, FLOOR, Z1, X2 + 1, FLOOR + 4, Z2 + 1);
	private static final Vec3 EXIT = new Vec3(96.5, FLOOR + 1, -0.5);
	private static final String[] COLORS = {"red", "blue", "green", "yellow", "pink", "purple", "orange", "sky"};

	private record Car(UUID driver, UUID boat, UUID model, String color) {
	}

	private static final List<Car> CARS = new ArrayList<>();
	private static final java.util.Map<UUID, Vec3> LAST = new java.util.HashMap<>();
	private static final java.util.Map<UUID, Integer> BONKED = new java.util.HashMap<>();
	private static int tick;

	static void build(ServerLevel level) {
		CARS.clear();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = X1 - 1; x <= X2 + 1; x++) {
			for (int z = Z1 - 1; z <= Z2 + 1; z++) {
				boolean edge = x == X1 - 1 || x == X2 + 1 || z == Z1 - 1 || z == Z2 + 1;
				HubPark.set(level, x, FLOOR - 1, z, Blocks.STONE.defaultBlockState());
				if (edge) {
					// A soft padded wall, two high, in bright stripes.
					HubPark.set(level, x, FLOOR, z, Blocks.PACKED_ICE.defaultBlockState());
					HubPark.set(level, x, FLOOR + 1, z, Blocks.WOOL.pick((x + z) % 4 < 2 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
					HubPark.set(level, x, FLOOR + 2, z, Blocks.WOOL.pick((x + z) % 4 < 2 ? DyeColor.WHITE : DyeColor.RED).defaultBlockState());
					HubPark.set(level, x, FLOOR + 3, z, air);
				} else {
					// Ice, with a few blue "go faster" stripes.
					boolean stripe = (x - X1) % 8 == 4 && z > Z1 + 2 && z < Z2 - 2;
					HubPark.set(level, x, FLOOR, z, stripe ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState());
					for (int y = 1; y <= 4; y++) {
						HubPark.set(level, x, FLOOR + y, z, air);
					}
				}
			}
		}
		// Lamps round the outside (nothing overhead: the coaster's hills run past the far corner).
		for (int[] l : new int[][] {{X1 - 3, Z1 - 1}, {X1 - 3, Z2 + 1}, {(X1 + X2) / 2, Z2 + 3}, {X2 + 2, Z2 + 3}, {X1 + 6, Z2 + 3}}) {
			HubPark.lamp(level, l[0], FLOOR + 1, l[1]);
		}
		HubPark.label(level, (X1 + X2) / 2.0 + 0.5, FLOOR + 11.5, Z1 - 1.5, "BUMPER CARS", ChatFormatting.GREEN, 3.0F);
		HubPark.label(level, (X1 + X2) / 2.0 + 0.5, FLOOR + 11.5, Z2 + 2.5, "BUMPER CARS", ChatFormatting.GREEN, 3.0F);
		// A few cars parked by the gate, waiting for drivers.
		HubPark.model(level, new Vec3(96.0, FLOOR + 1, -4.5), 90, "bumper_boat_blue", 2.0F, 0.2F);
		HubPark.model(level, new Vec3(96.0, FLOOR + 1, 3.5), 90, "bumper_boat_yellow", 2.0F, 0.2F);
		HubHosts.add(level, new Vec3(96.5, FLOOR + 1, 0.5), 270, VillagerProfession.ARMORER, VillagerType.DESERT,
				new HubHosts.Host("bumper", "Buzz", "Bumper Cars", 0x60FF80, "minecraft:minecart",
						"Big ice rink, slippy-slidey cars! W to go, A and D to steer, S to slow down. Bonk your friends! Crouch to get out.",
						"Bumper Cars: right-click Buzz to drive!", HubHosts.rideButtons("Drive a bumper car!"), (p, key) -> {
							for (ServerPlayer q : HubHosts.group(p, key)) {
								drive(q);
							}
						}));
	}

	private static void drive(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (p.isPassenger()) {
			return;
		}
		if (CARS.size() >= COLORS.length) {
			p.sendOverlayMessage(Component.literal("All the cars are out! Try again in a moment.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		Set<String> used = new HashSet<>();
		for (Car c : CARS) {
			used.add(c.color());
		}
		String color = COLORS[0];
		for (String c : COLORS) {
			if (!used.contains(c)) {
				color = c;
				break;
			}
		}
		// Somewhere clear of the other cars.
		var random = level.getRandom();
		Vec3 at = null;
		for (int tries = 0; tries < 20 && at == null; tries++) {
			Vec3 t = new Vec3(X1 + 2 + random.nextDouble() * (X2 - X1 - 3), FLOOR + 1, Z1 + 2 + random.nextDouble() * (Z2 - Z1 - 3));
			boolean clear = true;
			for (Car c : CARS) {
				Entity b = level.getEntity(c.boat());
				if (b != null && b.position().distanceTo(t) < 3) {
					clear = false;
				}
			}
			if (clear) {
				at = t;
			}
		}
		if (at == null) {
			at = new Vec3(X1 + 2, FLOOR + 1, Z1 + 2);
		}
		float yaw = 90;
		net.minecraft.world.entity.vehicle.boat.AbstractBoat boat = EntityTypes.BAMBOO_RAFT.create(level, EntitySpawnReason.EVENT);
		if (boat == null) {
			return;
		}
		boat.snapTo(at.x, at.y, at.z, yaw, 0);
		boat.setPermanentlyInvulnerable(true);
		boat.addTag(HubRides.TEMP);
		HubPark.live(boat);
		level.addFreshEntity(boat);
		Display.ItemDisplay model = HubPark.tempModel(level, at, yaw, "bumper_boat_" + color, 2.0F, 0.0F);
		if (model == null) {
			boat.discard();
			return;
		}
		// The car rides behind the driver in the boat, so it's nudged forward to sit round them.
		((DisplayAccessor) model).rubymod$setTransformation(new Transformation(new Vector3f(0, 0.15F, 0.6F), new Quaternionf().rotateY((float) Math.PI),
				new Vector3f(2.0F, 2.0F, 2.0F), new Quaternionf()));
		((DisplayAccessor) model).rubymod$setPosRotInterpolationDuration(1);
		p.teleportTo(at.x, at.y, at.z);
		p.startRiding(boat, true, true);
		model.startRiding(boat, true, true);
		CARS.add(new Car(p.getUUID(), boat.getUUID(), model.getUUID(), color));
		p.sendOverlayMessage(Component.literal("W to go, A and D to steer. Bonk someone! (Crouch to get out)").withStyle(ChatFormatting.GREEN));
		level.playSound(null, p.blockPosition(), SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.6F, 1.6F);
	}

	static void tick(ServerLevel level) {
		tick++;
		for (Iterator<Car> it = CARS.iterator(); it.hasNext();) {
			Car c = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(c.driver());
			Entity boat = level.getEntity(c.boat());
			if (p == null || boat == null || p.getVehicle() != boat || !RINK.inflate(1.5, 3, 1.5).contains(boat.position())) {
				HubPark.discard(level, c.model());
				HubPark.discard(level, c.boat());
				LAST.remove(c.boat());
				it.remove();
				if (p != null && p.level() == level) {
					FunPark.letOff(p, EXIT, "Talk to Buzz to drive again!");
				}
			}
		}
		// Each car model faces the way its boat does.
		for (Car c : CARS) {
			Entity b = level.getEntity(c.boat());
			Entity m = level.getEntity(c.model());
			if (b != null && m != null) {
				m.setYRot(b.getYRot());
			}
		}
		// Bonks: two cars meeting fast enough, and the walls.
		List<Vec3> vel = new ArrayList<>();
		List<Entity> boats = new ArrayList<>();
		for (Car c : CARS) {
			Entity b = level.getEntity(c.boat());
			Vec3 last = LAST.getOrDefault(c.boat(), b.position());
			vel.add(b.position().subtract(last));
			LAST.put(c.boat(), b.position());
			boats.add(b);
			BONKED.merge(c.boat(), -1, Integer::sum);
		}
		for (int i = 0; i < boats.size(); i++) {
			for (int j = i + 1; j < boats.size(); j++) {
				Vec3 d = boats.get(j).position().subtract(boats.get(i).position());
				double dist = d.horizontalDistance();
				if (dist > 2.2 || dist < 1e-4) {
					continue;
				}
				Vec3 n = new Vec3(d.x / dist, 0, d.z / dist);
				double closing = vel.get(i).dot(n) - vel.get(j).dot(n);
				if (closing > 0.12 && BONKED.getOrDefault(boats.get(i).getUUID(), 0) <= 0 && BONKED.getOrDefault(boats.get(j).getUUID(), 0) <= 0) {
					Vec3 mid = boats.get(i).position().lerp(boats.get(j).position(), 0.5);
					level.playSound(null, BlockPos.containing(mid), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 1.4F, 1.3F);
					level.playSound(null, BlockPos.containing(mid), SoundEvents.SLIME_ATTACK, SoundSource.PLAYERS, 1.0F, 0.9F);
					level.sendParticles(ParticleTypes.CRIT, mid.x, mid.y + 0.8, mid.z, 16, 0.4, 0.2, 0.4, 0.25);
					for (int k : new int[] {i, j}) {
						BONKED.put(boats.get(k).getUUID(), 10);
						for (Entity e : boats.get(k).getPassengers()) {
							if (e instanceof ServerPlayer q) {
								q.sendOverlayMessage(Component.literal("BONK!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
							}
						}
					}
				}
			}
			Vec3 at = boats.get(i).position();
			boolean nearWall = at.x < X1 + 0.9 || at.x > X2 + 0.1 || at.z < Z1 + 0.9 || at.z > Z2 + 0.1;
			if (nearWall && vel.get(i).horizontalDistance() > 0.25 && BONKED.getOrDefault(boats.get(i).getUUID(), 0) <= 0) {
				BONKED.put(boats.get(i).getUUID(), 10);
				level.playSound(null, BlockPos.containing(at), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 0.8F);
			}
			if (vel.get(i).horizontalDistance() > 0.4 && tick % 4 == 0) {
				level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.1, at.z, 2, 0.3, 0.05, 0.3, 0.01);
			}
		}
		// Anything left on the ice that isn't one of ours (from before a restart) goes.
		if (tick % 100 == 0) {
			Set<UUID> ours = new HashSet<>();
			for (Car c : CARS) {
				ours.add(c.boat());
				ours.add(c.model());
			}
			for (Entity e : level.getEntities((Entity) null, RINK.inflate(2), e -> e.entityTags().contains(HubRides.TEMP) && !ours.contains(e.getUUID())
					&& (e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat || e instanceof Display.ItemDisplay))) {
				e.discard();
			}
		}
	}

	static boolean busy() {
		return !CARS.isEmpty();
	}

	static void reset() {
		CARS.clear();
		LAST.clear();
		BONKED.clear();
	}
}
