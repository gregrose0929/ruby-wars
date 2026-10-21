package dev.rubymod.block;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.RocketEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import dev.rubymod.entity.Interceptable;
import dev.rubymod.game.Alerts;
import dev.rubymod.game.Explosions;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.registry.ModBlockEntities;
import dev.rubymod.registry.ModDamage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** The brains of every {@link DefenseBlock}. Runs on the server only. */
public class DefenseBlockEntity extends BlockEntity {
	public static final int MAX_TURRET_AMMO = 512;
	public static final int MAX_CHARGES = 3;
	private static final double TURRET_RANGE = 16;
	private static final float TURRET_DAMAGE = 3.0F;
	private static final double INTERCEPT_RANGE = 48;
	private static final double RADAR_RANGE = 96;
	private static final double ALARM_RANGE = 12;

	public int ammo;
	public int charges = MAX_CHARGES;
	private int cooldown;

	public DefenseBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.DEFENSE, pos, state);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		ammo = input.getIntOr("ammo", 0);
		charges = input.getIntOr("charges", MAX_CHARGES);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("ammo", ammo);
		output.putInt("charges", charges);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, DefenseBlockEntity be) {
		if (!(level instanceof ServerLevel serverLevel) || !(state.getBlock() instanceof DefenseBlock block)) {
			return;
		}
		if (be.cooldown > 0) {
			be.cooldown--;
			return;
		}
		// Spread work across ticks: each block only thinks every few ticks.
		long t = serverLevel.getGameTime() + (pos.asLong() & 7);
		SeasonData.Claim claim;
		switch (block.kind()) {
			case SENTRY_TURRET -> {
				if (t % 4 == 0 && Rules.phase().pvp() && (claim = claimHere(serverLevel, pos)) != null) {
					be.tickTurret(serverLevel, pos, claim);
				}
			}
			case INTERCEPTOR -> {
				if (Rules.phase().isBattle() && (claim = claimHere(serverLevel, pos)) != null) {
					be.tickInterceptor(serverLevel, pos, claim);
				}
			}
			case RADAR -> {
				if (t % 20 == 0 && (claim = claimHere(serverLevel, pos)) != null) {
					tickRadar(serverLevel, pos, claim);
				}
			}
			case ALARM -> {
				if (t % 10 == 0 && (claim = claimHere(serverLevel, pos)) != null) {
					be.tickAlarm(serverLevel, pos, claim);
				}
			}
			case CLAYMORE -> {
				if (t % 4 == 0 && Rules.phase().pvp() && (claim = claimHere(serverLevel, pos)) != null) {
					tickClaymore(serverLevel, pos, state, claim);
				}
			}
			case FLAK -> {
				if (t % 5 == 0) {
					be.tickFlak(serverLevel, pos, claimHere(serverLevel, pos));
				}
			}
			case SAM -> {
				if (t % 10 == 0) {
					be.tickSam(serverLevel, pos, claimHere(serverLevel, pos));
				}
			}
			case AIRFIELD_PAD -> {
				if (t % 40 == 0) {
					tickPad(serverLevel, pos);
				}
			}
		}
	}

	private static SeasonData.Claim claimHere(ServerLevel level, BlockPos pos) {
		return SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ());
	}

	private static List<Player> enemiesNear(ServerLevel level, Vec3 center, double range, SeasonData.Claim claim) {
		return level.getEntitiesOfClass(Player.class, new AABB(center, center).inflate(range),
				p -> p.isAlive() && Rules.isEnemyOf(claim, p) && p.position().distanceTo(center) <= range);
	}

	private static boolean canSee(ServerLevel level, Vec3 from, Vec3 to) {
		HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS;
	}

	private static ServerPlayer owner(ServerLevel level, SeasonData.Claim claim) {
		return level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(claim.owner));
	}

	// ----- Sentry Turret -----

	private void tickTurret(ServerLevel level, BlockPos pos, SeasonData.Claim claim) {
		if (ammo <= 0) {
			return;
		}
		Vec3 gun = Vec3.atCenterOf(pos).add(0, 0.7, 0);
		Optional<Player> target = enemiesNear(level, gun, TURRET_RANGE, claim).stream()
				.filter(p -> canSee(level, gun, p.getEyePosition()) || canSee(level, gun, p.position().add(0, 1, 0)))
				.min(Comparator.comparingDouble(p -> p.distanceToSqr(gun)));
		if (target.isEmpty()) {
			return;
		}
		Player victim = target.get();
		Vec3 aim = victim.position().add(0, victim.getBbHeight() * 0.6, 0);
		victim.setInvulnerableTime(0);
		victim.hurtServer(level, ModDamage.source(level, ModDamage.TURRET, null, owner(level, claim)), TURRET_DAMAGE);
		ammo--;
		cooldown = 3;
		setChanged();
		level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 1.2F, 1.8F);
		Vec3 step = aim.subtract(gun).normalize();
		double length = aim.distanceTo(gun);
		for (double d = 1; d < length; d += 1.5) {
			Vec3 p = gun.add(step.scale(d));
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
	}

	// ----- Missile Interceptor -----

	private void tickInterceptor(ServerLevel level, BlockPos pos, SeasonData.Claim claim) {
		if (charges <= 0) {
			return;
		}
		Vec3 center = Vec3.atCenterOf(pos);
		List<Entity> threats = level.getEntitiesOfClass(Entity.class, new AABB(center, center).inflate(INTERCEPT_RANGE),
				e -> e instanceof Interceptable i && i.threatens(claim));
		if (threats.isEmpty()) {
			return;
		}
		Entity threat = threats.get(0);
		Vec3 to = threat.position();
		Vec3 step = to.subtract(center).normalize();
		for (double d = 1; d < center.distanceTo(to); d += 1.0) {
			Vec3 p = center.add(step.scale(d));
			level.sendParticles(ParticleTypes.FIREWORK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
		level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 3.0F, 1.5F);
		((Interceptable) threat).intercept(level);
		charges--;
		cooldown = 20;
		setChanged();
		ServerPlayer owner = owner(level, claim);
		if (owner != null) {
			owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("Interceptor shot down incoming fire! Charges left: " + charges), true);
		}
	}

	// ----- Radar Station -----

	private static void tickRadar(ServerLevel level, BlockPos pos, SeasonData.Claim claim) {
		ServerPlayer owner = owner(level, claim);
		if (owner == null) {
			return;
		}
		Vec3 center = Vec3.atCenterOf(pos);
		List<Player> contacts = enemiesNear(level, center, RADAR_RANGE, claim);
		int nearest = -1;
		int bearing = 0;
		Optional<Player> closest = contacts.stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(owner)));
		if (closest.isPresent()) {
			Vec3 d = closest.get().position().subtract(owner.position());
			nearest = (int) Math.sqrt(d.x * d.x + d.z * d.z);
			bearing = (int) ((Math.toDegrees(Math.atan2(d.x, -d.z)) + 360) % 360);
		}
		boolean missile = !level.getEntitiesOfClass(Entity.class, new AABB(center, center).inflate(160),
				e -> e instanceof Interceptable i && i.threatens(claim)).isEmpty();
		Alerts.sendRadar(owner, contacts.size(), nearest, bearing, missile);
	}

	// ----- Alarm Block -----

	private void tickAlarm(ServerLevel level, BlockPos pos, SeasonData.Claim claim) {
		List<Player> intruders = enemiesNear(level, Vec3.atCenterOf(pos), ALARM_RANGE, claim);
		if (intruders.isEmpty()) {
			return;
		}
		cooldown = 100;
		level.playSound(null, pos, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 1.0F);
		level.sendParticles(ParticleTypes.ANGRY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 3, 0.3, 0.2, 0.3, 0);
		Alerts.intruder(level, claim, intruders.get(0), pos);
	}

	// ----- Anti-aircraft: Flak Cannon and SAM Site -----

	private static final double FLAK_RANGE = 80;
	private static final double SAM_RANGE = 180;

	/**
	 * Aircraft this air defense should shoot at: in a claim, enemy-flown aircraft on
	 * Battle Day; anywhere, target drones (so the training range has something to shoot).
	 */
	private static AircraftEntity airTarget(ServerLevel level, Vec3 from, double range, SeasonData.Claim claim) {
		AircraftEntity best = null;
		double bestDist = range;
		for (AircraftEntity a : level.getEntitiesOfClass(AircraftEntity.class, new AABB(from, from).inflate(range), a -> a.isAlive() && !a.onGround())) {
			var pilot = a.getControllingPassenger();
			boolean hostile = pilot != null ? claim != null && Rules.phase().pvp() && Rules.isEnemyOf(claim, pilot) : a.isDrone();
			double dist = a.position().distanceTo(from);
			if (hostile && dist < bestDist && canSee(level, from, a.getBoundingBox().getCenter())) {
				best = a;
				bestDist = dist;
			}
		}
		return best;
	}

	private void tickFlak(ServerLevel level, BlockPos pos, SeasonData.Claim claim) {
		if (ammo <= 0) {
			return;
		}
		Vec3 gun = Vec3.atCenterOf(pos).add(0, 1.2, 0);
		AircraftEntity target = airTarget(level, gun, FLAK_RANGE, claim);
		if (target == null) {
			return;
		}
		// Lead the target, plus a fuse error that grows with range.
		Vec3 center = target.getBoundingBox().getCenter();
		double t = center.distanceTo(gun) / 6.0;
		Vec3 velocity = target.getDeltaMovement().lengthSqr() > 0.01 ? target.getDeltaMovement()
				: Vec3.directionFromRotation(target.getXRot(), target.getYRot()).scale(target.groundSpeed());
		double error = 0.6 + center.distanceTo(gun) / 30.0;
		var random = level.getRandom();
		Vec3 burst = center.add(velocity.scale(t)).add((random.nextDouble() - 0.5) * 2 * error, (random.nextDouble() - 0.5) * 2 * error,
				(random.nextDouble() - 0.5) * 2 * error);
		level.sendParticles(ParticleTypes.EXPLOSION, true, true, burst.x, burst.y, burst.z, 2, 0.5, 0.5, 0.5, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, burst.x, burst.y, burst.z, 8, 0.8, 0.8, 0.8, 0.02);
		level.playSound(null, burst.x, burst.y, burst.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 3.0F, 1.3F);
		level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 2.0F, 0.6F);
		dev.rubymod.game.Armor.blast(level, null, claim != null ? owner(level, claim) : null, burst, 14.0F, null);
		ammo--;
		setChanged();
	}

	private void tickSam(ServerLevel level, BlockPos pos, SeasonData.Claim claim) {
		if (charges <= 0) {
			return;
		}
		Vec3 top = Vec3.atCenterOf(pos).add(0, 1.0, 0);
		AircraftEntity target = airTarget(level, top, SAM_RANGE, claim);
		if (target == null) {
			return;
		}
		var missile = RocketEntity.missile(level, claim != null ? owner(level, claim) : null, null, top.add(0, 0.8, 0), new Vec3(0, 3.0, 0),
				3.0F, 8.0, target, null).withAntiArmor(100.0F);
		level.addFreshEntity(missile);
		level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 4.0F, 0.5F);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, top.x, top.y, top.z, 20, 0.6, 0.4, 0.6, 0.05);
		charges--;
		cooldown = 100;
		setChanged();
	}

	// ----- Airfield Pad -----

	private static void tickPad(ServerLevel level, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		for (AircraftEntity a : level.getEntitiesOfClass(AircraftEntity.class, new AABB(center, center).inflate(12, 4, 12),
				a -> a.isAlive() && a.onGround() && a.groundSpeed() < 0.1)) {
			boolean done = a.rearmStep();
			if (a.getControllingPassenger() instanceof ServerPlayer pilot) {
				pilot.sendOverlayMessage(net.minecraft.network.chat.Component.literal(done ? "Rearmed and repaired" : "Rearming..."));
			}
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.getX(), a.getY() + 1.5, a.getZ(), 4, 1.0, 0.5, 1.0, 0);
		}
	}

	// ----- Claymore -----

	private static void tickClaymore(ServerLevel level, BlockPos pos, BlockState state, SeasonData.Claim claim) {
		Direction facing = state.hasProperty(ClaymoreBlock.FACING) ? state.getValue(ClaymoreBlock.FACING) : Direction.NORTH;
		Vec3 front = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(2.5));
		AABB zone = new AABB(front, front).inflate(1.8, 1.5, 1.8);
		List<Player> victims = level.getEntitiesOfClass(Player.class, zone, p -> p.isAlive() && Rules.isEnemyOf(claim, p));
		if (victims.isEmpty()) {
			return;
		}
		level.removeBlock(pos, false);
		Vec3 blast = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(1.2));
		Explosions.explode(level, null, blast.x, blast.y, blast.z, 2.5F, false);
	}
}
