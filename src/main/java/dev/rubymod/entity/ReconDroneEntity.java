package dev.rubymod.entity;

import java.util.UUID;

import dev.rubymod.game.Armor;
import dev.rubymod.game.Rules;
import dev.rubymod.game.Squads;
import dev.rubymod.game.Waypoints;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Recon Drone (Armory, Recon research): thrown up from your hand, it climbs to
 * 28 blocks over the spot and circles for 90 seconds, marking every enemy
 * within 56 blocks on your squad's HUD and map. Enemies can shoot it down.
 */
public class ReconDroneEntity extends Mob {
	public static final int LIFETIME = 90 * 20;
	private static final double SPOT_RANGE = 56;
	private UUID owner = new UUID(0, 0);
	private Vec3 center = Vec3.ZERO;
	private int age;

	public ReconDroneEntity(EntityType<? extends ReconDroneEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder attributes() {
		return createMobAttributes().add(Attributes.MAX_HEALTH, 8.0);
	}

	public static ReconDroneEntity launch(ServerLevel level, ServerPlayer owner) {
		ReconDroneEntity drone = dev.rubymod.registry.ModEntities.RECON_DRONE.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (drone == null) {
			return null;
		}
		drone.owner = owner.getUUID();
		drone.center = owner.position().add(0, 28, 0);
		drone.snapTo(owner.getX(), owner.getEyeY() + 0.5, owner.getZ(), owner.getYRot(), 0);
		return drone;
	}

	@Override
	public void tick() {
		super.tick();
		setNoGravity(true);
		age++;
		if (level().isClientSide()) {
			return;
		}
		// Climb to station, then orbit.
		double t = age / 60.0;
		Vec3 goal = center.add(Math.cos(t) * 12, Math.sin(t * 0.7) * 1.5, Math.sin(t) * 12);
		if (age > LIFETIME) {
			goal = position().add(0, 6, 0);
		}
		Vec3 to = goal.subtract(position());
		Vec3 v = to.length() > 0.6 ? to.normalize().scale(Math.min(0.6, to.length() * 0.1 + 0.15)) : to;
		setDeltaMovement(getDeltaMovement().scale(0.6).add(v.scale(0.4)));
		setYRot((float) Math.toDegrees(Math.atan2(-getDeltaMovement().x, getDeltaMovement().z)));
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		if (age > LIFETIME + 60) {
			level.sendParticles(ParticleTypes.CLOUD, getX(), getY(), getZ(), 6, 0.2, 0.2, 0.2, 0.02);
			discard();
			return;
		}
		if (age % 20 == 0 && age > 40) {
			for (Entity e : level.getEntities(this, getBoundingBox().inflate(SPOT_RANGE, 64, SPOT_RANGE), this::isEnemy)) {
				Waypoints.spot(e, owner, 3);
			}
			// Enemy bases it flies over go on its owner's map.
			if (owner != null) {
				for (dev.rubymod.game.SeasonData.Claim claim : dev.rubymod.game.SeasonData.get().claims.values()) {
					if (!claim.destroyed && !claim.canUse(owner) && Math.max(Math.abs(claim.x - getX()), Math.abs(claim.z - getZ())) < claim.radius() + SPOT_RANGE) {
						Waypoints.discover(owner, claim.owner, null);
					}
				}
			}
		}
		if (age % 40 == 0) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, 0.6F, 1.6F);
		}
	}

	private boolean isEnemy(Entity e) {
		if (e instanceof Player p) {
			return !p.isSpectator() && !p.isCreative() && !Squads.same(owner, p.getUUID()) && !Rules.isFriendly(owner, p.getUUID())
					&& !Rules.isEliminated(p.getUUID());
		}
		if (e instanceof SoldierEntity s) {
			return s.isRaider() || !Squads.same(owner, s.owner());
		}
		if (Armor.isArmored(e) && e.getFirstPassenger() instanceof Player crew) {
			return isEnemy(crew);
		}
		return false;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt && !isAlive()) {
			level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
		}
		return hurt;
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("age", age);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		// Drones don't survive a restart.
		discard();
	}

	public float rotorAngle(float partial) {
		return (age + partial) * 1.6F;
	}

	public LivingEntity self() {
		return this;
	}
}
