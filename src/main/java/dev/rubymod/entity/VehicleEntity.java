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
	/** Can't be damaged until this game time (a fresh spawn in Tank Battle). */
	private long shieldUntil;
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

	/** Point the gun (a gunboat's deck gun turns smoothly toward where its driver looks). */
	protected void aim(float yaw, float pitch) {
		float now = turretYaw();
		entityData.set(TURRET_YAW, Mth.wrapDegrees(now + Mth.clamp(Mth.wrapDegrees(yaw - now), -6.0F, 6.0F)));
		float p = barrelPitch();
		entityData.set(BARREL_PITCH, p + Mth.clamp(pitch - p, -4.0F, 4.0F));
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

	private long rapidUntil;

	/** The main gun reloads three times as fast for a while (a pick-up in Tank Battle). */
	public void rapidFire(int ticks) {
		rapidUntil = level().getGameTime() + ticks;
		cannonCooldown = Math.min(cannonCooldown, RELOAD_TICKS / 3);
	}

	public boolean rapidFiring() {
		return level().getGameTime() < rapidUntil;
	}

	public void refillSmoke() {
		entityData.set(SMOKE, SMOKE_CHARGES);
	}

	/** No damage for a few seconds (spawn protection). */
	public void shield(int ticks) {
		shieldUntil = level().getGameTime() + ticks;
	}

	public boolean isTank() {
		return getType() == ModEntities.TANK;
	}

	/** What it's called on the HUD and in messages. */
	public String vehicleName() {
		return isTank() ? "Tank" : "Jeep";
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
		if (player.getItemInHand(hand).is(dev.rubymod.registry.ModItems.PAINT_KIT)) {
			return InteractionResult.PASS;
		}
		if (player.isSecondaryUseActive()) {
			// Crouch-right-click: pack it back up (your own).
			return PackUp.tryPackUp(this, player, paint()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		if (isVehicle() && getPassengers().size() >= seats()) {
			return InteractionResult.PASS;
		}
		if (flooded()) {
			player.sendOverlayMessage(Component.literal("It's flooded: it won't go in deep water. Crouch + right-click to pack it up.")
					.withStyle(net.minecraft.ChatFormatting.AQUA));
			return InteractionResult.SUCCESS;
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

	// ----- Driving like a car (the Jeep and the Quad) -----

	/** The car's speed along its heading, blocks a tick (negative: reversing). Worked out where it's driven. */
	protected float carSpeed;
	/** The steering wheel, -1 (full right) to 1 (full left), easing towards the keys. */
	protected float carSteer;

	/** Does it drive like a car (gathering speed, coasting, braking, steering only while it rolls)? The Jeep and the Quad. */
	protected boolean drivesLikeACar() {
		return getType() == ModEntities.JEEP;
	}

	/** Degrees a tick with the wheel full over, at its best (a medium speed). */
	protected float carTurn() {
		return 5.0F;
	}

	/** Does it drive like a car? (For the self-test.) */
	public boolean isCar() {
		return drivesLikeACar();
	}

	/** For the self-test: the car's speed now, blocks a tick. */
	public float carSpeed() {
		return carSpeed;
	}

	/** For the self-test: one tick of the pedals (1 accelerator, -1 brake and reverse, 0 neither). */
	public void testPedals(float input, boolean grounded) {
		pedals(input, grounded);
	}

	/** For the self-test: how far it turns in a tick at this speed with the wheel full over. */
	public float testCarTurn(float atSpeed) {
		return carTurnRate(atSpeed, true);
	}

	private float top() {
		return (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
	}

	private void pedals(float input, boolean grounded) {
		float top = top();
		if (input > 0) {
			carSpeed = carSpeed < 0 ? Math.min(0, carSpeed + top * 0.09F) : Math.min(top, carSpeed + top * 0.045F * (1.0F - 0.6F * carSpeed / top));
		} else if (input < 0) {
			carSpeed = carSpeed > 0.01F ? Math.max(0, carSpeed - top * 0.09F) : Math.max(-top * 0.35F, carSpeed - top * 0.03F);
		} else if (grounded) {
			float roll = top * 0.012F;
			carSpeed = carSpeed > 0 ? Math.max(0, carSpeed - roll) : Math.min(0, carSpeed + roll * 2);
		}
	}

	/** Nothing standing still (wheels don't turn a parked car), tightest at a medium speed, wider flat out, none in the air. */
	private float carTurnRate(float atSpeed, boolean grounded) {
		if (!grounded) {
			return 0;
		}
		float f = Math.min(1.0F, Math.abs(atSpeed) / top());
		float best = carTurn();
		return f < 0.3F ? best * (f / 0.3F) : best - best * 0.45F * ((f - 0.3F) / 0.7F);
	}

	@Override
	protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
		if (drivesLikeACar()) {
			pedals(Math.signum(controller.zza), onGround());
			if (horizontalCollision && Math.abs(carSpeed) > 0.08F) {
				carSpeed *= 0.5F; // ran into something
			}
			return new Vec3(0, 0, carSpeed == 0 ? 0 : Math.signum(carSpeed));
		}
		float forward = controller.zza;
		if (forward < 0) {
			forward *= 0.5F;
		}
		return new Vec3(0, 0, forward);
	}

	@Override
	protected void tickRidden(Player controller, Vec3 riddenInput) {
		super.tickRidden(controller, riddenInput);
		if (drivesLikeACar()) {
			carSteer += (Mth.clamp(controller.xxa, -1, 1) - carSteer) * 0.25F;
			if (Math.abs(carSteer) < 0.01F) {
				carSteer = 0;
			}
			// Reversing, it swings the other way (like a real car backing up).
			float yaw = getYRot() - carSteer * carTurnRate(carSpeed, onGround()) * Math.signum(carSpeed);
			setYRot(yaw);
			yBodyRot = yaw;
			yHeadRot = yaw;
			return;
		}
		float turnRate = isTank() ? 3.0F : 5.0F;
		float yaw = getYRot() - controller.xxa * turnRate;
		setYRot(yaw);
		yRotO = yaw;
		yBodyRot = yaw;
		yHeadRot = yaw;
	}

	@Override
	protected float getRiddenSpeed(Player controller) {
		return drivesLikeACar() ? Math.abs(carSpeed) : (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
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

	// ----- Water, falls and running into things -----

	/** Does it float (boats)? Everything else is flooded by deep water. */
	protected boolean floats() {
		return false;
	}

	/** Deep water over its wheels (not riding aboard a Landing Craft). */
	public boolean flooded() {
		return !floats() && !isPassenger() && isInWater() && getFluidHeight(net.minecraft.tags.FluidTags.WATER) > getBbHeight() * 0.55;
	}

	/** Underwater, its riders get out (Minecraft's own rule, as for horses). */
	@Override
	public boolean dismountsUnderwater() {
		return !floats();
	}

	/** Blocks fallen through that it takes without a dent (a bike off a bump, a jeep down a bank). */
	public static final double SAFE_DROP = 8.0;

	/**
	 * A big drop dents it (more the further it fell): small hops and jumps are free. Boats and the tank are left as they
	 * were; the riders aren't hurt by it.
	 */
	@Override
	public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource damageSource) {
		if (floats() || isTank() || fallDistance <= SAFE_DROP || !(level() instanceof ServerLevel level)) {
			return false;
		}
		float dent = (float) ((fallDistance - SAFE_DROP + 2) * getMaxHealth() * 0.04);
		boolean hurt = hurtServer(level, level.damageSources().fall(), dent);
		if (hurt) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 0.8F, 0.6F);
			level.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 0.2, getZ(), 12, getBbWidth() * 0.5, 0.1, getBbWidth() * 0.5, 0.02);
			if (getControllingPassenger() instanceof ServerPlayer driver) {
				driver.sendOverlayMessage(Component.literal("Hard landing! " + vehicleName() + " armor: " + (int) getHealth() + " / " + (int) getMaxHealth()));
			}
		}
		return hurt;
	}

	/** Does it knock down what it runs into? The jeep, the quad and the dirt bike. */
	protected boolean rams() {
		return getType() == ModEntities.JEEP || getType() == ModEntities.QUAD || getType() == ModEntities.DIRT_BIKE;
	}

	/** Who it last ran into, and when (game time), so one bump counts once. */
	private final java.util.Map<java.util.UUID, Long> rammed = new java.util.HashMap<>();

	/** Blocks a tick it has to be doing to hurt what it hits. */
	public static final double RAM_SPEED = 0.18;

	/**
	 * Running into something at speed: it's hurt and knocked away (more the faster), if it's something you're allowed to
	 * hurt (monsters, and enemies once PvP's on, never your squad); anything else (animals, friends) is just nudged
	 * aside. The vehicle takes a small bump too.
	 */
	/** Where it was a tick ago (the driver's game moves it between ticks, so this is how fast it's really going). */
	private Vec3 lastTickPos;

	private void ram(ServerLevel level, ServerPlayer driver) {
		Vec3 was = lastTickPos == null ? position() : lastTickPos;
		lastTickPos = position();
		Vec3 motion = new Vec3(getX() - was.x, 0, getZ() - was.z);
		double speed = motion.length();
		if (speed < RAM_SPEED) {
			return;
		}
		Vec3 dir = motion.scale(1 / speed);
		net.minecraft.world.phys.AABB box = getBoundingBox().inflate(0.3, 0, 0.3).expandTowards(motion.scale(1.5));
		long now = level.getGameTime();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != this && e.isAlive() && !hasPassenger(e) && !e.isPassenger()
				&& !(e instanceof VehicleEntity) && !(e instanceof AircraftEntity) && !(e instanceof HeavyAAGunEntity)
				&& !(e instanceof net.minecraft.world.entity.decoration.ArmorStand) && !(e instanceof Player p && p.isSpectator()))) {
			if (e.position().subtract(position()).dot(dir) < 0 || now - rammed.getOrDefault(e.getUUID(), -100L) < 15) {
				continue;
			}
			rammed.put(e.getUUID(), now);
			hit(level, driver, e, dir, speed);
		}
		if (rammed.size() > 50) {
			rammed.values().removeIf(t -> now - t > 100);
		}
	}

	/** Bump {@code e} at this speed, heading this way. Whether it hurt it. */
	public boolean hit(ServerLevel level, ServerPlayer driver, LivingEntity e, Vec3 dir, double speed) {
		boolean may = e instanceof SoldierEntity s ? s.isHostileTo(driver)
				: e instanceof Player ? dev.rubymod.game.Rules.canHurt(driver, e)
				: e instanceof net.minecraft.world.entity.monster.Enemy;
		Vec3 shove = dir.scale(may ? speed * 2.2 : 0.5).add(0, may ? 0.35 : 0.1, 0);
		e.push(shove.x, shove.y, shove.z);
		e.syncVelocity = true;
		if (!may) {
			return false;
		}
		boolean hurt = e.hurtServer(level, level.damageSources().playerAttack(driver), (float) (speed * 22));
		level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.2F, 0.7F);
		setHealth(Math.max(1, getHealth() - 1));
		return hurt;
	}

	// ----- Combat -----

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (level.getGameTime() < shieldUntil) {
			return false;
		}
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
		if (hurt) {
			PackUp.hit(this);
		}
		if (hurt && getControllingPassenger() instanceof ServerPlayer driver) {
			driver.sendOverlayMessage(Component.literal(vehicleName() + " armor: " + (int) getHealth() + " / " + (int) getMaxHealth()));
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
		cannonCooldown = level().getGameTime() < rapidUntil ? RELOAD_TICKS / 3 : RELOAD_TICKS;
		shieldUntil = 0; // firing ends spawn protection
		entityData.set(RELOAD, cannonCooldown);
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
		if (drivesLikeACar() && getControllingPassenger() == null) {
			carSpeed = 0;
			carSteer = 0;
		}
		if (level() instanceof ServerLevel here) {
			if (flooded()) {
				carSpeed = 0;
				if (isVehicle()) {
					for (Entity rider : java.util.List.copyOf(getPassengers())) {
						if (rider instanceof ServerPlayer p) {
							p.sendOverlayMessage(Component.literal("The " + vehicleName() + " is flooded! Swim for it (crouch + right-click it to pack it up).")
									.withStyle(net.minecraft.ChatFormatting.AQUA));
						}
					}
					ejectPassengers();
				}
			} else if (rams() && getControllingPassenger() instanceof ServerPlayer driver) {
				ram(here, driver);
			} else {
				lastTickPos = null;
			}
		}
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
			if (tickCount % 20 == 0) {
				PackUp.mend(this);
			}
			if (health < 0.25F && tickCount % 2 == 0) {
				level.sendParticles(ParticleTypes.FLAME, getX(), getY() + getBbHeight() * 0.8, getZ(), 2, 0.5, 0.2, 0.5, 0.01);
			}
		}
	}

	/** What you get back if you pack the vehicle up (crouch-right-click: see PackUp). */
	public ItemStack asItem() {
		return new ItemStack(isTank() ? ModItems.TANK : ModItems.JEEP);
	}
}
