package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.mixin.DisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Toyland, just north of the plaza: the Toy Blaster (a dark ride: you ride through the toy room, outer space and a pirate
 * cove with a blaster, zapping targets for points) and the Petting Zoo (pens of friendly animals to pet and feed).
 */
final class Toyland {
	private Toyland() {
	}

	static final int[][] CIRCLES = {{4, 66, 22}, {22, 66, 14}, {-9, 87, 11}};
	static final int MX = 8;
	static final int MZ = 66;
	private static final String BLASTER = "Toy Blaster";
	private static final String SNACKS = "Animal Snacks";
	private static final Vec3 EXIT = new Vec3(14.5, FLOOR + 1, 46.5);

	private static TrackRide ride;

	/** A target: its model, where it stands, what it's worth; knocked down until a tick. */
	private static final class Target {
		UUID id;
		Vec3 at;
		int points;
		int downUntil;
	}

	private static final List<Target> TARGETS = new ArrayList<>();
	private static final Map<UUID, Integer> SCORE = new HashMap<>();
	private static final List<UUID> ANIMALS = new ArrayList<>();
	private static int tick;

	static void build(ServerLevel level) {
		TARGETS.clear();
		SCORE.clear();
		ANIMALS.clear();
		HubPark.blob(level, CIRCLES, FLOOR);
		HubPark.travelPad(level, 2, FLOOR, 46, "toyland");
		HubPark.label(level, 4.5, FLOOR + 13, 64.5, "TOYLAND", ChatFormatting.LIGHT_PURPLE, 3.0F);
		blasterBuilding(level);
		blasterRide(level);
		zoo(level);
		HelterSkelter.build(level);
		Butterflies.build(level);
	}

	// ----- The Toy Blaster -----

	private static void blasterBuilding(ServerLevel level) {
		BlockState air = Blocks.AIR.defaultBlockState();
		DyeColor[] stripes = {DyeColor.RED, DyeColor.YELLOW, DyeColor.BLUE, DyeColor.LIME};
		for (int x = -16; x <= 14; x++) {
			for (int z = 52; z <= 76; z++) {
				boolean edge = x == -16 || x == 14 || z == 52 || z == 76;
				// Room floors: the toy room's chequers, space's black, the pirate cove's planks.
				BlockState floor = z < 60 ? Blocks.CONCRETE.pick((x + z) % 2 == 0 ? DyeColor.WHITE : DyeColor.PINK).defaultBlockState()
						: z < 67 ? Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
				HubPark.set(level, x, FLOOR, z, floor);
				for (int y = 1; y <= 8; y++) {
					boolean door = z == 52 && y <= 4 && (x >= -4 && x <= -2 || x >= 11 && x <= 13);
					HubPark.set(level, x, FLOOR + y, z, edge && !door ? Blocks.WOOL.pick(stripes[Math.floorMod(x + z + y / 3, stripes.length)]).defaultBlockState() : air);
				}
				HubPark.set(level, x, FLOOR + 9, z, edge ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
			}
		}
		// The rooms' dividing walls (low enough to look over into the next room).
		for (int x = -16; x <= 3; x++) {
			for (int y = 1; y <= 4; y++) {
				HubPark.set(level, x, FLOOR + y, 59, Blocks.WOOL.pick(DyeColor.PURPLE).defaultBlockState());
			}
		}
		for (int x = -9; x <= 10; x++) {
			for (int y = 1; y <= 4; y++) {
				HubPark.set(level, x, FLOOR + y, 66, Blocks.WOOL.pick(DyeColor.BLUE).defaultBlockState());
			}
		}
		for (int z = 52; z <= 66; z++) {
			for (int y = 1; y <= 4; y++) {
				HubPark.set(level, 10, FLOOR + y, z, Blocks.WOOL.pick(DyeColor.BLUE).defaultBlockState());
			}
		}
		// Space: stars in the ceiling. The cove: a little pirate ship's bow and some treasure.
		for (int x = -15; x <= 13; x += 3) {
			for (int z = 61; z <= 65; z += 2) {
				HubPark.set(level, x + (z % 3), FLOOR + 9, z, Blocks.SEA_LANTERN.defaultBlockState());
			}
		}
		HubPark.box(level, -15, FLOOR + 1, 72, -12, FLOOR + 3, 75, Blocks.SPRUCE_PLANKS.defaultBlockState());
		HubPark.box(level, -15, FLOOR + 4, 74, -15, FLOOR + 7, 74, Blocks.SPRUCE_FENCE.defaultBlockState());
		HubPark.box(level, 0, FLOOR + 1, 75, 2, FLOOR + 1, 75, Blocks.GOLD_BLOCK.defaultBlockState());
		HubPark.set(level, 1, FLOOR + 2, 75, Blocks.CHEST.defaultBlockState());
		// Dim light all through.
		for (int x = -14; x <= 12; x += 3) {
			for (int z = 54; z <= 74; z += 3) {
				BlockPos lp = new BlockPos(x, FLOOR + 7, z);
				if (level.getBlockState(lp).isAir()) {
					level.setBlock(lp, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 13), 2);
				}
			}
		}
		// Outside: the queue, a sign, the station.
		HubPark.box(level, -5, FLOOR, 44, 15, FLOOR, 51, Blocks.SMOOTH_STONE.defaultBlockState());
		HubPark.label(level, -0.5, FLOOR + 11, 51.5, "TOY BLASTER", ChatFormatting.GOLD, 2.6F);
		HubPark.label(level, -0.5, FLOOR + 6.5, 51.2, "Ride through and zap the targets!", ChatFormatting.WHITE, 0.9F);
		HubPark.label(level, 12.5, FLOOR + 5.5, 51.2, "EXIT", ChatFormatting.GREEN, 1.0F);
	}

