package dev.rubymod.entity;

import java.util.List;

import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
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
import net.minecraft.world.phys.Vec3;

/**
 * War boats for the rivers and the coast. They float, and steer like the jeep (W/S throttle, A/D turn); on land they
 * only scrape along. The Patrol Boat seats four; the Gunboat has a deck gun its driver aims and fires (left-click); the
 * Landing Craft seats six and carries a vehicle: Space lowers or raises its bow ramp, a jeep (or quad, or bike) drives
 * aboard over the lowered ramp, and lowering the ramp against a beach lets it drive off.
 */
public class BoatEntity extends VehicleEntity {
	private static final EntityDataAccessor<Boolean> RAMP = SynchedEntityData.defineId(BoatEntity.class, EntityDataSerializers.BOOLEAN);
	/** How deep the hull sits in the water. */
	private static final double DRAFT = 0.35;
	private int gunCooldown;
	private boolean jumpWas;

	public BoatEntity(EntityType<? extends BoatEntity> type, Level level) {
		super(type, level);
	}

	public enum Kind {
		PATROL, GUNBOAT, LANDING
	}

	public Kind kind() {
		return getType() == ModEntities.GUNBOAT ? Kind.GUNBOAT : getType() == ModEntities.LANDING_CRAFT ? Kind.LANDING : Kind.PATROL;
	}

