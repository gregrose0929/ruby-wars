package dev.rubymod.entity;

import java.util.List;

import dev.rubymod.entity.AircraftType.Weapon;
import dev.rubymod.game.Countermeasures;
import dev.rubymod.game.Explosions;
import dev.rubymod.game.Rules;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModEntities;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A flyable aircraft (fighter, attack jet, or bomber; see {@link AircraftType}).
 *
 * On the ground: W/S throttle (S at idle throttle brakes), A/D steer the nose
 * wheel. Roll down the runway until you reach takeoff speed, then pull up (look
 * up) to lift off. In the air the aircraft turns toward where you look; it
 * needs speed to stay up (climbing costs speed, diving gains it) and stalls if
 * it gets too slow. Land gently or you crash.
 *
 * Weapons: left-click fires the selected weapon, R switches weapons, Space
 * drops flares, Sneak ejects (with a parachute when airborne).
 *
 * The flight model runs on the pilot's client (like a boat), and the server
 * checks for crashes using the positions it receives.
 */
public class AircraftEntity extends Mob {
	private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> WEAPON = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> CANNON_AMMO = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> MISSILES = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> BOMBS = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> FLARES = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);

	private final AircraftType kind;

	// Flight state (simulated on the pilot's client).
	private double speed;
	private float throttle;

	// Server bookkeeping.
	/** Recent positions (server), newest last: velocity is averaged over them to smooth out bunched network updates. */
	private final java.util.ArrayDeque<Vec3> recentPositions = new java.util.ArrayDeque<>();
	private Vec3 serverVelocity = Vec3.ZERO;
	/** Smoothed speeds over the last few ticks, newest last (for crash detection). */
	private final java.util.ArrayDeque<Double> recentSpeeds = new java.util.ArrayDeque<>();
	private int cannonCooldown;
	private int missileCooldown;
	private int bombCooldown;
	private int flareCooldown;
	private boolean leftWing;

	// Autopilot: air strike flyover or a circling target drone.
	private Vec3 strikeTarget;
	private Vec3 droneCenter;
	private int bombsLeft;
	private Entity strikeOwner;
	private long forcedChunk = Long.MIN_VALUE;

	public AircraftEntity(EntityType<? extends AircraftEntity> type, Level level) {
		super(type, level);
		this.kind = ModEntities.aircraftType(type);
		setPersistenceRequired();
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder attributes(AircraftType type) {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, type.health())
				.add(Attributes.ARMOR, 8.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.STEP_HEIGHT, 1.1);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		AircraftType type = ModEntities.aircraftType(getType());
		builder.define(ROLL, 0.0F);
		builder.define(WEAPON, 0);
		builder.define(CANNON_AMMO, type.cannonAmmo());
		builder.define(MISSILES, type.missiles());
		builder.define(BOMBS, type.bombs());
		builder.define(FLARES, type.flares());
	}

	// ----- Accessors for the HUD -----

	public AircraftType kind() {
		return kind;
	}

	public float roll() {
		return entityData.get(ROLL);
	}

	public double speed() {
		return speed;
	}

	public float throttle() {
		return throttle;
	}

	/** Start already flying (spawned in the air): this speed, full throttle. */
	public void launch(double speed) {
		this.speed = speed;
		this.throttle = 1.0F;
	}

	public Weapon selectedWeapon() {
		List<Weapon> weapons = kind.weapons();
		return weapons.isEmpty() ? null : weapons.get(Math.floorMod(entityData.get(WEAPON), weapons.size()));
	}

	public int ammo(Weapon weapon) {
		return switch (weapon) {
			case CANNON -> entityData.get(CANNON_AMMO);
			case AAM, AGM -> entityData.get(MISSILES);
			case BOMBS -> entityData.get(BOMBS);
		};
	}

	public int flares() {
		return entityData.get(FLARES);
	}

	public boolean isAirborne() {
		return !onGround();
	}

	/** Server side: is there air under the wheels? (The pilot's client moves the aircraft, so onGround isn't reliable here.) */
	private boolean airborneOnServer() {
		return level().noCollision(this, getBoundingBox().move(0, -0.4, 0));
	}

	/** Where the nose points. */
	public Vec3 forward() {
		return Vec3.directionFromRotation(getXRot(), getYRot());
	}

	// ----- Riding -----

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (player.isSecondaryUseActive() || isVehicle()) {
			return InteractionResult.PASS;
		}
		if (!level().isClientSide()) {
			player.startRiding(this);
			if (player instanceof ServerPlayer sp) {
				sp.sendSystemMessage(Component.literal(kind.name() + ": W/S throttle, A/D steer on the ground, reach takeoff speed then look up to lift off. "
						+ "Left-click fires, R switches weapons, Space drops flares, Sneak ejects."));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player pilot ? pilot : null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		return new Vec3(0, kind.seatUp(), kind.seatForward()).yRot((float) Math.toRadians(-getYRot()));
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (!level().isClientSide()) {
			speed = serverVelocity.length(); // Keep gliding instead of stopping dead.
		}
		if (!level().isClientSide() && passenger instanceof LivingEntity living && airborneOnServer()) {
			living.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 20, 0, false, false));
			if (passenger instanceof ServerPlayer player) {
				player.sendOverlayMessage(Component.literal("Ejected! Parachute deployed."));
			}
		}
	}

	@Override
	protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
		return new Vec3(controller.xxa, 0, controller.zza);
	}

	// ----- Flight model -----

	@Override
	public void travel(Vec3 input) {
		LivingEntity pilot = getControllingPassenger();
		if (pilot != null) {
			fly(pilot, input);
		} else if (strikeTarget != null || droneCenter != null) {
			autopilot();
		} else {
			coast();
		}
	}

	private void fly(LivingEntity pilot, Vec3 input) {
		boolean airborne = isAirborne();

		// Throttle and brakes.
		boolean braking = false;
		if (input.z > 0) {
			throttle = Math.min(1.0F, throttle + 0.015F);
		} else if (input.z < 0) {
			if (!airborne && throttle <= 0.0F) {
				braking = true;
			}
			throttle = Math.max(0.0F, throttle - 0.025F);
		}

		// Speed: thrust minus drag, gravity along the flight path, rolling resistance and brakes on the ground.
		double accel = throttle * kind.thrust() - kind.drag() * speed * speed;
		if (airborne) {
			accel -= Math.sin(Math.toRadians(-getXRot())) * 0.035;
		} else {
			accel -= 0.002;
			if (braking) {
				accel -= 0.03;
			}
		}
		speed = Mth.clamp(speed + accel, 0.0, kind.maxSpeed() * 1.35);

		float pitch = getXRot();
		float yaw = getYRot();
		float roll;
		if (!airborne) {
			// Taxi: A/D steer the nose wheel; turning is gentler at speed.
			float steer = (float) (-input.x * (speed < 0.05 ? 0 : Math.max(1.2, 4.0 - speed * 3.0)));
			yaw += steer;
			// Rotate for takeoff: only at takeoff speed, and only while the pilot pulls up.
			if (speed >= kind.rotateSpeed() && pilot.getXRot() < -4.0F) {
				pitch = Math.max(-15.0F, pitch - 1.2F);
			} else {
				pitch = Math.min(0.0F, pitch + 2.0F);
			}
			roll = roll() * 0.8F;
		} else {
			// Mouse aim: turn toward where the pilot looks. Slow aircraft barely respond.
			float authority = (float) Mth.clamp((speed - kind.stallSpeed() * 0.6) / kind.rotateSpeed(), 0.15, 1.0);
			float yawRate = kind.turnRate() * authority;
			float pitchRate = kind.turnRate() * 0.6F * authority;
			float yawDelta = Mth.clamp(Mth.wrapDegrees(pilot.getYRot() - yaw), -yawRate, yawRate);
			float pitchDelta = Mth.clamp(pilot.getXRot() - pitch, -pitchRate, pitchRate);
			yaw += yawDelta;
			pitch = Mth.clamp(pitch + pitchDelta, -70.0F, 75.0F);
			roll = Mth.lerp(0.2F, roll(), Mth.clamp(yawDelta * 10.0F, -60.0F, 60.0F));
		}
		setYRot(yaw);
		setXRot(pitch);
		yBodyRot = yaw;
		yHeadRot = yaw;
		entityData.set(ROLL, roll);

		Vec3 velocity;
		if (!airborne && pitch >= -0.5F) {
			// Rolling on the wheels.
			velocity = Vec3.directionFromRotation(0, yaw).scale(speed).add(0, -0.1, 0);
		} else {
			velocity = Vec3.directionFromRotation(pitch, yaw).scale(speed);
			if (speed < kind.stallSpeed()) {
				// Stall: the wings stop lifting and the nose falls.
				double sink = 0.1 + (kind.stallSpeed() - speed) * 1.4;
				velocity = velocity.add(0, -sink, 0);
				setXRot(Math.min(75.0F, getXRot() + 1.8F));
			} else if (speed < kind.rotateSpeed()) {
				// Mushy, just above stall: sink a little.
				velocity = velocity.add(0, -(kind.rotateSpeed() - speed) * 0.5, 0);
			}
		}
		setDeltaMovement(velocity);
		move(MoverType.SELF, velocity);
		if (horizontalCollision && speed > 0.3) {
			speed *= 0.3;
		}
	}

	/** No pilot: glide down and roll to a stop. */
	private void coast() {
		speed *= onGround() ? 0.85 : 0.985;
		Vec3 velocity = Vec3.directionFromRotation(0, getYRot()).scale(speed).add(0, onGround() ? -0.1 : -0.45, 0);
		setDeltaMovement(velocity);
		move(MoverType.SELF, velocity);
		if (onGround()) {
			setXRot(Math.min(0.0F, getXRot() + 2.0F));
		}
		entityData.set(ROLL, roll() * 0.9F);
	}

	// ----- Weapons (server) -----

	/** Left-click. {@code target} is the entity the pilot's HUD locked (or null), {@code point} the marked ground point. */
	public void fire(ServerPlayer pilot, Entity target, Vec3 point) {
		if (pilot == getControllingPassenger()) {
			fireSelected(pilot, target, point);
		}
	}

	/** Fire the selected weapon on {@code pilot}'s behalf (no seat check; the selftest uses this directly). */
	public void fireSelected(ServerPlayer pilot, Entity target, Vec3 point) {
		Weapon weapon = selectedWeapon();
		if (weapon == null) {
			return;
		}
		if (ammo(weapon) <= 0) {
			pilot.sendOverlayMessage(Component.literal(weapon.title + ": empty"));
			return;
		}
		switch (weapon) {
			case CANNON -> fireCannon(pilot);
			case AAM, AGM -> fireMissile(pilot, weapon, validTarget(target), point);
			case BOMBS -> dropBomb(pilot);
		}
	}

	public void cycleWeapon(ServerPlayer pilot) {
		if (pilot == getControllingPassenger()) {
			nextWeapon();
		}
	}

	public void nextWeapon() {
		if (!kind.weapons().isEmpty()) {
			entityData.set(WEAPON, Math.floorMod(entityData.get(WEAPON) + 1, kind.weapons().size()));
			level().playSound(null, getX(), getY(), getZ(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 1.6F);
		}
	}

	public void flares(ServerPlayer pilot) {
		if (pilot != getControllingPassenger() || flareCooldown > 0 || flares() <= 0) {
			return;
		}
		flareCooldown = 20;
		entityData.set(FLARES, flares() - 1);
		Countermeasures.dropFlares((ServerLevel) level(), this);
	}

	/** Only accept a lock the server agrees with: in front of the nose and in range. */
	private Entity validTarget(Entity target) {
		if (target == null || !target.isAlive() || target == this || hasPassenger(target)) {
			return null;
		}
		Vec3 to = target.position().subtract(position());
		if (to.length() > 300) {
			return null;
		}
		double angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(forward()), -1, 1)));
		return angle <= 40 ? target : null;
	}

	private void fireCannon(ServerPlayer pilot) {
		if (cannonCooldown > 0) {
			return;
		}
		cannonCooldown = kind.cannonInterval();
		entityData.set(CANNON_AMMO, ammo(Weapon.CANNON) - 1);
		ServerLevel level = (ServerLevel) level();
		Vec3 dir = forward().add((random.nextDouble() - 0.5) * 0.02, (random.nextDouble() - 0.5) * 0.02, (random.nextDouble() - 0.5) * 0.02).normalize();
		Vec3 start = position().add(0, 0.6, 0).add(dir.scale(kind.scale() * 1.4));
		Vec3 end = start.add(dir.scale(200));
		BlockHitResult blockHit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		Vec3 stop = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
		Entity hit = null;
		double best = Double.MAX_VALUE;
		for (Entity e : level.getEntities(this, new AABB(start, stop).inflate(1.5), e -> e.isAlive() && e.isPickable() && e != pilot)) {
			var clip = e.getBoundingBox().inflate(0.3).clip(start, stop);
			if (clip.isPresent() && start.distanceToSqr(clip.get()) < best) {
				best = start.distanceToSqr(clip.get());
				hit = e;
				stop = clip.get();
			}
		}
		if (hit != null && Rules.canHurt(pilot, hit)) {
			hit.setInvulnerableTime(0);
			hit.hurtServer(level, ModDamage.source(level, ModDamage.BULLET, pilot, pilot), kind.cannonDamage());
		} else if (blockHit.getType() == HitResult.Type.BLOCK) {
			dev.rubymod.gun.BlockDamage.hit(level, blockHit.getBlockPos(), kind.cannonDamage() * 0.8F, pilot);
			level.sendParticles(ParticleTypes.SMOKE, stop.x, stop.y, stop.z, 2, 0.1, 0.1, 0.1, 0.02);
		}
		Vec3 step = stop.subtract(start).normalize();
		double length = start.distanceTo(stop);
		for (double d = 4; d < length; d += 4) {
			Vec3 p = start.add(step.scale(d));
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
		if (tickCount % 2 == 0) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 2.0F, 2.0F);
		}
	}

	private void fireMissile(ServerPlayer pilot, Weapon weapon, Entity target, Vec3 point) {
		if (missileCooldown > 0) {
			return;
		}
		missileCooldown = 15;
		entityData.set(MISSILES, ammo(weapon) - 1);
		Vec3 forward = forward();
		Vec3 side = new Vec3(leftWing ? 1.8 : -1.8, -0.4, 0).yRot((float) Math.toRadians(-getYRot()));
		leftWing = !leftWing;
		Vec3 from = position().add(side).add(forward.scale(2.0)).add(0, 0.6, 0);
		boolean aam = weapon == Weapon.AAM;
		Vec3 velocity = forward.scale(Math.max(aam ? 3.0 : 2.4, speedOnServer() + 1.0));
		Vec3 aimPoint = target == null ? point : null;
		RocketEntity missile = RocketEntity.missile(level(), pilot, this, from, velocity, aam ? 3.5F : 4.5F, aam ? 7.0 : 4.0, target, aimPoint);
		level().addFreshEntity(missile);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 3.0F, 0.7F);
	}

	private void dropBomb(ServerPlayer pilot) {
		if (bombCooldown > 0 || !airborneOnServer()) {
			return;
		}
		bombCooldown = 6;
		entityData.set(BOMBS, ammo(Weapon.BOMBS) - 1);
		BombEntity bomb = BombEntity.drop(level(), pilot, position().add(0, -1.2, 0));
		bomb.setDeltaMovement(serverVelocity.add(0, -0.1, 0));
		level().addFreshEntity(bomb);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.DISPENSER_LAUNCH, SoundSource.PLAYERS, 2.0F, 0.6F);
	}

	private double speedOnServer() {
		return serverVelocity.length();
	}

	// ----- Damage, crashing -----

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(ModDamage.BULLET) || source.is(ModDamage.HEADSHOT)) {
			amount *= 0.4F;
		} else if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			amount *= 2.0F;
		}
		if (source.getEntity() != null && hasPassenger(source.getEntity())) {
			return false;
		}
		return super.hurtServer(level, source, amount);
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource damageSource) {
		return false;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel level) {
			ejectPassengers();
			Explosions.explode(level, this, getX(), getY() + 0.5, getZ(), 4.0F, false);
			releaseChunk(level);
			discard();
		}
	}

	@Override
	public boolean removeWhenFarAway(double distanceSquared) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 previousVelocity = serverVelocity;
		recentPositions.addLast(position());
		if (recentPositions.size() > 4) {
			recentPositions.removeFirst();
		}
		serverVelocity = position().subtract(recentPositions.peekFirst()).scale(1.0 / Math.max(1, recentPositions.size() - 1));
		cannonCooldown = Math.max(0, cannonCooldown - 1);
		missileCooldown = Math.max(0, missileCooldown - 1);
		bombCooldown = Math.max(0, bombCooldown - 1);
		flareCooldown = Math.max(0, flareCooldown - 1);

		double moving = serverVelocity.length();
		if (moving > 0.4 && tickCount % 2 == 0) {
			Vec3 back = position().add(forward().scale(-kind.scale() * 1.1)).add(0, 0.6, 0);
			level.sendParticles(ParticleTypes.SMOKE, back.x, back.y, back.z, 1, 0.05, 0.05, 0.05, 0.0);
		}
		if (moving > 0.2 && tickCount % 16 == 0) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.NEUTRAL, 1.5F, 0.8F + (float) moving * 0.4F);
		}
		// Crashes, judged from how the aircraft moved (the pilot's client flies it): a sudden stop right next to blocks
		// (flew into something), or slamming down onto the ground. Requiring blocks nearby keeps network hiccups,
		// which can also make the aircraft look like it stopped for a tick, from counting as crashes.
		LivingEntity pilot = getControllingPassenger();
		boolean nearBlocks = !level.noCollision(this, getBoundingBox().inflate(0.75, 0.0, 0.75).move(0, 0.5, 0));
		double peak = recentSpeeds.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
		recentSpeeds.addLast(moving);
		if (recentSpeeds.size() > 6) {
			recentSpeeds.removeFirst();
		}
		boolean suddenStop = peak > 0.9 && moving < peak * 0.35 && nearBlocks;
		boolean slammed = previousVelocity.y < -0.6 && serverVelocity.y > previousVelocity.y * 0.3 && !airborneOnServer();
		if (pilot != null && (suddenStop || slammed)) {
			dev.rubymod.RubyMod.LOGGER.info("{} crashed ({}) at {} km/h", kind.name(), suddenStop ? "hit something" : "hard landing",
					Math.round(Math.max(peak, previousVelocity.length()) * 72));
			pilot.hurtServer(level, level.damageSources().flyIntoWall(), 12.0F);
			hurtServer(level, level.damageSources().flyIntoWall(), 10000.0F);
			return;
		}
		if (getHealth() < getMaxHealth() * 0.3F && tickCount % 3 == 0) {
			level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.8, getZ(), 1, 0.3, 0.1, 0.3, 0.01);
		}
		if (strikeTarget != null || droneCenter != null) {
			keepChunkLoaded(level);
		}
	}

	// ----- Autopilot -----

	/** Spawn an attack jet that flies over {@code target} along {@code yaw}, carpet-bombing it. */
	public static AircraftEntity airStrike(ServerLevel level, Entity owner, Vec3 target, float yaw) {
		AircraftEntity jet = new AircraftEntity(ModEntities.ATTACK_JET, level);
		Vec3 start = target.add(Vec3.directionFromRotation(0, yaw).scale(-48)).add(0, 30, 0);
		jet.snapTo(start.x, start.y, start.z, yaw, 0);
		jet.strikeTarget = target;
		jet.bombsLeft = 10;
		jet.strikeOwner = owner;
		jet.speed = 1.6;
		jet.setPermanentlyInvulnerable(true);
		return jet;
	}

	/** A fighter that circles {@code center} at its height: a target for missile practice. */
	public static AircraftEntity targetDrone(ServerLevel level, Vec3 center, double radius) {
		AircraftEntity drone = new AircraftEntity(ModEntities.FIGHTER_JET, level);
		drone.snapTo(center.x + radius, center.y, center.z, 180, 0);
		drone.droneCenter = center;
		drone.speed = 1.0;
		drone.setCustomName(Component.literal("Target Drone"));
		return drone;
	}

	private void autopilot() {
		Vec3 velocity;
		if (droneCenter != null) {
			// Circle the center counter-clockwise, banked.
			Vec3 radial = position().subtract(droneCenter).multiply(1, 0, 1);
			Vec3 tangent = new Vec3(-radial.z, 0, radial.x).normalize();
			setYRot((float) Math.toDegrees(Mth.atan2(-tangent.x, tangent.z)));
			velocity = tangent.scale(speed).add(0, (droneCenter.y - getY()) * 0.05, 0);
			entityData.set(ROLL, -25.0F);
		} else {
			velocity = Vec3.directionFromRotation(0, getYRot()).scale(speed);
		}
		setDeltaMovement(velocity);
		setPos(position().add(velocity));
		if (!(level() instanceof ServerLevel level) || strikeTarget == null) {
			return;
		}
		double dx = strikeTarget.x - getX();
		double dz = strikeTarget.z - getZ();
		double along = dx * velocity.x + dz * velocity.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (bombsLeft > 0 && distance < 9 && tickCount % 2 == 0) {
			bombsLeft--;
			BombEntity bomb = BombEntity.drop(level, strikeOwner != null ? strikeOwner : this, position().add(0, -1.5, 0));
			bomb.setDeltaMovement(0, -0.8, 0);
			level.addFreshEntity(bomb);
		}
		if (tickCount % 10 == 0) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.HOSTILE, 6.0F, 1.2F);
		}
		if (along < 0 && distance > 70 || tickCount > 300) {
			releaseChunk(level);
			discard();
		}
	}

	private void keepChunkLoaded(ServerLevel level) {
		int cx = (int) Math.floor(getX()) >> 4;
		int cz = (int) Math.floor(getZ()) >> 4;
		long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
		if (key != forcedChunk) {
			releaseChunk(level);
			level.setChunkForced(cx, cz, true);
			forcedChunk = key;
		}
	}

	private void releaseChunk(ServerLevel level) {
		if (forcedChunk != Long.MIN_VALUE) {
			level.setChunkForced((int) (forcedChunk >> 32), (int) forcedChunk, false);
			forcedChunk = Long.MIN_VALUE;
		}
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("cannon_ammo", ammo(Weapon.CANNON));
		output.putInt("missiles", entityData.get(MISSILES));
		output.putInt("bombs", entityData.get(BOMBS));
		output.putInt("flares", flares());
		output.putBoolean("strike", strikeTarget != null);
		if (droneCenter != null) {
			output.putDouble("drone_x", droneCenter.x);
			output.putDouble("drone_y", droneCenter.y);
			output.putDouble("drone_z", droneCenter.z);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		entityData.set(CANNON_AMMO, input.getIntOr("cannon_ammo", kind.cannonAmmo()));
		entityData.set(MISSILES, input.getIntOr("missiles", kind.missiles()));
		entityData.set(BOMBS, input.getIntOr("bombs", kind.bombs()));
		entityData.set(FLARES, input.getIntOr("flares", kind.flares()));
		if (input.getBooleanOr("strike", false)) {
			discard(); // An air strike from before a restart: just leave.
		}
		double dx = input.getDoubleOr("drone_x", Double.NaN);
		if (!Double.isNaN(dx)) {
			droneCenter = new Vec3(dx, input.getDoubleOr("drone_y", getY()), input.getDoubleOr("drone_z", getZ()));
			speed = 1.0;
		}
	}

	/** Refill ammo, missiles, bombs, and flares (training range resupply). */
	public void rearm() {
		entityData.set(CANNON_AMMO, kind.cannonAmmo());
		entityData.set(MISSILES, kind.missiles());
		entityData.set(BOMBS, kind.bombs());
		entityData.set(FLARES, kind.flares());
		setHealth(getMaxHealth());
	}
}
