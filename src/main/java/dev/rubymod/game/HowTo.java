package dev.rubymod.game;

import java.util.List;
import java.util.Map;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.server.level.ServerPlayer;

/**
 * The how-to card: three pictures and three sentences on screen for the first seconds of a game, so nobody has to
 * be told the rules. Each player sees a game's card the first three times they play it, then it stays out of the way.
 */
public final class HowTo {
	/** How many times a card is shown before the player is taken to know the game. */
	private static final int TIMES = 3;

	/** mode -> title, then three "item|sentence" lines. */
	private static final Map<String, List<String>> CARDS = Map.ofEntries(
			Map.entry("gungame", List.of("Gun Game", "rubymod:arr_rifle|Every kill gives you the next weapon.",
					"rubymod:ruby_sword|The last one is the Golden Blade: one kill with it wins.", "minecraft:iron_sword|Get stabbed and you drop back a weapon.")),
			Map.entry("hideseek", List.of("Hide & Seek", "minecraft:barrel|Hiders: right-click a block to look like it, then stand still.",
					"minecraft:iron_sword|Seekers: hit anything that looks out of place.", "minecraft:feather|Hiders: use the feather to taunt for bonus points.")),
			Map.entry("infection", List.of("Infection", "minecraft:zombie_head|Zombies: catch a survivor and they join you.",
					"rubymod:combat_shotgun|Survivors: hold out for four minutes.", "rubymod:ruby_minigun|The last survivor gets a minigun.")),
			Map.entry("koth", List.of("King of the Hill", "minecraft:gold_block|Stand on the gold to score. First to 75 wins.",
					"minecraft:clock|The gold shrinks, then moves, every minute.", "minecraft:lightning_rod|Step off when lightning is on its way!")),
			Map.entry("ctf", List.of("Capture the Flag", "minecraft:red_wool|Grab their flag and carry it back to yours.",
					"rubymod:sandbags|You get 20 seconds to build defences first.", "minecraft:iron_bars|Right-click the ground with the Instant Barricade.")),
			Map.entry("tanks", List.of("Tank Battle", "rubymod:tank|W A S D to drive. Aim with the mouse, click to fire.",
					"minecraft:anvil|Drive over the floating pick-ups: repair, rapid fire, mines.", "minecraft:stone_bricks|You can drive straight through walls and trees.")),
			Map.entry("air", List.of("Air Battle", "rubymod:fighter_jet|Hold W to speed up, then look up to take off.",
					"minecraft:nether_star|Fly through a glowing ring to rearm and repair.", "minecraft:gold_ingot|The Ace glows, and is worth two knockouts.")),
			Map.entry("skyrace", List.of("Sky Race", "rubymod:jetpack|Go through every ring, in order, for two laps.",
					"minecraft:lime_concrete|Green rings give you a boost.", "minecraft:amethyst_shard|The purple ghost is the record run: beat it!")),
			Map.entry("build", List.of("Build Battle", "minecraft:grass_block|Five minutes to build the theme on your plot.",
					"minecraft:lime_wool|Then rate everyone's build with the wool in your hand.", "minecraft:painting|The winner goes on show in the hub.")),
			Map.entry("rush", List.of("Ruby Rush", "minecraft:chest|Drop in and grab gear from the chests.",
					"minecraft:red_stained_glass|Stay inside the zone as it closes in.", "minecraft:golden_helmet|Last one standing wins.")),
			Map.entry("horde", List.of("Co-op Horde", "rubymod:ruby_block|Protect the Ruby Core, wave after wave.",
					"rubymod:ruby|Spend the team's rubies at the Armory between waves.", "minecraft:golden_apple|Stand by a downed teammate to get them back up.")),
			Map.entry("rift", List.of("Ruby Rift", "minecraft:iron_door|Clear each room, then choose a relic.",
					"minecraft:nether_star|Some pairs of relics make a COMBO.", "minecraft:ender_pearl|Press F to use your ability.")),
			Map.entry("convoy", List.of("Ruby Convoy", "rubymod:jeep|Stay near the truck: it only moves with someone close.",
					"minecraft:iron_ingot|Crouch beside the truck to repair it.", "minecraft:goat_horn|Right-click the Truck Horn to knock enemies back.")));

	private HowTo() {
	}

	/** Show this mode's card to the player, if they're still new to it. */
	public static void show(ServerPlayer player, String mode) {
		List<String> card = CARDS.get(mode);
		if (card == null) {
			return;
		}
		Stats.Record r = Stats.get(player);
		int seen = r.howTo.getOrDefault(mode, 0);
		if (seen >= TIMES) {
			return;
		}
		r.howTo.put(mode, seen + 1);
		Stats.markDirty();
		Net.send(player, new MorePayloads.HowTo(card.get(0), card.subList(1, card.size())));
	}
}
