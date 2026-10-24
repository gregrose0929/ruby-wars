# Ruby Wars

A Fabric mod for Minecraft 26.3 that turns a server into a two-week season:
build a fortified base for 14 days, then survive one Battle Day of guns,
mortars, and cruise missiles. The last player standing wins.

Design doc: https://claude.ai/code/artifact/7412ee76-bb2f-47f6-8045-6372edb0be5d

## How a season works

1. **Lobby.** Players join. An admin runs `/rubywars start`.
2. **Build phase (14 days).** Everyone gets a Ruby Heart, a pistol, and some ammo.
   Place the Heart to claim the land around it (49x49 blocks at tier 1). Nobody
   else can build, break, or open things in your claim, PvP is off, and explosions
   can't damage claims. Mine Ruby Ore (rarer than diamonds, below Y 0), and spend
   rubies at an Armory.
3. **Battle Day.**
   - **Lockdown (15 min):** claims open, defenses switch on, PvP still off.
   - **Open War (60 min):** PvP on. Hearts can be destroyed with Breaching Charges.
     While your Heart stands you respawn at it; once it's gone, your next death
     eliminates you. Supply drops land every 15 minutes.
   - **Sudden Death (20 min):** every Heart shatters, no more respawns, and the
     world border shrinks to 200 blocks (from 1700 on the Ruby Wars map, 3000 elsewhere).
   - **Final Circle:** everyone glows and artillery shells random spots every
     15 seconds, so nobody can hide in a bunker. Last one alive wins.

During the build phase everyone also gets three **daily bounties** (land gun hits,
travel, mine stone or ores) that pay rubies. See them with `/rubywars bounties`.
Fire and lava can't spread into claims until Battle Day.

There are no hostile mobs and no hunger (`"hostileMobs"` and `"hunger"` in the
config turn them back on): the game is building and staying alive.

## The hub and game modes

On the server, everyone arrives in **the hub**: a floating plaza with a portal for each
game. Walk into one, press **H** anywhere for the game picker, or type `/hub` and
`/play rubywars|horde`.

- **Ruby Wars** (red portal) is the main game: the season.
- **Co-op Horde** (green portal) is a team game you can play any time (see below).
- **Party Games** (purple portal, in the middle) opens the game picker with 9 quick games.

Leaving Ruby Wars saves where you were and everything you carry (in
`world/rubywars/hub/`); going back puts it all back, so the hub and Horde never touch
your Ruby Wars things. Raids only come when someone from the base is actually in Ruby
Wars. On Battle Day, everyone still in the season is called back into Ruby Wars and
can't leave until it's over (eliminated players can go anywhere). Singleplayer worlds
skip the hub when you load them (H still works). Turn the hub off with `"hub": false`.

## Co-op Horde

A Ruby Wars base, built by everyone together, against a horde. The team gets an open
battlefield (rocks, trees, old ruins) with the **Ruby Core** in the middle and an
**Armory** next to it. You have 2 minutes to build before wave 1. Waves come from 1 to 3
directions (named in chat, with red flares in the sky just before), and get bigger and
nastier. If the Core is destroyed, it's game over.

- **Difficulty:** pick Easy, Normal or Hard on the Horde card (H) when you start a game,
  or click the choice in chat before wave 1 (`/play horde easy|normal|hard` works too).
- **It scales with the team.** Wave 1 is 6 enemies for one player, 8 for two, 13 for
  four and 18 for six (Normal); wave 5 is 16 solo and 50 for six. Enemies also get
  tougher per player (and per wave), Brutes much more so, and more fight at once in a
  bigger game. Someone joining or leaving mid-wave changes the rest of that wave.
- **The team bank.** Every kill puts rubies in one bank the whole team shares (bigger
  enemies pay more; the gunship pays most), and every cleared wave adds a bonus. The
  team starts with 200. The bank shows top left.
- **The Armory.** Right-click the Armory by the Core (or click [ARMORY] in chat, or
  `/horde shop`) to spend the bank. Tabs: Weapons (rifle, shotgun, sword, grenades,
  then sniper, C4, rockets, Ruby Blaster, Skyhammer, Lancer, air strikes), Ammo (plus
  ruby armor, a Rangefinder and mortar shells, a jetpack), Build (sandbags, planks,
  ladders, concrete walls, barbed wire, floodlights, glass, a blast door, reinforced
  concrete), Defenses (Sentry Turrets, landmines, claymores, Tesla Coils, a mortar,
  anti-tank mines, flak, a SAM site, point defense, a shield generator), Power (solar
  panels, a generator and coal, batteries) and Support (repair the Core, hire a guard,
  make the Core tougher, a jeep, an AT guard, a tank). New things unlock as waves are
  cleared; the shop shows when. Things from Ruby Wars that don't make sense here
  (drills, vaults, missiles, claims) aren't sold.
- **Build a base.** Monsters smash through walls in their way, so stronger walls hold
  longer (sandbags < glass < concrete < blast doors < reinforced concrete), and
  Sappers blow holes in them. Mines, claymores, barbed wire, turrets and Tesla Coils
  only go after the horde. Machines run on the team's one power grid: build solar
  panels or a generator to power Tesla Coils and the like. Mortars fire on a spot
  marked with a Rangefinder. Each game starts on a fresh battlefield.
- **Between waves:** full health, then 90 seconds to build and shop. When everyone
  clicks "I'm ready" (in the Armory or chat, or `/horde ready`), the next wave starts
  in 5 seconds.
