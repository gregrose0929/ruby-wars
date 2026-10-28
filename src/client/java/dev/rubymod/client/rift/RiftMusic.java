package dev.rubymod.client.rift;

import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Music in the Ruby Rift: each theme has its own track (the Crystal Caverns echo, the
 * Molten Forge rumbles...), each boss its own, and when a boss is badly hurt the music
 * turns to the dragon fight's. The server says what's on ("music:..."); outside the Rift
 * the game's own music plays as usual.
 */
public final class RiftMusic {
	private static String track = "";

	private RiftMusic() {
	}

	static void set(String id) {
		track = id;
	}

	/** What should be playing now (null: whatever the game would play). */
	public static Music current() {
		if (!RiftClient.active() || track.isEmpty()) {
			return null;
		}
		Holder<SoundEvent> sound = switch (track) {
			case "theme:CRYSTAL" -> SoundEvents.MUSIC_BIOME_DRIPSTONE_CAVES;
			case "theme:FORGE" -> SoundEvents.MUSIC_BIOME_BASALT_DELTAS;
			case "theme:FROST" -> SoundEvents.MUSIC_BIOME_FROZEN_PEAKS;
			case "theme:LAB" -> SoundEvents.MUSIC_BIOME_LUSH_CAVES;
			case "theme:FOUNDRY" -> SoundEvents.MUSIC_BIOME_NETHER_WASTES;
			case "theme:SKYDOCK" -> SoundEvents.MUSIC_BIOME_JAGGED_PEAKS;
			case "theme:HEART" -> SoundEvents.MUSIC_BIOME_CRIMSON_FOREST;
			case "theme:THRESHOLD" -> SoundEvents.MUSIC_BIOME_DEEP_DARK;
			case "boss1" -> SoundEvents.MUSIC_DISC_PIGSTEP;
			case "boss2" -> SoundEvents.MUSIC_DISC_OTHERSIDE;
			case "boss3" -> SoundEvents.MUSIC_DISC_CREATOR;
			case "boss4" -> SoundEvents.MUSIC_DISC_PRECIPICE;
			case "rage" -> SoundEvents.MUSIC_DRAGON;
			case "victory" -> SoundEvents.MUSIC_CREDITS;
			default -> null;
		};
		if (sound == null) {
			return null;
		}
		// Fights and bosses cut straight in; the rooms' own tracks come and go with short gaps.
		boolean urgent = track.startsWith("boss") || track.equals("rage") || track.equals("victory");
		return new Music(sound, urgent ? 0 : 200, urgent ? 0 : 900, urgent);
	}
}
