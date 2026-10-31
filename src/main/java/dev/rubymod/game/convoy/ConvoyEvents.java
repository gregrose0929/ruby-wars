package dev.rubymod.game.convoy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.game.rift.RiftFx;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The set pieces along the road, one per stage, and what happens between them:
 *
 * <ul>
 * <li>Greenwood Road: fallen logs across the road. Shoot them (or blow them up) to clear the way.</li>
 * <li>Ember Town: Legion tanks roll into the square. The truck can't get past until they're scrap.</li>
 * <li>Red Canyon: the bridge is out. Engineers lay a new one while you hold them off (and a gunship attacks).</li>
 * <li>Frost Pass: boulders roll down the mountains onto red circles, then an avalanche buries the road.</li>
 * <li>Iron Fortress: the Iron Dreadnought (see {@link Dreadnought}).</li>
 * </ul>
 *
 * Ambushes are set off as the truck reaches them, never behind it: the Legion comes from up
 * the road and the sides, out of red smoke a second before it arrives. Each stage also hides
 * a supply cache off the road (stand on it to open it).
 */
final class ConvoyEvents {
	/** How far into Ember Town its square is. */
	static final int SQUARE = 88;
	static final int LOGS = ConvoyRoute.START[0] + 84;
	static final int SNOW_WALL = ConvoyRoute.START[3] + 84;
	static final int BRIDGE_TICKS = 55 * 20;
	/** Where the ambushes are, along each stage. */
	private static final int[][] AMBUSHES = {{14, 38, 60, 104, 128}, {12, 36, 60, 106, 130}, {14, 36, 92, 116, 136}, {12, 34, 56, 108, 130},
			{10, 30}};

	/** Blocks of the barricade (logs or snow) still standing, and how many more hits each can take. */
	static final Map<BlockPos, Integer> BARRICADE = new HashMap<>();
	static int barricadeTotal;
	private static int barricadeWaves;
	private static boolean avalanched;
	static boolean tanksCalled;
	static int bridge;
	private static int bridgeWave;
	private static int bridgeLaid;
	static boolean bridgeDone;
	private static int nextAmbush;
	/** Monsters on their way in (red smoke first). */
	private record Pending(BlockPos at, ConvoyEnemies.Kind kind, int[] left) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	/** Boulders rolling down the mountains in Frost Pass. */
	private static final class Boulder {
		UUID display;
		Vec3 from;
		Vec3 to;
		int t;
		int warn;
	}

	private static final List<Boulder> BOULDERS = new ArrayList<>();
	private static int boulderIn = 100;
	/** This stage's supply cache: where, its glowing crate, and how long someone's been standing on it. */
	static BlockPos cache;
	private static UUID cacheDisplay;
	private static int cacheProgress;
	static ConvoyGunship gunship;

	private ConvoyEvents() {
	}

	/** Parking pads in each depot: {x offset, z offset, 1 = helipad}. */
	static int[][] pads(int depot) {
		return switch (depot) {
			case 2, 4 -> new int[][] {{-8, 8, 0}, {8, 8, 1}, {-8, 17, 0}};
			default -> new int[][] {{-8, 8, 0}, {8, 8, 0}, {-8, 17, 0}};
		};
	}

	static void clear(ServerLevel level) {
		PENDING.clear();
		BARRICADE.clear();
		for (Boulder b : BOULDERS) {
			Entity d = level.getEntity(b.display);
			if (d != null) {
				d.discard();
			}
		}
		BOULDERS.clear();
		removeCache(level);
		if (gunship != null) {
			gunship.remove(level);
			gunship = null;
		}
	}

