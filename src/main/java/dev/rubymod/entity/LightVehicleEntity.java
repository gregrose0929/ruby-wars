package dev.rubymod.entity;

import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Light vehicles for getting across the map quickly: the Dirt Bike (one seat, fastest, nimble, flimsy) and the Quad
 * (two seats, quick). Both are quicker than the jeep, and bullets hurt them more.
 *
 * The Dirt Bike rides like a motorbike: the throttle (W) winds it up to speed, letting go coasts, S brakes (and only
 * then rolls it slowly backwards). It can't spin on the spot: it steers tightest at a medium speed and in wide sweeps
 * flat out, and hardly at all in the air, so it flies off a bump and keeps going. It leans into its turns, and Space
 * pulls a wheelie.
 */
public class LightVehicleEntity extends VehicleEntity {
	private static final EntityDataAccessor<Boolean> WHEELIE = SynchedEntityData.defineId(LightVehicleEntity.class, EntityDataSerializers.BOOLEAN);

	/** The bike's speed along its heading, blocks a tick (negative: backing up). Worked out where it's driven. */
	private float speed;
	/** The handlebars, -1 (full right) to 1 (full left), easing towards the keys. */
	private float steer;

	public LightVehicleEntity(EntityType<? extends LightVehicleEntity> type, Level level) {
		super(type, level);
	}

	public boolean isBike() {
		return getType() == ModEntities.DIRT_BIKE;
	}

