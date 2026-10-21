package dev.rubymod.entity;

import dev.rubymod.game.CannonRounds;
import dev.rubymod.game.Explosions;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModDamage;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Heavy AA Gun: a manned twin autocannon on a rotating mount. Sit in it; the
 * mount follows your look, left-click fires (alternating barrels), hold
 * right-click for the gun sight with lead markers on aircraft. Proximity-fused
 * flak shells burst near aircraft out to about 300 blocks. A 40-round magazine
 * reloads from the gunner's Heavy Ammo.
 */
public class HeavyAAGunEntity extends Mob {
	public static final int MAGAZINE = 40;
	public static final int RELOAD_TICKS = 70;
	public static final double SHELL_SPEED = 7.0;
	private static final float TRAVERSE = 7.0F;
	private static final float ELEVATION = 5.0F;

	private static final EntityDataAccessor<Float> TURRET_YAW = SynchedEntityData.defineId(HeavyAAGunEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> GUN_PITCH = SynchedEntityData.defineId(HeavyAAGunEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> AMMO = SynchedEntityData.defineId(HeavyAAGunEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> RELOAD = SynchedEntityData.defineId(HeavyAAGunEntity.class, EntityDataSerializers.INT);

	private int cooldown;
	private boolean leftBarrel;
	/** Client: ticks since the last shot (for barrel recoil). */
	public int recoil = 10;

	public HeavyAAGunEntity(EntityType<? extends HeavyAAGunEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder attributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 120.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(TURRET_YAW, 0.0F);
		builder.define(GUN_PITCH, -20.0F);
		builder.define(AMMO, MAGAZINE);
		builder.define(RELOAD, 0);
	}

	public float turretYaw() {
		return entityData.get(TURRET_YAW);
	}

	public float gunPitch() {
		return entityData.get(GUN_PITCH);
	}

	public int ammo() {
		return entityData.get(AMMO);
	}

	public int reloadLeft() {
		return entityData.get(RELOAD);
	}

	protected void setReloadLeft(int ticks) {
		entityData.set(RELOAD, ticks);
	}

	/** Length of a full reload/recharge, for the HUD bar. */
	public int reloadTicks() {
		return RELOAD_TICKS;
	}

	/** Shell speed for the sight's lead calculation (blocks per tick). */
	public double shellSpeed() {
		return SHELL_SPEED;
	}

	/** Where the barrels point. */
	public Vec3 aim() {
		return Vec3.directionFromRotation(gunPitch(), turretYaw());
	}

	/** The muzzle of one barrel (side -1 left, +1 right). */
	public Vec3 muzzle(int side) {
		Vec3 aim = aim();
		Vec3 right = new Vec3(-Math.cos(Math.toRadians(turretYaw())), 0, -Math.sin(Math.toRadians(turretYaw())));
		return position().add(0, 1.5, 0).add(aim.scale(3.2)).add(right.scale(0.2 * side));
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
				sp.sendOverlayMessage(Component.literal(mountHint()));
			}
		}
		return InteractionResult.SUCCESS;
	}

	protected String mountHint() {
		return "Heavy AA Gun: look to aim, left-click fires, hold right-click for the sight. Sneak to leave.";
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player p ? p : null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// The gunner sits on the seat behind the guns and turns with the mount.
		return new Vec3(0, 0.55, -0.7).yRot((float) Math.toRadians(-turretYaw()));
	}

	@Override
	public void travel(Vec3 input) {
		// A gun mount doesn't drive; it just sits (and falls if the ground goes).
		Vec3 v = getDeltaMovement();
		setDeltaMovement(0, onGround() ? 0 : v.y - 0.08, 0);
		move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distanceSquared) {
		return false;
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource damageSource) {
		return false;
	}

	// ----- Gunnery -----

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			recoil++;
			return;
		}
		if (tickCount == 1 && turretYaw() == 0.0F) {
			entityData.set(TURRET_YAW, getYRot());
		}
		if (cooldown > 0) {
			cooldown--;
		}
		LivingEntity gunner = getControllingPassenger();
		if (gunner != null) {
			float yaw = turretYaw();
			entityData.set(TURRET_YAW, Mth.wrapDegrees(yaw + Mth.clamp(Mth.wrapDegrees(gunner.getYHeadRot() - yaw), -TRAVERSE, TRAVERSE)));
			float pitch = gunPitch();
			float target = Mth.clamp(gunner.getXRot(), -85.0F, 5.0F);
			entityData.set(GUN_PITCH, pitch + Mth.clamp(target - pitch, -ELEVATION, ELEVATION));
		}
		int reload = reloadLeft();
		if (reload > 0) {
			entityData.set(RELOAD, reload - 1);
			if (reload == 1 && usesMagazine()) {
				entityData.set(AMMO, MAGAZINE);
				level().playSound(null, getX(), getY(), getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.PLAYERS, 1.5F, 0.6F);
			}
		}
	}

	protected boolean usesMagazine() {
		return true;
	}

	/** Left-click from the gunner (VehicleFire packet). */
	public void fire(ServerPlayer gunner) {
		if (gunner != getControllingPassenger() || cooldown > 0 || reloadLeft() > 0) {
			return;
		}
		if (ammo() <= 0) {
			startReload(gunner);
			return;
		}
		cooldown = 4;
		entityData.set(AMMO, ammo() - 1);
		ServerLevel level = (ServerLevel) level();
		int side = leftBarrel ? -1 : 1;
		leftBarrel = !leftBarrel;
		Vec3 muzzle = muzzle(side);
		var random = level.getRandom();
		Vec3 dir = aim().add((random.nextDouble() - 0.5) * 0.015, (random.nextDouble() - 0.5) * 0.015, (random.nextDouble() - 0.5) * 0.015).normalize();
		CannonRounds.fireFlak(level, gunner, this, muzzle, dir.scale(SHELL_SPEED), 22.0F, 45);
		level.sendParticles(ParticleTypes.FLAME, true, false, muzzle.x, muzzle.y, muzzle.z, 6, 0.15, 0.15, 0.15, 0.05);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, muzzle.x, muzzle.y, muzzle.z, 3, 0.2, 0.2, 0.2, 0.02);
		level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.5F, 1.5F + random.nextFloat() * 0.2F);
		level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 2.0F, 0.6F);
		Net.send(gunner, new Payloads.Shake(0.6F, 4));
		level.broadcastEntityEvent(this, (byte) 60);
		if (ammo() == 0) {
			startReload(gunner);
		}
	}

	private void startReload(ServerPlayer gunner) {
		if (!gunner.isCreative()) {
			int need = 4;
			if (gunner.getInventory().countItem(ModItems.HEAVY_AMMO) < need) {
				gunner.sendOverlayMessage(Component.literal("Out of shells: a reload takes 4 Heavy Ammo."));
				return;
			}
			for (int i = 0; i < gunner.getInventory().getContainerSize() && need > 0; i++) {
				ItemStack s = gunner.getInventory().getItem(i);
				if (s.is(ModItems.HEAVY_AMMO)) {
					int take = Math.min(need, s.getCount());
					s.shrink(take);
					need -= take;
				}
			}
		}
		entityData.set(RELOAD, RELOAD_TICKS);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.CROSSBOW_LOADING_START.value(), SoundSource.PLAYERS, 1.5F, 0.6F);
	}

	@Override
	public void handleEntityEvent(byte id) {
		if (id == 60) {
			recoil = 0;
		} else {
			super.handleEntityEvent(id);
		}
	}

	// ----- Damage -----

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(ModDamage.ANTI_ARMOR)) {
			// Full damage.
		} else if (source.is(ModDamage.BULLET) || source.is(ModDamage.HEADSHOT)) {
			amount *= 0.15F;
		} else if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			amount *= 0.4F;
		}
		if (source.getEntity() != null && hasPassenger(source.getEntity())) {
			return false;
		}
		return super.hurtServer(level, source, amount);
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel level) {
			ejectPassengers();
			Explosions.explode(level, this, getX(), getY() + 1, getZ(), 3.0F, false);
			level.addFreshEntity(WreckEntity.of(level, this));
			discard();
		}
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putFloat("turret_yaw", turretYaw());
		output.putInt("ammo", ammo());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		entityData.set(TURRET_YAW, input.getFloatOr("turret_yaw", getYRot()));
		entityData.set(AMMO, input.getIntOr("ammo", MAGAZINE));
	}
}