- **Revive your teammates.** If someone goes down, crouch next to them for 3 seconds to
  bring them back; otherwise they're back at the next wave. Everyone has one **Second
  Wind** per game: when the whole team is down, someone gets back up (so solo players
  get one extra life). It's game over when the team is down for good or the Core is
  destroyed, and everyone goes back to the hub.
- **Enemies:** zombies, husks and spiders first; then Runners (fast little zombies),
  skeletons, Sappers (creepers that blow up walls and the Core), soldiers, Shield
  Soldiers (shoot them from the side), Juggernauts (armored, slow, very tough) and AT
  gunners.
- **Special waves:** every 5th wave brings Brutes (ravagers), waves 4, 9, 14... are fog
  waves (darkness closes in, but enemies glow), and waves 7, 12, 17... are air raids:
  an enemy gunship circles the Core: shoot it down with flak, a SAM site or Skyhammers
  (if the team has none, everyone gets a Skyhammer).
- Join mid-game any time: you get the starting kit, and the team bank is yours too.
  The best wave (and who reached it) shows on the hub sign. Reaching wave 5 completes
  the "Reach wave 5 in Co-op Horde" goal (a star for Ruby Wars). Nothing else from Horde
  carries over.

## Profile, cosmetics and leaderboards

Press **I** for your profile: lifetime stats across every mode (party game wins and
kills, Horde best wave, Sky Race best time, duels and seasons won) and your cosmetics.
Cosmetics never change how anything plays; they're earned by playing:

- **Hats** (float above your head): Floating Ruby (win any party game), Cake Hat (play
  10), Pumpkin Head (win Hide & Seek), Mini Tank (win Tank Battle), Diamond (win 10),
  Ruby Heart (Horde wave 10), Golden Crown (win a Ruby Wars season).
- **Trails** (when you move): Ruby Sparkles (everyone), Hearts (revive someone in Horde),
  Snowflakes (Horde wave 5), Clouds (finish a Sky Race), Music Notes (win Build Battle),
  Flames (25 party kills), Magic (win 3 duels).
- **Kill effects**: Confetti (everyone), Heart Burst (50 party kills), Lightning (win Gun
  Game), Ruby Explosion (win Ruby Rush).

Click one on the profile to wear it, click again to take it off. They're hidden while
you're hiding in Hide & Seek, invisible or watching. The hub has two leaderboards: Party
Champions (most wins) and Records (Horde waves, Sky Race times, most party kills). Stats
live in `world/rubywars/stats.json` and last across seasons.

## Party games

Nine quick, round-based games from the hub's purple portal or the H picker
(`/play <id>`). Rounds loop: a 15-second lobby, the round, 8 seconds of results, then
the next round, until everyone leaves. Each has its own arena, rebuilt between rounds
a little at a time (so the server never freezes); every arena is also built in the
background when the server starts. Your own things stay saved in the hub's save.
Different people can play different games at the same time. If a game ever hits a bug,
its players are sent back to the hub and the error is logged; the server keeps running.

AI bots fill in where a game supports them. They strafe, duck into cover to patch up
when they're hurt, and use the right weapon for the job: in Gun Game they climb the
weapon ladder too (and can win), in Capture the Flag they join both teams (half attack
and carry flags, half defend), in King of the Hill they fight for the zone, and in Ruby
Rush they land 15 seconds in with a random gun.

| Game | `/play` | Players | What it is |
| --- | --- | --- | --- |
| Gun Game | `gungame` | 1+ (bots fill in) | Every kill swaps your weapon for the next: pistol, shotgun, rifle, sniper, Ruby Blaster, rockets, sword. Win with a sword kill; get knifed and you drop a level. |
| Holo Hide & Seek | `hideseek` | 2+ | Hiders right-click any block to turn into it, and lock in place by standing still for 2 seconds. Seekers wait 40 seconds, then one hit finds a hider (who then seeks too). Hiders chirp every 30 seconds. 3.5 minutes. |
| Infection | `infection` | 2+ | One zombie (fast, strong) at the start; anyone caught becomes a zombie. Survivors have a pistol and shotgun and must last 4 minutes. Wild zombies wander in. |
| Capture the Flag | `ctf` | 1+ (teams, bots fill in) | Red vs Blue. 20 seconds to build sandbag defenses, then grab their flag and bring it to yours. Dropped flags: your team walks over it to send it home. First to 3. |
| King of the Hill | `koth` | 1+ (bots fill in) | Score a point a second while you're the only one in the gold zone; it moves every minute. First to 75. |
| Tank Battle | `tanks` | 2+ (teams) | Red vs Blue in tanks on the vehicle arena; a fresh tank 5 seconds after you're knocked out. First team to 10. |
| Sky Race | `skyrace` | 1+ | Jetpack through 12 rings in the sky, twice around. Land on the glowing pads to refuel. The record time is kept. |
| Build Battle | `build` | 2+ | Same theme for everyone, 5 minutes in creative on your own plot (blocks and wands only), then rate every build 1 to 5 stars. |
| Ruby Rush | `rush` | 1+ (bots fill in) | A battle royale on Rush Island (a quarter-size copy of the Ruby Wars Map in its own world). Float down, loot crates, stay inside the closing red zone. No respawns; last one standing wins. |

## The Ruby Wars map

