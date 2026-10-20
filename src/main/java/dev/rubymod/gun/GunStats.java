package dev.rubymod.gun;

/**
 * Tunable numbers for one gun. Damage is in half-hearts (a player has 20 HP).
 *
 * @param damage        damage per bullet (per pellet for shotguns)
 * @param fireInterval  ticks between shots (20 ticks = 1 second)
 * @param magazine      rounds per magazine
 * @param reloadTicks   ticks a full reload takes
 * @param hipSpread     bullet spread in degrees while not aiming
 * @param aimSpread     bullet spread in degrees while aiming (sneaking)
 * @param range         max range in blocks
 * @param pellets       bullets per shot (shotguns fire several)
 * @param ammo          ammo item this gun reloads from
 * @param automatic     true = hold right-click to keep firing
 * @param headshot      damage multiplier for headshots
 * @param moveSpeed     movement speed multiplier while firing (1.0 = no slowdown)
 * @param recoil        camera kick in degrees per shot
 * @param zoom          FOV multiplier while aiming (1.0 = no zoom)
 * @param special       extra behaviour
 * @param silenced      fitted with a Suppressor: quiet, no tracers
 */
public record GunStats(
		float damage,
		int fireInterval,
		int magazine,
		int reloadTicks,
		float hipSpread,
		float aimSpread,
		double range,
		int pellets,
		AmmoType ammo,
		boolean automatic,
		float headshot,
		float moveSpeed,
		float recoil,
		float zoom,
		Special special,
		boolean silenced
) {
	public GunStats(float damage, int fireInterval, int magazine, int reloadTicks, float hipSpread, float aimSpread, double range, int pellets,
			AmmoType ammo, boolean automatic, float headshot, float moveSpeed, float recoil, float zoom, Special special) {
		this(damage, fireInterval, magazine, reloadTicks, hipSpread, aimSpread, range, pellets, ammo, automatic, headshot, moveSpeed, recoil, zoom,
				special, false);
	}

	public enum Special {
		NONE,
		/** Shotgun: breaks glass and doors it hits (only where blocks may be broken). */
		BREACH,
		/** Ruby Blaster: bolts bounce once off walls. */
		BOUNCE,
		/** Sniper: other players see a glint when you aim at them. */
		GLINT
	}
}
