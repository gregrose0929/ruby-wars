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
	private static final EntityDataAccessor<Boolean> GEAR_DOWN = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.BOOLEAN);

	/** Sink rate (blocks/tick) a landing can take without damage: 6 m/s. */
	private static final double SAFE_SINK = 0.3;
	/**
	 * How the pilot's client reports an impact it felt (severity: 0 = scrape, 1+ = destroyed).
	 * The flight model runs on the pilot's client, which sees collisions exactly; the client
	 * mod points this at a packet sender.
	 */
	public static java.util.function.DoubleConsumer crashReporter = severity -> { };
	private int crashCooldown;

	// Designated target (T): every weapon goes for it.
	private Entity designatedEntity;
	private Vec3 designatedPoint;
	/** Autopilot (server): when on, fly this heading and altitude instead of following the pilot's view. */
	private boolean autopilot;
	private float autopilotHeading;
	private double autopilotAltitude;

	public void setAutopilot(Player pilot, boolean on, float heading, double altitude) {
		if (pilot == getControllingPassenger()) {
			// Set on both sides: the pilot's client flies the aircraft, the server keeps it for anyone else watching.
			autopilot = on && (isAirborne() || !onGround());
			autopilotHeading = heading;
			autopilotAltitude = altitude;
		}
	}

	public boolean autopilotOn() {
		return autopilot;
	}
	/** The last player who damaged us, and when (a crash soon after is their kill). */
	private ServerPlayer lastAttacker;
	private long lastAttackedAt;

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
	private double droneRadius;
	private boolean droneRespawns;
	private int bombsLeft;
	private Entity strikeOwner;

	public AircraftEntity(EntityType<? extends AircraftEntity> type, Level level) {
		super(type, level);
		this.kind = ModEntities.aircraftType(type);
		setPersistenceRequired();
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder attributes(AircraftType type) {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, type.health())
				.add(Attributes.CAMERA_DISTANCE, 6.0 + type.scale() * 2.5)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.STEP_HEIGHT, 1.1);
	}

	/** Paint scheme (see PaintKitItem.SCHEMES): 0 = factory default. */
	private static final EntityDataAccessor<Integer> PAINT = SynchedEntityData.defineId(AircraftEntity.class, EntityDataSerializers.INT);

	public int paint() {
		return entityData.get(PAINT);
	}

	public void setPaint(int paint) {
		entityData.set(PAINT, paint);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(PAINT, 0);
		AircraftType type = ModEntities.aircraftType(getType());
		builder.define(ROLL, 0.0F);
		builder.define(WEAPON, 0);
		builder.define(CANNON_AMMO, type.cannonAmmo());
		builder.define(MISSILES, type.missiles());
		builder.define(BOMBS, type.bombs() + type.rockets());
		builder.define(FLARES, type.flares());
		builder.define(GEAR_DOWN, true);
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
			case BOMBS, ROCKETS -> entityData.get(BOMBS);
		};
	}

	public int flares() {
		return entityData.get(FLARES);
	}

	public boolean gearDown() {
		return entityData.get(GEAR_DOWN);
	}

	/** G: raise or lower the landing gear (raising only works in the air). */
	public void toggleGear(ServerPlayer pilot) {
		if (pilot != getControllingPassenger()) {
			return;
		}
		if (kind.rotorcraft()) {
			return; // Fixed landing gear.
		}
		if (gearDown() && !airborneOnServer()) {
			pilot.sendOverlayMessage(Component.literal("Can't raise the gear on the ground"));
			return;
		}
		entityData.set(GEAR_DOWN, !gearDown());
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 1.0F, gearDown() ? 0.8F : 1.2F);
	}

	/** Crashing shortly after being shot up counts as the shooter's kill. */
	private DamageSource crashSource(ServerLevel level) {
		if (lastAttacker != null && level.getGameTime() - lastAttackedAt < 200 && lastAttacker.isAlive()) {
			return ModDamage.source(level, ModDamage.ANTI_ARMOR, null, lastAttacker);
		}
		return level.damageSources().flyIntoWall();
	}

	/** T: designate the entity or ground point the pilot is looking at (null both = clear). */
	public void designate(ServerPlayer pilot, Entity target, Vec3 point) {
		if (pilot != getControllingPassenger()) {
			return;
		}
		designatedEntity = target != null && target != this && !hasPassenger(target) && target.distanceTo(this) < 1600 ? target : null;
		designatedPoint = designatedEntity == null ? point : null;
	}

	/** The designated entity, if it's still a valid target. */
	private Entity designated() {
		if (designatedEntity != null && (!designatedEntity.isAlive() || designatedEntity.isRemoved())) {
			designatedPoint = designatedEntity.position();
			designatedEntity = null;
		}
		return designatedEntity;
	}

	/** The pilot's client felt an impact (see {@link #crashReporter}). */
	public void reportCrash(ServerPlayer pilot, float severity) {
		if (pilot != getControllingPassenger() || !(level() instanceof ServerLevel level) || severity <= 0.02F || crashCooldown > 0) {
			return;
		}
		crashCooldown = 10;
		if (severity >= 1.0F) {
			dev.rubymod.RubyMod.LOGGER.info("{} crashed (impact severity {})", kind.name(), String.format("%.2f", severity));
			pilot.hurtServer(level, crashSource(level), 12.0F);
			hurtServer(level, crashSource(level), 10000.0F);
			return;
		}
		hurtServer(level, crashSource(level), getMaxHealth() * severity);
		pilot.hurtServer(level, crashSource(level), 8.0F * severity);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.5, getZ(), 10, 1.0, 0.3, 1.0, 0.05);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.5F, 0.6F);
		pilot.sendOverlayMessage(Component.literal((severity > 0.4F ? "Heavy damage! " : "Hard landing! ") + "Hull "
				+ Math.round(getHealth() / getMaxHealth() * 100) + "%"));
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
		if (player.isSecondaryUseActive() || isVehicle() || player.getItemInHand(hand).is(dev.rubymod.registry.ModItems.PAINT_KIT)) {
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
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);
		// A freshly spawned aircraft hasn't been told it's on the ground yet on the pilot's
		// client, so its first ticks look like a hard landing: don't count them.
		crashCooldown = 20;
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		autopilot = false;
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
		if (pilot != null && kind.rotorcraft()) {
			flyRotor(pilot, input);
		} else if (pilot != null) {
			fly(pilot, input);
		} else if (strikeTarget != null || droneCenter != null) {
			autopilot();
		} else {
			coast();
		}
	}

	private void fly(LivingEntity pilot, Vec3 input) {
		boolean airborne = isAirborne();
		if (!airborne) {
			autopilot = false;
		}

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
		if (autopilot && airborne && !kind.rotorcraft()) {
			// Autothrottle: never let the plane bleed down toward a stall while you aim, so
			// switching the autopilot off hands you a plane that's still flying. It adds just
			// enough power to cruise at a safe speed (full power if already near a stall).
			double cruise = kind.rotateSpeed() * 1.3;
			float cruiseThrottle = (float) Math.min(1.0, kind.drag() * cruise * cruise / kind.thrust() * 1.1);
			if (speed < kind.rotateSpeed()) {
				throttle = Math.min(1.0F, throttle + 0.05F);
			} else if (speed < cruise && throttle < cruiseThrottle) {
				throttle = Math.min(cruiseThrottle, throttle + 0.03F);
			}
		}

		// Speed: thrust minus drag, gravity along the flight path, rolling resistance and brakes on the ground.
		double drag = kind.drag() * (gearDown() ? 1.0 : 0.85);
		double accel = throttle * kind.thrust() - drag * speed * speed;
		if (airborne) {
			accel -= Math.sin(Math.toRadians(-getXRot())) * 0.035;
		} else {
			accel -= gearDown() ? 0.0015 : 0.05; // Rolling resistance, or scraping along on the belly.
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
			float wantYaw = pilot.getYRot();
			float wantPitch = pilot.getXRot();
			if (autopilot) {
				// Hold the heading; pitch gently toward the held altitude (negative pitch climbs).
				wantYaw = autopilotHeading;
				wantPitch = (float) Mth.clamp(-(autopilotAltitude - getY()) * 1.5, -12.0, 12.0);
			}
			float yawDelta = Mth.clamp(Mth.wrapDegrees(wantYaw - yaw), -yawRate, yawRate);
			float pitchDelta = Mth.clamp(wantPitch - pitch, -pitchRate, pitchRate);
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
		if (crashCooldown > 0) {
			crashCooldown--;
		}
		// Impacts, felt here on the pilot's client.
		double severity = 0.0;
		if (airborne && onGround()) {
			double sink = -velocity.y;
			if (sink > SAFE_SINK) {
				severity = (sink - SAFE_SINK) / 0.35; // ~13 m/s sink destroys the aircraft
			}
			if (getXRot() > 8.0F) {
				severity = Math.max(severity, (getXRot() - 8.0F) / 12.0F * speed); // nose first
			}
			if (!gearDown()) {
				severity = Math.max(severity, Math.max(0.35, speed / kind.rotateSpeed() * 0.6)); // belly landing
			}
		}
		if (horizontalCollision && speed > 0.6) {
			severity = Math.max(severity, (speed - 0.6) / 0.9);
			speed *= 0.3;
		} else if (horizontalCollision && speed > 0.3) {
			speed *= 0.3;
		}
		if (severity > 0.02 && crashCooldown == 0 && level().isClientSide()) {
			crashCooldown = 10;
			crashReporter.accept(severity);
		}
	}

	/** Helicopter velocity (the rotor model doesn't use the airspeed/throttle model). */
	private Vec3 rotorVelocity = Vec3.ZERO;

	/**
	 * Helicopter: W/S climb and descend (no input holds altitude), the nose turns to where the
	 * pilot looks, looking down tilts the rotor forward to fly forward (up flies backward),
	 * A/D strafe. Lands vertically anywhere; touching down too hard or too fast damages it.
	 */
	private void flyRotor(LivingEntity pilot, Vec3 input) {
		boolean airborne = isAirborne();
		float yaw = getYRot();
		float yawDelta = Mth.clamp(Mth.wrapDegrees((autopilot ? autopilotHeading : pilot.getYRot()) - yaw), -kind.turnRate(), kind.turnRate());
		yaw += yawDelta;
		// Rotor tilt follows the pilot's look, limited to +-25 degrees. On autopilot the helicopter hovers level.
		float tilt = airborne && !autopilot ? Mth.clamp(pilot.getXRot() - 10.0F, -20.0F, 25.0F) : 0.0F;
		float pitch = Mth.lerp(0.15F, getXRot(), tilt);
		throttle = (float) Mth.clamp(throttle + (input.z > 0 ? 0.04 : input.z < 0 ? -0.04 : (0.5 - throttle) * 0.1), 0.0, 1.0);

		Vec3 forward = Vec3.directionFromRotation(0, yaw);
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		Vec3 v = rotorVelocity;
		// Horizontal: tilt pushes along the nose, A/D push sideways, drag.
		Vec3 push = forward.scale(pitch / 25.0 * kind.thrust() * 2.0).add(right.scale(-input.x * kind.thrust()));
		Vec3 horizontal = new Vec3(v.x, 0, v.z).add(push).scale(0.985);
		if (horizontal.length() > kind.maxSpeed()) {
			horizontal = horizontal.normalize().scale(kind.maxSpeed());
		}
		// Vertical: W climbs, S descends, otherwise hold altitude.
		double targetClimb = input.z > 0 ? 0.35 : input.z < 0 ? -0.3 : 0.0;
		double vy = Mth.lerp(0.08, v.y, targetClimb);
		if (!airborne && targetClimb <= 0) {
			vy = Math.min(vy, 0) - 0.05;
			horizontal = horizontal.scale(0.7); // wheels on the ground
		}
		Vec3 velocity = new Vec3(horizontal.x, vy, horizontal.z);
		rotorVelocity = velocity;
		speed = horizontal.length();

		setYRot(yaw);
		setXRot(pitch);
		yBodyRot = yaw;
		yHeadRot = yaw;
		entityData.set(ROLL, Mth.lerp(0.2F, roll(), Mth.clamp(yawDelta * 6.0F + (float) input.x * -12.0F, -30.0F, 30.0F)));
		setDeltaMovement(velocity);
		move(MoverType.SELF, velocity);
		if (crashCooldown > 0) {
			crashCooldown--;
		}
		double severity = 0.0;
		if (airborne && onGround()) {
			severity = Math.max((-velocity.y - 0.35) / 0.4, (speed - 1.0) / 1.0);
		}
		if (horizontalCollision && speed > 0.8) {
			severity = Math.max(severity, (speed - 0.8) / 1.0);
		}
		if (horizontalCollision) {
			rotorVelocity = new Vec3(0, rotorVelocity.y, 0);
		}
		if (severity > 0.02 && crashCooldown == 0 && level().isClientSide()) {
			crashCooldown = 10;
			crashReporter.accept(severity);
		}
	}

	/** Helicopter rocket pods: a pair of unguided rockets per click. */
	private void fireRockets(ServerPlayer pilot) {
		if (bombCooldown > 0) {
			return;
		}
		bombCooldown = 4;
		ServerLevel level = (ServerLevel) level();
		Vec3 forward = forward();
		for (int side = -1; side <= 1; side += 2) {
			if (ammo(Weapon.ROCKETS) <= 0) {
				break;
			}
			entityData.set(BOMBS, ammo(Weapon.ROCKETS) - 1);
			Vec3 offset = new Vec3(side * 0.9 * kind.scale(), -0.2, 0).yRot((float) Math.toRadians(-getYRot()));
			Vec3 from = position().add(0, 0.9, 0).add(offset).add(forward.scale(1.5));
			Vec3 dir = forward.add((random.nextDouble() - 0.5) * 0.03, (random.nextDouble() - 0.5) * 0.03, (random.nextDouble() - 0.5) * 0.03);
			level.addFreshEntity(RocketEntity.shell(level, pilot, from, dir.normalize().scale(3.5).add(serverVelocity), 2.5F).withAntiArmor(30.0F));
		}
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.5F, 1.2F);
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
			case AAM, AGM -> {
				Entity aim = validTarget(target);
				if (aim == null && designated() != null) {
					aim = designated();
				}
				fireMissile(pilot, weapon, aim, aim == null && designatedPoint != null ? designatedPoint : point);
			}
			case BOMBS -> dropBomb(pilot, validTarget(target), point);
			case ROCKETS -> fireRockets(pilot);
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
		if (to.length() > dev.rubymod.game.Range.CONTACT_RANGE) {
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
		Vec3 dir = forward().add((random.nextDouble() - 0.5) * 0.012, (random.nextDouble() - 0.5) * 0.012, (random.nextDouble() - 0.5) * 0.012).normalize();
		Vec3 start = gunPosition();
		float antiArmor = kind.cannonDamage() * (kind == AircraftType.ATTACK ? 1.0F : 0.5F);
		dev.rubymod.game.CannonRounds.fire(level, pilot, this, start, dir.scale(dev.rubymod.game.CannonRounds.SPEED).add(serverVelocity),
				kind.cannonDamage(), antiArmor);
		level.sendParticles(ParticleTypes.FLAME, true, false, start.x, start.y, start.z, 3, 0.1, 0.1, 0.1, 0.02);
		level.playSound(null, start.x, start.y, start.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 3.0F, 1.7F + random.nextFloat() * 0.3F);
		if (tickCount % 2 == 0) {
			level.playSound(null, start.x, start.y, start.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.7F, 2.0F);
		}
		dev.rubymod.net.Net.send(pilot, new dev.rubymod.net.Payloads.Shake(0.35F, 3));
	}

	/** Where cannon rounds leave the nose. */
	public Vec3 gunPosition() {
		return position().add(0, 0.6, 0).add(forward().scale(kind.scale() * 1.4));
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
		RocketEntity missile = RocketEntity.missile(level(), pilot, this, from, velocity, aam ? 3.5F : 4.5F, aam ? 7.0 : 4.0, target, aimPoint)
				.withAntiArmor(aam ? 110.0F : 75.0F);
		level().addFreshEntity(missile);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 3.0F, 0.7F);
	}

	/** Drop a guided bomb: it glides to the locked target, else the designated one, else the point the pilot aimed at. */
	private void dropBomb(ServerPlayer pilot, Entity target, Vec3 point) {
		if (kind.rotorcraft()) {
			return;
		}
		if (bombCooldown > 0 || !airborneOnServer()) {
			return;
		}
		bombCooldown = 6;
		entityData.set(BOMBS, ammo(Weapon.BOMBS) - 1);
		BombEntity bomb = BombEntity.drop(level(), pilot, position().add(0, -1.2, 0));
		bomb.setDeltaMovement(serverVelocity.add(0, -0.1, 0));
		Entity aim = target != null ? target : designated();
		bomb.guide(aim, aim != null ? null : designatedPoint != null ? designatedPoint : point);
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
			amount *= 0.3F;
		} else if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			amount *= 0.5F; // Splash; warheads hit through Armor (ANTI_ARMOR) instead.
		}
		if (source.getEntity() != null && hasPassenger(source.getEntity())) {
			return false;
		}
		if (source.getEntity() instanceof ServerPlayer attacker) {
			lastAttacker = attacker;
			lastAttackedAt = level.getGameTime();
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
			level.addFreshEntity(WreckEntity.of(level, this));
			if (droneRespawns && droneCenter != null) {
				dev.rubymod.game.TrainingGround.queueRespawn(level, droneCenter, droneRadius);
			}
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

	/**
	 * Where the engine exhaust and wingtips are, in model pixels (x, y, z of the exhaust,
	 * then x, y, z of the right wingtip; the left is mirrored). Matches tools/aircraft_models.py.
	 */
	private double[] effectPoints() {
		if (kind == AircraftType.ATTACK) {
			return new double[] {6.5, 15, -18, 31, 7, -4};
		}
		if (kind == AircraftType.BOMBER) {
			return new double[] {14, 6, -6, 38, 12, -10};
		}
		return new double[] {0, 9.5, -28, 23, 9, -8};
	}

	/** Client-side effects: afterburner flame, exhaust smoke, wingtip contrails, rotor wash. */
	private void clientEffects() {
		Vec3 vel = position().subtract(xo, yo, zo);
		double v = vel.length();
		boolean flying = !onGround();
		double s = kind.scale() / 16.0;
		if (kind.rotorcraft()) {
			// Rotor wash: dust kicked up under a low-flying helicopter.
			if ((isVehicle() || flying) && tickCount % 2 == 0) {
				double ground = getY();
				for (int i = 0; i < 12 && ground > level().getMinY(); i++) {
					if (!level().getBlockState(net.minecraft.core.BlockPos.containing(getX(), ground - 1, getZ())).isAir()) {
						break;
					}
					ground -= 1;
				}
				if (getY() - ground < 10) {
					for (int i = 0; i < 4; i++) {
						double a = random.nextDouble() * Math.PI * 2;
						level().addParticle(ParticleTypes.CLOUD, getX() + Math.cos(a) * 3, ground + 0.2, getZ() + Math.sin(a) * 3,
								Math.cos(a) * 0.3, 0.02, Math.sin(a) * 0.3);
					}
				}
			}
			return;
		}
		double[] p = effectPoints();
		Vec3 fwd = forward();
		Vec3 up = Vec3.directionFromRotation(getXRot() - 90, getYRot());
		Vec3 right = fwd.cross(up).normalize();
		boolean afterburner = v > kind.maxSpeed() * 0.8 || (isVehicle() && throttle > 0.95F);
		for (int side = p[0] == 0 ? 1 : -1; side <= 1; side += 2) {
			Vec3 nozzle = position().add(0, 1.0, 0).add(fwd.scale(p[2] * s)).add(up.scale((p[1] - 16.0 / kind.scale()) * s))
					.add(right.scale(p[0] * side * s));
			if (afterburner) {
				for (int i = 0; i < 2; i++) {
					level().addParticle(ParticleTypes.FLAME, nozzle.x, nozzle.y, nozzle.z, -fwd.x * 0.4 + vel.x * 0.6, -fwd.y * 0.4 + vel.y * 0.6,
							-fwd.z * 0.4 + vel.z * 0.6);
				}
			} else if (v > 0.2 && tickCount % 2 == 0) {
				level().addParticle(ParticleTypes.SMOKE, nozzle.x, nozzle.y, nozzle.z, vel.x * 0.5, vel.y * 0.5, vel.z * 0.5);
			}
		}
		// Contrails at speed or in hard turns.
		if (flying && (v > kind.maxSpeed() * 0.75 || Math.abs(roll()) > 40)) {
			for (int side = -1; side <= 1; side += 2) {
				Vec3 tip = position().add(0, 1.0, 0).add(right.scale(p[3] * side * s)).add(fwd.scale(p[5] * s))
						.add(up.scale((p[4] - 16.0 / kind.scale()) * s));
				level().addParticle(ParticleTypes.CLOUD, tip.x, tip.y, tip.z, 0, 0, 0);
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			clientEffects();
		}
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
		if (crashCooldown > 0) {
			crashCooldown--;
		}
		if (pilot != null && (suddenStop || slammed)) {
			dev.rubymod.RubyMod.LOGGER.info("{} crashed ({}) at {} km/h", kind.name(), suddenStop ? "hit something" : "hard landing",
					Math.round(Math.max(peak, previousVelocity.length()) * 72));
			pilot.hurtServer(level, crashSource(level), 12.0F);
			hurtServer(level, crashSource(level), 10000.0F);
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
		drone.droneRadius = radius;
		drone.speed = 2.0;
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
		dev.rubymod.game.Range.keepLoaded(level, this);
	}

	private void releaseChunk(ServerLevel level) {
		// Chunk tickets expire on their own.
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("paint", paint());
		output.putInt("cannon_ammo", ammo(Weapon.CANNON));
		output.putInt("missiles", entityData.get(MISSILES));
		output.putInt("bombs", entityData.get(BOMBS));
		output.putInt("flares", flares());
		output.putBoolean("strike", strikeTarget != null);
		if (droneCenter != null) {
			output.putDouble("drone_radius", droneRadius);
			output.putBoolean("drone_respawns", droneRespawns);
			output.putDouble("drone_x", droneCenter.x);
			output.putDouble("drone_y", droneCenter.y);
			output.putDouble("drone_z", droneCenter.z);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		setPaint(input.getIntOr("paint", 0));
		entityData.set(CANNON_AMMO, input.getIntOr("cannon_ammo", kind.cannonAmmo()));
		entityData.set(MISSILES, input.getIntOr("missiles", kind.missiles()));
		entityData.set(BOMBS, input.getIntOr("bombs", kind.bombs() + kind.rockets()));
		entityData.set(FLARES, input.getIntOr("flares", kind.flares()));
		if (input.getBooleanOr("strike", false)) {
			discard(); // An air strike from before a restart: just leave.
		}
		double dx = input.getDoubleOr("drone_x", Double.NaN);
		if (!Double.isNaN(dx)) {
			droneCenter = new Vec3(dx, input.getDoubleOr("drone_y", getY()), input.getDoubleOr("drone_z", getZ()));
			droneRadius = input.getDoubleOr("drone_radius", 40);
			droneRespawns = input.getBooleanOr("drone_respawns", false);
			speed = 2.0;
		}
	}

	/** Refill ammo, missiles, bombs, and flares (training range resupply). */
	/** Training drones come back after being shot down (see TrainingGround). */
	public void respawnAfterDeath() {
		droneRespawns = true;
	}

	/** A target drone (circles on autopilot, no pilot). */
	public boolean isDrone() {
		return droneCenter != null;
	}

	/** Server: how fast it's actually moving (blocks/tick). */
	public double groundSpeed() {
		return serverVelocity.length();
	}

	/** One step of rearming on an Airfield Pad: some of every store back, 10% hull. True when fully done. */
	public boolean rearmStep() {
		entityData.set(CANNON_AMMO, Math.min(kind.cannonAmmo(), ammo(Weapon.CANNON) + Math.max(1, kind.cannonAmmo() / 5)));
		entityData.set(MISSILES, Math.min(kind.missiles(), entityData.get(MISSILES) + 1));
		int store = kind.bombs() + kind.rockets();
		entityData.set(BOMBS, Math.min(store, entityData.get(BOMBS) + Math.max(1, store / 4)));
		entityData.set(FLARES, Math.min(kind.flares(), flares() + 2));
		setHealth(Math.min(getMaxHealth(), getHealth() + getMaxHealth() * 0.1F));
		return ammo(Weapon.CANNON) >= kind.cannonAmmo() && entityData.get(MISSILES) >= kind.missiles() && entityData.get(BOMBS) >= store
				&& flares() >= kind.flares() && getHealth() >= getMaxHealth();
	}

	public void rearm() {
		entityData.set(CANNON_AMMO, kind.cannonAmmo());
		entityData.set(MISSILES, kind.missiles());
		entityData.set(BOMBS, kind.bombs() + kind.rockets());
		entityData.set(FLARES, kind.flares());
		setHealth(getMaxHealth());
	}
}
