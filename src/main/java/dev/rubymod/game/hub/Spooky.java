package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Spooky Hollow: a dark little island with a graveyard, dead trees and a haunted house, and the Ghost Train that
 * rattles through it. Ghosts rise from the floor, a skeleton leaps out, the lights go out and a spider drops in your
 * face, bats swoop, and lightning cracks over the graveyard on the way out.
 */
final class Spooky {
	private Spooky() {
	}

	static final int CX = 44;
	static final int CZ = 40;
	static final Vec3 LAND = new Vec3(32.5, FLOOR + 1, 44.5);
	private static final double[][] TRACK = {{35.5, 41.5}, {35.5, 43.5}, {36.5, 45.2}, {38.5, 45.5}, {46, 45.5}, {48.8, 45.2}, {49.5, 43.5}, {48.8, 41},
		{46, 40.5}, {43, 40.5}, {41, 40}, {40.5, 38}, {41.5, 36.5}, {44, 36.5}, {49, 36.5}, {52, 36.5}, {54, 34.5}, {52.5, 31.5}, {46, 30.5}, {38, 31},
		{34.5, 33.5}, {34.5, 37.5}, {35.5, 40}, {35.5, 41.5}};

	private static TrackRide ride;

	/** A scary thing: hidden at {@code hide}, it jumps to {@code show} when a car passes, and goes back after a while. */
	private record Prop(UUID id, Vec3 hide, Vec3 show) {
	}

	private static final List<Prop> PROPS = new ArrayList<>();
	private static final List<int[]> RETURNS = new ArrayList<>();
	private static int tick;

