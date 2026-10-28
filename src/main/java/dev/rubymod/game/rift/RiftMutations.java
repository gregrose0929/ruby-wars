package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Boss mutations: every boss fight rolls one (two from Heat 2), so the same boss is never
 * quite the same fight. Announced when the fight starts and shown under the boss's name.
 */
final class RiftMutations {
	enum Mutation {
		ARMORED("Armored", "50% more health.", 0xFFB8C4D0),
		ENRAGED("Enraged", "Below half health it gets faster and hits harder.", 0xFFFF4040),
		SUMMONER("Summoner", "Calls in reinforcements every 15 seconds.", 0xFFC27BFF),
		METEORS("Meteor Storm", "Meteors fall on red circles: keep moving.", 0xFFFF7A2E),
		REGENERATING("Regenerating", "Heals when you stop hurting it.", 0xFF6EE38A),
		STORMCALLER("Stormcaller", "Lightning strikes on marked spots.", 0xFF66E0FF);

		final String title;
		final String about;
		final int color;

		Mutation(String title, String about, int color) {
			this.title = title;
			this.about = about;
			this.color = color;
		}
	}

	private record Strike(Vec3 at, long when, boolean meteor) {
	}

	private static final List<Mutation> ACTIVE = new ArrayList<>();
	private static final List<Strike> STRIKES = new ArrayList<>();
	private static boolean enraged;
	private static float lastHealth = -1;
	private static long lastHurt;

	private RiftMutations() {
	}

