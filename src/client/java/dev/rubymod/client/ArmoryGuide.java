package dev.rubymod.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What everything in the Armory is for, in plain words for someone who's never played: what it does, and what it
 * works with (shown as little pictures under it). Keyed by item id ("rubymod:sentry_turret"); "heart_upgrade" is the
 * Heart upgrade card. Anything missing here falls back to the item's own tooltip.
 */
public final class ArmoryGuide {
	private ArmoryGuide() {
	}

	/** {@code works} are item ids: "rubymod:x" or "minecraft:x" ("x" alone means rubymod). */
	public record Entry(String text, List<String> works) {
	}

	private static final Map<String, Entry> ALL = new HashMap<>();

	private static void add(String id, String text, String... works) {
		ALL.put(id.contains(":") || id.equals("heart_upgrade") ? id : "rubymod:" + id, new Entry(text, List.of(works)));
	}

	public static Entry get(String id) {
		return ALL.get(id);
	}

	static {
		// ----- Your Heart -----
		add("ruby_heart", "Put it down to claim your base: the land round it is yours (its edge shows), and on Battle Day you come back here while it stands.");
		add("heart_upgrade", "Makes your Ruby Heart stronger: a bigger base, more health, and room for more Ruby Drills. Each tier costs more.",
				"ruby_heart", "ruby_drill");
		add("armory", "Another shop just like this one. Put it anywhere in your base, so you never have to walk back to the first.");

		// ----- Guns -----
		add("service_pistol", "A cheap backup gun: weak, but quick to fire and reload. Good while you save up for something better.",
				"light_ammo", "red_dot_sight", "extended_mag");
		add("arr_rifle", "An automatic rifle: hold the trigger down. The best all-round gun for most fights.",
				"heavy_ammo", "extended_mag", "vertical_grip", "red_dot_sight");
		add("combat_shotgun", "Fires a spread of pellets: huge damage up close, very little far away. It also smashes doors and glass it hits.",
				"shotgun_shell", "vertical_grip", "extended_mag");
		add("longshot_sniper", "Hits very hard from very far away. Hold right-click to look down the scope. Players you aim at can see its glint.",
				"sniper_round", "suppressor", "red_dot_sight");
		add("ruby_blaster", "An automatic energy gun. Its bolts bounce once off walls, so you can hit someone round a corner.",
				"ruby_cell", "extended_mag", "vertical_grip");
		add("flamethrower", "Hold right-click to spray fire a short way. Whatever it touches catches fire. Twenty seconds of flame, and it refills by itself in about a minute.");
		add("extended_mag", "Fits on a gun: half as many bullets again before you have to reload. Hold it with the gun in your other hand and right-click.",
				"arr_rifle", "service_pistol", "ruby_blaster");
		add("red_dot_sight", "Fits on a gun: tighter shots and more zoom when you aim. Hold it with the gun in your other hand and right-click.",
				"arr_rifle", "service_pistol", "longshot_sniper");
		add("vertical_grip", "Fits on a gun: 40% less kick when you fire, so you stay on target. Hold it with the gun in your other hand and right-click.",
				"arr_rifle", "combat_shotgun", "ruby_blaster");
		add("suppressor", "Fits on a gun: quiet shots with no tracer, and no sniper glint to give you away. Hold it with the gun in your other hand and right-click.",
				"longshot_sniper", "arr_rifle", "service_pistol");

		// ----- Ammo -----
		add("light_ammo", "Bullets for the Service Pistol. An Ammo Factory can make more.", "service_pistol", "ammo_factory");
		add("heavy_ammo", "Bullets for the rifle. Also loads your Sentry Turrets, Flak Cannons and the Heavy AA Gun (right-click them with it).",
				"arr_rifle", "sentry_turret", "flak_cannon", "heavy_aa_gun");
		add("shotgun_shell", "Shells for the Combat Shotgun.", "combat_shotgun");
		add("sniper_round", "Rounds for the Longshot Sniper.", "longshot_sniper");
		add("ruby_cell", "Energy cells for the Ruby Blaster.", "ruby_blaster");
		add("rocket", "Rockets for the Rocket Launcher.", "rocket_launcher");
		add("aa_missile", "Missiles for the Skyhammer: they chase aircraft.", "skyhammer");
		add("at_missile", "Missiles for the Lancer: they hit tanks and other vehicles.", "lancer");
		add("mortar_shell", "Shells for the Mortar. Right-click the Mortar holding them to fire at your marked target.", "mortar", "rangefinder");

		// ----- Explosives -----
		add("frag_grenade", "Hold right-click to wind up, let go to throw (longer for farther): it bounces, then explodes after 2.5 seconds.");
		add("smoke_grenade", "Hold right-click to wind up, let go to throw: after 2.5 seconds it makes a thick cloud of smoke to hide in, or to cross open ground.");
		add("fire_bomb", "Hold right-click to wind up, let go to throw: it shatters where it lands and sets everything round it on fire.");
		add("rocket_launcher", "Left-click to fire a rocket. Keep aiming (hold right-click) and the rocket follows your crosshair. Good against vehicles and walls.",
				"rocket");
		add("lancer", "Anti-tank launcher: hold right-click to lock on to a vehicle, then left-click to fire. A heavy warhead.", "at_missile");
		add("skyhammer", "Anti-aircraft launcher: hold right-click to lock on to a plane or helicopter, then left-click to fire. Flares can fool it.",
				"aa_missile");
		add("breaching_charge", "Right-click a block to stick it there: it blows 5 seconds later. The hardest hit on an enemy's Ruby Heart (anything wears one down, this the fastest).",
				"ruby_heart");
		add("c4", "Right-click to throw a sticky charge: it sticks to walls and vehicles. Crouch and right-click to set off all of yours at once. Throw your last one and you're left holding a Detonator to set them off.");
		add("mortar", "Place it in your base. Mark a target with the Rangefinder, then right-click the Mortar holding Mortar Shells: it lobs them there.",
				"mortar_shell", "rangefinder");
		add("rangefinder", "Hold right-click to look through it, zoomed in; let go to mark the spot in the middle (up to 400 blocks away) as the target for your Mortar and Missile Silo. The target stays on your map.",
				"mortar", "missile_silo");
		add("missile_silo", "Mark a target with the Rangefinder, then right-click the silo holding a Cruise Missile: it flies high across the map and dives on it.",
				"cruise_missile", "rangefinder");
		add("cruise_missile", "Fired from a Missile Silo at the spot you marked. It breaks even concrete walls at once.", "missile_silo", "rangefinder");
		add("air_strike_flare", "Hold right-click to wind up, let go to throw: ten seconds after it lands, a plane drops five bombs in a line through it, the way you threw it.");

		// ----- Defenses -----
		add("sandbags", "Cheap cover to hide behind: soaks up bullets, but one blast knocks it down.");
		add("concrete_wall", "A tough wall block: takes 4 blasts. Cruise missiles break it at once.", "reinforced_concrete");
		add("reinforced_concrete", "The toughest wall: takes 6 blasts, and grenades can't hurt it at all. Cruise missiles break it at once.", "concrete_wall");
		add("reinforced_glass", "Bulletproof glass: see out, and stop small arms. Takes 2 blasts.");
		add("blast_door", "A heavy door that only you (and players you trust, before Battle Day) can open.");
		add("barbed_wire", "Slows down anyone who walks through it. Put it outside your walls.");
		add("holo_camo", "Copies the look of any block. You can walk through it; enemies just see a wall.");
		add("camo_net", "Hides what's under it from aircraft flying over.");
		add("landmine", "Hide it in the ground: it goes off when an enemy steps on it. Only you and your squad can see it; to enemies it's buried out of sight.");
		add("claymore", "Goes off when an enemy walks in front of it.");
		add("at_mine", "Blows up enemy vehicles that drive over it. Hidden from enemies, like a Landmine.", "tank", "jeep");
		add("sentry_turret", "Shoots monsters, and enemy players from Open War on. Load it with Heavy Ammo (right-click it, or run a belt of it in). It tells you when it runs low. Needs no power.",
				"heavy_ammo", "practice_dummy");
		add("tesla_coil", "Zaps monsters, and enemies inside your base on Battle Day. Runs on power from your generators.",
				"combustion_generator", "solar_panel", "battery");
		add("practice_dummy", "Put it up in your base for a minute before Battle Day: your Sentry Turrets and Tesla Coils fire on it, and you're told what hit it. Keep it and use it again.",
				"sentry_turret", "tesla_coil");

		// ----- Air defense -----
		add("heavy_aa_gun", "A twin anti-aircraft gun you sit in and aim yourself. Its shells burst near aircraft. It reloads from the Heavy Ammo you carry.",
				"heavy_ammo");
		add("railgun", "A big gun you sit in: each shot crosses 400 blocks at once and goes through up to three targets. Each shot uses a lot of power.",
				"battery", "combustion_generator", "solar_panel");
		add("flak_cannon", "Fires bursting shells at enemy aircraft within 80 blocks, all by itself. Load it with Heavy Ammo (by hand or by belt); it tells you when it runs low.", "heavy_ammo");
		add("sam_site", "Fires missiles at enemy aircraft within 180 blocks, all by itself. Reload it with rubies (right-click it).", "ruby");
		add("point_defense", "A fast automatic gun that shreds missiles, bombs and drones near it. Runs on power.", "battery", "combustion_generator");
		add("missile_interceptor", "Shoots down missiles and shells headed for your base. Recharge it with rubies (right-click it).", "ruby");
		add("shield_generator", "Puts a dome over your base that destroys rockets, shells and bombs coming in. Uses power for each one it stops.",
				"battery", "combustion_generator", "solar_panel");
		add("radar_station", "Shows enemies within 96 blocks of it on your screen, so you see them coming.");

		// ----- Vehicles -----
		add("jeep", "A fast four-seat car: W and S to drive, A and D to steer. Bullets barely scratch it; rockets and mines are what hurt it.",
				"paint_kit", "landing_craft");
		add("tank", "Slow, but heavily armored, with a big cannon (right-click with an empty hand to fire). Two seats.", "paint_kit");
		add("dirt_bike", "The fastest way across the map: one seat, and it rides like a real motorbike. W winds it up to speed, S brakes, it leans into turns, and Space pulls a wheelie. Bullets hurt it more.", "paint_kit");
		add("quad", "A quick two-seat four-wheeler, faster than the jeep. Bullets hurt it more.", "paint_kit");
		add("tunnel_borer", "A slow digging machine: hold W and its drill grinds out a tunnel 3 blocks wide and 3 high (harder rock takes longer). Dig under an enemy base (their Seismic Sensor can hear it).",
				"seismic_sensor");
		add("patrol_boat", "A fast four-seat boat for rivers and the coast. Right-click water to put it in.", "paint_kit");
		add("gunboat", "A boat with a deck gun its driver aims and fires (left-click). Right-click water to put it in.", "paint_kit");
		add("landing_craft", "Carries six players and a jeep, quad or bike. Space lowers its ramp: lower it on a beach and the vehicle drives off.",
				"jeep", "quad", "dirt_bike");

		// ----- Aircraft -----
		add("fighter_jet", "Fast and agile, with a cannon and missiles that chase other aircraft. Takes off from a long straight runway. Space drops flares.",
				"airfield_pad", "paint_kit");
		add("attack_jet", "Slow and tough, with a heavy cannon, guided missiles and a few bombs, for hitting things on the ground. Takes off from a runway.",
				"airfield_pad", "paint_kit");
		add("bomber", "Carries a huge load of bombs. Needs a very long runway (about 400 blocks) to get off the ground.", "airfield_pad", "paint_kit");
		add("attack_heli", "Takes off and lands anywhere. W A S D to fly, Space and Ctrl to go up and down. A cannon, rockets and guided missiles.",
				"airfield_pad", "paint_kit");
		add("airfield_pad", "Where your aircraft park and rearm.", "fighter_jet", "attack_jet", "bomber", "attack_heli");

		// ----- Machines -----
		add("combustion_generator", "Makes power by burning coal, charcoal, wood or lava. Your machines, Tesla Coils and shields run on power.",
				"battery", "ruby_drill", "minecraft:coal");
		add("solar_panel", "Makes free power while the sun shines on it (none at night).", "battery", "ruby_drill");
		add("battery", "Stores power, so your machines keep going at night, and when lots of them run at once.", "solar_panel", "combustion_generator");
		add("ruby_drill", "Digs rubies and ore by itself, and keeps them until you collect them (crouch and right-click it). Needs power.",
				"ore_silo", "conveyor", "ore_refinery", "solar_panel");
		add("ore_refinery", "Turns each raw ore into two ingots, cooks anything a furnace can, and makes gunpowder from coal and sand. Needs power.",
				"ruby_drill", "ammo_factory", "trade_depot");
		add("trade_depot", "Sells anything you put in it, and the rubies go straight to your bank. Ore and ingots pay best.",
				"ruby_drill", "ore_refinery", "conveyor");
		add("vault", "Your bank: put rubies in to keep them safe and earn a little interest each day. Raiders can crack it on Battle Day.", "ruby");
		add("ammo_factory", "Turns metal and gunpowder into ammo. Click its recipe to pick which kind it makes. Needs power.",
				"ore_refinery", "light_ammo", "heavy_ammo");
		add("prospector_pick", "Right-click: it chimes and sparkles toward the nearest Ruby Ore within 16 blocks. Crouch and right-click to look for other ore.",
				"ruby_ore");
		add("assembler", "Crafts what you pick: ingots into blocks, torches, rails, TNT and more. Belts can feed it. Needs power.",
				"ore_refinery", "conveyor");
		add("ore_silo", "Holds 128 stacks of one thing. A Ruby Drill touching it fills it by itself. Right-click with an item to store it.",
				"ruby_drill", "conveyor", "ruby_wrench");

		// ----- Moving things -----
		add("conveyor", "A belt that carries things the way it points, into whatever's at the end. Belts curve by themselves where one joins another.",
				"belt_loader", "ruby_drill", "minecraft:chest");
		add("belt_loader", "Takes things out of the chest, furnace or machine behind it and puts them on the belt in front. Right-click with an item to take only that.",
				"conveyor", "minecraft:chest");
		add("belt_sorter", "A belt piece that turns one kind of item off to the side. Right-click it with an item to choose which.", "conveyor");
		add("belt_ramp", "A belt that carries things up a level (right-click with an empty hand to make it carry them down).", "conveyor");
		add("belt_splitter", "A belt piece that shares what comes in between its left and its right.", "conveyor");
		add("belt_tunnel", "Place two facing the same way, up to 7 blocks apart: things go in one and come out the other, under walls and floors.",
				"conveyor");
		add("ruby_wrench", "Right-click a machine, belt or silo to turn it. Crouch and right-click to pick it up: it keeps its settings and what's in it.",
				"conveyor", "ore_silo", "ruby_drill");
		add("elevator", "Place one above another. Stand on one and jump to go up to the next; crouch to go down.");

		// ----- Building -----
		add("architect_wand", "Builds a wall, floor, room, round tower, dome or stairs in two clicks, from the block in your other hand. Crouch and right-click to change the shape.",
				"minecraft:stone_bricks", "concrete_wall");
		add("remodeler", "Swaps a whole 5x5x5 patch of one block for the block in your other hand: change a wall's material at once. Crouch to swap just one.",
				"concrete_wall", "reinforced_concrete");
		add("builders_wand", "Right-click a block to stretch it 16 blocks forward; crouch and right-click to build straight up. It never breaks: it mends itself.");
		add("excavator", "A pickaxe that digs a 3x3 hole at once: quick for rooms, basements and tunnels.");
		add("blueprint", "Right-click two opposite corners of a building (up to 24 blocks across) to copy it. Then put it in a Constructor to build a copy. Your Blueprints come with you to the next season.",
				"constructor");
		add("constructor", "Builds what's on a Blueprint, taking blocks from chests next to it. It can also repair your base where it was copied.",
				"blueprint", "minecraft:chest");
		add("ruby_bearing", "Spins the blocks stuck to its face when it gets a redstone signal: make turning doors, gates and drawbridges.",
				"keycard_reader", "minecraft:lever");
		add("ruby_piston", "Slides the blocks in front of it up to eight blocks when it gets a redstone signal: hidden walls, doors and lifts.",
				"keycard_reader", "minecraft:lever");
		add("floodlight", "A bright light for your base, so nobody sneaks up in the dark.");

		// ----- Security -----
		add("keycard_door", "A door that opens only for you, whoever carries your Keycard, and players you trust (not on Battle Day). Walk up and right-click it.", "keycard");
		add("keycard_reader", "Gives a redstone signal for a few seconds when someone with your Keycard uses it. Put it next to a Ruby Piston for a secret gate.",
				"keycard", "ruby_piston", "ruby_bearing");
		add("keycard", "Right-click it in your base to make it your base's card. Whoever carries it can open your Keycard Doors and Readers.",
				"keycard_door", "keycard_reader");
		add("security_camera", "Watches part of your base. Look through it on a Monitor.", "monitor");
		add("monitor", "Right-click to see what your Security Cameras see. Right-click again for the next camera, crouch to stop.", "security_camera");
		add("laser_tripwire", "A beam that gives a redstone signal when an enemy walks through it.", "alarm_block", "ruby_piston");
		add("seismic_sensor", "Feels digging and tunneling under your base, and warns you.", "tunnel_borer");
		add("alarm_block", "Sounds when someone steps into your base who shouldn't be there.");

		// ----- Gear -----
		add("field_pack", "A backpack with 27 more slots. Right-click to open it.");
		add("heavy_rucksack", "A big backpack with 54 more slots. Right-click to open it.");
		add("field_radio", "Talk to anyone on the same channel, anywhere on the map. Right-click to change channel. Needs Simple Voice Chat.");
		add("jetpack", "Wear it in your chest slot. Jump, then hold Space to fly; W A S D to steer; crouch in the air to float gently down. It keeps a little fuel back to soften your landing, and refills on the ground.");
		add("grappling_hook", "Right-click a block up to 40 blocks away and you're pulled up to it: cliffs, walls, mountains. Crouch to let go. It never breaks: it mends itself while you're not using it.");
		add("night_goggles", "Wear them on your head to see in the dark.");
		add("ruby_helmet", "Ruby armor: a little stronger than iron. Repair it with rubies.", "ruby_chestplate", "ruby_leggings", "ruby_boots");
		add("ruby_chestplate", "Ruby armor: a little stronger than iron. Repair it with rubies.", "ruby_helmet", "ruby_leggings", "ruby_boots");
		add("ruby_leggings", "Ruby armor: a little stronger than iron. Repair it with rubies.", "ruby_helmet", "ruby_chestplate", "ruby_boots");
		add("ruby_boots", "Ruby armor: a little stronger than iron. Repair it with rubies.", "ruby_helmet", "ruby_chestplate", "ruby_leggings");
		add("ruby_sword", "Hits a little harder than an iron sword, and lasts much longer.");
		add("ruby_pickaxe", "Mines everything an iron pickaxe can, a bit faster, and lasts much longer.", "excavator");

		// ----- Support -----
		add("guard_contract", "Hires a rifleman: right-click the ground to post them. They guard your base or follow you (riding along in your vehicle if there's a seat), and fight monsters (and enemy players once PvP is on).");
		add("at_guard_contract", "Hires an anti-tank gunner: right-click the ground to post them. They fire rockets at enemy vehicles and aircraft.");
		add("recon_drone", "Throw it up (right-click): it circles high over you for 90 seconds and marks every enemy within 56 blocks on your map and screen. Used up after.",
				"scout_drone");
		add("scout_drone", "A little drone you fly yourself for a minute: W A S D, Space and Shift. Enemies it sees glow for you. Right-click or Q brings it home to use again.",
				"recon_drone");
		add("paint_kit", "Right-click your vehicle or aircraft to repaint it: Woodland, Desert, Arctic, Urban, Night or Ruby. Crouch and right-click to go back one.", "jeep", "tank", "fighter_jet");
	}
}
