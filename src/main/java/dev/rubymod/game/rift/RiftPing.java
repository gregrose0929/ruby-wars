package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * B in the Ruby Rift: ping what you're looking at for the whole team. A monster glows (so
 * everyone can see it through walls) with a beam of your colour over it; a spot gets a ring
 * and a beam. Everyone hears a chime and sees who pinged what.
 */
public final class RiftPing {
	private record Mark(Entity target, Vec3 at, int color, long until) {
	}

	private static final List<Mark> MARKS = new ArrayList<>();
	private static final int[] COLORS = {0xFFFFC02E, 0xFF66E0FF, 0xFF6EE38A, 0xFFFF7AE0};

	private RiftPing() {
	}

	/** True if this was a Rift ping (handled here). */
	public static boolean ping(ServerPlayer p, int x, int y, int z, int entityId) {
		if (!Rift.playing(p) || !(p.level() instanceof ServerLevel level)) {
			return false;
		}
		int index = new ArrayList<>(Rift.RUNNERS.keySet()).indexOf(p.getUUID());
		int color = COLORS[Math.max(0, index) % COLORS.length];
		Entity e = entityId >= 0 ? level.getEntity(entityId) : null;
		String what;
		if (e instanceof LivingEntity living && living.isAlive() && e != p) {
			living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, false, false));
			MARKS.add(new Mark(e, null, color, level.getGameTime() + 100));
			what = Rift.isEnemy(e) ? (e.hasCustomName() ? e.getCustomName().getString() : e.getType().getDescription().getString()) : e.getName().getString();
		} else {
			Vec3 at = Vec3.atBottomCenterOf(new BlockPos(x, y, z)).add(0, 1, 0);
			MARKS.add(new Mark(null, at, color, level.getGameTime() + 100));
			what = Rift.room != null && Rift.room.portal.distanceTo(at) < 4 ? "the portal" : "this spot";
		}
		for (ServerPlayer mate : Rift.players(level.getServer())) {
			mate.sendOverlayMessage(Component.literal("▼ " + p.getName().getString() + " pinged " + what).withColor(color & 0xFFFFFF));
			mate.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
					net.minecraft.core.Holder.direct(SoundEvents.NOTE_BLOCK_BELL.value()), SoundSource.PLAYERS, mate.getX(), mate.getY(), mate.getZ(), 0.7F, 1.6F,
					level.getRandom().nextLong()));
		}
		return true;
	}

	static void tick(ServerLevel level) {
		long now = level.getGameTime();
		for (Iterator<Mark> it = MARKS.iterator(); it.hasNext();) {
			Mark m = it.next();
			if (now >= m.until() || m.target() != null && !m.target().isAlive()) {
				it.remove();
				continue;
			}
			if (now % 4 != 0) {
				continue;
			}
			Vec3 at = m.target() != null ? m.target().position().add(0, m.target().getBbHeight() + 0.6, 0) : m.at();
			// A downward chevron of light, bobbing over it, and a beam up to the sky.
			double bob = Math.sin(now * 0.3) * 0.2;
			int rgb = m.color() & 0xFFFFFF;
			for (int k = -3; k <= 3; k++) {
				double off = Math.abs(k) * 0.15;
				level.sendParticles(RiftFx.dust(rgb, 1.2F), true, false, at.x + k * 0.15, at.y + 0.5 + off + bob, at.z, 1, 0, 0, 0, 0);
			}
			for (double h = 1.2; h < 9; h += 0.8) {
				level.sendParticles(RiftFx.dust(rgb, 0.9F), true, false, at.x, at.y + h, at.z, 1, 0.02, 0.1, 0.02, 0);
			}
			if (m.target() == null) {
				RiftFx.ring(level, at.add(0, -0.9, 0), 1.2, RiftFx.dust(rgb, 1.2F), 16);
			}
		}
	}

	static void clear() {
		MARKS.clear();
	}
}
