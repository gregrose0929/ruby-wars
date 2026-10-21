package dev.rubymod.registry;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.AirStrikeFlareEntity;
import dev.rubymod.entity.BombEntity;
import dev.rubymod.entity.BreachingChargeEntity;
import dev.rubymod.entity.CruiseMissileEntity;
import dev.rubymod.entity.GrenadeEntity;
import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType;
import dev.rubymod.entity.MortarShellEntity;
import dev.rubymod.entity.RocketEntity;
import dev.rubymod.entity.VehicleEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public class ModEntities {
	public static final EntityType<GrenadeEntity> FRAG_GRENADE = register("frag_grenade",
			EntityType.Builder.<GrenadeEntity>of(GrenadeEntity::new, MobCategory.MISC).sized(0.25F, 0.25F).clientTrackingRange(8).updateInterval(5));
	public static final EntityType<GrenadeEntity> SMOKE_GRENADE = register("smoke_grenade",
			EntityType.Builder.<GrenadeEntity>of(GrenadeEntity::new, MobCategory.MISC).sized(0.25F, 0.25F).clientTrackingRange(8).updateInterval(5));
	public static final EntityType<RocketEntity> ROCKET = register("rocket",
			EntityType.Builder.<RocketEntity>of(RocketEntity::new, MobCategory.MISC).sized(0.3F, 0.3F).clientTrackingRange(10).updateInterval(1));
	public static final EntityType<MortarShellEntity> MORTAR_SHELL = register("mortar_shell",
			EntityType.Builder.<MortarShellEntity>of(MortarShellEntity::new, MobCategory.MISC).sized(0.3F, 0.3F).clientTrackingRange(16).updateInterval(2));
	public static final EntityType<CruiseMissileEntity> CRUISE_MISSILE = register("cruise_missile",
			EntityType.Builder.<CruiseMissileEntity>of(CruiseMissileEntity::new, MobCategory.MISC).sized(0.6F, 0.6F).clientTrackingRange(24).updateInterval(1));
	public static final EntityType<BombEntity> BOMB = register("bomb",
			EntityType.Builder.<BombEntity>of(BombEntity::new, MobCategory.MISC).sized(0.5F, 0.5F).clientTrackingRange(12).updateInterval(2));
	public static final EntityType<AirStrikeFlareEntity> AIR_STRIKE_FLARE = register("air_strike_flare",
			EntityType.Builder.<AirStrikeFlareEntity>of(AirStrikeFlareEntity::new, MobCategory.MISC).sized(0.25F, 0.25F).clientTrackingRange(8).updateInterval(5));
	public static final EntityType<BreachingChargeEntity> BREACHING_CHARGE = register("breaching_charge",
			EntityType.Builder.<BreachingChargeEntity>of(BreachingChargeEntity::new, MobCategory.MISC).sized(0.4F, 0.4F).clientTrackingRange(8).updateInterval(20));

	public static final EntityType<VehicleEntity> JEEP = register("jeep",
			EntityType.Builder.<VehicleEntity>of(VehicleEntity::new, MobCategory.MISC).sized(2.4F, 1.6F).clientTrackingRange(10).updateInterval(1));
	public static final EntityType<VehicleEntity> TANK = register("tank",
			EntityType.Builder.<VehicleEntity>of(VehicleEntity::new, MobCategory.MISC).sized(3.0F, 2.0F).clientTrackingRange(10).updateInterval(1));

	public static final EntityType<dev.rubymod.entity.HeavyAAGunEntity> HEAVY_AA_GUN = register("heavy_aa_gun",
			EntityType.Builder.<dev.rubymod.entity.HeavyAAGunEntity>of(dev.rubymod.entity.HeavyAAGunEntity::new, MobCategory.MISC).sized(2.4F, 2.2F)
					.clientTrackingRange(12).updateInterval(1));
	public static final EntityType<dev.rubymod.entity.StickyChargeEntity> STICKY_CHARGE = register("sticky_charge",
			EntityType.Builder.<dev.rubymod.entity.StickyChargeEntity>of(dev.rubymod.entity.StickyChargeEntity::new, MobCategory.MISC).sized(0.3F, 0.3F)
					.clientTrackingRange(8).updateInterval(2));
	public static final EntityType<dev.rubymod.entity.WreckEntity> WRECK = register("wreck",
			EntityType.Builder.<dev.rubymod.entity.WreckEntity>of(dev.rubymod.entity.WreckEntity::new, MobCategory.MISC).sized(0.5F, 0.5F)
					.clientTrackingRange(10).noSave());
	public static final EntityType<dev.rubymod.entity.TargetDummyEntity> TARGET_DUMMY = register("target_dummy",
			EntityType.Builder.<dev.rubymod.entity.TargetDummyEntity>of(dev.rubymod.entity.TargetDummyEntity::new, MobCategory.MISC).sized(0.5F, 1.975F)
					.eyeHeight(1.7765F).clientTrackingRange(10));

	private static final java.util.Map<EntityType<?>, AircraftType> AIRCRAFT_TYPES = new java.util.HashMap<>();
	public static final EntityType<AircraftEntity> FIGHTER_JET = aircraft(AircraftType.FIGHTER, 4.0F, 1.9F);
	public static final EntityType<AircraftEntity> ATTACK_JET = aircraft(AircraftType.ATTACK, 4.5F, 2.0F);
	public static final EntityType<AircraftEntity> BOMBER = aircraft(AircraftType.BOMBER, 6.5F, 2.6F);
	public static final EntityType<AircraftEntity> ATTACK_HELI = aircraft(AircraftType.HELICOPTER, 3.5F, 2.6F);

	private static EntityType<AircraftEntity> aircraft(AircraftType kind, float width, float height) {
		EntityType<AircraftEntity> type = register(kind.id(),
				EntityType.Builder.<AircraftEntity>of(AircraftEntity::new, MobCategory.MISC).sized(width, height).clientTrackingRange(20).updateInterval(1));
		AIRCRAFT_TYPES.put(type, kind);
		return type;
	}

	/** The flight numbers for an aircraft entity type. */
	public static AircraftType aircraftType(EntityType<?> type) {
		return AIRCRAFT_TYPES.getOrDefault(type, AircraftType.FIGHTER);
	}

	private static <T extends Entity> EntityType<T> register(String name, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, RubyMod.id(name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}

	public static void initialize() {
		FabricDefaultAttributeRegistry.register(JEEP, VehicleEntity.jeepAttributes());
		FabricDefaultAttributeRegistry.register(TANK, VehicleEntity.tankAttributes());
		FabricDefaultAttributeRegistry.register(HEAVY_AA_GUN, dev.rubymod.entity.HeavyAAGunEntity.attributes());
		FabricDefaultAttributeRegistry.register(TARGET_DUMMY, net.minecraft.world.entity.decoration.ArmorStand.createAttributes());
		FabricDefaultAttributeRegistry.register(FIGHTER_JET, AircraftEntity.attributes(AircraftType.FIGHTER));
		FabricDefaultAttributeRegistry.register(ATTACK_JET, AircraftEntity.attributes(AircraftType.ATTACK));
		FabricDefaultAttributeRegistry.register(BOMBER, AircraftEntity.attributes(AircraftType.BOMBER));
		FabricDefaultAttributeRegistry.register(ATTACK_HELI, AircraftEntity.attributes(AircraftType.HELICOPTER));
	}
}
