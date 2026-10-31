package dev.rubymod.game.convoy;

/**
 * What the team can fit to the convoy at a depot: each player picks one of three cards.
 * Most go on the truck; a few hand out gear. Some can be taken more than once.
 */
public enum ConvoyUpgrade {
	PLATING("Armor Plating", "The truck takes less damage, gets 25% more armor, and is fully repaired.", "minecraft:iron_block", 0xFFB8C4D0, 3),
	MINIGUN("Roof Minigun", "The truck shoots enemies near it all by itself. Take it again to fire faster.", "rubymod:ruby_minigun", 0xFFFFC040, 3),
	ROCKETS("Rocket Pods", "Every few seconds the truck fires a rocket at the biggest enemy nearby.", "rubymod:rocket", 0xFFFF7A2E, 2),
	TESLA("Tesla Coil", "Zaps enemies that get close to the truck every 2 seconds.", "minecraft:lightning_rod", 0xFF80E0FF, 2),
	ENGINE("Turbo Engine", "The truck drives 25% faster.", "minecraft:blaze_powder", 0xFFFF9A3D, 2),
	RAM("Spiked Ram", "Monsters can't stop the truck: it hurts and shoves away anything in front of it.", "minecraft:pointed_dripstone", 0xFFC0A080, 1),
	MEDIC("Field Hospital", "Everyone near the truck slowly heals.", "minecraft:golden_apple", 0xFFFF7090, 2),
	SHIELD("Energy Shield", "A shield soaks up hits on the truck, and recharges when it isn't hit for a while.", "minecraft:heart_of_the_sea", 0xFF60C0FF, 2),
	DRONE("Repair Drone", "A little drone mends the truck whenever it isn't under fire.", "rubymod:recon_drone", 0xFF7CF59A, 2),
	AIRSTRIKE("Air Support", "Everyone gets an Air Strike Flare now, and another at every depot.", "rubymod:air_strike_flare", 0xFFFF5050, 1),
	SPARE("Spare Truck", "One more spare truck, in case this one gets wrecked.", "minecraft:minecart", 0xFFD8DEE4, 2),
	JETPACKS("Jump Jets", "Everyone gets a jetpack: hold Space in the air to fly.", "rubymod:jetpack", 0xFFFFD040, 1),
	HEAVY("Heavy Weapons", "You get a Ruby Minigun, and everyone gets more grenades.", "rubymod:ruby_minigun", 0xFFE0303A, 2);

	public final String title;
	public final String about;
	public final String icon;
	public final int color;
	public final int max;

	ConvoyUpgrade(String title, String about, String icon, int color, int max) {
		this.title = title;
		this.about = about;
		this.icon = icon;
		this.color = color;
		this.max = max;
	}

	static ConvoyUpgrade byId(String id) {
		for (ConvoyUpgrade u : values()) {
			if (u.name().equalsIgnoreCase(id)) {
				return u;
			}
		}
		return null;
	}

	/** A card for the picker: "id|title|about|icon|color". */
	String card(int have) {
		return name() + "|" + title + (have > 0 ? " " + "I".repeat(have + 1) : "") + "|" + about + "|" + icon + "|" + color;
	}

	/** The jobs to pick from before rolling out. */
	public enum Role {
		GUNNER("Gunner", "ARR Rifle and Combat Shotgun. Tough: you take less damage.", "rubymod:arr_rifle", 0xFFFFC040),
		DEMOLITION("Demolition", "Rocket Launcher and lots of grenades. Great on tanks, logs and the boss.", "rubymod:rocket_launcher", 0xFFFF7A2E),
		MARKSMAN("Marksman", "Longshot Sniper and Six Shooter. Picks off snipers on the ridges.", "rubymod:longshot_sniper", 0xFF80E0FF),
		MECHANIC("Mechanic", "Ruby Blaster and Shotgun. Repairs the truck and vehicles 3x faster (crouch next to them).", "rubymod:ruby_blaster",
				0xFF7CF59A);

		public final String title;
		public final String about;
		public final String icon;
		public final int color;

		Role(String title, String about, String icon, int color) {
			this.title = title;
			this.about = about;
			this.icon = icon;
			this.color = color;
		}

		static Role byId(String id) {
			for (Role r : values()) {
				if (r.name().equalsIgnoreCase(id)) {
					return r;
				}
			}
			return null;
		}

		String card() {
			return name() + "|" + title + "|" + about + "|" + icon + "|" + color;
		}
	}
}
