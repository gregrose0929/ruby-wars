package dev.rubymod.industry;

/**
 * Every kind of machine, with its slot layout and numbers per tier (Mk I to Mk V).
 * Charge figures are per tick (the screens show them per second).
 */
public enum MachineKind {
	COMBUSTION_GENERATOR("Combustion Generator", "Burns coal, charcoal, wood or lava for charge.", 1, 0, 10),
	SOLAR_PANEL("Solar Panel", "Free charge while the sun shines on it.", 0, 0, 8),
	BATTERY("Battery Bank", "Stores charge: raises your grid's capacity.", 0, 0, 10),
	RUBY_DRILL("Ruby Drill", "Mines ore by itself; deeper drills find more rubies. Keeps digging at half speed while you're away.", 0, 9, 20),
	ORE_REFINERY("Ore Refinery", "Raw ore in, two ingots out (three at Mk IV). Ruby ore gives rubies.", 1, 3, 15),
	AMMO_FACTORY("Ammo Factory", "Metal and gunpowder in, ammunition out. Click the recipe to change it.", 2, 2, 12),
	TRADE_DEPOT("Trade Depot", "Sells anything put in it; rubies go straight to your bank.", 9, 0, 15),
	VAULT("Ruby Vault", "Your bank: deposit rubies, earn daily interest. Raiders can crack it on Battle Day.", 1, 0, 25),
	SHIELD_GENERATOR("Shield Generator", "Projects a dome that destroys incoming rockets, shells and bombs. Uses charge per hit.", 0, 0, 40),
	POINT_DEFENSE("Point Defense Gun", "Automatic gatling that shreds missiles, bombs and drones nearby. Uses charge.", 0, 0, 35),
	CONSTRUCTOR("Constructor", "Builds a Blueprint, pulling blocks from containers next to it.", 1, 0, 15);

	public static final int MAX_TIER = 5;
	public static final String[] TIER_NAMES = {"Mk I", "Mk II", "Mk III", "Mk IV", "Mk V"};

	public final String title;
	public final String description;
	public final int inputs;
	public final int outputs;
	/** Rubies for the Mk II upgrade; each next tier costs twice as much. */
	public final int upgradeBase;

	MachineKind(String title, String description, int inputs, int outputs, int upgradeBase) {
		this.title = title;
		this.description = description;
		this.inputs = inputs;
		this.outputs = outputs;
		this.upgradeBase = upgradeBase;
	}

	public int slots() {
		return inputs + outputs;
	}

	/** Rubies to go from {@code tier} to {@code tier + 1}, or -1 at max. */
	public int upgradeCost(int tier) {
		return tier >= MAX_TIER ? -1 : upgradeBase << (tier - 1);
	}

	// ----- Numbers per tier (index tier - 1) -----

	/** Generator / solar output in charge per tick. */
	public int output(int tier) {
		return switch (this) {
			case COMBUSTION_GENERATOR -> 2 + tier;          // 60..140 per second
			case SOLAR_PANEL -> tier;                        // 20..100 per second in sunlight
			default -> 0;
		};
	}

	/** Charge per tick while working. */
	public int draw(int tier) {
		return switch (this) {
			case RUBY_DRILL -> 1 + tier / 2;
			case ORE_REFINERY, AMMO_FACTORY -> 1 + tier / 3;
			case SHIELD_GENERATOR -> tier;
			case CONSTRUCTOR -> 2;
			default -> 0;
		};
	}

	/** Ticks per operation. */
	public int cycle(int tier) {
		return switch (this) {
			case RUBY_DRILL -> new int[] {160, 120, 90, 65, 45}[tier - 1];
			case ORE_REFINERY, AMMO_FACTORY -> new int[] {100, 75, 55, 40, 28}[tier - 1];
			case TRADE_DEPOT -> 20;
			case CONSTRUCTOR -> new int[] {8, 6, 4, 3, 2}[tier - 1];
			default -> 1;
		};
	}

	/** Battery capacity added to the grid. */
	public long batteryCapacity(int tier) {
		return this == BATTERY ? 20_000L * tier : 0;
	}

	/** Shield / point defense reach in blocks. */
	public int radius(int tier) {
		return switch (this) {
			case SHIELD_GENERATOR -> 8 + 4 * tier;
			case POINT_DEFENSE -> 24 + 8 * tier;
			default -> 0;
		};
	}

	/** Vault daily interest in percent (capped at {@link #interestCap}). */
	public int interestPercent(int tier) {
		return 1 + tier;
	}

	public int interestCap(int tier) {
		return 10 * tier;
	}

	public boolean generator() {
		return this == COMBUSTION_GENERATOR || this == SOLAR_PANEL;
	}
}
