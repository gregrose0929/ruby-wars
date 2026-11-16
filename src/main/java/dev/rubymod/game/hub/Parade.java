package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.phys.Vec3;

/**
 * The night parade: a few times a night, four floats (a giant ice cream, a pirate ship, a friendly dragon and a birthday
 * cake) roll slowly round Home Street and up and down Main Street with music and confetti. The Parade Marshal on Main
 * Street hops you onto a float to ride along.
 */
final class Parade {
	private Parade() {
	}

	private static final String[] FLOATS = {"float_icecream", "float_ship", "float_dragon", "float_cake"};
	private static final int[] CONFETTI = {0xFF5080, 0xFFD040, 0x40C8FF, 0x60FF80, 0xC060FF, 0xFF9030};
	private static TrackRide parade;
	private static int spawned = -1;
	private static int next;
	private static int tick;
	private static int note;
	private static boolean ranTonight;
	private static boolean ranLate;

	static void reset() {
		if (parade != null) {
			parade.reset();
		}
		spawned = -1;
		ranTonight = false;
		ranLate = false;
	}

	static void build(ServerLevel level) {
		double y = FLOOR + 1;
		double[][] c = {{-84, -50}, {-74, -50}, {-64, -50}, {-60, -53}, {-60, -57}, {-60, -70}, {-60, -90}, {-60, -112}, {-62, -116}, {-64, -112},
			{-64, -90}, {-64, -70}, {-64, -57}, {-60, -54}, {-50, -54}, {-40, -54}, {-38, -52}, {-40, -50}, {-50, -50}, {-58, -50}, {-62, -50}, {-74, -54},
			{-84, -54}, {-86, -52}, {-84, -50}};
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : c) {
			pts.add(new Vec3(p[0] + 0.5, y, p[1] + 0.5));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		java.util.Arrays.fill(speed, 0.075);
		parade = new TrackRide("parade", path, speed, "float_icecream", 3.6F, new Vec3(-61.5, FLOOR + 1, -46.5), "the Parade Marshal");
		parade.keepEmpty = true;
		parade.upright = true;
		parade.sideBySide = true;
		parade.seatHeight = 1.0;
		parade.modelFor = i -> FLOATS[i % FLOATS.length];
		parade.again = "What a parade! Wave goodbye to the floats.";
		HubHosts.add(level, new Vec3(-56.5, FLOOR + 1, -91.5), 90, VillagerProfession.CLERIC, VillagerType.JUNGLE,
				new HubHosts.Host("parade", "Marsha", "Parade Marshal", 0xFF60C0, "minecraft:firework_rocket",
						"The night parade comes a few times every night: floats roll round Home Street and up and down Main Street, with music and "
								+ "confetti! While it's on, I can pop you onto a float to ride along.",
						"Parade: right-click Marsha!", p -> List.of("ride|Ride a float!", "when|When's the parade?"), (p, key) -> {
							if (key.equals("ride")) {
								if (parade.cars.isEmpty()) {
									p.sendSystemMessage(Component.literal("The parade comes out at night: come back after the sun goes down!").withStyle(ChatFormatting.LIGHT_PURPLE));
								} else if (!parade.boardNearest(p, 200)) {
									p.sendSystemMessage(Component.literal("The floats are full! Wave as they go by.").withStyle(ChatFormatting.LIGHT_PURPLE));
								}
							} else {
								long t = level.getOverworldClockTime() % 24000;
								p.sendSystemMessage(Component.literal(!parade.cars.isEmpty() ? "It's on right now! Look along Main Street."
										: t > 13000 && t < 23000 ? "There's one later tonight: keep watching Main Street!" : "As soon as it gets dark!")
										.withStyle(ChatFormatting.LIGHT_PURPLE));
							}
						}));
	}

	/** Start the parade: the floats set off one after another. */
	static void start(ServerLevel level) {
		if (parade == null || spawned >= 0) {
			return;
		}
		spawned = 0;
		next = tick;
		for (ServerPlayer p : level.players()) {
			p.sendSystemMessage(Component.literal("★ The night parade is starting on Main Street! ★").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		}
	}

	static void tick(ServerLevel level) {
		tick++;
		if (parade == null) {
			return;
		}
		long time = level.getOverworldClockTime() % 24000;
		if (time > 13500 && time < 14000 && !ranTonight && !level.players().isEmpty()) {
			ranTonight = true;
			start(level);
		} else if (time > 18500 && time < 19000 && !ranLate && !level.players().isEmpty()) {
			ranLate = true;
			start(level);
		} else if (time < 13000) {
			ranTonight = false;
			ranLate = false;
		}
		// The floats set off ten seconds apart.
		if (spawned >= 0 && spawned < FLOATS.length && tick >= next) {
			parade.spawnEmpty(level, 0);
			spawned++;
			next = tick + 200;
		}
		parade.tick(level);
		if (spawned >= FLOATS.length && parade.cars.isEmpty()) {
			spawned = -1;
		}
		if (parade.cars.isEmpty()) {
			return;
		}
		// Confetti, and a tune from the leading float.
		var random = level.getRandom();
		if (tick % 4 == 0) {
			for (TrackRide.Car c : parade.cars) {
				for (int i = 0; i < 3; i++) {
					level.sendParticles(new DustParticleOptions(CONFETTI[random.nextInt(CONFETTI.length)], 1.3F), c.pos.x, c.pos.y + 4.2, c.pos.z, 2, 1.4, 0.6, 1.4, 0);
				}
			}
		}
		if (tick % 5 == 0) {
			TrackRide.Car lead = parade.cars.get(0);
			int[] song = Music.song(1);
			int n = song[note % song.length];
			if (n >= 0) {
				level.playSound(null, BlockPos.containing(lead.pos), SoundEvents.NOTE_BLOCK_TRUMPET.value(), SoundSource.RECORDS, 1.6F, (float) Math.pow(2, (n - 12) / 12.0));
				level.playSound(null, BlockPos.containing(lead.pos), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.RECORDS, 0.8F, (float) Math.pow(2, (n - 12) / 12.0));
			}
			if (note % 4 == 0) {
				level.playSound(null, BlockPos.containing(lead.pos), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.RECORDS, 1.2F, 1.0F);
			}
			note++;
		}
		if (tick % 80 == 0) {
			for (TrackRide.Car c : parade.cars) {
				level.sendParticles(ParticleTypes.FIREWORK, c.pos.x, c.pos.y + 5, c.pos.z, 12, 0.6, 0.3, 0.6, 0.12);
			}
		}
	}
}
