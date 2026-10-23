package dev.rubymod.game;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/**
 * Jetpacks, server side. The wearer's client does the flying (players move themselves)
 * and says when the jets are firing; here we cancel fall damage while they fire and
 * show everyone the exhaust and hiss.
 */
public final class Jetpacks {
	private static final Set<UUID> THRUSTING = new HashSet<>();

	private Jetpacks() {
	}

	public static boolean wearing(ServerPlayer player) {
		return player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.JETPACK);
	}

	public static void setThrusting(ServerPlayer player, boolean on) {
		if (on && wearing(player) && player.getVehicle() == null) {
			THRUSTING.add(player.getUUID());
		} else {
			THRUSTING.remove(player.getUUID());
		}
	}

	public static void tick(MinecraftServer server) {
		for (Iterator<UUID> it = THRUSTING.iterator(); it.hasNext();) {
			ServerPlayer p = server.getPlayerList().getPlayer(it.next());
			if (p == null || !wearing(p) || p.getVehicle() != null || p.isSpectator()) {
				it.remove();
				continue;
			}
			p.resetFallDistance();
			ServerLevel level = (ServerLevel) p.level();
			// Two nozzles low on the back.
			Vec3 back = Vec3.directionFromRotation(0, p.yBodyRot).scale(-0.32);
			Vec3 side = new Vec3(-back.z, 0, back.x).normalize().scale(0.14);
			for (int s = -1; s <= 1; s += 2) {
				Vec3 at = p.position().add(back).add(side.scale(s)).add(0, 0.62, 0);
				level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 2, 0.03, 0.05, 0.03, 0.02);
				level.sendParticles(ParticleTypes.SMOKE, at.x, at.y - 0.2, at.z, 1, 0.05, 0.05, 0.05, 0.02);
			}
			if (p.tickCount % 5 == 0) {
				level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.18F, 1.7F + p.getRandom().nextFloat() * 0.2F);
			}
		}
	}

	public static void forget(UUID player) {
		THRUSTING.remove(player);
	}
}
