package dev.rubymod.game.rift;

/**
 * Everything you can pick up in Ruby Rift. Commons are simple but huge; Rares add a new
 * mechanic; Legendaries change the whole run (bosses and treasure rooms only); Cursed
 * ones are massive with a real cost. Elements steer later offers toward a build.
 */
public enum Relic {
	// ----- Common: simple, but huge -----
	BOTTOMLESS_MAG(Tier.COMMON, Element.NONE, "Bottomless Mag", "rubymod:heavy_ammo", "You never reload. Ever."),
	TWIN_BARRELS(Tier.COMMON, Element.NONE, "Twin Barrels", "minecraft:crossbow", "Every shot fires twice."),
	TITAN_BLOOD(Tier.COMMON, Element.NONE, "Titan Blood", "minecraft:enchanted_golden_apple", "Double max health."),
	ROCKET_BOOTS(Tier.COMMON, Element.NONE, "Rocket Boots", "minecraft:firework_rocket", "Jump again in mid-air. No fall damage."),
	QUICK_HANDS(Tier.COMMON, Element.NONE, "Quick Hands", "minecraft:sugar", "Your guns fire twice as fast."),
	FIELD_MEDIC(Tier.COMMON, Element.NONE, "Field Medic", "minecraft:glistering_melon_slice", "Touch a downed teammate to bring them back instantly."),

	// ----- Rare: new mechanics -----
	CHAIN_LIGHTNING(Tier.RARE, Element.SHOCK, "Chain Lightning", "minecraft:lightning_rod", "Every hit arcs to 3 nearby enemies."),
	NAPALM(Tier.RARE, Element.FIRE, "Napalm Rounds", "minecraft:fire_charge", "Hits leave burning ground. Burning enemies explode when they die."),
	CRYO(Tier.RARE, Element.FROST, "Cryo Rounds", "minecraft:blue_ice", "3 hits freeze an enemy solid. Shoot it again and it shatters, hurting everything around."),
	DOMINO(Tier.RARE, Element.EXPLOSIVE, "Domino Effect", "minecraft:tnt", "Every enemy you kill explodes."),
	GRAVITY_GRENADES(Tier.RARE, Element.EXPLOSIVE, "Gravity Grenades", "minecraft:heavy_core", "Your grenades pull every enemy into a pile before they blow. +4 grenades a room."),
	RUBY_SIPHON(Tier.RARE, Element.RUBY, "Ruby Siphon", "rubymod:ruby", "Every kill heals your whole team."),
	RICOCHET(Tier.RARE, Element.NONE, "Ricochet Rounds", "minecraft:slime_ball", "Bullets bounce off walls 3 times, curving toward an enemy each time."),
	OVERKILL(Tier.RARE, Element.NONE, "Overkill", "minecraft:iron_sword", "Extra damage from a kill carries on into the next enemy."),
	POCKET_TANK(Tier.RARE, Element.EXPLOSIVE, "Pocket Tank", "rubymod:tank", "A mini tank drone follows you and shells enemies."),
	DROP_TURRET(Tier.RARE, Element.NONE, "Drop Turret", "rubymod:sentry_turret", "A sentry turret lands beside you at the start of every room."),
	BLINK(Tier.RARE, Element.NONE, "Blink", "minecraft:ender_pearl", "Crouch + jump to teleport 8 blocks where you're looking."),
	SHADOW_CLONE(Tier.RARE, Element.NONE, "Shadow Clone", "minecraft:echo_shard", "Kills can summon a shadow copy of you that fights for 10 seconds."),
	MIRROR_GUARD(Tier.RARE, Element.NONE, "Mirror Guard", "minecraft:shield", "While crouching, enemy shots bounce back at whoever fired them."),
	BULLET_TIME(Tier.RARE, Element.FROST, "Bullet Time", "minecraft:clock", "Once a room, dropping low slows every enemy almost to a stop for 5 seconds."),
	LEECH_SWARM(Tier.RARE, Element.RUBY, "Leech Swarm", "minecraft:fermented_spider_eye", "Each kill sends leeches into nearby enemies; they heal you."),

	// ----- Legendary: changes the whole run -----
	RUBY_AVATAR(Tier.LEGENDARY, Element.RUBY, "Ruby Avatar", "rubymod:ruby_block", "Grow to twice your size with double health. Land from a jump to stomp everything flat."),
	ORBITAL_STRIKE(Tier.LEGENDARY, Element.EXPLOSIVE, "Orbital Strike", "minecraft:end_crystal", "Every 12 seconds, a laser from the sky hits what you're aiming at."),
	NECROMANCER(Tier.LEGENDARY, Element.NONE, "Necromancer", "minecraft:wither_skeleton_skull", "Enemies you kill come back on your side for 30 seconds."),
	BLACK_HOLE(Tier.LEGENDARY, Element.NONE, "Black Hole Rounds", "minecraft:ender_eye", "Every 10th hit opens a black hole that swallows small enemies."),
	MIDAS(Tier.LEGENDARY, Element.RUBY, "Midas Touch", "rubymod:ruby_heart", "Hits can turn an enemy into a solid ruby statue. Instant kill, extra gold."),
	TIME_LOOP(Tier.LEGENDARY, Element.NONE, "Time Loop", "minecraft:totem_of_undying", "The first time your whole team goes down, time rewinds and everyone's back up."),
	ARSENAL(Tier.LEGENDARY, Element.NONE, "Arsenal", "rubymod:ruby_blaster", "Every gun in your hotbar fires together."),

	// ----- Cursed: massive power, a real cost -----
	GLASS_CANNON(Tier.CURSED, Element.NONE, "Glass Cannon", "minecraft:glass", "Triple damage. Your max health drops to 3 hearts."),
	BERSERKER(Tier.CURSED, Element.FIRE, "Berserker", "minecraft:redstone", "The lower your health, the harder you hit (up to 4x)."),
	BLOOD_AMMO(Tier.CURSED, Element.RUBY, "Blood Ammo", "minecraft:nether_wart", "Infinite ammo and 2.5x damage, but every shot costs health.");

	public enum Tier {
		COMMON("Common", 0xFFB8C4D0), RARE("Rare", 0xFF4FA8FF), LEGENDARY("Legendary", 0xFFFFC02E), CURSED("Cursed", 0xFFB04CFF);

		public final String title;
		public final int color;

		Tier(String title, int color) {
			this.title = title;
			this.color = color;
		}
	}

	public enum Element {
		NONE("", 0xFFFFFFFF), SHOCK("Shock", 0xFF66E0FF), FIRE("Fire", 0xFFFF7A2E), FROST("Frost", 0xFFA8E8FF), EXPLOSIVE("Explosive", 0xFFFFD040),
		RUBY("Ruby", 0xFFFF3D6E);

		public final String title;
		public final int color;

		Element(String title, int color) {
			this.title = title;
			this.color = color;
		}
	}

	public final Tier tier;
	public final Element element;
	public final String title;
	public final String icon;
	public final String about;

	Relic(Tier tier, Element element, String title, String icon, String about) {
		this.tier = tier;
		this.element = element;
		this.title = title;
		this.icon = icon;
		this.about = about;
	}

	public String id() {
		return name().toLowerCase();
	}

	public static Relic byId(String id) {
		for (Relic r : values()) {
			if (r.id().equals(id)) {
				return r;
			}
		}
		return null;
	}

	/** "id|title|tier|element|icon|about|price" for the screens. */
	public String line(int price) {
		return id() + "|" + title + "|" + tier.name() + "|" + element.name() + "|" + icon + "|" + about + "|" + price;
	}
}
