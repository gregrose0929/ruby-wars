package dev.rubymod.entity;

import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

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
 */
public class LightVehicleEntity extends VehicleEntity {
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
	protected void tickRidden(Player controller, Vec3 riddenInput) {
		super.tickRidden(controller, riddenInput);
		// Nimbler than the jeep (VehicleEntity turns 5 degrees a tick): a little extra turn on top.
		float extra = isBike() ? 2.0F : 1.0F;
		float yaw = getYRot() - controller.xxa * extra;
		setYRot(yaw);
		yRotO = yaw;
		yBodyRot = yaw;
		yHeadRot = yaw;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		int index = Math.max(0, getPassengers().indexOf(passenger));
		double back = isBike() ? -0.15 : index == 0 ? 0.0 : -0.65;
		return new Vec3(0, dimensions.height() * 0.6, back).yRot((float) Math.toRadians(-getYRot()));
	}
}
