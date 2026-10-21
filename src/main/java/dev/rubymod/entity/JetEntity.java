package dev.rubymod.entity;

import dev.rubymod.game.Explosions;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A fighter jet. Get in with right-click. W/S raise and lower the throttle,
 * the jet steers toward where you look, left-click fires missiles from the
 * wings, and Space drops a bomb. You need speed to take off, and if you slow
 * down in the air you stall. Bail out with Sneak (you get a parachute).
 *
 * The same entity flies the air strike flyover on autopilot (no pilot).
 */
public class JetEntity extends Mob {
	public static final double MAX_SPEED = 1.9;
	public static final double TAKEOFF_SPEED = 0.75;
	private static final int MAX_MISSILES = 8;
	private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> MISSILES = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.INT);

	private double speed;
	private float throttle;
	private int missileCooldown;
	private int bombCooldown;
	private boolean leftWing;

	// Autopilot (air strike flyover).
	private Vec3 autopilotTarget;
	private int bombsLeft;
	private Entity strikeOwner;

	public JetEntity(EntityType<? extends JetEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder attributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 120.0)
				.add(Attributes.ARMOR, 8.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.STEP_HEIGHT, 1.1);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ROLL, 0.0F);
		builder.define(MISSILES, MAX_MISSILES);
	}

	public float roll() {
		return entityData.get(ROLL);
	}

	public int missiles() {
		return entityData.get(MISSILES);
	}

	public double speed() {
		return speed;
	}

	public float throttle() {
		return throttle;
	}

	// ----- Riding -----

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (player.isSecondaryUseActive() || isVehicle()) {
			return InteractionResult.PASS;
		}
		if (!level().isClientSide()) {
			player.startRiding(this);
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
		return new Vec3(0, 0.25, 0.9).yRot((float) Math.toRadians(-getYRot()));
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		// Bailing out in mid-air: deploy a parachute.
		if (!level().isClientSide() && passenger instanceof LivingEntity living && !onGround()) {
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

	// ----- Flight -----

	@Override
	public void travel(Vec3 input) {
		LivingEntity pilot = getControllingPassenger();
		if (pilot != null) {
			fly(pilot, input);
		} else if (autopilotTarget != null) {
			autopilot();
		} else {
			coast();
		}
	}

	private void fly(LivingEntity pilot, Vec3 input) {
		if (input.z > 0) {
			throttle = Math.min(1.0F, throttle + 0.02F);
		} else if (input.z < 0) {
			throttle = Math.max(0.0F, throttle - 0.03F);
		}
		double target = throttle * MAX_SPEED;
		speed += Mth.clamp(target - speed, -0.025, 0.02);

		// Steer toward where the pilot is looking; faster jets turn more slowly.
		float turnRate = (float) (6.0 - 2.5 * (speed / MAX_SPEED));
		float yawDelta = Mth.clamp(Mth.wrapDegrees(pilot.getYRot() - getYRot()), -turnRate, turnRate);
		float desiredPitch = pilot.getXRot();
		boolean airborne = !onGround();
		if (!airborne && (speed < TAKEOFF_SPEED || desiredPitch > 0)) {
			desiredPitch = 0; // Can't climb without takeoff speed, can't dive into the runway.
		}
		float pitchDelta = Mth.clamp(desiredPitch - getXRot(), -3.0F, 3.0F);
		setYRot(getYRot() + yawDelta);
		setXRot(Mth.clamp(getXRot() + pitchDelta, -60.0F, 70.0F));
		yBodyRot = getYRot();
		yHeadRot = getYRot();
		setRoll(Mth.lerp(0.2F, roll(), Mth.clamp(yawDelta * 9.0F, -50.0F, 50.0F)));

		Vec3 velocity;
		if (airborne && speed < TAKEOFF_SPEED * 0.8) {
			// Stall: the nose drops and the jet falls.
			setXRot(Math.min(70.0F, getXRot() + 1.5F));
			velocity = Vec3.directionFromRotation(getXRot(), getYRot()).scale(speed).add(0, -0.35, 0);
		} else if (!airborne && speed < TAKEOFF_SPEED) {
			// Taxiing on the ground.
			velocity = Vec3.directionFromRotation(0, getYRot()).scale(speed).add(0, -0.08, 0);
		} else {
			velocity = Vec3.directionFromRotation(getXRot(), getYRot()).scale(speed);
		}
		setDeltaMovement(velocity);
		move(MoverType.SELF, velocity);
		if (horizontalCollision && speed > 0.6) {
			speed *= 0.2;
		}
	}

	/** No pilot: glide down and roll to a stop. */
	private void coast() {
		speed *= onGround() ? 0.8 : 0.98;
		Vec3 velocity = Vec3.directionFromRotation(0, getYRot()).scale(speed).add(0, onGround() ? -0.08 : -0.4, 0);
		setDeltaMovement(velocity);
		move(MoverType.SELF, velocity);
		setRoll(roll() * 0.9F);
	}

	// ----- Combat -----

	/** Left-click: fire a missile from alternating wings. */
	public void fireMissile(ServerPlayer pilot) {
		if (pilot != getControllingPassenger() || missileCooldown > 0) {
			return;
		}
		if (missiles() <= 0) {
			pilot.sendOverlayMessage(Component.literal("Out of missiles. They reload over time."));
			return;
		}
		missileCooldown = 12;
		entityData.set(MISSILES, missiles() - 1);
		Vec3 forward = Vec3.directionFromRotation(getXRot(), getYRot());
		Vec3 side = new Vec3(leftWing ? 1.6 : -1.6, -0.3, 0).yRot((float) Math.toRadians(-getYRot()));
		leftWing = !leftWing;
		RocketEntity missile = RocketEntity.fire(level(), pilot);
		missile.setPos(position().add(side).add(forward.scale(2.0)).add(0, 0.4, 0));
		missile.setDeltaMovement(forward.scale(Math.max(2.6, speed + 1.2)));
		level().addFreshEntity(missile);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 3.0F, 0.7F);
		pilot.sendOverlayMessage(Component.literal("Missiles: " + missiles() + " / " + MAX_MISSILES));
	}

	/** Space: drop a bomb straight down. */
	public void dropBomb(ServerPlayer pilot) {
		if (pilot != getControllingPassenger() || bombCooldown > 0 || onGround()) {
			return;
		}
		bombCooldown = 30;
		BombEntity bomb = BombEntity.drop(level(), pilot, position().add(0, -1.0, 0));
		bomb.setDeltaMovement(getDeltaMovement().multiply(0.8, 0, 0.8).add(0, -0.4, 0));
		level().addFreshEntity(bomb);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.DISPENSER_LAUNCH, SoundSource.PLAYERS, 2.0F, 0.6F);
	}

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
			discard();
		}
	}

	@Override
	public boolean removeWhenFarAway(double distanceSquared) {
		return autopilotTarget != null;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (missileCooldown > 0) {
			missileCooldown--;
		}
		if (bombCooldown > 0) {
			bombCooldown--;
		}
		if (level() instanceof ServerLevel level) {
			if (tickCount % 60 == 0 && missiles() < MAX_MISSILES) {
				entityData.set(MISSILES, missiles() + 1);
			}
			double moving = getDeltaMovement().length();
			if (moving > 0.3) {
				if (tickCount % 2 == 0) {
					Vec3 back = position().add(Vec3.directionFromRotation(getXRot(), getYRot()).scale(-2.6)).add(0, 0.6, 0);
					level.sendParticles(ParticleTypes.SMOKE, back.x, back.y, back.z, 1, 0.05, 0.05, 0.05, 0.0);
				}
				if (tickCount % 16 == 0) {
					level.playSound(null, getX(), getY(), getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.NEUTRAL, 1.5F, 1.5F);
				}
			}
			// Crashing into the ground or a wall at speed destroys the jet.
			if ((horizontalCollision || verticalCollisionBelow) && moving > 1.1 && getControllingPassenger() != null) {
				LivingEntity pilot = getControllingPassenger();
				hurtServer(level, level.damageSources().flyIntoWall(), 1000.0F);
				if (pilot != null) {
					pilot.hurtServer(level, level.damageSources().flyIntoWall(), 10.0F);
				}
			}
			if (getHealth() < getMaxHealth() * 0.3F && tickCount % 3 == 0) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.8, getZ(), 1, 0.3, 0.1, 0.3, 0.01);
			}
		}
	}

	private void setRoll(float roll) {
		entityData.set(ROLL, roll);
	}

	// ----- Air strike flyover -----

	/** Spawn a jet that flies over {@code target} along {@code yaw}, carpet-bombing the line. */
	public static JetEntity airStrike(ServerLevel level, Entity owner, Vec3 target, float yaw) {
		JetEntity jet = new JetEntity(ModEntities.JET, level);
		Vec3 direction = Vec3.directionFromRotation(0, yaw);
		Vec3 start = target.add(direction.scale(-90)).add(0, 35, 0);
		jet.snapTo(start.x, start.y, start.z, yaw, 0);
		jet.autopilotTarget = target;
		jet.bombsLeft = 10;
		jet.strikeOwner = owner;
		jet.speed = 1.6;
		jet.setPermanentlyInvulnerable(true);
		return jet;
	}

	private void autopilot() {
		Vec3 velocity = Vec3.directionFromRotation(0, getYRot()).scale(speed);
		setDeltaMovement(velocity);
		setPos(position().add(velocity));
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		double dx = autopilotTarget.x - getX();
		double dz = autopilotTarget.z - getZ();
		double along = dx * velocity.x + dz * velocity.z; // > 0 while approaching
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (bombsLeft > 0 && distance < 14 && tickCount % 2 == 0) {
			bombsLeft--;
			BombEntity bomb = BombEntity.drop(level, strikeOwner != null ? strikeOwner : this, position().add(0, -1.5, 0));
			bomb.setDeltaMovement(velocity.scale(0.5).add(0, -0.6, 0));
			level.addFreshEntity(bomb);
		}
		if (tickCount % 10 == 0) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.HOSTILE, 6.0F, 1.2F);
		}
		if (along < 0 && distance > 120 || tickCount > 400) {
			discard();
		}
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("missiles", missiles());
		output.putBoolean("autopilot", autopilotTarget != null);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		entityData.set(MISSILES, input.getIntOr("missiles", MAX_MISSILES));
		if (input.getBooleanOr("autopilot", false)) {
			discard(); // A flyover from before a restart: just leave.
		}
	}

	public ItemStack asItem() {
		return new ItemStack(ModItems.JET);
	}
}
