package dev.rubymod.entity;

import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A bomb dropped by an air strike. */
public class BombEntity extends ModProjectile {
	public BombEntity(EntityType<? extends BombEntity> type, Level level) {
		super(type, level);
	}

	public static BombEntity drop(Level level, Entity owner, Vec3 pos) {
		BombEntity bomb = new BombEntity(ModEntities.BOMB, level);
		bomb.setPos(pos);
		bomb.setOwner(owner);
		bomb.setItem(ModItems.AIR_STRIKE_FLARE.getDefaultInstance());
		bomb.setDeltaMovement(0, -0.5, 0);
		return bomb;
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.AIR_STRIKE_FLARE;
	}

	@Override
	protected double gravity() {
		return 0.08;
	}

	/** Guidance authority (blocks/tick of sideways correction per tick). The HUD uses this to judge reach. */
	public static final double MAX_STEER = 0.12;

	// Guided bomb (JDAM-style): steers its fall toward a designated target or point.
	private Entity guideEntity;
	private Vec3 guidePoint;

	public void guide(Entity target, Vec3 point) {
		this.guideEntity = target;
		this.guidePoint = point;
	}

	@Override
	public void tick() {
		if (level() instanceof ServerLevel && (guideEntity != null || guidePoint != null)) {
			steer();
		}
		super.tick();
		if (level() instanceof ServerLevel && tickCount > 400) {
			discard();
		}
	}

	/** Fins: trim the horizontal velocity so the fall ends on the target (limited authority, like a glide kit). */
	private void steer() {
		Vec3 target = guideEntity != null && guideEntity.isAlive() ? guideEntity.position() : guidePoint;
		if (target == null) {
			return;
		}
		Vec3 v = getDeltaMovement();
		double drop = getY() - target.y;
		if (drop <= 0.5) {
			return;
		}
		// Time to fall the remaining height (gravity 0.08, current sink rate).
		double sink = Math.max(0.0, -v.y);
		double t = (-sink + Math.sqrt(sink * sink + 2 * 0.08 * drop)) / 0.08;
		t = Math.max(t, 1.0);
		Vec3 need = new Vec3((target.x - getX()) / t, 0, (target.z - getZ()) / t);
		Vec3 have = new Vec3(v.x, 0, v.z);
		Vec3 correction = need.subtract(have);
		double max = MAX_STEER;
		if (correction.length() > max) {
			correction = correction.normalize().scale(max);
		}
		setDeltaMovement(v.add(correction));
	}

	/** Bombs fall in a stick: don't detonate on each other or on the plane that dropped them. */
	@Override
	protected boolean canHitEntity(Entity entity) {
		return !(entity instanceof BombEntity) && !(entity instanceof AircraftEntity) && super.canHitEntity(entity);
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		explode(level, 5.0F, true);
	}

	@Override
	protected float antiArmor(float power) {
		return 90.0F;
	}
}
