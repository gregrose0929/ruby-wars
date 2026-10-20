package dev.rubymod.entity;

import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A mortar shell on a high arc toward a marked target. */
public class MortarShellEntity extends ModProjectile implements Interceptable {
	public static final double GRAVITY = 0.05;
	private double targetX;
	private double targetZ;

	public MortarShellEntity(EntityType<? extends MortarShellEntity> type, Level level) {
		super(type, level);
	}

	/**
	 * Launch a shell from {@code from} so it lands at {@code target}. The flight
	 * time grows with distance so the arc always looks like a mortar lob.
	 */
	public static MortarShellEntity launch(Level level, LivingEntity owner, Vec3 from, Vec3 target) {
		MortarShellEntity shell = new MortarShellEntity(ModEntities.MORTAR_SHELL, level);
		shell.setPos(from);
		shell.setOwner(owner);
		shell.setItem(ModItems.MORTAR_SHELL.getDefaultInstance());
		shell.targetX = target.x;
		shell.targetZ = target.z;
		Vec3 d = target.subtract(from);
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		double t = Math.max(40, horizontal / 1.6);
		double vy = (d.y + 0.5 * GRAVITY * t * t) / t;
		shell.setDeltaMovement(d.x / t, vy, d.z / t);
		return shell;
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.MORTAR_SHELL;
	}

	@Override
	protected double gravity() {
		return GRAVITY;
	}

	@Override
	protected float drag() {
		return 1.0F;
	}

	@Override
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel level) {
			level.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
			if (getDeltaMovement().y < 0 && tickCount % 10 == 0) {
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 2.0F, 0.5F);
			}
			if (tickCount > 600) {
				discard();
			}
		}
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		explode(level, 3.0F, true);
	}

	@Override
	public boolean threatens(SeasonData.Claim claim) {
		if (getOwner() != null && claim.canUse(getOwner().getUUID())) {
			return false;
		}
		return claim.contains(Rules.dim(level()), (int) Math.floor(targetX), (int) Math.floor(targetZ));
	}

	@Override
	public void intercept(ServerLevel level) {
		level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 3.0F, 0.8F);
		discard();
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putDouble("target_x", targetX);
		output.putDouble("target_z", targetZ);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		targetX = input.getDoubleOr("target_x", getX());
		targetZ = input.getDoubleOr("target_z", getZ());
	}
}
