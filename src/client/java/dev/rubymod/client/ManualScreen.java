package dev.rubymod.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The Field Manual (Y): short pages on everything Ruby Wars adds, so nobody has to read the README. */
public class ManualScreen extends Screen {
	private record Page(String title, String... lines) {
	}

	private static final List<Page> PAGES = List.of(
			new Page("Getting Started",
					"New? Try Free Roam first (H): every gun, vehicle and aircraft to play with, nothing to lose.",
					"In Ruby Wars, place your Ruby Heart: a starter base appears around it, with an Armory and a Ruby Drill that fills your bank by itself.",
					"Buy defenses at the Armory before raid day (days 3, 6, 9, 12), spend stars on upgrades (J) for tanks and jets, and grow your base.",
					"The NEXT STEP panel (left of the screen) and the GUIDE tab (J) show what to do next, step by step."),
			new Page("The Hub",
					"Joining the server puts you in the hub. Walk into a portal to play: Ruby Wars (red), Co-op Horde (green) or the party games (purple).",
					"Press H any time to switch games, or use /hub and /play rubywars|horde.",
					"Press I for your profile: your stats, and cosmetics you've earned (hats, trails, kill effects).",
					"Leaving Ruby Wars saves where you were and everything you carry; going back puts it all back.",
					"On Battle Day everyone still in the season is called back into Ruby Wars."),
			new Page("Party Games",
					"The purple portal in the hub (or H) has quick games, and Free Roam: a playground with every gun, weapon, vehicle and aircraft "
							+ "(the big red button resets it). Rounds keep going until you leave.",
					"Gun Game: a new weapon every kill. Hide & Seek: turn into a block! Infection: don't get caught by the zombies.",
					"Capture the Flag, Tank Battle and Air Battle: Red vs Blue. King of the Hill: hold the gold zone. Sky Race: jetpack, elytra and ice boat races.",
					"Build Battle: build the theme, then vote. Ruby Rush: a quick battle royale on a mini island."),
			new Page("Co-op Horde",
					"Build a base together and protect the Ruby Core in the middle from waves of monsters. You get 2 minutes to build first.",
					"Kills and cleared waves fill the team's ruby bank. Spend it at the Armory by the Core: guns, walls, turrets, mines, Tesla Coils...",
					"Monsters smash through walls, so build strong ones. Better gear unlocks as you clear waves.",
					"Teammate down? Crouch next to them for 3 seconds to bring them back. Everyone gets one Second Wind per game.",
					"Brutes every 5th wave, fog on waves 4, 9..., an enemy gunship on waves 7, 12... (flak, SAMs, Skyhammers).",
					"Pick Easy, Normal or Hard when you start (H). Reaching wave 5 counts as a Ruby Wars goal."),
			new Page("Ruby Rift",
					"A co-op roguelike for 1-4: fight room to room through three acts, and after every room pick 1 of 3 relics.",
					"Vote on the map where to go next: fights, elites, treasure, the merchant, mysteries, the Arsenal (new weapons), tank assaults.",
					"Relics change how you play: lightning, napalm, ice, drones, clones, black holes... Two elements on two teammates can make a Bond relic.",
					"Bosses: the Juggernaut Mech, the Rift Gunship, the Ruby Titan (break its pylons first).",
					"Between runs, Rift Shards buy loadouts and new relics at the Altar of Echoes (J in the Threshold). Heat makes it harder for more.",
					"Gunmen get tougher each act: Recruits (a red laser warns you before they fire), then Troopers, then Commandos."),
			new Page("The Season",
					"Build for 14 days, then survive one Battle Day. Last squad (or player) standing wins.",
					"Place your Ruby Heart to claim land: nobody else can build there, and you're safe inside it until Battle Day. Outside the bases you can fight.",
					"Battle Day: Lockdown (defenses arm), Open War (PvP, Hearts can be breached, you respawn at your Heart), "
							+ "Sudden Death (Hearts shatter, the border shrinks), Final Circle.",
					"Mine Ruby Ore deep underground (or in the Ruby Crater) and spend rubies at an Armory.",
					"No monsters, no hunger and it's always daytime: just build and stay alive."),
			new Page("The Map",
					"An island, the same every season. The Ruby Crater sits in the middle, full of ruby ore close to the surface.",
					"A ring of mountains surrounds it, with a pass toward each corner. Four rivers run out to the sea between the corners.",
					"Each corner has a flat meadow for a base: Green Hills, Pine Woods, Red Desert and Snow Plateau.",
					"The world border hugs the island."),
			new Page("Controls",
					"Left-click fire, hold right-click to aim, R reload.",
					"B: ping a spot for your squad, or spot the enemy under your crosshair.",
					"/duel guns|vehicles|planes <player>: a 1v1 test duel in the arena, first to 10 kills. You get your own gear back after.",
					"H: choose a game.  K: map.  U: hide/show the minimap.  J: guide, goals & upgrades.  O: squad.  I: profile.  Y: this manual.  V: voice chat (Simple Voice Chat).",
					"In vehicles: W/S, A/D, Space (smoke / flares), Left Alt free look. Aircraft: R weapon, scroll zoom, T designate, X flares, Z autopilot, C weapon cam, G gear. Bombs: look at the target, release on IN REACH. Helicopter: WASD fly, Space/Ctrl up/down, the mouse aims."),
			new Page("Economy",
					"Generators and solar panels fill your power grid. Ruby Drills dig ore; the Ore Refinery doubles it; the Trade Depot sells it into your bank.",
					"The Vault holds rubies and pays interest. Shop purchases come out of your bank automatically.",
					"Upgrade machines Mk I to Mk V. Your Heart's tier sets how many drills you can own (1, then 2/4/6).",
					"A Vault blown open on Battle Day spills a quarter of its owner's bank."),
			new Page("Squads",
					"/squad create <name>, /squad invite <player>, /squad join <name>, /squad leave.  /sc <message> for squad chat.",
					"Squadmates can't hurt each other, share claims, Hearts (respawns) and research, and show on your map and through walls.",
					"Squads lock when Battle Day starts."),
			new Page("Upgrades",
					"Advanced Armory gear starts locked. Press J: the GUIDE tab walks you through the season; the GOALS tab lists things to do, each worth 1 or 2 stars.",
					"Place your Heart, mine ruby, build machines, repel a raid, win a duel, drive and fly... Each goal counts once.",
					"Spend stars on the UPGRADES tab. You can't afford everything: split the upgrades with your squad."),
			new Page("Raids & Guards",
					"On build days 3, 6, 9 and 12 raiders attack your base. Each one at your Heart loots your bank until it dies.",
					"Kill them all for rubies (and a goal). Turrets fire on raiders even before Battle Day.",
					"/rubywars raid calls a practice raid on yourself (once a day).",
					"Guard Contracts hire a Rifleman or AT Gunner. Right-click a guard: follow / hold post. Sneak + right-click: pack up."),
			new Page("Gear",
					"Backpacks hold 27 or 54 slots. The Field Radio lets your squad talk across the map (needs Simple Voice Chat).",
					"Recon Drones mark enemies for your squad. Paint Kits respray vehicles.",
					"When you die your gear waits in a War Grave: only you can open it for 5 minutes.",
					"Blueprints copy a structure; hold one to see where a Constructor would build it.",
					"Jetpack (wear it): hold Space in the air to fly; it refuels on the ground.",
					"Holo Camo: right-click it with any block to disguise it. Your squad walks through it; enemies hit a wall."));

