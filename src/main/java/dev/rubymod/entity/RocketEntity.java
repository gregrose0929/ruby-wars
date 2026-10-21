package dev.rubymod.entity;

import dev.rubymod.game.Countermeasures;
import dev.rubymod.item.RocketLauncherItem;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Rockets and missiles. Three flavors:
 * - launcher rockets: laser-guided, they follow the shooter's crosshair while the
 *   launcher is held;
 * - homing missiles (from aircraft): fly toward a locked entity or a marked point,
 *   can be decoyed by flares;
 * - unguided rounds (tank shells, dumb rockets).
 */
public class RocketEntity extends ModProjectile {
	private static final double LAUNCHER_SPEED = 1.6;
	private static final int LIFETIME = 160;
	/** Guided missiles fly longer (about 1000 blocks for a Skyhammer). */
	private static final int GUIDED_LIFETIME = 260;
	private static final EntityDataAccessor<Boolean> SHELL = SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.BOOLEAN);
	/** Entity id of what this missile is homing on (-1 = none); lets the target's HUD warn them. */
	private static final EntityDataAccessor<Integer> TARGET = SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);

	private boolean laserGuided = true;
	private float power = 3.0F;
	private double speed = LAUNCHER_SPEED;
	private Entity homingEntity;
	private Vec3 homingPoint;
	private double turnRate;
	private Entity launchedFrom;
	private float antiArmor = 48.0F;
	private boolean topAttack;

	public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(SHELL, false);
		builder.define(TARGET, -1);
	}

	/** Drawn as a tank shell instead of a rocket. */
	public boolean isShell() {
		return entityData.get(SHELL);
	}

	public int targetId() {
		return entityData.get(TARGET);
	}

	public static RocketEntity fire(Level level, LivingEntity shooter) {
		RocketEntity rocket = new RocketEntity(ModEntities.ROCKET, level);
		Vec3 look = shooter.getLookAngle();
		rocket.setPos(shooter.getX() + look.x, shooter.getEyeY() - 0.1 + look.y, shooter.getZ() + look.z);
		rocket.setOwner(shooter);
		rocket.setItem(ModItems.ROCKET.getDefaultInstance());
		rocket.setDeltaMovement(look.scale(LAUNCHER_SPEED));
		return rocket;
	}

	/** An unguided round. {@code shooter} may be null (fired by a defense block). */
	public static RocketEntity shell(Level level, Entity shooter, Vec3 from, Vec3 velocity, float power) {
		RocketEntity rocket = new RocketEntity(ModEntities.ROCKET, level);
		rocket.setOwner(shooter);
		rocket.setItem(ModItems.ROCKET.getDefaultInstance());
		rocket.laserGuided = false;
		rocket.power = power;
		rocket.speed = velocity.length();
		rocket.setPos(from);
		rocket.setDeltaMovement(velocity);
		return rocket;
	}

	/** A tank round: looks like a shell instead of a rocket. */
	public static RocketEntity tankShell(Level level, LivingEntity shooter, Vec3 from, Vec3 velocity, float power) {
		RocketEntity shell = shell(level, shooter, from, velocity, power);
		shell.entityData.set(SHELL, true);
		shell.antiArmor = 56.0F;
		return shell;
	}

	/** Fly a top-attack profile: climb above the target, then dive onto it (anti-tank missiles). */
	public RocketEntity topAttack() {
		this.topAttack = true;
		return this;
	}

	/** Set how much this round hurts vehicles and aircraft (see {@link dev.rubymod.game.Armor}). */
	public RocketEntity withAntiArmor(float damage) {
		this.antiArmor = damage;
		return this;
	}

	@Override
	protected float antiArmor(float power) {
		return antiArmor;
	}

	/** A homing missile fired from {@code launcher} (an aircraft) at an entity or a point. */
	public static RocketEntity missile(Level level, Entity shooter, Entity launcher, Vec3 from, Vec3 velocity, float power,
			double turnRate, Entity target, Vec3 point) {
		RocketEntity rocket = shell(level, shooter, from, velocity, power);
		rocket.launchedFrom = launcher;
		rocket.turnRate = turnRate;
		rocket.homingEntity = target;
		rocket.homingPoint = point;
		if (target != null) {
			rocket.entityData.set(TARGET, target.getId());
		}
		return rocket;
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.ROCKET;
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
	protected boolean canHitEntity(Entity entity) {
		return entity != launchedFrom && super.canHitEntity(entity);
	}

	@Override
	public void tick() {
		if (level() instanceof ServerLevel level) {
			if (laserGuided) {
				steerToCrosshair();
			} else if (homingEntity != null || homingPoint != null) {
				home(level);
				if (!isAlive()) {
					return;
				}
			}
			if (tickCount > (turnRate > 0 ? GUIDED_LIFETIME : LIFETIME)) {
				explode(level, power, true);
				return;
			}
			if (tickCount % 2 == 0) {
				level.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 1, 0.02, 0.02, 0.02, 0.0);
			}
			level.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 1, 0.03, 0.03, 0.03, 0.0);
		}
		super.tick();
	}

	private void steerToCrosshair() {
		Entity owner = getOwner();
		if (!(owner instanceof Player player) || !(player.getMainHandItem().getItem() instanceof RocketLauncherItem)) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 aimEnd = eye.add(player.getLookAngle().scale(160));
		HitResult hit = level().clip(new ClipContext(eye, aimEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 target = hit.getType() == HitResult.Type.MISS ? aimEnd : hit.getLocation();
		Vec3 desired = target.subtract(position()).normalize().scale(speed);
		// Turn at most ~15% toward the laser point per tick, so rockets curve rather than snap.
		setDeltaMovement(getDeltaMovement().lerp(desired, 0.15).normalize().scale(speed));
	}

	/** Turn toward the target at a limited rate (proportional navigation, simplified). */
	private void home(ServerLevel level) {
		if (homingEntity != null && (!homingEntity.isAlive() || homingEntity.isRemoved())) {
			homingPoint = homingEntity.position();
			homingEntity = null;
			entityData.set(TARGET, -1);
		}
		if (homingEntity != null && Countermeasures.obscured(level, homingEntity)) {
			// Smoke: the seeker loses the target and flies on to where it last saw it.
			homingPoint = homingEntity.position();
			homingEntity = null;
			entityData.set(TARGET, -1);
		}
		if (homingEntity != null) {
			Vec3 decoy = Countermeasures.decoyFor(level, homingEntity, this);
			if (decoy != null) {
				homingPoint = decoy;
				homingEntity = null;
				entityData.set(TARGET, -1);
			}
		}
		Vec3 aim;
		if (homingEntity != null) {
			// Lead the target a little.
			double eta = position().distanceTo(homingEntity.position()) / Math.max(speed, 0.5);
			aim = homingEntity.getBoundingBox().getCenter().add(homingEntity.getDeltaMovement().scale(Math.min(eta, 20) * 0.6));
			if (position().distanceTo(homingEntity.getBoundingBox().getCenter()) < 2.5) {
				// Proximity fuse.
				setPos(homingEntity.getBoundingBox().getCenter().lerp(position(), 0.5));
				explode(level, power, true);
				return;
			}
		} else {
			aim = homingPoint;
		}
		if (topAttack) {
			double horizontal = Math.sqrt(Math.pow(aim.x - getX(), 2) + Math.pow(aim.z - getZ(), 2));
			// Climb to ~20 above the target, then pitch over early enough to come straight down on the roof.
			if (horizontal > Math.max(18.0, (getY() - aim.y) * 1.3)) {
				aim = new Vec3(aim.x, Math.max(aim.y + 20.0, getY()), aim.z);
			}
		}
		Vec3 current = getDeltaMovement().normalize();
		Vec3 wanted = aim.subtract(position()).normalize();
		double angle = Math.acos(Math.max(-1, Math.min(1, current.dot(wanted))));
		double maxTurn = Math.toRadians(topAttack ? Math.max(turnRate, 12.0) : turnRate);
		Vec3 next = angle <= maxTurn ? wanted : current.lerp(wanted, maxTurn / angle).normalize();
		setDeltaMovement(next.scale(speed));
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		explode(level, power, true);
	}
}
