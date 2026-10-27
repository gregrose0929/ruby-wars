package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Act 2's boss: the Rift Gunship, circling over an open-sky dock. It rakes you with its
 * guns, rains rockets on red circles, and drops troopers in. Everyone gets a Skyhammer,
 * and there are Heavy AA Guns on the pads. Half dead, it calls MAYDAY and drops into a
 * lower, angrier orbit. Shot down, it spirals out in a trail of explosions.
 */
public final class RiftGunship implements RiftBoss {
	private final UUID ship;
	private final RiftRoom room;
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("RIFT GUNSHIP").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_6);
	private int t;
	private boolean mayday;
	private int gunAt = 40;
	private int rocketAt = 120;
	private int dropAt = 300;
	private final List<Vec3> rockets = new ArrayList<>();
	private int rocketsIn;
	private int dying = -1;
	private Vec3 lastPos;
	private boolean done;

	private RiftGunship(UUID ship, RiftRoom room) {
		this.ship = ship;
		this.room = room;
	}

	static RiftGunship spawn(ServerLevel level, RiftRoom room, int players) {
		Vec3 c = Vec3.atBottomCenterOf(room.center).add(0, 24, 0);
		AircraftEntity heli = AircraftEntity.gunship(level, c, 22);
		AttributeInstance hp = heli.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			hp.setBaseValue(hp.getBaseValue() * 2.2 * (1 + 0.6 * (players - 1)));
			heli.setHealth(heli.getMaxHealth());
		}
		heli.setCustomName(Component.literal("Rift Gunship").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
		heli.addTag(Rift.TAG);
		heli.addTag(JuggernautMech.BOSS_TAG);
		level.addFreshEntity(heli);
		Rift.MOBS.add(heli.getUUID());
		// Heavy AA Guns on the pads, and a Skyhammer each.
		for (int[] p : new int[][] {{-12, -12}, {12, -12}, {-12, 12}, {12, 12}}) {
			Mob gun = ModEntities.HEAVY_AA_GUN.create(level, EntitySpawnReason.EVENT);
			if (gun != null) {
				gun.snapTo(room.center.getX() + p[0] + 0.5, room.center.getY() + 2, room.center.getZ() + p[1] + 0.5, 0, 0);
				gun.addTag(Rift.TAG);
				level.addFreshEntity(gun);
			}
		}
		for (ServerPlayer p : Rift.players(level.getServer())) {
			p.getInventory().add(new ItemStack(ModItems.SKYHAMMER));
			p.getInventory().add(new ItemStack(ModItems.AA_MISSILE, 8));
		}
		RiftGunship g = new RiftGunship(heli.getUUID(), room);
		for (ServerPlayer p : Rift.players(level.getServer())) {
			g.bar.addPlayer(p);
		}
		return g;
	}

	@Override
	public boolean dead() {
		return done;
	}

	@Override
	public String debug() {
		return "gunship t" + t + (mayday ? " MAYDAY" : "") + (dying >= 0 ? " dying" : "");
	}

	@Override
	public void remove(ServerLevel level) {
		Entity e = level.getEntity(ship);
		if (e != null) {
			e.discard();
		}
		bar.removeAllPlayers();
	}

	@Override
	public void tick(ServerLevel level, List<ServerPlayer> targets) {
		t++;
		if (dying >= 0) {
			tickDeath(level);
			return;
		}
		Entity e = level.getEntity(ship);
		if (!(e instanceof AircraftEntity heli) || !heli.isAlive()) {
			dying = 0;
			for (ServerPlayer p : Rift.players(level.getServer())) {
				dev.rubymod.game.Alerts.title(p, "SHOT DOWN!", "The Gunship is going down!", ChatFormatting.GOLD);
			}
			return;
		}
		lastPos = heli.position();
		float hp = heli.getHealth() / heli.getMaxHealth();
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal(mayday ? "RIFT GUNSHIP  •  MAYDAY" : "RIFT GUNSHIP").withStyle(ChatFormatting.BOLD));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		if (!mayday && hp < 0.5F) {
			mayday = true;
			heli.setOrbit(Vec3.atBottomCenterOf(room.center).add(0, 15, 0), 14);
			level.playSound(null, heli.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0F, 0.6F);
			for (ServerPlayer p : targets) {
				dev.rubymod.game.Alerts.title(p, "MAYDAY!", "It's diving lower: and angrier", ChatFormatting.RED);
			}
		}
		if (t % 3 == 0) {
			level.sendParticles(ParticleTypes.SMOKE, heli.getX(), heli.getY() + 0.5, heli.getZ(), mayday ? 4 : 1, 0.4, 0.2, 0.4, 0.02);
		}
		ServerPlayer target = nearest(heli, targets);
		if (target == null) {
			return;
		}
		// Guns: bursts at whoever's closest it can see.
		if (--gunAt <= 0 && gunAt > -30) {
			if (gunAt % 2 == 0) {
				gun(level, heli, target);
			}
		} else if (gunAt <= -30) {
			gunAt = (mayday ? 35 : 60) + level.getRandom().nextInt(30);
		}
		// Rockets on red circles.
		if (--rocketAt <= 0) {
			rocketAt = mayday ? 110 : 170;
			rockets.clear();
			for (ServerPlayer p : targets) {
				for (int k = 0; k < (mayday ? 4 : 2); k++) {
					rockets.add(new Vec3(p.getX() + level.getRandom().nextInt(9) - 4, Math.floor(p.getY()) + 0.1, p.getZ() + level.getRandom().nextInt(9) - 4));
				}
			}
			rocketsIn = 30;
			level.playSound(null, heli.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 3.0F, 0.6F);
		}
		if (rocketsIn > 0) {
			rocketsIn--;
			for (Vec3 r : rockets) {
				RiftFx.ring(level, r, 2.2, RiftFx.dust(0xFF2020, 1.3F), 16);
				RiftFx.ring(level, r, 2.2 * rocketsIn / 30.0, RiftFx.dust(0xFFFFFF, 0.9F), 10);
			}
			if (rocketsIn == 0) {
				for (Vec3 r : rockets) {
					RiftFx.line(level, heli.position(), r, ParticleTypes.FLAME, 1.0);
					level.sendParticles(ParticleTypes.EXPLOSION, r.x, r.y + 0.5, r.z, 2, 0.5, 0.3, 0.5, 0);
					level.playSound(null, BlockPos.containing(r), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(r) < 2.6) {
							p.hurtServer(level, level.damageSources().explosion(heli, heli), mayday ? 9 : 7);
							RiftFx.shake(p, 0.8F, 8);
						}
					}
				}
				rockets.clear();
			}
		}
		// Troopers dropped in on ropes of smoke.
		if (--dropAt <= 0) {
			dropAt = mayday ? 260 : 360;
			for (int k = 0; k < Math.min(4, 1 + targets.size()); k++) {
				BlockPos at = room.spawns.get(level.getRandom().nextInt(room.spawns.size()));
				RiftFx.line(level, heli.position(), Vec3.atBottomCenterOf(at), ParticleTypes.LARGE_SMOKE, 1.2);
				Rift.summon(at, true);
			}
		}
	}

	private static ServerPlayer nearest(Entity from, List<ServerPlayer> targets) {
		ServerPlayer best = null;
		for (ServerPlayer p : targets) {
			if (best == null || p.distanceToSqr(from) < best.distanceToSqr(from)) {
				best = p;
			}
		}
		return best;
	}

	private void gun(ServerLevel level, AircraftEntity heli, ServerPlayer target) {
		var random = level.getRandom();
		Vec3 muzzle = heli.position().add(0, -0.4, 0);
		Vec3 aim = target.position().add(0, 1, 0).add((random.nextDouble() - 0.5) * 2.6, (random.nextDouble() - 0.5) * 1.4, (random.nextDouble() - 0.5) * 2.6);
		var hit = level.clip(new ClipContext(muzzle, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, heli));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? aim : hit.getLocation();
		RiftFx.line(level, muzzle, end, ParticleTypes.CRIT, 1.0);
		level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 1.4F, 0.7F);
		if (end.distanceTo(target.position().add(0, 1, 0)) < 1.1) {
			target.hurtServer(level, level.damageSources().mobAttack(heli), mayday ? 2.5F : 2.0F);
		}
	}

	private void tickDeath(ServerLevel level) {
		dying++;
		Vec3 at = lastPos == null ? Vec3.atCenterOf(room.center) : lastPos;
		if (dying % 5 == 0) {
			level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y - dying * 0.2, at.z, 3, 1, 1, 1, 0);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y - dying * 0.2, at.z, 10, 1, 1, 1, 0.05);
			level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0F, 0.7F);
		}
		if (dying >= 50) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, room.center.getY() + 2, at.z, 2, 1, 1, 1, 0);
			remove(level);
			done = true;
		}
	}
}