	private int page;

	public ManualScreen() {
		super(Component.literal("Field Manual"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		int w = Math.min(320, width - 20);
		int h = Math.min(200, height - 20);
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
		g.fillGradient(x, y, x + w, y + h, 0xF0232830, 0xF0171A1F);
		g.outline(x, y, w, h, 0xFF7A0A2A);
		g.fill(x + 1, y + 1, x + w - 1, y + 16, 0xFF1A1E24);
		g.fill(x + 1, y + 16, x + w - 1, y + 17, 0xFFD0154A);
		g.text(font, "FIELD MANUAL", x + 8, y + 5, 0xFFE8EAED, false);
		String count = (page + 1) + " / " + PAGES.size();
		g.text(font, count, x + w - 8 - font.width(count), y + 5, 0xFF8B93A1, false);
		// Page list down the left.
		for (int i = 0; i < PAGES.size(); i++) {
			int ty = y + 24 + i * 14;
			boolean on = i == page;
			if (on) {
				g.fill(x + 6, ty - 2, x + 84, ty + 10, 0xFF3A2430);
				g.fill(x + 6, ty - 2, x + 8, ty + 10, 0xFFD0154A);
			}
			g.text(font, PAGES.get(i).title(), x + 12, ty, on ? 0xFFFFFFFF : 0xFF8B93A1, false);
		}
		Page p = PAGES.get(page);
		int tx = x + 94;
		int ty = y + 24;
		g.text(font, p.title().toUpperCase(), tx, ty, 0xFFFF5A82, false);
		ty += 14;
		for (String line : p.lines()) {
			for (FormattedCharSequence seq : font.split(Component.literal(line), x + w - 8 - tx)) {
				if (ty > y + h - 12) {
					break;
				}
				g.text(font, seq, tx, ty, 0xFFC8D0D8, false);
				ty += 10;
			}
			ty += 4;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		int w = Math.min(320, width - 20);
		int h = Math.min(200, height - 20);
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		for (int i = 0; i < PAGES.size(); i++) {
			int ty = y + 24 + i * 14;
			if (event.x() >= x + 6 && event.x() < x + 84 && event.y() >= ty - 2 && event.y() < ty + 10) {
				page = i;
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	/** Test hook. */
	public void showPage(int page) {
		this.page = Math.max(0, Math.min(PAGES.size() - 1, page));
	}
}
