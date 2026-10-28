package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * Which boss waits at the end of each act: chosen when the act's map is made (and shown at
 * the top of the map), from that act's pool, so runs don't always meet the same three.
 */
public final class RiftBosses {
	public enum Boss {
		JUGGERNAUT(1, "Juggernaut Mech", "A walking fortress. Wait for it to overheat, then hit it with everything.", RiftRoom.Theme.FOUNDRY, "minecraft:netherite_block",
				"Wait for it to overheat, then hit it with everything!"),
		GUNSHIP(2, "Rift Gunship", "A heavy gunship. Skyhammers and the AA guns bring it down.", RiftRoom.Theme.SKYDOCK, "rubymod:skyhammer",
				"Skyhammers and the AA guns: shoot it down!"),
		TITAN(3, "Ruby Titan", "A giant of living ruby, shielded by four pylons. Break them first.", RiftRoom.Theme.HEART, "rubymod:ruby_block",
				"Break the four pylons to bring its shield down!"),
		HIVE_QUEEN(1, "Hive Queen", "A giant spider queen. She spits acid, leaps on you, and lays egg sacs: smash them before they hatch.",
				RiftRoom.Theme.LAB, "minecraft:spider_eye", "Smash her egg sacs before they hatch!"),
		MIRROR_KNIGHT(2, "Mirror Knight", "A knight of polished steel that copies your relics. Don't shoot its Mirror Stance: shots bounce back.",
				RiftRoom.Theme.FROST, "minecraft:diamond_sword", "It copies your relics. Hold fire when the mirror's up!"),
		STORM_WYRM(3, "Storm Wyrm", "A flying serpent that calls down lightning and dives. Hit it hard while it lies stunned.", RiftRoom.Theme.SKYDOCK,
				"minecraft:trident", "Dodge the dive, then hit it while it's stunned!");

		public final int act;
		public final String title;
		public final String about;
		public final RiftRoom.Theme theme;
		public final String icon;
		public final String tip;

		Boss(int act, String title, String about, RiftRoom.Theme theme, String icon, String tip) {
			this.act = act;
			this.title = title;
			this.about = about;
			this.theme = theme;
			this.icon = icon;
			this.tip = tip;
		}
	}

	private RiftBosses() {
	}

	static Boss pick(int act, RandomSource random) {
		List<Boss> pool = new ArrayList<>();
		for (Boss b : Boss.values()) {
			if (b.act == act) {
				pool.add(b);
			}
		}
		return pool.isEmpty() ? Boss.TITAN : pool.get(random.nextInt(pool.size()));
	}

	/** Bring it in. */
	static RiftBoss spawn(Boss b, ServerLevel level, RiftRoom room, int players, int floor) {
		return switch (b) {
			case JUGGERNAUT -> JuggernautMech.spawn(level, room, players, floor);
			case GUNSHIP -> RiftGunship.spawn(level, room, players);
			case TITAN -> RubyTitan.spawn(level, room, players);
			case HIVE_QUEEN -> HiveQueen.spawn(level, room, players);
			case MIRROR_KNIGHT -> MirrorKnight.spawn(level, room, players);
			case STORM_WYRM -> StormWyrm.spawn(level, room, players);
		};
	}
}
