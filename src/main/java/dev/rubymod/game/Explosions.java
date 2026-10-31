package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Every explosion in the world passes its list of blocks through {@link #filter}
 * (see ServerExplosionMixin). That's where claims are protected and armored
 * blocks soak up hits instead of breaking.
 */
public final class Explosions {
	/** Explosions this big or bigger (cruise missiles) break armored blocks outright. */
	private static final float HEAVY_RADIUS = 6.0F;
	/** Reinforced Concrete ignores explosions smaller than this (grenades). */
	private static final float REINFORCED_MIN_RADIUS = 2.5F;

	private static final Map<String, Map<Long, Integer>> HITS = new HashMap<>();
	private static final boolean DEBUG = System.getenv("RUBYMOD_AUTOTEST") != null;

	private Explosions() {
	}

	public static void explode(ServerLevel level, Entity source, double x, double y, double z, float power, boolean breakBlocks) {
		level.explode(source, x, y, z, power, breakBlocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
		dev.rubymod.game.convoy.Convoy.onBlast(level, x, y, z, power);
		if (power >= 3.0F) {
			Vec3 at = new Vec3(x, y, z);
			shake(level, at, power * 0.9F, (int) (6 + power * 3));
			level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.5, z, (int) (power * 6), power * 0.25, power * 0.3, power * 0.25, 0.04);
			level.sendParticles(ParticleTypes.FLAME, x, y + 0.3, z, (int) (power * 4), power * 0.2, power * 0.15, power * 0.2, 0.08);
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 1, z, (int) power * 2, power * 0.3, 0.5, power * 0.3, 0.02);
			effects(level, at, power);
		}
	}

	/** The big-blast extras: a ground debris spray, an expanding dust ring, and a boom that carries (a low rumble far away). */
	private static void effects(ServerLevel level, Vec3 at, float power) {
		net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(at).below();
		var ground = level.getBlockState(below);
		if (ground.isAir()) {
			ground = level.getBlockState(below.below());
		}
		if (!ground.isAir()) {
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.3, at.z,
					(int) (power * 14), power * 0.3, 0.2, power * 0.3, 0.5);
		}
		int ring = 28;
		for (int i = 0; i < ring; i++) {
			double a = i * Math.PI * 2 / ring;
			// count 0: the offset is the particle's velocity.
			level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, 0, Math.cos(a), 0.02, Math.sin(a), 0.25 + power * 0.06);
		}
		var random = level.getRandom();
		for (ServerPlayer player : level.players()) {
			double d = player.position().distanceTo(at);
			if (d < 64) {
				player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
						net.minecraft.core.Holder.direct(dev.rubymod.registry.ModSounds.EXPLOSION), net.minecraft.sounds.SoundSource.BLOCKS,
						at.x, at.y, at.z, Math.min(2.0F, power * 0.4F), 0.9F + random.nextFloat() * 0.2F, random.nextLong()));
			} else if (d < 64 + power * 50) {
				// Far away: a muffled boom from the blast's direction.
				// Sound travels about 340 blocks a second, so the boom arrives after the flash.
				float volume = (float) Math.max(0.15, 1.0 - (d - 64) / (power * 50));
				long seed = random.nextLong();
				float pitch = 0.9F + random.nextFloat() * 0.2F;
				DELAYED.add(new Delayed(player.getUUID(), at, volume, pitch, seed, level.getGameTime() + (long) (d / 340.0 * 20.0)));
			}
		}
	}

	private record Delayed(java.util.UUID player, Vec3 at, float volume, float pitch, long seed, long when) {
	}

	private static final java.util.List<Delayed> DELAYED = new java.util.ArrayList<>();

	/** Every tick: deliver far-away booms whose sound has arrived. */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (DELAYED.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		DELAYED.removeIf(d -> {
			if (d.when > now) {
				return false;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(d.player);
			if (player != null) {
				Vec3 p = player.position().add(d.at.subtract(player.position()).normalize().scale(12));
				player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
						net.minecraft.core.Holder.direct(dev.rubymod.registry.ModSounds.EXPLOSION_FAR), net.minecraft.sounds.SoundSource.BLOCKS,
						p.x, p.y, p.z, d.volume, d.pitch, d.seed));
			}
			return true;
		});
	}

	/** Shake the camera of every player near a blast; stronger the closer they are. */
	public static void shake(ServerLevel level, Vec3 at, float strength, int ticks) {
		double reach = 10 + strength * 10;
		for (ServerPlayer player : level.players()) {
			double distance = player.position().distanceTo(at);
			if (distance < reach) {
				float felt = (float) (strength * (1.0 - distance / reach));
				if (felt > 0.15F) {
					Net.send(player, new Payloads.Shake(felt, ticks));
				}
			}
		}
	}

	public static List<BlockPos> filter(ServerLevel level, List<BlockPos> positions, float radius) {
		List<BlockPos> result = new ArrayList<>(positions.size());
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (!Rules.canWeaponBreak(level, pos, state)) {
				continue;
			}
			int hp = armor(state);
			if (hp > 0 && !absorb(level, pos, state, hp, radius)) {
				continue;
			}
			result.add(pos);
		}
		if (DEBUG && radius >= 3.0F && !positions.isEmpty()) {
			dev.rubymod.RubyMod.LOGGER.info("[explosion] radius {} at {} kept {} of {} blocks (phase {})", radius,
					positions.get(0), result.size(), positions.size(), Rules.phase());
		}
		return result;
	}

	/** How many explosion hits an armored block takes; 0 = not armored. */
	private static int armor(BlockState state) {
		if (state.is(ModBlocks.CONCRETE_WALL)) {
			return 4;
		}
		if (state.is(ModBlocks.REINFORCED_CONCRETE)) {
			return 3;
		}
		if (state.is(ModBlocks.REINFORCED_GLASS)) {
			return 2;
		}
		if (state.is(ModBlocks.VAULT)) {
			return 5;
		}
		return 0;
	}

	/** Records one hit. Returns true once the block has taken enough to break. */
	private static boolean absorb(ServerLevel level, BlockPos pos, BlockState state, int hp, float radius) {
		if (radius >= HEAVY_RADIUS) {
			return true;
		}
		if (state.is(ModBlocks.REINFORCED_CONCRETE) && radius < REINFORCED_MIN_RADIUS) {
			return false;
		}
		Map<Long, Integer> hits = HITS.computeIfAbsent(Rules.dim(level), k -> new HashMap<>());
		int taken = hits.merge(pos.asLong(), 1, Integer::sum);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				12, 0.4, 0.4, 0.4, 0.1);
		if (taken >= hp) {
			hits.remove(pos.asLong());
			return true;
		}
		return false;
	}

	public static void clearHits() {
		HITS.clear();
	}
}
