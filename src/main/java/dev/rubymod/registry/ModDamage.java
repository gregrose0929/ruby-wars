package dev.rubymod.registry;

import dev.rubymod.RubyMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Custom damage types, defined in data/rubymod/damage_type/. */
public class ModDamage {
	public static final ResourceKey<DamageType> BULLET = ResourceKey.create(Registries.DAMAGE_TYPE, RubyMod.id("bullet"));
	public static final ResourceKey<DamageType> HEADSHOT = ResourceKey.create(Registries.DAMAGE_TYPE, RubyMod.id("headshot"));
	public static final ResourceKey<DamageType> TURRET = ResourceKey.create(Registries.DAMAGE_TYPE, RubyMod.id("turret"));

	public static DamageSource source(Level level, ResourceKey<DamageType> type, Entity direct, Entity causing) {
		return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type), direct, causing);
	}
}
