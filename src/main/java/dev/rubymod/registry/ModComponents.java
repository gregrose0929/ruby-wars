package dev.rubymod.registry;

import com.mojang.serialization.Codec;

import dev.rubymod.RubyMod;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

public class ModComponents {
	/** Rounds left in a gun's magazine. */
	public static final DataComponentType<Integer> AMMO = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("ammo"),
			DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	public static void initialize() {
	}
}