	/** A stage starts (or starts again after a wreck): set its piece up fresh. */
	static void startStage(ServerLevel level, int stage, double truckZ, RandomSource random) {
		clear(level);
		tanksCalled = false;
		bridge = 0;
		bridgeWave = 0;
		bridgeLaid = 0;
		bridgeDone = false;
		barricadeWaves = 0;
		avalanched = false;
		boulderIn = 120;
		nextAmbush = 0;
		int[] list = AMBUSHES[stage];
		while (nextAmbush < list.length && ConvoyRoute.START[stage] + list[nextAmbush] < truckZ + 8) {
			nextAmbush++;
		}
		if (stage == 0) {
			barricade(level, LOGS, Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
		} else if (stage == 2) {
			// The bridge: gone again.
			for (int z = ConvoyRoute.GAP_FROM; z < ConvoyRoute.GAP_TO; z++) {
				int c = (int) Math.floor(ConvoyRoute.cx(z));
				for (int dx = -ConvoyRoute.ROAD - 1; dx <= ConvoyRoute.ROAD + 1; dx++) {
					for (int y = 0; y <= 1; y++) {
						BlockPos p = new BlockPos(c + dx, ConvoyRoute.Y + y, z);
						if (!level.getBlockState(p).isAir()) {
							level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
					}
				}
			}
		} else if (stage == 3) {
			BARRICADE.clear();
		}
		placeCache(level, stage, random);
	}

	/** Skipped ahead (autotests): the ambushes behind the truck don't happen. */
	static void skipAmbushes(int stage, double truckZ) {
		int[] list = AMBUSHES[stage];
		while (nextAmbush < list.length && ConvoyRoute.START[stage] + list[nextAmbush] < truckZ + 8) {
			nextAmbush++;
		}
	}

	// ----- The barricades (logs, then snow) -----

	private static void barricade(ServerLevel level, int z0, BlockState log, BlockState top, int hits) {
		BARRICADE.clear();
		for (int z = z0; z < z0 + 3; z++) {
			int c = (int) Math.floor(ConvoyRoute.cx(z));
			for (int dx = -ConvoyRoute.ROAD - 2; dx <= ConvoyRoute.ROAD + 2; dx++) {
				int h = Math.abs(dx) <= ConvoyRoute.ROAD ? 2 : 1;
				for (int y = 1; y <= h; y++) {
					BlockPos p = new BlockPos(c + dx, ConvoyRoute.Y + y, z);
					level.setBlock(p, log, Block.UPDATE_CLIENTS);
					BARRICADE.put(p, hits);
				}
				if (Math.abs(dx) <= ConvoyRoute.ROAD && (dx + z) % 2 == 0) {
					BlockPos p = new BlockPos(c + dx, ConvoyRoute.Y + 3, z);
					level.setBlock(p, top, Block.UPDATE_CLIENTS);
					BARRICADE.put(p, 1);
				}
			}
		}
		barricadeTotal = BARRICADE.size();
	}

	static float barricadeLeft() {
		return barricadeTotal == 0 ? 0 : BARRICADE.size() / (float) barricadeTotal;
	}

	/** A bullet hit a block: chip at the barricade. */
	static void onImpact(ServerLevel level, BlockPos pos) {
		Integer hp = BARRICADE.get(pos);
		if (hp == null) {
			return;
		}
		if (hp <= 1) {
			breakBlock(level, pos);
		} else {
			BARRICADE.put(pos, hp - 1);
		}
	}

	/** An explosion: everything of the barricade close by goes. */
	static void onBlast(ServerLevel level, Vec3 at, float power) {
		if (BARRICADE.isEmpty()) {
			return;
		}
		double r = 1.5 + power * 0.8;
		for (BlockPos p : new ArrayList<>(BARRICADE.keySet())) {
			if (Vec3.atCenterOf(p).distanceTo(at) <= r) {
				breakBlock(level, p);
			}
		}
	}

	private static void breakBlock(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BARRICADE.remove(pos);
		if (!state.isAir()) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.3, 0.3, 0.3,
					0.1);
			level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.8F, 0.9F);
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private static void clearBarricade(ServerLevel level) {
		for (BlockPos p : new ArrayList<>(BARRICADE.keySet())) {
			breakBlock(level, p);
		}
		BARRICADE.clear();
	}

	// ----- How far the truck may go right now -----

	/** The furthest the truck can drive at the moment (a set piece in the way stops it short). */
	static double stopAt(ServerLevel level, int stage) {
		switch (stage) {
			case 0 -> {
				if (!BARRICADE.isEmpty()) {
					return LOGS - 5;
				}
			}
			case 1 -> {
				int square = ConvoyRoute.START[1] + SQUARE;
				if (!tanksCalled || ConvoyEnemies.tanksLeft(level) > 0) {
					return square - 6;
				}
			}
			case 2 -> {
				if (!bridgeDone) {
					return ConvoyRoute.GAP_FROM - 5;
				}
			}
			case 3 -> {
				if (!BARRICADE.isEmpty()) {
					return SNOW_WALL - 5;
				}
			}
			case 4 -> {
				if (!Convoy.bossBeaten) {
					return ConvoyRoute.COURTYARD + 6;
				}
			}
			default -> {
			}
		}
		// Nothing in the way (the depots and the vault are Convoy's to stop at).
		return Double.MAX_VALUE;
	}

	/** What the team needs to do right now ("" when it's just "keep the truck moving"), and how far along it is (-1: no bar). */
	static String objective(ServerLevel level, int stage, double truckZ) {
		if (stage == 0 && !BARRICADE.isEmpty() && truckZ > LOGS - 30) {
			return "Clear the fallen logs: shoot them or blow them up!";
		}
		if (stage == 1 && tanksCalled && ConvoyEnemies.tanksLeft(level) > 0) {
			int n = ConvoyEnemies.tanksLeft(level);
			return "Destroy the Legion tank" + (n == 1 ? "" : "s") + " (" + n + " left): rockets and tanks work best!";
		}
		if (stage == 2 && !bridgeDone && truckZ > ConvoyRoute.GAP_FROM - 14) {
			return "The bridge is out! Guard the engineers while they build it.";
		}
		if (stage == 3 && !BARRICADE.isEmpty()) {
			return "AVALANCHE! Dig the truck out: shoot the snow!";
		}
		if (stage == 3 && truckZ > ConvoyRoute.START[3] + 18 && truckZ < ConvoyRoute.START[3] + 140) {
			return "Boulders! Keep out of the red circles.";
		}
		if (stage == 4 && !Convoy.bossBeaten && truckZ > ConvoyRoute.COURTYARD - 10) {
			return "Destroy the Iron Dreadnought!";
		}
		return "";
	}

	static float objectiveBar(ServerLevel level, int stage, double truckZ) {
		if ((stage == 0 || stage == 3) && !BARRICADE.isEmpty()) {
			return 1 - barricadeLeft();
		}
		if (stage == 2 && !bridgeDone && bridge > 0) {
			return bridge / (float) BRIDGE_TICKS;
		}
		return -1;
	}

	// ----- Every tick of the run -----

	static void tick(ServerLevel level, int stage, ConvoyTruck truck, List<ServerPlayer> targets, boolean crewNear, long ticks) {
		RandomSource random = level.getRandom();
		tickPending(level, stage, targets.size());
		// Ambushes, as the truck reaches them.
		int[] list = AMBUSHES[stage];
		if (nextAmbush < list.length && truck.z >= ConvoyRoute.START[stage] + list[nextAmbush]) {
			nextAmbush++;
			ambush(level, stage, truck, targets, random, 1.0);
		}
		switch (stage) {
			case 0, 3 -> {
				int wall = stage == 0 ? LOGS : SNOW_WALL;
				if (stage == 3 && !avalanched && truck.z >= SNOW_WALL - 22) {
					// The avalanche comes down in front of the truck.
					avalanche(level, targets);
				}
				if (!BARRICADE.isEmpty() && truck.z >= wall - 6) {
					if (barricadeWaves == 0 || barricadeWaves == 1 && barricadeLeft() < 0.55F) {
						barricadeWaves++;
						ambush(level, stage, truck, targets, random, 0.8);
					}
					if (barricadeLeft() < 0.2F) {
						clearBarricade(level);
						for (ServerPlayer p : targets) {
							Alerts.title(p, "ROAD CLEAR!", "Keep the truck moving", ChatFormatting.GREEN);
						}
					}
				}
				if (stage == 3) {
					tickBoulders(level, truck, targets);
				}
			}
			case 1 -> {
				int square = ConvoyRoute.START[1] + SQUARE;
				if (!tanksCalled && truck.z >= square - 22) {
					tanksCalled = true;
					int n = Math.min(3, 1 + targets.size() / 2);
					for (int i = 0; i < n; i++) {
						int side = i % 2 == 0 ? 1 : -1;
						double z = square + 6 + i * 5;
						BlockPos at = BlockPos.containing(ConvoyRoute.cx(z) + side * (12 + random.nextInt(3)), ConvoyRoute.Y + 1, z);
						RiftFx.burst(level, Vec3.atCenterOf(at), 0xFFFF4020, 20, 0.6);
						ConvoyEnemies.spawnTank(level, at, stage, targets.size());
					}
					ambush(level, stage, truck, targets, random, 0.8);
					for (ServerPlayer p : targets) {
						Alerts.title(p, "TANKS!", "Legion tanks in the square: knock them out!", ChatFormatting.RED);
					}
				}
			}
			case 2 -> {
				if (gunship == null && truck.z >= ConvoyRoute.START[2] + 26 && !bridgeDone) {
					gunship = ConvoyGunship.spawn(level, truck, targets.size());
					for (ServerPlayer p : targets) {
						Alerts.title(p, "GUNSHIP!", "Grab the Skyhammer: lock on and fire!", ChatFormatting.RED);
						if (p.getInventory().countItem(dev.rubymod.registry.ModItems.SKYHAMMER) == 0) {
							p.getInventory().add(new ItemStack(dev.rubymod.registry.ModItems.SKYHAMMER));
						}
					}
				}
				if (gunship != null) {
					gunship.tick(level, truck, targets);
					if (gunship.dead()) {
						gunship = null;
					}
				}
				tickBridge(level, stage, truck, targets, crewNear, random);
			}
			default -> {
			}
		}
		tickCache(level, targets, truck);
		ambient(level, stage, truck, random, ticks);
	}

	/** The Legion arrives: from up the road and the sides (never behind the truck), out of red smoke. */
	static void ambush(ServerLevel level, int stage, ConvoyTruck truck, List<ServerPlayer> targets, RandomSource random, double size) {
		int players = Math.max(1, targets.size());
		int budget = (int) Math.round((4 + 2.5 * players) * (1 + stage * 0.2) * size);
		int maxRanged = 1 + players / 2 + (stage >= 3 ? 1 : 0);
		int ranged = 0;
		int guard = 0;
		while (budget > 0 && guard++ < 40 && Convoy.MOBS.size() + PENDING.size() < 12 + 4 * players) {
			ConvoyEnemies.Kind kind = ConvoyEnemies.pick(stage, random, ranged >= maxRanged);
			if (kind.cost() > budget + 1) {
				kind = ConvoyEnemies.melee(stage);
			}
			BlockPos at = spot(level, stage, truck, targets, kind, random);
			if (at == null) {
				continue;
			}
			if (kind.ranged()) {
				ranged++;
			}
			PENDING.add(new Pending(at, kind, new int[] {20 + random.nextInt(30)}));
			budget -= kind.cost();
		}
		level.playSound(null, BlockPos.containing(truck.position()), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.6F, 1.0F);
		for (ServerPlayer p : targets) {
			p.sendOverlayMessage(Component.literal("⚠ AMBUSH! The Iron Legion is coming up the road!").withStyle(ChatFormatting.RED));
		}
	}

	/** Where one of them comes in: ahead of the truck, off to a side, on open ground and not on top of anyone. */
	private static BlockPos spot(ServerLevel level, int stage, ConvoyTruck truck, List<ServerPlayer> targets, ConvoyEnemies.Kind kind,
			RandomSource random) {
		for (int tries = 0; tries < 12; tries++) {
			BlockPos at;
			if (stage == 1 && kind.ranged() && !ConvoyRoute.WINDOWS.isEmpty() && random.nextInt(3) != 0) {
				at = ahead(ConvoyRoute.WINDOWS, truck, random);
			} else if ((stage == 2 || stage == 3) && kind.ranged() && !ConvoyRoute.LEDGES.isEmpty()) {
				at = ahead(ConvoyRoute.LEDGES, truck, random);
			} else {
				double z = truck.z + 16 + random.nextInt(16);
				if (stage == 4 && !Convoy.bossBeaten) {
					z = Math.min(z, ConvoyRoute.COURTYARD + 20);
				}
				if (stage == 2 && !bridgeDone && z >= ConvoyRoute.GAP_FROM - 1) {
					z = ConvoyRoute.GAP_TO + 2 + random.nextInt(14);
				}
				int side = random.nextBoolean() ? 1 : -1;
				double x = ConvoyRoute.cx(z) + side * (5 + random.nextInt(9));
				at = BlockPos.containing(x, ConvoyRoute.Y + 1, z);
				at = new BlockPos(at.getX(), surface(level, at.getX(), at.getZ()) + 1, at.getZ());
			}
			if (at == null) {
				continue;
			}
			final BlockPos p = at;
			if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() || !level.getBlockState(p.below()).getFluidState().isEmpty()
					|| level.getBlockState(p.below()).isAir()) {
				continue;
			}
			if (targets.stream().anyMatch(pl -> pl.distanceToSqr(Vec3.atCenterOf(p)) < 10 * 10)) {
				continue;
			}
			return p;
		}
		return null;
	}

