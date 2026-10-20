package dev.rubymod.registry;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.AirStrikeFlareEntity;
import dev.rubymod.entity.BombEntity;
import dev.rubymod.entity.BreachingChargeEntity;
import dev.rubymod.entity.CruiseMissileEntity;
import dev.rubymod.entity.GrenadeEntity;
import dev.rubymod.entity.MortarShellEntity;
import dev.rubymod.entity.RocketEntity;

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

	private static <T extends Entity> EntityType<T> register(String name, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, RubyMod.id(name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}

	public static void initialize() {
	}
}
