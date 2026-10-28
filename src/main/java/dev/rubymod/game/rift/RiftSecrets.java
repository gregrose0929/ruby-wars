package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Secret stashes: some fight rooms hide a patch of cracked floor that glints now and then.
 * Shoot it enough, or blow it up, and it breaks open: a shower of gold, maybe a charm or
 * even a relic.
 */
public final class RiftSecrets {
	private static final List<BlockPos> TILES = new ArrayList<>();
	private static Vec3 center;
	private static int hits;

	private RiftSecrets() {
	}

	/** For autotests: the next room surely has one. */
	static boolean force;

	/** A fight room was entered: maybe a secret in its floor (3 in 10). */
	static void place(ServerLevel level, RiftRoom room) {
		clear();
		boolean sure = force;
		force = false;
		if (room.spawns.isEmpty() || !sure && level.getRandom().nextFloat() > 0.3F) {
			return;
		}
		BlockPos s = room.spawns.get(level.getRandom().nextInt(room.spawns.size())).offset(level.getRandom().nextInt(5) - 2, -1, level.getRandom().nextInt(5) - 2);
		for (int dx = 0; dx < 2; dx++) {
			for (int dz = 0; dz < 2; dz++) {
				BlockPos b = s.offset(dx, 0, dz);
				if (level.getBlockState(b).isAir() || !level.getBlockState(b.above()).isAir()) {
					continue;
				}
				level.setBlock(b, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 3);
				TILES.add(b);
			}
		}
		if (TILES.isEmpty()) {
			return;
		}
		center = Vec3.atCenterOf(s).add(0.5, 0.5, 0.5);
		hits = 0;
	}

	static void tick(ServerLevel level) {
		if (center != null && level.getGameTime() % 30 == 0) {
			// A glint: easy to miss, easy to spot once you know.
			level.sendParticles(ParticleTypes.WAX_ON, center.x, center.y + 0.1, center.z, 3, 0.5, 0.05, 0.5, 0.3);
		}
	}

	/** A bullet hit a block. */
	public static void onImpact(ServerLevel level, BlockPos pos) {
		if (center != null && TILES.contains(pos)) {
			hits++;
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CRACKED_STONE_BRICKS.defaultBlockState()), pos.getX() + 0.5, pos.getY() + 1,
					pos.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.1);
			if (hits >= 8) {
				open(level);
			}
		}
	}

	/** An explosion went off. */
	static void onBlast(ServerLevel level, Vec3 at, double radius) {
		if (center != null && at.distanceTo(center) < radius + 1.5) {
			open(level);
		}
	}

	private static void open(ServerLevel level) {
		Vec3 at = center;
		center = null;
		for (BlockPos b : TILES) {
			level.setBlock(b, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CRACKED_STONE_BRICKS.defaultBlockState()), b.getX() + 0.5, b.getY() + 1,
					b.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.2);
		}
		TILES.clear();
		var random = level.getRandom();
		int gold = 60 + random.nextInt(61);
		Rift.gold += gold;
		level.sendParticles(ParticleTypes.WAX_ON, at.x, at.y + 0.5, at.z, 80, 0.5, 1.5, 0.5, 0.8);
		RiftFx.burst(level, at.add(0, 1, 0), 0xFFFFC02E, 50, 0.8);
		RiftFx.beam(level, at, 12, ParticleTypes.END_ROD, 0xFFFFC02E);
		level.playSound(null, BlockPos.containing(at), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 1.2F, 0.8F);
		level.playSound(null, BlockPos.containing(at), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.6F);
		List<ServerPlayer> players = Rift.players(level.getServer());
		ServerPlayer nearest = players.stream().min(java.util.Comparator.comparingDouble(p -> p.position().distanceTo(at))).orElse(null);
		String extra = "";
		if (nearest != null && random.nextFloat() < 0.5F) {
			RiftCharms.give(nearest, RiftCharms.Charm.values()[random.nextInt(RiftCharms.Charm.values().length)]);
			extra = " and a charm";
		}
		if (nearest != null && random.nextFloat() < 0.25F) {
			Rift.Runner r = Rift.runner(nearest);
			List<Relic> rares = java.util.Arrays.stream(Relic.values()).filter(x -> x.tier == Relic.Tier.RARE && r != null && !r.has(x)).toList();
			if (r != null && !rares.isEmpty()) {
				Rift.give(nearest, r, rares.get(random.nextInt(rares.size())));
				extra += " and a relic";
			}
		}
		for (ServerPlayer p : players) {
			dev.rubymod.game.Alerts.title(p, "SECRET STASH!", "+" + gold + " gold" + extra, ChatFormatting.GOLD);
		}
	}

	/** For autotests: where the secret is (null: none). */
	public static Vec3 where() {
		return center;
	}

	static void clear() {
		TILES.clear();
		center = null;
		hits = 0;
	}
}
