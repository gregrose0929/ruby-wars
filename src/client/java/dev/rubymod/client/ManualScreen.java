package dev.rubymod.client;

import java.util.List;

import dev.rubymod.client.ui.Ui;

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
					"In Ruby Wars, place your Ruby Heart: a starter base appears around it, with an Armory and a Ruby Drill that digs ore into a chest.",
					"Upgrade your Heart at the Armory (the first tab, Your Heart): a bigger claim, more health and more drills. 20 rubies, then 60.",
					"Buy defenses at the Armory before raid day (days 3, 6, 9, 12), spend stars on upgrades (J) for tanks and jets, and grow your base.",
					"The NEXT STEP panel (left of the screen) and the GUIDE tab (J) show what to do next, step by step."),
			new Page("The Hub",
					"Joining the server puts you in the hub. Walk into a portal to play: Ruby Wars (red), the Ruby Rift (pink), the Ruby Convoy (orange), "
							+ "Co-op Horde (green) or the party games (purple, behind you).",
					"Press H any time to switch games, or use /hub and /play rubywars|horde.",
					"Press I for your profile: your stats, and cosmetics you've earned: armor looks, trails, kill effects, truck paint, Ruby Heart looks and victory fireworks.",
					"Each Ruby Wars season you play puts a gold star after your name. More seasons unlock new Heart looks; winners get a statue in the Hall of Champions.",
					"Leaving Ruby Wars saves where you were and everything you carry; going back puts it all back.",
					"On Battle Day everyone still in the season is called back into Ruby Wars."),
			new Page("Party Games",
					"The purple portal in the hub (or H) has quick games, and Free Roam: a playground with every gun, weapon, vehicle and aircraft "
							+ "(the big red button resets it). Rounds keep going until you leave.",
					"Gun Game: a new weapon every kill. Hide & Seek: turn into a block! Infection: don't get caught by the zombies.",
					"Capture the Flag, Tank Battle and Air Battle: Red vs Blue. King of the Hill: hold the gold zone. Sky Race: jetpack, elytra and ice boat races.",
					"Build Battle: build the theme, then vote. Ruby Rush: a quick battle royale on a mini island.",
					"Party Board: a board game with minigames. Pie Mystery: catch the secret Prankster. Coaster Workshop: build a roller coaster (see Coaster Park)."),
			new Page("Co-op Horde",
					"Build a base together and protect the Ruby Core in the middle from waves of monsters. You get 2 minutes to build first.",
					"Kills and cleared waves fill the team's ruby bank. Spend it at the Armory by the Core: guns, walls, turrets, mines, Tesla Coils...",
					"Monsters smash through walls, so build strong ones. Better gear unlocks as you clear waves.",
					"Teammate down? Crouch next to them for 3 seconds to bring them back. Everyone gets one Second Wind per game.",
					"Brutes every 5th wave, fog on waves 4, 9..., an enemy gunship on waves 7, 12... (flak, SAMs, Skyhammers).",
					"Pick Easy, Normal or Hard when you start (H). Reaching wave 5 counts as a Ruby Wars goal."),
			new Page("Ruby Rift",
					"A co-op roguelike for 1-4: fight room to room through three acts, and after every room pick 1 of 3 relics.",
					"The map is different every run: three rooms a floor, paths that cross. Hover a room to see what it is, and plan your route.",
					"Find the three Rift Keys (beat a Burning Elite, steal 100 gold in a Vault Heist, take one at a Ruby Spring) to open a secret fourth act.",
					"Relics change how you play: lightning, napalm, ice, drones, clones, black holes... Two elements on two teammates can make a Bond relic.",
					"COMBOS: some pairs of relics switch on a bonus power by themselves (cards that would complete one say COMBO!). "
							+ "At the Arsenal, fuse two weapons; 12 pairs make a named legendary with its own special move.",
					"Your ability (pick it at the Altar of Echoes): press F (or Z). B pings what you look at for your team.",
					"Bosses change every run (each act has its own few). Hover the boss at the top of the map to see what waits.",
					"Between runs, Rift Shards buy loadouts and new relics at the Altar of Echoes (J in the Threshold). Heat makes it harder for more.",
					"Gunmen get tougher each act: Recruits (a red laser warns you before they fire), then Troopers, then Commandos."),
			new Page("Ruby Convoy",
					"Co-op for 1-4: get the Ruby Truck from the mine to the vault through five stages. Pick your job first (Gunner, Demolition, Marksman, Mechanic).",
					"The truck only drives while someone stays close, and stops while enemies crowd it. It never rolls back.",
					"Crouch next to it to repair it. Right-click the top of it to ride: the roof gun hits harder.",
					"Watch for Sappers (TNT heads) running at it, and Rocketeers aiming a red laser at it: shoot them first! Red circles: get out of them.",
					"Each stage has a job: shoot the fallen logs, wreck the tanks, guard the bridge builders, dig out of the avalanche, beat the Iron Dreadnought.",
					"Depots: repairs, jeeps, tanks and a helicopter, and everyone picks an upgrade (J if you closed it). Wrecked? A spare truck restarts the stage."),
			new Page("The Season",
					"Build until everyone's ready, then survive one Battle Day. Everyone plays on their own: the last player standing wins.",
					"Place your Ruby Heart to claim land: nobody else can build there, and you're safe inside it until Battle Day. Outside the bases you can fight.",
					"Battle Day: Lockdown (defenses arm), Open War (PvP, Hearts can be breached, you respawn at your Heart), "
							+ "Sudden Death (Hearts shatter, the border shrinks), Final Circle.",
					"Respawns: in Lockdown and Open War you come back at your Heart while it stands (the line under the timer says so). "
							+ "Heart gone, or Sudden Death: your next death knocks you out.",
					"Knocked out? Watch: click for the next player, right-click for the one before, crouch to fly free. Or press H and play party games in the hub.",
					"When the season ends, everyone gets a wrap-up card: the winner and a few awards. /rubywars recap shows it again. "
							+ "Every winner goes up in the Hall of Champions, in the hub beside the path to Ruby Wars.",
					"Each new season starts fresh: a fresh island, and everyone begins again with nothing in Ruby Wars (the hub and other games keep their things).",
					"Mine Ruby Ore deep underground (or in the Ruby Crater) and spend rubies at an Armory.",
					"No monsters and no hunger: just build and stay alive. Battle Day comes when the admin calls it (or on the date they set)."),
			new Page("The Armory",
					"Right-click an Armory. The tabs down the left are kinds of things: Your Heart, Guns, Ammo, Explosives, Defenses, Air Defense, "
							+ "Vehicles, Aircraft, Machines, Building, Security, Gear and Support.",
					"Click a card to see what it does and costs, then Buy. Shift-click a card to buy it at once. Prices are green when you can afford them.",
					"Guns have a Buy ammo button. Locked things say which upgrade unlocks them: Open upgrades (or J) to spend your stars.",
					"It spends the rubies you carry first, then your bank."),
			new Page("Machines & Belts",
					"Machines share your power grid: generators and solar panels fill it, Battery Banks hold more. Look at any machine to see how it's doing.",
					"A Ruby Drill keeps what it digs until you collect it, and stops when it's full. A conveyor belt next to it carries its finds away.",
					"Belts run the way their yellow arrows point and feed whatever is at the end: a chest, a Refinery, a Trade Depot.",
					"The Trade Depot buys anything, and sells whatever a belt brings it. Its screen shows how close you are to your next ruby.",
					"Stopped? The machine's screen says why and what to do (No power, Full, Needs fuel...)."),
			new Page("Sorting & Furnaces",
					"Belt Loader: put it against a chest, furnace or machine. It takes things out onto the belt in front (or straight into a container in front).",
					"Right-click a Loader with an item to take only that (gold ore, say). Crouch and right-click with an empty hand to take anything again.",
					"Belt Sorter: a belt piece that turns one kind of item off to its side. Right-click with an item to choose; empty hand swaps left and right.",
					"Furnaces work with belts from any side: things to cook go in the top, fuel underneath. A Loader on a furnace takes only what's cooked.",
					"Example: chest > Loader > belt > Sorter (coal turns off to the furnace's fuel, gold goes round to it) > furnace > Loader > belt > Trade Depot."),
			new Page("Defenses",
					"All base defenses follow one rule: they go for raiders whenever a raid is on, monsters any time, and enemy players from Open War.",
					"Sentry Turrets and Flak Cannons load with Heavy Ammo (right-click them). Interceptors and SAM Sites recharge with rubies.",
					"Tesla Coils, Shield Generators and Point Defense Guns run on your power grid.",
					"Walls: sandbags < glass < concrete < blast doors < reinforced concrete. Buildings that lose their supports fall down."),
			new Page("Vehicles & Boats",
					"Right-click the ground (or water, for boats) with a vehicle to put it down. Right-click it to get in, Sneak to get out.",
					"Jeeps, dirt bikes and quads are quick; tanks are slow and tough. The Tunnel Borer digs under enemy walls.",
					"Patrol Boat, Gunboat (it has a deck gun), Landing Craft: drive a jeep aboard, then Space drops the ramp on a beach.",
					"Jets need a runway (or a long flat road) to take off. An Airfield Pad repairs and rearms aircraft parked near it."),
			new Page("Battle Day Extras",
					"The admin can make Battle Day a night battle: searchlights sweep, flares light up the sky, and Night-Vision Goggles help you see.",
					"Sandstorms and blizzards roll over the desert and snow corners on Battle Day: you can't see far, and aircraft slow down.",
					"Shelled buildings come down: knock out the supports and everything above them falls."),
			new Page("Security & Moving Parts",
					"Keycard Doors open only for you (or whoever holds your keycard). A Keycard Reader powers redstone for a few seconds.",
					"Security Cameras show on a Monitor. Laser Tripwires, Seismic Sensors and Alarm Blocks warn you about intruders.",
					"The Ruby Bearing swings a drawbridge or gate; the Ruby Piston slides a wall, door or lift. Power them with redstone (or a Keycard Reader).",
					"Elevators: stand on one and jump to go up to the next; sneak to go down."),
			new Page("Coaster Park",
					"The Coaster Workshop (H, or Bolt in the hub) is Ruby Coaster Park: everyone gets their own spot on the main street, kept for good.",
					"Right-click the pieces in your hotbar to build; the paper turns the page. A blue preview shows where a piece would go.",
					"Pick a Piece (page 4) to change any piece: a piece tool swaps it, Undo takes it out, Edit mode: Insert puts one in before it.",
					"Name your coaster, set its laps, copy a friend's. Ride!: at a station it rides that coaster; anywhere else it lists every coaster to pick from. Friends can hop on the same train."),
			new Page("The Map",
					"An island, the same every season. The Ruby Crater sits in the middle, full of ruby ore close to the surface.",
					"A ring of mountains surrounds it, with a pass toward each corner. Four rivers run out to the sea between the corners.",
					"Each corner has a flat meadow for a base: Green Hills, Pine Woods, Red Desert and Snow Plateau.",
					"The world border hugs the island."),
			new Page("Controls",
					"Left-click fire, hold right-click to aim, R reload.",
					"B: ping a spot on your map, or spot the enemy under your crosshair.",
					"/duel guns|vehicles|planes <player>: a 1v1 test duel in the arena, first to 10 kills. You get your own gear back after.",
					"H: choose a game.  K: map.  U: hide/show the minimap.  J: guide, goals & upgrades.  I: profile.  Y: this manual.  V: voice chat (Simple Voice Chat).",
					"N: show or hide the how-to card for a game or vehicle.  Comma (in the hub): the park map, fly anywhere.  F9 (aircraft): full instruments.",
					"Knocked out on Battle Day: click to watch the next player, right-click the one before, crouch to fly free.",
					"In vehicles: W/S, A/D, Space (smoke / flares), Left Alt free look. Aircraft: R weapon, scroll zoom, T designate, X flares, Z autopilot, C weapon cam, G gear. Bombs: look at the target, release on IN REACH. Helicopter: WASD fly, Space/Ctrl up/down, the mouse aims."),
			new Page("Economy",
					"Generators and solar panels fill your power grid. A Ruby Drill keeps what it digs (open it to collect, or run a conveyor belt to a chest); "
							+ "the Ore Refinery doubles ore; the Trade Depot sells anything into your bank.",
					"The Vault holds rubies and pays interest. Shop purchases come out of your bank automatically.",
					"Upgrade machines Mk I to Mk V. Your Heart's tier sets how many drills you can own (1, then 2/4/6).",
					"A Vault blown open on Battle Day spills a quarter of its owner's bank."),
			new Page("Upgrades",
					"Advanced Armory gear starts locked. Press J: the GUIDE tab walks you through the season; the GOALS tab lists things to do, each worth 1 or 2 stars.",
					"Place your Heart, mine ruby, build machines, repel a raid, win a duel, drive and fly... Each goal counts once.",
					"Spend stars on the UPGRADES tab. You can't afford everything, so pick the upgrades that suit how you play."),
			new Page("Raids & Guards",
					"On build days 3, 6, 9 and 12 raiders attack your base. Each one at your Heart loots your bank until it dies.",
					"Kill them all for rubies (and a goal). Turrets fire on raiders even before Battle Day.",
					"/rubywars raid calls a practice raid on yourself (once a day).",
					"Guard Contracts hire a Rifleman or AT Gunner. Right-click a guard: follow / hold post. Sneak + right-click: pack up."),
			new Page("Gear",
					"Backpacks hold 27 or 54 slots. The Field Radio lets you talk across the map (needs Simple Voice Chat).",
					"Recon Drones mark enemies for you. Paint Kits respray vehicles.",
					"When you die your gear waits in a War Grave: only you can open it for 5 minutes.",
					"Blueprints copy a structure; hold one to see where a Constructor would build it.",
					"Jetpack (wear it): hold Space in the air to fly; it refuels on the ground.",
					"Holo Camo: right-click it with any block to disguise it. You walk through it; enemies hit a wall."));

	private int page;
	private final long opened = System.currentTimeMillis();
	private long turned = System.currentTimeMillis();

	public ManualScreen() {
		super(Component.literal("Field Manual"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int w() {
		return Math.min(360, width - 16);
	}

	private int h() {
		return Math.min(224, height - 16);
	}

	private int navW() {
		return 96;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		float in = Ui.ease(opened, 240);
		Ui.dim(g, width, height, in);
		int w = w();
		int h = h();
		int x = (width - w) / 2;
		int y = (height - h) / 2 + Math.round((1 - in) * 8);
		Ui.panel(g, x, y, w, h);
		Ui.heading(g, "Field Manual", x + 10, y + 9, Ui.TEXT, 1.0F, false);
		String count = (page + 1) + " of " + PAGES.size();
		Ui.text(g, count, x + w - 10 - font.width(count), y + 9, Ui.MUTED);
		// The pages, down the left.
		int nx = x + 6;
		int ny = y + 24;
		int rowH = Math.min(13, (h - 32) / PAGES.size());
		Ui.inset(g, nx, ny - 2, navW(), PAGES.size() * rowH + 4);
		for (int i = 0; i < PAGES.size(); i++) {
			int ty = ny + i * rowH;
			boolean on = i == page;
			boolean over = Ui.inside(mouseX, mouseY, nx + 2, ty, navW() - 4, rowH);
			float hv = Ui.hover("manual:" + i, over && !on);
			if (on) {
				g.fill(nx + 2, ty, nx + navW() - 2, ty + rowH - 1, 0x40E0294A);
				g.fill(nx + 2, ty, nx + 4, ty + rowH - 1, Ui.RUBY);
			} else if (hv > 0) {
				g.fill(nx + 2, ty, nx + navW() - 2, ty + rowH - 1, Ui.alpha(0x20FFFFFF, hv));
			}
			Ui.text(g, PAGES.get(i).title(), nx + 8, ty + (rowH - 8) / 2, navW() - 12, on ? Ui.TEXT : Ui.mix(Ui.MUTED, Ui.DIM, hv));
		}
		// The page.
		Page p = PAGES.get(page);
		float turn = Ui.ease(turned, 180);
		int tx = x + navW() + 16;
		int ty = y + 26;
		int tw = x + w - 10 - tx;
		Ui.heading(g, p.title(), tx + Math.round((1 - turn) * 6), ty, Ui.alpha(Ui.GOLD, turn), 1.0F, false);
		ty += 15;
		for (String line : p.lines()) {
			for (FormattedCharSequence seq : font.split(Component.literal(line), tw)) {
				if (ty > y + h - 12) {
					break;
				}
				g.text(font, seq, tx, ty, Ui.alpha(Ui.DIM, turn), false);
				ty += 10;
			}
			ty += 4;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		int w = w();
		int h = h();
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		int rowH = Math.min(13, (h - 32) / PAGES.size());
		for (int i = 0; i < PAGES.size(); i++) {
			int ty = y + 24 + i * rowH;
			if (Ui.inside(event.x(), event.y(), x + 8, ty, navW() - 4, rowH)) {
				if (page != i) {
					page = i;
					turned = System.currentTimeMillis();
				}
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
