package dev.rubymod.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Sticky C4. Thrown; sticks to the first vehicle, creature or block it hits and
 * rides along. Its thrower sets off every charge they've placed by right-clicking
 * with C4 again. Strong against vehicles.
 */
public class StickyChargeEntity extends ModProjectile {
	/** Live charges per thrower, so one right-click sets them all off. */
	private static final Map<UUID, List<StickyChargeEntity>> PLACED = new HashMap<>();

	private Entity stuckTo;
	private Vec3 offset;
	private boolean stuck;

	public StickyChargeEntity(EntityType<? extends StickyChargeEntity> type, Level level) {
		super(type, level);
	}

	public static StickyChargeEntity throwFrom(Level level, Player thrower) {
		StickyChargeEntity charge = new StickyChargeEntity(ModEntities.STICKY_CHARGE, level);
		charge.setPos(thrower.getEyePosition().add(thrower.getLookAngle().scale(0.5)));
		charge.setOwner(thrower);
		charge.setItem(ModItems.C4.getDefaultInstance());
		charge.setDeltaMovement(thrower.getLookAngle().scale(1.1).add(thrower.getDeltaMovement()));
		PLACED.computeIfAbsent(thrower.getUUID(), k -> new ArrayList<>()).add(charge);
		return charge;
	}

	/** True if this player has charges out (then right-click detonates instead of throwing). */
	public static boolean hasCharges(Player player) {
		List<StickyChargeEntity> list = PLACED.get(player.getUUID());
		if (list == null) {
			return false;
		}
		list.removeIf(c -> !c.isAlive());
		return !list.isEmpty();
	}

	public static void detonateAll(ServerLevel level, Player player) {
		List<StickyChargeEntity> list = PLACED.remove(player.getUUID());
		if (list != null) {
			for (StickyChargeEntity c : list) {
				if (c.isAlive() && c.level() instanceof ServerLevel l) {
					c.explode(l, 3.0F, true);
				}
			}
		}
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.C4;
	}

	@Override
	protected double gravity() {
		return stuck ? 0.0 : 0.05;
	}

	@Override
	protected float antiArmor(float power) {
		return 110.0F;
	}

	@Override
	protected boolean canHitEntity(Entity entity) {
		return !stuck && !(entity instanceof StickyChargeEntity) && super.canHitEntity(entity);
	}

	@Override
	public void tick() {
		if (stuck) {
			if (stuckTo != null) {
				if (!stuckTo.isAlive()) {
					stuckTo = null;
					stuck = false; // Drop off a destroyed target.
				} else {
					setPos(stuckTo.position().add(offset));
				}
			}
			setDeltaMovement(Vec3.ZERO);
			if (level() instanceof ServerLevel level && tickCount % 20 == 0) {
				level.sendParticles(ParticleTypes.SMALL_FLAME, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
			}
			baseTick();
			return;
		}
		super.tick();
		if (tickCount > 20 * 60 * 10) {
			discard();
		}
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		stuck = true;
		setDeltaMovement(Vec3.ZERO);
		if (hitResult instanceof EntityHitResult entityHit) {
			stuckTo = entityHit.getEntity();
			offset = hitResult.getLocation().subtract(stuckTo.position());
		}
		setPos(hitResult.getLocation());
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);
	}

	@Override
	protected void onHit(HitResult hitResult) {
		if (!stuck) {
			super.onHit(hitResult);
		}
	}
}
