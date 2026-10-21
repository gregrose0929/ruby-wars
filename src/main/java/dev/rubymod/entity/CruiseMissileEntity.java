package dev.rubymod.entity;

import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A cruise missile: climbs out of its silo, cruises high above the map, then
 * dives onto its target. It keeps the chunk it's in loaded so it can cross the
 * whole map even where no players are.
 */
public class CruiseMissileEntity extends ModProjectile implements Interceptable {
	private static final double CLIMB_SPEED = 0.8;
	private static final double CRUISE_SPEED = 2.2;
	private static final double DIVE_SPEED = 2.6;
	private static final float POWER = 7.0F;

	private double targetX;
	private double targetY;
	private double targetZ;
	private double cruiseY;
	private final java.util.Set<Long> forcedChunks = new java.util.HashSet<>();

	public CruiseMissileEntity(EntityType<? extends CruiseMissileEntity> type, Level level) {
		super(type, level);
	}

	public static CruiseMissileEntity launch(Level level, LivingEntity owner, Vec3 from, Vec3 target) {
		CruiseMissileEntity missile = new CruiseMissileEntity(ModEntities.CRUISE_MISSILE, level);
		missile.setPos(from);
		missile.setOwner(owner);
		missile.setItem(ModItems.CRUISE_MISSILE.getDefaultInstance());
		missile.targetX = target.x;
		missile.targetY = target.y;
		missile.targetZ = target.z;
		missile.cruiseY = Math.min(level.getMaxY() - 8, Math.max(from.y, target.y) + 60);
		missile.setDeltaMovement(0, CLIMB_SPEED, 0);
		return missile;
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.CRUISE_MISSILE;
	}

	@Override
	protected double gravity() {
		return 0.0;
	}

	@Override
	protected float drag() {
		return 1.0F;
	}

	@Override
	public void tick() {
		if (level() instanceof ServerLevel level) {
			guide();
			keepChunkLoaded(level);
			level.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0.0);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY(), getZ(), 1, 0.1, 0.1, 0.1, 0.0);
			if (tickCount % 20 == 0) {
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 4.0F, 0.4F);
			}
			if (tickCount > 20 * 120) {
				explode(level, POWER, true);
				return;
			}
		}
		super.tick();
	}

	private void guide() {
		Vec3 pos = position();
		double dx = targetX - pos.x;
		double dz = targetZ - pos.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double climbNeeded = cruiseY - pos.y;
		if (horizontal < Math.max(24, climbNeeded * 0.5) || (horizontal < 40 && getDeltaMovement().y < 0)) {
			// Dive.
			Vec3 toTarget = new Vec3(dx, targetY - pos.y, dz).normalize();
			setDeltaMovement(toTarget.scale(DIVE_SPEED));
		} else if (pos.y < cruiseY - 2 && tickCount < 200) {
			// Climb, drifting toward the target.
			Vec3 flat = new Vec3(dx, 0, dz).normalize().scale(0.3);
			setDeltaMovement(flat.x, CLIMB_SPEED + tickCount * 0.02, flat.z);
		} else {
			// Cruise.
			Vec3 flat = new Vec3(dx, (cruiseY - pos.y) * 0.05, dz).normalize();
			setDeltaMovement(flat.scale(CRUISE_SPEED));
		}
	}

	private void keepChunkLoaded(ServerLevel level) {
		// Force-load the chunk we're in plus the next ones along our path, so the
		// missile never stalls at the edge of an unloaded area.
		java.util.Set<Long> wanted = new java.util.HashSet<>();
		for (int ahead = 0; ahead <= 16; ahead += 8) {
			Vec3 p = position().add(getDeltaMovement().scale(ahead));
			wanted.add(chunkKey((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4));
		}
		for (Long key : forcedChunks) {
			if (!wanted.contains(key)) {
				level.setChunkForced(chunkX(key), chunkZ(key), false);
			}
		}
		for (Long key : wanted) {
			if (!forcedChunks.contains(key)) {
				level.setChunkForced(chunkX(key), chunkZ(key), true);
			}
		}
		forcedChunks.clear();
		forcedChunks.addAll(wanted);
	}

	private void releaseChunks(ServerLevel level) {
		for (Long key : forcedChunks) {
			level.setChunkForced(chunkX(key), chunkZ(key), false);
		}
		forcedChunks.clear();
	}

	private static long chunkKey(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static int chunkX(long key) {
		return (int) (key >> 32);
	}

	private static int chunkZ(long key) {
		return (int) key;
	}

	@Override
	public void remove(RemovalReason reason) {
		if (level() instanceof ServerLevel level) {
			releaseChunks(level);
		}
		super.remove(reason);
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		if (tickCount < 10) {
			return; // Still clearing the silo.
		}
		explode(level, POWER, true);
	}

	@Override
	public boolean threatens(SeasonData.Claim claim) {
		if (getOwner() != null && claim.canUse(getOwner().getUUID())) {
			return false;
		}
		return claim.contains(Rules.dim(level()), (int) Math.floor(targetX), (int) Math.floor(targetZ));
	}

	@Override
	public void intercept(ServerLevel level) {
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 6.0F, 1.2F);
		discard();
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putDouble("target_x", targetX);
		output.putDouble("target_y", targetY);
		output.putDouble("target_z", targetZ);
		output.putDouble("cruise_y", cruiseY);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		targetX = input.getDoubleOr("target_x", getX());
		targetY = input.getDoubleOr("target_y", getY());
		targetZ = input.getDoubleOr("target_z", getZ());
		cruiseY = input.getDoubleOr("cruise_y", getY());
	}

	@Override
	protected float antiArmor(float power) {
		return 250.0F;
	}
}
