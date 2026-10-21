package dev.rubymod.entity;

import dev.rubymod.game.Countermeasures;
import dev.rubymod.game.Explosions;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A drivable vehicle: the Jeep (fast, 4 seats) and the Tank (slow, armored,
 * 2 seats, main cannon). W/S drive, A/D steer. The Tank's driver fires the
 * cannon by right-clicking with an empty hand (see GameEvents).
 *
 * Bullets barely scratch vehicles; explosions, rockets, and landmines are what
 * kill them.
 */
public class VehicleEntity extends Mob {
	private static final EntityDataAccessor<Float> TURRET_YAW = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> BARREL_PITCH = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
	/** Turret traverse speed, degrees per tick. */
	private static final float TRAVERSE = 4.0F;
	private int cannonCooldown;
	/** Ticks the main gun takes to reload. */
	public static final int RELOAD_TICKS = 60;
	private static final EntityDataAccessor<Integer> RELOAD = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SMOKE = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
	public static final int SMOKE_CHARGES = 3;
	private int smokeCooldown;

	public VehicleEntity(EntityType<? extends VehicleEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
	}

	/** Does the driver have a weapon to fire with the attack button? */
	public boolean hasWeapon() {
		return isTank();
	}

	/** Paint scheme (see PaintKitItem.SCHEMES): 0 = factory default. */
	private static final EntityDataAccessor<Integer> PAINT = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);

	public int paint() {
		return entityData.get(PAINT);
	}

	public void setPaint(int paint) {
		entityData.set(PAINT, paint);
	}

	@Override
	protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("paint", paint());
	}

	@Override
	protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
		super.readAdditionalSaveData(input);
		setPaint(input.getIntOr("paint", 0));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(PAINT, 0);
		builder.define(TURRET_YAW, 0.0F);
		builder.define(BARREL_PITCH, 0.0F);
		builder.define(RELOAD, 0);
		builder.define(SMOKE, SMOKE_CHARGES);
	}

	/** Where the turret points (world yaw, degrees). */
	public float turretYaw() {
		return entityData.get(TURRET_YAW);
	}

	/** Ticks until the main gun can fire again (synced for the tank HUD). */
	public int reloadLeft() {
		return entityData.get(RELOAD);
	}

	public int smokeCharges() {
		return entityData.get(SMOKE);
	}

	/** Space in the tank: smoke launchers. Breaks missile locks for 8 seconds. */
	public void popSmoke(ServerPlayer driver) {
		if (!isTank() || getControllingPassenger() != driver || smokeCooldown > 0 || smokeCharges() <= 0) {
			return;
		}
		smokeCooldown = 100;
		entityData.set(SMOKE, smokeCharges() - 1);
		ServerLevel level = (ServerLevel) level();
		Countermeasures.smokeScreen(level, position(), 6.0, 160);
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, false, getX() + Math.cos(a) * 3, getY() + 1.5, getZ() + Math.sin(a) * 3,
					8, 0.8, 0.8, 0.8, 0.02);
		}
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.0F, 1.6F);
	}

	/** Barrel elevation (negative = up, like player pitch). */
	public float barrelPitch() {
		return entityData.get(BARREL_PITCH);
	}

	public boolean isTank() {
		return getType() == ModEntities.TANK;
	}

	public static AttributeSupplier.Builder jeepAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 60.0)
				.add(Attributes.MOVEMENT_SPEED, 0.32)
				.add(Attributes.STEP_HEIGHT, 1.1)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
	}

	public static AttributeSupplier.Builder tankAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 160.0)
				.add(Attributes.MOVEMENT_SPEED, 0.16)
				.add(Attributes.STEP_HEIGHT, 1.1)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
	}

	private int seats() {
		return isTank() ? 2 : 4;
	}

	// ----- Riding -----

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (player.isSecondaryUseActive() || isVehicle() && getPassengers().size() >= seats() || player.getItemInHand(hand).is(dev.rubymod.registry.ModItems.PAINT_KIT)) {
			return InteractionResult.PASS;
		}
		if (!level().isClientSide()) {
			player.startRiding(this);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().size() < seats();
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player driver ? driver : null;
	}

	@Override
	protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
		float forward = controller.zza;
		if (forward < 0) {
			forward *= 0.5F;
		}
		return new Vec3(0, 0, forward);
	}

	@Override
	protected void tickRidden(Player controller, Vec3 riddenInput) {
		super.tickRidden(controller, riddenInput);
		float turnRate = isTank() ? 3.0F : 5.0F;
		float yaw = getYRot() - controller.xxa * turnRate;
		setYRot(yaw);
		yRotO = yaw;
		yBodyRot = yaw;
		yHeadRot = yaw;
	}

	@Override
	protected float getRiddenSpeed(Player controller) {
		return (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		int index = Math.max(0, getPassengers().indexOf(passenger));
		double height = dimensions.height() * (isTank() ? 0.75 : 0.45);
		if (isTank()) {
			// Driver/commander stands in the cupola (head and shoulders above the turret roof); a second rider on the engine deck.
			return new Vec3(index == 0 ? 0.45 : 0, height + (index == 0 ? 0.45 : 0.1), index == 0 ? -0.1 : -1.2).yRot((float) Math.toRadians(-getYRot()));
		}
		// Jeep seats (the model's seats are at z 1 px (front) and -15 px (back), 0.5 block either side).
		double side = index % 2 == 0 ? 0.5 : -0.5;
		double front = index < 2 ? 0.0 : -0.95;
		return new Vec3(side, 0.28, front).yRot((float) Math.toRadians(-getYRot()));
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
	public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource damageSource) {
		return false;
	}

	// ----- Combat -----

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(ModDamage.ANTI_ARMOR)) {
			// Full damage: rockets, shells, missiles, mines (see Armor).
		} else if (source.is(ModDamage.BULLET) || source.is(ModDamage.HEADSHOT) || source.is(ModDamage.TURRET)) {
			amount *= isTank() ? 0.0F : 0.25F;
		} else if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			amount *= isTank() ? 0.25F : 0.5F; // Splash; warheads hit through Armor instead.
		}
		if (amount <= 0) {
			return false;
		}
		if (source.getEntity() != null && hasPassenger(source.getEntity())) {
			return false; // Don't shoot your own ride.
		}
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt && getControllingPassenger() instanceof ServerPlayer driver) {
			driver.sendOverlayMessage(Component.literal((isTank() ? "Tank" : "Jeep") + " armor: " + (int) getHealth() + " / " + (int) getMaxHealth()));
		}
		return hurt;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel level) {
			ejectPassengers();
			Explosions.explode(level, this, getX(), getY() + 1, getZ(), isTank() ? 4.0F : 3.0F, false);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1, getZ(), 30, 1.0, 1.0, 1.0, 0.05);
			level.addFreshEntity(WreckEntity.of(level, this));
			discard();
		}
	}

	/** The Tank's driver fires the main cannon: a heavy, unguided shell. */
	public void fireCannon(ServerPlayer driver) {
		if (!isTank() || cannonCooldown > 0 || getControllingPassenger() != driver) {
			return;
		}
		cannonCooldown = RELOAD_TICKS;
		entityData.set(RELOAD, RELOAD_TICKS);
		Vec3 aim = Vec3.directionFromRotation(barrelPitch(), turretYaw());
		Vec3 muzzle = muzzlePosition();
		RocketEntity shell = RocketEntity.tankShell(level(), driver, muzzle, aim.scale(2.6), 4.0F);
		level().addFreshEntity(shell);
		ServerLevel level = (ServerLevel) level();
		level.playSound(null, muzzle.x, muzzle.y, muzzle.z, dev.rubymod.registry.ModSounds.TANK_CANNON, SoundSource.PLAYERS, 3.0F, 1.0F);
		level.sendParticles(ParticleTypes.EXPLOSION, muzzle.x, muzzle.y, muzzle.z, 2, 0.1, 0.1, 0.1, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, muzzle.x, muzzle.y, muzzle.z, 12, 0.3, 0.3, 0.3, 0.05);
		Explosions.shake(level, muzzle, 2.0F, 12);
		// Recoil: the tank rocks back a little.
		setDeltaMovement(getDeltaMovement().add(aim.multiply(-0.25, 0, -0.25)));
	}

	/** World position of the barrel tip (model: turret on top of the hull, barrel pivot ahead of center). */
	public Vec3 muzzlePosition() {
		double scale = 3.0 / 16.0;
		Vec3 pivot = position().add(0, (7.0 + 1.6) * scale, 0).add(Vec3.directionFromRotation(0, turretYaw()).scale(4.5 * scale));
		return pivot.add(Vec3.directionFromRotation(barrelPitch(), turretYaw()).scale(15.5 * scale));
	}

	private void aimTurret() {
		LivingEntity driver = getControllingPassenger();
		if (driver == null) {
			return;
		}
		float yaw = turretYaw();
		float yawStep = Mth.clamp(Mth.wrapDegrees(driver.getYHeadRot() - yaw), -TRAVERSE, TRAVERSE);
		entityData.set(TURRET_YAW, Mth.wrapDegrees(yaw + yawStep));
		float pitch = barrelPitch();
		float targetPitch = Mth.clamp(driver.getXRot(), -20.0F, 8.0F);
		entityData.set(BARREL_PITCH, pitch + Mth.clamp(targetPitch - pitch, -3.0F, 3.0F));
	}

	@Override
	public void tick() {
		super.tick();
		if (smokeCooldown > 0) {
			smokeCooldown--;
		}
		if (cannonCooldown > 0) {
			cannonCooldown--;
			if (!level().isClientSide()) {
				entityData.set(RELOAD, cannonCooldown);
			}
		}
		if (isTank() && !level().isClientSide()) {
			if (tickCount == 1 && turretYaw() == 0.0F) {
				entityData.set(TURRET_YAW, getYRot());
			}
			aimTurret();
		}
		if (level() instanceof ServerLevel level) {
			// Damage you can see: smoke below half armor, fire below a quarter.
			float health = getHealth() / getMaxHealth();
			if (health < 0.5F && tickCount % 3 == 0) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + getBbHeight(), getZ(), 1, 0.3, 0.1, 0.3, 0.02);
			}
			if (health < 0.25F && tickCount % 2 == 0) {
				level.sendParticles(ParticleTypes.FLAME, getX(), getY() + getBbHeight() * 0.8, getZ(), 2, 0.5, 0.2, 0.5, 0.01);
			}
		}
	}

	/** What you get back if you pick the vehicle up (sneak + attack is not supported; vehicles are permanent until destroyed). */
	public ItemStack asItem() {
		return new ItemStack(isTank() ? ModItems.TANK : ModItems.JEEP);
	}
}
