package dev.rubymod.registry;

import java.util.List;

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

	/** Attachments fitted to a gun (names of {@link dev.rubymod.gun.Attachment} values). */
	public static final DataComponentType<List<String>> ATTACHMENTS = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("attachments"),
			DataComponentType.<List<String>>builder().persistent(Codec.STRING.listOf())
					.networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())).build());

	public static void initialize() {
	}
}