	private static void blasterRide(ServerLevel level) {
		double[][] c = {{-3, 46}, {-3, 50}, {-3, 53}, {-3, 56}, {6, 56}, {7.5, 59.5}, {6, 63}, {-12, 63}, {-13.5, 66.5}, {-12, 70}, {6, 70}, {9, 73},
			{12, 70}, {12, 60}, {12, 52}, {12, 48}};
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : c) {
			pts.add(new Vec3(p[0] + 0.5, FLOOR + 1, p[1] + 0.5));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		java.util.Arrays.fill(speed, 0.11);
		ride = new TrackRide("blaster", path, speed, "blaster_car", 2.0F, EXIT, "Sparky");
		ride.sideBySide = true;
		ride.upright = true;
		ride.waitTicks = 60;
		ride.onBoard = p -> {
			SCORE.put(p.getUUID(), 0);
			HubGames.hand(p, HubGames.toy(Items.BLAZE_ROD, 1, BLASTER, ChatFormatting.GOLD));
			int slot = p.getInventory().findSlotMatchingItem(blasterStack(p));
			if (slot >= 0 && slot < 9) {
				p.getInventory().setSelectedSlot(slot);
			}
			p.sendOverlayMessage(Component.literal("Right-click to zap the targets! Gold ones are worth 50.").withStyle(ChatFormatting.GOLD));
		};
		ride.onLeave = p -> {
			Integer s = SCORE.remove(p.getUUID());
			takeBlaster(p);
			if (s != null) {
				dev.rubymod.game.Alerts.title(p, s + " points!", s >= 400 ? "Blaster champion!" : "Nice zapping!", ChatFormatting.GOLD);
				Records.submit((ServerLevel) p.level(), "Toy Blaster score", p.getName().getString(), s, false, String::valueOf);
				if (s >= 300) {
					Prizes.plushie((ServerLevel) p.level(), p);
				}
			}
		};
		// Targets along the way, off to the sides, on the walls and up high.
		int k = 0;
		for (int i = 40; i < path.size() - 60; i += 10) {
			Vec3 p = path.get(i);
			Vec3 q = path.get(Math.min(path.size() - 1, i + 2));
			Vec3 dir = new Vec3(q.x - p.x, 0, q.z - p.z);
			if (dir.lengthSqr() < 1e-6) {
				continue;
			}
			dir = dir.normalize();
			Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(k % 2 == 0 ? 1 : -1);
			Vec3 at = p.add(side.scale(3.6 + (k % 3) * 0.7)).add(dir.scale(3)).add(0, 1.2 + (k % 4) * 0.7, 0);
			k++;
			BlockPos b = BlockPos.containing(at);
			if (!level.getBlockState(b).isAir() || at.x < -15 || at.x > 13.5 || at.z < 53 || at.z > 75.5 || tooClose(path, at)) {
				continue;
			}
			boolean gold = k % 5 == 0;
			String look = gold ? "target_gold" : at.z < 60 ? "target_red" : at.z < 67 ? "target_green" : "target_red";
			Display.ItemDisplay d = HubPark.model(level, at, 0, look, 1.1F, 0);
			if (d == null) {
				continue;
			}
			((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
			// Face the track.
			Vec3 to = p.subtract(at);
			d.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)) + 180);
			Target t = new Target();
			t.id = d.getUUID();
			t.at = at;
			t.points = gold ? 50 : 10;
			TARGETS.add(t);
		}
		HubHosts.add(level, new Vec3(-5.5, FLOOR + 1, 47.5), 90, VillagerProfession.FLETCHER, VillagerType.SNOW,
				new HubHosts.Host("blaster", "Sparky", "Toy Blaster", 0xFFC040, "minecraft:blaze_rod",
						"Ride through the toy room, outer space and the pirate cove with a Toy Blaster: right-click to zap the targets! "
								+ "Red and green are worth 10, gold are worth 50. Two to a car. What's your best score?",
						"Toy Blaster: right-click Sparky to ride!", HubHosts.rideButtons("Ride the Toy Blaster!"), (p, key) -> ride.board(p, HubHosts.group(p, key))));
	}

	private static boolean tooClose(List<Vec3> path, Vec3 at) {
		for (Vec3 p : path) {
			if (Math.hypot(p.x - at.x, p.z - at.z) < 2.6 && Math.abs(p.y - at.y) < 4) {
				return true;
			}
		}
		return false;
	}

	private static ItemStack blasterStack(ServerPlayer p) {
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (HubGames.isToy(s) && s.getHoverName().getString().equals(BLASTER)) {
				return s;
			}
		}
		return ItemStack.EMPTY;
	}

	private static void takeBlaster(ServerPlayer p) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (HubGames.isToy(s) && s.getHoverName().getString().equals(BLASTER)) {
				p.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
		p.inventoryMenu.broadcastChanges();
	}

	/** Right-click with the blaster: zap! True if it was the blaster. */
	static boolean shoot(ServerPlayer p, ItemStack held) {
		if (!HubGames.isToy(held) || !held.getHoverName().getString().equals(BLASTER)) {
			return false;
		}
		if (p.getCooldowns().isOnCooldown(held)) {
			return true;
		}
		p.getCooldowns().addCooldown(held, 6);
		ServerLevel level = (ServerLevel) p.level();
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		Target best = null;
		double bestT = 22;
		for (Target t : TARGETS) {
			if (t.downUntil > tick) {
				continue;
			}
			Vec3 to = t.at.subtract(eye);
			double along = to.dot(look);
			if (along <= 0 || along > bestT) {
				continue;
			}
			if (to.subtract(look.scale(along)).length() < 0.75) {
				best = t;
				bestT = along;
			}
		}
		Vec3 end = eye.add(look.scale(bestT));
		if (best != null) {
			HitResult wall = level.clip(new ClipContext(eye, best.at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			if (wall.getType() == HitResult.Type.BLOCK && wall.getLocation().distanceTo(eye) < bestT - 0.8) {
				best = null;
				end = wall.getLocation();
			}
		}
		// The zap: a line of sparkles.
		for (double d = 0.8; d < eye.distanceTo(end); d += 0.6) {
			Vec3 q = eye.add(look.scale(d));
			level.sendParticles(new DustParticleOptions(0xFFFFD040, 0.7F), q.x, q.y - 0.15, q.z, 1, 0, 0, 0, 0);
		}
		level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.8F);
		if (best != null) {
			best.downUntil = tick + 70;
			Entity e = level.getEntity(best.id);
			if (e != null) {
				e.teleportTo(best.at.x, best.at.y - 30, best.at.z);
			}
			int score = SCORE.merge(p.getUUID(), best.points, Integer::sum);
			level.sendParticles(ParticleTypes.FIREWORK, best.at.x, best.at.y, best.at.z, 14, 0.2, 0.2, 0.2, 0.08);
			level.playSound(null, BlockPos.containing(best.at), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.2F, best.points > 10 ? 2.0F : 1.4F);
			p.sendOverlayMessage(Component.literal((best.points > 10 ? "GOLD! +" : "+") + best.points + "   Score: " + score)
					.withStyle(best.points > 10 ? ChatFormatting.GOLD : ChatFormatting.YELLOW, ChatFormatting.BOLD));
		}
		return true;
	}

	// ----- The Petting Zoo -----

	private static void zoo(ServerLevel level) {
		BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
		int[][] pens = {{18, 52, 30, 60}, {18, 62, 30, 70}, {18, 72, 30, 80}};
		for (int[] pen : pens) {
			for (int x = pen[0]; x <= pen[2]; x++) {
				for (int z = pen[1]; z <= pen[3]; z++) {
					boolean edge = x == pen[0] || x == pen[2] || z == pen[1] || z == pen[3];
					HubPark.set(level, x, FLOOR + 1, z, edge ? fence : Blocks.AIR.defaultBlockState());
					if (!edge && (x * 3 + z) % 9 == 0) {
						HubPark.set(level, x, FLOOR + 1, z, Blocks.SHORT_GRASS.defaultBlockState());
					}
				}
			}
			HubPark.set(level, pen[0] + 2, FLOOR + 1, pen[1] + 2, Blocks.HAY_BLOCK.defaultBlockState());
			HubPark.set(level, pen[2] - 2, FLOOR + 1, pen[3] - 2, Blocks.WATER_CAULDRON.defaultBlockState());
		}
		HubPark.label(level, 24.5, FLOOR + 6, 51.5, "PETTING ZOO", ChatFormatting.GREEN, 2.0F);
		HubPark.label(level, 24.5, FLOOR + 4.2, 51.5, "Right-click the animals to pet them!", ChatFormatting.WHITE, 0.7F);
		// Who lives where (and a few babies).
		animal(level, EntityTypes.SHEEP, 21, 55, false, DyeColor.PINK);
		animal(level, EntityTypes.SHEEP, 27, 57, true, DyeColor.LIGHT_BLUE);
		animal(level, EntityTypes.SHEEP, 24, 58, true, DyeColor.YELLOW);
		animal(level, EntityTypes.GOAT, 26, 54, false, null);
		animal(level, EntityTypes.GOAT, 22, 58, true, null);
		animal(level, EntityTypes.PIG, 21, 65, false, null);
		animal(level, EntityTypes.PIG, 23, 67, true, null);
		animal(level, EntityTypes.COW, 27, 65, false, null);
		animal(level, EntityTypes.COW, 26, 68, true, null);
		animal(level, EntityTypes.CHICKEN, 20, 68, false, null);
		animal(level, EntityTypes.CHICKEN, 24, 64, true, null);
		animal(level, EntityTypes.RABBIT, 21, 75, false, null);
		animal(level, EntityTypes.RABBIT, 23, 78, true, null);
		animal(level, EntityTypes.LLAMA, 27, 76, false, null);
		animal(level, EntityTypes.TURTLE, 25, 74, true, null);
		animal(level, EntityTypes.ARMADILLO, 20, 78, false, null);
		HubHosts.add(level, new Vec3(16.5, FLOOR + 1, 66.5), 270, VillagerProfession.FARMER, VillagerType.PLAINS,
				new HubHosts.Host("zoo", "Zara", "Petting Zoo", 0x70E070, "minecraft:wheat",
						"Come and meet the animals! Right-click them to give them a pet. Want some snacks for them? They love a treat.",
						"Petting Zoo: right-click Zara for animal snacks!", HubHosts.play("Animal snacks, please!"), (p, key) -> {
							HubGames.hand(p, HubGames.toy(Items.WHEAT, 1, SNACKS, ChatFormatting.GREEN));
							p.sendOverlayMessage(Component.literal("Right-click an animal to give it a snack!").withStyle(ChatFormatting.GREEN));
						}));
	}

	private static void animal(ServerLevel level, EntityType<? extends Mob> type, int x, int z, boolean baby, DyeColor wool) {
		Mob m = type.create(level, EntitySpawnReason.EVENT);
		if (m == null) {
			return;
		}
		m.snapTo(x + 0.5, FLOOR + 1, z + 0.5, level.getRandom().nextFloat() * 360, 0);
		m.setPermanentlyInvulnerable(true);
		m.setPersistenceRequired();
		if (baby && m instanceof AgeableMob a) {
			a.setAge(-24000);
		}
		if (wool != null && m instanceof net.minecraft.world.entity.animal.sheep.Sheep s) {
			s.setColor(wool);
		}
		HubPark.keep(m);
		level.addFreshEntity(m);
		ANIMALS.add(m.getUUID());
	}

	/** Right-clicking one of the zoo's animals: a pet (or a snack). True if it was one. */
	static boolean pet(ServerPlayer p, Entity e) {
		if (!ANIMALS.contains(e.getUUID())) {
			return false;
		}
		ServerLevel level = (ServerLevel) p.level();
		ItemStack held = p.getMainHandItem();
		boolean snack = HubGames.isToy(held) && held.getHoverName().getString().equals(SNACKS);
		level.sendParticles(snack ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), snack ? 8 : 3, 0.3, 0.2,
				0.3, 0.02);
		level.playSound(null, e.blockPosition(), snack ? SoundEvents.GENERIC_EAT.value() : SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.8F, 1.2F);
		String name = e.getType().getDescription().getString().toLowerCase();
		boolean little = e instanceof AgeableMob a && a.isBaby();
		p.sendOverlayMessage(Component.literal(snack ? "Munch munch! The " + (little ? "baby " : "") + name + " loves it!"
				: "You pet the " + (little ? "baby " : "") + name + ". It's very happy!").withStyle(ChatFormatting.GREEN));
		return true;
	}

	static void tick(ServerLevel level, int t) {
		tick = t;
		HelterSkelter.tick(level);
		if (ride != null) {
			ride.tick(level);
		}
		// Knocked-down targets pop back up.
		for (Target tg : TARGETS) {
			if (tg.downUntil > 0 && tg.downUntil <= tick) {
				tg.downUntil = 0;
				Entity e = level.getEntity(tg.id);
				if (e != null) {
					e.teleportTo(tg.at.x, tg.at.y, tg.at.z);
				}
			}
		}
		// The babies stay babies.
		if (tick % 600 == 0) {
			for (UUID id : ANIMALS) {
				if (level.getEntity(id) instanceof AgeableMob a && a.isBaby()) {
					a.setAge(-24000);
				}
			}
		}
	}

	static boolean busy() {
		return ride != null && !ride.cars.isEmpty() || HelterSkelter.busy();
	}

	static void reset() {
		HelterSkelter.reset();
		if (ride != null) {
			ride.reset();
		}
		TARGETS.clear();
		SCORE.clear();
		ANIMALS.clear();
	}
}