A dedicated island, the same every season (it doesn't depend on the world seed), so
everyone can learn it. About 1,500 blocks across, with the world border just past the beaches:

- **The Ruby Crater** in the middle: a rim and a bowl with ruby ore a few blocks under the
  floor. World spawn. Worth fighting over.
- **A ring of mountains** around it, with a pass on each diagonal toward a corner.
- **Four home corners**, each with a flat meadow ~470 blocks from the middle: Green Hills,
  Pine Woods, Red Desert and Snow Plateau. Every corner is the same land turned 90°, so
  nobody gets a better start.
- **Four rivers** wind from the crater to the sea between the corners, shallow enough to
  wade, with fords. Beaches and ocean all round.

Pick it when creating a world (*World Type: Ruby Wars Map*), or on a server set
`level-type=rubymod\:ruby_wars` in `server.properties` before the world is first made
(`rubywars newmap` on the server does this for you, keeping a backup of the old world).
Other world types still work; the border then uses `borderStartSize`.

All timings, prices, and sizes are in `config/rubywars.json`.

## Controls

| Action | How |
| --- | --- |
| Fire a gun or launcher | Left-click (hold for automatic guns) |
| Aim down sights / lock on | Hold right-click (the sniper shows a full scope; lock-on launchers build a lock) |
| Reload | R (rebindable under Controls > Ruby Wars) |
| Throw grenade / flare / C4 | Right-click (sneak + right-click with C4 detonates your charges) |
| Mark a target for mortars and missiles | Right-click with a Rangefinder |
| Fire a mortar / launch a missile | Right-click the Mortar or Missile Silo holding shells or a missile |
| Load a turret or flak cannon | Right-click it with Heavy Ammo |
| Recharge an interceptor or SAM site | Right-click it with a Ruby |
| Deploy a vehicle | Right-click the ground with a Jeep, Tank, aircraft or helicopter item |
| Get in / drive / get out | Right-click the vehicle / W-S drive, A-D steer / Sneak |
| Tank | Look to turn the turret, left-click fires, hold right-click for the gunner sight, Space pops smoke |
| Look around in any vehicle | Hold Left Alt (free look; the vehicle keeps its course) |
| Taxi an aircraft | W/S throttle (S at idle throttle brakes), A/D steer the nose wheel |
| Take off | Line up on a runway, full throttle, and when the HUD says ROTATE, look up |
| Fly | Look where you want to go. Climbing costs speed, diving gains it; too slow and you stall |
| Aircraft weapons | Left-click fires, R switches weapon, scroll wheel zooms the view (1x-8x), X drops flares (Space too, in jets), T designates a target, Z autopilot, C weapon camera, G landing gear, Sneak ejects (parachute) |
| Helicopter | W/S fly forward and back, A/D strafe, Space climbs, Ctrl descends, the mouse turns and aims (the chin gun and rockets hit what's under the crosshair); no keys = hover |
| Ping / spot | B: mark the spot you're looking at for your squad, or spot the enemy under your crosshair (10 s) |
| Map | K: the full map (scroll to zoom, drag to pan). The minimap is always top right |
| Goals & upgrades | J: goals that earn stars, and upgrades to spend them on |
| Squad | O: your squad, members and one-click invites |
| Minimap | U: hide or show it |
| Field Manual | Y: short in-game guide pages on everything here |
| Choose a game | H: Ruby Wars, Co-op Horde, party games, or back to the hub |
| Profile | I: your stats and cosmetics (P is Minecraft's own player list) |

In any vehicle the view is truly first person: no hands, no held item, and the
hotbar and hearts make way for the vehicle's HUD.

## What's in the Armory

Guns: Service Pistol, AR-R Rifle, Combat Shotgun, Longshot Sniper, and the Ruby
Blaster. Throwables: frag and smoke grenades. Raid gear: Rocket
Launcher, Rangefinder, Breaching Charge, Mortar, Missile Silo, Cruise Missile.
Defenses: Holo Camo (looks like any block you give it; your squad walks through), Sandbags, Concrete Wall (takes 4 blasts), Reinforced Concrete (ignores
grenades), Blast Door (only you can open it), Sentry Turret, Missile Interceptor,
Radar Station, Alarm Block, Claymore, Landmine, Barbed Wire. Tools: Builder's
Wand, Excavator Pickaxe, and Heart upgrades (bigger claim, more HP). Gear: the
Jetpack (wear it in the chest slot; hold Space in the air to fly, refuels on the ground).

Ammo can also be crafted (copper, iron, and gunpowder; check the recipe book).
Killing 5 players in a row earns an Air Strike Flare.

**Gun attachments** (Armory, row 5): Extended Mag (+50% magazine), Red Dot Sight
(tighter spread, more zoom), Vertical Grip (-40% recoil), and Suppressor (quiet,
no tracers, hides the sniper glint). Hold the attachment in your main hand and the gun
in your off hand, then right-click. Each gun takes one of each.

The Armory has three pages: **Weapons & Base**, **Vehicles & Air Defense**, and
**Industry & Building** (the arrows in the bottom corners switch pages). Purchases
use the rubies you carry first, then your bank.

Vehicles: the **Jeep** (fast, 4 seats) and the **Tank** (slow, 2 seats, a turret
that follows your aim, main cannon, 3 smoke charges). Ruby armor, sword, and
pickaxe sit between iron and diamond.

## Economy and industry (the build phase)

The two weeks of building run on one loop: **build machines, make stuff, sell it
for rubies, upgrade the machines, repeat.** It borrows from tycoon "gen" servers
(place a generator, sell, upgrade), Clash of Clans (collectors that fill up while
you're away, a bank raiders want), and GregTech/Mekanism (tiered machines, ore
processing that multiplies output), but kept small: one currency, one power
number, no cables.

**Power ("charge").** All of your machines share one power grid wherever they
are. Generators add charge, machines draw it, Battery Banks raise how much it
holds. The gauge on every machine screen (and the panel under your Heart HP)
shows charge, capacity, and +/- per second.

| Machine | What it does |
| --- | --- |
| Combustion Generator | Burns coal, charcoal, wood, blaze rods or lava: 60-140 charge/s |
| Solar Panel | 20-100 charge/s in sunlight under open sky |
| Battery Bank | +20,000 to +100,000 grid capacity |
| Ruby Drill | Mines ore by itself (stone, coal, iron, copper, gold, redstone, lapis, diamonds and rubies). Below Y 0 it finds twice the rubies. Keeps digging at half speed while its chunk is unloaded (up to a day) |
| Ore Refinery | Raw ore in, 2 ingots out (3 at Mk IV); ruby ore gives rubies; cobble to gravel to sand |
| Ammo Factory | Metal + gunpowder in, ammo out: light, heavy, shells, sniper rounds, rockets, mortar shells |
| Trade Depot | Sells anything it accepts; the rubies go straight into your bank |
| Ruby Vault | Your bank: drop rubies in, withdraw with the buttons. Pays 2-6% interest per Minecraft day (capped). On Battle Day, a Vault blown open spills a quarter of its owner's bank |
| Shield Generator | A dome (12-28 blocks) that destroys incoming rockets, shells and bombs; 1,500 charge per hit (6,000 for a cruise missile) |
| Point Defense Gun | An automatic gatling that shoots down missiles, bombs and drones within 32-64 blocks |
| Constructor | Builds a Blueprint, block by block, from chests next to it |
| Tesla Coil | Arcs lightning at hostile mobs, and at enemies in your claim on Battle Day; 400 charge per zap |

**Limits.** You can own one Ruby Drill without a Heart, then 2, 4 or 6 as you
upgrade your Heart: the Heart upgrades are what grow your economy. Trade Depot
prices and the drill limits are in `config/rubywars.json`
(`tradeDepotPriceMultiplier`, `drillsPerHeartTier`).

**Upgrades.** Every machine goes from Mk I to Mk V. Click Upgrade on its screen:
each tier costs twice the last, paid from your inventory, then your bank. A machine
you pick up keeps its tier.

**Automation.** Machines push their output into the machine or conveyor next to
them first, and into chests after that, and they accept hoppers on every side. A
Drill next to a Refinery next to a Trade Depot is a complete ore-to-rubies line.
**Conveyor Belts** carry items (and people) and feed whatever is at their end.

## Building tools

| Tool | How it works |
| --- | --- |
| **Architect's Wand** | Right-click two corners to build a line, wall, floor, solid box or hollow room (sneak + right-click switches shape) out of the block in your off hand. A live outline shows exactly what you'll get. R undoes the last build and gives the blocks back |
| **Remodeler** | Right-click a block to swap it and the connected blocks of the same kind (a 5x5x5 patch) for your off-hand block; sneak for just one. Turns a cobblestone wall into reinforced concrete in a few clicks |
| **Blueprint** + **Constructor** | Right-click two corners to copy a structure (up to 24x24x24). Put the Blueprint in a Constructor, fill the chests next to it, and it builds a copy in front of itself, rotated the way it faces, with a ruby outline of where it'll go |
| Builder's Wand | Extends a block 16 blocks in a line |
| Excavator | Mines 3x3 |

New building blocks: **Reinforced Glass** (a bulletproof window, about 30 rifle
rounds or two blasts), **Floodlight**, **Conveyor Belt**, **Elevator** (stand on
it and jump to ride up to the next Elevator above, up to 48 blocks; sneak to go
down) and **Camo Netting** (see-through, walk-through cover for gun pits and
vehicles).

`/rubywars leaderboard` ranks everyone by the rubies their economy has earned.

## Heavy AA Gun and Railgun

A manned twin autocannon for your base. Right-click to man it; the mount swings
smoothly to follow your view and the shells go exactly where your crosshair is.
Left-click fires (alternating barrels), hold right-click for the
gun sight. Shells are proximity-fused flak: they burst near aircraft out to about
300 blocks. The sight boxes every aircraft in range and shows a lead marker: put
the reticle on it (it turns red: FIRE). The 40-round magazine reloads from 4 Heavy
Ammo.

The **Railgun Emplacement** is the anti-armor counterpart: each shot draws 4,000
charge from your power grid and takes 3 seconds to recharge. The slug crosses 400
blocks instantly, punches through up to three targets (90 anti-armor: more than
half a tank, or any jet) and bursts on whatever stops it.

## Fighting vehicles and aircraft

Bullets don't hurt tanks and barely scratch jeeps and aircraft; ordinary blast
splash does a little. Weapons made for it hit vehicles hard. Against a tank
(160 armor; hits from behind or above do 1.5x):

| Weapon | Damage | Notes |
| --- | --- | --- |
| Rocket Launcher | 48 | Laser-guided: keep aiming at the target |
| Tank shell | 56 | |
| **Lancer** (anti-tank launcher) | 80, big blast | Hold right-click to lock a vehicle or anything on the ground, left-click to fire. Chases its target like the Skyhammer, a little slower |
| **Skyhammer** (anti-air launcher) | 100 | Locks aircraft in flight; fast, leads its target. Flares can fool it |
| **Sticky C4** | 110 | Throw it onto a vehicle; sneak + right-click with C4 to detonate |
| Landmine / **Anti-Tank Mine** | 80 / 130 | The AT mine ignores people and fires under driven vehicles |
| Guided missile (aircraft) / bomb | 80, big blast / 90 | |
| Cruise missile | 250 | |

Damaged vehicles smoke below half armor and burn below a quarter; destroyed ones
leave a burning wreck. Shooters get a hit marker and a "HIT - Tank 40%" readout.

Counters: tanks have **smoke launchers** (Space, 3 charges): while a vehicle is in
smoke, missiles lose their lock and launchers can't lock it. Aircraft have
**flares** (Space). Bases can field a **Flak Cannon** (bursting shells at enemy
aircraft within 80 blocks; load with Heavy Ammo) and a **SAM Site** (radar-guided
missiles out to 180 blocks; reload with rubies). Both only engage enemies of the
claim on Battle Day. An **Airfield Pad** rearms and repairs any aircraft parked
and stopped within 12 blocks.

## Aircraft

Four aircraft, flown in the style of Nuclear Option. The jets have to take off
from a runway (or a long, flat road): taxi out, roll until the HUD says ROTATE,
then pull up. The takeoff roll takes 10-20 seconds.

| Aircraft | Top speed | Takeoff | Weapons |
| --- | --- | --- | --- |
| **Fighter Jet** | 200 km/h | 127 km/h, ~160-210 blocks | Fast-firing cannon, 4 air-to-air missiles, 8 flares |
| **Attack Jet** | 173 km/h | 109 km/h | Heavy cannon (best against armor), 6 guided missiles, 4 guided bombs, 6 flares |
| **Bomber** | 150 km/h | 104 km/h, ~260 blocks | 16 guided bombs, 10 flares |
| **Attack Helicopter** | 158 km/h | Vertical, anywhere | Chin cannon, 24 rockets, 4 guided missiles, 6 flares |

**Crashes.** Touching down faster than about 6 m/s sink, nose-first, or with the
gear up damages the aircraft; a hard impact or flying into terrain destroys it.
If you were shot up in the last 10 seconds, the shooter gets the kill. Gear up (G)
makes a jet a little faster.

**Pilot HUD**: speed and altitude tapes, radar altitude, climb rate, compass,
pitch ladder, flight path marker, throttle, hull, gear, weapons, and warnings
(STALL, PULL UP, MISSILE). Landing aid: with the gear down and descending, a
TOUCHDOWN marker shows where you'll land, plus glide-slope and sink-rate cues.

**Targeting** (works for every weapon):
- **T designates** whatever is under your crosshair (a vehicle, aircraft, player or
  ground point, up to 1500 blocks). Missiles and bombs go for it; an arrow points
  to it when it's off screen. **Bombs are guided**: drop them anywhere near and
  they steer onto the designated target.
- **Missile seeker**: keep a target in the circle until the box closes and turns
  red (LOCK). Without a lock or designation, a guided missile flies to the point
  under your crosshair (amber diamond).
- **Cannon**: rounds take time to arrive and drop slightly. On a target, a lead
  circle shows where to put the pipper (it turns red: SHOOT).
- **Bombs**: just look at the target. A diamond marks the aim point with its
  range, and the line under it says when to let go: **IN REACH - RELEASE** (with the
  fall time), HOLD while it's still ahead, or OUT OF REACH once you've passed it.
  Released bombs glide onto the aimed point (or the designated target). The red
  CCIP pipper still shows where an unguided drop would land.
- **Z: autopilot** holds your heading and altitude and frees your view, so you can
  look around (even straight down) to aim missiles and bombs while the plane flies
  itself. Z again (or landing) hands control back.
- **C: weapon camera**: ride along with your last missile or bomb to impact (your
  aircraft holds its course meanwhile). C again to come back.

## Long range

Minecraft only draws things within your render distance, and freezes things
outside the server's simulation distance. Ruby Wars works around both:
- Missiles, bombs, shells and pilotless aircraft keep the chunks around them
  loaded, so they keep flying when nobody is near.
- Pilots, drivers and players holding a lock-on launcher get **radar contacts**
  for aircraft and vehicles up to 1500 blocks away, drawn on the HUD with type and
  range. Seekers and designation can lock contacts beyond render distance.
- The sniper reaches 512 blocks.
- Recommended server settings: `view-distance=16` and `simulation-distance=10`
  in `server.properties`. Jets outrun world generation, so pre-generate the map
  with the Chunky mod (`/chunky radius 1500`, then `/chunky start`).

## Block damage

Bullets chip away at blocks, scaled by gun damage: dirt takes about 3 pistol
rounds, stone 7, planks 9. Cracks show the damage, and it heals if a block isn't
shot for 10 seconds. Obsidian, Reinforced Concrete, and other very hard blocks
ignore bullets, and claims are protected exactly as they are from explosions.

## Training world

`dist/Ruby Wars Training/` is a ready-made singleplayer world (creative, cheats on).
Copy it into `.minecraft/saves/`. It has:

- a **gun range** with chests of every gun, ammo, and attachment, target dummies at
  10/25/50/80 m (they show each hit's damage and headshots), and walls of dirt,
  planks, cobblestone, stone brick, concrete, and reinforced concrete to shoot;
- a **vehicle yard** with jeeps, tanks, obstacles, a bunker, targets, and an
  anti-tank mine lane;
- an **airfield**: a 600-block runway, every aircraft (and the helicopter) on the
  apron with an Airfield Pad, a bombing range with ground targets, target drones
  circling overhead (they come back 30 seconds after you shoot them down), and an
  **air defense range** with a Flak Cannon and SAM Site to practice flares against;
- a **Launchers** chest at the gun range with the Skyhammer, Lancer, C4 and mines;
- an **Industry Yard** north of spawn: a powered production line (Drill, Refinery,
  conveyor, Trade Depot, Vault), an Ammo Factory, a Constructor building a copy of a
  pillbox from its Blueprint, a Shield Generator and Point Defense Gun, Heavy AA Guns
  at the airfield, and chests with every machine and building tool.

Machines placed by the training build are unowned and shared by everyone.

On any world, an admin can build the same thing at their feet with
`/rubywars training` (it flattens a 130 x 780 area). `/rubywars rearm` refills
and repairs the aircraft you're in.

## Combat details

- Guns are hitscan with **lag compensation**: the server checks your shot against
  where targets were on your screen (up to 300 ms back), so 100 ms of ping doesn't
  make you miss.
- After respawning at your Heart you get 4 seconds of **spawn protection**. It ends
  the moment you fire.
- A **kill feed** in the top-right shows who killed whom, with which weapon, and
  headshots.

## Guns up close

Guns are full 3D models (40 to 60 parts each, with painted rails, vents, stippled
grips, wood grain and markings) in your hands, in other players' hands, in your
inventory and on the ground. In first person you hold them with both arms, and:

- **Aim down sights** lines up the real sight with your eye: iron sights with a
  glowing front post, the red dot when one is fitted, and a proper scope with a
  mil-dot reticle on the sniper.
- **Firing** kicks the gun back, flashes the muzzle, cycles the slide, bolt or
  pump, and throws spent brass that tumbles away.
- **Reloading** tilts the gun, drops the old magazine and slots a new one in with
  your other hand, then works the charging handle (shotgun shells go in one at a
  time; launchers load a fresh rocket).
- Sprinting lowers the gun, switching raises it, and it sways behind your mouse.
- Attachments show on the model: suppressor, red dot, vertical grip, extended mag.

Every gun has its own synthesized report (rifle, pistol, shotgun, a long sniper
crack with echo, the ruby blaster's zap, a muffled suppressed shot) that carries
across the map, plus magazine, bolt and shell sounds while reloading and brass
that tinkles when it lands. Bullets kick up debris, dust and sparks and leave
bullet holes for 30 seconds. Explosions throw ground debris and a dust ring and
rumble; far away you hear a low boom, delayed by the speed of sound. Tanks,
jeeps, jets and helicopters have engine sounds that follow their throttle.
Rounds that pass close crack past you and make you flinch, the screen edges
bleed red when you're badly hurt, and kill streaks of 3, 5, 7 and 10 get called
out (and so does ending someone else's). Base alarms and Heart attacks alert the
whole squad, and an alarm spots the intruder on your map. During a practice raid
a banner shows how many raiders are left and how long until they pull out, and
your daily bounties sit under the bank panel with progress bars.

The ammo panel shows the round count (amber when low, flashing red when empty),
reserve ammo, fire mode, a row of rounds and your attachments, and prompts you
to reload. The Armory has its own screen: page tabs, item cards with prices,
padlocks on gear that still needs research, and a detail panel with gun stats.

Tanks and jeeps are detailed animated models too: road wheels and sprockets that
turn, track links that move, a turret and gun that recoils with a muzzle flash,
smoke launchers, stowage and markings. Vehicle and aircraft items show the real
model in your inventory.

## Squads

Team up with up to 3 others (`squadMaxSize`): `/squad create <name>`,
`/squad invite <player>`, `/squad join <name>`, `/squad leave`, `/squad kick <name>`,
and `/sc <message>` for squad chat. Squadmates can't hurt each other, share
each other's claims, respawn at any standing squad Heart, see each other on the
map and through walls (name, distance and health), share pings and spotted
enemies, and share research. Squads lock when Battle Day starts, and Battle Day
ends when one squad (or one solo player) is left.

## Map, waypoints and pings

The minimap (top right) shows the terrain you've explored, claim borders (yours
green, your squad's blue, others red), squadmates, and markers: waypoints, your
grave, pings, supply drops and raids. K opens the full map. Markers also float in
the world with their distance. Right-click the full map to drop a waypoint (or remove one). `/waypoint add <name>` saves where you stand
(`/waypoint list`, `/waypoint remove <name>`). B pings a spot for your squad, or
spots an enemy for 10 seconds; eliminated players can still spot for their squad
while they spectate.

Near the edge of a claim (within 16 blocks), its border shows in the world as
glowing bands in the same colors.

## Look-at info

Look at a machine, defense, Heart, grave, vehicle, guard or squadmate and a
panel at the top of the screen shows what matters: owner, tier, status and grid
charge for machines, ammo for turrets, integrity for Hearts, armor and crew for
vehicles, the lock on a grave.

## Gear & Support (Armory page 4)

- **Field Pack / Heavy Rucksack:** backpacks with 27 or 54 slots. Right-click to open.
- **Field Radio:** needs Simple Voice Chat (in the modpack). Hold it and talk:
  everyone carrying a radio on the same channel hears you, at any range.
  Right-click to change channel (1-8). A captured radio lets the enemy listen in.
- **Guard Contracts:** hire a Rifleman or an AT Gunner. Right-click the ground to
  post them. They fight monsters and raiders any time and enemy players (and their
  guards) when PvP is on. Right-click your guard to switch between holding a post
  and following you; sneak + right-click packs them back up (not on Battle Day).
  Up to 4 each (`maxGuards`).
- **Recon Drone:** climbs over you and circles for 90 seconds, marking every enemy
  within 56 blocks for your squad. It can be shot down.
- **Paint Kit:** right-click a vehicle or aircraft to respray it: woodland, desert,
  arctic, urban, night or ruby.

## War Graves

When you die, everything you carry goes into a War Grave where you fell (an ammo
crate with your helmet and dog tags) instead of scattering. Only you can open it
for 5 minutes (`graveLockSeconds`), then anyone can loot it. It shows on your map,
and crumbles once it's empty.

## Combat logging

Hurting or being hurt by another player tags you both "in combat" for 15 seconds
(`combatTagSeconds`); the HUD shows the countdown. Log out while tagged when PvP
is on and you die where you stood: your gear goes to a War Grave and the death
counts.

## Practice raids

On build days 3, 6, 9 and 12 (`raidDays`), once you or a squadmate is online,
scouts warn you and a few minutes later a squad of AI raiders (4, 6 or 8 by Heart
tier) lands outside your claim and marches on your Heart. Every raider standing
at the Heart loots your bank each second (up to 20%). Kill them all within 6
minutes for a bonus (15 rubies, and the "Repel a raid" goal); each kill pays 3 rubies,
to you or to whoever's guard made it. Turrets fire on raiders even before Battle
Day. You can also call one on yourself once a day with `/rubywars raid`.

## Goals and upgrades

Advanced Armory gear starts locked. Upgrades come from **doing things**, never from
how long or how often you play. Press J:

- **GOALS** lists 19 things to do, each worth 1 or 2 stars, once per season: place
  your Heart, mine 10 and 50 Ruby Ore, build a generator, a Ruby Drill and a Trade
  Depot, get a machine to Mk III, bank 500 and 2,000 rubies, upgrade your Heart, buy
  from the Armory, place a base defense, join a squad, finish 3 bounties, repel a
  raid, win a duel (the practice bot counts), reach wave 5 in Co-op Horde, drive 300
  blocks, fly 1,000 blocks.
- **UPGRADES** is where you spend stars: four branches (Arms, Vehicles, Defense,
  Support), 16 unlocks costing 2 to 5 stars. There are fewer stars than the whole tree
  costs, so squads split it (squadmates share unlocks).

Basic guns, the Heart, base blocks, basic industry and basic defenses never need
upgrades. Locked Armory items say what unlocks them; supply drops can still hand you
locked gear. Turn it off with `"research": false`.

## 1v1 test duels

Try every weapon against a real person without touching the season:

    /duel guns <player>        every gun, rocket launcher, Lancer, Skyhammer, grenades, C4, air strike flare, recon drone
    /duel vehicles <player>    you start in a tank; jeep, railgun, Lancer, rocket launcher, C4 and AT mines in your bag
    /duel planes <player>      you start in a fighter on your runway; attack jet, bomber, attack helicopter,
                               Skyhammer AA launcher and a Heavy AA Gun in your bag
    /duel guns <player> 5      first to 5 instead of 10
    /duel guns bot [kills]     practice against an AI soldier that hunts you down (gun duels only)

They get a chat message with a clickable **[ACCEPT]** (or they type `/duel accept`).
Both of you go to the **Duel Arena**, a separate flat dimension, so nothing in the
season world gets blown up. Each mode has its own arena: a walled 60x60 yard with
cover and two watchtowers for guns, a 240x240 walled field with hills, ruins and
trees for vehicles, and two runways pointing at each other for planes.

- You spawn at opposite ends, with 4 seconds of spawn protection (it ends if you shoot).
- When you die you watch for 3 seconds, then respawn with a fresh kit (and a fresh
  tank or jet). Every death is a point for the other player, crashes included.
- The bar at the top shows the score. **First to 10 wins.** At the end everyone gets a
  scoreboard: kills, deaths, accuracy, damage, longest kill, best streak and favorite weapon.
- Every death (in duels or anywhere else) shows who got you, with what and from how far, and
  the camera watches through your killer's eyes for two seconds.
- Afterwards you both go back exactly where you were, with your own inventory.
  `/duel leave` quits early (the other player wins). If someone disconnects or the
  server stops mid-duel, they get their things back the next time they join.
- Duels don't count toward season kills, bounties, graves or combat tags, and PvP
  in the arena works in every phase except Sudden Death and the Final Circle
  (no duels then). `/duel list` shows who's duelling.

## Playing together (LAN or your own server)

Everyone needs the same version of the mod. The easiest way is the modpack:
`dist/ruby-wars-1.0.0.mrpack`. Each player installs the free
[Modrinth App](https://modrinth.com/app) (or Prism Launcher), clicks
*Add instance -> Import*, picks the .mrpack, and plays that instance. Send them a
new .mrpack whenever the mod changes.

**Same house, quickest (LAN):** one person opens a world (the training world works
well), presses Esc -> *Open to LAN* -> *Start LAN World*. Everyone else on the same
Wi-Fi opens *Multiplayer*; the world shows up under "Scanning for games on your local
network" (or *Direct Connect* to the host's IP and the port shown in chat). The world
only runs while the host plays.

**Always-on server (on a Windows PC):**

1. Install Java 25 (Temurin 25 from adoptium.net).
2. Make an empty folder, download the Fabric *server* launcher for Minecraft 26.3 from
   https://fabricmc.net/use/server/ into it, and double-click it (or run
   `java -Xmx6G -jar fabric-server-launch.jar nogui`). It stops once to ask you to
   accept the EULA: set `eula=true` in `eula.txt` and start it again.
3. Copy `rubymod-1.0.0.jar`, Fabric API (for 26.3) and, for voice chat, Simple Voice
   Chat into the server's `mods/` folder. Restart.
4. In the server console: `op <yourname>`. Players on your home network join with
   *Multiplayer -> Add Server* and the PC's local IP (`ipconfig` shows it, like
   192.168.1.20). Allow Java through Windows Firewall when it asks.
5. Friends outside your house need port 25565 (TCP) forwarded on your router (and UDP
   24454 for voice chat), or a tunnel such as playit.gg.

Players whose modpack doesn't match the server get a chat message with a clickable link to the
newest one when they join (each build carries a fingerprint of its code, so a stale modpack is
spotted automatically).

Backups: the server zips the world into `/opt/rubywars/server/backups` every 24 hours and keeps
7. `tools/pull-backups.ps1 -Install` (on the PC set up with `tools/vm-trust-this-pc.ps1`) copies
them to `Documents\Ruby Wars backups` daily, so a dead VM disk doesn't take the world with it.
The server also runs Chunky: `rubywars cmd "chunky start"` pregenerates terrain (see `chunky help`).

Then `/duel guns <sibling>` from anywhere on the server. Duels work in the lobby,
so you don't need to start a season to test weapons.

## Commands

Everyone: `/hub`, `/play rubywars|horde` (switch games; same as H), `/play horde easy|normal|hard`, `/horde shop`, `/horde ready`,
`/play gungame|hideseek|infection|ctf|koth|tanks|skyrace|build|rush` (party games), `/rubywars` (status), `/rubywars heart`, `/rubywars bounties`, `/rubywars trust <player>`,
`/rubywars untrust <player>`, `/rubywars champions`, `/rubywars raid` (practice raid on your
base, once a day), `/duel guns|vehicles|planes <player> [kills]` (1v1 test duel; see above),
`/squad ...`, `/sc <message>`, `/waypoint ...`.

Admins: `start`, `phase <lobby|build|lockdown|war|sudden_death|final_circle|ended>`,
`timer <minutes>`, `pause`, `resume`, `reload` (config), `backup`, `revive <player>`,
`reset confirm` (new season), `kit` (every gun, ammo, raid gear, attachments,
vehicles, aircraft, and ruby armor, for testing), `training` (build the training
ground here), `rearm` (refill your aircraft), `raidnow <player>` (launch a raid),
`research <player> <points>` (give stars), `selftest` (rule checks; use on a test world).

## Running the server (Linux)

**Quickest: the setup script** (Debian 12/13 or Ubuntu 22.04/24.04, e.g. a Proxmox VM).
It installs Java 25, builds the mod from this repo, downloads Fabric and the
server mods, and sets up a service that starts on boot and restarts daily at 5 AM:

    curl -fsSL https://raw.githubusercontent.com/gregrose0929/ruby-wars/main/tools/setup-server.sh -o setup-server.sh
    sudo MEMORY=6G OP=YourName bash setup-server.sh

Afterwards: `rubywars console` (Ctrl-A then D to leave), `rubywars cmd "<command>"`,
`rubywars logs`, `rubywars restart`, and `rubywars update` to pull and rebuild the
latest version. The server lives in `/opt/rubywars/server`.

**By hand:**

1. Install Java 25 (your distro's OpenJDK 25 package, or Temurin 25 from adoptium.net).
2. Download the Fabric server launcher for Minecraft 26.3 from https://fabricmc.net/use/server/
   into an empty folder, and run it once: `java -Xmx6G -jar fabric-server-launch.jar nogui`.
   Accept the EULA in `eula.txt`.
3. Put `rubymod-1.0.0.jar` and Fabric API (for 26.3) in the server's `mods/` folder.
4. Start it again. Give yourself op (`op <name>` in the console), then `/rubywars start`
   when everyone's ready.
5. Players need the mod too. The easiest way: send them `dist/ruby-wars-1.0.0.mrpack`
   (built with `python tools/make_mrpack.py`). They import it in Modrinth App or
   Prism Launcher, which installs Fabric, Fabric API, and Ruby Wars in one click, plus
   Sodium, Lithium, FerriteCore and Entity Culling (frame rate), Mouse Tweaks,
   AppleSkin and Simple Voice Chat.
6. For voice chat and Field Radios, also put Simple Voice Chat (for 26.3, from
   Modrinth) in the server's `mods/` folder and open UDP port 24454. Lithium and
   FerriteCore help the server too.

The mod writes a zipped world backup to `backups/` every 24 hours and keeps the
newest 7. Season state lives in `world/rubywars/season.json`.

To test a full season quickly, set short times in `config/rubywars.json`
(for example `"buildPhaseMinutes": 10`), run `/rubywars reload`, then `/rubywars start`.

## Building

Requires JDK 25.

    ./gradlew build        # jar in build/libs/
    ./gradlew runClient    # dev client
    tools/test-server.sh tools/tests/smoke.txt --fresh   # headless server test + /rubywars selftest
    python tools/make_mrpack.py   # dist/ruby-wars-<version>.mrpack for players
    tools/release.sh "notes"      # publish it on GitHub; players get the newest from
                                  # https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack
    tools/make-training-world.sh  # dist/Ruby Wars Training/ (flat world + /rubywars training)
    tools/client-test.sh planes   # scripted client run (guns|vehicles|strikes|planes|training|full)
                                  # screenshots in run/screenshots/, log in run/client-test.log

Art is generated: edit the pixel grids in `tools/textures.py` and models/data in
`tools/gen_assets.py`, then run them from the project root. `tools/recolor.py`
makes the ruby armor and tool textures by recoloring Minecraft's diamond ones;
swap in original art before publishing the mod publicly.
