package dev.rubymod.game;

import dev.rubymod.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Elevator blocks: jump on one to go up to the next Elevator above (up to 48 blocks), sneak to go down. */
public final class Elevators {
	private static final int REACH = 48;

	private Elevators() {
	}

	public static void ride(ServerPlayer player, boolean up) {
		ServerLevel level = (ServerLevel) player.level();
		BlockPos below = player.blockPosition().below();
		if (!level.getBlockState(below).is(ModBlocks.ELEVATOR) || player.isPassenger()) {
			return;
		}
		int step = up ? 1 : -1;
		for (int i = 1; i <= REACH; i++) {
			BlockPos p = below.offset(0, step * i, 0);
			if (!level.getBlockState(p).is(ModBlocks.ELEVATOR)) {
				continue;
			}
			// Room to stand on top of it?
			if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
					|| !level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty()) {
				continue;
			}
			level.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1, player.getZ(), 20, 0.3, 0.6, 0.3, 0.05);
			player.teleportTo(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5);
			player.fallDistance = 0;
			level.playSound(null, p, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.8F, up ? 1.4F : 0.9F);
			level.sendParticles(ParticleTypes.PORTAL, p.getX() + 0.5, p.getY() + 2, p.getZ() + 0.5, 20, 0.3, 0.6, 0.3, 0.05);
			return;
		}
	}
}