	static void build(ServerLevel level) {
		PROPS.clear();
		RETURNS.clear();
		HubPark.island(level, CX, CZ, 13, FLOOR);
		// Dark ground, a spiky iron fence round the edge instead of the hedge.
		for (int x = -14; x <= 14; x++) {
			for (int z = -14; z <= 14; z++) {
				BlockPos top = new BlockPos(CX + x, FLOOR, CZ + z);
				if (level.getBlockState(top).is(Blocks.GRASS_BLOCK)) {
					level.setBlock(top, ((x * 7 + z * 13) & 3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.PODZOL.defaultBlockState(), 2);
				}
				if (level.getBlockState(top.above()).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
					level.setBlock(top.above(), Blocks.IRON_BARS.defaultBlockState(), 2);
				}
			}
		}
		house(level);
		graveyard(level);
		// The station: a little platform with a purple-and-black awning.
		HubPark.box(level, 33, FLOOR, 40, 34, FLOOR, 46, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		for (int z = 40; z <= 46; z++) {
			for (int x = 32; x <= 37; x++) {
				HubPark.set(level, x, FLOOR + 5, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? DyeColor.PURPLE : DyeColor.BLACK).defaultBlockState());
			}
		}
		for (int z : new int[] {40, 46}) {
			HubPark.box(level, 32, FLOOR + 1, z, 32, FLOOR + 4, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
			HubPark.box(level, 37, FLOOR + 1, z, 37, FLOOR + 4, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		HubPark.set(level, 32, FLOOR + 1, 43, Blocks.JACK_O_LANTERN.defaultBlockState());
		HubPark.label(level, 35.5, FLOOR + 6.6, 43.5, "GHOST TRAIN", ChatFormatting.DARK_PURPLE, 1.8F);
		HubPark.label(level, CX + 0.5, FLOOR + 15, CZ + 0.5, "SPOOKY HOLLOW", ChatFormatting.LIGHT_PURPLE, 2.6F);
		HubPark.travelPad(level, 37, FLOOR, 50, "spooky");
		// The track.
		List<Vec3> pts = new ArrayList<>();
		for (double[] c : TRACK) {
			pts.add(new Vec3(c[0], FLOOR + 1, c[1]));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		java.util.Arrays.fill(speed, 0.16);
		ride = new TrackRide("ghost", path, speed, "ghost_car", 1.8F, new Vec3(33.5, FLOOR + 1, 47.5), "Mortimer");
		BlockState rail = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
		int seg = (int) Math.round(2.0 / TrackRide.STEP);
		for (int i = 0; i + seg < path.size(); i += seg) {
			Vec3 a = path.get(i);
			Vec3 b = path.get(i + seg);
			Vec3 dir = b.subtract(a).normalize();
			Vec3 side = new Vec3(-dir.z, 0, dir.x);
			for (int s : new int[] {-1, 1}) {
				HubPark.beam(level, a.add(side.scale(0.42 * s)).add(0, 0.04, 0), b.add(side.scale(0.42 * s)).add(0, 0.04, 0), 0.12F, 0.08F, rail);
			}
		}
		// The scares.
		scare(level, path, 42, 45.5, "ghost", new Vec3(44.5, FLOOR - 1.6, 46.8), new Vec3(44.5, FLOOR + 1.1, 46.6), 1.4F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(44.5, FLOOR + 2, 46.5), ModSounds.PARK_GHOST, SoundSource.AMBIENT, 1.5F, 1.0F);
			lvl.sendParticles(ParticleTypes.SOUL, 44.5, FLOOR + 2, 46.6, 12, 0.4, 0.6, 0.2, 0.02);
		});
		scare(level, path, 49.3, 44.5, "skeleton", new Vec3(50.3, FLOOR - 2.2, 42.8), new Vec3(50.3, FLOOR + 1, 42.8), 1.6F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(50.3, FLOOR + 2, 42.8), ModSounds.PARK_BOO, SoundSource.AMBIENT, 1.4F, 1.0F);
			lvl.playSound(null, BlockPos.containing(50.3, FLOOR + 2, 42.8), SoundEvents.SKELETON_AMBIENT, SoundSource.AMBIENT, 1.2F, 0.8F);
		});
		scare(level, path, 47.5, 40.5, "spider", new Vec3(44.8, FLOOR + 7.2, 40.5), new Vec3(44.8, FLOOR + 1.9, 40.5), 1.3F, (lvl, car) -> {
			for (ServerPlayer p : ride.riders(lvl, car)) {
				p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, false, false));
			}
			lvl.playSound(null, BlockPos.containing(44.8, FLOOR + 2, 40.5), SoundEvents.SPIDER_AMBIENT, SoundSource.AMBIENT, 1.4F, 0.7F);
			lvl.playSound(null, BlockPos.containing(44.8, FLOOR + 2, 40.5), ModSounds.PARK_BOO, SoundSource.AMBIENT, 1.0F, 0.8F);
		});
		scare(level, path, 42.8, 36.5, "bat", new Vec3(44.5, FLOOR + 6.6, 35.2), new Vec3(44.0, FLOOR + 2.8, 37.6), 0.9F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(44, FLOOR + 3, 36.5), SoundEvents.BAT_TAKEOFF, SoundSource.AMBIENT, 1.4F, 1.0F);
			lvl.sendParticles(ParticleTypes.SMOKE, 44, FLOOR + 3, 36.5, 20, 0.8, 0.4, 0.8, 0.02);
		});
		scare(level, path, 46.5, 36.5, "ghost", new Vec3(48.3, FLOOR + 8.5, 35.3), new Vec3(48.3, FLOOR + 1.4, 35.3), 1.4F, (lvl, car) -> {
			lvl.playSound(null, BlockPos.containing(48.3, FLOOR + 2, 35.3), ModSounds.PARK_GHOST, SoundSource.AMBIENT, 1.5F, 0.8F);
		});
		ride.events.put(nearest(path, 52.5, 35.8), (lvl, car) -> {
			LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(lvl, EntitySpawnReason.EVENT);
			if (bolt != null) {
				bolt.snapTo(46.5, FLOOR + 1, 28.5, 0, 0);
				bolt.setVisualOnly(true);
				lvl.addFreshEntity(bolt);
			}
			ride.tell(lvl, car, "BOO! You survived the Ghost Train!");
		});
		HubHosts.add(level, new Vec3(33.5, FLOOR + 1, 41.5), 270, VillagerProfession.LEATHERWORKER, VillagerType.SWAMP,
				new HubHosts.Host("ghost", "Mortimer", "Ghost Train", 0xB070FF, "minecraft:carved_pumpkin",
						"Dare you ride the Ghost Train through the haunted house? Ghosts, skeletons, spiders... it's only a little bit scary. "
								+ "Two people fit in a car.",
						"Ghost Train: right-click Mortimer... if you dare!", HubHosts.rideButtons("Ride the Ghost Train!"),
						(p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	private static void house(ServerLevel level) {
		BlockState wall = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
		BlockState beam = Blocks.DARK_OAK_LOG.defaultBlockState();
		HubPark.clear(level, 38, FLOOR + 1, 34, 51, FLOOR + 14, 47);
		HubPark.box(level, 38, FLOOR, 34, 51, FLOOR, 47, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		for (int x = 38; x <= 51; x++) {
			for (int z = 34; z <= 47; z++) {
				boolean edge = x == 38 || x == 51 || z == 34 || z == 47;
				boolean corner = (x == 38 || x == 51) && (z == 34 || z == 47);
				if (!edge) {
					continue;
				}
				for (int y = FLOOR + 1; y <= FLOOR + 6; y++) {
					boolean door = x == 38 && z >= 44 && z <= 46 && y <= FLOOR + 3 || x == 51 && z >= 35 && z <= 37 && y <= FLOOR + 3;
					boolean window = !corner && y == FLOOR + 4 && (x + z) % 4 == 0;
					HubPark.set(level, x, y, z, door ? Blocks.AIR.defaultBlockState() : window ? Blocks.STAINED_GLASS.pick(DyeColor.PURPLE).defaultBlockState()
							: corner || y == FLOOR + 6 ? beam : wall);
				}
			}
		}
		// Inside: two walls make three dark rooms the track winds through.
		HubPark.box(level, 39, FLOOR + 1, 43, 47, FLOOR + 5, 43, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		HubPark.box(level, 42, FLOOR + 1, 38, 50, FLOOR + 5, 38, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		HubPark.box(level, 39, FLOOR + 6, 35, 50, FLOOR + 6, 46, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		for (int[] c : new int[][] {{39, 46}, {50, 46}, {39, 39}, {50, 35}, {39, 35}}) {
			HubPark.set(level, c[0], FLOOR + 5, c[1], Blocks.COBWEB.defaultBlockState());
		}
		for (int[] l : new int[][] {{44, 47}, {51, 42}, {45, 34}}) {
			HubPark.set(level, l[0], FLOOR + 3, l[1], Blocks.SOUL_LANTERN.defaultBlockState());
		}
		// A stepped roof, a crooked tower at the corner, pumpkins by the door.
		for (int i = 0; i < 6; i++) {
			HubPark.box(level, 37 + i, FLOOR + 7 + i, 33 + i, 52 - i, FLOOR + 7 + i, 48 - i, Blocks.DEEPSLATE_TILES.defaultBlockState());
		}
		HubPark.box(level, 39, FLOOR + 7, 35, 41, FLOOR + 15, 37, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
		HubPark.set(level, 40, FLOOR + 12, 34, Blocks.STAINED_GLASS.pick(DyeColor.ORANGE).defaultBlockState());
		HubPark.set(level, 40, FLOOR + 16, 36, Blocks.DEEPSLATE_TILES.defaultBlockState());
		HubPark.set(level, 40, FLOOR + 17, 36, Blocks.END_ROD.defaultBlockState());
		HubPark.set(level, 37, FLOOR + 1, 43, Blocks.JACK_O_LANTERN.defaultBlockState());
		HubPark.set(level, 37, FLOOR + 1, 47, Blocks.CARVED_PUMPKIN.defaultBlockState());
	}

	private static void graveyard(ServerLevel level) {
		for (int x = 40; x <= 50; x += 2) {
			HubPark.set(level, x, FLOOR + 1, 33, (x % 4 == 0 ? Blocks.MOSSY_COBBLESTONE_WALL : Blocks.COBBLESTONE_WALL).defaultBlockState());
		}
		for (int[] g : new int[][] {{42, 29}, {45, 28}, {48, 29}, {51, 30}}) {
			HubPark.set(level, g[0], FLOOR + 1, g[1], Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState());
		}
		HubPark.set(level, 46, FLOOR + 1, 32, Blocks.JACK_O_LANTERN.defaultBlockState());
		// Dead trees: bare trunks with crooked branches.
		for (int[] t : new int[][] {{37, 37}, {54, 42}, {41, 51}}) {
			HubPark.box(level, t[0], FLOOR + 1, t[1], t[0], FLOOR + 5, t[1], Blocks.DARK_OAK_LOG.defaultBlockState());
			HubPark.set(level, t[0] + 1, FLOOR + 4, t[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			HubPark.set(level, t[0] - 1, FLOOR + 5, t[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			HubPark.set(level, t[0], FLOOR + 6, t[1] + 1, Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
	}

	private static int nearest(List<Vec3> path, double x, double z) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = Math.hypot(path.get(i).x - x, path.get(i).z - z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	private static void scare(ServerLevel level, List<Vec3> path, double x, double z, String model, Vec3 hide, Vec3 show, float size,
			java.util.function.BiConsumer<ServerLevel, TrackRide.Car> also) {
		Display.ItemDisplay d = HubPark.model(level, hide, 90, model, size, 0);
		if (d == null) {
			return;
		}
		((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(12, 12));
		Prop prop = new Prop(d.getUUID(), hide, show);
		PROPS.add(prop);
		int index = PROPS.size() - 1;
		ride.events.put(nearest(path, x, z), (lvl, car) -> {
			Entity e = lvl.getEntity(prop.id());
			if (e != null) {
				((DisplayAccessor) e).rubymod$setPosRotInterpolationDuration(3);
				e.teleportTo(prop.show().x, prop.show().y, prop.show().z);
				Vec3 look = car.pos.subtract(prop.show());
				e.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)) + 180);
				RETURNS.add(new int[] {index, tick + 45});
			}
			also.accept(lvl, car);
		});
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
		if (tick % 8 == 0) {
			var random = level.getRandom();
			level.sendParticles(ParticleTypes.WHITE_ASH, CX + (random.nextDouble() - 0.5) * 24, FLOOR + 1.5, CZ + (random.nextDouble() - 0.5) * 24, 6, 1.5, 0.3,
					1.5, 0.01);
		}
	}

	static void reset() {
		if (ride != null) {
			ride.reset();
		}
		PROPS.clear();
		RETURNS.clear();
	}
}