	public static AttributeSupplier.Builder bikeAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 30.0)
				.add(Attributes.MOVEMENT_SPEED, 0.46)
				.add(Attributes.STEP_HEIGHT, 1.1)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.6);
	}

	public static AttributeSupplier.Builder quadAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 40.0)
				.add(Attributes.MOVEMENT_SPEED, 0.40)
				.add(Attributes.STEP_HEIGHT, 1.1)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.8);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(WHEELIE, false);
	}

	/** Is the rider pulling a wheelie (holding Space at speed)? For the model. */
	public boolean wheelie() {
		return entityData.get(WHEELIE);
	}

	@Override
	public String vehicleName() {
		return isBike() ? "Dirt Bike" : "Quad";
	}

	@Override
	public ItemStack asItem() {
		return new ItemStack(isBike() ? ModItems.DIRT_BIKE : ModItems.QUAD);
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().size() < (isBike() ? 1 : 2);
	}

	@Override
	protected boolean drivesLikeACar() {
		return !isBike();
	}

	/** The Quad's nimbler than the jeep. */
	@Override
	protected float carTurn() {
		return 6.0F;
	}

	// ----- The Dirt Bike's riding -----

	private static final float ACCEL = 0.02F;
	private static final float BRAKE = 0.035F;
	private static final float COAST = 0.006F;
	private static final float REVERSE = 0.1F;

	private float top() {
		return (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
	}

	/** For the self-test: the bike's speed now, blocks a tick. */
	public float bikeSpeed() {
		return speed;
	}

	/** For the self-test: one tick of the throttle and brake (1 forward, -1 back, 0 neither), as if it were being ridden. */
	public void testThrottle(float input, boolean grounded) {
		throttle(input, grounded);
	}

	/** For the self-test: how far the bike turns in a tick at this speed, with the bars right over. */
	public float testTurn(float atSpeed) {
		return turnRate(atSpeed, true);
	}

	private void throttle(float input, boolean grounded) {
		float top = top();
		if (input > 0) {
			// More push low down, less as it nears top speed; braking first if it was rolling back.
			speed = speed < 0 ? Math.min(0, speed + BRAKE) : Math.min(top, speed + ACCEL * (1.0F - 0.7F * speed / top));
		} else if (input < 0) {
			speed = speed > 0.01F ? Math.max(0, speed - BRAKE) : Math.max(-REVERSE, speed - 0.01F);
		} else if (grounded) {
			speed = speed > 0 ? Math.max(0, speed - COAST) : Math.min(0, speed + COAST * 2);
		}
	}

	/** Degrees a tick with the bars full over: a little when nearly stopped, tightest at a medium speed, wide at full speed. */
	private float turnRate(float atSpeed, boolean grounded) {
		float f = Math.min(1.0F, Math.abs(atSpeed) / top());
		float rate = f < 0.3F ? 1.2F + 5.8F * (f / 0.3F) : 7.0F - 3.6F * ((f - 0.3F) / 0.7F);
		return grounded ? rate : rate * 0.2F;
	}

	@Override
	protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
		if (!isBike()) {
			return super.getRiddenInput(controller, selfInput);
		}
		throttle(Math.signum(controller.zza), onGround());
		if (horizontalCollision && Math.abs(speed) > 0.1F) {
			speed *= 0.5F; // ran into something
		}
		return new Vec3(0, 0, speed == 0 ? 0 : Math.signum(speed));
	}

	@Override
	protected float getRiddenSpeed(Player controller) {
		return isBike() ? Math.abs(speed) : super.getRiddenSpeed(controller);
	}

	@Override
	protected void tickRidden(Player controller, Vec3 riddenInput) {
		if (!isBike()) {
			super.tickRidden(controller, riddenInput); // the Quad drives like a (nimble) car: see VehicleEntity
			return;
		}
		steer += (Mth.clamp(controller.xxa, -1, 1) - steer) * 0.25F;
		if (Math.abs(steer) < 0.01F) {
			steer = 0;
		}
		// Backing up, the bars steer the other way round (like pushing it backwards).
		float yaw = getYRot() - steer * turnRate(speed, onGround()) * Math.signum(speed == 0 ? 1 : speed) * (speed == 0 ? 0.6F : 1.0F);
		setYRot(yaw);
		yBodyRot = yaw;
		yHeadRot = yaw;
		if (controller instanceof ServerPlayer sp) {
			entityData.set(WHEELIE, sp.getLastClientInput().jump() && onGround());
		}
	}

	/** How far it's leaning over (degrees, positive to its right) and up on its back wheel, now and a tick ago (worked out where it's seen). */
	private float lean;
	private float leanO;
	private float lift;
	private float liftO;

	public float lean(float partial) {
		return Mth.lerp(partial, leanO, lean);
	}

	public float wheelieAngle(float partial) {
		return Mth.lerp(partial, liftO, lift);
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			// Leaning into the turn as far as a real bike would: more the faster it goes and the tighter it turns.
			double yawRad = Math.toRadians(getYRot());
			double ahead = -(getX() - xo) * Math.sin(yawRad) + (getZ() - zo) * Math.cos(yawRad);
			float yawRate = Mth.wrapDegrees(getYRot() - yRotO);
			// (The Quad's body rolls the other way, a little, out of the turn.)
			float target = isBike() ? Mth.clamp((float) (yawRate * ahead * 22.0), -35.0F, 35.0F) * (onGround() ? 1.0F : 0.3F)
					: Mth.clamp((float) (-yawRate * ahead * 6.0), -7.0F, 7.0F);
			leanO = lean;
			lean += (target - lean) * 0.3F;
			liftO = lift;
			float up = wheelie() && ahead > 0.05 ? 24.0F : 0.0F;
			lift += (up - lift) * (up > lift ? 0.25F : 0.4F);
		}
		if (isBike() && getControllingPassenger() == null) {
			speed = 0;
			steer = 0;
			if (!level().isClientSide() && wheelie()) {
				entityData.set(WHEELIE, false);
			}
		}
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		int index = Math.max(0, getPassengers().indexOf(passenger));
		double back = isBike() ? -0.15 : index == 0 ? 0.0 : -0.65;
		double seat = dimensions.height() * 0.6;
		if (isBike() && lean != 0) {
			// The seat swings over with the lean, and the rider with it.
			double a = Math.toRadians(lean);
			return new Vec3(-seat * Math.sin(a), seat * Math.cos(a), back).yRot((float) Math.toRadians(-getYRot()));
		}
		return new Vec3(0, seat, back).yRot((float) Math.toRadians(-getYRot()));
	}
}
