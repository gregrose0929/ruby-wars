package dev.rubymod.game;

/** The stages of a Ruby Wars season, in order. */
public enum Phase {
	/** Before an admin runs /rubywars start. Plays like the build phase, with no timer. */
	LOBBY("Lobby"),
	BUILD("Build Phase"),
	LOCKDOWN("Lockdown"),
	WAR("Open War"),
	SUDDEN_DEATH("Sudden Death"),
	FINAL_CIRCLE("Final Circle"),
	ENDED("Season Over");

	private final String title;

	Phase(String title) {
		this.title = title;
	}

	public String title() {
		return title;
	}

	/** Battle Day is running: claims are open and defenses are on. */
	public boolean isBattle() {
		return this == LOCKDOWN || this == WAR || this == SUDDEN_DEATH || this == FINAL_CIRCLE;
	}

	/** Players can hurt each other. */
	public boolean pvp() {
		return this == WAR || this == SUDDEN_DEATH || this == FINAL_CIRCLE;
	}

	/** Claims block other players from building and explosions from breaking blocks. */
	public boolean claimsProtected() {
		return !isBattle();
	}

	/** Raid weapons (rockets, mortars, missiles, breaching charges) can be used. */
	public boolean raidWeaponsActive() {
		return pvp();
	}

	/** Hearts still let their owners respawn. */
	public boolean heartsGiveRespawns() {
		return this == LOCKDOWN || this == WAR;
	}

	public Phase next() {
		return switch (this) {
			case LOBBY -> BUILD;
			case BUILD -> LOCKDOWN;
			case LOCKDOWN -> WAR;
			case WAR -> SUDDEN_DEATH;
			case SUDDEN_DEATH -> FINAL_CIRCLE;
			case FINAL_CIRCLE, ENDED -> ENDED;
		};
	}
}