	private static BlockPos ahead(List<BlockPos> spots, ConvoyTruck truck, RandomSource random) {
		List<BlockPos> ok = new ArrayList<>();
		for (BlockPos p : spots) {
			if (p.getZ() > truck.z + 10 && p.getZ() < truck.z + 40) {
				ok.add(p);
			}
		}
		return ok.isEmpty() ? null : ok.get(random.nextInt(ok.size()));
	}

	static int surface(ServerLevel level, int x, int z) {
		for (int y = ConvoyRoute.Y + 30; y > ConvoyRoute.Y - 20; y--) {
			BlockPos p = new BlockPos(x, y, z);
			BlockState s = level.getBlockState(p);
			if (!s.isAir() && !s.getCollisionShape(level, p).isEmpty()) {
				return y;
			}
		}
		return ConvoyRoute.Y;
	}

	private static void tickPending(ServerLevel level, int stage, int players) {
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext();) {
			Pending p = it.next();
			int left = --p.left()[0];
			Vec3 at = Vec3.atBottomCenterOf(p.at());
			if (left % 4 == 0) {
				level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y + 0.3, at.z, 1, 0.2, 0.1, 0.2, 0.01);
				level.sendParticles(RiftFx.dust(0xFF3020, 1.4F), at.x, at.y + 0.2 + (30 - Math.min(30, left)) * 0.05, at.z, 3, 0.3, 0.1, 0.3, 0);
			}
			if (left <= 0) {
				it.remove();
				Mob mob = ConvoyEnemies.spawn(level, p.kind(), p.at(), stage, players);
				if (mob != null) {
					level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 10, 0.3, 0.6, 0.3, 0.02);
					level.playSound(null, p.at(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.6F, 0.8F);
				}
			}
		}
	}

	static int pending() {
		return PENDING.size();
	}

	// ----- The bridge -----

	private static void tickBridge(ServerLevel level, int stage, ConvoyTruck truck, List<ServerPlayer> targets, boolean crewNear, RandomSource random) {
		if (bridgeDone || truck.z < ConvoyRoute.GAP_FROM - 6) {
			return;
		}
		// The engineers only work while someone guards them.
		if (crewNear) {
			bridge++;
		} else if (level.getGameTime() % 40 == 0) {
			for (ServerPlayer p : targets) {
				p.sendOverlayMessage(Component.literal("The engineers need cover: get back to the truck!").withStyle(ChatFormatting.YELLOW));
			}
		}
		int span = ConvoyRoute.GAP_TO - ConvoyRoute.GAP_FROM;
		int want = Math.min(span, bridge * span / BRIDGE_TICKS + (bridge > 0 ? 1 : 0));
		while (bridgeLaid < want) {
			int z = ConvoyRoute.GAP_FROM + bridgeLaid++;
			int c = (int) Math.floor(ConvoyRoute.cx(z));
			for (int dx = -ConvoyRoute.ROAD - 1; dx <= ConvoyRoute.ROAD + 1; dx++) {
				BlockPos p = new BlockPos(c + dx, ConvoyRoute.Y, z);
				level.setBlock(p, Math.abs(dx) == ConvoyRoute.ROAD + 1 ? Blocks.SPRUCE_LOG.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState(),
						Block.UPDATE_CLIENTS);
				if (Math.abs(dx) == ConvoyRoute.ROAD + 1) {
					level.setBlock(p.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState()), c + dx + 0.5, ConvoyRoute.Y + 1,
						z + 0.5, 3, 0.3, 0.2, 0.3, 0.05);
			}
			level.playSound(null, new BlockPos(c, ConvoyRoute.Y, z), SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
			level.playSound(null, new BlockPos(c, ConvoyRoute.Y, z), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.3F, 1.6F);
		}
		// Waves while they work: a third of the way, and two thirds.
		int waves = bridge * 3 / BRIDGE_TICKS;
		if (bridge > 0 && bridgeWave == 0 || waves > bridgeWave && bridgeWave < 3) {
			bridgeWave = Math.max(bridgeWave + 1, waves);
			ambush(level, stage, truck, targets, random, 0.75);
		}
		if (bridge >= BRIDGE_TICKS) {
			bridgeDone = true;
			for (ServerPlayer p : targets) {
				Alerts.title(p, "BRIDGE BUILT!", "Roll on across", ChatFormatting.GREEN);
			}
		}
	}

	// ----- Frost Pass: boulders and the avalanche -----

	private static void avalanche(ServerLevel level, List<ServerPlayer> targets) {
		avalanched = true;
		barricadeWaves = 0;
		barricade(level, SNOW_WALL, Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.SNOW_BLOCK.defaultBlockState(), 1);
		for (BlockPos p : BARRICADE.keySet()) {
			if (level.getRandom().nextInt(3) == 0) {
				level.sendParticles(ParticleTypes.SNOWFLAKE, p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5, 6, 0.6, 0.6, 0.6, 0.05);
			}
		}
		level.playSound(null, BlockPos.containing(Vec3.atCenterOf(BARRICADE.keySet().iterator().next())), SoundEvents.GENERIC_EXPLODE.value(),
				SoundSource.BLOCKS, 2.0F, 0.4F);
		for (ServerPlayer p : targets) {
			Alerts.title(p, "AVALANCHE!", "The road's buried: shoot the snow to dig through", ChatFormatting.AQUA);
			RiftFx.shake(p, 1.2F, 30);
		}
	}

	private static void tickBoulders(ServerLevel level, ConvoyTruck truck, List<ServerPlayer> targets) {
		double z0 = ConvoyRoute.START[3] + 18;
		double z1 = ConvoyRoute.START[3] + 140;
		RandomSource random = level.getRandom();
		if (truck.z > z0 && truck.z < z1 && --boulderIn <= 0) {
			boulderIn = 90 + random.nextInt(70) - Math.min(40, targets.size() * 8);
			// Land somewhere near the truck or one of the team, on the road.
			double z = targets.isEmpty() || random.nextBoolean() ? truck.z + (random.nextDouble() - 0.3) * 10
					: targets.get(random.nextInt(targets.size())).getZ();
			double x = ConvoyRoute.cx(z) + (random.nextDouble() - 0.5) * 6;
			int side = random.nextBoolean() ? 1 : -1;
			Boulder b = new Boulder();
			b.to = new Vec3(x, ConvoyRoute.Y + 1, z);
			b.from = new Vec3(ConvoyRoute.cx(z) + side * 20, ConvoyRoute.Y + 16, z + (random.nextDouble() - 0.5) * 8);
			b.warn = 40;
			BOULDERS.add(b);
		}
		for (Iterator<Boulder> it = BOULDERS.iterator(); it.hasNext();) {
			Boulder b = it.next();
			if (b.warn > 0) {
				b.warn--;
				RiftFx.ring(level, b.to.add(0, 0.1, 0), 2.5, RiftFx.dust(0xFF2020, 1.3F), 16);
				RiftFx.ring(level, b.to.add(0, 0.1, 0), 2.5 * b.warn / 40.0, RiftFx.dust(0xFFFFFF, 0.9F), 10);
				if (b.warn == 20) {
					Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
					if (d != null) {
						d.snapTo(b.from.x, b.from.y, b.from.z, 0, 0);
						((BlockDisplayAccessor) d).rubymod$setBlockState(random.nextBoolean() ? Blocks.PACKED_ICE.defaultBlockState()
								: Blocks.COBBLESTONE.defaultBlockState());
						((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-1, 0, -1), new Quaternionf(),
								new Vector3f(2, 2, 2), new Quaternionf()));
						((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
						d.addTag(Convoy.TAG);
						level.addFreshEntity(d);
						b.display = d.getUUID();
					}
					level.playSound(null, BlockPos.containing(b.from), SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 2.0F, 0.5F);
				}
				if (b.warn < 20) {
					move(level, b, (20 - b.warn) / 20.0);
				}
				continue;
			}
			// It lands.
			Entity d = b.display == null ? null : level.getEntity(b.display);
			if (d != null) {
				d.discard();
			}
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), b.to.x, b.to.y + 0.5, b.to.z, 40, 1.2,
					0.5, 1.2, 0.2);
			level.sendParticles(ParticleTypes.SNOWFLAKE, b.to.x, b.to.y + 0.5, b.to.z, 30, 1.5, 0.6, 1.5, 0.1);
			level.playSound(null, BlockPos.containing(b.to), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0F, 0.6F);
			if (truck.distanceTo(b.to) < 2.5) {
				truck.hurt(level, 16, b.to.add(0, 1, 0));
			}
			for (ServerPlayer p : targets) {
				if (p.position().distanceTo(b.to) < 2.8) {
					p.hurtServer(level, level.damageSources().fallingBlock(null), 7);
					Vec3 push = p.position().subtract(b.to).multiply(1, 0, 1);
					push = push.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : push.normalize();
					p.push(push.x * 0.8, 0.4, push.z * 0.8);
					p.syncVelocity = true;
				}
				if (p.position().distanceTo(b.to) < 12) {
					RiftFx.shake(p, 0.6F, 8);
				}
			}
			it.remove();
		}
	}

	private static void move(ServerLevel level, Boulder b, double f) {
		Entity d = b.display == null ? null : level.getEntity(b.display);
		if (d == null) {
			return;
		}
		Vec3 p = b.from.lerp(b.to, f).add(0, Math.sin(f * Math.PI) * 2, 0);
		d.teleportTo(p.x, p.y, p.z);
		d.setYRot((float) (f * 720));
		level.sendParticles(ParticleTypes.SNOWFLAKE, p.x, p.y + 0.5, p.z, 3, 0.5, 0.5, 0.5, 0.05);
	}

	// ----- Supply caches -----

	private static void placeCache(ServerLevel level, int stage, RandomSource random) {
		if (stage >= 4) {
			cache = null;
			return;
		}
		double z = ConvoyRoute.START[stage] + 40 + random.nextInt(70);
		int side = random.nextBoolean() ? 1 : -1;
		int x = (int) Math.floor(ConvoyRoute.cx(z) + side * (8 + random.nextInt(4)));
		int y = surface(level, x, (int) z) + 1;
		cache = new BlockPos(x, y, (int) z);
		cacheProgress = 0;
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d != null) {
			d.snapTo(x + 0.5, y, z + 0.5, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.BARREL.defaultBlockState());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-0.45F, 0, -0.45F), new Quaternionf(),
					new Vector3f(0.9F, 0.9F, 0.9F), new Quaternionf()));
			d.setGlowingTag(true);
			((DisplayAccessor) d).rubymod$setGlowColorOverride(0xFFD040);
			d.addTag(Convoy.TAG);
			level.addFreshEntity(d);
			cacheDisplay = d.getUUID();
		}
	}

	private static void removeCache(ServerLevel level) {
		if (cacheDisplay != null) {
			Entity d = level.getEntity(cacheDisplay);
			if (d != null) {
				d.discard();
			}
		}
		cacheDisplay = null;
		cache = null;
	}

	private static void tickCache(ServerLevel level, List<ServerPlayer> targets, ConvoyTruck truck) {
		if (cache == null) {
			return;
		}
		Vec3 at = Vec3.atBottomCenterOf(cache);
		if (level.getGameTime() % 5 == 0) {
			RiftFx.beam(level, at, 12, RiftFx.dust(0xFFD040, 1.0F), 0xFFD040);
		}
		ServerPlayer on = null;
		for (ServerPlayer p : targets) {
			if (p.position().distanceTo(at) < 2.0) {
				on = p;
			}
		}
		if (on == null) {
			cacheProgress = Math.max(0, cacheProgress - 2);
			return;
		}
		cacheProgress++;
		on.sendOverlayMessage(Component.literal("Opening the supply cache... " + Math.min(100, cacheProgress * 100 / 50) + "%").withStyle(ChatFormatting.GOLD));
		if (cacheProgress < 50) {
			return;
		}
		RiftFx.burst(level, at.add(0, 1, 0), 0xFFFFD040, 30, 0.6);
		level.playSound(null, cache, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
		level.playSound(null, cache, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.2F);
		String what = Convoy.openCache(level, on);
		for (ServerPlayer p : targets) {
			Alerts.title(p, "SUPPLY CACHE!", on.getName().getString() + " found " + what, ChatFormatting.GOLD);
		}
		removeCache(level);
	}

	// ----- Looks -----

	private static void ambient(ServerLevel level, int stage, ConvoyTruck truck, RandomSource random, long ticks) {
		if (ticks % 4 != 0) {
			return;
		}
		Vec3 t = truck.position();
		switch (stage) {
			case 1 -> {
				for (BlockPos f : ConvoyRoute.FIRES) {
					if (Math.abs(f.getZ() - t.z) < 50) {
						level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, f.getX() + 0.5, f.getY(), f.getZ() + 0.5, 1, 0.8, 0.2, 0.8, 0.02);
						if (random.nextInt(3) == 0) {
							level.sendParticles(ParticleTypes.FLAME, f.getX() + 0.5, f.getY() - 0.5, f.getZ() + 0.5, 3, 1.2, 0.3, 1.2, 0.02);
							level.sendParticles(ParticleTypes.LAVA, f.getX() + 0.5, f.getY(), f.getZ() + 0.5, 1, 0.8, 0.2, 0.8, 0);
						}
					}
				}
			}
			case 2 -> level.sendParticles(ParticleTypes.WHITE_ASH, t.x, t.y + 6, t.z + 10, 20, 16, 4, 16, 0.01);
			case 3 -> level.sendParticles(ParticleTypes.SNOWFLAKE, t.x, t.y + 10, t.z + 6, 30, 18, 4, 18, 0.02);
			case 4 -> level.sendParticles(ParticleTypes.ASH, t.x, t.y + 6, t.z + 8, 30, 18, 4, 18, 0.01);
			default -> {
				if (random.nextInt(4) == 0) {
					level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, t.x, t.y + 4, t.z + 6, 6, 14, 3, 14, 0);
				}
			}
		}
	}

	static boolean isCacheDisplay(Entity e) {
		return e != null && e.getUUID().equals(cacheDisplay);
	}
}
