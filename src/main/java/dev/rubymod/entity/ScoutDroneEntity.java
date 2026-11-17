package dev.rubymod.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.game.Squads;
import dev.rubymod.game.Waypoints;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Scout Drone (Armory, Recon research): a little quadcopter you fly yourself, through its own camera. W A S D to fly
 * (where you look is where it goes), Space up, Shift down, right-click to bring it home (it comes back to your
 * pocket). Enemies it can see get marked on your squad's HUD and map and glow for your squad for 10 seconds. One hit
 * knocks it out of the sky. The battery lasts a minute, and it can't go more than {@link #RANGE} blocks from you.
 */
public class ScoutDroneEntity extends ReconDroneEntity {
	public static final int BATTERY = 60 * 20;
	public static final double RANGE = 96;
	private static final double SPOT_RANGE = 48;
	private static final int GLOW_SECONDS = 10;
	private static final double SPEED = 0.7;
	private static final double CLIMB = 0.45;

	private UUID pilot;
	private float forward;
	private float strafe;
	private float up;
	private int flown;
	/** Knocked out: falling, until it hits the ground (or a few seconds pass). */
	private int downedAt = -1;

	public ScoutDroneEntity(EntityType<? extends ScoutDroneEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder attributes() {
		return createMobAttributes().add(Attributes.MAX_HEALTH, 1.0);
	}

	public static ScoutDroneEntity launch(ServerLevel level, ServerPlayer pilot) {
		ScoutDroneEntity drone = dev.rubymod.registry.ModEntities.SCOUT_DRONE.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (drone == null) {
			return null;
		}
		drone.pilot = pilot.getUUID();
		Vec3 look = pilot.getLookAngle();
		drone.snapTo(pilot.getX() + look.x * 1.2, pilot.getEyeY() + 0.3, pilot.getZ() + look.z * 1.2, pilot.getYRot(), 0);
		drone.setDeltaMovement(0, 0.35, 0);
		return drone;
	}

	public UUID pilot() {
		return pilot;
	}

	public boolean downed() {
		return downedAt >= 0;
	}

	public int batteryLeft() {
		return Math.max(0, BATTERY - flown);
	}

	/** The pilot's keys and where they're looking, this tick. */
	public void steer(float forward, float strafe, float up, float yaw, float pitch) {
		this.forward = Math.max(-1, Math.min(1, forward));
		this.strafe = Math.max(-1, Math.min(1, strafe));
		this.up = Math.max(-1, Math.min(1, up));
		if (!downed()) {
			setYRot(yaw);
			setYHeadRot(yaw);
			setYBodyRot(yaw);
			setXRot(Math.max(-90, Math.min(90, pitch)));
		}
	}

	@Override
	protected boolean steered(ServerLevel level) {
		flown++;
		ServerPlayer p = pilot == null ? null : level.getServer().getPlayerList().getPlayer(pilot);
		if (downed()) {
			// Tumbling down, smoking.
			setNoGravity(false);
			setDeltaMovement(getDeltaMovement().multiply(0.9, 1, 0.9).add(0, -0.06, 0));
			setYRot(getYRot() + 25);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.3, getZ(), 2, 0.1, 0.1, 0.1, 0.01);
			if (onGround() || tickCount - downedAt > 100) {
				level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.3, getZ(), 1, 0, 0, 0, 0);
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.6F, 1.6F);
				discard();
			}
			return true;
		}
		if (p == null || !p.isAlive()) {
			// Pilot gone: it just drops out of the air.
			discard();
			return true;
		}
		if (p.level() != level) {
			DronePilots.recall(p);
			return true;
		}
		if (flown >= BATTERY) {
			p.sendSystemMessage(Component.literal("Your Scout Drone's battery ran out: it flew home to your pocket.").withStyle(ChatFormatting.AQUA));
			DronePilots.recall(p);
			return true;
		}
		// Where the pilot is looking: forward goes that way (level, so looking down doesn't dive into the ground).
		double yaw = Math.toRadians(getYRot());
		Vec3 ahead = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		Vec3 want = ahead.scale(forward * SPEED).add(side.scale(strafe * SPEED)).add(0, up * CLIMB, 0);
		// Not past the edge of the signal: anything taking it further out is cancelled.
		Vec3 home = p.position().subtract(position());
		double out = Math.hypot(home.x, home.z);
		if (out > RANGE) {
			Vec3 back = new Vec3(home.x, 0, home.z).normalize();
			double outward = -(want.x * back.x + want.z * back.z);
			if (outward > 0) {
				want = want.add(back.scale(outward + 0.1));
			}
			if (flown % 20 == 0) {
				p.sendOverlayMessage(Component.literal("Signal's weak! The drone can't go any further from you.").withStyle(ChatFormatting.YELLOW));
			}
		}
		if (getY() > level.getMaxY() - 4 && want.y > 0) {
			want = new Vec3(want.x, 0, want.z);
		}
		// Easing toward what the keys want: a little drift, so it feels like flying.
		setDeltaMovement(getDeltaMovement().scale(0.55).add(want.scale(0.45)));
		if (flown % 10 == 0) {
			spot(level, p);
		}
		if (flown % 30 == 0) {
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, 0.5F, 2.0F);
		}
		if (flown % 10 == 0 && flown > 0) {
			int left = batteryLeft() / 20;
			int far = (int) Math.round(out);
			p.sendOverlayMessage(Component.literal("Scout Drone  •  battery " + left + "s  •  " + far + " m  •  right-click to bring it home")
					.withStyle(left <= 10 ? ChatFormatting.YELLOW : ChatFormatting.AQUA));
		}
		return true;
	}

	/** Enemies it can see: on the squad's HUD and map, and glowing for the squad. */
	private void spot(ServerLevel level, ServerPlayer p) {
		List<Integer> seen = new ArrayList<>();
		for (Entity e : level.getEntities(this, getBoundingBox().inflate(SPOT_RANGE), e -> enemyOf(pilot, e))) {
			if (e.distanceTo(this) > SPOT_RANGE || !hasLineOfSight(e)) {
				continue;
			}
			Waypoints.spot(e, pilot, GLOW_SECONDS);
			seen.add(e.getId());
		}
		if (seen.isEmpty()) {
			return;
		}
		MorePayloads.DroneGlow glow = new MorePayloads.DroneGlow(seen, GLOW_SECONDS);
		Net.send(p, glow);
		for (UUID mate : Squads.mates(pilot)) {
			ServerPlayer m = level.getServer().getPlayerList().getPlayer(mate);
			if (m != null) {
				Net.send(m, glow);
			}
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (downed() || isInvulnerableTo(level, source)) {
			return false;
		}
		// One hit and it's out of the sky.
		downedAt = tickCount;
		level.sendParticles(ParticleTypes.CRIT, getX(), getY() + 0.3, getZ(), 12, 0.3, 0.3, 0.3, 0.2);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.NEUTRAL, 1.0F, 0.8F);
		ServerPlayer p = pilot == null ? null : level.getServer().getPlayerList().getPlayer(pilot);
		if (p != null) {
			DronePilots.lost(p, this);
		}
		return true;
	}

	@Override
	public void remove(RemovalReason reason) {
		super.remove(reason);
		if (level() instanceof ServerLevel level && pilot != null) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(pilot);
			if (p != null) {
				DronePilots.gone(p, this);
			}
		}
	}

	@Override
	public float rotorAngle(float partial) {
		return (tickCount + partial) * 2.4F;
	}
}
