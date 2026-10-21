package dev.rubymod.registry;

import dev.rubymod.RubyMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/** Ruby Wars sounds (synthesized by tools/sounds.py; see assets/rubymod/sounds.json). */
public final class ModSounds {
	public static final SoundEvent RIFLE_SHOT = register("rifle_shot");
	public static final SoundEvent PISTOL_SHOT = register("pistol_shot");
	public static final SoundEvent SHOTGUN_SHOT = register("shotgun_shot");
	public static final SoundEvent SNIPER_SHOT = register("sniper_shot");
	public static final SoundEvent BLASTER_SHOT = register("blaster_shot");
	public static final SoundEvent SILENCED_SHOT = register("silenced_shot");
	public static final SoundEvent ROCKET_LAUNCH = register("rocket_launch");
	public static final SoundEvent TANK_CANNON = register("tank_cannon");
	public static final SoundEvent MAG_OUT = register("mag_out");
	public static final SoundEvent MAG_IN = register("mag_in");
	public static final SoundEvent BOLT = register("bolt");
	public static final SoundEvent SHELL_IN = register("shell_in");
	public static final SoundEvent CASING = register("casing");
	public static final SoundEvent DRY_FIRE = register("dry_fire");
	public static final SoundEvent JET_ENGINE = register("jet_engine");
	public static final SoundEvent HELI_ROTOR = register("heli_rotor");
	public static final SoundEvent TANK_ENGINE = register("tank_engine");
	public static final SoundEvent JEEP_ENGINE = register("jeep_engine");

	private ModSounds() {
	}

	private static SoundEvent register(String name) {
		var id = RubyMod.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void initialize() {
	}
}
