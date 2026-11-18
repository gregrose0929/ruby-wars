package dev.rubymod.entity;

import net.minecraft.world.entity.Entity;

import net.minecraft.core.BlockPos;

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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Frag and smoke grenades. They bounce, then go off after a 2.5 second fuse. */
public class GrenadeEntity extends ModProjectile {
	private static final int FUSE = 50;
	private static final int SMOKE_TICKS = 240;
	private int smokeLeft = -1;

	public GrenadeEntity(EntityType<? extends GrenadeEntity> type, Level level) {
		super(type, level);
	}

	public static GrenadeEntity throwFrom(Level level, LivingEntity thrower, boolean smoke) {
		GrenadeEntity grenade = new GrenadeEntity(smoke ? ModEntities.SMOKE_GRENADE : ModEntities.FRAG_GRENADE, level);
		grenade.setPos(thrower.getX(), thrower.getEyeY() - 0.1, thrower.getZ());
		grenade.setOwner(thrower);
		grenade.setItem((smoke ? ModItems.SMOKE_GRENADE : ModItems.FRAG_GRENADE).getDefaultInstance());
		return grenade;
	}

	/** A fire bomb: a bottle of fuel that shatters on whatever it hits and sets the spot alight. */
	public static GrenadeEntity fireBomb(Level level, LivingEntity thrower) {
		GrenadeEntity bomb = new GrenadeEntity(ModEntities.FIRE_BOMB, level);
		bomb.setPos(thrower.getX(), thrower.getEyeY() - 0.1, thrower.getZ());
		bomb.setOwner(thrower);
		bomb.setItem(ModItems.FIRE_BOMB.getDefaultInstance());
		return bomb;
	}

	private boolean isSmoke() {
		return getType() == ModEntities.SMOKE_GRENADE;
	}

	private boolean isFire() {
		return getType() == ModEntities.FIRE_BOMB;
	}

	@Override
	protected Item getDefaultItem() {
		return isFire() ? ModItems.FIRE_BOMB : isSmoke() ? ModItems.SMOKE_GRENADE : ModItems.FRAG_GRENADE;
	}

	/** The fire bomb breaks: flames on the ground all round (where fire's allowed), and everyone close catches fire. */
	public void burst(ServerLevel level) {
		Vec3 at = position();
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2F, 0.9F);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.2F, 0.8F);
		level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, 60, 1.4, 0.4, 1.4, 0.05);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 12, 1.0, 0.5, 1.0, 0.02);
		BlockPos c = BlockPos.containing(at);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (Math.hypot(dx, dz) > 2.8) {
					continue;
				}
				for (int dy = 2; dy >= -3; dy--) {
					BlockPos p = c.offset(dx, dy, dz);
					if (level.getBlockState(p).isAir() && !level.getBlockState(p.below()).isAir()) {
						dev.rubymod.game.Fire.light(level, p);
						break;
					}
				}
			}
		}
		Entity owner = getOwner();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(3.5), e -> e.isAlive())) {
			if (e instanceof net.minecraft.world.entity.player.Player victim && owner instanceof net.minecraft.world.entity.player.Player thrower
					&& victim != thrower && !dev.rubymod.game.Rules.canHurt(thrower, victim)) {
				continue;
			}
			e.igniteForSeconds(6);
		}
		discard();
	}

	@Override
	protected double gravity() {
		return 0.05;
	}

	@Override
	public void tick() {
		if (smokeLeft >= 0) {
			// Sitting on the ground pouring out smoke.
			baseTick();
			if (level() instanceof ServerLevel level) {
				if (smokeLeft % 2 == 0) {
					level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, getX(), getY() + 0.5, getZ(), 6, 2.0, 1.2, 2.0, 0.01);
				}
				if (--smokeLeft <= 0) {
					discard();
				}
			}
			return;
		}
		super.tick();
		if (level() instanceof ServerLevel level && tickCount >= FUSE) {
			if (isFire()) {
				burst(level);
				return;
			}
			detonate(level);
		}
	}

	private void detonate(ServerLevel level) {
		if (isSmoke()) {
			setDeltaMovement(Vec3.ZERO);
			setNoGravity(true);
			smokeLeft = SMOKE_TICKS;
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.0F, 0.6F);
		} else {
			explode(level, 2.2F, true);
		}
	}

	@Override
	protected float antiArmor(float power) {
		return 12.0F;
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		// Handled in onHitBlock / onHitEntity: grenades bounce instead of exploding on contact.
	}

	@Override
	protected void onHitBlock(BlockHitResult hit) {
		super.onHitBlock(hit);
		if (isFire()) {
			if (level() instanceof ServerLevel level) {
				setPos(hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()).scale(0.3)));
				burst(level);
			}
			return;
		}
		Vec3 v = getDeltaMovement();
		Vec3 n = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
		Vec3 bounced = v.subtract(n.scale(2 * v.dot(n))).scale(0.35);
		setDeltaMovement(bounced.lengthSqr() < 0.002 ? Vec3.ZERO : bounced);
		setPos(hit.getLocation().add(n.scale(0.05)));
		if (bounced.lengthSqr() > 0.01) {
			playSound(SoundEvents.CHAIN_HIT, 0.5F, 1.4F);
		}
	}

	@Override
	protected void onHitEntity(EntityHitResult hit) {
		if (isFire() && level() instanceof ServerLevel level && hit.getEntity() != getOwner()) {
			burst(level);
			return;
		}
		setDeltaMovement(getDeltaMovement().scale(-0.1));
	}
}
