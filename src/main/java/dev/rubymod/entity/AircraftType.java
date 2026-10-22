package dev.rubymod.entity;

import java.util.List;

/**
 * Per-aircraft flight and weapon numbers. Speeds are blocks per tick
 * (1.0 = 20 blocks/s = 72 km/h on the HUD).
 *
 * @param id             entity/item id
 * @param name           display name
 * @param maxSpeed       top speed at full throttle in level flight
 * @param thrust         acceleration at full throttle
 * @param rotateSpeed    takeoff speed: pull up at or above this on the runway to lift off
 * @param stallSpeed     below this in the air the wings stop lifting
 * @param turnRate       how fast it turns toward where you look (degrees/tick at speed)
 * @param health         hit points
 * @param scale          render scale of the model
 * @param cannonDamage   nose cannon damage per round (0 = no cannon)
 * @param cannonInterval ticks between cannon rounds
 * @param cannonAmmo     cannon rounds
 * @param missiles       missile count
 * @param missileKind    what the missiles lock onto
 * @param bombs          bomb count
 * @param flares         flare bursts
 * @param seatUp         pilot seat height above the entity origin (blocks)
 * @param seatForward    pilot seat distance ahead of the entity origin (blocks)
 * @param gearHeight     model height of the bottom of the landing gear (model pixels)
 */
public record AircraftType(String id, String name, double maxSpeed, double thrust, double rotateSpeed, double stallSpeed, float turnRate,
		float health, float scale, float cannonDamage, int cannonInterval, int cannonAmmo, int missiles, Weapon missileKind, int bombs,
		int flares, double seatUp, double seatForward, double gearHeight, int rockets, boolean rotorcraft) {

	public enum Weapon {
		CANNON("Cannon"),
		ROCKETS("Rocket pods"),
		AAM("Air-to-air missiles"),
		AGM("Guided missiles"),
		BOMBS("Bombs");

		public final String title;

		Weapon(String title) {
			this.title = title;
		}
	}

	/** Fast and agile. Cannon, heat-seeking air-to-air missiles, lots of flares. */
	public static final AircraftType FIGHTER = new AircraftType("fighter_jet", "Fighter", 2.8, 0.0125, 1.76, 1.28, 6.0F,
			100.0F, 2.0F, 4.5F, 1, 450, 4, Weapon.AAM, 0, 8, 0.75, 2.1, 0.0, 0, false);
	/** Slow and tough. Heavy cannon, guided ground-attack missiles, a few bombs. */
	public static final AircraftType ATTACK = new AircraftType("attack_jet", "Attack Jet", 2.4, 0.0105, 1.52, 1.12, 4.5F,
			220.0F, 2.0F, 7.0F, 2, 500, 6, Weapon.AGM, 4, 6, 0.7, 2.9, 0.0, 0, false);
	/** Huge bomb load; needs a long runway (~400 blocks) to get off the ground. */
	public static final AircraftType BOMBER = new AircraftType("bomber", "Bomber", 2.08, 0.0082, 1.44, 1.08, 2.5F,
			320.0F, 2.4F, 0.0F, 0, 0, 0, Weapon.AGM, 16, 10, 1.4, 5.7, 0.0, 0, false);
	/**
	 * Takes off and lands anywhere. W/S fly forward and back, A/D strafe, Space/Ctrl climb and descend,
	 * the mouse turns the nose and aims. Chin cannon and rockets fire at the crosshair; guided missiles.
	 */
	public static final AircraftType HELICOPTER = new AircraftType("attack_heli", "Attack Helicopter", 2.2, 0.02, 0.0, 0.0, 5.0F,
			180.0F, 2.0F, 4.0F, 2, 600, 4, Weapon.AGM, 0, 6, 0.75, 2.0, 0.0, 24, true);

	public List<Weapon> weapons() {
		java.util.ArrayList<Weapon> list = new java.util.ArrayList<>();
		if (cannonDamage > 0) {
			list.add(Weapon.CANNON);
		}
		if (rockets > 0) {
			list.add(Weapon.ROCKETS);
		}
		if (missiles > 0) {
			list.add(missileKind);
		}
		if (bombs > 0) {
			list.add(Weapon.BOMBS);
		}
		return list;
	}

	/** Air drag that makes full throttle top out at maxSpeed. */
	public double drag() {
		return thrust / (maxSpeed * maxSpeed);
	}
}
