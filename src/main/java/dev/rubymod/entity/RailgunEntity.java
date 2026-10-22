package dev.rubymod.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.rubymod.game.Armor;
import dev.rubymod.game.Explosions;
import dev.rubymod.game.Rules;
import dev.rubymod.gun.BlockDamage;
import dev.rubymod.industry.Grid;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModDamage;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Railgun Emplacement: a manned electromagnetic gun powered by your grid. Each
 * shot draws 4,000 charge and needs 3 seconds to recharge; the slug crosses 400
 * blocks instantly, punches through up to three targets (90 anti-armor: most of
 * a tank, any jet) and bursts on whatever stops it.
 */
public class RailgunEntity extends HeavyAAGunEntity {
	public static final long CHARGE_PER_SHOT = 4000;
	public static final int RECHARGE = 60;
	private static final double RANGE = 400;

	public RailgunEntity(EntityType<? extends RailgunEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected boolean usesMagazine() {
		return false;
	}

	@Override
	public int reloadTicks() {
		return RECHARGE;
	}

	@Override
	public double shellSpeed() {
		return 1000.0; // effectively instant: aim right at the target
	}

	@Override
	protected String mountHint() {
		return "Railgun: look to aim, left-click fires (4,000 charge from your grid), hold right-click to zoom. Sneak to leave.";
	}

	@Override
	public void fire(ServerPlayer gunner) {
		if (gunner != getControllingPassenger() || reloadLeft() > 0) {
			return;
		}
		if (!gunner.isCreative() && !dev.rubymod.game.Duels.inDuel(gunner) && !Grid.consume(gunner.getUUID(), CHARGE_PER_SHOT)) {
			gunner.sendOverlayMessage(Component.literal("Railgun needs 4,000 charge from your power grid (you have " + Grid.charge(gunner.getUUID()) + ")."));
			return;
		}
		setReloadLeft(RECHARGE);
		ServerLevel level = (ServerLevel) level();
		Vec3 dir = gunnerAim(gunner);
		Vec3 from = position().add(0, 1.5, 0).add(dir.scale(4.2));
		Vec3 end = from.add(dir.scale(RANGE));
		BlockHitResult blockHit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		Vec3 stop = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

		// Everything along the line, nearest first; the slug goes through the first three.
		Vec3 lineEnd = stop;
		List<Entity> hits = new ArrayList<>(level.getEntities(this, new AABB(from, lineEnd).inflate(1.5),
				e -> e.isAlive() && e.isPickable() && e != gunner && e.getBoundingBox().inflate(0.4).clip(from, lineEnd).isPresent()));
		hits.sort(Comparator.comparingDouble(e -> e.distanceToSqr(from)));
		int pierced = 0;
		for (Entity e : hits) {
			if (pierced >= 3) {
				stop = e.getBoundingBox().getCenter();
				break;
			}
			if (Armor.isArmored(e)) {
				Armor.hit(level, e, this, gunner, 90.0F);
			} else if (e instanceof LivingEntity && Rules.canHurt(gunner, e)) {
				e.hurtServer(level, ModDamage.source(level, ModDamage.BULLET, this, gunner), 24.0F);
				Net.send(gunner, new Payloads.HitMarker(false, !e.isAlive()));
			}
			pierced++;
		}
		if (pierced < 3 && blockHit.getType() == HitResult.Type.BLOCK) {
			BlockDamage.hit(level, blockHit.getBlockPos(), 200.0F, gunner);
			Explosions.explode(level, this, stop.x, stop.y, stop.z, 1.8F, true);
		}

		// The beam: a ruby streak with sparks, visible from far away.
		DustParticleOptions beam = new DustParticleOptions(0xFF2060, 1.6F);
		double len = from.distanceTo(stop);
		for (double d = 0; d < len; d += 0.6) {
			Vec3 p = from.add(dir.scale(d));
			level.sendParticles(beam, true, true, p.x, p.y, p.z, 1, 0, 0, 0, 0);
			if (d % 3 < 0.6) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.05);
			}
		}
		level.sendParticles(ParticleTypes.EXPLOSION, true, true, from.x, from.y, from.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.END_ROD, true, false, from.x, from.y, from.z, 20, 0.3, 0.3, 0.3, 0.2);
		level.playSound(null, from.x, from.y, from.z, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 3.0F, 1.6F);
		level.playSound(null, from.x, from.y, from.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 2.0F, 2.0F);
		Net.send(gunner, new Payloads.Shake(0.5F, 4));
		level.broadcastEntityEvent(this, (byte) 60);
	}
}
