package dev.rubymod.entity;

import dev.rubymod.game.Explosions;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
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
	private int cannonCooldown;

	public VehicleEntity(EntityType<? extends VehicleEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
	}

	public boolean isTank() {
		return getType() == ModEntities.TANK;
	}

	public static AttributeSupplier.Builder jeepAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 80.0)
				.add(Attributes.MOVEMENT_SPEED, 0.32)
				.add(Attributes.STEP_HEIGHT, 1.1)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.ARMOR, 6.0);
	}

	public static AttributeSupplier.Builder tankAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 300.0)
				.add(Attributes.MOVEMENT_SPEED, 0.16)
				.add(Attributes.STEP_HEIGHT, 1.1)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.ARMOR, 20.0);
	}

	private int seats() {
		return isTank() ? 2 : 4;
	}

	// ----- Riding -----

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (player.isSecondaryUseActive() || isVehicle() && getPassengers().size() >= seats()) {
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
			// Driver sits in the hull, gunner on the turret.
			return new Vec3(0, height + (index == 0 ? 0 : 0.4), index == 0 ? 0.6 : -0.4).yRot((float) Math.toRadians(-getYRot()));
		}
		double side = index % 2 == 0 ? 0.45 : -0.45;
		double front = index < 2 ? 0.3 : -0.7;
		return new Vec3(side, height, front).yRot((float) Math.toRadians(-getYRot()));
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
		if (source.is(ModDamage.BULLET) || source.is(ModDamage.HEADSHOT) || source.is(ModDamage.TURRET)) {
			amount *= isTank() ? 0.05F : 0.25F;
		} else if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			amount *= 2.0F;
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
			discard();
		}
	}

	/** The Tank's driver fires the main cannon: a heavy, unguided shell. */
	public void fireCannon(ServerPlayer driver) {
		if (!isTank() || cannonCooldown > 0 || getControllingPassenger() != driver) {
			return;
		}
		cannonCooldown = 60;
		Vec3 look = driver.getLookAngle();
		RocketEntity shell = RocketEntity.fire(level(), driver);
		shell.setPos(getX() + look.x * 2.5, getY() + 1.9 + look.y * 2.5, getZ() + look.z * 2.5);
		shell.setDeltaMovement(look.scale(2.2));
		level().addFreshEntity(shell);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3.0F, 0.6F);
		((ServerLevel) level()).sendParticles(ParticleTypes.EXPLOSION, getX() + look.x * 2.5, getY() + 1.9, getZ() + look.z * 2.5, 1, 0, 0, 0, 0);
	}

	@Override
	public void tick() {
		super.tick();
		if (cannonCooldown > 0) {
			cannonCooldown--;
		}
		if (level() instanceof ServerLevel level && getHealth() < getMaxHealth() * 0.3F && tickCount % 4 == 0) {
			level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + getBbHeight(), getZ(), 1, 0.3, 0.1, 0.3, 0.01);
		}
	}

	/** What you get back if you pick the vehicle up (sneak + attack is not supported; vehicles are permanent until destroyed). */
	public ItemStack asItem() {
		return new ItemStack(isTank() ? ModItems.TANK : ModItems.JEEP);
	}
}