	/** A boss fight begins: roll its mutations and tell everyone. */
	static void roll(ServerLevel level, RiftBoss boss, int heat, RandomSource random) {
		clear();
		List<Mutation> pool = new ArrayList<>(List.of(Mutation.values()));
		int n = heat >= 2 ? 2 : 1;
		for (int i = 0; i < n && !pool.isEmpty(); i++) {
			ACTIVE.add(pool.remove(random.nextInt(pool.size())));
		}
		LivingEntity body = boss.body(level);
		if (body != null && ACTIVE.contains(Mutation.ARMORED)) {
			AttributeInstance hp = body.getAttribute(Attributes.MAX_HEALTH);
			if (hp != null) {
				hp.setBaseValue(hp.getBaseValue() * 1.5);
				body.setHealth(body.getMaxHealth());
			}
		}
		String names = String.join(" + ", ACTIVE.stream().map(m -> m.title.toUpperCase()).toList());
		for (ServerPlayer p : Rift.players(level.getServer())) {
			p.sendSystemMessage(Component.literal("☠ Boss mutation: ").withStyle(ChatFormatting.DARK_RED)
					.append(Component.literal(String.join(", ", ACTIVE.stream().map(m -> m.title + " (" + m.about + ")").toList()))
							.withStyle(ChatFormatting.RED)));
			p.sendOverlayMessage(Component.literal("MUTATED: " + names).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
	}

	/** For the boss bar: " • ARMORED + ENRAGED". */
	static String suffix() {
		return ACTIVE.isEmpty() ? "" : "  •  " + String.join(" + ", ACTIVE.stream().map(m -> m.title.toUpperCase()).toList());
	}

	static void clear() {
		ACTIVE.clear();
		STRIKES.clear();
		enraged = false;
		lastHealth = -1;
	}

	static void tick(ServerLevel level, RiftBoss boss, List<ServerPlayer> targets, int t) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		LivingEntity body = boss.body(level);
		long now = level.getGameTime();
		RandomSource random = level.getRandom();
		if (body != null) {
			if (body.getHealth() < lastHealth) {
				lastHurt = now;
			}
			lastHealth = body.getHealth();
			// A haze in its colour, so you can see what it's become.
			if (t % 6 == 0) {
				Mutation m = ACTIVE.get((t / 6) % ACTIVE.size());
				level.sendParticles(RiftFx.dust(m.color, 1.4F), true, false, body.getX(), body.getY() + body.getBbHeight() * 0.6, body.getZ(), 4,
						body.getBbWidth() * 0.5, body.getBbHeight() * 0.3, body.getBbWidth() * 0.5, 0);
			}
		}
		if (body != null && ACTIVE.contains(Mutation.ENRAGED) && !enraged && body.getHealth() < body.getMaxHealth() / 2) {
			enraged = true;
			body.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 1, false, false));
			body.addEffect(new MobEffectInstance(MobEffects.STRENGTH, -1, 1, false, false));
			level.playSound(null, body.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0F, 0.6F);
			for (ServerPlayer p : targets) {
				Alerts.title(p, "ENRAGED!", "It's faster and hits harder now", ChatFormatting.RED);
				RiftFx.shake(p, 1.0F, 20);
			}
		}
		if (body != null && ACTIVE.contains(Mutation.REGENERATING) && now - lastHurt > 60 && t % 20 == 0 && body.getHealth() < body.getMaxHealth()) {
			body.heal(body.getMaxHealth() * 0.015F);
			level.sendParticles(ParticleTypes.HEART, body.getX(), body.getY() + body.getBbHeight(), body.getZ(), 3, 0.6, 0.3, 0.6, 0);
		}
		if (ACTIVE.contains(Mutation.SUMMONER) && t % 300 == 150 && Rift.room != null && !Rift.room.spawns.isEmpty()) {
			for (int k = 0; k < 2; k++) {
				Rift.summon(Rift.room.spawns.get(random.nextInt(Rift.room.spawns.size())), random.nextBoolean());
			}
			if (body != null) {
				level.playSound(null, body.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 2.0F, 0.7F);
			}
		}
		if (ACTIVE.contains(Mutation.METEORS) && t % 160 == 80) {
			for (ServerPlayer p : targets) {
				for (int k = 0; k < 2; k++) {
					STRIKES.add(new Strike(new Vec3(p.getX() + random.nextInt(7) - 3, Math.floor(p.getY()) + 0.1, p.getZ() + random.nextInt(7) - 3), now + 34, true));
				}
			}
		}
		if (ACTIVE.contains(Mutation.STORMCALLER) && t % 100 == 50 && !targets.isEmpty()) {
			ServerPlayer p = targets.get(random.nextInt(targets.size()));
			STRIKES.add(new Strike(new Vec3(p.getX() + random.nextInt(3) - 1, Math.floor(p.getY()) + 0.1, p.getZ() + random.nextInt(3) - 1), now + 24, false));
		}
		for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext();) {
			Strike s = it.next();
			long left = s.when() - now;
			int color = s.meteor() ? 0xFF7A2E : 0x66E0FF;
			if (left > 0) {
				if (left % 3 == 0) {
					RiftFx.ring(level, s.at(), 2.0, RiftFx.dust(color, 1.3F), 16);
					RiftFx.ring(level, s.at(), 2.0 * left / 34.0, RiftFx.dust(0xFFFFFF, 0.8F), 10);
				}
				if (s.meteor() && left < 14) {
					// The meteor, falling.
					Vec3 m = s.at().add(left * 0.6, left * 1.6, left * 0.3);
					level.sendParticles(ParticleTypes.FLAME, true, false, m.x, m.y, m.z, 6, 0.25, 0.25, 0.25, 0.02);
					level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, m.x, m.y, m.z, 2, 0.2, 0.2, 0.2, 0.01);
				}
				continue;
			}
			it.remove();
			if (s.meteor()) {
				level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, s.at().x, s.at().y + 0.5, s.at().z, 1, 0, 0, 0, 0);
				level.sendParticles(ParticleTypes.LAVA, s.at().x, s.at().y + 0.5, s.at().z, 20, 1, 0.5, 1, 0);
				level.playSound(null, BlockPos.containing(s.at()), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.6F, 0.7F);
			} else {
				var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
				if (bolt != null) {
					bolt.snapTo(s.at().x, s.at().y, s.at().z, 0, 0);
					bolt.setVisualOnly(true);
					level.addFreshEntity(bolt);
				}
			}
			for (ServerPlayer p : targets) {
				if (p.position().distanceTo(s.at()) < 2.3) {
					p.hurtServer(level, s.meteor() ? level.damageSources().explosion(null, null) : level.damageSources().lightningBolt(), s.meteor() ? 7 : 6);
					RiftFx.shake(p, 0.8F, 8);
				}
			}
		}
	}
}