	public static AttributeSupplier.Builder patrolAttributes() {
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 60.0).add(Attributes.MOVEMENT_SPEED, 0.34).add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
	}

	public static AttributeSupplier.Builder gunboatAttributes() {
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 110.0).add(Attributes.MOVEMENT_SPEED, 0.28).add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
	}

	public static AttributeSupplier.Builder landingAttributes() {
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 140.0).add(Attributes.MOVEMENT_SPEED, 0.22).add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(RAMP, false);
	}

	public boolean rampDown() {
		return entityData.get(RAMP);
	}

	@Override
	public String vehicleName() {
		return switch (kind()) {
			case GUNBOAT -> "Gunboat";
			case LANDING -> "Landing Craft";
			default -> "Patrol Boat";
		};
	}

	@Override
	public ItemStack asItem() {
		return new ItemStack(switch (kind()) {
			case GUNBOAT -> ModItems.GUNBOAT;
			case LANDING -> ModItems.LANDING_CRAFT;
			default -> ModItems.PATROL_BOAT;
		});
	}

	@Override
	public boolean hasWeapon() {
		return kind() == Kind.GUNBOAT;
	}

	private int seats() {
		return switch (kind()) {
			case GUNBOAT -> 3;
			case LANDING -> 6;
			default -> 4;
		};
	}

	private int people() {
		int n = 0;
		for (Entity e : getPassengers()) {
			if (e instanceof Player || e instanceof SoldierEntity) {
				n++;
			}
		}
		return n;
	}

	/** The vehicle on board (the landing craft's cargo), or null. */
	public VehicleEntity cargo() {
		for (Entity e : getPassengers()) {
			if (e instanceof VehicleEntity v) {
				return v;
			}
		}
		return null;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		if (passenger instanceof VehicleEntity) {
			return kind() == Kind.LANDING && cargo() == null;
		}
		return people() < seats();
	}

	@Override
	protected net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
		if (player.getItemInHand(hand).is(dev.rubymod.registry.ModItems.PAINT_KIT)) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		if (player.isSecondaryUseActive()) {
			return PackUp.tryPackUp(this, player, paint()) ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
		}
		if (!canAddPassenger(player)) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		if (!level().isClientSide()) {
			player.startRiding(this);
		}
		return net.minecraft.world.InteractionResult.SUCCESS;
	}

	@Override
	public LivingEntity getControllingPassenger() {
		for (Entity e : getPassengers()) {
			if (e instanceof Player p) {
				return p;
			}
		}
		return null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		float yaw = (float) Math.toRadians(-getYRot());
		if (passenger instanceof VehicleEntity) {
			return new Vec3(0, 0.55, 0.4).yRot(yaw);
		}
		int seat = 0;
		for (Entity e : getPassengers()) {
			if (e == passenger) {
				break;
			}
			if (e instanceof Player || e instanceof SoldierEntity) {
				seat++;
			}
		}
		double side = seat % 2 == 0 ? -0.55 : 0.55;
		double back = switch (kind()) {
			// Three rows of two at the back (the deck in front is for the vehicle).
			case LANDING -> -1.1 - (seat / 2) * 0.75;
			case GUNBOAT -> seat == 0 ? -1.2 : -0.2;
			default -> seat < 2 ? 0.3 : -0.8;
		};
		return new Vec3(side, 0.55, back).yRot(yaw);
	}

	// ----- Afloat -----

	/** The top of the water here (or NaN if it's not water). */
	private double surface() {
		BlockPos p = BlockPos.containing(getX(), getY() + 0.6, getZ());
		if (!level().getFluidState(p).is(FluidTags.WATER)) {
			p = p.below();
			if (!level().getFluidState(p).is(FluidTags.WATER)) {
				return Double.NaN;
			}
		}
		while (level().getFluidState(p.above()).is(FluidTags.WATER)) {
			p = p.above();
		}
		return p.getY() + level().getFluidState(p).getHeight(level(), p);
	}

	public boolean afloat() {
		return !Double.isNaN(surface());
	}

	@Override
	public void travel(Vec3 input) {
		double top = surface();
		if (Double.isNaN(top)) {
			// Beached: it scrapes along slowly (enough to nose up a beach or back off it).
			super.travel(input.scale(0.15));
			return;
		}
		float speed = getSpeed();
		double yaw = Math.toRadians(getYRot());
		Vec3 ahead = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 v = getDeltaMovement();
		// Bob up to the waterline; push along where it's pointing; the water drags the rest away.
		double vy = Mth.clamp((top - DRAFT - getY()) * 0.2, -0.3, 0.3);
		Vec3 along = ahead.scale(v.dot(ahead) * 0.96 + input.z * speed * 0.12);
		Vec3 sideways = v.subtract(ahead.scale(v.dot(ahead))).multiply(0.7, 0, 0.7);
		Vec3 next = new Vec3(along.x + sideways.x, vy, along.z + sideways.z);
		setDeltaMovement(next);
		move(MoverType.SELF, next);
		resetFallDistance();
	}

	/**
	 * Steering: a boat turns by its rudder, so it only really turns while it's moving through the water (tightest at a
	 * good speed, a slow drift round when it's nearly stopped), the other way round when going astern, and hardly at all
	 * beached.
	 */
	@Override
	protected void tickRidden(Player controller, Vec3 riddenInput) {
		float rate = turnRate(getDeltaMovement(), afloat());
		float yaw = getYRot() - Mth.clamp(controller.xxa, -1, 1) * rate;
		setYRot(yaw);
		yBodyRot = yaw;
		yHeadRot = yaw;
	}

	/** Degrees a tick with the helm hard over, moving like this. (Negative going astern: it swings the other way.) */
	public float turnRate(Vec3 motion, boolean water) {
		double yaw = Math.toRadians(getYRot());
		double ahead = motion.x * -Math.sin(yaw) + motion.z * Math.cos(yaw);
		if (!water) {
			return 0.6F;
		}
		double cruise = getAttributeValue(Attributes.MOVEMENT_SPEED) * 3.0;
		float f = (float) Math.min(1.0, Math.abs(ahead) / (cruise * 0.35));
		return (0.6F + 4.4F * f) * (ahead < -0.02 ? -1 : 1);
	}

	@Override
	protected boolean floats() {
		return true;
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	@Override
	public boolean canBeAffected(net.minecraft.world.effect.MobEffectInstance effect) {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (gunCooldown > 0) {
			gunCooldown--;
		}
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		// A wake, under way.
		if (afloat() && getDeltaMovement().horizontalDistanceSqr() > 0.01 && tickCount % 2 == 0) {
			double yaw = Math.toRadians(getYRot());
			level.sendParticles(ParticleTypes.SPLASH, getX() + Math.sin(yaw) * getBbWidth(), getY() + DRAFT, getZ() - Math.cos(yaw) * getBbWidth(), 4, 0.4, 0.05, 0.4, 0.05);
			level.sendParticles(ParticleTypes.BUBBLE, getX(), getY(), getZ(), 2, 0.6, 0.1, 0.6, 0.02);
		}
		if (kind() == Kind.GUNBOAT && getControllingPassenger() instanceof ServerPlayer driver) {
			aim(driver.getYHeadRot(), Mth.clamp(driver.getXRot(), -25.0F, 10.0F));
		}
		if (kind() == Kind.LANDING) {
			tickLanding(level);
		}
	}

	// ----- The Gunboat's deck gun -----

	@Override
	public void fireCannon(ServerPlayer driver) {
		if (kind() != Kind.GUNBOAT || gunCooldown > 0 || getControllingPassenger() != driver) {
			return;
		}
		gunCooldown = 30;
		Vec3 aim = Vec3.directionFromRotation(barrelPitch(), turretYaw());
		Vec3 muzzle = muzzle();
		RocketEntity shell = RocketEntity.tankShell(level(), driver, muzzle, aim.scale(2.4), 3.0F);
		level().addFreshEntity(shell);
		ServerLevel level = (ServerLevel) level();
		level.playSound(null, muzzle.x, muzzle.y, muzzle.z, dev.rubymod.registry.ModSounds.TANK_CANNON, SoundSource.PLAYERS, 2.5F, 1.2F);
		level.sendParticles(ParticleTypes.EXPLOSION, muzzle.x, muzzle.y, muzzle.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, muzzle.x, muzzle.y, muzzle.z, 8, 0.2, 0.2, 0.2, 0.04);
		LightEntity.flash(level, muzzle);
	}

	/** Where the deck gun's barrel ends (on the bow, about a block and a half up). */
	public Vec3 muzzle() {
		double yaw = Math.toRadians(getYRot());
		Vec3 mount = position().add(-Math.sin(yaw) * 1.4, 1.5, Math.cos(yaw) * 1.4);
		return mount.add(Vec3.directionFromRotation(barrelPitch(), turretYaw()).scale(1.6));
	}

	// ----- The Landing Craft's ramp and cargo -----

	private void tickLanding(ServerLevel level) {
		if (getControllingPassenger() instanceof ServerPlayer driver) {
			boolean jump = driver.getLastClientInput().jump();
			if (jump && !jumpWas) {
				ramp(level, !rampDown(), driver);
			}
			jumpWas = jump;
		}
		if (!rampDown()) {
			return;
		}
		// A vehicle driving up the lowered ramp comes aboard (if there's room).
		if (cargo() == null && tickCount % 5 == 0) {
			Vec3 bow = bow();
			for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, getBoundingBox().inflate(1.5),
					v -> v != this && !(v instanceof BoatEntity) && !v.isTank() && !(v instanceof TunnelBorerEntity) && !v.isPassenger())) {
				if (v.position().distanceTo(bow) < 3.0 || v.getBoundingBox().intersects(getBoundingBox())) {
					v.startRiding(this, true, false);
					level.playSound(null, getX(), getY(), getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 1.0F, 0.7F);
					break;
				}
			}
		}
	}

	/** Just past the bow (where the ramp comes down). */
	private Vec3 bow() {
		double yaw = Math.toRadians(getYRot());
		return position().add(-Math.sin(yaw) * (getBbWidth() / 2 + 1.5), 0, Math.cos(yaw) * (getBbWidth() / 2 + 1.5));
	}

	/** Lower (or raise) the ramp; lowered against land with a vehicle aboard, the vehicle rolls off onto the beach. */
	public void ramp(ServerLevel level, boolean down, ServerPlayer who) {
		entityData.set(RAMP, down);
		level.playSound(null, getX(), getY(), getZ(), down ? SoundEvents.PISTON_EXTEND : SoundEvents.PISTON_CONTRACT, SoundSource.PLAYERS, 1.2F, 0.6F);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 0.6F);
		if (!down) {
			return;
		}
		VehicleEntity cargo = cargo();
		if (cargo == null) {
			if (who != null) {
				who.sendOverlayMessage(Component.literal("Ramp down: drive a jeep, quad or bike aboard.").withStyle(ChatFormatting.AQUA));
			}
			return;
		}
		Vec3 out = bow();
		BlockPos land = BlockPos.containing(out.x, getY() + 1, out.z);
		// Find the beach in front: firm ground within a couple of blocks up or down.
		for (int dy = 2; dy >= -2; dy--) {
			BlockPos p = land.above(dy);
			if (!level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty() && level.getBlockState(p).isAir()
					&& level.getBlockState(p.above()).isAir()) {
				cargo.stopRiding();
				cargo.teleportTo(out.x, p.getY(), out.z);
				cargo.setYRot(getYRot());
				level.sendParticles(ParticleTypes.SPLASH, out.x, p.getY(), out.z, 20, 0.8, 0.2, 0.8, 0.1);
				if (who != null) {
					who.sendOverlayMessage(Component.literal("Ramp down: the " + cargo.vehicleName() + " is off and on the beach!").withStyle(ChatFormatting.GREEN));
				}
				return;
			}
		}
		if (who != null) {
			who.sendOverlayMessage(Component.literal("Ramp down, but there's no beach in front for the " + cargo.vehicleName() + " to drive onto.")
					.withStyle(ChatFormatting.YELLOW));
		}
	}

	/** For the self-test and autotests: who's aboard. */
	public List<Entity> aboard() {
		return getPassengers();
	}
}
