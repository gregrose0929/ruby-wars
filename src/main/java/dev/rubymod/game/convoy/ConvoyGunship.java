package dev.rubymod.game.convoy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.game.Alerts;
import dev.rubymod.game.rift.RiftFx;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Red Canyon's Legion Gunship: circles over the convoy, sprays bursts at the team, and fires
 * rockets onto red circles (often at the truck). Everyone's handed a Skyhammer when it shows
 * up, and the canyon depot has a helicopter of your own.
 */
final class ConvoyGunship {
	private final UUID ship;
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("LEGION GUNSHIP").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
	private int t;
	private double angle;
	private int gunAt = 60;
	private int rocketAt = 160;
	private final List<Vec3> rockets = new ArrayList<>();
	private int rocketsIn;
	private boolean done;
	private int dying = -1;
	private Vec3 lastPos;

	private ConvoyGunship(UUID ship) {
		this.ship = ship;
	}

	static ConvoyGunship spawn(ServerLevel level, ConvoyTruck truck, int players) {
		Vec3 c = truck.position().add(0, 22, 26);
		AircraftEntity heli = AircraftEntity.gunship(level, c, 18);
		AttributeInstance hp = heli.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			hp.setBaseValue(hp.getBaseValue() * 1.4 * (1 + 0.5 * (players - 1)));
			heli.setHealth(heli.getMaxHealth());
		}
		heli.setCustomName(Component.literal("Legion Gunship").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		heli.addTag(Convoy.TAG);
		heli.addTag(Convoy.BOSS_TAG);
		level.addFreshEntity(heli);
		Convoy.MOBS.add(heli.getUUID());
		return new ConvoyGunship(heli.getUUID());
	}

	boolean dead() {
		return done;
	}

	void remove(ServerLevel level) {
		Entity e = level.getEntity(ship);
		if (e != null) {
			e.discard();
		}
		bar.removeAllPlayers();
	}

	void tick(ServerLevel level, ConvoyTruck truck, List<ServerPlayer> targets) {
		t++;
		if (dying >= 0) {
			dying++;
			Vec3 at = lastPos == null ? truck.position().add(0, 20, 20) : lastPos;
			if (dying % 5 == 0) {
				level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y - dying * 0.25, at.z, 3, 1, 1, 1, 0);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y - dying * 0.25, at.z, 10, 1, 1, 1, 0.05);
				level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0F, 0.7F);
			}
			if (dying >= 40) {
				remove(level);
				done = true;
			}
			return;
		}
		Entity e = level.getEntity(ship);
		if (!(e instanceof AircraftEntity heli) || !heli.isAlive()) {
			dying = 0;
			for (ServerPlayer p : targets) {
				Alerts.title(p, "SHOT DOWN!", "The gunship is going down!", ChatFormatting.GOLD);
			}
			return;
		}
		lastPos = heli.position();
		bar.setProgress(Math.max(0, heli.getHealth() / heli.getMaxHealth()));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		// Circle over the truck, a little ahead of it.
		angle += 0.02;
		Vec3 c = truck.position().add(0, 20, 8);
		ServerPlayer target = null;
		for (ServerPlayer p : targets) {
			if (target == null || p.distanceToSqr(heli) < target.distanceToSqr(heli)) {
				target = p;
			}
		}
		heli.steer(c.add(Math.cos(angle) * 18, Math.sin(t * 0.03) * 2, Math.sin(angle) * 18), 0.7, target == null ? truck.position() : target.getEyePosition());
		if (t % 3 == 0) {
			level.sendParticles(ParticleTypes.SMOKE, heli.getX(), heli.getY() + 0.5, heli.getZ(), heli.getHealth() < heli.getMaxHealth() / 2 ? 4 : 1, 0.4,
					0.2, 0.4, 0.02);
		}
		if (target != null && --gunAt <= 0) {
			if (gunAt > -20 && gunAt % 3 == 0) {
				gun(level, heli, target);
			}
			if (gunAt <= -20) {
				gunAt = 70 + level.getRandom().nextInt(40);
			}
		}
		if (--rocketAt <= 0) {
			rocketAt = 150 + level.getRandom().nextInt(60);
			rockets.clear();
			for (int k = 0; k < 2; k++) {
				Vec3 r = truck.local((level.getRandom().nextDouble() - 0.5) * 3, 0, (level.getRandom().nextDouble() - 0.5) * 6);
				rockets.add(new Vec3(r.x, Math.floor(r.y) + 0.1, r.z));
			}
			for (ServerPlayer p : targets) {
				rockets.add(new Vec3(p.getX() + level.getRandom().nextInt(5) - 2, Math.floor(p.getY()) + 0.1, p.getZ() + level.getRandom().nextInt(5) - 2));
			}
			rocketsIn = 34;
			level.playSound(null, heli.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 3.0F, 0.6F);
		}
		if (rocketsIn > 0) {
			rocketsIn--;
			for (Vec3 r : rockets) {
				RiftFx.ring(level, r, 2.2, RiftFx.dust(0xFF2020, 1.3F), 16);
				RiftFx.ring(level, r, 2.2 * rocketsIn / 34.0, RiftFx.dust(0xFFFFFF, 0.9F), 10);
			}
			if (rocketsIn == 0) {
				for (Vec3 r : rockets) {
					RiftFx.line(level, heli.position(), r, ParticleTypes.FLAME, 1.0);
					level.sendParticles(ParticleTypes.EXPLOSION, r.x, r.y + 0.5, r.z, 2, 0.5, 0.3, 0.5, 0);
					level.playSound(null, BlockPos.containing(r), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
					if (truck.distanceTo(r) < 2.4) {
						truck.hurt(level, 10, r.add(0, 1, 0));
					}
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(r) < 2.5) {
							p.hurtServer(level, level.damageSources().explosion(heli, heli), 6);
							RiftFx.shake(p, 0.8F, 8);
						}
					}
				}
				rockets.clear();
			}
		}
	}

	private void gun(ServerLevel level, AircraftEntity heli, ServerPlayer target) {
		var random = level.getRandom();
		Vec3 muzzle = heli.position().add(0, -0.4, 0);
		Vec3 aim = target.position().add(0, 1, 0).add((random.nextDouble() - 0.5) * 3, (random.nextDouble() - 0.5) * 1.4, (random.nextDouble() - 0.5) * 3);
		var hit = level.clip(new ClipContext(muzzle, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, heli));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? aim : hit.getLocation();
		RiftFx.line(level, muzzle, end, ParticleTypes.CRIT, 1.0);
		level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 1.4F, 0.7F);
		if (end.distanceTo(target.position().add(0, 1, 0)) < 1.1) {
			target.hurtServer(level, level.damageSources().mobAttack(heli), 1.5F);
		}
	}
}
