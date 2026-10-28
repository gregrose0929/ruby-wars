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

	/** A fused weapon's traits (names of {@link dev.rubymod.gun.Trait} values), and "LEGEND:<name>" for a named legendary. */
	public static final DataComponentType<List<String>> TRAITS = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("traits"),
			DataComponentType.<List<String>>builder().persistent(Codec.STRING.listOf())
					.networkSynchronized(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())).build());

	/** A Field Radio's channel (1-8). */
	public static final DataComponentType<Integer> RADIO_CHANNEL = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("radio_channel"),
			DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	/** A machine's upgrade tier, kept on the item when it's picked up. */
	public static final DataComponentType<Integer> MACHINE_TIER = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("machine_tier"),
			DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	/** A copied structure on a Blueprint. */
	public static final DataComponentType<dev.rubymod.industry.Blueprints.Data> BLUEPRINT = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("blueprint"),
			DataComponentType.<dev.rubymod.industry.Blueprints.Data>builder().persistent(dev.rubymod.industry.Blueprints.Data.CODEC)
					.networkSynchronized(dev.rubymod.industry.Blueprints.Data.STREAM_CODEC).build());

	/** First corner picked with a Blueprint or the Architect's Wand. */
	public static final DataComponentType<net.minecraft.core.BlockPos> CORNER = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("corner"),
			DataComponentType.<net.minecraft.core.BlockPos>builder().persistent(net.minecraft.core.BlockPos.CODEC)
					.networkSynchronized(net.minecraft.core.BlockPos.STREAM_CODEC).build());

	/** Architect's Wand shape (index into ArchitectWandItem.Mode). */
	public static final DataComponentType<Integer> WAND_MODE = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			RubyMod.id("wand_mode"),
			DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	public static void initialize() {
	}
}
