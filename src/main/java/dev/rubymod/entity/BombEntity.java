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

	@Override
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel && tickCount > 400) {
			discard();
		}
	}

	@Override
	protected void onImpact(ServerLevel level, HitResult hitResult) {
		explode(level, 5.0F, true);
	}
}
